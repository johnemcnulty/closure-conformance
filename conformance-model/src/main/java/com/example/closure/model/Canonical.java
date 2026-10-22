package com.example.closure.model;

/**
 * The canonical serialization of a resource reference (L8): the type, then {@code :}, then the id.
 *
 * <p>A type may not contain {@code :}; an id may contain anything, so parsing splits at the
 * <em>first</em> colon and everything after it is the id. That one rule is what makes the form
 * round-trip (L9).
 */
public final class Canonical {

    public static final char DELIMITER = ':';

    private Canonical() {
    }

    public static String write(Ref ref) {
        return ref.type() + DELIMITER + ref.id();
    }

    /**
     * The reference a canonical string names.
     *
     * @throws IllegalArgumentException if it carries no type, or an empty type or id (L6)
     */
    public static Ref parse(String serialized) {
        int split = serialized.indexOf(DELIMITER);
        if (split < 0) {
            throw new IllegalArgumentException("no type/id delimiter: " + serialized);
        }
        return new Ref(serialized.substring(0, split), serialized.substring(split + 1));
    }
}
