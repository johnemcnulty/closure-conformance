package com.example.closure.suite;

import com.example.closure.model.Edge;
import com.example.closure.model.EdgeId;
import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Builds well-formed ingest events (§6.5). Fixtures that need a malformed one start from these and
 * break a single member, so each invalid event differs from a valid one in one known way.
 */
public final class EdgeEvents {

    static final ObjectMapper JSON = new ObjectMapper();

    private EdgeEvents() {
    }

    /** The record key for every event about {@code edge}: its edge reference (W24, W28). */
    public static String key(Edge edge) {
        return EdgeId.referenceOf(edge);
    }

    /** A {@code snapshot} asserting {@code edge} exists, asserted by {@code scope} (W20, W22). */
    public static ObjectNode snapshot(Edge edge, String eventId, List<String> scope) {
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("a snapshot's scope is never empty (W22)");
        }
        ObjectNode inner = inner(edge);
        scope.forEach(((ObjectNode) inner.get("attributes")).putArray("scope")::add);
        return envelope("snapshot", eventId, inner, edge);
    }

    /** A {@code delete} retracting {@code edge}, carrying its triple and nothing else (W20). */
    public static ObjectNode delete(Edge edge, String eventId) {
        return envelope("delete", eventId, inner(edge), edge);
    }

    private static ObjectNode inner(Edge edge) {
        ObjectNode inner = JSON.createObjectNode();
        inner.put("type", EdgeId.EDGE).put("id", EdgeId.of(edge));
        inner.putObject("attributes").put("edgeType", edge.edgeType());
        ObjectNode relationships = inner.putObject("relationships");
        relationships.putObject("subject").set("data", reference(edge.subject()));
        relationships.putObject("object").set("data", reference(edge.object()));
        return inner;
    }

    private static ObjectNode envelope(String kind, String eventId, ObjectNode inner, Edge edge) {
        ObjectNode document = JSON.createObjectNode();
        ObjectNode data = document.putObject("data");
        data.put("type", kind).put("id", eventId); // W15, W16
        data.putObject("attributes").set(kind, inner); // W17
        data.putObject("relationships").putObject("resource")
            .set("data", reference(new Ref(EdgeId.EDGE, EdgeId.of(edge)))); // W18
        return document;
    }

    static ObjectNode reference(Ref ref) {
        return JSON.createObjectNode().put("type", ref.type()).put("id", ref.id());
    }
}
