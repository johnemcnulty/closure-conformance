package com.example.closure.tree.testkit;

import java.util.List;
import java.util.Set;

/**
 * One generated edit sequence: {@code chaos} applies arbitrary adds and deletes — second parents,
 * cycles, self-loops, transient roots — and {@code converge} then takes the edges, in random order,
 * to {@code target}, a proper tree.
 */
public record Scenario(long seed, List<EdgeEvent> chaos, List<EdgeEvent> converge, Set<Edge> target) {

    public Scenario {
        chaos = List.copyOf(chaos);
        converge = List.copyOf(converge);
        target = Set.copyOf(target);
    }
}
