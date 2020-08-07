package eu.mihosoft.vsm.model;

import java.util.List;

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
    default void trigger(String evt, Object... args) {
        Event event = Event.newBuilder().withName(evt).withArgs(args).build();
        trigger(event);
    }

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
     * Resets the associated state machine excluding nested state machines.
     */
    void resetShallow();

    /**
     * Resets the associated state machine including all nested state machines.
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
     * Triggers the specified event and adds it to the head position of the event queue. This method should only
     * be used if prioritized processing is necessary.
     * @param evt
     */
    void triggerFirst(Event evt);

    /**
     * Exits the do-action of the specified state.
     * @param evt event that is associated with the cancellation of the specified states do-action
     * @param state
     */
    void exitDoActionOfState(Event evt, State state);

}
