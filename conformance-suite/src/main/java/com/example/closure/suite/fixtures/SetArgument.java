package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Example;
import com.example.closure.suite.Example.Row;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.RunId;
import java.util.List;
import java.util.Set;

/**
 * Fixture 8's graph and table (§7): a set argument returns exactly the union of its members taken
 * singly (L21), an empty set returns empty (L17), and a set mixing known and unknown references is
 * answered, not rejected (L14). Two disjoint spines, so a union can be told from either part.
 *
 * <pre>
 *   A1 holds B1   B1 holds G1
 *   A2 holds B2
 * </pre>
 */
public final class SetArgument {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();

    private SetArgument() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("set-argument"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref b1 = graph.ref(BETA, "B1");
        Ref g1 = graph.ref(GAMMA, "G1");
        Ref a2 = graph.ref(ALPHA, "A2");
        Ref b2 = graph.ref(BETA, "B2");
        Ref unknown = graph.ref(OMEGA, "unknown");
        graph.edge(a1, HOLDS, b1).edge(b1, HOLDS, g1).edge(a2, HOLDS, b2);

        return new Example("set argument", graph.build(), List.of(
            Row.of("theFirstSpineTakenSingly", "L21", CONTENTS, a1, a1, b1, g1),
            Row.of("theSecondSpineTakenSingly", "L21", CONTENTS, a2, a2, b2),
            Row.ofSet("bothSpinesTogetherAreTheUnion", "L21", CONTENTS, Set.of(a1, a2), a1, b1, g1, a2, b2),
            Row.ofSet("membersPartWayDownEachSpineAreTheUnionOfTheirReach", "L21", CONTENTS, Set.of(b1, a2),
                b1, g1, a2, b2),
            Row.ofSet("overlappingMembersAreTheUnionWithoutDuplicates", "L6, L21", CONTENTS, Set.of(a1, b1),
                a1, b1, g1),
            Row.ofSet("aSetUnderTheInverseClosureIsTheUnionToo", "L21", CONTAINERS, Set.of(g1, b2), g1, b1, a1, b2, a2),
            Row.ofSet("anEmptySetReturnsEmpty", "L17", CONTENTS, Set.of()),
            Row.ofSet("aSetMixingKnownAndUnknownIsAnswered", "L14, L21", CONTENTS, Set.of(a1, unknown), a1, b1, g1),
            Row.ofSet("aSetOfOnlyUnknownsReturnsEmpty", "L14, L16", CONTENTS, Set.of(unknown))));
    }
}
