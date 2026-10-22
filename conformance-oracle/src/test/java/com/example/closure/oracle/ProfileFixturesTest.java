package com.example.closure.oracle;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.FED_BY;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.NOTES;
import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.model.TestProfile.TRACKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Closure;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Appendix A claims every fixture is writable against the profile. These tests are the evidence:
 * each builds the graph a fixture would publish and checks, against the oracle, that the
 * assertions the fixture intends to make actually hold and are not vacuous. A fixture that cannot
 * be written against the profile is evidence the profile is wrong (§7), and this is where that
 * shows up — before the suite harness exists.
 *
 * <p>Every graph here respects the invariant A.4 names: a beta or gamma that is not deliberately
 * provoking {@code one-holder} gets exactly one holder.
 */
class ProfileFixturesTest {

    static final Closure CONTENTS = TestProfile.closure("contents");
    static final Closure CONTAINERS = TestProfile.closure("containers");
    static final Closure SUPPLIERS = TestProfile.closure("suppliers");
    static final Closure CONSUMERS = TestProfile.closure("consumers");
    static final Closure DEPENDENTS = TestProfile.closure("dependents");

    static Oracle oracle() {
        return new Oracle(TestProfile.CARDINALITY_RULES);
    }

    static Ref ref(String type, String id) {
        return Ref.of(type, id);
    }

    @Nested
    class Fixture2TypeRestriction {
        final Ref a1 = ref(ALPHA, "A1");
        final Ref a2 = ref(ALPHA, "A2");
        final Ref b1 = ref(BETA, "B1");
        final Ref g1 = ref(GAMMA, "G1");
        final Ref g2 = ref(GAMMA, "G2");
        final Ref o1 = ref(OMEGA, "O1");
        final Ref o2 = ref(OMEGA, "O2");
        final Ref o3 = ref(OMEGA, "O3");

        /**
         * An unadmitted `tracks` edge sits above an admitted `holds` edge; `notes` makes O1 known
         * through a type no closure admits; and `O2 fed_by O3` makes O3 known through a type
         * `suppliers` admits in one direction only and `contents` does not admit at all.
         */
        Oracle graph() {
            Oracle o = oracle();
            o.snapshot(Edge.of(a1, HOLDS, b1));
            o.snapshot(Edge.of(b1, TRACKS, g1));
            o.snapshot(Edge.of(a2, HOLDS, g1));  // G1's one holder, outside A1's subtree
            o.snapshot(Edge.of(g1, HOLDS, g2));
            o.snapshot(Edge.of(b1, NOTES, o1));
            o.snapshot(Edge.of(o2, FED_BY, o3));
            return o;
        }

        @Test
        void traversalStopsAtTheUnadmittedEdge() { // L23
            Result r = graph().of(CONTENTS, a1);
            assertEquals(Set.of(a1, b1), r.refs());
            assertEquals(Set.of(), r.breaches(), "a clean graph must not qualify, or the fixture reads as a failure");
            assertFalse(r.cycleDetected());
        }

        @Test
        void theFarSideOfAnAdmittedTypeWalkedTheWrongWayReturnsItself() { // L15, L16
            Oracle o = graph();
            assertEquals(Set.of(o3), o.reach(SUPPLIERS, Set.of(o3)),
                "suppliers admits (fed_by, forward), so it cannot move from the object end");
            assertEquals(Set.of(o2, o3), o.reach(SUPPLIERS, Set.of(o2)),
                "and from the subject end it does move, so the assertion above is not vacuous");
        }

        @Test
        void aReferenceKnownOnlyThroughATypeNoClosureAdmitsReturnsItself() { // L12, L15, L16
            Oracle o = graph();
            Result r = o.of(CONTENTS, o1);
            assertEquals(Set.of(o1), r.refs());
            assertEquals(Set.of(), r.breaches());
            for (var declared : TestProfile.CLOSURES) {
                assertEquals(Set.of(o1), o.reach(declared.closure(), Set.of(o1)),
                    declared.name() + " must still know O1, which no closure can traverse to");
            }
        }

        @Test
        void aReferenceKnownOnlyThroughATypeThisClosureDoesNotAdmitReturnsItself() { // L12, L15
            assertEquals(Set.of(o3), graph().reach(CONTENTS, Set.of(o3)),
                "contents has no entry for fed_by at all, though suppliers does");
        }

        @Test
        void aReferenceInNoEdgeAtAllReturnsEmpty() { // L16: the control for the three cases above
            assertEquals(Set.of(), graph().reach(CONTENTS, Set.of(ref(OMEGA, "absent"))));
        }

        @Test
        void theSameGraphReachesFurtherUnderAClosureThatAdmitsTracks() { // the assertion is not vacuous
            assertEquals(Set.of(a1, b1, g1, g2), graph().of(DEPENDENTS, a1).refs());
        }
    }

    @Nested
    class Fixture3Cycle {
        final Ref a1 = ref(ALPHA, "A1");
        final Ref g0 = ref(GAMMA, "G0");
        final Ref g1 = ref(GAMMA, "G1");
        final Ref g2 = ref(GAMMA, "G2");
        final Ref g3 = ref(GAMMA, "G3");

        /** G1 -> G2 -> G3 -> G1 under `fed_by` forward, with G0 feeding into it from outside. */
        Oracle graph() {
            Oracle o = oracle();
            for (Ref g : Set.of(g0, g1, g2, g3)) {
                o.snapshot(Edge.of(a1, HOLDS, g)); // each gamma gets its one holder
            }
            o.snapshot(Edge.of(g0, FED_BY, g1));
            o.snapshot(Edge.of(g1, FED_BY, g2));
            o.snapshot(Edge.of(g2, FED_BY, g3));
            o.snapshot(Edge.of(g3, FED_BY, g1));
            return o;
        }

        @Test
        void everyMemberOfTheCycleHasTheSameReachSet() {
            Oracle o = graph();
            Set<Ref> cycle = Set.of(g1, g2, g3);
            assertEquals(cycle, o.of(SUPPLIERS, g1).refs());
            assertEquals(cycle, o.of(SUPPLIERS, g2).refs());
            assertEquals(cycle, o.of(SUPPLIERS, g3).refs());
        }

        @Test
        void aResultReachingTheCycleFromOutsideIsQualified() { // L27
            Result r = graph().of(SUPPLIERS, g0);
            assertEquals(Set.of(g0, g1, g2, g3), r.refs());
            assertEquals(List.of(Set.of(g1, g2, g3)), r.cycles(), "G0 leads into the cycle but is not on it");
        }

        @Test
        void aResultTheCycleDoesNotReachIsNotQualified() {
            assertFalse(graph().of(CONSUMERS, g0).cycleDetected());
        }

        @Test
        void aClosureNotAdmittingFedByReportsNoCycleOverTheSameInstance() { // L27's restriction
            Result r = graph().of(CONTENTS, g1);
            assertEquals(Set.of(g1), r.refs());
            assertFalse(r.cycleDetected());
        }
    }

    @Nested
    class Fixture4Cardinality {
        final Ref a1 = ref(ALPHA, "A1");
        final Ref a2 = ref(ALPHA, "A2");
        final Ref b1 = ref(BETA, "B1");
        final Ref b2 = ref(BETA, "B2");
        final Ref b3 = ref(BETA, "B3");
        final Ref o1 = ref(OMEGA, "O1");

        /** B1 has two holders, B2 none, O1 two but is unlisted, and B3 tracks three things. */
        Oracle graph() {
            Oracle o = oracle();
            o.snapshot(Edge.of(a1, HOLDS, b1));
            o.snapshot(Edge.of(a2, HOLDS, b1)); // breaches maxEdgesPerObject
            o.snapshot(Edge.of(b1, TRACKS, b2)); // B2 reaches no holder: breaches minEdgesPerObject
            o.snapshot(Edge.of(a1, HOLDS, o1));
            o.snapshot(Edge.of(a2, HOLDS, o1)); // two holders, but omega is unlisted
            o.snapshot(Edge.of(a1, HOLDS, b3));
            for (String id : Set.of("T1", "T2", "T3")) {
                Ref tracked = ref(GAMMA, id);
                o.snapshot(Edge.of(a1, HOLDS, tracked));
                o.snapshot(Edge.of(b3, TRACKS, tracked)); // three tracks edges: breaches maxEdgesPerSubject
            }
            return o;
        }

        @Test
        void tooManyHoldersBreachesTheMaximum() {
            Result r = graph().of(CONTAINERS, b1);
            assertEquals(Set.of(b1, a1, a2), r.refs());
            assertEquals(Set.of(b1), r.breachesOf("one-holder"));
        }

        @Test
        void noHolderBreachesTheMinimum() {
            assertTrue(graph().of(DEPENDENTS, b1).breachesOf("one-holder").contains(b2));
        }

        @Test
        void anUnlistedTypeWithTheSameCountIsNotQualified() {
            Result r = graph().of(CONTAINERS, o1);
            assertEquals(Set.of(o1, a1, a2), r.refs());
            assertEquals(Set.of(), r.breaches());
        }

        @Test
        void aClosureNotAdmittingTheRulesEdgeTypeReportsNothing() { // L27
            Result r = graph().of(SUPPLIERS, b1);
            assertEquals(Set.of(b1), r.refs(), "B1 is known, so this is not vacuous");
            assertEquals(Set.of(), r.breaches());
        }

        @Test
        void theSubjectEndRuleIsBreachedAndIsClosureRestrictedToo() {
            assertTrue(graph().of(DEPENDENTS, b3).breachesOf("tracks-fanout").contains(b3));
            assertEquals(Set.of(), graph().of(CONTENTS, b3).breaches(), "contents does not admit tracks");
        }
    }

    @Nested
    class Fixture4BreachFloor {
        @Test
        void oneResultCanHoldMoreBreachesThanTheReportingFloor() { // L28
            Oracle o = oracle();
            Ref a1 = ref(ALPHA, "A1");
            Ref a2 = ref(ALPHA, "A2");
            Set<Ref> betas = new HashSet<>();
            for (int i = 0; i < TestProfile.BREACH_REPORT_FLOOR + 2; i++) {
                Ref beta = ref(BETA, "B" + i);
                betas.add(beta);
                o.snapshot(Edge.of(a1, HOLDS, beta));
                o.snapshot(Edge.of(a2, HOLDS, beta)); // every beta now has two holders
            }
            Result r = o.of(CONTENTS, a1);
            assertEquals(betas, r.breachesOf("one-holder"));
            assertTrue(r.breachesOf("one-holder").size() > TestProfile.BREACH_REPORT_FLOOR);
        }
    }

    @Nested
    class Fixture5FarEnd {
        @Test
        void aReferenceOnlyEverAnObjectCarriesItsRealType() { // L11, L24
            Oracle o = oracle();
            Ref a1 = ref(ALPHA, "A1");
            Ref g1 = ref(GAMMA, "G1");
            o.snapshot(Edge.of(a1, HOLDS, g1));
            Result r = o.of(CONTENTS, a1);
            assertEquals(Set.of(a1, g1), r.refs());
            assertEquals(GAMMA, r.refs().stream().filter(x -> x.id().equals("G1")).findFirst().orElseThrow().type());
            assertEquals(Set.of(), r.breaches());
        }
    }

    @Nested
    class Fixture10ResultSize {
        @Test
        void aLargeFanOutCarriesNoQualification() { // W7: the result must be clean, not merely large
            Oracle o = oracle();
            Ref a1 = ref(ALPHA, "A1");
            for (int i = 0; i < 500; i++) {
                o.snapshot(Edge.of(a1, HOLDS, ref(BETA, "B" + i)));
            }
            Result r = o.of(CONTENTS, a1);
            assertEquals(501, r.refs().size());
            assertEquals(Set.of(), r.breaches());
            assertFalse(r.cycleDetected());
        }
    }

    @Nested
    class Fixture13EdgeId {
        @Test
        void twoEdgeTypesOverTheSamePairAreDistinctEdges() { // W21
            Oracle o = oracle();
            Ref a1 = ref(ALPHA, "A1");
            Ref b1 = ref(BETA, "B1");
            Ref g1 = ref(GAMMA, "G1");
            o.snapshot(Edge.of(a1, HOLDS, b1));
            o.snapshot(Edge.of(a1, HOLDS, g1));
            o.snapshot(Edge.of(b1, HOLDS, g1));
            o.snapshot(Edge.of(b1, TRACKS, g1));
            o.delete(Edge.of(b1, HOLDS, g1));
            assertTrue(o.edges().contains(Edge.of(b1, TRACKS, g1)), "deleting one must leave the other");
            assertEquals(Set.of(a1, b1, g1), o.of(DEPENDENTS, a1).refs());
        }
    }
}
