package com.example.closure.model;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** A closure: a name and the {@code (edge type, direction)} pairs it admits. */
public record Closure(String name, Set<Admit> admits) {
    public Closure {
        Objects.requireNonNull(name, "name");
        admits = Set.copyOf(admits);
        for (Admit a : admits) {
            if (admits.contains(a.flip())) { // L2
                throw new IllegalArgumentException("closure " + name + " admits both directions of " + a.edgeType());
            }
        }
    }

    public static Closure of(String name, Admit... admits) {
        return new Closure(name, Set.of(admits));
    }

    /** The inverse closure: the same edge types with every direction flipped (W5). */
    public Closure inverse(String inverseName) {
        return new Closure(inverseName, admits.stream().map(Admit::flip).collect(Collectors.toSet()));
    }

    public boolean admits(String edgeType, Direction direction) {
        return admits.contains(new Admit(edgeType, direction));
    }

    /** Whether the closure admits the edge type in at least one direction. */
    public boolean admitsType(String edgeType) {
        return admits(edgeType, Direction.FORWARD) || admits(edgeType, Direction.REVERSE);
    }
}
