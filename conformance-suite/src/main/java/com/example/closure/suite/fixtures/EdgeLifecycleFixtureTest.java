package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.EdgeEvents;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.example.closure.suite.RunId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fixture 11 (§7): one edge through its lifecycle, polling through {@code of} until each event's
 * effect is visible before publishing the next. Publishing them all before looking would let an
 * implementation coalescing records for one key (W33) show only the final state.
 */
@Fixture
class EdgeLifecycleFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();

    final Instance instance = Instance.get();

    @Test
    void anEdgeExistsIsUnchangedByRedeliveryThenIsGone() { // W20, W24, W30
        RunId run = instance.runId().child("lifecycle");
        Ref holder = run.ref(ALPHA, "A");
        Ref held = run.ref(OMEGA, "O");
        Edge edge = Edge.of(holder, HOLDS, held);
        String key = EdgeEvents.key(edge);
        ObjectNode snapshot = EdgeEvents.snapshot(edge, UUID.randomUUID().toString(), List.of("fixture-11"));

        instance.publisher().send(key, snapshot);
        instance.settledOf(CONTENTS, holder, Set.of(holder, held));

        instance.publisher().send(key, snapshot); // the same record again: a redelivery
        instance.awaitConsumedPast(key);
        assertEquals(Set.of(holder, held), instance.of(CONTENTS, List.of(holder)).references(),
            "a redelivered snapshot changes nothing (W24)");
        assertEquals(Set.of(held, holder), instance.of(CONTAINERS, List.of(held)).references(),
            "a redelivered snapshot changes nothing (W24)");

        instance.publisher().delete(edge); // the delete envelope, then its tombstone (W30)
        instance.settledOf(CONTENTS, holder, Set.of());
        assertEquals(Set.of(), instance.of(CONTAINERS, List.of(held)).references(),
            "once its only edge is deleted, a reference is no longer known (L12, L16)");
    }

    @Test
    void aDeleteCarryingScopeAndAnUnrelatedAttributeStillRetractsItsEdge() { // W19, W20
        RunId run = instance.runId().child("lifecycle-extra");
        Ref holder = run.ref(ALPHA, "A");
        Ref held = run.ref(OMEGA, "O");
        Edge edge = Edge.of(holder, HOLDS, held);
        String key = EdgeEvents.key(edge);

        instance.publisher().snapshot(edge);
        instance.settledOf(CONTENTS, holder, Set.of(holder, held));

        ObjectNode delete = EdgeEvents.delete(edge, UUID.randomUUID().toString());
        ObjectNode innerAttributes = (ObjectNode) delete.path("data").path("attributes").path("delete").path("attributes");
        innerAttributes.putArray("scope").add("fixture-11");
        innerAttributes.put("colour", "teal");
        instance.publisher().send(key, delete);
        instance.publisher().send(key, (byte[]) null);
        instance.settledOf(CONTENTS, holder, Set.of());
    }
}
