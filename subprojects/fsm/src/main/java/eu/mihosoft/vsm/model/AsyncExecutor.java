package eu.mihosoft.vsm.model;

import java.util.concurrent.CompletableFuture;

public interface AsyncExecutor extends Executor {
    /**
     * Starts the executor and waits until the state machine has stopped.
     */
    void startAndWait();

    /**
     * Starts the state machine and returns the thread object that is performing the
     * execution. This method does return while the state machine is executed
     * @return future that completes if the execution stopped
     */
    CompletableFuture<Void> startAsync();

    /**
     * Stops the execution of the state machine.
     *
     * @return future that completes if the execution stopped
     */
    CompletableFuture<Void> stopAsync();

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
