package com.example.closure.tree.testkit;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The worked traces of brief §9 as executable checks: exact paths, exact extra edges, and selected
 * reads after each event. Framework-independent; each trace throws {@link AssertionError} on the
 * first difference. Add a trace here whenever a bug is found, using the reproducer the property
 * runner prints.
 */
public final class ForestTraces {

    private static final String H = "hosts";
    private static final Set<Admitted> DOWN = Set.of(Admitted.down(H));
    private static final Set<Admitted> UP = Set.of(Admitted.up(H));

    private ForestTraces() {}

    public static void runAll(Supplier<? extends TreeUnderTest> newTree) {
        run("T1", () -> t1ReparentAddFirst(newTree.get()));
        run("T1b", () -> t1bReparentDeleteFirst(newTree.get()));
        run("T2", () -> t2TwoCycleFormsAndClears(newTree.get()));
        run("T3", () -> t3RingUnderARoot(newTree.get()));
        run("T4", () -> t4RingBrokenOldRootRechecked(newTree.get()));
        run("T5", () -> t5OnlyParentBeneathStaysRoot(newTree.get()));
    }

    /** Runs one trace, reporting an implementation that throws as a failed trace rather than a crash. */
    public static void run(String name, Runnable trace) {
        try {
            trace.run();
        } catch (AssertionError e) {
            throw new AssertionError(name + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new AssertionError(name + ": the tree threw " + e, e);
        }
    }

    /** T1 — a card moves between shelves, the new parent's edge arriving first. */
    public static void t1ReparentAddFirst(TreeUnderTest t) {
        apply(t, "NE>S1", "NE>S2", "S1>C", "C>P");
        expectPaths(t, "NE=/NE", "S1=/NE/S1", "S2=/NE/S2", "C=/NE/S1/C", "P=/NE/S1/C/P");
        expectExtra(t);

        apply(t, "S2>C");                                        // second parent: extra, no path changes
        expectPaths(t, "NE=/NE", "S1=/NE/S1", "S2=/NE/S2", "C=/NE/S1/C", "P=/NE/S1/C/P");
        expectExtra(t, "S2>C");
        expectRead(t, "S2", DOWN, "S2", "C", "P");               // one hop from S2 to C
        expectRead(t, "P", UP, "P", "C", "S1", "NE", "S2");      // one hop from C to S2

        delete(t, "S1>C");                                       // promotion: the subtree moves once
        expectPaths(t, "NE=/NE", "S1=/NE/S1", "S2=/NE/S2", "C=/NE/S2/C", "P=/NE/S2/C/P");
        expectExtra(t);
        expectRead(t, "S1", DOWN, "S1");
    }

    /** T1b — the same move, the old edge's delete arriving first: a transient root. */
    public static void t1bReparentDeleteFirst(TreeUnderTest t) {
        apply(t, "NE>S1", "NE>S2", "S1>C", "C>P");
        delete(t, "S1>C");
        expectPaths(t, "NE=/NE", "S1=/NE/S1", "S2=/NE/S2", "C=/C", "P=/C/P");
        expectExtra(t);

        apply(t, "S2>C");
        expectPaths(t, "NE=/NE", "S1=/NE/S1", "S2=/NE/S2", "C=/NE/S2/C", "P=/NE/S2/C/P");
        expectExtra(t);
    }

    /** T2 — a two-cycle forms and clears; ends as the design discussion described. */
    public static void t2TwoCycleFormsAndClears(TreeUnderTest t) {
        apply(t, "A>B");
        expectPaths(t, "A=/A", "B=/A/B");

        apply(t, "B>A");                                         // B is beneath A: the edge is extra
        expectPaths(t, "A=/A", "B=/A/B");
        expectExtra(t, "B>A");
        expectRead(t, "A", DOWN, "A", "B");
        expectRead(t, "B", DOWN, "B", "A");

        delete(t, "A>B");                                        // B becomes a root; re-check promotes B>A
        expectPaths(t, "A=/B/A", "B=/B");
        expectExtra(t);
    }

    /** T3 — a ring under a root, and which reads hop. */
    public static void t3RingUnderARoot(TreeUnderTest t) {
        apply(t, "A>B", "B>C", "B>D", "C>A");
        expectPaths(t, "A=/A", "B=/A/B", "C=/A/B/C", "D=/A/B/D");
        expectExtra(t, "C>A");

        apply(t, "R>A");                                         // A is a root and R is not beneath it
        expectPaths(t, "R=/R", "A=/R/A", "B=/R/A/B", "C=/R/A/B/C", "D=/R/A/B/D");
        expectExtra(t, "C>A");
        expectRead(t, "A", DOWN, "A", "B", "C", "D");
        expectRead(t, "R", DOWN, "R", "A", "B", "C", "D");
        expectRead(t, "D", UP, "D", "B", "A", "R", "C");         // hop from A to C
        expectRead(t, "C", UP, "C", "B", "A", "R");
    }

    /** T4 — a ring broken: the old tree's root is re-checked and promoted. */
    public static void t4RingBrokenOldRootRechecked(TreeUnderTest t) {
        apply(t, "A>B", "B>C", "C>A");
        expectPaths(t, "A=/A", "B=/A/B", "C=/A/B/C");
        expectExtra(t, "C>A");

        delete(t, "A>B");
        expectPaths(t, "A=/B/C/A", "B=/B", "C=/B/C");
        expectExtra(t);
    }

    /**
     * T5 — a resource loses its materialized parent and its only remaining parent lies beneath it:
     * it must become a root, not promote that parent (which would make the forest cyclic).
     */
    public static void t5OnlyParentBeneathStaysRoot(TreeUnderTest t) {
        apply(t, "R>A", "A>B", "B>A");
        expectPaths(t, "R=/R", "A=/R/A", "B=/R/A/B");
        expectExtra(t, "B>A");

        delete(t, "R>A");                                        // R leaves; A's only parent B is beneath it
        expectPaths(t, "A=/A", "B=/A/B");
        expectExtra(t, "B>A");
        expectRead(t, "B", DOWN, "B", "A");                      // the hop across B>A still finds A
        expectRead(t, "A", UP, "A", "B");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Edges are written {@code parent>child}, all of type {@code hosts}. */
    private static Edge edge(String spec) {
        String[] pc = spec.split(">");
        return Edge.of(pc[0], H, pc[1]);
    }

    private static void apply(TreeUnderTest t, String... specs) {
        for (String s : specs) {
            t.apply(EdgeEvent.add(edge(s)));
            ForestInvariants.check(t);
        }
    }

    private static void delete(TreeUnderTest t, String... specs) {
        for (String s : specs) {
            t.apply(EdgeEvent.delete(edge(s)));
            ForestInvariants.check(t);
        }
    }

    /** Every known resource's path, as {@code id=/rendered/path}; no other resource may be known. */
    private static void expectPaths(TreeUnderTest t, String... specs) {
        Map<String, String> expected = new TreeMap<>();
        for (String s : specs) {
            String[] kv = s.split("=", 2);
            expected.put(kv[0], kv[1]);
        }
        Map<String, String> actual = new TreeMap<>();
        for (Ref r : Bfs.known(t.edges())) {
            actual.put(r.id(), t.pathOf(r).map(ForestPath::render).orElse("(none)"));
        }
        if (!expected.equals(actual)) throw new AssertionError("paths: expected " + expected + " but got " + actual);
    }

    private static void expectExtra(TreeUnderTest t, String... specs) {
        Set<Edge> expected = Arrays.stream(specs).map(ForestTraces::edge).collect(Collectors.toCollection(TreeSet::new));
        Set<Edge> actual = t.edges().stream().filter(t::isExtra).collect(Collectors.toCollection(TreeSet::new));
        if (!expected.equals(actual)) throw new AssertionError("extra edges: expected " + expected + " but got " + actual);
    }

    private static void expectRead(TreeUnderTest t, String x, Set<Admitted> closure, String... ids) {
        Set<Ref> expected = Arrays.stream(ids).map(Ref::of).collect(Collectors.toCollection(TreeSet::new));
        Set<Ref> actual = new TreeSet<>(t.of(Ref.of(x), closure));
        if (!expected.equals(actual))
            throw new AssertionError("read of " + x + " under " + closure + ": expected " + expected + " but got " + actual);
        Set<Ref> spec = new TreeSet<>(Bfs.reach(t.edges(), Ref.of(x), closure));
        if (!spec.equals(expected))
            throw new AssertionError("trace is wrong, not the tree: BFS gives " + spec + " for " + x);
    }
}
