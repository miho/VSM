package eu.mihosoft.vsm.model;

import java.util.concurrent.locks.ReentrantLock;

public interface AsyncExecutor extends Executor {
    /**
     * Starts the executor and waits until the state machine has stopped.
     */
    void startAndWait();

    /**
     * Starts the state machine and returns the thread object that is performing the
     * execution. This method does return while the state machine is executed
     * @return the thread performing the execution
     */
    Thread startAsync();

//    /**
//     * Returns the lock object that locks the FSM instance controlled by this executor.
//     * @return the lock object that locks the FSM instance controlled by this executor
//     */
//    ReentrantLock getFSMLock();

    /**
     * Execution mode.
     */
    enum ExecutionMode {
        /**
         * Regions aka nested FSMs are processed in the thread of the executed FSM.
         */
        SERIAL_REGIONS,
        /**
         * Regions aka nested FSMs are processed parallel.
         */
        PARALLEL_REGIONS,
    }
}
