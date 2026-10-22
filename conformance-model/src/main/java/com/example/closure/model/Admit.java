package com.example.closure.model;

import java.util.Objects;

/** One {@code (edge type, direction)} pair a closure admits. The edge type is its authored name. */
public record Admit(String edgeType, Direction direction) {
    public Admit {
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(direction, "direction");
    }

    public static Admit forward(String edgeType) {
        return new Admit(edgeType, Direction.FORWARD);
    }

    public static Admit reverse(String edgeType) {
        return new Admit(edgeType, Direction.REVERSE);
    }

    public Admit flip() {
        return new Admit(edgeType, direction.flip());
    }
}
