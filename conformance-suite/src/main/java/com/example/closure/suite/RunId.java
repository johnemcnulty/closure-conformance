package com.example.closure.suite;

import com.example.closure.model.Ref;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The prefix every id a run creates carries (suite-design §6). It keeps runs from colliding on one
 * long-lived instance, which has no reset. A generated one also carries characters that stress
 * the encodings — a space, a slash, a comma, the edge id separator and escape, a colon and
 * non-ASCII — so every run exercises them in every id as a side effect (L10).
 */
public record RunId(String value) {

    static final String HOSTILE_TAIL = "~ /,>%é:";

    private static final SecureRandom RANDOM = new SecureRandom();

    public RunId {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("empty run id");
        }
    }

    public static RunId generate() {
        return new RunId("r" + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36) + HOSTILE_TAIL);
    }

    /** A narrower prefix under this one, for a fixture or a property try that needs its own ids. */
    public RunId child(String name) {
        return new RunId(value + name + HOSTILE_TAIL);
    }

    /** A reference of {@code type} whose id is {@code local} behind this run's prefix. */
    public Ref ref(String type, String local) {
        return Ref.of(type, value + local);
    }
}
