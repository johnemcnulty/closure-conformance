package com.example.closure.model;

/** Direction relative to an edge type's authored name: FORWARD reads subject to object (W5). */
public enum Direction {
    FORWARD, REVERSE;

    public Direction flip() {
        return this == FORWARD ? REVERSE : FORWARD;
    }
}
