package com.example.closure.reference;

import java.util.Set;

/**
 * A deliberately broken reference instance (suite-design §5). Each variant plants one known defect,
 * guarded where it acts, and names the fixtures that must fail against it; {@link #CORRECT} plants
 * none. A passing run shows the suite accepts a correct implementation; a variant failing its
 * fixtures shows it rejects a wrong one.
 *
 * <p>A variant may instead be a <em>correct alternative</em>: an instance behaving differently from
 * {@link #CORRECT} in a way the contract allows, which names the fixtures that must still pass
 * against it. That is how a fixture's branches beyond the correct instance's own are exercised.
 *
 * <p>Fixtures are named by class, since that is how the self-test selects them. A variant may also
 * narrow its run to particular tests of those fixtures, when one failing test proves the claim and
 * running the rest would only add time.
 */
public enum Variant {

    CORRECT(Set.of()),

    /** Correct, but refuses any result over 100 references with {@code result_too_large} (W7). */
    CEILING(Set.of(), Set.of(), Set.of("ResultSizeFixtureTest")),
    /** Returns the first 100 references of a larger result, as though it were whole (W7). */
    TRUNCATES(Set.of("ResultSizeFixtureTest")),

    /** Traverses edge types the closure does not admit, in each direction it admits any. */
    IGNORES_ADMISSION(Set.of("TypeRestrictionFixtureTest", "LawPropertiesFixtureTest")),
    /** Gives the derived closure of each pair its carrier's pairs without flipping them (W4, L18). */
    UNFLIPPED_INVERSE(Set.of("LawPropertiesFixtureTest", "TypeRestrictionFixtureTest")),
    /**
     * Stops consuming at the first rejected event (W27). Narrowed to one rejection test per fixture:
     * once consumption stops, every later wait runs to the settle timeout, so the rest of each
     * fixture would fail too, but only because an earlier test's event halted consumption.
     */
    HALTS_ON_REJECT(Set.of("AuthorshipFixtureTest", "EdgeIdFixtureTest", "IngestKindFixtureTest"), Set.of(
        "AuthorshipFixtureTest#anEventUnderTheUnauthoredNameIsRejected",
        "EdgeIdFixtureTest#anInnerIdThatIsNotTheIdOfItsTripleIsRejected",
        "IngestKindFixtureTest#anInnerObjectThatIsNotAnEdgeIsRejected")),
    /** Returns {@code {x}} for an unknown {@code x} (L16). */
    NO_REFLEXIVE_UNKNOWN(Set.of("TypeRestrictionFixtureTest", "SetArgumentFixtureTest")),
    /** Applies an event whose inner id, key or resource disagrees with its triple (W25). */
    TRUSTS_TRIPLE(Set.of("EdgeIdFixtureTest")),
    /** Publishes a discovery document departing from Appendix A: {@code feeds} authored under its own name. */
    MISCONFIGURED(Set.of("ProfileFixtureTest")),
    /** Never reports {@code cycle_detected} (L25). */
    NO_CYCLES(Set.of("CycleFixtureTest")),
    /** Reports a rule's breaches on closures not admitting its edge type (L27). */
    UNRESTRICTED_BREACHES(Set.of("CardinalityFixtureTest")),
    /** Gives {@code total} as the number listed rather than the number breaching (L28). */
    SHORT_TOTAL(Set.of("CardinalityFixtureTest")),
    /** Lists one breaching reference whatever the total (L28). */
    ONE_BREACH_LISTED(Set.of("CardinalityFixtureTest")),
    /** The cycle diagnostic reports a group's members without its edges (W36). */
    EDGELESS_GROUPS(Set.of("CycleFixtureTest")),
    /** The cycle diagnostic lists edges among a group's members the closure does not admit (W36). */
    UNADMITTED_GROUP_EDGES(Set.of("CycleFixtureTest")),
    /** Returns a reference it only ever sees as an object under a placeholder type (L11, W1). */
    PLACEHOLDER_FAR_END(Set.of("FarEndFixtureTest")),
    /** Case-folds the ids of arguments, so references differing only in case collide (L8). */
    CASE_FOLDS(Set.of("IdentityFixtureTest")),
    /** Trims the ids it returns (L10). */
    TRIMS_IDS(Set.of("HostileReferencesFixtureTest")),
    /** Rejects a malformed argument without carrying it in {@code meta.argument} (W11). */
    OMITS_ARGUMENT(Set.of("ErrorsFixtureTest")),
    /** A redelivered snapshot retracts the edge it asserts, rather than changing nothing (W24). */
    TOGGLES_ON_REDELIVERY(Set.of("EdgeLifecycleFixtureTest")),
    /** A delete forgets its object even while another edge still holds it (W24, L12). */
    FORGETS_HELD_OBJECT(Set.of("ReorderingFixtureTest")),
    /** Accepts an event under the unauthored name of an edge type (W23). */
    ACCEPTS_UNAUTHORED(Set.of("AuthorshipFixtureTest")),
    /**
     * Applies records on several threads, keeping each key's order, and recomputes affected reach
     * sets outside the lock from a snapshot a concurrent change can make stale (W34). Fixture 15 is
     * probabilistic, so the snapshot is held briefly to make the overlap it needs likely.
     */
    RACY_RECOMPUTE(Set.of("ConcurrencyFixtureTest")),
    /** Applies an event of an unknown kind as a delete (W26). */
    KINDS_AS_DELETES(Set.of("IngestKindFixtureTest"));

    /** How many references {@link #CEILING} returns before refusing, and {@link #TRUNCATES} before cutting. */
    static final int LIMIT = 100;

    private final Set<String> caughtBy;
    private final Set<String> narrowedTo;
    private final Set<String> passes;

    Variant(Set<String> caughtBy) {
        this(caughtBy, Set.of());
    }

    Variant(Set<String> caughtBy, Set<String> narrowedTo) {
        this(caughtBy, narrowedTo, Set.of());
    }

    Variant(Set<String> caughtBy, Set<String> narrowedTo, Set<String> passes) {
        this.caughtBy = caughtBy;
        this.narrowedTo = narrowedTo;
        this.passes = passes;
        for (String test : narrowedTo) {
            if (!caughtBy.contains(test.substring(0, test.indexOf('#')))) {
                throw new IllegalArgumentException(test + " is not in a fixture " + this + " names");
            }
        }
    }

    /** The fixture classes that must fail against this variant. */
    public Set<String> caughtBy() {
        return caughtBy;
    }

    /**
     * Whether the fixtures catch this variant only some of the time, as races are caught (§7,
     * fixture 15): a single failure is conclusive and a pass only evidence, so the self-test gives
     * it several attempts.
     */
    public boolean probabilistic() {
        return this == RACY_RECOMPUTE;
    }

    /** The fixture classes that must pass against this variant: a correct alternative's. */
    public Set<String> passes() {
        return passes;
    }

    /** Particular tests, as {@code FixtureClass#method}, to run instead of whole fixtures; empty for whole fixtures. */
    public Set<String> narrowedTo() {
        return narrowedTo;
    }
}
