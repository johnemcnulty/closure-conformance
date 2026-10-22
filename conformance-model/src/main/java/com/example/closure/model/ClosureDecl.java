package com.example.closure.model;

import java.util.Objects;

/**
 * A declared closure: its name, the name of its inverse (L2), and the pairs it admits. Exactly one
 * closure of each pair states its admitted pairs in the discovery document; the other's are derived
 * by flipping each direction (W4). {@code statesAdmits} records which side this is, because
 * fixture 1 checks that the document states them on the side the profile designates and not the
 * other.
 */
public record ClosureDecl(Closure closure, String inverse, boolean statesAdmits) {

    public ClosureDecl {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(inverse, "inverse");
        if (inverse.equals(closure.name())) {
            throw new IllegalArgumentException("closure " + closure.name() + " names itself as its inverse");
        }
    }

    public static ClosureDecl stating(String name, String inverse, Admit... admits) {
        return new ClosureDecl(Closure.of(name, admits), inverse, true);
    }

    /** The derived side of a pair: its admitted pairs are the carrier's, flipped. */
    public static ClosureDecl deriving(String name, ClosureDecl carrier) {
        return new ClosureDecl(carrier.closure().inverse(name), carrier.closure().name(), false);
    }

    public String name() {
        return closure.name();
    }
}
