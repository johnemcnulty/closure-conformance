package com.example.closure.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A declared edge type (W23): a name, optionally an inverse name reading the other way, and which
 * of the two is <em>authored</em>. Every ingest event for the type carries the authored name, and
 * an admitted pair names it too (W4).
 */
public record EdgeTypeDecl(String name, Optional<String> inverse, String authored) {

    public EdgeTypeDecl {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inverse, "inverse");
        Objects.requireNonNull(authored, "authored");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty edge type name");
        }
        if (inverse.filter(name::equals).isPresent()) {
            throw new IllegalArgumentException("edge type " + name + " is its own inverse");
        }
        if (!authored.equals(name) && !inverse.filter(authored::equals).isPresent()) {
            throw new IllegalArgumentException(
                "the authored name of " + name + " must be one of its two names, not " + authored);
        }
    }

    /** A type authored under its own name, with an inverse reading the other way. */
    public static EdgeTypeDecl authoredForward(String name, String inverse) {
        return new EdgeTypeDecl(name, Optional.of(inverse), name);
    }

    /** A type authored under its inverse name, which is what fixture 12 rejects events against. */
    public static EdgeTypeDecl authoredInverse(String name, String inverse) {
        return new EdgeTypeDecl(name, Optional.of(inverse), inverse);
    }

    /** A type declaring no inverse name. */
    public static EdgeTypeDecl withoutInverse(String name) {
        return new EdgeTypeDecl(name, Optional.empty(), name);
    }

    /** The declared name that is not the authored one, if the type has two. */
    public Optional<String> unauthored() {
        return authored.equals(name) ? inverse : Optional.of(name);
    }

    /**
     * The admitted pair a {@code (name, direction)} denotes, written against the authored name.
     *
     * <p>An entry given under the unauthored name denotes the same traversal with its direction
     * flipped — {@code (feeds, forward)} and {@code (fed_by, reverse)} are one traversal — so a
     * configuration may accept either spelling while the published document keeps one form per
     * traversal (W4). Normalizing before checking L2 matters: {@code (fed_by, forward)} alongside
     * {@code (feeds, forward)} is both directions of one type while reading like two.
     *
     * @throws IllegalArgumentException if the name is not one of this type's names
     */
    public Admit normalize(String givenName, Direction direction) {
        if (givenName.equals(authored)) {
            return new Admit(authored, direction);
        }
        if (unauthored().filter(givenName::equals).isPresent()) {
            return new Admit(authored, direction.flip());
        }
        throw new IllegalArgumentException(givenName + " is not a name of edge type " + name);
    }
}
