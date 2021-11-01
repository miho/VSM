package eu.mihosoft.vsm.model;

import java.util.function.BiPredicate;

/**
 * Determines whether the conditions for a state transition are met.
 */
@FunctionalInterface
public interface Guard extends BiPredicate<Transition, Event> {

}
