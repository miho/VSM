/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package eu.mihosoft.vsm.executor;

import eu.mihosoft.vsm.model.FSM;
import eu.mihosoft.vsm.model.FSMState;
import eu.mihosoft.vsm.model.State;
import eu.mihosoft.vsm.model.Transition;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class App {

    public static void main(String[] a) throws InterruptedException {
        State stateA = State.newBuilder()
                .withName("State A")
                .withOnEntryAction((s, e) -> System.out.println("enter state a"))
//                .withDoAction()
                .withOnExitAction((s, e) -> System.out.println("exit state a"))
                .build();
        State stateB = State.newBuilder()
                .withName("State B")
                .withOnEntryAction((s, e) -> System.out.println("enter state b"))
//                .withDoAction()
                .withOnExitAction((s, e) -> System.out.println("exit state b"))
                .build();

        State stateCA1 = State.newBuilder()
                .withName("State CA1")
                .withOnEntryAction((s, e) -> System.out.println("  enter state ca1"))
//                .withDoAction()
                .withOnExitAction((s, e) -> System.out.println("  exit state ca1"))
                .build();

        State stateCB1 = State.newBuilder()
                .withName("State CB1")
                .withOnEntryAction((s, e) -> System.out.println("  enter state cb1"))
//                .withDoAction()
                .withOnExitAction((s, e) -> System.out.println("  exit state cb1"))
                .build();

        State stateCA2 = State.newBuilder()
                .withName("State CA2")
                .withOnEntryAction((s, e) -> System.out.println("  enter state ca2"))
//                .withDoAction()
                .withOnExitAction((s, e) -> System.out.println("  exit state ca2"))
                .build();

        State stateCB2 = State.newBuilder()
                .withName("State CB2")
                .withOnEntryAction((s, e) -> System.out.println("  enter state cb2"))
//                .withDoAction()
                .withOnExitAction((s, e) -> System.out.println("  exit state cb2"))
                .build();


        FSMState stateC = FSMState.newBuilder()
                .withName("State C")
                .withOnEntryAction((s, e) -> System.out.println("enter state c"))
                .withOnExitAction((s, e) -> System.out.println("exit state c"))
                .withDoAction((s, e) -> {
                    try {
                        System.out.println("!!! enter");
                        Thread.sleep(10000);
                    } catch (InterruptedException interruptedException) {
                        System.out.println("!!! interrupted");
                        Thread.currentThread().interrupt();
                    } finally {
                        System.out.println("!!! exit");
                    }
                })
                .withFSMs(
                        FSM.newBuilder()
                                .withName("FSM C1")
//                                .withVerbose(true)
                                .withOwnedState(stateCA1, stateCB1)
                                .withInitialState(stateCA1)
//                                .withFinalState(stateCB1)
                                .withTransitions(
                                        Transition.newBuilder()
                                                .withSource(stateCA1)
                                                .withTarget(stateCB1)
                                                .withTrigger("myEvent1")
                                                .build()
                                )
                                .build(),
                        FSM.newBuilder()
                                .withName("FSM C2")
//                                .withVerbose(true)
                                .withOwnedState(stateCA2, stateCB2)
                                .withInitialState(stateCA2)
//                                .withFinalState(stateCB2)
                                .withTransitions(
                                        Transition.newBuilder()
                                                .withSource(stateCA2)
                                                .withTarget(stateCB2)
                                                .withTrigger("myEvent2")
                                                .build()
                                )
                                .build()
                )
                .build();

        FSM fsm = FSM.newBuilder()
                .withName("FSM")
                .withVerbose(true)
                .withInitialState(stateA)
                .withOwnedState(stateA,stateB,stateC)
                .withTransitions(
                        Transition.newBuilder()
                                .withSource(stateA)
                                .withTarget(stateB)
                                .withTrigger("myEvent1")
                                .build(),
                        Transition.newBuilder()
                                .withSource(stateB)
                                .withTarget(stateC)
                                .withTrigger("myEvent2")
                                .build(),
                        Transition.newBuilder()
                                .withSource(stateC)
                                .withTarget(stateA)
                                .withTrigger("myEvent1")
                                .build()
                        ,
                        Transition.newBuilder()
                                .withSource(stateC)
                                .withTarget(stateA)
                                .withTrigger("fsm:on-do-action-done")
                                .build()
                )
                .build();

        Executor executor = Executor.newInstance(fsm);
        executor.startAsync();

        executor.trigger("myEvent1", (e, t) -> System.out.println("consumed " + e.getName() + ", " + t.getOwningFSM().getName()));
        executor.trigger("myEvent2", (e, t) -> System.out.println("consumed " + e.getName() + ", " + t.getOwningFSM().getName()));
        executor.trigger("myEvent2", (e, t) -> System.out.println("consumed " + e.getName() + ", " + t.getOwningFSM().getName()));
        executor.trigger("myEvent1", (e, t) -> System.out.println("consumed " + e.getName() + ", " + t.getOwningFSM().getName()));
//        executor.trigger("myEvent1", (e, t) -> System.out.println("consumed " + e.getName() + ", " + t.getOwningFSM().getName()));
    }

}
