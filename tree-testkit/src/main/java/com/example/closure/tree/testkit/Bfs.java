package com.example.closure.tree.testkit;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** The specification of every read (contract L23): breadth-first reachability over the edges. */
public final class Bfs {

    private Bfs() {}

    public static Set<Ref> known(Set<Edge> edges) {
        Set<Ref> known = new HashSet<>();
        for (Edge e : edges) {
            known.add(e.parent());
            known.add(e.child());
        }
        return known;
    }

    /** {@code of(x)} by definition: empty if {@code x} is unknown (L16), else everything reachable, {@code x} included (L15). */
    public static Set<Ref> reach(Set<Edge> edges, Ref x, Set<Admitted> closure) {
        if (!known(edges).contains(x)) return Set.of();
        Set<Ref> seen = new HashSet<>();
        seen.add(x);
        var todo = new ArrayDeque<Ref>();
        todo.add(x);
        while (!todo.isEmpty()) {
            Ref v = todo.poll();
            for (Edge e : edges) {
                if (e.parent().equals(v) && closure.contains(Admitted.down(e.type())) && seen.add(e.child()))
                    todo.add(e.child());
                if (e.child().equals(v) && closure.contains(Admitted.up(e.type())) && seen.add(e.parent()))
                    todo.add(e.parent());
            }
        }
        return seen;
    }
}
