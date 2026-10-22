package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sends {@code of} requests carrying an argument as given, and asserts how the instance answers:
 * {@code reference_malformed}, locating the argument with {@code source.pointer} and repeating it
 * in {@code meta.argument} (L7, L9, W11), or a success (W8, W12). {@code detail} is free text and is
 * never asserted on.
 */
public final class Rejections {

    public static final String REFERENCE_MALFORMED = "reference_malformed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Rejections() {
    }

    /** Where {@link #ask} puts its argument in the request. */
    public static final String ONLY_ARGUMENT = "/data/0";

    /** {@code of} on {@code closure} with {@code argument} as the request's only member of {@code data}. */
    public static OfResponse ask(Instance instance, String closure, JsonNode argument) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("data").add(argument);
        return new OfClient().post(instance.discovery().ofLink(closure), body);
    }

    public static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    /**
     * A well-formed {@code errors} document whose first error is {@code reference_malformed}, with
     * status 400, pointing at {@code pointer} and carrying {@code argument} as received (W11).
     */
    public static void assertMalformed(OfResponse response, JsonNode argument, String pointer) {
        assertEquals(400, response.status(), () -> "reference_malformed carries status 400 (W11): " + response.body());
        JsonNode errors = response.errors();
        assertTrue(errors.isArray() && !errors.isEmpty(), () -> "a rejection is an errors document (W11): " + response.body());
        assertFalse(response.body().has("data"), () -> "an errors document carries no data: " + response.body());
        JsonNode error = errors.get(0);
        assertEquals(REFERENCE_MALFORMED, error.path("code").asText(), () -> "the first error's code (W11): " + error);
        if (error.has("status")) {
            assertEquals("400", error.path("status").asText(), () -> "an error's status matches the response (W11): " + error);
        }
        assertEquals(pointer, error.path("source").path("pointer").asText(null),
            () -> "the error's source.pointer locates the argument (W11): " + error);
        assertEquals(argument, error.path("meta").path("argument"),
            () -> "the error's meta.argument is the argument as received (W11): " + error);
    }

    /** A success with no errors document, whatever the result (W8, W12, L14). */
    public static void assertAnswered(OfResponse response) {
        assertEquals(200, response.status(), () -> "a well-formed request succeeds (W8, W12): " + response.body());
        assertFalse(response.body().has("errors"), () -> "a success carries no errors (W12): " + response.body());
        assertTrue(response.body().path("data").isArray(), () -> "a success carries data (W8): " + response.body());
    }
}
