package com.example.closure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Two kinds of check on Appendix A.
 *
 * <p>The first is internal consistency: the profile must be a configuration the contract permits,
 * so a typo in a closure's inverse or an admitted pair naming an unauthored type fails here rather
 * than as a baffling fixture failure later.
 *
 * <p>The second is adequacy. Appendix A claims each part of the profile is load-bearing — remove it
 * and some fixture becomes unwritable — and these tests pin the claims that are mechanically
 * checkable, so trimming the profile breaks a test instead of silently weakening a fixture.
 */
class TestProfileTest {

    @Nested
    class Consistency {
        @Test
        void everyClosureNamesADeclaredInverseAndThePairingIsSymmetric() { // L2, W4
            for (ClosureDecl declared : TestProfile.CLOSURES) {
                ClosureDecl inverse = TestProfile.declared(declared.inverse());
                assertEquals(declared.name(), inverse.inverse(),
                    declared.name() + " and " + inverse.name() + " must name each other");
            }
        }

        @Test
        void aPairsAdmittedPairsAreExactFlips() { // W4
            for (ClosureDecl declared : TestProfile.CLOSURES) {
                Closure inverse = TestProfile.closure(declared.inverse());
                Set<Admit> flipped = declared.closure().admits().stream().map(Admit::flip).collect(Collectors.toSet());
                assertEquals(flipped, inverse.admits(), declared.name() + " against its inverse");
            }
        }

        @Test
        void exactlyOneClosureOfEachPairStatesItsAdmittedPairs() { // W4, fixture 1
            for (ClosureDecl declared : TestProfile.CLOSURES) {
                ClosureDecl inverse = TestProfile.declared(declared.inverse());
                assertTrue(declared.statesAdmits() != inverse.statesAdmits(),
                    declared.name() + " and " + inverse.name() + " must disagree on who states admits");
            }
        }

        @Test
        void noClosureAdmitsBothDirectionsOfOneEdgeType() { // L2
            for (ClosureDecl declared : TestProfile.CLOSURES) {
                for (Admit admit : declared.closure().admits()) {
                    assertFalse(declared.closure().admits().contains(admit.flip()), declared.name());
                }
            }
        }

        @Test
        void everyAdmittedPairNamesAnAuthoredEdgeType() { // W4: admitted pairs use authored names
            Set<String> authored = TestProfile.authoredNames();
            for (ClosureDecl declared : TestProfile.CLOSURES) {
                for (Admit admit : declared.closure().admits()) {
                    assertTrue(authored.contains(admit.edgeType()),
                        declared.name() + " admits " + admit.edgeType() + ", which is not an authored name");
                }
            }
        }

        @Test
        void everyEdgeTypeAuthorsExactlyOneOfItsNames() { // W23
            for (EdgeTypeDecl declared : TestProfile.EDGE_TYPES) {
                Set<String> names = new HashSet<>();
                names.add(declared.name());
                declared.inverse().ifPresent(names::add);
                assertTrue(names.contains(declared.authored()), declared.name());
                assertEquals(declared.inverse().isPresent(), declared.unauthored().isPresent(),
                    declared.name() + " has an unauthored name only if it declares an inverse");
            }
        }

        @Test
        void edgeTypeNamesAreUniqueAcrossBothDirections() { // a shared name would make a key ambiguous
            Set<String> seen = new HashSet<>();
            for (EdgeTypeDecl declared : TestProfile.EDGE_TYPES) {
                assertTrue(seen.add(declared.name()), "duplicate edge type name " + declared.name());
                declared.inverse().ifPresent(inverse ->
                    assertTrue(seen.add(inverse), "duplicate edge type name " + inverse));
            }
        }

        @Test
        void everyRuleNamesAnAuthoredEdgeTypeAndDeclaredResourceTypes() { // W4
            for (CardinalityRule rule : TestProfile.CARDINALITY_RULES) {
                assertTrue(TestProfile.authoredNames().contains(rule.edgeType()),
                    rule.id() + " bounds " + rule.edgeType() + ", which is not an authored name");
                assertTrue(TestProfile.RESOURCE_TYPES.containsAll(rule.types()),
                    rule.id() + " names resource types outside the profile: " + rule.types());
            }
        }

        @Test
        void ruleIdentifiersAreUnique() { // a qualification names a rule by its identifier (L28)
            Set<String> ids = TestProfile.CARDINALITY_RULES.stream()
                .map(CardinalityRule::id).collect(Collectors.toSet());
            assertEquals(TestProfile.CARDINALITY_RULES.size(), ids.size());
        }

        @Test
        void anUndeclaredClosureIsNotSilentlyAccepted() {
            assertThrows(IllegalArgumentException.class, () -> TestProfile.closure("no-such-closure"));
            assertThrows(IllegalArgumentException.class, () -> TestProfile.edgeTypeAuthoredAs("feeds")); // unauthored
        }
    }

    /**
     * W4 restricts the <em>published</em> document to authored names, but a configuration may
     * accept either name and normalize. These check that the mapping is what W4 says it is, since
     * the discovery client relies on it to validate what an instance reports.
     */
    @Nested
    class NameNormalization {
        static final EdgeTypeDecl FEEDS = TestProfile.edgeTypeAuthoredAs(TestProfile.FED_BY);
        static final EdgeTypeDecl HOLDS = TestProfile.edgeTypeAuthoredAs(TestProfile.HOLDS);

        @Test
        void theAuthoredNameKeepsItsDirection() {
            assertEquals(Admit.forward(TestProfile.FED_BY), FEEDS.normalize(TestProfile.FED_BY, Direction.FORWARD));
            assertEquals(Admit.reverse(TestProfile.FED_BY), FEEDS.normalize(TestProfile.FED_BY, Direction.REVERSE));
        }

        @Test
        void theUnauthoredNameFlipsIt() { // (feeds, forward) and (fed_by, reverse) are one traversal
            assertEquals(Admit.reverse(TestProfile.FED_BY), FEEDS.normalize("feeds", Direction.FORWARD));
            assertEquals(Admit.forward(TestProfile.FED_BY), FEEDS.normalize("feeds", Direction.REVERSE));
        }

        @Test
        void fourSpellingsCollapseToTheTwoTraversals() {
            Set<Admit> normalized = java.util.stream.Stream.of(
                    FEEDS.normalize(TestProfile.FED_BY, Direction.FORWARD),
                    FEEDS.normalize(TestProfile.FED_BY, Direction.REVERSE),
                    FEEDS.normalize("feeds", Direction.FORWARD),
                    FEEDS.normalize("feeds", Direction.REVERSE))
                .collect(Collectors.toSet()); // a set literal would reject the duplicates, which is the point
            assertEquals(2, normalized.size());
            assertEquals(Set.of(Admit.forward(TestProfile.FED_BY), Admit.reverse(TestProfile.FED_BY)), normalized);
        }

        @Test
        void twoSpellingsOfOneTraversalNormalizeToTheSamePair() {
            assertEquals(HOLDS.normalize(TestProfile.HOLDS, Direction.REVERSE),
                HOLDS.normalize("held_by", Direction.FORWARD));
        }

        @Test
        void normalizingFirstIsWhatLetsL2CatchTheAliasedCase() {
            Admit a = FEEDS.normalize(TestProfile.FED_BY, Direction.FORWARD);
            Admit b = FEEDS.normalize("feeds", Direction.FORWARD); // reads like another type; is the other direction
            assertThrows(IllegalArgumentException.class, () -> Closure.of("aliased", a, b));
        }

        @Test
        void aNameTheTypeDoesNotHaveIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> FEEDS.normalize("tracks", Direction.FORWARD));
            assertThrows(IllegalArgumentException.class,
                () -> TestProfile.edgeTypeAuthoredAs(TestProfile.NOTES).normalize("noted_by", Direction.FORWARD));
        }
    }

    @Nested
    class AdequacyForTheFixtures {
        @Test
        void oneEdgeTypeIsAuthoredThroughItsInverse() { // fixture 12 has something to reject
            EdgeTypeDecl feeds = TestProfile.edgeTypeAuthoredAs(TestProfile.FED_BY);
            assertEquals("feeds", feeds.name());
            assertEquals("feeds", feeds.unauthored().orElseThrow());
        }

        @Test
        void oneEdgeTypeDeclaresNoInverse() { // fixture 1 checks W4's "if declared" branch
            assertTrue(TestProfile.EDGE_TYPES.stream().anyMatch(t -> t.inverse().isEmpty()));
        }

        @Test
        void oneEdgeTypeIsAdmittedByNoClosure() { // fixture 2: known (L12) but unreachable
            assertEquals(Set.of(TestProfile.NOTES), unadmittedTypes());
        }

        @Test
        void oneClosureAdmitsAStrictSubsetOfTheDeclaredTypes() { // fixture 2: L23 has something to prune
            Set<String> admitted = admittedTypes(TestProfile.CONTENTS);
            assertEquals(Set.of(TestProfile.HOLDS), admitted);
            assertTrue(admitted.size() < TestProfile.authoredNames().size());
        }

        @Test
        void oneClosureAdmitsMoreThanOneTypeSoAPathMayCrossThem() {
            assertEquals(Set.of(TestProfile.HOLDS, TestProfile.TRACKS), admittedTypes(TestProfile.DEPENDENTS));
        }

        @Test
        void theCyclingTypeIsAdmittedBySomeClosuresAndNotOthers() { // fixture 3
            assertTrue(admittedTypes(TestProfile.SUPPLIERS).contains(TestProfile.FED_BY));
            assertFalse(admittedTypes(TestProfile.CONTENTS).contains(TestProfile.FED_BY));
        }

        @Test
        void oneRuleCarriesBothBounds() { // fixture 4 provokes each bound of one rule
            assertEquals(1, TestProfile.ONE_HOLDER.min());
            assertEquals(1, TestProfile.ONE_HOLDER.max());
        }

        @Test
        void theRulesBoundBothEnds() {
            Set<End> ends = TestProfile.CARDINALITY_RULES.stream()
                .map(CardinalityRule::end).collect(Collectors.toSet());
            assertEquals(Set.of(End.OBJECT, End.SUBJECT), ends);
        }

        @Test
        void someResourceTypeIsNamedByNoRule() { // fixture 4: a rule reaches only the types it names
            Set<String> bounded = TestProfile.CARDINALITY_RULES.stream()
                .flatMap(rule -> rule.types().stream()).collect(Collectors.toSet());
            Set<String> unbounded = new HashSet<>(TestProfile.RESOURCE_TYPES);
            unbounded.removeAll(bounded);
            assertEquals(Set.of(TestProfile.ALPHA, TestProfile.OMEGA), unbounded);
        }

        @Test
        void everyRuleHasAClosureThatAdmitsItAndOneThatDoesNot() { // fixture 4's two closure cases
            for (CardinalityRule rule : TestProfile.CARDINALITY_RULES) {
                assertTrue(TestProfile.CLOSURES.stream().anyMatch(c -> admittedTypes(c).contains(rule.edgeType())),
                    "no closure admits " + rule.edgeType() + ", so " + rule.id() + " can never be reported");
                assertTrue(TestProfile.CLOSURES.stream().noneMatch(c -> admittedTypes(c).contains(rule.edgeType()))
                        || TestProfile.CLOSURES.stream().anyMatch(c -> !admittedTypes(c).contains(rule.edgeType())),
                    "every closure admits " + rule.edgeType() + ", so L27's restriction is untestable");
            }
        }

        private static Set<String> admittedTypes(ClosureDecl declared) {
            return declared.closure().admits().stream().map(Admit::edgeType).collect(Collectors.toSet());
        }

        private static Set<String> unadmittedTypes() {
            Set<String> unadmitted = new HashSet<>(TestProfile.authoredNames());
            TestProfile.CLOSURES.forEach(c -> unadmitted.removeAll(admittedTypes(c)));
            return unadmitted;
        }
    }
}
