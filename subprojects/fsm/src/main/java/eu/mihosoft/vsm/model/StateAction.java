package eu.mihosoft.vsm.model;

@FunctionalInterface
public interface StateAction {
    /**
     * Executed if a state is entered, exited or as do-action while a state is currently entered.
     * @param s state
     * @param e event
     */
    void execute(State s, Event e);
}
