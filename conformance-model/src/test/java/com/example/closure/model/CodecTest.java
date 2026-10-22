package com.example.closure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The canonical serialization (L8) and the edge id function (W21), checked against written-out
 * strings rather than against the codec's own output. The hostile ids are the ones fixture 7 feeds
 * an instance: delimiters, separators, quotes, spaces and non-ASCII.
 */
class CodecTest {

    /** Ids a real inventory might emit, each of which has broken a naive encoder somewhere. */
    static final List<String> HOSTILE_IDS = List.of(
        "PT/1,2",            // a comma, which is why the separator is not one
        "PT>1",              // the separator itself
        "100%",              // the escape character
        "A%3EB",             // something that already looks escaped
        "A%25B",             // and something that looks doubly escaped
        "00:1A:2B:3C:4D:5E", // colons, as a MAC-shaped id
        "P(1)",              // parentheses, which are positional and never escaped
        "slot 3",            // a space
        "say \"hi\"",        // quotes
        "ánténna-Ω",         // non-ASCII
        "(card:X>hosts>port:P1)", // an id shaped like an edge id
        ">",                 // nothing but the separator
        "%");                // nothing but the escape character

    @Nested
    class CanonicalReferences {
        @Test
        void writesTypeThenColonThenId() {
            assertEquals("port:PT-B1", Canonical.write(Ref.of("port", "PT-B1")));
        }

        @Test
        void parsesAtTheFirstColonSoAnIdMayContainOne() { // L9
            Ref parsed = Canonical.parse("port:00:1A:2B");
            assertEquals(Ref.of("port", "00:1A:2B"), parsed);
        }

        @Test
        void roundTripsEveryHostileId() { // L9, L10
            for (String id : HOSTILE_IDS) {
                Ref ref = Ref.of("port", id);
                assertEquals(ref, Canonical.parse(Canonical.write(ref)), id);
            }
        }

        @Test
        void aBareIdIsMalformed() { // L6
            assertThrows(IllegalArgumentException.class, () -> Canonical.parse("PT-B1"));
        }

        @Test
        void emptyTypeOrIdIsMalformed() { // L6
            assertThrows(IllegalArgumentException.class, () -> Canonical.parse(":PT-B1"));
            assertThrows(IllegalArgumentException.class, () -> Canonical.parse("port:"));
        }

        @Test
        void referencesDifferingOnlyInCaseOrWhitespaceAreDistinct() { // L8
            assertNotEquals(Canonical.parse("port:p1"), Canonical.parse("port:P1"));
            assertNotEquals(Canonical.parse("port:P1 "), Canonical.parse("port:P1"));
        }
    }

    @Nested
    class EdgeIds {
        static final Edge HOSTS = Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "PT/1,2"));

        @Test
        void readsAsTheTripleItIs() {
            assertEquals("(card:X>hosts>port:PT/1,2)", EdgeId.of(HOSTS));
        }

        @Test
        void commasInIdsStayLiteral() { // the reason the separator is not a comma
            assertEquals("(card:X>hosts>port:PT/1,2)", EdgeId.of(HOSTS));
        }

        @Test
        void referenceIsTheIdBehindTheEdgeType() { // W21, W24
            assertEquals("edge:(card:X>hosts>port:PT/1,2)", EdgeId.referenceOf(HOSTS));
        }

        @Test
        void separatorInAnIdIsEscaped() {
            Edge edge = Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "A>B"));
            assertEquals("(card:X>hosts>port:A%3EB)", EdgeId.of(edge));
            assertEquals(edge, EdgeId.parse(EdgeId.of(edge)));
        }

        @Test
        void escapeCharacterInAnIdIsEscapedFirst() { // so an id already holding %3E round-trips
            Edge edge = Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "A%3EB"));
            assertEquals("(card:X>hosts>port:A%253EB)", EdgeId.of(edge));
            assertEquals(edge, EdgeId.parse(EdgeId.of(edge)));
        }

        @Test
        void parenthesesInAnIdNeedNoTreatment() {
            Edge edge = Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "P(1)"));
            assertEquals("(card:X>hosts>port:P(1))", EdgeId.of(edge));
            assertEquals(edge, EdgeId.parse(EdgeId.of(edge)));
        }

        @Test
        void anEdgeTypeCarryingTheSeparatorIsEscapedToo() {
            Edge edge = Edge.of(Ref.of("card", "X"), "a>b", Ref.of("port", "P1"));
            assertEquals("(card:X>a%3Eb>port:P1)", EdgeId.of(edge));
            assertEquals(edge, EdgeId.parse(EdgeId.of(edge)));
        }

        @Test
        void roundTripsEveryHostileIdAtEitherEnd() { // fixture 7
            for (String id : HOSTILE_IDS) {
                Edge asObject = Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", id));
                Edge asSubject = Edge.of(Ref.of("port", id), "hosts", Ref.of("card", "X"));
                assertEquals(asObject, EdgeId.parse(EdgeId.of(asObject)), id);
                assertEquals(asSubject, EdgeId.parse(EdgeId.of(asSubject)), id);
                assertEquals(asObject, EdgeId.parseReference(EdgeId.referenceOf(asObject)), id);
            }
        }

        @Test
        void hostileIdsAtBothEndsAtOnceStayDistinct() {
            for (String first : HOSTILE_IDS) {
                for (String second : HOSTILE_IDS) {
                    Edge edge = Edge.of(Ref.of("port", first), "hosts", Ref.of("port", second));
                    assertEquals(edge, EdgeId.parse(EdgeId.of(edge)), first + " / " + second);
                }
            }
        }

        @Test
        void differentTriplesNeverShareAnId() { // W21: compaction depends on this
            List<Edge> distinct = List.of(
                Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "P1")),
                Edge.of(Ref.of("card", "X"), "monitors", Ref.of("port", "P1")),
                Edge.of(Ref.of("port", "P1"), "hosts", Ref.of("card", "X")),
                Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "P2")),
                Edge.of(Ref.of("card", "X>hosts>port"), "hosts", Ref.of("port", "P1")),
                Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "A>B")),
                Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "A%3EB")));
            for (int i = 0; i < distinct.size(); i++) {
                for (int j = i + 1; j < distinct.size(); j++) {
                    assertNotEquals(EdgeId.of(distinct.get(i)), EdgeId.of(distinct.get(j)),
                        distinct.get(i) + " vs " + distinct.get(j));
                }
            }
        }

        @Test
        void anUnparenthesizedIdIsMalformed() { // W25 rejects these
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("card:X>hosts>port:P1"));
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("(card:X>hosts>port:P1"));
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("card:X>hosts>port:P1)"));
        }

        @Test
        void anIdWithTheWrongNumberOfComponentsIsMalformed() {
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("(card:X>hosts)"));
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("(card:X>hosts>port:P1>extra)"));
        }

        @Test
        void anIdWithAnEmptyComponentIsMalformed() { // no valid triple produces one
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("(card:X>hosts>)"));
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("(>hosts>port:P1)"));
            assertThrows(IllegalArgumentException.class, () -> EdgeId.parse("(card:X>>port:P1)"));
        }

        @Test
        void aReferenceOfAnotherTypeIsNotAnEdgeReference() {
            assertThrows(IllegalArgumentException.class,
                () -> EdgeId.parseReference("port:(card:X>hosts>port:P1)"));
        }
    }
}
