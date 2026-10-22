package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.suite.IngestRejections.assertAccepted;
import static com.example.closure.suite.IngestRejections.assertRejected;
import static com.example.closure.suite.IngestRejections.ends;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.closure.model.Edge;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.EdgeEvents;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.example.closure.suite.RunId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Fixture 16 (§7): an ingest topic carries {@code snapshot} and {@code delete} only, and every inner
 * object is an {@code edge}; anything else is rejected (W26). Each event is otherwise a well-formed
 * snapshot of a fresh edge, so its kind or inner type is the only fault.
 */
@Fixture
class IngestKindFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();

    final Instance instance = Instance.get();

    Edge fresh(String name) {
        RunId run = instance.runId().child("ingest-kind-" + name);
        return Edge.of(run.ref(OMEGA, "S"), HOLDS, run.ref(OMEGA, "O"));
    }

    /** A snapshot of {@code edge} re-labelled as {@code kind}, its inner object moved to match (W17). */
    static ObjectNode ofKind(Edge edge, String kind) {
        ObjectNode event = EdgeEvents.snapshot(edge, UUID.randomUUID().toString(), List.of("fixture-16"));
        ObjectNode data = (ObjectNode) event.path("data");
        ObjectNode attributes = (ObjectNode) data.path("attributes");
        attributes.set(kind, attributes.remove("snapshot"));
        data.put("type", kind);
        return event;
    }

    @TestFactory
    Stream<DynamicTest> aKindOtherThanSnapshotOrDeleteIsRejected() { // W15, W26
        return Stream.of("upsert", "baseline", "create").flatMap(kind -> Stream.of(
            DynamicTest.dynamicTest(kind + " about a fresh edge is not applied as a snapshot", () -> {
                Edge edge = fresh(kind);
                assertRejected(instance, EdgeEvents.key(edge), ofKind(edge, kind), ends(edge));
                assertAccepted(instance, edge);
            }),
            DynamicTest.dynamicTest(kind + " about a visible edge is not applied as a delete", () -> {
                Edge edge = fresh(kind + "-existing");
                assertAccepted(instance, edge);
                instance.publisher().send(EdgeEvents.key(edge), ofKind(edge, kind));
                instance.awaitConsumedPast(EdgeEvents.key(edge));
                assertEquals(Set.of(edge.subject(), edge.object()),
                    instance.of(CONTENTS, List.of(edge.subject())).references(),
                    "the event was applied: it retracted an existing edge (W26, W27)");
            })));
    }

    @Test
    void anInnerObjectThatIsNotAnEdgeIsRejected() { // W26
        Edge edge = fresh("inner-type");
        ObjectNode event = EdgeEvents.snapshot(edge, UUID.randomUUID().toString(), List.of("fixture-16"));
        ((ObjectNode) event.path("data").path("attributes").path("snapshot")).put("type", OMEGA);
        assertRejected(instance, EdgeEvents.key(edge), event, ends(edge));
        assertAccepted(instance, edge);
    }
}
