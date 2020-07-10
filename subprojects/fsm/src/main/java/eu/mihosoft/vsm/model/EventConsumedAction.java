package eu.mihosoft.vsm.model;

@FunctionalInterface
public interface EventConsumedAction {
    void execute(Event e, Transition t);
}
