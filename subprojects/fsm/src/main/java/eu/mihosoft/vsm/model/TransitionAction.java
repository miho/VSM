package eu.mihosoft.vsm.model;

@FunctionalInterface
public interface TransitionAction {
    /**
     * Executed if the associated transition is performed.
     * @param t transition
     * @param e event that triggers the transition
     */
    void execute(Transition t, Event e);
}
