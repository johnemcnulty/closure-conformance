package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Example;
import com.example.closure.suite.Example.Row;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.RunId;
import java.util.List;

/**
 * Fixture 5's graph and table (§7): a reference appearing only as the object of an edge, never as
 * a subject, is returned like any other (L24), with its own type (L11, W1). Implementations tend to
 * treat references they hold state for differently from ones they only see in edges; the contract
 * draws no such distinction.
 *
 * <pre>
 *   A1 holds B1   B1 holds G1     (G1 is the far end: the object of one edge, the subject of none)
 * </pre>
 */
public final class FarEnd {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();
    static final String DEPENDENTS = TestProfile.DEPENDENTS.name();

    private FarEnd() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("far-end"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref b1 = graph.ref(BETA, "B1");
        Ref g1 = graph.ref(GAMMA, "G1");
        graph.edge(a1, HOLDS, b1).edge(b1, HOLDS, g1);

        return new Example("far end", graph.build(), List.of(
            Row.of("theFarEndIsReachedWithItsOwnType", "L11, L24, W1", CONTENTS, a1, a1, b1, g1),
            Row.of("theFarEndIsReachedFromItsHolderUnderAnotherClosure", "L11, L24", DEPENDENTS, b1, b1, g1),
            Row.of("theFarEndIsKnownAndReturnsItself", "L12, L15", CONTENTS, g1, g1),
            Row.of("theFarEndReachesUpwardLikeAnyReference", "L24", CONTAINERS, g1, g1, b1, a1)));
    }
}
