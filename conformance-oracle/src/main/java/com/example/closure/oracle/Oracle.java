package com.example.closure.oracle;

import com.example.closure.model.CardinalityRule;
import com.example.closure.model.Closure;
import com.example.closure.model.Direction;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The naive reference closure computer.
 *
 * <p>It holds the edge set and nothing else, and answers every question by recomputing from that
 * set with a breadth-first search. That is deliberate: the suite tests implementations whose
 * difficulty lies in maintaining closures incrementally, and the oracle is useful exactly because
 * it does none of that and can be checked by reading it. Do not optimize it, and never share its
 * code with the reference implementation (see docs/suite-design.md).
 */
public final class Oracle {

    private final Set<Edge> edges = new LinkedHashSet<>();
    private final List<CardinalityRule> rules;

    public Oracle(List<CardinalityRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Oracle() {
        this(List.of());
    }

    // ---- ingest: both kinds assert an end state, so applying either twice is a no-op ----

    public void snapshot(Edge edge) {
        edges.add(edge);
    }

    public void delete(Edge edge) {
        edges.remove(edge);
    }

    public Set<Edge> edges() {
        return Set.copyOf(edges);
    }

    /** A reference is known when it appears at either end of any edge (L12). */
    public boolean isKnown(Ref ref) {
        return edges.stream().anyMatch(e -> e.subject().equals(ref) || e.object().equals(ref));
    }

    // ---- reachability: the definition in L23, written as a search ----

    /** The references one admitted step away from {@code from}. */
    Set<Ref> steps(Closure closure, Ref from) {
        Set<Ref> next = new HashSet<>();
        for (Edge e : edges) {
            if (e.subject().equals(from) && closure.admits(e.edgeType(), Direction.FORWARD)) next.add(e.object());
            if (e.object().equals(from) && closure.admits(e.edgeType(), Direction.REVERSE)) next.add(e.subject());
        }
        return next;
    }

    /** {@code A.of(S)}: every known argument, plus everything reachable from one by admitted steps. */
    public Set<Ref> reach(Closure closure, Set<Ref> args) {
        Set<Ref> start = new HashSet<>();
        for (Ref x : args) if (isKnown(x)) start.add(x); // unknown arguments contribute nothing (L14, L21)
        return search(start, from -> steps(closure, from));
    }

    /** Breadth-first search; the result includes {@code start} itself. */
    private static Set<Ref> search(Set<Ref> start, Function<Ref, Set<Ref>> step) {
        Set<Ref> reached = new HashSet<>(start);
        Deque<Ref> frontier = new ArrayDeque<>(start);
        while (!frontier.isEmpty()) {
            for (Ref to : step.apply(frontier.remove())) {
                if (reached.add(to)) frontier.add(to);
            }
        }
        return reached;
    }

    // ---- qualifications (L25 to L28) ----

    /** The full expected answer to {@code A.of(S)}, qualifications included. */
    public Result of(Closure closure, Set<Ref> args) {
        Set<Ref> refs = reach(closure, args);
        return new Result(refs, cycles(closure, refs), breaches(closure, refs));
    }

    public Result of(Closure closure, Ref... args) {
        return of(closure, Set.of(args));
    }

    /**
     * Every cyclic strongly connected component inside the result. A reference lies on a cycle when
     * it can reach itself in one or more admitted steps; its component is everything it reaches
     * that also reaches it back. Every member of a cycle reaches every other, so a result holding
     * one member holds the whole component (L27).
     */
    List<Set<Ref>> cycles(Closure closure, Set<Ref> result) {
        List<Set<Ref>> components = new ArrayList<>();
        Set<Ref> assigned = new HashSet<>();
        for (Ref r : result) {
            if (assigned.contains(r)) continue;
            Set<Ref> fromR = search(steps(closure, r), from -> steps(closure, from));
            if (!fromR.contains(r)) continue; // r reaches nothing that leads back to it
            Set<Ref> component = new HashSet<>();
            for (Ref s : fromR) {
                if (search(steps(closure, s), from -> steps(closure, from)).contains(r)) component.add(s);
            }
            assigned.addAll(component);
            components.add(component);
        }
        return components;
    }

    /** Breaches of rules whose edge type the closure admits, by references in the result (L27). */
    Set<Breach> breaches(Closure closure, Set<Ref> result) {
        Set<Breach> found = new HashSet<>();
        for (CardinalityRule rule : rules) {
            if (!closure.admitsType(rule.edgeType())) continue;
            for (Ref r : result) {
                if (!rule.types().contains(r.type())) continue;
                long count = edges.stream()
                    .filter(e -> e.edgeType().equals(rule.edgeType()) && rule.end().of(e).equals(r))
                    .count();
                if (rule.breachedBy(count)) found.add(new Breach(rule.id(), r));
            }
        }
        return found;
    }
}
