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

package eu.mihosoft.vsm.model.vmfmodel;

import eu.mihosoft.vmf.core.*;
import eu.mihosoft.vmf.core.VMFEquals.EqualsType;

import java.time.Duration;


@VMFModel(
    equality = EqualsType.INSTANCE
)

@ExternalType(pkgName = "eu.mihosoft.vsm.model")
interface StateAction {
}

@ExternalType(pkgName = "eu.mihosoft.vsm.model")
interface TransitionAction {
}

@ExternalType(pkgName = "eu.mihosoft.vsm.model")
interface EventConsumedAction {
}

@ExternalType(pkgName = "eu.mihosoft.vsm.model")
interface Guard {
}

@ExternalType(pkgName = "eu.mihosoft.vsm.model")
interface FSMExecutor {
}

@InterfaceOnly
interface WithName {
    String getName();
}

@InterfaceOnly
interface WithArgs {
    Object[] getArgs();
}


@Doc("Child of an FSM.")
@InterfaceOnly
interface FSMChild {
    @Doc("FSM that owns this object.")
    FSM getOwningFSM();
}

@Doc("Parent of an FSM.")
@InterfaceOnly
interface FSMParent {
    @Doc("Child FSMs.")
    FSM[] getFSMs();
}

@Doc("Child of an FSM state.")
@InterfaceOnly
interface StateChild {
    @Doc("Parent state.")
    FSMState getParentState();
}

@InterfaceOnly
interface WithStateActions {
    @Doc("Action to be executed when entering a state.")
    StateAction[] getOnEntryActions();
    @Doc("Action to be executed as long as the owning state machine is currently in this state.")
    StateAction getDoAction();
    @Doc("Action to be executed when exiting a state. ")
    StateAction[] getOnExitActions();
}

@Doc("A finite state machine that contains states and transitions between them.")
//@DelegateTo(className = "eu.mihosoft.vsm.model.FSMBehavior")
interface FSM extends WithName, StateChild {

    @Doc("Indicates whether this state machine should be verbose, i.e., produce" +
            " detailed log output.")
    boolean isVerbose();

    @Doc("Name of this state machine.")
    String getName();

    @Doc("The initial state that is entered as soon as this state machine is started.")
    State getInitialState();
    @Doc("The current state of this state machine.")
    State getCurrentState();
    @Doc("The final state denotes states that end the execution of this state machine" +
            " as soon as they are entered.")
    State[] getFinalState();
    @Doc("Denotes the error state that is entered whenever an error occurs. This is" +
            " allows to handle exceptions. Implicitly a transition between every state and" +
            " the error state is created. Transitions from the error state can be used to" +
            " recover ")
    State getErrorState();

    @Contains(opposite="owningFSM")
    State[] getOwnedState();

    @Contains(opposite = "owningFSM")
    Transition[] getTransitions();

    @Container(opposite = "fSMs")
    FSMState getParentState();

    @Doc("Indicates whether this state machine is currently running.")
    boolean isRunning();

    @DefaultValue("java.time.Duration.ofSeconds(1)")
    Duration getMaxCancellationTimeout();

    @Doc("The executor that is used to process the events sent" +
            " to this state machine. Synchronous as well as async executors" +
            " are supported.")
    FSMExecutor getExecutor();

//    @DelegateTo(className = "eu.mihosoft.vsm.model.FSMBehavior")
//    int depth();
}

@Doc("A state of a finite state machine.")
interface State extends FSMChild, WithName, WithStateActions {

    @Container(opposite = "ownedState")
    FSM getOwningFSM();

    @Refers(opposite="source")
    Transition[] getOutgoingTransitions();

    @Refers(opposite="target")
    Transition[] getIncomingTransitions();

    @Doc("Contains a list of event names or patterns that are deferred by this state.")
    String[] getDeferredEvents();

    @DefaultValue("java.time.Duration.ofSeconds(1)")
    Duration getCancellationTimeout();
}

@Doc("A state that contains a list of state machines" +
        " that are children of the owning state machine.")
interface FSMState extends State, FSMParent {
    @Contains(opposite = "parentState")
    FSM[] getFSMs();

    @Container(opposite = "ownedState")
    FSM getOwningFSM();
}

@Doc("A transition between two states.")
interface Transition extends FSMChild {

    @Container(opposite = "transitions")
    FSM getOwningFSM();

    @Doc("Only valid for transitions with {@code source-state == target-state}.")
    boolean isLocal();

    @Doc("Name of the event that can trigger this transition.")
    String getTrigger();

    @Doc("The guard condition that determines whether to execute this transition.")
    Guard getGuard();

    @Refers(opposite="outgoingTransitions")
    State getSource();
    @Refers(opposite="incomingTransitions")
    State getTarget();

    @Doc("Actions to be performed if this transition is executed.")
    TransitionAction[] getActions();
}

@Doc("An event is used to trigger state changes of a state machine.")
interface Event extends WithName, WithArgs {
    @Doc("The name of the event is used by transitions" +
            " to indicate whether to perform a state transition.")
    String getName();
    @Doc("Arguments that can be used by state actions.")
    Object[] getArgs();
    @Doc("The action to be performed if this event is consumed.")
    EventConsumedAction getAction();
    @Doc("Indicates whether this event has been deferred.")
    boolean isDeferred();
    @Doc("Indicates whether this event has been consumed.")
    boolean isConsumed();
    @Doc("Indicates whether this event is a local event" +
            " (only executed in the state machine that has" +
            " been used for triggering the event)." +
            " Local events are not propagated to children of the state machine.")
    boolean isLocal();
    @Doc("Point in time when the event was created/triggerred.")
    @DefaultValue("-1")
    long getTimeStamp();
}

