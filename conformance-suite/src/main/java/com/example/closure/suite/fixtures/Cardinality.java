package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.model.TestProfile.TRACKS;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Example;
import com.example.closure.suite.Example.Row;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.RunId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fixture 4's graphs and tables (§7): each bound of each declared rule provoked, a qualification
 * naming the rule and the references breaching it (L28, W10), and the two ways a breach goes
 * unreported — a type the rule does not list, and a closure not admitting the rule's edge type
 * (L27). A second graph holds more breaches of one rule than the reporting floor of ten (L28).
 *
 * <pre>
 *   A1 holds B1   A2 holds B1         B1 has two holders: one-holder's maximum
 *   B1 tracks B2                      B2 has no holder: one-holder's minimum
 *   A1 holds O1   A2 holds O1         O1 has two holders too, but omega is not listed
 *   A1 holds B3   A1 holds T1..T3     B3 tracks T1, T2, T3: tracks-fanout's maximum
 *   B3 tracks T1..T3
 * </pre>
 */
public final class Cardinality {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();
    static final String SUPPLIERS = TestProfile.SUPPLIERS.name();
    static final String DEPENDENTS = TestProfile.DEPENDENTS.name();
    static final String ONE_HOLDER = TestProfile.ONE_HOLDER.id();
    static final String TRACKS_FANOUT = TestProfile.TRACKS_FANOUT.id();

    private Cardinality() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("cardinality"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref a2 = graph.ref(ALPHA, "A2");
        Ref b1 = graph.ref(BETA, "B1");
        Ref b2 = graph.ref(BETA, "B2");
        Ref b3 = graph.ref(BETA, "B3");
        Ref o1 = graph.ref(OMEGA, "O1");
        List<Ref> tracked = List.of(graph.ref(GAMMA, "T1"), graph.ref(GAMMA, "T2"), graph.ref(GAMMA, "T3"));
        graph.edge(a1, HOLDS, b1).edge(a2, HOLDS, b1)
            .edge(b1, TRACKS, b2)
            .edge(a1, HOLDS, o1).edge(a2, HOLDS, o1)
            .edge(a1, HOLDS, b3);
        for (Ref t : tracked) {
            graph.edge(a1, HOLDS, t).edge(b3, TRACKS, t);
        }

        List<Ref> underA1 = new ArrayList<>(List.of(a1, b1, o1, b3));
        underA1.addAll(tracked);
        List<Ref> fromB3 = new ArrayList<>(List.of(b3));
        fromB3.addAll(tracked);

        return new Example("cardinality", graph.buildBreaching(), List.of(
            Row.of("tooManyHoldersBreachesTheMaximum", "L25, L28", CONTAINERS, b1, b1, a1, a2)
                .withBreaches(ONE_HOLDER, b1),
            Row.of("noHolderBreachesTheMinimum", "L25, L28", DEPENDENTS, b1, b1, b2)
                .withBreaches(ONE_HOLDER, b1, b2),
            Row.of("aBreachSeveralHopsAwayQualifiesTheResult", "L27", CONTENTS, a2, a2, b1, o1)
                .withBreaches(ONE_HOLDER, b1),
            Row.of("aResultHoldingBreachesAndCleanReferencesListsOnlyTheBreaches", "L28", CONTENTS, a1,
                underA1.toArray(Ref[]::new)).withBreaches(ONE_HOLDER, b1),
            Row.of("anUnlistedTypeWithTheSameCountIsNotQualified", "L27", CONTAINERS, o1, o1, a1, a2),
            Row.of("aClosureNotAdmittingTheRulesEdgeTypeReportsNothing", "L27", SUPPLIERS, b1, b1),
            Row.of("theSubjectEndRuleIsBreached", "L25, L28", DEPENDENTS, b3, fromB3.toArray(Ref[]::new))
                .withBreaches(TRACKS_FANOUT, b3),
            Row.of("theSubjectEndBreacherHasItsOneHolder", "L27", CONTAINERS, b3, b3, a1),
            Row.of("theSubjectEndRuleGoesUnreportedWhereTracksIsNotAdmitted", "L27", CONTENTS, b3, b3)));
    }

    /** More breaches of one rule than the floor: twelve betas, each with two holders (L28). */
    public static Example floor(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("cardinality-floor"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref a2 = graph.ref(ALPHA, "A2");
        Set<Ref> betas = new HashSet<>();
        for (int i = 0; i < TestProfile.BREACH_REPORT_FLOOR + 2; i++) {
            Ref beta = graph.ref(BETA, "B" + i);
            betas.add(beta);
            graph.edge(a1, HOLDS, beta).edge(a2, HOLDS, beta);
        }
        Set<Ref> underA1 = new HashSet<>(betas);
        underA1.add(a1);
        Set<Ref> underA2 = new HashSet<>(betas);
        underA2.add(a2);

        return new Example("cardinality floor", graph.buildBreaching(), List.of(
            Row.of("moreBreachesThanTheFloorListAtLeastTenAndCountThemAll", "L28", CONTENTS, a1,
                underA1.toArray(Ref[]::new)).withBreaches(ONE_HOLDER, betas),
            Row.of("theSameBreachesFromTheOtherHolder", "L28", CONTENTS, a2, underA2.toArray(Ref[]::new))
                .withBreaches(ONE_HOLDER, betas)));
    }
}
