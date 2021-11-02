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
package eu.mihosoft.vrlfsm.model.vmfmodel;

import eu.mihosoft.vmf.core.*;
import eu.mihosoft.vmf.core.VMFEquals.EqualsType;
import java.util.function.Predicate;
import java.util.function.Function;

@VMFModel(
    equality = EqualsType.INSTANCE
)

@InterfaceOnly
interface WithType {
    String getTypeName();
}

@InterfaceOnly
interface WithName {
    String getName();
}

@InterfaceOnly
interface WithValue {
    Object getValue();
}

@InterfaceOnly
interface WithInputStream {
    InputStream getInputStream();
}

@InterfaceOnly
interface WithOutputStream {
    OutputStream getOutputStream();
}

@InterfaceOnly
interface WithBehavior {
    VBehavior getBehavior();
}

@InterfaceOnly
interface OutputStream {
    boolean push(Object o);
    boolean isPushPossible();
}

@InterfaceOnly
interface InputStream {
    Object pull();
    boolean isDone();
}

@InterfaceOnly
//@DelegateTo("ensure either value or stream, not both")
interface WithValueOrInputStream extends WithValue, WithInputStream {
    
}

@InterfaceOnly
//@DelegateTo("ensure either value or stream, not both")
interface WithValueOrOutputStream extends WithValue, WithOutputStream {

}

@InterfaceOnly
interface OfKind {
    @DefaultValue("\"default\"")
    String getKind(); // @todo: should be enum
}


@InterfaceOnly
interface BehaviorChild {
    VBehavior getOwningBehavior();
}

@InterfaceOnly
interface VBehavior extends OfKind {

    @Contains(opposite = "owningBehavior")
    Input[] getInputs();

    @Contains(opposite = "owningBehavior")
    Output[] getOutputs();

    InvocationResult invoke();

}

@InterfaceOnly
interface EventHandler {

    EventHandler handleEvent(Event e, Object eventArgs);
}

@InterfaceOnly
interface WithCapabilities {
    String[] getCapabilities();
}

@InterfaceOnly
interface WithCode {
    String getCode();

    String getLanguage();
}

@InterfaceOnly
interface Connector extends WithName, WithType, BehaviorChild, WithCapabilities, OfKind, WithMetaData {

}

interface Input extends Connector {
    @Container(opposite = "inputs")
    VBehavior getOwningBehavior();

    boolean isStream();

    @Refers(opposite="receiver")
    Connection getIncomingConnection();
}

interface Output extends Connector {
    @Container(opposite = "outputs")
    VBehavior getOwningBehavior();

    boolean isStream();

    @Refers(opposite="sender")
    Connection[] getOutgoingConnections();
}


interface Argument extends WithName, WithValueOrInputStream, WithMetaData {

}

interface InvocationResult {
    ReturnValue[] getValues();
}

interface ReturnValue extends WithName, WithValueOrOutputStream, WithMetaData {

}


@InterfaceOnly
interface RegionChild {
    Region getOwningRegion();
}

@InterfaceOnly
interface DataFlowChild {
    DataFlow getOwningDataFlow();
}

@InterfaceOnly
interface WithDataFlow {
    DataFlow getDataFlow();
}

@InterfaceOnly
interface WithMetaData {
    String[] getMetaData(); 
}

interface DataFlow extends EventHandler {

    @Contains(opposite = "owningDataFlow")
    Connection[] getConnections();

    @Refers(opposite = "dataFlow")
    DataFlowUnit[] getReferencingUnits();

    @Refers(opposite = "dataFlow")
    DataFlowState[] getReferencingStates();

    @Contains(opposite="owningDataFlow")
    Unit[] getOwnedUnits();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.DataFlowDelegate")
    InvocationResult invoke();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.DataFlowDelegate")
    EventHandler handleEvent(Event e, Object eventArgs);

}

interface Connection {

    @Refers(opposite="outgoingConnections")
    Output getSender();
    @Refers(opposite="incomingConnection")
    Input getReceiver();

    @Container(opposite = "connections")
    DataFlow getOwningDataFlow();

}

@InterfaceOnly
interface Unit extends VBehavior, DataFlowChild {

    @Contains(opposite = "owningBehavior")
    Input[] getInputs();

    @Contains(opposite = "owningBehavior")
    Output[] getOutputs();

    String[] getTriggers();

    @Container(opposite="ownedUnits")
    DataFlow getOwningDataFlow();
    
}

interface CodeSnippetUnit extends Unit, WithCode {
    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.CodeSnippedUnitDelegate")
    InvocationResult invoke();
}

interface DataFlowUnit extends Unit, WithDataFlow {
    @Refers(opposite = "referencingUnits")
    DataFlow getDataFlow();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.DataFlowUnitDelegate")
    InvocationResult invoke();
}

interface FSMUnit extends Unit {
    @Refers(opposite = "referencingUnits")
    FSM getFSM();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.FSMUnitDelegate")
    InvocationResult invoke();
}



@Doc("This model entity is a finite state machine.")
interface FSM extends WithName {

    @Contains(opposite="owningFSM")
    Region[] getRegions();

    @Refers(opposite = "fSM")
    FSMState[] getReferencingStates();

    @Refers(opposite = "fSM")
    FSMUnit[] getReferencingUnits();

    @Contains(opposite = "owningFSM")
    ExecutionBehavior getExecutionBehavior();

    @Contains(opposite = "owningFSM")
    TriggerBehavior getTriggerBehavior();

}

interface Region {
    State getInitialState();
    State getCurrentState();
    State getFinalState();

    @Container(opposite="regions")
    FSM getOwningFSM();

    @Contains(opposite="owningRegion")
    State[] getOwnedState();

    @Contains(opposite = "owningRegion")
    Transition[] getTransitions();

    // @DelegateTo(className = "com.biofluidix.fsmtest.FSMDelegate")
    // void trigger(String evtName, Object[] args);

    // @DelegateTo(className = "com.biofluidix.fsmtest.FSMDelegate")
    // void reset();

    // @DelegateTo(className = "com.biofluidix.fsmtest.FSMDelegate")
    // void stop();

    // boolean isRunning();
}

interface ExecutionBehavior extends VBehavior {
    @Container(opposite = "executionBehavior")
    FSM getOwningFSM();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.ExecutionBehaviorDelegate")
    InvocationResult invoke();
}

interface TriggerBehavior extends EventHandler {
    @Container(opposite = "triggerBehavior")
    FSM getOwningFSM();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.TriggerBehaviorDelegate")
    EventHandler handleEvent(Event e, Object eventArgs);
}

interface State extends RegionChild, WithName {

    @Container(opposite = "ownedState")
    Region getOwningRegion();

    @Refers(opposite="source")
    Transition[] getOutgoingTransitions();

    @Refers(opposite="target")
    Transition[] getIncomingTransitions();

    String[] getDeferredEvents();

    @Contains()
    Action getOnEntryAction();

    @Contains()
    Action[] getDoActions();

    @Contains()
    Action getOnExitAction();
}

interface FSMState extends State {

    //@DefaultValue("FSM.newInstance()")
    @Refers(opposite = "referencingStates")
    FSM getFSM();
}

interface DataFlowState extends State, WithDataFlow {

    //@DefaultValue("FSM.newInstance()")
    @Refers(opposite = "referencingStates")
    DataFlow getDataFlow();
}

interface Transition extends RegionChild {

    @Container(opposite = "transitions")
    Region getOwningRegion();

    String getTrigger();

    @Contains(opposite="transition")
    Guard getGuard();

    @Refers(opposite="outgoingTransitions")
    State getSource();

    @Refers(opposite="incomingTransitions")
    State getTarget();

    @Contains()
    Action[] getActions();
}

@InterfaceOnly
interface Action extends WithName, VBehavior {

}

interface TaskAction extends Action {
    Function<Object[],Object> getTask();
    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.TaskActionDelegate")
    InvocationResult invoke();
}

interface CodeSnippetAction extends Action {
    Function<Object[],Object> getTask();

    @DelegateTo(className = "eu.mihosoft.vrlfsm.model.CodeSnippedActionDelegate")
    InvocationResult invoke();
}


interface Event extends WithName, OfKind {
    Object[] getArgs();
    boolean isDeferred();
}

interface Guard {

    @Container(opposite = "guard")
    Transition getTransition();

    Predicate<Event> getPredicate();
}