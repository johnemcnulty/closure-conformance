package com.example.closure.suite;

import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;

/** Sends {@code of} requests as W5 gives them: a {@code POST} of resource reference objects. */
public final class OfClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();

    /** {@code of} over {@code refs}, at the closure's {@code link}. */
    public OfResponse of(URI link, Collection<Ref> refs) {
        ObjectNode body = json.createObjectNode();
        ArrayNode data = body.putArray("data");
        refs.forEach(ref -> data.add(EdgeEvents.reference(ref)));
        return post(link, body);
    }

    /** Posts {@code body} as given, for fixtures sending malformed arguments. */
    public OfResponse post(URI link, JsonNode body) {
        try {
            return post(link, json.writeValueAsString(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private OfResponse post(URI link, String body) {
        HttpRequest request = HttpRequest.newBuilder(link)
            .header("Content-Type", DiscoveryClient.JSON_API)
            .header("Accept", DiscoveryClient.JSON_API)
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = response.body().isEmpty() ? json.missingNode() : json.readTree(response.body());
            return new OfResponse(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""), parsed);
        } catch (IOException e) {
            throw new UncheckedIOException("of at " + link + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling " + link, e);
        }
    }
}
