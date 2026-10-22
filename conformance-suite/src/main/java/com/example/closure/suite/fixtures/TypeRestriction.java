package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.FED_BY;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.NOTES;
import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.model.TestProfile.TRACKS;

import com.example.closure.model.ClosureDecl;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Example;
import com.example.closure.suite.Example.Row;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.RunId;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixture 2's graph and expectation table (§7). An unadmitted {@code tracks} edge sits above an
 * admitted {@code holds} edge, so {@code contents} stops at it (L23). Then three ways a closure
 * declines to move from a known reference, each of which must return exactly that reference
 * (L15, L16), and a reference in no edge at all, which returns empty — the control (L16):
 *
 * <pre>
 *   A1 holds B1   B1 tracks G1   G1 holds G2      A2 holds G1   (G1's one holder, outside A1)
 *   B1 notes O1                                   (notes: admitted by no closure)
 *   O2 fed_by O3                                  (suppliers admits it forward only; contents not at all)
 * </pre>
 *
 * Every beta and gamma has exactly one holder, so no result is qualified (A.4).
 */
public final class TypeRestriction {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();
    static final String SUPPLIERS = TestProfile.SUPPLIERS.name();
    static final String DEPENDENTS = TestProfile.DEPENDENTS.name();

    private TypeRestriction() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("type-restriction"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref a2 = graph.ref(ALPHA, "A2");
        Ref b1 = graph.ref(BETA, "B1");
        Ref g1 = graph.ref(GAMMA, "G1");
        Ref g2 = graph.ref(GAMMA, "G2");
        Ref o1 = graph.ref(OMEGA, "O1");
        Ref o2 = graph.ref(OMEGA, "O2");
        Ref o3 = graph.ref(OMEGA, "O3");
        Ref absent = graph.ref(OMEGA, "absent");
        graph.edge(a1, HOLDS, b1)
            .edge(b1, TRACKS, g1)
            .edge(g1, HOLDS, g2)
            .edge(a2, HOLDS, g1)
            .edge(b1, NOTES, o1)
            .edge(o2, FED_BY, o3);

        List<Row> rows = new ArrayList<>(List.of(
            Row.of("traversalStopsAtTheUnadmittedEdge", "L23", CONTENTS, a1, a1, b1),
            Row.of("theAdmittedEdgeBeyondItIsTraversedFromItsOwnStart", "L23", CONTENTS, g1, g1, g2),
            Row.of("theUnadmittedEdgeIsNotTraversedBackwardsEither", "L23", CONTAINERS, g1, g1, a2),
            Row.of("aClosureAdmittingTheTypeCrossesIt", "L23", DEPENDENTS, a1, a1, b1, g1, g2),
            Row.of("theFarSideOfATypeAdmittedOnlyTheOtherWayReturnsItself", "L15, L16", SUPPLIERS, o3, o3),
            Row.of("theNearSideOfThatTypeDoesMove", "L23", SUPPLIERS, o2, o2, o3),
            Row.of("aReferenceKnownOnlyThroughATypeThisClosureDoesNotAdmitReturnsItself", "L12, L15", CONTENTS, o3, o3),
            Row.of("aReferenceInNoEdgeAtAllReturnsEmpty", "L16", CONTENTS, absent)));
        for (ClosureDecl closure : TestProfile.CLOSURES) {
            rows.add(Row.of("aReferenceKnownOnlyThroughATypeNoClosureAdmitsReturnsItself", "L12, L15, L16",
                closure.name(), o1, o1));
        }
        return new Example("type restriction", graph.build(), rows);
    }
}
