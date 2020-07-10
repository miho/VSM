package eu.mihosoft.vsm.model;

import java.util.function.BiPredicate;

@FunctionalInterface
public interface Guard extends BiPredicate<Transition, Event> {

}
