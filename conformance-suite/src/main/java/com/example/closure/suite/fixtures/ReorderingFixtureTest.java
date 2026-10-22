package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.example.closure.suite.RunId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixture 14 (§7): a {@code delete} of one edge and a {@code snapshot} of another, touching the same
 * resource, converge to the same state in either order (W24). The suite fixes the order by waiting
 * for one event's effect before publishing the other, so no partition arrangement is needed.
 *
 * <pre>
 *   before:  A1 holds O
 *   events:  delete (A1 holds O)   snapshot (A2 holds O)     — in each order, on fresh ids
 *   after:   A2 holds O
 * </pre>
 */
@Fixture
class ReorderingFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();

    final Instance instance = Instance.get();

    /** One run of the scenario on its own ids: the two references, the shared one, and the edges. */
    record Scenario(Ref a1, Ref a2, Ref shared, Edge old, Edge replacement) {
        static Scenario on(RunId run) {
            Ref a1 = run.ref(ALPHA, "A1");
            Ref a2 = run.ref(ALPHA, "A2");
            Ref shared = run.ref(OMEGA, "O");
            return new Scenario(a1, a2, shared, Edge.of(a1, HOLDS, shared), Edge.of(a2, HOLDS, shared));
        }
    }

    Scenario before(String name) {
        Scenario s = Scenario.on(instance.runId().child(name));
        instance.publisher().snapshot(s.old());
        instance.settledOf(CONTENTS, s.a1(), Set.of(s.a1(), s.shared()));
        return s;
    }

    void deleteTheOld(Scenario s) {
        instance.publisher().delete(s.old());
        instance.settledOf(CONTENTS, s.a1(), Set.of());
    }

    void snapshotTheReplacement(Scenario s) {
        instance.publisher().snapshot(s.replacement());
        instance.settledOf(CONTENTS, s.a2(), Set.of(s.a2(), s.shared()));
    }

    void assertConverged(Scenario s) {
        assertEquals(Set.of(s.shared(), s.a2()), instance.of(CONTAINERS, List.of(s.shared())).references(),
            "the shared resource has only its new holder");
        assertEquals(Set.of(s.a2(), s.shared()), instance.of(CONTENTS, List.of(s.a2())).references(),
            "the new holder holds it");
        assertEquals(Set.of(), instance.of(CONTENTS, List.of(s.a1())).references(),
            "the old holder, whose only edge was deleted, is unknown");
    }

    @Test
    void deleteThenSnapshotConvergesToTheFinalState() { // W24
        Scenario s = before("delete-first");
        deleteTheOld(s);
        snapshotTheReplacement(s);
        assertConverged(s);
    }

    @Test
    void snapshotThenDeleteConvergesToTheSameFinalState() { // W24
        Scenario s = before("snapshot-first");
        snapshotTheReplacement(s);
        deleteTheOld(s);
        assertConverged(s);
    }
}
