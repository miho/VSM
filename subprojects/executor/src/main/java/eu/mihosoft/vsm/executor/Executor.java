package eu.mihosoft.vsm.executor;

import eu.mihosoft.vsm.model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class Executor implements eu.mihosoft.vsm.model.Executor {

    private final Deque<Event> evtQueue = new ConcurrentLinkedDeque<>();
    private Thread doActionThread;
    private CompletableFuture<Void> doActionFuture;
    private Map<State, Boolean> stateExited = new HashMap<>();
    private final int depth;
    private final FSM fsm;
    private final ReentrantLock fsmLock = new ReentrantLock();
    private final ReentrantLock eventLock = new ReentrantLock();

    private final List<Executor> pathToRoot = new ArrayList<>();

    private final ExecutionMode mode;

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

    public static Executor newInstance(FSM fsm, ExecutionMode mode) {
        return new Executor(fsm, mode, 0, null);
    }

    private int getDepth() {
        return this.depth;
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

    public boolean process(String evt, EventConsumedAction onConsumed, Object... args) {
        try {
            trigger(evt, onConsumed, args);
            return processRemainingEvents();
        } finally {
            //
        }
    }

    public boolean process(String evt, Object... args) {

//        if() {
//            throw new RuntimeException(
//                "Cannot call 'process()' if machine is already running,"+
//                " try calling trigger(). The 'process()' method triggers and" +
//                        " processes the event in a single method call.");
//        }

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



    private void modifyFSMSafe(Consumer<FSM> fsmTask) {
        try {
            fsmLock.lock();
            fsmTask.accept(getCaller());
        } finally {
            fsmLock.unlock();
        }
    }

    boolean firedFinalState   = false;
    boolean firedDoActionDone = false;

    public boolean processRemainingEvents() {

        // everything modified concurrently with start(), reset(), stop() etc. must be inside
        // locked code block
        try {
            fsmLock.lock();
            if (!getCaller().isRunning()) return false;
            if (getCaller().getOwnedState().isEmpty()) return false;

            // set current state to initial state if current state is null
            if(getCaller().getCurrentState()==null) {
                firedDoActionDone = false;
                firedFinalState   = false;
                performStateTransition(
                        Event.newBuilder().withName("fsm:init").build(),
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

        if(prevState instanceof FSMState) {
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
                        .allMatch(fsm->!fsm.isRunning()&&fsm.getFinalState().contains(fsm.getCurrentState()));

                if(allMatch && !firedFinalState) {
                    evtQueue.addFirst(Event.newBuilder().withName("fsm:final-state").withLocal(true).build());
                    firedFinalState = true;
                }
            }
        }


        for (Iterator<Event> iter = evtQueue.iterator(); iter.hasNext() && getCaller().isRunning(); ) {

            try {

                fsmLock.lock();

                Event evt = iter.next();
                boolean removed = false;
                State currentState = getCaller().getCurrentState();
                boolean stateChanged = currentState!=prevState;

                if(stateChanged){
                    firedDoActionDone = false;
                    firedFinalState   = false;
                }

                prevState = currentState;

                if (getCaller().isVerbose()) {
                    log("> try-consume: " + evt.getName() +
                            (evt.isDeferred() ? " (previously deferred)" : "") + ", fsm: " + level(getCaller()));
                    log("  -> in state: " + level(getCaller()) + ":" + currentState.getName());
                }

                // if we are in a state with nested fsm we try to consume the event in the nested machine
                // before we try to consume it on the current level.
                AtomicBoolean consumedParam = new AtomicBoolean(consumed);
                AtomicBoolean removedParam  = new AtomicBoolean(removed);
                if (currentState instanceof FSMState) {
                    processRegions(iter, evt, (FSMState) currentState, consumedParam, removedParam);
                    removed = removedParam.get();
                    consumed = consumedParam.get();
                } else {
                    if(!firedFinalState) {
                        evtQueue.addFirst(Event.newBuilder().withName("fsm:final-state").withLocal(true).build());
                        firedFinalState = true;
                    }
                }

                if("fsm:final-state".equals(evt.getName())) {
                    firedFinalState = true;
                }

                if("fsm:on-do-action-done".equals(evt.getName())) {
                    firedDoActionDone = true;
                }

                if(!"fsm:state-done".equals(evt.getName()) && firedFinalState && firedDoActionDone) {
                    evtQueue.add(Event.newBuilder().withName("fsm:state-done").withLocal(true).build());
                }

                // children consumed event
                if (consumed) {
                    continue;
                }

                Transition consumer = currentState.getOutgoingTransitions().
                        stream().filter(t -> Objects.equals(t.getTrigger(), evt.getName())).findFirst().
                        orElse(null);

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

                    performStateTransition(evt, consumer.getSource(), consumer.getTarget(), consumer);

                    if (getCaller().getFinalState().contains(getCaller().getCurrentState())) {
                        log("  -> final state reached. stopping.");
                        exitDoActionOfOldState(evt, getCaller().getCurrentState(), null);
                        getCaller().setRunning(false);
                    }

                    // if we consume the current event, pop the corresponding entry in the queue
                    if (!consumed) {
                        if (!removed) {
                            iter.remove();
                        }
                        consumed = true;

                        if (evt.getAction() != null) {
                            evt.getAction().execute(evt, consumer);
                        }

                        try {
                            eventLock.lock();
                            evt.setConsumed(true);
                        } finally {
                            eventLock.unlock();
                        }

                    }

                } else if (!consumed) {
                    if (guardMatches && defers(getCaller().getCurrentState(), evt)) {
                        log("  -> deferring: " + evt.getName());
                        try {
                            eventLock.lock();
                            evt.setDeferred(true);
                        } finally {
                            eventLock.unlock();
                        }
                    } else {
                        log("  -> discarding unconsumed event: " + evt.getName() + " in FSM " + level(getCaller()));
                        // discard event (not deferred)
                        if (!removed) {
                            iter.remove();
                        }
                    }
                }
            } finally {
                fsmLock.unlock();
            }

        } // end for

        return consumed;
    }

    private void processRegions(Iterator<Event> iter, Event evt,
                                FSMState currentState,
                                AtomicBoolean consumedParam,
                                AtomicBoolean removedParam) {
        FSMState fsmState = currentState;
        var threads = new ArrayList<Thread>();
        for (FSM childFSM : fsmState.getFSMs()) {
            Runnable r = () -> {
                    if (evt != null && !evt.isLocal()) {
                        // trigger in child fsm if not local to our fsm
                        // Event must not be modified concurrently if this runs in multiple threads
                        childFSM.getExecutor().trigger(evt);
                    }

                    // process event of non local and potential internal events
                    childFSM.getExecutor().processRemainingEvents();

                    boolean isEvtConsumed;

                    try {
                        eventLock.lock();
                        isEvtConsumed = evt.isConsumed();
                    } finally {
                        eventLock.unlock();
                    }

                    // if we consumed it then remove it
                    if (evt != null && !removedParam.get() && isEvtConsumed) {
                        iter.remove();
                        removedParam.set(true);
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

        consumedParam.set(evt.isConsumed());

        if(evt!=null&&evt.isConsumed()) {
            log(" -> consumed " + evt.getName());
        }

        boolean allMatch = fsmState.getFSMs().stream()
                .allMatch(fsm->!fsm.isRunning()&&fsm.getFinalState().contains(fsm.getCurrentState()));

        if(allMatch && !firedFinalState) {
            evtQueue.addFirst(Event.newBuilder().withName("fsm:final-state").withLocal(true).build());
            firedFinalState = true;
        }
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
                    action.execute(consumer, evt);
                } catch (Exception ex) {
                    handleExecutionError(evt, consumer.getSource(), consumer.getTarget(), ex);
                    return;
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
                        entryAction.execute(s, evt);
                    }

                    if (!executeDoActionOfNewState(evt, s, newState)) return;

                    // enter children states
                    if(enterAndExit &&  s instanceof FSMState) {
                        FSMState fsmState = (FSMState)  s;
                        for(FSM childFSM : fsmState.getFSMs()) {

                            // create a new execute for child fsm if it doesn't exist yet
                            if (childFSM.getExecutor() == null) {
                                getCaller().getExecutor().newChild(childFSM);
                            }
                            Executor executor = (Executor) childFSM.getExecutor();
                            executor.reset();
                            childFSM.setRunning(true);
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
                    entryAction.execute(newState, evt);
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

                // create a new execute for child fsm if it doesn't exist yet
                if (childFSM.getExecutor() == null) {
                    getCaller().getExecutor().newChild(childFSM);
                }

                Executor executor = (Executor) childFSM.getExecutor();
                executor.reset();
                childFSM.setRunning(true);
            }
        }

        // transition done, set new current state
        getCaller().setCurrentState(newState);
        stateExited.put(newState, false);
    }


    private boolean executeDoActionOfNewState(Event evt, State oldState, State newState) {
        try {
            StateAction doAction = newState.getDoAction();
            if(doAction!=null) {
                Runnable doActionDone = ()->{
                    evtQueue.addFirst(Event.newBuilder().withName("fsm:on-do-action-done").withLocal(true).build());
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
                evtQueue.addFirst(Event.newBuilder().withName("fsm:on-do-action-done").withLocal(true).build());
            }
        } catch(Exception ex) {
            handleExecutionError(evt, oldState, newState, ex);
            return false;
        }
        return true;
    }

    private boolean exitDoActionOfOldState(Event evt, State oldState, State newState) {

        if(oldState!=null && !(stateExited.get(oldState)==null?false:stateExited.get(oldState))) {

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
            if(oldState instanceof FSMState) {
                FSMState fsmState = (FSMState) oldState;
                for(FSM childFSM : fsmState.getFSMs()) {
                    Executor executor = (Executor) childFSM.getExecutor();
                    executor.exitDoActionOfOldState(evt, childFSM.getCurrentState(), null);
                }
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
                stateExited.put(oldState, true);
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

        start_int();
    }

    private void start_int() {
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

        getCaller().getExecutor().reset();
        getCaller().setRunning(true);

        Thread t = new Thread(()->{
            start_int();
        });
        t.start();

        return t;
    }

    @Override
    public ReentrantLock getFSMLock() {
        return this.fsmLock;
    }

    private void reset_int() {
        evtQueue.clear();
        modifyFSMSafe(fsm-> fsm.setCurrentState(null));
    }

    public void reset() {

        reset_int();

        // reset children
        fsm.vmf().content().stream(FSM.class).filter(sm->sm.getExecutor()!=null).filter(sm->sm!=fsm)
                .forEach(fsm->((Executor)fsm.getExecutor()).reset_int());
    }

    public void stop() {
        modifyFSMSafe(fsm-> fsm.setRunning(false));
        reset();
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

        try {
            fsmLock.lock();

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
