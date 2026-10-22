package com.example.closure.model;

import java.util.Objects;
import java.util.Set;

/**
 * A cardinality rule (W4): for every known resource whose type is listed, the number of edges of
 * {@code edgeType} with that resource at {@code end} must lie within {@code [min, max]}. Either
 * bound may be null, but not both.
 */
public record CardinalityRule(String id, String edgeType, End end, Set<String> types, Integer min, Integer max) {
    public CardinalityRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(end, "end");
        types = Set.copyOf(types);
        if (min == null && max == null) {
            throw new IllegalArgumentException("rule " + id + " has no bound");
        }
    }

    public boolean breachedBy(long edgeCount) {
        return (min != null && edgeCount < min) || (max != null && edgeCount > max);
    }
}
