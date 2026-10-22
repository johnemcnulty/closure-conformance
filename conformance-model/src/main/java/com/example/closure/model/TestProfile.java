package com.example.closure.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appendix A of the contract: the configuration a deployment stands up for the suite to run
 * against. Read the appendix for why each part is here; this class is only its machine-readable
 * form, and {@code TestProfileTest} checks the two agree in the ways a fixture depends on.
 *
 * <p>The resource types are named after Greek letters on purpose. A recognizable vocabulary would
 * invite a reader to reject a fixture's graph on domain grounds — the cyclic fixture needs a cycle
 * in a containment-like relationship — so each type means nothing beyond its role in the rules.
 */
public final class TestProfile {

    private TestProfile() {
    }

    // ---- resource types (A.1) ----

    /** Named by no rule; the top of a containment spine, so it bears no holder of its own. */
    public static final String ALPHA = "alpha";
    /** Bounded at the object end of holds and the subject end of tracks. */
    public static final String BETA = "beta";
    /** Bounded at the object end of holds; the type the cyclic fixture puts in a cycle. */
    public static final String GAMMA = "gamma";
    /** Named by no rule, so it may carry any number of edges; carries the hostile ids. */
    public static final String OMEGA = "omega";

    public static final Set<String> RESOURCE_TYPES = Set.of(ALPHA, BETA, GAMMA, OMEGA);

    // ---- edge types (A.2), by authored name ----

    public static final String HOLDS = "holds";
    /** Authored through its inverse: events carry {@code fed_by}, never {@code feeds}. */
    public static final String FED_BY = "fed_by";
    public static final String TRACKS = "tracks";
    /** Declares no inverse, and no closure admits it. */
    public static final String NOTES = "notes";

    public static final List<EdgeTypeDecl> EDGE_TYPES = List.of(
        EdgeTypeDecl.authoredForward(HOLDS, "held_by"),
        EdgeTypeDecl.authoredInverse("feeds", FED_BY),
        EdgeTypeDecl.authoredForward(TRACKS, "tracked_by"),
        EdgeTypeDecl.withoutInverse(NOTES));

    // ---- closures (A.3) ----

    public static final ClosureDecl CONTAINERS =
        ClosureDecl.stating("containers", "contents", Admit.reverse(HOLDS));
    public static final ClosureDecl CONTENTS = ClosureDecl.deriving("contents", CONTAINERS);

    public static final ClosureDecl SUPPLIERS =
        ClosureDecl.stating("suppliers", "consumers", Admit.forward(FED_BY));
    public static final ClosureDecl CONSUMERS = ClosureDecl.deriving("consumers", SUPPLIERS);

    public static final ClosureDecl DEPENDENTS =
        ClosureDecl.stating("dependents", "dependencies", Admit.forward(HOLDS), Admit.forward(TRACKS));
    public static final ClosureDecl DEPENDENCIES = ClosureDecl.deriving("dependencies", DEPENDENTS);

    public static final List<ClosureDecl> CLOSURES =
        List.of(CONTAINERS, CONTENTS, SUPPLIERS, CONSUMERS, DEPENDENTS, DEPENDENCIES);

    // ---- cardinality rules (A.4) ----

    /** Every beta and gamma sits in exactly one holder; both bounds bite. */
    public static final CardinalityRule ONE_HOLDER = new CardinalityRule(
        "one-holder", HOLDS, End.OBJECT, Set.of(BETA, GAMMA), 1, 1);

    /** A beta tracks at most two things; a maximum at the other end of a different type. */
    public static final CardinalityRule TRACKS_FANOUT = new CardinalityRule(
        "tracks-fanout", TRACKS, End.SUBJECT, Set.of(BETA), null, 2);

    public static final List<CardinalityRule> CARDINALITY_RULES = List.of(ONE_HOLDER, TRACKS_FANOUT);

    /** The floor on how many breaching references a qualification lists (L28). */
    public static final int BREACH_REPORT_FLOOR = 10;

    /** The ingest topic carries at least this many partitions (A.5). */
    public static final int MIN_INGEST_PARTITIONS = 2;

    // ---- lookups ----

    private static final Map<String, ClosureDecl> BY_NAME = byName();

    private static Map<String, ClosureDecl> byName() {
        Map<String, ClosureDecl> map = new LinkedHashMap<>();
        for (ClosureDecl declared : CLOSURES) {
            map.put(declared.name(), declared);
        }
        return Map.copyOf(map);
    }

    /** The declared closure of that name. */
    public static ClosureDecl declared(String name) {
        ClosureDecl found = BY_NAME.get(name);
        if (found == null) {
            throw new IllegalArgumentException("no closure named " + name + " in the profile");
        }
        return found;
    }

    /** The closure of that name, ready to query the oracle with. */
    public static Closure closure(String name) {
        return declared(name).closure();
    }

    /** The declared edge type whose authored name is this one. */
    public static EdgeTypeDecl edgeTypeAuthoredAs(String authoredName) {
        return EDGE_TYPES.stream()
            .filter(declared -> declared.authored().equals(authoredName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no edge type authored as " + authoredName));
    }

    /** Every authored name, which is the only vocabulary an ingest event may use (W23). */
    public static Set<String> authoredNames() {
        return EDGE_TYPES.stream().map(EdgeTypeDecl::authored).collect(java.util.stream.Collectors.toSet());
    }
}
