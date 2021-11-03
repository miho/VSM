/*
 * Copyright 2019-2021 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vsm.executor;

import eu.mihosoft.vsm.model.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An async executor for state machines.
 */
public class Executor implements eu.mihosoft.vsm.model.AsyncExecutor {

    private final Deque<Event> evtQueue = new ConcurrentLinkedDeque<>();
    private volatile Thread doActionThread;
    private volatile CompletableFuture<Void> doActionFuture;
    private volatile Thread executionThread;
    private final Map<State, Boolean> stateExited = new HashMap<>();
    private final int depth;
    private final FSM fsm;
    private final ReentrantLock fsmLock = new ReentrantLock();
    private final ReentrantLock eventLock = new ReentrantLock();

    private final List<Executor> pathToRoot = new ArrayList<>();

    private final AsyncExecutor.ExecutionMode mode;

    private static final long MAX_EVT_CONSUMED_ACTION_TIMEOUT = 10_000 /*ms*/;
    private static final long MAX_ENTER_ACTION_TIMEOUT        = 10_000 /*ms*/;
    private static final long MAX_EXIT_ACTION_TIMEOUT         = 10_000 /*ms*/;
    private static final long MAX_TRANSITION_ACTION_TIMEOUT   = 10_000 /*ms*/;

    private static Optional<Executor> getLCA(Executor a, Executor b) {
        int start = Math.min(a.pathToRoot.size(), b.pathToRoot.size());

        for(int i = start; i >=0; i++) {
            var ancestorOfA = a.pathToRoot.get(i);
            var ancestorOfB = b.pathToRoot.get(i);
            if(ancestorOfA == ancestorOfB) return Optional.of(ancestorOfA);
        }

        return Optional.empty();
    }

    private Executor(FSM fsm, ExecutionMode mode, int depth, Executor parent) {
        this.fsm = fsm;
        this.mode = mode;
        this.fsm.setExecutor(this);
        this.depth = depth;
        if(parent!=null) {
            pathToRoot.addAll(parent.pathToRoot);
        }
        pathToRoot.add(this);
    }

    /**
     * Creates a new async executor instance.
     * @param fsm the fsm to execute
     * @param mode the execution mode
     * @return the new executor instance
     */
    public static Executor newInstance(FSM fsm, ExecutionMode mode) {
        return new Executor(fsm, mode, 0, null);
    }

    private int getDepth() {
        return this.depth;
    }

    private FSM getCaller(){return this.fsm;}

    @Override
    public void trigger(String evt, EventConsumedAction onConsumed, Object... args) {

        Event event = Event.newBuilder().withName(evt).withArgs(args)
                .withAction(onConsumed)
                .withTimeStamp(System.currentTimeMillis())
                .build();

        trigger(event);
    }

    @Override
    public void trigger(Event event) {

        if(event.isConsumed()) throw new IllegalArgumentException("Cannot trigger consumed event: " + event.getName());

        if(event.getTimeStamp()<=0) event.setTimeStamp(System.currentTimeMillis());
        evtQueue.add(event);

        if(executionThread!=null) {
            synchronized(executionThread) {
                executionThread.notify();
            }
        }
    }

    @Override
    public void triggerFirst(Event event) {

        if(event.isConsumed()) throw new IllegalArgumentException("Cannot trigger consumed event: " + event.getName());

        if(event.getTimeStamp()<=0) event.setTimeStamp(System.currentTimeMillis());
        evtQueue.addFirst(event);

        if(executionThread!=null) {
            synchronized(executionThread) {
                executionThread.notify();
            }
        }
    }

    @Override
    public boolean process(Event evt) {

        if(executorRunning.get()) {
            throw new RuntimeException(
                    "Cannot call 'process()' if machine is already running,"+
                            " try calling trigger(). The 'process()' method triggers and" +
                            " processes the event in a single method call.");
        }

        try {
            trigger(evt);
            return processRemainingEvents();
        } finally {
            //
        }
    }

    @Override
    public boolean process(String evt, EventConsumedAction onConsumed, Object... args) {

        if(executorRunning.get()) {
            throw new RuntimeException(
                    "Cannot call 'process()' if machine is already running,"+
                            " try calling trigger(). The 'process()' method triggers and" +
                            " processes the event in a single method call.");
        }

        try {
            trigger(evt, onConsumed, args);
            return processRemainingEvents();
        } finally {
            //
        }
    }

    @Override
    public boolean process(String evt, Object... args) {

        if(executorRunning.get()) {
            throw new RuntimeException(
                    "Cannot call 'process()' if machine is already running,"+
                            " try calling trigger(). The 'process()' method triggers and" +
                            " processes the event in a single method call.");
        }

        try {
            trigger(evt, args);
            return processRemainingEvents();
        } finally {
            //
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


    @Override
    public void accessFSMSafe(Consumer<FSM> fsmTask) {
        fsmLock.lock();
        try {
            fsmTask.accept(getCaller());
        } finally {
            fsmLock.unlock();
        }
    }

    private volatile boolean firedFinalState   = false;
    private volatile boolean firedDoActionDone = false;
    private volatile boolean firedStateDone    = false;

    @Override
    public boolean processRemainingEvents() {

        // everything modified concurrently with start(), reset(), stop() etc. must be inside
        // locked code block
        fsmLock.lock();
        try {
            if (!getCaller().isRunning()) return false;
            if (getCaller().getOwnedState().isEmpty()) return false;

            // set current state to initial state if current state is null
            if(getCaller().getCurrentState()==null) {
                firedDoActionDone = false;
                firedFinalState   = false;
                firedStateDone    = false;
                performStateTransition(
                        Event.newBuilder().withName(FSMEvents.INIT.getName()).build(),
                        null,
                        getCaller().getInitialState(),
                        null
                );
            }

        } finally {
            fsmLock.unlock();
        }


        boolean consumed = false;
        State prevState = getCaller().getCurrentState();


        // if we are in a state with nested fsm we process any upcoming events even if we don't
        // currently have events in our queue
        if (prevState instanceof FSMState) {
            FSMState fsmState = (FSMState) prevState;
            for (FSM childFSM : fsmState.getFSMs()) {
                if (childFSM != null) {
                    childFSM.getExecutor().processRemainingEvents();
                }
            } // end for each child fsm

            boolean allMatch = fsmState.getFSMs().stream()
                    .allMatch(fsm -> !fsm.isRunning() && fsm.getFinalState().contains(fsm.getCurrentState()));

            if (allMatch && !firedFinalState) {
                log("> triggering final-state, currently in state " + prevState.getName());
                triggerFirst(Event.newBuilder().withName(FSMEvents.FINAL_STATE.getName()).withLocal(true)
                        .withArgs(fsmState.getName() + ":" + System.identityHashCode(fsmState)).build());
                firedFinalState = true;
                log(" -> final state reached via: "
                        + fsmState.getFSMs().stream().map(cfsm -> cfsm.getName()).collect(Collectors.toList()));
            }
        }


        for (Iterator<Event> iter = evtQueue.iterator(); iter.hasNext() && getCaller().isRunning(); ) {

            fsmLock.lock();
            try {
                Event evt = iter.next();
                boolean removed = false;
                State currentState = getCaller().getCurrentState();
                boolean stateChanged = currentState!=prevState;

                if(stateChanged) {
                    firedDoActionDone = false;
                    firedFinalState   = false;
                    firedStateDone    = false;
                }

                prevState = currentState;

                if (getCaller().isVerbose()) {
                    if(getCaller().isVerbose()) {
                        log("> try-consume: " + evt.getName() + ", timestamp: "
                            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS").
                            format(new Date(evt.getTimeStamp()))
                            + ", " + (evt.isDeferred() ? " (previously deferred), " : "") + "fsm: "
                            + level(getCaller())
                        );
                        log("  -> in state: " + level(getCaller()) + ":"
                            + (currentState == null ? "<undefined>" : currentState.getName())
                        );
                    }
                }

                // handle errors
                if(FSMEvents.ERROR.getName().equals(evt.getName())) {
                    handleExecutionError((Event)evt.getArgs().get(0),
                            (State) evt.getArgs().get(1),
                            (State) evt.getArgs().get(2),
                            (Exception) evt.getArgs().get(3));

                    if (getCaller().getFinalState().contains(getCaller().getCurrentState())) {
                        log("  -> final state reached. stopping.");
                        exitDoActionOfOldState(evt, getCaller().getCurrentState(), null);
                        getCaller().setRunning(false);
                    }
                }

                // if we are in a state with nested fsm we try to consume the event in the nested machine
                // before we try to consume it on the current level.
                AtomicBoolean consumedParam = new AtomicBoolean(consumed);
                AtomicBoolean removedParam  = new AtomicBoolean(removed);
                boolean isFSMState = (currentState instanceof FSMState);
                if (isFSMState) {
                    processRegions(iter, evt, (FSMState) currentState, consumedParam, removedParam);
                    removed = removedParam.get();
                    consumed = consumedParam.get();
                } else {
                    //
                }

                if(FSMEvents.FINAL_STATE.getName().equals(evt.getName())) {
                    firedFinalState = true;
                }

                if(FSMEvents.DO_ACTION_DONE.getName().equals(evt.getName())) {
                    firedDoActionDone = true;
                }

                if(!firedStateDone && (firedFinalState || !isFSMState) && firedDoActionDone) {
                    log("> triggering state-done, currently in state " + currentState.getName());
                    triggerFirst(Event.newBuilder().withName(FSMEvents.STATE_DONE.getName()).withLocal(true)
                            .withArgs(currentState.getName()+":"+System.identityHashCode(currentState)).build());
                    firedStateDone = true;
                }

                // children consumed event
                if (consumed) {
                    continue;
                }

                var consumers = currentState.getOutgoingTransitions().
                        stream().filter(t -> Objects.equals(t.getTrigger(), evt.getName())).
                        collect(Collectors.toList());

                Transition consumer = consumers.stream().filter(c->guardMatches(c, evt)).findFirst().orElse(null);

                boolean guardMatches;

                if (consumer != null && !guardMatches(consumer, evt)) {
                    guardMatches = false;
                    log("  -> guard of potential consumer does not match: " + level(getCaller()));
                } else {
                    guardMatches = true;
                }

                if (consumer != null && guardMatches) {

                    if (consumer.getTarget() == null) {
                        handleExecutionError(evt, consumer.getSource(), consumer.getTarget(),
                                new RuntimeException("Cannot process transitions without target: " + consumer)
                        );
                    }

                    log("  -> found consumer: " + level(getCaller()) + ":" + consumer.getTarget().getName());
                    log("     on-thread:      " + Thread.currentThread().getName());

                    {
                        prevState = currentState;
                        performStateTransition(evt, consumer.getSource(), consumer.getTarget(), consumer);
                        stateChanged = prevState != getCaller().getCurrentState();
                        if (stateChanged) {
                            firedDoActionDone = false;
                            firedFinalState = false;
                            firedStateDone = false;
                        }
                    }

                    if (getCaller().getFinalState().contains(getCaller().getCurrentState())) {
                        log("  -> final state reached. stopping.");
                        exitDoActionOfOldState(evt, getCaller().getCurrentState(), null);
                        getCaller().setRunning(false);
                    }

                    // if we consume the current event, pop the corresponding entry in the queue
                    if (!consumed) {

                        eventLock.lock();
                        try {
                            if (!removed) {
                                iter.remove();
                            }
                            consumed = true;
                            evt.setConsumed(true);
                        } finally {
                            eventLock.unlock();
                        }

                        if (evt.getAction() != null) {
                            // evt.getAction().execute(evt, consumer);
                            fsmLock.unlock();
                            try {
                                CompletableFuture.runAsync(() -> {
                                    fsmLock.lock();
                                    try {
                                        evt.getAction().execute(evt, consumer);
                                    } finally {
                                        fsmLock.unlock();
                                    }
                                }).orTimeout(MAX_EVT_CONSUMED_ACTION_TIMEOUT, TimeUnit.MILLISECONDS).join();
                            } finally {
                                fsmLock.lock();
                            }
                        }

                    }

                } else if (!consumed) {
                    if (guardMatches && defers(getCaller().getCurrentState(), evt)) {
                        log("  -> deferring: " + evt.getName());
                        eventLock.lock();
                        try {
                            evt.setDeferred(true);
                        } finally {
                            eventLock.unlock();
                        }
                    } else {
                        log("  -> discarding unconsumed event: " + evt.getName() + " in FSM " + level(getCaller()));
                        // discard event (not deferred)
                        eventLock.lock();
                        try {
                            if (!removed) {
                                iter.remove();
                            }
                        } finally {
                            eventLock.unlock();
                        }
                    }
                }
            } finally {
                fsmLock.unlock();
            }

        } // end for

        return consumed;
    }

    private void processRegions(Iterator<Event> iter, Event event,
                                FSMState currentState,
                                AtomicBoolean consumedParam,
                                AtomicBoolean removedParam) {
        FSMState fsmState = currentState;
        var threads = new ArrayList<Thread>();

        var consumed = new AtomicBoolean();
        for (FSM childFSM : fsmState.getFSMs()) {
            final var evt = event.clone();
            evt.setConsumed(false);
            Runnable r = () -> {

                    if (evt != null && !evt.isLocal()) {
                        // trigger in child fsm if not local to our fsm
                        // Event must not be modified concurrently if this runs in multiple threads
                        childFSM.getExecutor().trigger(evt);
                    }

                    // process event of non-local and potential internal events
                    childFSM.getExecutor().processRemainingEvents();

                    eventLock.lock();
                    try {
                        if(evt.isConsumed()) {
                            consumed.set(true);
                        }
                        // if we consumed it then remove it
                        if (evt != null && !removedParam.get() && evt.isConsumed()) {
                            iter.remove();
                            removedParam.set(true);
                        }
                    } finally {
                        eventLock.unlock();
                    }
            };
            if(mode == ExecutionMode.PARALLEL_REGIONS) {
                Thread thread = new Thread(r);
                thread.start();
                threads.add(thread);
            } else if(mode == ExecutionMode.SERIAL_REGIONS) {
                r.run();
            } else {
                throw new RuntimeException("Unknown execution mode: " + mode);
            }
        } // end for each child fsm

        threads.forEach(t-> {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        consumedParam.set(consumed.get());

        if(event!=null&&consumedParam.get()) {
            log(" -> consumed " + event.getName());
        }

        boolean allMatch = fsmState.getFSMs().stream()
                .allMatch(fsm->!fsm.isRunning()&&fsm.getFinalState().contains(fsm.getCurrentState()));

        if(allMatch && !firedFinalState) {
            log("> triggering final-state, currently in state " + currentState.getName());
            triggerFirst(Event.newBuilder().withName(FSMEvents.FINAL_STATE.getName()).withLocal(true)
                    .withArgs(currentState.getName()+":"+System.identityHashCode(currentState)).build());
            firedFinalState = true;
            log(" -> final state reached via: "
                + fsmState.getFSMs().stream().map(cfsm->cfsm.getName()).collect(Collectors.toList()));
        }
    }

    private void handleExecutionError(Event evt, State oldState, State newState, Exception ex) {
        if(getCaller().getErrorState()!=null) {
            performStateTransition(
                    Event.newBuilder()
                            .withName(FSMEvents.ERROR.getName())
                            .withArgs(ex, evt)
                            .build(),
                    oldState, getCaller().getErrorState(), null);
        } else {

            // try to propagate to parent fsm because no error state found

            boolean parentPresent = getCaller().getParentState()!=null;

            if(!parentPresent) {
                // no parent present, throwing exception (not handled by the fsm)
                throw new RuntimeException("Action cannot be executed", ex);
            }

            eu.mihosoft.vsm.model.Executor parentExecutor = getCaller().getParentState().getOwningFSM().getExecutor();
            parentExecutor.triggerFirst(Event.newBuilder().withName(FSMEvents.ERROR.getName()).
                    withArgs(evt, oldState, newState, ex).build());

        }
    }

    private List<State> pathToRootExcluding(State state) {
        List<State> result = new ArrayList<>();

        while(state.getOwningFSM().getParentState()!=null) {
            state = state.getOwningFSM().getParentState();
            result.add(state);
        }
        return result;
    }

    private boolean contains(State container, State contained) {
        return container.vmf().content().stream(State.class).anyMatch(s->s==contained);
    }

    private void performStateTransition(Event evt, State oldState, State newState, Transition consumer) {

        var exitOldStateList = new ArrayList<State>();
        var enterNewStateList = new ArrayList<State>();

        // compute LCA of oldState and newState
        if(oldState!=null && newState!=null) {

            var pathToRootSrc = pathToRootExcluding(oldState);
            var pathToRootDst = pathToRootExcluding(newState);

            int maxSize = Math.max(pathToRootSrc.size(),pathToRootDst.size());

            for(int i = 0; i < maxSize; i++) {
                State srcParent;
                if(i<pathToRootSrc.size()) {
                    srcParent = pathToRootSrc.get(i);
                } else {
                    srcParent = null;
                }

                State dstParent;
                if(i<pathToRootDst.size()) {
                    dstParent = pathToRootDst.get(i);

                } else {
                    dstParent = null;
                }

                if(srcParent!=null && dstParent!=null && srcParent == dstParent) {
                    //LCA found
                    break;
                } else {
                    if(srcParent!=null) {
                        exitOldStateList.add(srcParent);
                    }
                    if(dstParent!=null) {
                        enterNewStateList.add(dstParent);
                    }
                }
            }
        }

        boolean enterAndExit = !(oldState == newState && (consumer == null ? false : consumer.isLocal()));

        if (enterAndExit){
            // exit do-action of oldState
            if (!exitDoActionOfOldState(evt, oldState, newState)) return;

            // exit do-action and state ancestors until we reach direct children of LAC(oldState, newState)
            for(State s : exitOldStateList) {
                if (!exitDoActionOfOldState(evt, s, newState)) return;
            }
        }

        // execute transition action
        if(consumer!=null) {
            consumer.getActions().forEach(action -> {
                try {

                    // execute action
                    // action.execute(consumer, evt);
                    fsmLock.unlock();
                    CompletableFuture.runAsync(()->{
                        fsmLock.lock();
                        try {
                            action.execute(consumer, evt);
                        } finally {
                            fsmLock.unlock();
                        }
                    }).orTimeout(MAX_TRANSITION_ACTION_TIMEOUT,TimeUnit.MILLISECONDS).get();
                } catch (Exception ex) {
                    handleExecutionError(evt, consumer.getSource(), consumer.getTarget(), ex);
                    return;
                } finally {
                    fsmLock.lock();
                }
            });
        }

        if (enterAndExit) {

            // enter do-action and state ancestors from direct child of LAC(oldState, newState) to newState
            for(State s : enterNewStateList) {
                try {

                    // execute entry-action
                    StateAction entryAction = s.getOnEntryAction();
                    if (entryAction != null) {

                        try {
                            fsmLock.unlock();
                            //entryAction.execute(s, evt);
                            CompletableFuture.runAsync(() -> {
                                fsmLock.lock();
                                try {
                                    entryAction.execute(s, evt);
                                } finally {
                                    fsmLock.unlock();
                                }
                            }).orTimeout(MAX_ENTER_ACTION_TIMEOUT, TimeUnit.MILLISECONDS).get();
                        } finally {
                            fsmLock.lock();
                        }
                    }

                    if (!executeDoActionOfNewState(evt, s, newState)) return;

                    // enter children states
                    if(enterAndExit &&  s instanceof FSMState) {
                        FSMState fsmState = (FSMState)  s;
                        for(FSM childFSM : fsmState.getFSMs()) {
                            fsmLock.lock();
                            try {
                                // create a new execute for child fsm if it doesn't exist yet
                                if (childFSM.getExecutor() == null) {
                                    getCaller().getExecutor().newChild(childFSM);
                                }
                                eu.mihosoft.vsm.model.Executor executor = childFSM.getExecutor();
                                executor.reset();
                                childFSM.setRunning(true);
                            } finally {
                                fsmLock.unlock();
                            }
                        }
                    }

                } catch (Exception ex) {
                    handleExecutionError(evt, oldState, newState, ex);
                    return;
                }
            }

            // execute on-entry action
            try {
                StateAction entryAction = newState.getOnEntryAction();
                if (entryAction != null) {
                    try {
                        fsmLock.unlock();
                        //entryAction.execute(newState, evt);
                        CompletableFuture.runAsync(() -> {
                            fsmLock.lock();
                            try {
                                entryAction.execute(newState, evt);
                            } finally {
                                fsmLock.unlock();
                            }
                        }).orTimeout(MAX_ENTER_ACTION_TIMEOUT, TimeUnit.MILLISECONDS).get();
                    } finally {
                        fsmLock.lock();
                    }
                }

            } catch (Exception ex) {
                handleExecutionError(evt, oldState, newState, ex);
                return;
            }

            // execute do-action
            if (!executeDoActionOfNewState(evt, oldState, newState)) return;

        }

        // enter children states
        if(enterAndExit && newState instanceof FSMState) {
            FSMState fsmState = (FSMState) newState;
            for(FSM childFSM : fsmState.getFSMs()) {

                fsmLock.lock();
                try {
                    // create a new execute for child fsm if it doesn't exist yet
                    if (childFSM.getExecutor() == null) {
                        getCaller().getExecutor().newChild(childFSM);
                    }

                    eu.mihosoft.vsm.model.Executor executor = childFSM.getExecutor();
                    executor.reset();
                    executor.accessFSMSafe((cfsm)->{
                        cfsm.setRunning(true);
                    });
                } finally {
                    fsmLock.unlock();
                }
            }
        }

        // transition done, set new current state
        fsmLock.lock();
        try {
            getCaller().setCurrentState(newState);
            stateExited.put(newState, false);
        } finally {
            fsmLock.unlock();
        }
    }


    private boolean executeDoActionOfNewState(Event evt, State oldState, State newState) {
        try {
            StateAction doAction = newState.getDoAction();
            if(doAction!=null) {
                Runnable doActionDone = ()->{
                    triggerFirst(Event.newBuilder().withName(FSMEvents.DO_ACTION_DONE.getName()).withLocal(true)
                            .withArgs(newState.getName()+":"+System.identityHashCode(newState)).build());
                };

                try {
                    doActionFuture = new CompletableFuture<>();
                    doActionThread = new Thread(() -> {
                        try {
                            doAction.execute(newState, evt);
                        } catch (Exception ex) {
                            handleExecutionError(evt, oldState, newState, ex);
                            return;
                        } finally {
                            //
                        }
                        doActionFuture.complete(null);
                        if (!Thread.currentThread().isInterrupted()) {
                            doActionDone.run();
                        }
                    });
                    doActionThread.start();
                } finally {
                    //
                }
            } else {
                // no do-action means, we are done after onEnter()
                triggerFirst(Event.newBuilder().withName(FSMEvents.DO_ACTION_DONE.getName()).withLocal(true)
                        .withArgs(newState.getName()+":"+System.identityHashCode(newState)).build());
            }
        } catch(Exception ex) {
            handleExecutionError(evt, oldState, newState, ex);
            return false;
        }
        return true;
    }

    @Override
    public void exitDoActionOfState(Event evt, State state) {
        this.exitDoActionOfOldState(evt, state, null);
    }

    private boolean exitDoActionOfOldState(Event evt, State oldState, State newState) {

        if (oldState != null && !(stateExited.get(oldState) == null ? false : stateExited.get(oldState))) {

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

            // exit children states
            if (oldState instanceof FSMState) {
                FSMState fsmState = (FSMState) oldState;
                for (FSM childFSM : fsmState.getFSMs()) {
                    eu.mihosoft.vsm.model.Executor executor = childFSM.getExecutor();
                    executor.accessFSMSafe(fsmc -> {
                        executor.exitDoActionOfState(evt, childFSM.getCurrentState());
                    });
                }
            }

            try {
                StateAction exitAction = oldState.getOnExitAction();
                if (exitAction != null) {
                    try {
                        fsmLock.unlock();
                        // exitAction.execute(oldState, evt);
                        CompletableFuture.runAsync(() -> {
                            fsmLock.lock();
                            try {
                                exitAction.execute(oldState, evt);
                            } finally {
                                fsmLock.unlock();
                            }
                        }).orTimeout(MAX_EXIT_ACTION_TIMEOUT, TimeUnit.MILLISECONDS).get();
                    } finally {
                        fsmLock.lock();
                    }
                }
            } catch (Exception ex) {
                // mark as exited, because exit action already failed (prevents stack-overflow)
                stateExited.put(oldState, true);
                handleExecutionError(evt, oldState, newState, ex);
                return false;
            } finally {
                stateExited.put(oldState, true);
            }

        } // end if oldState != null

        return true;

    }

    private boolean guardMatches(Transition consumer, Event evt) {

        if(FSMEvents.DO_ACTION_DONE.getName().equals(consumer.getTrigger())) {
            return checkGuardOfStateEvents(consumer, evt);
        }

        if(FSMEvents.STATE_DONE.getName().equals(consumer.getTrigger())) {
            return checkGuardOfStateEvents(consumer, evt);
        }

        if(FSMEvents.FINAL_STATE.getName().equals(consumer.getTrigger())) {
            return checkGuardOfStateEvents(consumer, evt);
        }

        if(consumer.getGuard()==null) return true;
        try {
            return consumer.getGuard().test(consumer, evt);
        } catch (Exception ex) {
            handleExecutionError(evt, consumer.getSource(), consumer.getTarget(), ex);
        }

        return false;
    }

    private boolean checkGuardOfStateEvents(Transition consumer, Event evt) {
        boolean guard = consumer.getGuard()==null;
        try {
            if(!guard) guard = consumer.getGuard().test(consumer, evt);
        } catch (Exception ex) {
            handleExecutionError(evt, consumer.getSource(), consumer.getTarget(), ex);
        }

        return guard && Objects.equals(evt.getArgs().get(0),
                consumer.getSource().getName()+":"+System.identityHashCode(consumer.getSource()));
    }

    private boolean defers(State s, Event evt) {
        return s.getDeferredEvents().stream().anyMatch(dE->Objects.equals(dE, evt.getName()))
            || s.getDeferredEvents().stream().anyMatch(dE-> Pattern.matches(dE, evt.getName()));
    }

    @Override
    public void startAndWait() {

        var f = new CompletableFuture();
        accessFSMSafe((fsm)->{
            try {
                getCaller().getExecutor().reset();
                getCaller().setRunning(true);
                f.complete(null);
            } catch(Exception ex) {
                f.completeExceptionally(ex);
            }
        });

        f.join();

        start_int();
    }

    private long timestamp;
    private long duration1 = 1000;
    private long duration2 =  100;
    private long duration3 =   10;
    private long waitTime  =   10;

    private final AtomicBoolean executorRunning = new AtomicBoolean();

    private void start_int() {
        try {
            executorRunning.set(true);
            while (getCaller().isRunning() && !Thread.currentThread().isInterrupted()) {
                try {
                    long currentTime = System.currentTimeMillis();
                    boolean eventsProcessed = getCaller().getExecutor().processRemainingEvents();

                    if (Thread.currentThread() == executionThread) {
                        if (eventsProcessed || timestamp == 0) {
                            timestamp = currentTime;
                        }

                        long timeDiff = currentTime - timestamp;
                        if (timeDiff > duration1) {
                            waitTime = 100;
                        } else if (timeDiff > duration2) {
                            waitTime = 10;
                        } else if (timeDiff > duration3) {
                            waitTime = 1;
                        } else {
                            // full speed
                            waitTime = 0;
                        }

                        try {
                            synchronized (executionThread) {
                                if (waitTime > 0) {
                                    executionThread.wait(waitTime);
                                }
                            }
                        } catch (InterruptedException iEx) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } catch (Exception ex) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        } finally {
            executorRunning.set(false);
        }
    }

    @Override
    public boolean isRunning() {
        return executorRunning.get();
    }

    @Override
    public CompletableFuture<Void> startAsync() {

        getCaller().getExecutor().reset();
        getCaller().setRunning(true);

        var f = new CompletableFuture();
        this.executionThread = new Thread(()->{
            try {
                start_int();
                f.complete(null);
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        this.executionThread.start();

        return f;
    }

//    @Override
    private ReentrantLock getFSMLock() {
        return this.fsmLock;
    }

    @Override
    public void resetShallow() {
        evtQueue.clear();
        accessFSMSafe(fsm-> fsm.setCurrentState(null));
    }

    @Override
    public void reset() {

        resetShallow();

        // reset children
        fsm.vmf().content().stream(FSM.class).filter(sm->sm.getExecutor()!=null).filter(sm->sm!=fsm)
                .forEach(fsm->
                    fsm.getExecutor().resetShallow()
                );
    }

    @Override
    public void stop() {
        stopAsync().join();
    }

    public CompletableFuture<Void> stopAsync() {
        return CompletableFuture.runAsync(()->{
            while(hasRemainingEvents() && executionThread!=null && executionThread.isAlive()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).thenAccept((unused)->{
            accessFSMSafe(fsm-> fsm.setRunning(false));
            reset();
        });
    }

    private void log(String msg) {
        if(getCaller().isVerbose()) {
            System.out.println(msg);
        }
    }

    @Override
    public eu.mihosoft.vsm.model.Executor newChild(FSM fsm) {
        return new Executor(fsm,this.mode,this.depth+1, this);
    }

    @Override
    public boolean hasRemainingEvents() {

        fsmLock.lock();
        try {

            if (!getCaller().isRunning()) return false;

            boolean eventsInQueue = !evtQueue.isEmpty();
            boolean actionsRunning = doActionThread != null && doActionThread.isAlive();

            if (eventsInQueue) return true;
            if (actionsRunning) return true;

            State state = getCaller().getCurrentState();

            boolean initialRun = getCaller().isRunning() && state == null;

            if (initialRun) return true; // initial state

            if (state instanceof FSMState) {
                FSMState fsmState = (FSMState) state;
                boolean childrenExec = fsmState.getFSMs().stream().filter(fsm -> fsm.getExecutor() != null).
                        map(fsm -> fsm.getExecutor().hasRemainingEvents()).
                        anyMatch(hasExec -> hasExec);

                return childrenExec;
            }

            return false;

        } finally {
            fsmLock.unlock();
        }
    }
}
