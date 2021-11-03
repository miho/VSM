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
package eu.mihosoft.vsm.model;

import java.util.function.Consumer;

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
     * Triggers and processes the specified event. The executor of the state machine must
     * not be running (via {@link Executor#isRunning()}) if this method is used to process events.
     * This method returns as soon as the triggered event and events created as consequence
     * of triggering this event have been processed.
     * @param evt event identifier
     * @param args optional event arguments
     * @return {@code true} if the method processed events; {@code false} otherwise
     */
    boolean process(String evt, Object... args);

    /**
     * Triggers and processes the specified event. The executor of the state machine must
     * not be running (via {@link Executor#isRunning()}) if this method is used to process events.
     * This method returns as soon as the triggered event and events created as consequence
     * of triggering this event have been processed.
     * @param evt event identifier
     * @return {@code true} if the method processed events; {@code false} otherwise
     */
    public boolean process(Event evt);

    /**
     * Triggers and processes the specified event. The executor of the state machine must
     * not be running (via {@link Executor#isRunning()}) if this method is used to process events.
     * This method returns as soon as the triggered event and events created as consequence
     * of triggering this event have been processed.
     * @param evt event identifier
     * @param onConsumed an optional action that is executed if and when the event is consumed
     * @param args optional event arguments
     * @return {@code true} if the method processed events; {@code false} otherwise
     */
    public boolean process(String evt, EventConsumedAction onConsumed, Object... args);

    /**
     * Processes events that are on the event queue and haven't been processed yet.
     * @return {@code true} if the method processed events; {@code false} otherwise
     */
    boolean processRemainingEvents();

    /**
     * Indicates whether this executor is currently running.
     *
     * @return {@code true} if this executor is currently running; {@code false} otherwise
     */
    boolean isRunning();

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

    /**
     * Allows safe access to the managed FSM. If this executor is not running asynchronously
     * this is a no-op. Otherwise, this method ensures correct locking of the FSM model.
     * TODO implement proper actor model
     * @param fsmTask task to perform
     */
     void accessFSMSafe(Consumer<FSM> fsmTask);

    /**
     * Events triggered by the state machine.
     *
     * <p><b>Caution: </b>Do not trigger these events manually.</p>
     */
    enum FSMEvents {

        /**
         * Triggerred if state is done.
         */
        STATE_DONE("fsm:state-done"),
        /**
         * Triggered if do-action is done.
         */
        DO_ACTION_DONE("fsm:on-do-action-done"),
        /**
         * Triggered if an error occurred.
         */
        ERROR("fsm:error"),
        /**
         * Triggerred on init.
         */
        INIT("fsm:init"),
        /**
         * Triggered if final state has been reached.
         */
        FINAL_STATE("fsm:final-state"),
        ;

        private final String name;

        private FSMEvents(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

}
