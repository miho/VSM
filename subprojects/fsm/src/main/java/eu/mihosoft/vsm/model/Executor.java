package eu.mihosoft.vsm.model;

import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executor interface for executing finite state machines (FSM).
 */
public interface Executor {

    /**
     * Triggers the specified event.
     * @param evt event identifier
     * @param onConsumed an optional action that is executed if and when the event is consumed
     * @param args optional event arguments
     */
    void trigger(String evt, EventConsumedAction onConsumed, Object... args);

    /**
     * Triggers the specified event.
     * @param evt event identifier
     * @param args optional event arguments
     */
    void trigger(String evt, Object... args) ;

    /**
     * Triggers the specified event.
     * @param event event to be triggered
     */
    void trigger(Event event);

    /**
     * Triggers and processes the specified event. The state machine must
     * not be running if this method should be used.
     * @param evt event identifier
     * @param args optional event arguments
     * @return {@code true} if the method processed events; {@code false} otherwise
     */
    boolean process(String evt, Object... args);

    /**
     * Processes events that are on the event queue and haven't been processed yet.
     * @return {@code true} if the method processed events; {@code false} otherwise
     */
    boolean processRemainingEvents();

    /**
     * Starts the executor and waits until the state machine has stopped.
     */
    void startAndWait();

    /**
     * Starts the state machine and returns the thread object that is performing the
     * execution. This method does return while the state machine is executed
     * @return the thread performing the execution
     */
    Future<Void> startAsync();

    /**
     * Returns the lock object that locks the FSM instance controlled by this executor.
     * @return the lock object that locks the FSM instance controlled by this executor
     */
    ReentrantLock getFSMLock();

    /**
     * Resets the associated state machine.
     */
    void reset();

    /**
     * Stops the execution of the state machine.
     */
    void stop();

    /**
     * Spawns a new executor from this executor.
     * @param fsm the state machine to be executed
     * @return the requested executor instance
     */
    Executor newChild(FSM fsm);

    /**
     * Indicates whether there are remaining events to be processed.
     * @return {@code true} if there are remaining events to be processed; {@code false} otherwise
     */
    boolean hasRemainingEvents();

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
