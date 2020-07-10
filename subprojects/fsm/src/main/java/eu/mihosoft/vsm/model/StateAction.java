package eu.mihosoft.vsm.model;

@FunctionalInterface
public interface StateAction {
    void execute(State s, Event e);
}
