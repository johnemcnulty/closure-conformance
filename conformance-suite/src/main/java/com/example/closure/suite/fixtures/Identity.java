package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Example;
import com.example.closure.suite.Example.Row;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.RunId;
import java.util.List;
import java.util.Locale;

/**
 * Fixture 6's graph and table (§7): references differing only in case, whitespace or Unicode
 * normalization are distinct, because equality is byte equality of the canonical form (L8). Two
 * holders each hold one of a case pair, so a merged pair would show in either holder's result; the
 * other variants are never published, so each must come back unknown.
 *
 * <pre>
 *   A1 holds Ox       A2 holds ox       A3 holds é (U+00E9, composed)
 * </pre>
 */
public final class Identity {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();

    private Identity() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("identity"));
        Ref a1 = graph.ref(ALPHA, "A1");
        Ref a2 = graph.ref(ALPHA, "A2");
        Ref a3 = graph.ref(ALPHA, "A3");
        Ref upper = graph.ref(OMEGA, "Ox");
        Ref lower = graph.ref(OMEGA, "ox");
        Ref composed = graph.ref(OMEGA, "é");
        graph.edge(a1, HOLDS, upper).edge(a2, HOLDS, lower).edge(a3, HOLDS, composed);

        return new Example("identity", graph.build(), List.of(
            Row.of("aCasePairAreTwoReferences", "L8", CONTENTS, a1, a1, upper),
            Row.of("theOtherOfTheCasePairIsSeparate", "L8", CONTENTS, a2, a2, lower),
            Row.of("eachOfTheCasePairHasItsOwnHolder", "L8", CONTAINERS, lower, lower, a2),
            Row.of("aTrailingSpaceIsADifferentReference", "L8", CONTENTS, graph.ref(OMEGA, "Ox ")),
            Row.of("aLeadingSpaceIsADifferentReference", "L8", CONTENTS, graph.ref(OMEGA, " Ox")),
            Row.of("theComposedFormIsKnown", "L8", CONTAINERS, composed, composed, a3),
            Row.of("theDecomposedFormIsADifferentReference", "L8", CONTENTS, graph.ref(OMEGA, "é")),
            Row.of("aTypeDifferingInCaseIsADifferentReference", "L8", CONTENTS,
                Ref.of(OMEGA.toUpperCase(Locale.ROOT), upper.id()))));
    }
}
