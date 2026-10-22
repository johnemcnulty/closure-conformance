package com.example.closure.model;

import java.util.Objects;

/**
 * A resource reference: a {@code (type, id)} pair (L7). Equality is exact string equality of both
 * members, with no case-folding, trimming or normalization (L8). Empty strings are malformed (L7).
 */
public record Ref(String type, String id) {
    public Ref {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type.isEmpty() || id.isEmpty()) {
            throw new IllegalArgumentException("empty type or id: (" + type + ", " + id + ")");
        }
    }

    public static Ref of(String type, String id) {
        return new Ref(type, id);
    }

    /** The canonical serialization (L8); see {@link Canonical}. */
    @Override
    public String toString() {
        return Canonical.write(this);
    }
}
