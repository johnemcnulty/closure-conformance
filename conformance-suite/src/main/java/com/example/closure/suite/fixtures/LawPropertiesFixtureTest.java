package com.example.closure.suite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.ClosureDecl;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.oracle.Result;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.GeneratedGraph;
import com.example.closure.suite.GeneratedGraph.Published;
import com.example.closure.suite.Instance;
import com.example.closure.suite.OfResponse;
import com.example.closure.suite.Qualifications;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;

/**
 * The laws quantified over all pairs and subsets, as properties over generated graphs (§7): L18,
 * L19, L21 and L23. Each try publishes a graph of its own and checks its law across every reference
 * and closure of it. Tries are few and shrinking is off, since every try publishes and polls a live
 * instance (suite-design §4); a failure reports the graph it ran on.
 */
@Fixture
class LawPropertiesFixtureTest {

    static final int TRIES = 8;

    final Instance instance = Instance.get();

    @Provide
    Arbitrary<GeneratedGraph.Spec> graphs() {
        return GeneratedGraph.specs();
    }

    static Set<Ref> refs(Map<String, Map<Ref, OfResponse>> answers, String closure, Ref ref) {
        return answers.get(closure).get(ref).references();
    }

    @Property(tries = TRIES, shrinking = ShrinkingMode.OFF)
    void inverse(@ForAll("graphs") GeneratedGraph.Spec spec) { // L18
        Published graph = GeneratedGraph.publish(spec, instance, "L18");
        var answers = graph.answers(instance);
        for (ClosureDecl a : TestProfile.CLOSURES) {
            String b = a.inverse();
            for (Ref x : graph.refs()) {
                for (Ref y : graph.refs()) {
                    assertEquals(refs(answers, a.name(), x).contains(y), refs(answers, b, y).contains(x),
                        () -> y + " ∈ " + a.name() + ".of(" + x + ") iff " + x + " ∈ " + b + ".of(" + y + "), over " + graph);
                }
            }
        }
    }

    @Property(tries = TRIES, shrinking = ShrinkingMode.OFF)
    void transitivity(@ForAll("graphs") GeneratedGraph.Spec spec) { // L19
        Published graph = GeneratedGraph.publish(spec, instance, "L19");
        var answers = graph.answers(instance);
        for (ClosureDecl a : TestProfile.CLOSURES) {
            for (Ref x : graph.refs()) {
                Set<Ref> fromX = refs(answers, a.name(), x);
                for (Ref y : fromX) {
                    assertTrue(fromX.containsAll(refs(answers, a.name(), y)),
                        () -> a.name() + ".of(" + y + ") ⊆ " + a.name() + ".of(" + x + "), since " + y + " is in it, over " + graph);
                }
            }
        }
    }

    @Property(tries = TRIES, shrinking = ShrinkingMode.OFF)
    void distributivity(@ForAll("graphs") GeneratedGraph.Spec spec) { // L21
        Published graph = GeneratedGraph.publish(spec, instance, "L21");
        var answers = graph.answers(instance);
        List<Set<Ref>> sets = new ArrayList<>();
        List<Ref> refs = graph.refs();
        for (int i = 0; i < refs.size(); i++) {
            for (int j = i + 1; j < refs.size(); j++) {
                sets.add(Set.of(refs.get(i), refs.get(j)));
            }
        }
        sets.add(Set.copyOf(graph.refs()));
        Set<Ref> withUnknown = new HashSet<>(graph.refs());
        withUnknown.add(graph.unknown());
        sets.add(withUnknown);
        for (ClosureDecl a : TestProfile.CLOSURES) {
            for (Set<Ref> s : sets) {
                Set<Ref> union = new HashSet<>();
                s.stream().filter(graph.refs()::contains).forEach(x -> union.addAll(refs(answers, a.name(), x)));
                assertEquals(union, instance.of(a.name(), s).references(),
                    () -> a.name() + ".of(" + s + ") is the union of its members' results, over " + graph);
            }
        }
    }

    @Property(tries = TRIES, shrinking = ShrinkingMode.OFF)
    void admittedPaths(@ForAll("graphs") GeneratedGraph.Spec spec) { // L23, L25–L28
        Published graph = GeneratedGraph.publish(spec, instance, "L23");
        var answers = graph.answers(instance);
        for (ClosureDecl a : TestProfile.CLOSURES) {
            for (Ref x : graph.refs()) {
                Result expected = graph.oracle().of(a.closure(), x);
                OfResponse response = answers.get(a.name()).get(x);
                assertEquals(expected.refs(), response.references(),
                    () -> a.name() + ".of(" + x + ") is exactly what admitted paths reach, over " + graph);
                Map<String, Set<Ref>> breaches = new HashMap<>();
                TestProfile.CARDINALITY_RULES.forEach(rule -> {
                    if (!expected.breachesOf(rule.id()).isEmpty()) {
                        breaches.put(rule.id(), expected.breachesOf(rule.id()));
                    }
                });
                Qualifications.assertQualifications(response.meta(), expected.cycles(), breaches);
            }
        }
    }
}
