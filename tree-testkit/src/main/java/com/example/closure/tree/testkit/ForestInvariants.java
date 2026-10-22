package com.example.closure.tree.testkit;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The structural oracle: invariants F1–F3 of brief §4.2, checked from scratch against the stored
 * edges. F4 (static sets) and F5 (anomaly records) are not covered here; they need the
 * implementation's storage and belong in its own tests.
 */
public final class ForestInvariants {

    private ForestInvariants() {}

    public static void check(TreeUnderTest tree) {
        Set<Edge> edges = tree.edges();
        for (Ref x : Bfs.known(edges)) {
            ForestPath path = tree.pathOf(x)
                    .orElseThrow(() -> fail("F1", x, "a known resource has no path"));
            if (!path.ref().equals(x))
                throw fail("F1", x, "pathOf returned the path of " + path.ref() + ": " + path);
            Set<Ref> onPath = new HashSet<>();
            for (Segment s : path.segments()) {
                if (!onPath.add(s.ref())) throw fail("F2", x, "path visits " + s.ref() + " twice: " + path);
            }

            Optional<Edge> materialized = path.materializedEdge();
            if (materialized.isPresent()) {
                Edge m = materialized.get();
                if (!edges.contains(m))
                    throw fail("F1", x, "path " + path + " names edge " + m + ", which is not stored");
                if (tree.isExtra(m))
                    throw fail("F1", x, "materialized edge " + m + " is flagged extra");
                Optional<ForestPath> parentPath = tree.pathOf(m.parent());
                if (!parentPath.equals(path.parentPath()))
                    throw fail("F1", x, "path " + path + " does not extend its parent's path "
                            + parentPath.map(ForestPath::render).orElse("(none)"));
            }

            for (Edge e : edges) {
                if (!e.child().equals(x) || materialized.map(e::equals).orElse(false)) continue;
                if (!tree.isExtra(e))
                    throw fail("F1", x, "parent edge " + e + " is neither materialized nor flagged extra");
                if (materialized.isEmpty() && !inSubtree(tree, e.parent(), x))
                    throw fail("F3", x, "a root has parent " + e.parent() + " outside its own subtree via " + e);
            }
        }
    }

    /**
     * Once the edges form a proper tree, the forest must be exactly that tree (brief §4.2): every
     * edge materialized and nothing extra.
     */
    public static void checkConvergedTo(TreeUnderTest tree, Set<Edge> target) {
        check(tree);
        for (Edge e : target) {
            Optional<Edge> m = tree.pathOf(e.child()).flatMap(ForestPath::materializedEdge);
            if (!m.equals(Optional.of(e)))
                throw new AssertionError("recovery: edges form a proper tree but " + e.child()
                        + " materializes " + m.map(Edge::toString).orElse("no parent") + " instead of " + e);
        }
        for (Edge e : tree.edges()) {
            if (tree.isExtra(e)) throw new AssertionError("recovery: " + e + " is still extra in a proper tree");
        }
    }

    /** {@code q} is {@code x} or lies beneath it in the forest. */
    static boolean inSubtree(TreeUnderTest tree, Ref q, Ref x) {
        if (q.equals(x)) return true;
        Optional<ForestPath> px = tree.pathOf(x), pq = tree.pathOf(q);
        return px.isPresent() && pq.isPresent() && px.get().isStrictPrefixOf(pq.get());
    }

    private static AssertionError fail(String invariant, Ref x, String message) {
        return new AssertionError(invariant + " violated at " + x + ": " + message);
    }
}
