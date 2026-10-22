package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Observing that an ingest event was rejected (§7, suite-design §6). A rejected event is skipped
 * (W27), so its only effect is an absence. The event names fresh references used nowhere else, so
 * had it been applied they would be known (L12) whichever closure is asked. After it, a sentinel on
 * the same partition and a different key is published and waited for: seeing the sentinel proves
 * consumption continued past the invalid record, and the references still being unknown is evidence
 * — not proof, since W34 lets keys apply in either order — that it was not applied.
 */
public final class IngestRejections {

    private IngestRejections() {
    }

    /** Publishes {@code event} under {@code key}, then asserts none of {@code fresh} became known. */
    public static void assertRejected(Instance instance, String key, JsonNode event, List<Ref> fresh) {
        for (Ref ref : fresh) {
            assertFalse(instance.isKnown(ref), () -> ref + " must be fresh for the rejection to be observable");
        }
        instance.publisher().send(key, event);
        instance.awaitConsumedPast(key);
        for (Ref ref : fresh) {
            assertFalse(instance.isKnown(ref), () -> "the event was applied: " + ref + " became known (W27)");
        }
    }

    /** The two ends of each edge, which an applied event would make known. */
    public static List<Ref> ends(Edge... edges) {
        return java.util.Arrays.stream(edges).flatMap(e -> java.util.stream.Stream.of(e.subject(), e.object())).toList();
    }

    /**
     * The control for a rejection: the same triple, sent well-formed under its own key, is applied.
     * Without it, an instance applying nothing at all would pass every rejection case.
     */
    public static void assertAccepted(Instance instance, Edge edge) {
        instance.publisher().snapshot(edge);
        instance.settle().until(edge + " is applied once well-formed", () -> instance.isKnown(edge.subject()), known -> known);
        assertTrue(instance.isKnown(edge.object()));
    }
}
