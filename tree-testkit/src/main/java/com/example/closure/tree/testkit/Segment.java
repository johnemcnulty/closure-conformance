package com.example.closure.tree.testkit;

import java.util.Objects;

/** One segment of a materialized path: the edge type that reaches a resource, and the resource. */
public record Segment(String edgeType, Ref ref) {

    public Segment {
        Objects.requireNonNull(ref, "ref");
    }

    /** The first segment of every path, which has no edge type. */
    public static Segment root(Ref ref) {
        return new Segment(null, ref);
    }
}
