package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.CardinalityRule;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.oracle.Oracle;
import com.example.closure.oracle.Result;
import com.example.closure.suite.fixtures.Examples;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every example fixture's hand-written table, checked against the oracle (suite-design §4). The
 * table and the oracle are two independent answers to the same question, so each tests the other.
 * Each table must also be sharp: removing any one of its edges must change some row, so no edge is
 * there for nothing and no row passes regardless of the graph.
 */
class ExampleTablesTest {

    static final RunId RUN = new RunId("check/");

    static Oracle oracle(Set<Edge> edges) {
        Oracle oracle = new Oracle(TestProfile.CARDINALITY_RULES);
        edges.forEach(oracle::snapshot);
        return oracle;
    }

    /** Whether the oracle's whole answer — result, cycles and breaches — is the row's. */
    static boolean agrees(Oracle oracle, Example.Row row) {
        Result result = oracle.of(TestProfile.closure(row.closure()), row.arguments());
        return result.refs().equals(row.expected())
            && new HashSet<>(result.cycles()).equals(new HashSet<>(row.cycles()))
            && TestProfile.CARDINALITY_RULES.stream().allMatch(rule ->
                result.breachesOf(rule.id()).equals(row.breaches().getOrDefault(rule.id(), Set.of())));
    }

    @TestFactory
    Stream<DynamicNode> everyTableAgreesWithTheOracle() { // suite-design §4
        return Examples.ALL.stream().map(make -> make.apply(RUN)).map(example -> {
            Oracle oracle = oracle(example.graph().edges());
            Stream<DynamicNode> rows = example.rows().stream().map(row -> DynamicTest.dynamicTest(row.displayName(), () -> {
                Result result = oracle.of(TestProfile.closure(row.closure()), row.arguments());
                assertEquals(row.expected(), result.refs(), row.assertion());
                assertEquals(new HashSet<>(result.cycles()), new HashSet<>(row.cycles()),
                    row.assertion() + ": the row's cyclic components are not the oracle's");
                for (CardinalityRule rule : TestProfile.CARDINALITY_RULES) {
                    assertEquals(result.breachesOf(rule.id()), row.breaches().getOrDefault(rule.id(), Set.of()),
                        row.assertion() + ": the row's breaches of " + rule.id() + " are not the oracle's");
                }
            }));
            Stream<DynamicNode> sharpness = example.graph().edges().stream().map(edge -> DynamicTest.dynamicTest(
                "some row depends on " + edge, () -> {
                    Set<Edge> without = new HashSet<>(example.graph().edges());
                    without.remove(edge);
                    Oracle smaller = oracle(without);
                    assertTrue(example.rows().stream().anyMatch(row -> !agrees(smaller, row)),
                        "no row changes when " + edge + " is removed, so the table does not show it was consumed");
                }));
            return DynamicContainer.dynamicContainer(example.name(), Stream.concat(rows, sharpness));
        });
    }
}
