package com.example.closure.reference;

import com.example.closure.model.Direction;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The instance's state: its edges, and for each closure a reach set per known reference,
 * materialized as edges arrive rather than searched for per query (suite-design §5). Each change
 * recomputes the reach sets it can affect — those of every reference that reaches the changed
 * step's start — and a query only reads.
 *
 * <p>Changes are applied one at a time under a write lock, so the outcome is serial (W34).
 */
final class Graph {

    private final Configuration configuration;
    private final Variant variant;
    private final Set<Edge> edges = new HashSet<>();
    /** Known references (L12), with how many edge ends each appears at. */
    private final Map<Ref, Integer> ends = new HashMap<>();
    /** edge type → reference → how many edges of that type it is the subject, or object, of. */
    private final Map<String, Map<Ref, Integer>> asSubject = new HashMap<>();
    private final Map<String, Map<Ref, Integer>> asObject = new HashMap<>();
    private final Map<String, ClosureState> closures = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** One closure's admitted steps and its materialized reach sets. */
    private static final class ClosureState {
        /** from → to → how many edges give that step; two edge types can give the same one. */
        final Map<Ref, Map<Ref, Integer>> steps = new HashMap<>();
        final Map<Ref, Set<Ref>> reach = new HashMap<>();
    }

    Graph(Configuration configuration) {
        this(configuration, Variant.CORRECT);
    }

    Graph(Configuration configuration, Variant variant) {
        this.configuration = configuration;
        this.variant = variant;
        configuration.closures().keySet().forEach(name -> closures.put(name, new ClosureState()));
    }

    /** Applies a {@code snapshot}: the edge exists (W20). Reapplying it changes nothing (W24). */
    void assertEdge(Edge edge) {
        if (variant == Variant.RACY_RECOMPUTE) {
            racy(edge, true);
            return;
        }
        lock.writeLock().lock();
        try {
            if (edges.add(edge)) {
                ends.merge(edge.subject(), 1, Integer::sum);
                ends.merge(edge.object(), 1, Integer::sum);
                count(edge, +1);
                changeSteps(edge, +1);
            } else if (variant == Variant.TOGGLES_ON_REDELIVERY) {
                edges.remove(edge);
                forget(edge.subject());
                forget(edge.object());
                count(edge, -1);
                changeSteps(edge, -1);
            }
            recompute(edge);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Applies a {@code delete}: the edge does not exist (W20). Repeating it changes nothing (W31). */
    void retractEdge(Edge edge) {
        if (variant == Variant.RACY_RECOMPUTE) {
            racy(edge, false);
            return;
        }
        lock.writeLock().lock();
        try {
            if (edges.remove(edge)) {
                forget(edge.subject());
                if (variant == Variant.FORGETS_HELD_OBJECT) {
                    ends.remove(edge.object());
                } else {
                    forget(edge.object());
                }
                count(edge, -1);
                changeSteps(edge, -1);
            }
            recompute(edge);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * An {@code of} answer: the result, the cyclic components of the closure's admitted edges inside
     * it, and for each rule the closure's edge types bring in, the references in it breaching that
     * rule (L25, L27).
     */
    record Answer(Set<Ref> refs, List<CycleGroup> cycles, Map<String, List<Ref>> breaches) {
    }

    /** A cycle group (W36): mutually reaching references, and the admitted edges among them. */
    record CycleGroup(Set<Ref> members, Set<Edge> edges) {
    }

    /** {@code of} over {@code refs}: the union of each one's reach set; unknown ones add nothing (L14, L21). */
    Set<Ref> of(String closure, Collection<Ref> refs) {
        return answer(closure, refs).refs();
    }

    /** {@code of} over {@code refs}, with its qualifications, all read under one lock. */
    Answer answer(String closure, Collection<Ref> refs) {
        lock.readLock().lock();
        try {
            ClosureState state = closures.get(closure);
            Set<Ref> result = new HashSet<>();
            for (Ref ref : refs) {
                result.addAll(state.reach.getOrDefault(ref,
                    variant == Variant.NO_REFLEXIVE_UNKNOWN ? Set.of(ref) : Set.of()));
            }
            Configuration.ClosureDef def = configuration.closures().get(closure);
            List<CycleGroup> groups = cycles(state, result).stream()
                .map(members -> new CycleGroup(members, edges.stream()
                    .filter(e -> variant == Variant.UNADMITTED_GROUP_EDGES || !def.directionsFor(e.edgeType()).isEmpty())
                    .filter(e -> members.contains(e.subject()) && members.contains(e.object()))
                    .collect(Collectors.toUnmodifiableSet())))
                .toList();
            if (variant == Variant.NO_CYCLES) {
                groups = List.of();
            }
            return new Answer(Set.copyOf(result), groups, breaches(closure, result));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Each cyclic component inside the result. A reference is on a cycle when some reference one
     * step on reaches back to it; its component is what it reaches that reaches it in turn. Both
     * questions are answered from the materialized reach sets, without walking the graph.
     */
    private static List<Set<Ref>> cycles(ClosureState state, Set<Ref> result) {
        List<Set<Ref>> components = new ArrayList<>();
        Set<Ref> placed = new HashSet<>();
        for (Ref ref : result) {
            if (placed.contains(ref)) {
                continue;
            }
            boolean onCycle = state.steps.getOrDefault(ref, Map.of()).keySet().stream()
                .anyMatch(next -> state.reach.getOrDefault(next, Set.of()).contains(ref));
            if (onCycle) {
                Set<Ref> component = new HashSet<>();
                for (Ref other : state.reach.get(ref)) {
                    if (state.reach.getOrDefault(other, Set.of()).contains(ref)) {
                        component.add(other);
                    }
                }
                placed.addAll(component);
                components.add(Set.copyOf(component));
            }
        }
        return components;
    }

    /** For each rule on an edge type the closure admits, in either direction, the result's breaches of it. */
    private Map<String, List<Ref>> breaches(String closure, Set<Ref> result) {
        Map<String, List<Ref>> breaches = new HashMap<>();
        Configuration.ClosureDef def = configuration.closures().get(closure);
        for (Configuration.Rule rule : configuration.rules()) {
            if (def.directionsFor(rule.edgeType()).isEmpty() && variant != Variant.UNRESTRICTED_BREACHES) {
                continue;
            }
            Map<Ref, Integer> counts = (rule.atObject() ? asObject : asSubject).getOrDefault(rule.edgeType(), Map.of());
            List<Ref> breaching = result.stream()
                .filter(ref -> rule.types().contains(ref.type()))
                .filter(ref -> rule.breachedBy(counts.getOrDefault(ref, 0)))
                .toList();
            if (!breaching.isEmpty()) {
                breaches.put(rule.id(), breaching);
            }
        }
        return breaches;
    }

    /** Whether {@code ref} is the subject of any edge; used only by {@link Variant#PLACEHOLDER_FAR_END}. */
    boolean isEverSubject(Ref ref) {
        lock.readLock().lock();
        try {
            return asSubject.values().stream().anyMatch(counts -> counts.containsKey(ref));
        } finally {
            lock.readLock().unlock();
        }
    }

    private void count(Edge edge, int delta) {
        for (var entry : List.of(Map.entry(asSubject, edge.subject()), Map.entry(asObject, edge.object()))) {
            Map<Ref, Integer> counts = entry.getKey().computeIfAbsent(edge.edgeType(), t -> new HashMap<>());
            if (counts.merge(entry.getValue(), delta, Integer::sum) == 0) {
                counts.remove(entry.getValue());
            }
        }
    }

    private void forget(Ref ref) {
        if (ends.merge(ref, -1, Integer::sum) == 0) {
            ends.remove(ref);
        }
    }

    private void changeSteps(Edge edge, int delta) {
        for (Map.Entry<String, ClosureState> entry : closures.entrySet()) {
            Map<Ref, Map<Ref, Integer>> steps = entry.getValue().steps;
            Configuration.ClosureDef def = configuration.closures().get(entry.getKey());
            Set<Direction> directions = variant == Variant.IGNORES_ADMISSION
                ? def.admits().stream().map(a -> a.direction()).collect(Collectors.toSet())
                : def.directionsFor(edge.edgeType());
            for (Direction direction : directions) {
                Ref from = direction == Direction.FORWARD ? edge.subject() : edge.object();
                Ref to = direction == Direction.FORWARD ? edge.object() : edge.subject();
                Map<Ref, Integer> out = steps.computeIfAbsent(from, f -> new HashMap<>());
                if (out.merge(to, delta, Integer::sum) == 0) {
                    out.remove(to);
                    if (out.isEmpty()) {
                        steps.remove(from);
                    }
                }
            }
        }
    }

    /**
     * Recomputes every reach set the edge can have changed, in every closure. Whether the edge was
     * added or removed, a reference's reach set changes only if it reaches the start of a step the
     * edge gives, and the set of references reaching that start is the same before and after.
     */
    private void recompute(Edge edge) {
        for (ClosureState state : closures.values()) {
            for (Ref ref : affected(state, edge)) {
                if (ends.containsKey(ref)) {
                    state.reach.put(ref, Set.copyOf(walk(state, ref)));
                } else {
                    state.reach.remove(ref);
                }
            }
        }
    }

    private static Set<Ref> affected(ClosureState state, Edge edge) {
        Set<Ref> affected = new HashSet<>();
        for (Ref end : List.of(edge.subject(), edge.object())) {
            affected.add(end);
            state.reach.forEach((ref, reach) -> {
                if (reach.contains(end)) {
                    affected.add(ref);
                }
            });
        }
        return affected;
    }

    /**
     * {@link Variant#RACY_RECOMPUTE}: the change is made under the lock, but the affected reach sets
     * are computed outside it, from a copy of the steps, and written back later. A concurrent change
     * landing in between is overwritten by a result that never saw it — the overlap W34 warns of.
     */
    private void racy(Edge edge, boolean exists) {
        Map<ClosureState, Map<Ref, Set<Ref>>> copies = new HashMap<>();
        Map<ClosureState, Set<Ref>> affected = new HashMap<>();
        Set<Ref> known;
        lock.writeLock().lock();
        try {
            if (exists && edges.add(edge)) {
                ends.merge(edge.subject(), 1, Integer::sum);
                ends.merge(edge.object(), 1, Integer::sum);
                count(edge, +1);
                changeSteps(edge, +1);
            } else if (!exists && edges.remove(edge)) {
                forget(edge.subject());
                forget(edge.object());
                count(edge, -1);
                changeSteps(edge, -1);
            }
            for (ClosureState state : closures.values()) {
                affected.put(state, affected(state, edge));
                Map<Ref, Set<Ref>> copy = new HashMap<>();
                state.steps.forEach((from, out) -> copy.put(from, Set.copyOf(out.keySet())));
                copies.put(state, copy);
            }
            known = Set.copyOf(ends.keySet());
        } finally {
            lock.writeLock().unlock();
        }
        Map<ClosureState, Map<Ref, Set<Ref>>> results = new HashMap<>();
        affected.forEach((state, refs) -> {
            Map<Ref, Set<Ref>> computed = new HashMap<>();
            for (Ref ref : refs) {
                computed.put(ref, known.contains(ref) ? walk(copies.get(state), ref) : null);
            }
            results.put(state, computed);
        });
        try { // varying, as real recomputes do: a fixed delay would keep writes in snapshot order
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lock.writeLock().lock();
        try {
            results.forEach((state, computed) -> computed.forEach((ref, reach) -> {
                if (reach == null) {
                    state.reach.remove(ref);
                } else {
                    state.reach.put(ref, reach);
                }
            }));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static Set<Ref> walk(Map<Ref, Set<Ref>> steps, Ref start) {
        Set<Ref> seen = new HashSet<>(List.of(start));
        Deque<Ref> stack = new ArrayDeque<>(List.of(start));
        while (!stack.isEmpty()) {
            for (Ref next : steps.getOrDefault(stack.pop(), Set.of())) {
                if (seen.add(next)) {
                    stack.push(next);
                }
            }
        }
        return Set.copyOf(seen);
    }

    /** Everything reachable from {@code start} along admitted steps, itself included (L15, L23). */
    private static Set<Ref> walk(ClosureState state, Ref start) {
        Set<Ref> seen = new HashSet<>(List.of(start));
        Deque<Ref> stack = new ArrayDeque<>(List.of(start));
        while (!stack.isEmpty()) {
            for (Ref next : state.steps.getOrDefault(stack.pop(), Map.of()).keySet()) {
                if (seen.add(next)) {
                    stack.push(next);
                }
            }
        }
        return seen;
    }
}
