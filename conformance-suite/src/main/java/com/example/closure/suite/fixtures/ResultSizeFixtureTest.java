package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.HOLDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.oracle.Oracle;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.FixtureGraph;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.Instance;
import com.example.closure.suite.OfResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixture 10 (§7): a fan-out large enough to exceed a modest ceiling — {@code RESULT_SIZE_FANOUT}
 * betas beneath one alpha, each with that one holder, so the result is large and unqualified. The
 * response is either the whole result, or {@code result_too_large} carrying no references (W7). An
 * instance that never refuses passes on the first branch; one returning part of the result fails.
 * The expectation comes from the oracle, since the graph is too large to tabulate (suite-design §4).
 */
@Fixture
class ResultSizeFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();

    final Instance instance = Instance.get();

    @Test
    void aLargeResultIsReturnedWholeOrRefusedWithNothing() { // W7, W11
        GraphBuilder graph = new GraphBuilder(instance.runId().child("result-size"));
        Ref holder = graph.ref(ALPHA, "A");
        for (int i = 0; i < instance.config().resultSizeFanout(); i++) {
            graph.edge(holder, HOLDS, graph.ref(BETA, "B" + i));
        }
        FixtureGraph built = graph.build();
        Oracle oracle = new Oracle(TestProfile.CARDINALITY_RULES);
        built.edges().forEach(oracle::snapshot);
        Set<Ref> expected = oracle.reach(TestProfile.CONTENTS.closure(), Set.of(holder));
        built.publishTo(instance);

        OfResponse response = instance.of(CONTENTS, List.of(holder));
        switch (response.status()) {
            case 200 -> {
                List<Ref> listed = response.referenceList();
                assertEquals(listed.size(), new HashSet<>(listed).size(), "the result holds duplicates (L6)");
                Set<Ref> missing = new HashSet<>(expected);
                missing.removeAll(listed);
                assertTrue(missing.isEmpty(), () -> "a successful response carries the whole result (W7), but "
                    + missing.size() + " of " + expected.size() + " references are missing");
                assertEquals(expected, response.references());
                assertTrue(response.meta().path("qualifications").isEmpty(), "one holder each: the result is unqualified");
            }
            case 422 -> {
                JsonNode errors = response.errors();
                assertEquals("result_too_large", errors.path(0).path("code").asText(),
                    () -> "a 422 refuses with result_too_large (W7, W11): " + response.body());
                assertFalse(response.body().has("data"),
                    () -> "a refusal returns no references at all (W7): " + response.body().path("data").size() + " returned");
            }
            default -> fail("a large result is a 200 carrying all of it or a 422 refusing it (W7, W11), not "
                + response.status() + ": " + response.body());
        }
    }
}
