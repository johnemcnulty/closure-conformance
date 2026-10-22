package com.example.closure.tree.testkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Random scenarios over a handful of resources and two edge types, as in {@code sticky.py}. Small
 * graphs are deliberate: they make second parents and cycles frequent, and failures readable.
 */
public final class ScenarioGenerator {

    public static final List<String> TYPES = List.of("h", "s");

    private ScenarioGenerator() {}

    public static Scenario generate(long seed) {
        Random rng = new Random(seed);
        int n = 3 + rng.nextInt(7);                              // 3..9 resources

        Set<Edge> target = new TreeSet<>();
        for (int i = 1; i < n; i++) target.add(edge(rng.nextInt(i), type(rng), i));

        Set<Edge> current = new TreeSet<>();
        List<EdgeEvent> chaos = new ArrayList<>();
        int steps = 5 + rng.nextInt(36);                         // 5..40 events
        for (int k = 0; k < steps; k++) {
            if (!current.isEmpty() && rng.nextDouble() < 0.4) {
                Edge e = new ArrayList<>(current).get(rng.nextInt(current.size()));
                current.remove(e);
                chaos.add(EdgeEvent.delete(e));
            } else {
                Edge e = edge(rng.nextInt(n), type(rng), rng.nextInt(n));   // may be a self-loop
                if (current.add(e)) chaos.add(EdgeEvent.add(e));
            }
        }

        List<EdgeEvent> converge = new ArrayList<>();
        for (Edge e : current) if (!target.contains(e)) converge.add(EdgeEvent.delete(e));
        for (Edge e : target) if (!current.contains(e)) converge.add(EdgeEvent.add(e));
        Collections.shuffle(converge, rng);
        return new Scenario(seed, chaos, converge, target);
    }

    private static Edge edge(int parent, String type, int child) {
        return new Edge(Ref.of("n" + parent), type, Ref.of("n" + child));
    }

    private static String type(Random rng) {
        return TYPES.get(rng.nextInt(TYPES.size()));
    }
}
