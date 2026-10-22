package com.example.closure.suite;

import com.example.closure.model.ClosureDecl;
import com.example.closure.model.Direction;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A fixture's graph, built by {@link GraphBuilder}. */
public record FixtureGraph(Set<Edge> edges) {

    public FixtureGraph {
        edges = Set.copyOf(new LinkedHashSet<>(edges));
    }

    /**
     * Publishes every edge and waits until each is visible, so a fixture asserting that something
     * is <em>not</em> reached is not asserting it before the edge that would have reached it has
     * been consumed. An edge is visible when a closure admitting its type steps across it; one no
     * closure admits is visible when both its ends are known (L12). Visibility is asserted as
     * containment, so an instance that reaches too much still gets past here and fails the
     * fixture's own assertions with a precise reason.
     */
    public void publishTo(Instance instance) {
        instance.publisher().snapshotAll(edges);
        Set<Edge> pending = new LinkedHashSet<>(edges); // a snapshot once visible stays so: re-ask only the rest
        instance.settle().until("every edge of the fixture's graph is visible", () -> {
            pending.retainAll(invisible(instance, pending));
            return pending;
        }, Set::isEmpty);
    }

    private static Set<Edge> invisible(Instance instance, Set<Edge> candidates) {
        Map<String, Map<Ref, Set<Ref>>> asked = new HashMap<>();
        Set<Edge> invisible = new LinkedHashSet<>();
        for (Edge edge : candidates) {
            boolean visible = stepping(edge)
                .map(step -> of(instance, asked, step.closure(), step.from()).contains(step.to()))
                .orElseGet(() -> {
                    String any = TestProfile.CLOSURES.get(0).name();
                    return of(instance, asked, any, edge.subject()).contains(edge.subject())
                        && of(instance, asked, any, edge.object()).contains(edge.object());
                });
            if (!visible) {
                invisible.add(edge);
            }
        }
        return invisible;
    }

    private record Step(String closure, Ref from, Ref to) {
    }

    /** A closure admitting the edge's type, and the step it takes across the edge (L23). */
    private static Optional<Step> stepping(Edge edge) {
        for (ClosureDecl declared : TestProfile.CLOSURES) {
            for (Direction direction : List.of(Direction.FORWARD, Direction.REVERSE)) {
                if (declared.closure().admits(edge.edgeType(), direction)) {
                    return Optional.of(direction == Direction.FORWARD
                        ? new Step(declared.name(), edge.subject(), edge.object())
                        : new Step(declared.name(), edge.object(), edge.subject()));
                }
            }
        }
        return Optional.empty();
    }

    private static Set<Ref> of(Instance instance, Map<String, Map<Ref, Set<Ref>>> asked, String closure, Ref ref) {
        return asked.computeIfAbsent(closure, c -> new HashMap<>())
            .computeIfAbsent(ref, r -> instance.of(closure, List.of(r)).references());
    }
}
