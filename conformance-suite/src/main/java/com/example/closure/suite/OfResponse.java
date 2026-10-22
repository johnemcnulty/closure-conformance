package com.example.closure.suite;

import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An {@code of} response as received. It keeps the whole body, so a fixture can assert on
 * {@code meta} and {@code errors}, and offers the primary data as references.
 */
public record OfResponse(int status, String contentType, JsonNode body) {

    public OfResponse {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
    }

    /** The primary data as given, duplicates included, so a fixture can check there are none (L6). */
    public List<Ref> referenceList() {
        JsonNode data = body.path("data");
        if (!data.isArray()) {
            throw new AssertionError("the response's primary data is not an array (W8): " + body);
        }
        List<Ref> refs = new ArrayList<>();
        for (JsonNode element : data) {
            if (!element.path("type").isTextual() || !element.path("id").isTextual()) {
                throw new AssertionError("the response holds something that is not a resource reference (W8): " + element);
            }
            refs.add(Ref.of(element.path("type").textValue(), element.path("id").textValue()));
        }
        return refs;
    }

    /** The result, as the set it is (L6). */
    public Set<Ref> references() {
        return new HashSet<>(referenceList());
    }

    public JsonNode meta() {
        return body.path("meta");
    }

    public JsonNode errors() {
        return body.path("errors");
    }
}
