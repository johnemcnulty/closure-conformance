package com.example.closure.model;

import java.util.Objects;

/** An edge, identified by its triple: subject, authored edge type, object (W21). */
public record Edge(Ref subject, String edgeType, Ref object) {
    public Edge {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(edgeType, "edgeType");
        if (edgeType.isEmpty()) { // an authored name, so empty is malformed exactly as an empty id is (W20)
            throw new IllegalArgumentException("empty edgeType");
        }
    }

    public static Edge of(Ref subject, String edgeType, Ref object) {
        return new Edge(subject, edgeType, object);
    }
}
