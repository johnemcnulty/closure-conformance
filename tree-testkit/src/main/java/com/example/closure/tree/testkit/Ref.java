package com.example.closure.tree.testkit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A resource reference: a {@code (type, id)} pair (contract L7). Ordered by the bytes of its
 * canonical form, which is the tie-break the brief uses wherever a choice must be deterministic
 * (D8). The {@code type:id} form here is the kit's own; the deployment's canonical serialization
 * is not needed for anything the kit checks.
 */
public record Ref(String type, String id) implements Comparable<Ref> {

    public Ref {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type.isEmpty()) throw new IllegalArgumentException("a reference needs a type");
    }

    /** A reference of the kit's generic type, for scenarios and traces. */
    public static Ref of(String id) {
        return new Ref("node", id);
    }

    public String canonical() {
        return type + ":" + id;
    }

    @Override
    public int compareTo(Ref other) {
        return Arrays.compareUnsigned(
                canonical().getBytes(StandardCharsets.UTF_8),
                other.canonical().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return id;
    }
}
