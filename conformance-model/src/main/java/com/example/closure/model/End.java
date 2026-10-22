package com.example.closure.model;

/** The end of an edge a cardinality rule counts at, as the authored name reads. */
public enum End {
    SUBJECT, OBJECT;

    public Ref of(Edge edge) {
        return this == SUBJECT ? edge.subject() : edge.object();
    }
}
