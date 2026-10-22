package com.example.closure.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Fetches the instance's discovery document (W4), the one address the suite is given. */
public final class DiscoveryClient {

    public static final String JSON_API = "application/vnd.api+json";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @throws IOException if the document cannot be fetched, is not a {@code 200}, or is not JSON;
     *     the message says which, for fixture 1 to report
     */
    public JsonNode fetch(URI discoveryUrl) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(discoveryUrl)
            .header("Accept", JSON_API)
            .timeout(TIMEOUT)
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + discoveryUrl, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET " + discoveryUrl + " returned " + response.statusCode());
        }
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new IOException("the document at " + discoveryUrl + " is not JSON: " + e.getMessage(), e);
        }
    }
}
