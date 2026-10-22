package com.example.closure.tree.testkit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * An edge in parent/child terms. An implementation maps subject and object to parent and child
 * through each edge type's {@code parentEndpoint} (brief §3.2) before handing edges to the kit,
 * so the kit never needs to know which end an edge type is authored from.
 */
public record Edge(Ref parent, String type, Ref child) implements Comparable<Edge> {

    private static final Comparator<Edge> ORDER = Comparator
            .comparing(Edge::parent)
            .thenComparing((a, b) -> Arrays.compareUnsigned(
                    a.type().getBytes(StandardCharsets.UTF_8), b.type().getBytes(StandardCharsets.UTF_8)))
            .thenComparing(Edge::child);

    public Edge {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(child, "child");
    }

    public static Edge of(String parent, String type, String child) {
        return new Edge(Ref.of(parent), type, Ref.of(child));
    }

    /** Byte-least first: the order in which a promotion candidate is chosen (D8). */
    @Override
    public int compareTo(Edge other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return parent + " -" + type + "-> " + child;
    }
}
