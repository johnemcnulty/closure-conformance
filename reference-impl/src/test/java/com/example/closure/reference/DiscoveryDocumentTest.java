package com.example.closure.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DiscoveryDocumentTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** The JSON block under the contract's heading A.6. */
    static JsonNode contractA6() throws IOException {
        String spec = Files.readString(Path.of("..", "docs", "contract-spec.md"));
        Matcher block = Pattern.compile("### A\\.6 .*?```json\\n(.*?)```", Pattern.DOTALL).matcher(spec);
        assertTrue(block.find(), "contract-spec.md has no JSON block under A.6");
        return JSON.readTree(block.group(1));
    }

    @Test
    void theResourceIsTheContractsA6Document() throws IOException { // W4, Appendix A.6
        assertEquals(contractA6(), DiscoveryDocument.template());
    }

    @Test
    void renderingFillsInOnlyTheTopicAndTheLinks() throws IOException { // Appendix A.6
        URI base = URI.create("http://localhost:1234/");
        JsonNode rendered = JSON.readTree(DiscoveryDocument.render(base, "a-topic"));

        ObjectNode attributes = (ObjectNode) rendered.path("data").path("attributes");
        assertEquals("a-topic", attributes.path("ingestTopic").asText());
        for (JsonNode closure : attributes.path("closures")) {
            String name = closure.path("name").asText();
            assertEquals("http://localhost:1234/closures/" + name + "/of", closure.path("links").path("of").asText());
            assertEquals("http://localhost:1234/closures/" + name + "/cycles", closure.path("links").path("cycles").asText());
        }

        JsonNode template = DiscoveryDocument.template();
        ObjectNode expected = (ObjectNode) template.path("data").path("attributes");
        expected.put("ingestTopic", "a-topic");
        for (int i = 0; i < expected.path("closures").size(); i++) {
            JsonNode renderedLinks = attributes.path("closures").get(i).path("links");
            ((ObjectNode) expected.path("closures").get(i).path("links"))
                .setAll((ObjectNode) renderedLinks);
        }
        assertEquals(template, rendered);
    }

    @Test
    void theInstanceServesTheDocumentAsJsonApi() throws Exception { // W4, W6
        ReferenceInstance instance = SelfTestInstance.ensureStarted();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(instance.discoveryUrl()).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("application/vnd.api+json", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals(SelfTestInstance.INGEST_TOPIC,
            JSON.readTree(response.body()).path("data").path("attributes").path("ingestTopic").asText());
    }
}
