package com.example.closure.suite;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.oracle.Oracle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * A generated graph for the law properties (§7): a few references of the profile's types and edges
 * of its authored edge types among them, self-loops included, so instances come out acyclic and
 * cyclic alike. Each try is published under its own run-id prefix (suite-design §4), and its
 * expectations come from the oracle.
 */
public final class GeneratedGraph {

    /** One edge of a generated graph, by node index. */
    public record EdgeSpec(int subject, String edgeType, int object) {
    }

    /** A generated graph: each node's type, and its edges. */
    public record Spec(List<String> types, Set<EdgeSpec> edges) {
        public Spec {
            types = List.copyOf(types);
            edges = Set.copyOf(edges);
        }
    }

    static final int MAX_NODES = 7;
    static final int MAX_EDGES = 12;

    private static final AtomicInteger TRIES = new AtomicInteger();

    private GeneratedGraph() {
    }

    public static Arbitrary<Spec> specs() {
        List<String> types = List.copyOf(new TreeSet<>(TestProfile.RESOURCE_TYPES));
        List<String> edgeTypes = List.copyOf(new TreeSet<>(TestProfile.authoredNames()));
        return Arbitraries.of(types).list().ofMinSize(2).ofMaxSize(MAX_NODES).flatMap(nodes -> {
            Arbitrary<Integer> node = Arbitraries.integers().between(0, nodes.size() - 1);
            Arbitrary<EdgeSpec> edge = Combinators.combine(node, Arbitraries.of(edgeTypes), node).as(EdgeSpec::new);
            return edge.set().ofMinSize(1).ofMaxSize(MAX_EDGES).map(edges -> new Spec(nodes, edges));
        });
    }

    /** A spec made concrete on fresh ids: its references, its graph, and the oracle holding it. */
    public record Published(Spec spec, List<Ref> refs, FixtureGraph graph, Oracle oracle, Ref unknown) {

        /** The answers to every single-reference {@code of}, asked once and kept for the try. */
        public Map<String, Map<Ref, OfResponse>> answers(Instance instance) {
            Map<String, Map<Ref, OfResponse>> answers = new HashMap<>();
            for (var closure : TestProfile.CLOSURES) {
                Map<Ref, OfResponse> byRef = new HashMap<>();
                for (Ref ref : refs) {
                    byRef.put(ref, instance.of(closure.name(), List.of(ref)));
                }
                answers.put(closure.name(), byRef);
            }
            return answers;
        }

        @Override
        public String toString() {
            List<String> edges = new ArrayList<>();
            spec.edges().forEach(e -> edges.add("N" + e.subject() + " " + e.edgeType() + " N" + e.object()));
            return "nodes " + spec.types() + ", edges " + edges;
        }
    }

    /** Publishes {@code spec} under a fresh prefix and waits until every edge is visible. */
    public static Published publish(Spec spec, Instance instance, String law) {
        GraphBuilder builder = new GraphBuilder(instance.runId().child(law + "-" + TRIES.incrementAndGet()));
        List<Ref> refs = new ArrayList<>();
        for (int i = 0; i < spec.types().size(); i++) {
            refs.add(builder.ref(spec.types().get(i), "N" + i));
        }
        spec.edges().forEach(e -> builder.edge(refs.get(e.subject()), e.edgeType(), refs.get(e.object())));
        FixtureGraph graph = builder.buildGenerated();
        Oracle oracle = new Oracle(TestProfile.CARDINALITY_RULES);
        graph.edges().forEach(oracle::snapshot);
        graph.publishTo(instance);
        return new Published(spec, List.copyOf(refs), graph, oracle, builder.ref(TestProfile.OMEGA, "unknown"));
    }
}
