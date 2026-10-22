package com.example.closure.tree.testkit;

import java.util.Objects;

/** An ingest event reduced to what the forest rules see: a snapshot or delete of one edge. */
public record EdgeEvent(Kind kind, Edge edge) {

    public enum Kind { SNAPSHOT, DELETE }

    public EdgeEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(edge, "edge");
    }

    public static EdgeEvent add(Edge edge) {
        return new EdgeEvent(Kind.SNAPSHOT, edge);
    }

    public static EdgeEvent delete(Edge edge) {
        return new EdgeEvent(Kind.DELETE, edge);
    }

    public static EdgeEvent add(String parent, String type, String child) {
        return add(Edge.of(parent, type, child));
    }

    public static EdgeEvent delete(String parent, String type, String child) {
        return delete(Edge.of(parent, type, child));
    }

    /** A line of Java that recreates this event, for reproducers. */
    public String asJava() {
        return "EdgeEvent." + (kind == Kind.SNAPSHOT ? "add" : "delete")
                + "(\"" + edge.parent().id() + "\", \"" + edge.type() + "\", \"" + edge.child().id() + "\")";
    }

    @Override
    public String toString() {
        return (kind == Kind.SNAPSHOT ? "+ " : "- ") + edge;
    }
}
