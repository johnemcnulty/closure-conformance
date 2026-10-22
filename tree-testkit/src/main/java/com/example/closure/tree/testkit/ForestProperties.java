package com.example.closure.tree.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The property test of brief §10.2, independent of any test framework: for each generated
 * scenario, apply every event to a fresh tree and check after each one that the stored edges are
 * right, that F1–F3 hold, and (on a sample of events) that every read equals BFS; then check that
 * the converged tree is exactly the target.
 *
 * <p>A failure is an {@link AssertionError} carrying the seed and the events applied so far as Java
 * lines, ready to paste into a trace test.
 */
public final class ForestProperties {

    private ForestProperties() {}

    public record Summary(int scenarios, int events, int reads) {}

    public static Summary run(Supplier<? extends TreeUnderTest> newTree, int scenarios, long firstSeed,
                              double readSampleRate) {
        int events = 0, reads = 0;
        for (long seed = firstSeed; seed < firstSeed + scenarios; seed++) {
            Scenario s = ScenarioGenerator.generate(seed);
            Random sampler = new Random(~seed);
            TreeUnderTest tree = newTree.get();
            Set<Edge> expected = new TreeSet<>();
            List<EdgeEvent> applied = new ArrayList<>();
            try {
                List<EdgeEvent> all = new ArrayList<>(s.chaos());
                all.addAll(s.converge());
                for (EdgeEvent ev : all) {
                    applied.add(ev);
                    tree.apply(ev);
                    if (ev.kind() == EdgeEvent.Kind.SNAPSHOT) expected.add(ev.edge()); else expected.remove(ev.edge());
                    events++;
                    if (!expected.equals(tree.edges()))
                        throw new AssertionError("stored edges " + tree.edges() + " differ from " + expected);
                    ForestInvariants.check(tree);
                    if (sampler.nextDouble() < readSampleRate)
                        reads += ReadOracle.checkAll(tree, ReadOracle.STANDARD_CLOSURES);
                }
                ForestInvariants.checkConvergedTo(tree, s.target());
                reads += ReadOracle.checkAll(tree, ReadOracle.STANDARD_CLOSURES);
            } catch (AssertionError | RuntimeException failure) {
                throw new AssertionError(reproducer(seed, applied, failure), failure);
            }
        }
        return new Summary(scenarios, events, reads);
    }

    static String reproducer(long seed, List<EdgeEvent> applied, Throwable failure) {
        StringBuilder sb = new StringBuilder()
                .append("scenario seed ").append(seed).append(" failed after ").append(applied.size())
                .append(" events: ").append(failure.getMessage()).append("\nreproduce with:\n");
        for (EdgeEvent ev : applied) sb.append("    tree.apply(").append(ev.asJava()).append(");\n");
        return sb.toString();
    }
}
