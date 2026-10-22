package com.example.closure.suite;

import com.example.closure.model.CardinalityRule;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the graph a fixture publishes, with every id behind the fixture's run-id prefix
 * (suite-design §6). {@link #build()} refuses a graph that breaches a cardinality rule, which is
 * how A.4's invariant is enforced: a {@code beta} or {@code gamma} without exactly one holder
 * would qualify every result reaching it, and a fixture not about cardinality would read as
 * failing. A fixture that means to provoke a rule says so with {@link #buildBreaching()}.
 */
public final class GraphBuilder {

    private final RunId run;
    private final Set<Edge> edges = new LinkedHashSet<>();

    public GraphBuilder(RunId run) {
        this.run = run;
    }

    /** A reference of a profile type, with {@code local} behind the run-id prefix. */
    public Ref ref(String type, String local) {
        if (!TestProfile.RESOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException(type + " is not a resource type of the profile (A.1)");
        }
        return run.ref(type, local);
    }

    /** Adds {@code subject edgeType object}; the edge type is an authored name (W23). */
    public GraphBuilder edge(Ref subject, String edgeType, Ref object) {
        if (!TestProfile.authoredNames().contains(edgeType)) {
            throw new IllegalArgumentException(edgeType + " is not an authored edge type of the profile (A.2, W23)");
        }
        edges.add(Edge.of(subject, edgeType, object));
        return this;
    }

    /** The graph, refusing it if any known reference breaches a cardinality rule (A.4). */
    public FixtureGraph build() {
        List<String> breaches = breaches(edges);
        if (!breaches.isEmpty()) {
            throw new IllegalStateException("the graph breaches a cardinality rule, which would qualify every result"
                + " reaching it; give every beta and gamma exactly one holder, or call buildBreaching(): " + breaches);
        }
        return new FixtureGraph(edges);
    }

    /** The graph, for a fixture deliberately provoking a cardinality rule (fixture 4). */
    public FixtureGraph buildBreaching() {
        if (breaches(edges).isEmpty()) {
            throw new IllegalStateException("buildBreaching() was called, but the graph breaches no rule");
        }
        return new FixtureGraph(edges);
    }

    /**
     * The graph, whatever it breaches, for a generated fixture: its expectations, qualifications
     * included, come from the oracle rather than from a table (suite-design §4).
     */
    public FixtureGraph buildGenerated() {
        return new FixtureGraph(edges);
    }

    /** Each breach, as {@code rule: reference has n}, over the references the edges make known (L12). */
    static List<String> breaches(Set<Edge> edges) {
        Set<Ref> known = new HashSet<>();
        edges.forEach(e -> {
            known.add(e.subject());
            known.add(e.object());
        });
        List<String> breaches = new ArrayList<>();
        for (CardinalityRule rule : TestProfile.CARDINALITY_RULES) {
            for (Ref ref : known) {
                if (rule.types().contains(ref.type())) {
                    long count = edges.stream()
                        .filter(e -> e.edgeType().equals(rule.edgeType()) && rule.end().of(e).equals(ref))
                        .count();
                    if (rule.breachedBy(count)) {
                        breaches.add(rule.id() + ": " + ref + " has " + count);
                    }
                }
            }
        }
        return breaches;
    }
}
