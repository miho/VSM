package eu.mihosoft.vsm.executor;

import eu.mihosoft.vsm.model.AsyncFSMExecutor;
import eu.mihosoft.vsm.model.FSM;

/**
 * FSM Executors.
 */
public final class FSMExecutors {
    private FSMExecutors() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Creates a new async executor.
     * @param fsm the fsm to execute
     * @return new async executor instance
     */
    public static AsyncFSMExecutor newAsyncExecutor(FSM fsm) {
        return FSMExecutor.newInstance(fsm, AsyncFSMExecutor.ExecutionMode.PARALLEL_REGIONS);
    }

    /**
     * Creates a new async executor.
     * @param fsm the fsm to execute
     * @param executionMode the execution mode to use
     * @return new async executor instance
     */
    public static AsyncFSMExecutor newAsyncExecutor(FSM fsm, AsyncFSMExecutor.ExecutionMode executionMode) {
        return FSMExecutor.newInstance(fsm, executionMode);
    }
}
