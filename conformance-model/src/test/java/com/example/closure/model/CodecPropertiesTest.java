package com.example.closure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Round-trip properties over ids built from the characters that actually threaten the encoding —
 * the separator, the escape character, a colon, a comma and the parentheses — so collisions and
 * escaping mistakes surface instead of hiding behind well-behaved input.
 */
class CodecPropertiesTest {

    private static final String THREATENING = "%>:,()25E3-x";

    @Provide
    Arbitrary<String> ids() {
        return Arbitraries.of(THREATENING.chars().mapToObj(Character::toString).toList())
            .list().ofMaxSize(6)
            .map(chars -> String.join("", chars))
            .map(id -> id.isEmpty() ? "x" : id); // an id is never empty (L6)
    }

    @Provide
    Arbitrary<Edge> edges() {
        Arbitrary<String> types = Arbitraries.of("card", "port", "shelf");
        Arbitrary<Ref> refs = Combinators.combine(types, ids()).as(Ref::new);
        return Combinators.combine(refs, Arbitraries.of("hosts", "monitors", "a>b"), refs).as(Edge::new);
    }

    @Property
    void canonicalReferencesRoundTrip(@ForAll("ids") String id) { // L9
        Ref ref = Ref.of("port", id);
        assertEquals(ref, Canonical.parse(Canonical.write(ref)));
    }

    @Property
    void edgeIdsRoundTrip(@ForAll("edges") Edge edge) { // W21
        assertEquals(edge, EdgeId.parse(EdgeId.of(edge)));
    }

    @Property
    void edgeReferencesRoundTrip(@ForAll("edges") Edge edge) { // W21, W24
        assertEquals(edge, EdgeId.parseReference(EdgeId.referenceOf(edge)));
    }

    @Property
    void distinctTriplesGetDistinctIds(@ForAll("edges") Edge a, @ForAll("edges") Edge b) {
        if (a.equals(b)) {
            assertEquals(EdgeId.of(a), EdgeId.of(b));
        } else {
            assertNotEquals(EdgeId.of(a), EdgeId.of(b));
        }
    }

    @Property
    void noComponentOfAnIdCarriesABareSeparator(@ForAll("edges") Edge edge) {
        String id = EdgeId.of(edge);
        String inner = id.substring(1, id.length() - 1);
        assertEquals(3, inner.split(">", -1).length, id);
    }

    @Property
    void anIdIsAlwaysParenthesized(@ForAll("edges") Edge edge) {
        String id = EdgeId.of(edge);
        assertTrue(id.startsWith("(") && id.endsWith(")"), id);
    }
}
