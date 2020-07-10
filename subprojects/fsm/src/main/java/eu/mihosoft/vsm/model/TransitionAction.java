package eu.mihosoft.vsm.model;

@FunctionalInterface
public interface TransitionAction {
    void execute(Transition t, Event e);
}
