package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.FED_BY;
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
 * Fixture 3's graph and table (§7): a cycle of {@code fed_by} edges, with one gamma feeding into
 * it from outside. The result is still returned (L26); every member of the cycle has the same reach
 * set; a result containing the cycle is qualified, naming references on it (L28), whether its
 * argument is on the cycle or reaches it from outside (L27); a result the cycle does not reach is
 * not; and {@code contents}, which does not admit {@code fed_by}, reports nothing over the same
 * instance (L27).
 *
 * <pre>
 *   G0 fed_by G1   G1 fed_by G2   G2 fed_by G3   G3 fed_by G1     (the cycle is G1, G2, G3)
 *   A1 holds G0, G1, G3   G1 holds G2                             (each gamma's one holder)
 * </pre>
 *
 * {@code G1 holds G2} runs between two members of the cycle under a type {@code suppliers} does not
 * admit, so the cycle diagnostic must leave it out of the group's edges (W36).
 */
public final class Cycle {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String SUPPLIERS = TestProfile.SUPPLIERS.name();
    static final String CONSUMERS = TestProfile.CONSUMERS.name();

    private Cycle() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("cycle"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref g0 = graph.ref(GAMMA, "G0");
        Ref g1 = graph.ref(GAMMA, "G1");
        Ref g2 = graph.ref(GAMMA, "G2");
        Ref g3 = graph.ref(GAMMA, "G3");
        for (Ref g : List.of(g0, g1, g3)) {
            graph.edge(a1, HOLDS, g);
        }
        graph.edge(g1, HOLDS, g2);
        graph.edge(g0, FED_BY, g1).edge(g1, FED_BY, g2).edge(g2, FED_BY, g3).edge(g3, FED_BY, g1);

        return new Example("cycle", graph.build(), List.of(
            Row.of("aResultOverACycleIsReturnedAndQualified", "L25, L26, L28", SUPPLIERS, g1, g1, g2, g3)
                .withCycle(g1, g2, g3),
            Row.of("aSecondMemberHasTheSameReachSet", "L19, L26", SUPPLIERS, g2, g1, g2, g3).withCycle(g1, g2, g3),
            Row.of("aThirdMemberHasTheSameReachSet", "L19, L26", SUPPLIERS, g3, g1, g2, g3).withCycle(g1, g2, g3),
            Row.of("aResultReachingTheCycleFromOutsideIsQualified", "L27, L28", SUPPLIERS, g0, g0, g1, g2, g3)
                .withCycle(g1, g2, g3),
            Row.of("theInverseClosureReportsTheSameCycle", "L18, L27", CONSUMERS, g1, g1, g2, g3, g0)
                .withCycle(g1, g2, g3),
            Row.of("aResultTheCycleDoesNotReachIsNotQualified", "L27", CONSUMERS, g0, g0),
            Row.of("aClosureNotAdmittingTheCyclesTypeReportsNoCycle", "L27", CONTENTS, a1, a1, g0, g1, g2, g3),
            Row.of("theCycleIsInvisibleFromAMemberUnderThatClosure", "L27", CONTENTS, g1, g1, g2)));
    }
}
