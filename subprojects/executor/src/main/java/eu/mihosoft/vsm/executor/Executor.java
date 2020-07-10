package eu.mihosoft.vsm.executor;

import eu.mihosoft.vsm.model.*;

import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class Executor implements eu.mihosoft.vsm.model.Executor {

    private Deque<Event> evtQueue = new ConcurrentLinkedDeque<>();
    private Thread doActionThread;
    private CompletableFuture<Void> doActionFuture;

    private final FSM fsm;

    private Executor(FSM fsm) {
        this.fsm = fsm;
        this.fsm.setExecutor(this);
    }

    public static Executor newInstance(FSM fsm) {
        return new Executor(fsm);
    }

    private FSM getCaller(){return this.fsm;}

    public void trigger(String evt, EventConsumedAction onConsumed, Object... args) {

        Event event = Event.newBuilder().withName(evt).withArgs(args)
                .withAction(onConsumed)
                .build();

        trigger(event);
    }

    public void trigger(String evt, Object... args) {

        Event event = Event.newBuilder().withName(evt).withArgs(args)
                .build();

        trigger(event);
    }

    public void trigger(Event event) {
        evtQueue.add(event);
    }

    public boolean process(String evt, Object... args) {

        if(getCaller().isRunning()) {
            throw new RuntimeException(
                "Cannot call 'process()' if machine is already running,"+
                " try calling trigger(). The 'process()' method triggers and" +
                        " processes the event in a single method call.");
        }

        try {
            getCaller().setRunning(true);
            trigger(evt, args);
            return processRemainingEvents();
        } finally {
            getCaller().setRunning(false);
        }
    }

    private String level(FSM fsm) {

        String result = fsm.getName();
        FSMState parent = fsm.getParentState();

        while(parent!=null) {
            try {
                result = parent.getOwningFSM().getName() + "|" + result;
                parent = parent.getOwningFSM().getParentState();
            } catch(NullPointerException ex) {
                break;
            }

        }

        return result;
    }

    public boolean processRemainingEvents() {
        boolean consumed = false;

        // set current state to initial state if current state is null
        if(getCaller().getCurrentState()==null) {
            performStateTransition(
                    Event.newBuilder().withName("fsm:init").build(),
                    null,
                    getCaller().getInitialState(),
                    null
            );
        }

        for(Iterator<Event> iter = evtQueue.iterator(); iter.hasNext() && getCaller().isRunning(); ) {

            Event evt = iter.next();
            State currentState = getCaller().getCurrentState();

            if(getCaller().isVerbose()) {
                log("> try-consume: " + evt.getName() + (evt.isDeferred() ? " (previously deferred)" : ""));
                log("  -> in state: " + level(getCaller()) + ":" + currentState.getName());
            }

            // if we are in a state with nested fsm we try to consume the event in the nested machine
            // before we try to consume it on the current level.
            if(currentState instanceof FSMState) {
                FSMState fsmState = (FSMState) currentState;
                for(FSM childFSM : fsmState.getFSMs() ) {
                    if (childFSM != null) {

                        // create a new execute for child fsm if it doesn't exist yet
                        if (childFSM.getExecutor() == null) {
                            getCaller().getExecutor().newChild(childFSM);
                        }

                        // if we consumed it then break and return that we successfully consumed the event
                        childFSM.getExecutor().reset();
                        childFSM.setRunning(true);
                        childFSM.getExecutor().trigger(evt.getName(), evt.getArgs().toArray(new Object[evt.getArgs().size()]));

                        if (childFSM.getExecutor().processRemainingEvents()) {
                            log(" -> consumed");
                            consumed = true;
                            iter.remove();
                            break;
                        } else {
                            log(" -> reset");
                            childFSM.getExecutor().reset();
                        }
                        childFSM.setRunning(false);
                        //log("inner STEP: done.");
                    }
                } // end for each child fsm
            }

            Transition consumer = currentState.getOutgoingTransitions().
                    stream().filter(t -> Objects.equals(t.getTrigger(), evt.getName())).findFirst().orElse(null);

            boolean guardMatches;

            if(consumer!=null&&!guardMatches(consumer, evt)) {
                guardMatches = false;
                log("  -> guard of potential consumer does not match: " + level(getCaller()));
            } else {
                guardMatches = true;
            }       

            if(consumer!=null && guardMatches) {

                log("  -> found consumer: " + level(getCaller()) + ":" + consumer.getTarget().getName());
                log("     on-thread:      " + Thread.currentThread().getName());

                performStateTransition(evt, consumer.getSource(), consumer.getTarget(), consumer);

                if(getCaller().getFinalState().contains(getCaller().getCurrentState())) {
                    log("  -> final state reached. stopping.");

                    getCaller().setRunning(false);
                    break;
                }

                if(evt.getAction()!=null) {
                    evt.getAction().execute(evt, consumer);
                }

                // if we consume the current event, pop the corresponding entry in the queue
               if(!consumed) iter.remove();

                consumed = true;
            } else if(!consumed) {
                if(guardMatches && defers(getCaller().getCurrentState(), evt)) {
                    log("  -> deferring: " + evt.getName());
                    evt.setDeferred(true);
                } else {
                    log("  -> discarding unconsumed event: " + evt.getName());
                    // discard event (not deferred)
                    iter.remove();
                }
            }

        } // end for

        return consumed;
    }

    private void handleExecutionError(Event evt, State oldState, State newState, Exception ex) {
        if(getCaller().getErrorState()!=null) {
            performStateTransition(
                    Event.newBuilder()
                            .withName("fsm:error")
                            .withArgs(ex, evt)
                            .build(),
                    oldState, getCaller().getErrorState(), null);
        } else {
            throw new RuntimeException("Action cannot be executed", ex);
        }
    }

    private void performStateTransition(Event evt, State oldState, State newState, Transition consumer) {

        if(oldState==newState && (consumer==null?false:consumer.isLocal())) {
            return; // don't perform enter & exit actions
        }

        // exit do-action of oldState
        if (!exitDoActionOfOldState(evt, oldState, newState)) return;

        // execute transition action
        if(consumer!=null) {
            consumer.getActions().forEach(action -> {
                try {
                    // execute action
                    action.execute(consumer, evt);
                } catch (Exception ex) {
                    handleExecutionError(evt, consumer.getSource(), consumer.getTarget(), ex);
                    return;
                }
            });
        }

        // execute on-entry action
        try {
            StateAction entryAction = newState.getOnEntryAction();
            if(entryAction!=null) {
                entryAction.execute(newState, evt);
            }

        } catch(Exception ex) {
            handleExecutionError(evt, oldState, newState, ex);
            return;
        }

        // execute do-action
        if (!executeDoActionOfNewState(evt, oldState, newState)) return;

        // transition done, set new current state
        getCaller().setCurrentState(newState);

        // trigger event in children (nested fsm regions)
        // triggerEventInChildren(evt, newState);
    }

//    private void triggerEventInChildren(Event evt, State newState) {
//        if(newState instanceof FSMState) {
//            FSMState fsmState = (FSMState) newState;
//
//            for(FSM childFSM : fsmState.getFSMs()) {
//
//                // process the event in the nested machine
//                if (childFSM != null) {
//
//                    // create a new execute for child fsm if it doesn't exist yet
//                    if (childFSM.getExecutor() == null) {
//                        getCaller().getExecutor().newChild(childFSM);
//                    }
//
//                    childFSM.getExecutor().process(evt.getName(), evt.getArgs().
//                            toArray(new Object[evt.getArgs().size()])
//                    );
//                }
//            }
//        }
//    }

    private boolean executeDoActionOfNewState(Event evt, State oldState, State newState) {
        try {
            StateAction doAction = newState.getDoAction();
            if(doAction!=null) {
                Runnable doActionDone = ()->{
                    getCaller().getExecutor().trigger("fsm:on-do-action-done", newState);
                };
                doActionFuture = new CompletableFuture<>();
                doActionThread = new Thread(()->{
                    try {
                        doAction.execute(newState, evt);
                    } catch(Exception ex) {
                        handleExecutionError(evt, oldState, newState, ex);
                        return;
                    }
                    doActionFuture.complete(null);
                    if(!Thread.currentThread().isInterrupted()) {
                        doActionDone.run();
                    }
                });
                doActionThread.start();
            } else {
                // no do-action means, we are done after onEnter()
                getCaller().getExecutor().trigger("fsm:on-do-action-done", newState);
            }
        } catch(Exception ex) {
            handleExecutionError(evt, oldState, newState, ex);
            return false;
        }
        return true;
    }

    private boolean exitDoActionOfOldState(Event evt, State oldState, State newState) {
        if(oldState!=null) {
            try {
                if (doActionThread != null && doActionFuture != null) {
                    doActionThread.interrupt();
                    doActionFuture.get(
                            Math.min(
                                    getCaller().getMaxCancellationTimeout().toMillis(),
                                    oldState.getCancellationTimeout().toMillis()
                            ),
                            TimeUnit.MILLISECONDS);
                }
            } catch (Exception ex) {
                doActionThread = null;
                doActionFuture = null;
                handleExecutionError(evt, oldState, newState, ex);
            } finally {
                doActionThread = null;
                doActionFuture = null;
            }

            try {
                StateAction exitAction = oldState.getOnExitAction();
                if(exitAction!=null) {
                    exitAction.execute(oldState, evt);
                }
            } catch(Exception ex) {
                handleExecutionError(evt, oldState, newState, ex);
                return false;
            } finally {
                //
            }

            if(oldState instanceof FSMState) {
                FSMState fsmState = (FSMState) oldState;
                for(FSM childFSM : fsmState.getFSMs()) {
                    Executor executor = (Executor) childFSM.getExecutor();
                    executor.exitDoActionOfOldState(evt, childFSM.getCurrentState(), null);
                }
            }
        } // end if oldState != null
        return true;
    }

    private boolean guardMatches(Transition consumer, Event evt) {
        if(consumer.getGuard()==null) return true;
        try {
            return consumer.getGuard().test(consumer, evt);
        } catch (Exception ex) {
            handleExecutionError(evt, consumer.getSource(), consumer.getTarget(), ex);
        }

        return false;
    }

    private boolean defers(State s, Event evt) {
        return s.getDeferredEvents().stream().anyMatch(dE->Objects.equals(dE, evt.getName()))
            || s.getDeferredEvents().stream().anyMatch(dE-> Pattern.matches(dE, evt.getName()));
    }

    public void startAndWait() {
        getCaller().getExecutor().reset();
        getCaller().setRunning(true);
        while(getCaller().isRunning()&&!Thread.currentThread().isInterrupted()) {
            try {
                getCaller().getExecutor().processRemainingEvents();
            } catch (Exception ex) {
                Thread.currentThread().interrupt();
                throw ex;
            }
        }
    }

    public Thread startAsync() {
        Thread t = new Thread(()->{
            startAndWait();
        });
        t.start();
        return t;
    }

    public void reset() {
        evtQueue.clear();
    }

    public void stop() {
        getCaller().setRunning(false);
        reset();
    }

    private void log(String msg) {
        if(getCaller().isVerbose()) {
            System.out.println(msg);
        }
    }

    @Override
    public eu.mihosoft.vsm.model.Executor newChild(FSM fsm) {
        return new Executor(fsm);
    }
}
