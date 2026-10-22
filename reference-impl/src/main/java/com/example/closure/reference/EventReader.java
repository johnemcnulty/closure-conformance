package com.example.closure.reference;

import com.example.closure.model.Edge;
import com.example.closure.model.EdgeId;
import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/**
 * Reads one ingest record (§6.5, §6.6) into what to apply, or a reason to reject it. Rejecting
 * means skipping (W27): the caller applies nothing and carries on. Inner attributes other than
 * {@code edgeType}, {@code scope} included, are ignored and never validated (W19, W22).
 */
final class EventReader {

    /** What a record asks for. */
    sealed interface Read {
    }

    record Apply(Edge edge, boolean exists) implements Read {
    }

    /** A null-valued record: nothing to apply, and not malformed (W32). */
    record Tombstone() implements Read {
    }

    record Reject(String reason) implements Read {
        Reject {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Configuration configuration;
    private final Variant variant;

    EventReader(Configuration configuration) {
        this(configuration, Variant.CORRECT);
    }

    EventReader(Configuration configuration, Variant variant) {
        this.configuration = configuration;
        this.variant = variant;
    }

    Read read(String key, byte[] value) {
        if (value == null) {
            return new Tombstone();
        }
        JsonNode data;
        try {
            data = JSON.readTree(value).path("data");
        } catch (IOException e) {
            return new Reject("not JSON: " + e.getMessage());
        }
        String kind = data.path("type").asText();
        if (!kind.equals("snapshot") && !kind.equals("delete") && variant != Variant.KINDS_AS_DELETES) {
            return new Reject("an ingest topic carries snapshot and delete only, not " + kind + " (W26)");
        }
        JsonNode inner = data.path("attributes").path(kind);
        if (!inner.path("type").asText().equals(EdgeId.EDGE)) {
            return new Reject("the inner object of a " + kind + " is not an edge (W26)");
        }

        String edgeType = inner.path("attributes").path("edgeType").asText("");
        Ref subject = endpoint(inner.path("relationships").path("subject"));
        Ref object = endpoint(inner.path("relationships").path("object"));
        if (edgeType.isEmpty() || subject == null || object == null) {
            return new Reject("the inner object does not carry a whole triple (W20)");
        }
        boolean accepted = variant == Variant.ACCEPTS_UNAUTHORED
            ? configuration.isDeclaredName(edgeType)
            : configuration.isAuthored(edgeType);
        if (!accepted) {
            return new Reject(edgeType + " is not the authored name of a declared edge type (W23)");
        }
        Edge edge = Edge.of(subject, edgeType, object);
        if (variant == Variant.TRUSTS_TRIPLE) {
            return new Apply(edge, kind.equals("snapshot"));
        }

        if (!EdgeId.of(edge).equals(inner.path("id").asText())) {
            return new Reject("the inner id " + inner.path("id") + " is not the id of its triple (W25)");
        }
        if (!EdgeId.referenceOf(edge).equals(key)) {
            return new Reject("the record key " + key + " is not the edge's reference (W25)");
        }
        JsonNode resource = data.path("relationships").path("resource");
        if (!resource.isMissingNode()) {
            JsonNode named = resource.path("data");
            if (!named.path("type").asText().equals(EdgeId.EDGE) || !named.path("id").asText().equals(EdgeId.of(edge))) {
                return new Reject("relationships.resource names something other than the inner object (W25)");
            }
        }
        return new Apply(edge, kind.equals("snapshot"));
    }

    /** A to-one relationship's reference, or null if it is not a well-formed one (L7, L9). */
    private static Ref endpoint(JsonNode relationship) {
        JsonNode data = relationship.path("data");
        String type = data.path("type").textValue();
        String id = data.path("id").textValue();
        if (type == null || id == null || type.isEmpty() || id.isEmpty() || type.contains(":")) {
            return null;
        }
        return Ref.of(type, id);
    }
}
