package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.ClosureDecl;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.oracle.Oracle;
import com.example.closure.oracle.Result;
import com.example.closure.suite.EdgeEvents;
import com.example.closure.suite.EdgePublisher.Outgoing;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.example.closure.suite.Qualifications;
import com.example.closure.suite.RunId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixture 15 (§7): a large batch of events whose affected sets overlap heavily — a spine two deep
 * beneath one alpha, so every change touches the alpha's reach set and its beta's — published at
 * once across the topic's partitions. Some keys receive several events: a snapshot, a delete and a
 * snapshot again, or a snapshot then a delete. Each key's events are aligned to the end of the
 * batch, so its last round carries every key's final event together. The instance must converge on the state the suite
 * computes from each key's last event (W33, W34). Races are timing-dependent, so a pass is evidence
 * and a single failure is conclusive, which is why it runs several independent bursts.
 *
 * <pre>
 *   A holds B0..B19          each beta holds ten gammas, G(i,0)..G(i,9)
 *   every third gamma:  snapshot, delete, snapshot        (ends present)
 *   the next of each three:  snapshot, delete              (ends absent)
 *   every fourth beta's holds edge:  snapshot, delete      (the beta and its gammas detach from A)
 * </pre>
 */
@Fixture
class ConcurrencyFixtureTest {

    static final int BETAS = 20;
    static final int GAMMAS_PER_BETA = 10;
    /** Independent bursts on fresh ids: a race shows in some and not others, and one failure is conclusive. */
    static final int BURSTS = 3;

    final Instance instance = Instance.get();

    @Test
    void overlappingEventsConvergeOnTheSerialOutcome() { // W33, W34
        for (int burst = 0; burst < BURSTS; burst++) {
            burst(instance.runId().child("concurrency-" + burst));
        }
    }

    void burst(RunId run) {
        Ref alpha = run.ref(ALPHA, "A");
        Map<Edge, List<String>> history = new LinkedHashMap<>(); // each key's events, in order
        for (int i = 0; i < BETAS; i++) {
            Ref beta = run.ref(BETA, "B" + i);
            history.put(Edge.of(alpha, HOLDS, beta), i % 4 == 3 ? List.of("snapshot", "delete") : List.of("snapshot"));
            for (int j = 0; j < GAMMAS_PER_BETA; j++) {
                List<String> events = switch (j % 3) {
                    case 1 -> List.of("snapshot", "delete", "snapshot");
                    case 2 -> List.of("snapshot", "delete");
                    default -> List.of("snapshot");
                };
                history.put(Edge.of(beta, HOLDS, run.ref(GAMMA, "G" + i + "," + j)), events);
            }
        }

        // Interleave in rounds, each key's history aligned to the end, so the last round holds every
        // key's last event at once. A stale reach set is repaired by any later recompute of it, so
        // it is the final writes that must overlap for a race to show in the final state (W34).
        int rounds = history.values().stream().mapToInt(List::size).max().orElseThrow();
        List<Outgoing> batch = new ArrayList<>();
        Set<Integer> partitions = new HashSet<>();
        for (int round = 0; round < rounds; round++) {
            for (Map.Entry<Edge, List<String>> key : history.entrySet()) {
                int event = round - (rounds - key.getValue().size());
                if (event >= 0) {
                    Edge edge = key.getKey();
                    if (key.getValue().get(event).equals("snapshot")) {
                        batch.add(Outgoing.snapshot(edge));
                    } else {
                        batch.addAll(Outgoing.delete(edge));
                    }
                    partitions.add(instance.publisher().partitionOf(EdgeEvents.key(edge)));
                }
            }
        }
        assertTrue(partitions.size() >= TestProfile.MIN_INGEST_PARTITIONS,
            () -> "the batch must span the topic's partitions, but used " + partitions);

        Oracle oracle = new Oracle(TestProfile.CARDINALITY_RULES);
        history.forEach((edge, events) -> {
            if (events.get(events.size() - 1).equals("snapshot")) {
                oracle.snapshot(edge); // each key ends at its last record (W33)
            }
        });
        Set<Ref> everything = new HashSet<>(List.of(alpha));
        history.keySet().forEach(e -> everything.add(e.object()));

        instance.publisher().sendAll(batch);

        List<ClosureDecl> closures = List.of(TestProfile.CONTENTS, TestProfile.CONTAINERS);
        instance.settle().until("every reach set converges on the serial outcome", () -> {
            Map<String, Set<Ref>> wrong = new HashMap<>();
            for (ClosureDecl closure : closures) {
                for (Ref ref : everything) {
                    if (!instance.of(closure.name(), List.of(ref)).references()
                            .equals(oracle.reach(closure.closure(), Set.of(ref)))) {
                        wrong.computeIfAbsent(closure.name(), c -> new HashSet<>()).add(ref);
                    }
                }
            }
            return wrong;
        }, Map::isEmpty);

        for (ClosureDecl closure : closures) {
            for (Ref ref : everything) {
                Result expected = oracle.of(closure.closure(), ref);
                Map<String, Set<Ref>> breaches = new HashMap<>();
                TestProfile.CARDINALITY_RULES.forEach(rule -> {
                    if (!expected.breachesOf(rule.id()).isEmpty()) {
                        breaches.put(rule.id(), expected.breachesOf(rule.id()));
                    }
                });
                Qualifications.assertQualifications(instance.of(closure.name(), List.of(ref)).meta(),
                    expected.cycles(), breaches);
            }
        }
    }
}
