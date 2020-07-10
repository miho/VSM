
package eu.mihosoft.vsm.model.vmfmodel;
/**
 * Copyright 2019-200 Michael Hoffer <info@michaelhoffer.de> . All rights reserved.
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
interface Executor {
}


@InterfaceOnly
interface FSMChild {
    FSM getOwningFSM();
}

@Doc("This model entity is a finite state machine.")
interface FSM {

    boolean isVerbose();

    String getName();

    State getInitialState();
    State getCurrentState();
    State[] getFinalState();
    State getErrorState();

    @Contains(opposite="owningFSM")
    State[] getOwnedState();

    @Contains(opposite = "owningFSM")
    Transition[] getTransitions();

    @Container(opposite = "fSMs")
    FSMState getParentState();

    boolean isRunning();

    @DefaultValue("java.time.Duration.ofSeconds(10)")
    Duration getMaxCancellationTimeout();

    Executor getExecutor();
}

interface State extends FSMChild {

    @Container(opposite = "ownedState")
    FSM getOwningFSM();

    String getName();

    @Refers(opposite="source")
    Transition[] getOutgoingTransitions();

    @Refers(opposite="target")
    Transition[] getIncomingTransitions();

    String[] getDeferredEvents();

    StateAction getOnEntryAction();
    StateAction getDoAction();
    StateAction getOnExitAction();

    @DefaultValue("java.time.Duration.ofSeconds(10)")
    Duration getCancellationTimeout();
}

interface FSMState extends State {
    @Contains(opposite = "parentState")
    FSM[] getFSMs();
}


interface Transition extends FSMChild {

    @Container(opposite = "transitions")
    FSM getOwningFSM();

    boolean isLocal();

    String getTrigger();

    Guard getGuard();

    @Refers(opposite="outgoingTransitions")
    State getSource();
    @Refers(opposite="incomingTransitions")
    State getTarget();

    TransitionAction[] getActions();
}

interface Event {
    String getName();
    Object[] getArgs();
    EventConsumedAction getAction();
    boolean isDeferred();
}

