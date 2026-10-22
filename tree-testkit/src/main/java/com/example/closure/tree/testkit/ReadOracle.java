package com.example.closure.tree.testkit;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The read oracle: every {@code of} must equal {@link Bfs#reach} over the stored edges. */
public final class ReadOracle {

    private ReadOracle() {}

    /**
     * The closures the kit reads through, over the generator's two edge types {@code h} and
     * {@code s}: uniformly downward and upward (each type alone and both), and the two that mix
     * directions across types. No closure admits both directions of one type, which contract L2
     * forbids. A test instance of the real implementation is configured with one closure for each;
     * they pair up as inverses, so the real instance's L3 pairing is satisfied.
     */
    public static final List<Set<Admitted>> STANDARD_CLOSURES = List.of(
            Set.of(Admitted.down("h")),
            Set.of(Admitted.down("s")),
            Set.of(Admitted.down("h"), Admitted.down("s")),
            Set.of(Admitted.up("h")),
            Set.of(Admitted.up("s")),
            Set.of(Admitted.up("h"), Admitted.up("s")),
            Set.of(Admitted.down("h"), Admitted.up("s")),
            Set.of(Admitted.up("h"), Admitted.down("s")));

    /** A reference no scenario ever mentions, to check that unknown means empty (L16). */
    public static final Ref UNKNOWN = Ref.of("never-mentioned");

    /** Checks every known resource, plus one unknown, under every closure; returns the number of reads. */
    public static int checkAll(TreeUnderTest tree, List<Set<Admitted>> closures) {
        Set<Edge> edges = tree.edges();
        Set<Ref> probes = new TreeSet<>(Bfs.known(edges));
        probes.add(UNKNOWN);
        int reads = 0;
        for (Set<Admitted> closure : closures) {
            for (Ref x : probes) {
                Set<Ref> expected = Bfs.reach(edges, x, closure);
                Set<Ref> actual = tree.of(x, closure);
                reads++;
                if (!expected.equals(actual))
                    throw new AssertionError("read of " + x + " under " + closure + ": expected "
                            + new TreeSet<>(expected) + " but got " + new TreeSet<>(actual));
            }
        }
        return reads;
    }
}
