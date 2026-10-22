package com.example.closure.oracle;

import com.example.closure.model.Admit;
import com.example.closure.model.CardinalityRule;
import com.example.closure.model.Closure;
import com.example.closure.model.Edge;
import com.example.closure.model.End;
import com.example.closure.model.Ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Hand-written expectations for the oracle. Every expected set here was worked out by hand from the
 * graph in the test, not computed, so these cases check the oracle independently of its own code.
 */
class OracleTest {

    static final Ref NE = Ref.of("ne", "NE-7");
    static final Ref SHELF = Ref.of("shelf", "S1");
    static final Ref CARD = Ref.of("card", "C1");
    static final Ref PORT1 = Ref.of("port", "P1");
    static final Ref PORT2 = Ref.of("port", "P2");
    static final Ref LINK = Ref.of("link", "L1");
    static final Ref UNKNOWN = Ref.of("port", "nowhere");

    static final Closure CONTAINED = Closure.of("contained", Admit.forward("hosts"));
    static final Closure CONTAINING = CONTAINED.inverse("containing");

    /** NE-7 hosts S1 hosts C1 hosts {P1, P2}; L1 terminatesOn P1. */
    static Oracle equipment(List<CardinalityRule> rules) {
        Oracle o = new Oracle(rules);
        o.snapshot(Edge.of(NE, "hosts", SHELF));
        o.snapshot(Edge.of(SHELF, "hosts", CARD));
        o.snapshot(Edge.of(CARD, "hosts", PORT1));
        o.snapshot(Edge.of(CARD, "hosts", PORT2));
        o.snapshot(Edge.of(LINK, "terminatesOn", PORT1));
        return o;
    }

    static Oracle equipment() {
        return equipment(List.of());
    }

    @Nested
    class ReflexivityAndEmptiness {
        @Test
        void knownReferenceReachingNothingReturnsItself() { // L15, L16
            assertEquals(Set.of(PORT1), equipment().reach(CONTAINED, Set.of(PORT1)));
        }

        @Test
        void referenceKnownOnlyThroughUnadmittedEdgesReturnsItself() { // L15: LINK is known via terminatesOn
            assertEquals(Set.of(LINK), equipment().reach(CONTAINED, Set.of(LINK)));
        }

        @Test
        void unknownReferenceReturnsEmpty() { // L14, L16
            assertEquals(Set.of(), equipment().reach(CONTAINED, Set.of(UNKNOWN)));
        }

        @Test
        void emptyArgumentReturnsEmpty() { // L17
            assertEquals(Set.of(), equipment().reach(CONTAINED, Set.of()));
        }
    }

    @Nested
    class Traversal {
        @Test
        void forwardReachesEverythingBelow() {
            assertEquals(Set.of(SHELF, CARD, PORT1, PORT2), equipment().reach(CONTAINED, Set.of(SHELF)));
        }

        @Test
        void inverseReachesEverythingAbove() {
            assertEquals(Set.of(PORT2, CARD, SHELF, NE), equipment().reach(CONTAINING, Set.of(PORT2)));
        }

        @Test
        void unadmittedEdgeStopsThePath() { // L23: LINK -> P1 is terminatesOn, not admitted
            Closure linkDown = Closure.of("link-down", Admit.forward("terminatesOn"));
            assertEquals(Set.of(LINK, PORT1), equipment().reach(linkDown, Set.of(LINK)));
            // and nothing below P1 via hosts, because hosts is not admitted by this closure
            assertEquals(Set.of(CARD), equipment().reach(linkDown, Set.of(CARD)));
        }

        @Test
        void mixedClosureFollowsEachTypeInItsOwnDirection() {
            Closure portToLinkAndUp = Closure.of("up-and-links", Admit.reverse("hosts"), Admit.reverse("terminatesOn"));
            assertEquals(Set.of(PORT1, CARD, SHELF, NE, LINK), equipment().reach(portToLinkAndUp, Set.of(PORT1)));
        }

        @Test
        void sharedDescendantIsReturnedOnce() { // L6 set semantics on a diamond
            Oracle o = new Oracle();
            Ref top = Ref.of("x", "top"), a = Ref.of("x", "a"), b = Ref.of("x", "b"), bottom = Ref.of("x", "bottom");
            o.snapshot(Edge.of(top, "e", a));
            o.snapshot(Edge.of(top, "e", b));
            o.snapshot(Edge.of(a, "e", bottom));
            o.snapshot(Edge.of(b, "e", bottom));
            Result r = o.of(Closure.of("down", Admit.forward("e")), top);
            assertEquals(Set.of(top, a, b, bottom), r.refs());
            assertFalse(r.cycleDetected(), "a diamond is not a cycle");
        }

        @Test
        void setArgumentIsTheUnionAndIgnoresUnknownMembers() { // L21
            assertEquals(Set.of(PORT1, PORT2, CARD, SHELF, NE),
                equipment().reach(CONTAINING, Set.of(PORT1, PORT2, UNKNOWN)));
        }
    }

    @Nested
    class Ingest {
        @Test
        void deleteMakesAnOrphanedReferenceUnknown() {
            Oracle o = equipment();
            o.delete(Edge.of(LINK, "terminatesOn", PORT1));
            assertFalse(o.isKnown(LINK));
            assertEquals(Set.of(), o.reach(CONTAINED, Set.of(LINK)));
        }

        @Test
        void repeatedSnapshotAndDeleteAreNoOps() {
            Oracle o = equipment();
            Edge e = Edge.of(CARD, "hosts", PORT2);
            o.snapshot(e);
            assertEquals(5, o.edges().size());
            o.delete(e);
            o.delete(e);
            assertEquals(4, o.edges().size());
        }

        @Test
        void edgesDifferingOnlyInTypeAreDistinct() {
            Oracle o = new Oracle();
            o.snapshot(Edge.of(CARD, "hosts", PORT1));
            o.snapshot(Edge.of(CARD, "monitors", PORT1));
            o.delete(Edge.of(CARD, "monitors", PORT1));
            assertEquals(Set.of(CARD, PORT1), o.reach(CONTAINED, Set.of(CARD)));
        }
    }

    @Nested
    class Identity {
        @Test
        void emptyTypeOrIdIsMalformed() { // L7
            assertThrows(IllegalArgumentException.class, () -> Ref.of("", "P1"));
            assertThrows(IllegalArgumentException.class, () -> Ref.of("port", ""));
        }

        @Test
        void referencesDifferingInCaseOrWhitespaceAreDistinct() { // L8
            Oracle o = new Oracle();
            o.snapshot(Edge.of(Ref.of("card", "C1"), "hosts", Ref.of("port", "p1")));
            assertTrue(o.isKnown(Ref.of("port", "p1")));
            assertFalse(o.isKnown(Ref.of("port", "P1")));
            assertFalse(o.isKnown(Ref.of("port", "p1 ")));
        }
    }

    @Nested
    class Cycles {
        static final Ref A = Ref.of("x", "A"), B = Ref.of("x", "B"), C = Ref.of("x", "C");
        static final Ref ENTRY = Ref.of("x", "entry"), BELOW = Ref.of("x", "below");
        static final Closure DOWN = Closure.of("down", Admit.forward("e"));

        /** entry -> A -> B -> C -> A, and C -> below. */
        static Oracle triangle() {
            Oracle o = new Oracle();
            o.snapshot(Edge.of(ENTRY, "e", A));
            o.snapshot(Edge.of(A, "e", B));
            o.snapshot(Edge.of(B, "e", C));
            o.snapshot(Edge.of(C, "e", A));
            o.snapshot(Edge.of(C, "e", BELOW));
            return o;
        }

        @Test
        void everyMemberHasTheSameReachSet() {
            Oracle o = triangle();
            Set<Ref> expected = Set.of(A, B, C, BELOW);
            assertEquals(expected, o.reach(DOWN, Set.of(A)));
            assertEquals(expected, o.reach(DOWN, Set.of(B)));
            assertEquals(expected, o.reach(DOWN, Set.of(C)));
        }

        @Test
        void resultReachingTheCycleFromOutsideIsQualified() { // L27
            Result r = triangle().of(DOWN, ENTRY);
            assertEquals(List.of(Set.of(A, B, C)), r.cycles());
        }

        @Test
        void resultTheCycleDoesNotReachIsNotQualified() {
            assertFalse(triangle().of(DOWN, BELOW).cycleDetected());
        }

        @Test
        void cycleThroughAnUnadmittedTypeDoesNotQualify() { // L27: only admitted edges form cycles
            Oracle o = new Oracle();
            o.snapshot(Edge.of(A, "e", B));
            o.snapshot(Edge.of(B, "other", A));
            Result r = o.of(DOWN, A);
            assertEquals(Set.of(A, B), r.refs());
            assertFalse(r.cycleDetected());
        }

        @Test
        void cycleThroughTwoAdmittedTypesQualifies() {
            Oracle o = new Oracle();
            o.snapshot(Edge.of(A, "e", B));
            o.snapshot(Edge.of(A, "f", B)); // B -> A when f is walked in reverse
            Result r = o.of(Closure.of("mixed", Admit.forward("e"), Admit.reverse("f")), A);
            assertEquals(List.of(Set.of(A, B)), r.cycles());
        }

        @Test
        void selfLoopIsACycle() {
            Oracle o = new Oracle();
            o.snapshot(Edge.of(A, "e", A));
            assertEquals(List.of(Set.of(A)), o.of(DOWN, A).cycles());
        }

        @Test
        void twoDisjointCyclesAreSeparateComponents() {
            Oracle o = triangle();
            Ref d = Ref.of("x", "D"), e = Ref.of("x", "E");
            o.snapshot(Edge.of(BELOW, "e", d));
            o.snapshot(Edge.of(d, "e", e));
            o.snapshot(Edge.of(e, "e", d));
            Result r = o.of(DOWN, ENTRY);
            assertEquals(Set.of(Set.of(A, B, C), Set.of(d, e)), Set.copyOf(r.cycles()));
        }

        @Test
        void closureAdmittingBothDirectionsOfATypeIsRejected() { // L2
            assertThrows(IllegalArgumentException.class,
                () -> Closure.of("peers", Admit.forward("peers"), Admit.reverse("peers")));
        }
    }

    @Nested
    class Cardinality {
        static final CardinalityRule ONE_PARENT = new CardinalityRule(
            "one-parent", "hosts", End.OBJECT, Set.of("shelf", "card", "port"), 1, 1);
        static final CardinalityRule AT_MOST_ONE_PORT = new CardinalityRule(
            "at-most-one-port", "hosts", End.SUBJECT, Set.of("card"), null, 1);

        @Test
        void wellFormedTreeHasNoBreachesOnTheObjectRule() {
            Result r = equipment(List.of(ONE_PARENT)).of(CONTAINED, NE);
            assertEquals(Set.of(), r.breaches());
        }

        @Test
        void unlistedTypeIsNeverBreached() { // NE has no parent but is not a listed type
            Result r = equipment(List.of(ONE_PARENT)).of(CONTAINING, NE);
            assertEquals(Set.of(), r.breaches());
        }

        @Test
        void tooManyParentsIsABreach() {
            Oracle o = equipment(List.of(ONE_PARENT));
            Ref otherCard = Ref.of("card", "C2");
            o.snapshot(Edge.of(SHELF, "hosts", otherCard));
            o.snapshot(Edge.of(otherCard, "hosts", PORT2));
            Result r = o.of(CONTAINING, PORT2);
            assertEquals(Set.of(new Breach("one-parent", PORT2)), r.breaches());
        }

        @Test
        void tooFewParentsIsABreach() { // a port known only through terminatesOn has no hosts parent
            Oracle o = equipment(List.of(ONE_PARENT));
            Ref orphan = Ref.of("port", "P9");
            o.snapshot(Edge.of(LINK, "terminatesOn", orphan));
            Closure linkPorts = Closure.of("link-ports", Admit.forward("terminatesOn"), Admit.forward("hosts"));
            Result r = o.of(linkPorts, LINK);
            assertEquals(Set.of(new Breach("one-parent", orphan)), r.breaches());
        }

        @Test
        void ruleOnAnUnadmittedTypeIsNotReported() { // L27
            Oracle o = equipment(List.of(ONE_PARENT));
            Ref orphan = Ref.of("port", "P9");
            o.snapshot(Edge.of(LINK, "terminatesOn", orphan));
            Closure linkOnly = Closure.of("link-only", Admit.forward("terminatesOn"));
            assertEquals(Set.of(), o.of(linkOnly, LINK).breaches());
        }

        @Test
        void subjectEndRuleCountsOutgoingEdges() {
            Result r = equipment(List.of(AT_MOST_ONE_PORT)).of(CONTAINED, SHELF);
            assertEquals(Set.of(new Breach("at-most-one-port", CARD)), r.breaches());
        }

        @Test
        void everyBreachIsReportedWithItsRule() { // L28: the suite checks the floor of ten against this set
            Oracle o = new Oracle(List.of(ONE_PARENT));
            Ref card = Ref.of("card", "C1"), otherCard = Ref.of("card", "C2");
            Set<Ref> twelvePorts = new java.util.HashSet<>();
            for (int i = 0; i < 12; i++) {
                Ref port = Ref.of("port", "P" + i);
                twelvePorts.add(port);
                o.snapshot(Edge.of(card, "hosts", port));
                o.snapshot(Edge.of(otherCard, "hosts", port)); // every port now has two parents
            }
            Result r = o.of(CONTAINED, card);
            Set<Ref> expected = new java.util.HashSet<>(twelvePorts);
            expected.add(card); // C1 itself is in its result and has no hosts parent
            assertEquals(expected, r.breachesOf("one-parent"));
            assertEquals(Set.of(), r.breachesOf("at-most-one-port"));
        }

        @Test
        void ruleWithNoBoundIsRejected() {
            assertThrows(IllegalArgumentException.class,
                () -> new CardinalityRule("none", "hosts", End.OBJECT, Set.of("port"), null, null));
        }
    }
}
