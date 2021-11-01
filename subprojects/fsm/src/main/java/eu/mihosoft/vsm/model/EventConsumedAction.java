package eu.mihosoft.vsm.model;

@FunctionalInterface
public interface EventConsumedAction {
    /**
     * This method is called if the associated event is consumed. In a hierarchical state machine
     * this method might get called multiple times.
     *
     * @param e event
     * @param t transition the transition that consumed the event
     */
    void execute(Event e, Transition t);
}
