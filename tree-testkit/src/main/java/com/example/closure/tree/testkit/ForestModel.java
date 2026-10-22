package com.example.closure.tree.testkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The reference model of the forest rules (design §3.1, brief §6.2): the Java port of
 * {@code sticky.py}. It holds the same state an implementation does — edges, which parent edge
 * each resource materializes, and the paths that follow — in memory, with none of the storage,
 * statics, anomalies, journal or fencing.
 *
 * <p>Use it three ways: as the rules in executable form when the brief's prose is unclear; as a
 * subject for the kit's own tests, which proves the oracles before the real tree exists; and as a
 * source of exact expected paths, since promotion is deterministic (byte-least candidate, D8).
 *
 * <p>The protected hooks exist so the kit's sensitivity tests can break one rule at a time and
 * show that the oracles notice.
 */
public class ForestModel implements TreeUnderTest {

    private final Set<Edge> edges = new TreeSet<>();
    private final Map<Ref, Edge> materialized = new HashMap<>();
    private int lastHops;

    // ---- writes -------------------------------------------------------------------------------

    @Override
    public void apply(EdgeEvent event) {
        Edge e = event.edge();
        Ref p = e.parent(), c = e.child();
        switch (event.kind()) {
            case SNAPSHOT -> {
                if (!edges.add(e)) return;                       // redelivery: the stored flag stands
                if (!materialized.containsKey(c) && mayMaterialize(p, c)) materialized.put(c, e);
            }
            case DELETE -> {
                if (!edges.remove(e)) return;                    // redelivery: nothing left to do
                if (e.equals(materialized.get(c))) {
                    Ref oldRoot = rootOf(p);                     // p stays in the old tree whatever happens to c
                    materialized.remove(c);
                    enforceRootRule(c);                          // promotion
                    recheckOldRoot(oldRoot);                     // the ring case, design §3.1
                }
            }
        }
    }

    /** First-parent rule: materialize unless the parent already lies beneath the child (it would close a cycle). */
    protected boolean mayMaterialize(Ref parent, Ref child) {
        return !inSubtree(parent, child);
    }

    /** Root rule (brief §6.2): a resource with no materialized parent takes the byte-least eligible one. */
    protected void enforceRootRule(Ref x) {
        if (materialized.containsKey(x)) return;
        edges.stream()
                .filter(e -> e.child().equals(x) && mayPromote(e.parent(), x))
                .min(Comparator.naturalOrder())
                .ifPresent(e -> materialized.put(x, e));
    }

    protected boolean mayPromote(Ref parent, Ref child) {
        return !inSubtree(parent, child);
    }

    protected void recheckOldRoot(Ref oldRoot) {
        if (isKnown(oldRoot)) enforceRootRule(oldRoot);
    }

    /** Whether reads follow extra edges. Only a sensitivity test turns this off. */
    protected boolean followExtraEdges() {
        return true;
    }

    // ---- state --------------------------------------------------------------------------------

    @Override
    public Set<Edge> edges() {
        return Collections.unmodifiableSet(edges);
    }

    @Override
    public boolean isExtra(Edge edge) {
        return edges.contains(edge) && !edge.equals(materialized.get(edge.child()));
    }

    @Override
    public Optional<ForestPath> pathOf(Ref ref) {
        if (!isKnown(ref)) return Optional.empty();
        List<Segment> reversed = new ArrayList<>();
        Set<Ref> seen = new HashSet<>();
        Ref v = ref;
        while (true) {
            if (!seen.add(v)) throw new IllegalStateException("materialized edges form a cycle through " + v);
            Edge m = materialized.get(v);
            if (m == null) {
                reversed.add(Segment.root(v));
                break;
            }
            reversed.add(new Segment(m.type(), v));
            v = m.parent();
        }
        Collections.reverse(reversed);
        return Optional.of(new ForestPath(reversed));
    }

    /** The number of hops the last {@link #of} took; for traces and curiosity, not for assertions. */
    public int lastHops() {
        return lastHops;
    }

    private boolean isKnown(Ref ref) {
        for (Edge e : edges) if (e.parent().equals(ref) || e.child().equals(ref)) return true;
        return false;
    }

    private Ref rootOf(Ref ref) {
        return pathOf(ref).map(ForestPath::root).orElse(ref);
    }

    /** {@code q} is {@code x} or lies beneath it: walking up from {@code q} reaches {@code x}. */
    private boolean inSubtree(Ref q, Ref x) {
        Set<Ref> seen = new HashSet<>();
        for (Ref v = q; v != null && seen.add(v); ) {
            if (v.equals(x)) return true;
            Edge m = materialized.get(v);
            v = m == null ? null : m.parent();
        }
        return false;
    }

    // ---- reads (design §3.2) ------------------------------------------------------------------

    @Override
    public Set<Ref> of(Ref x, Set<Admitted> closure) {
        lastHops = 0;
        if (!isKnown(x)) return Set.of();
        if (Admitted.allDown(closure)) return descendants(x, closure);
        if (Admitted.allUp(closure)) return ancestors(x, closure);
        return Bfs.reach(edges, x, closure);                    // not uniformly directed: traversal
    }

    private Set<Ref> descendants(Ref x, Set<Admitted> closure) {
        Map<Ref, List<Edge>> forestChildren = new HashMap<>();
        for (Edge m : materialized.values()) forestChildren.computeIfAbsent(m.parent(), k -> new ArrayList<>()).add(m);
        Set<Ref> result = new LinkedHashSet<>();
        var todo = new ArrayDeque<Ref>(List.of(x));
        while (!todo.isEmpty()) {
            Ref v = todo.pop();
            if (result.contains(v)) continue;
            // the range read under v's path, pruned at the first unadmitted segment
            List<Ref> got = new ArrayList<>(List.of(v));
            result.add(v);
            var stack = new ArrayDeque<Ref>(List.of(v));
            while (!stack.isEmpty()) {
                Ref u = stack.pop();
                for (Edge m : forestChildren.getOrDefault(u, List.of())) {
                    if (closure.contains(Admitted.down(m.type())) && result.add(m.child())) {
                        got.add(m.child());
                        stack.push(m.child());
                    }
                }
            }
            if (!followExtraEdges()) continue;
            for (Ref u : got) {                                  // hops across extra child edges
                for (Edge e : edges) {
                    if (e.parent().equals(u) && isExtra(e) && closure.contains(Admitted.down(e.type()))
                            && !result.contains(e.child())) {
                        todo.push(e.child());
                        lastHops++;
                    }
                }
            }
        }
        return result;
    }

    private Set<Ref> ancestors(Ref x, Set<Admitted> closure) {
        Set<Ref> result = new LinkedHashSet<>();
        var todo = new ArrayDeque<Ref>(List.of(x));
        while (!todo.isEmpty()) {
            Ref v = todo.pop();
            if (result.contains(v)) continue;
            // split v's path, walking up while each segment's edge type is admitted
            List<Segment> segs = pathOf(v).orElseThrow().segments();
            List<Ref> got = new ArrayList<>(List.of(v));
            result.add(v);
            for (int i = segs.size() - 1; i > 0; i--) {
                if (!closure.contains(Admitted.up(segs.get(i).edgeType()))) break;
                Ref r = segs.get(i - 1).ref();
                result.add(r);
                got.add(r);
            }
            if (!followExtraEdges()) continue;
            for (Ref u : got) {                                  // hops across extra parent edges
                for (Edge e : edges) {
                    if (e.child().equals(u) && isExtra(e) && closure.contains(Admitted.up(e.type()))
                            && !result.contains(e.parent())) {
                        todo.push(e.parent());
                        lastHops++;
                    }
                }
            }
        }
        return result;
    }
}
