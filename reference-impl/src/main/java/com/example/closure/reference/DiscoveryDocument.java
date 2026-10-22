package com.example.closure.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;

/**
 * The discovery document this instance publishes (W4). It is Appendix A.6 of the contract, held
 * verbatim in {@code discovery.json}, with only what A.6 leaves to the deployment filled in: the
 * ingest topic and each closure's {@code of} link. It is deliberately not built from the suite's
 * {@code TestProfile}, so fixture 1 compares two independent transcriptions of Appendix A.
 */
final class DiscoveryDocument {

    static final String RESOURCE = "discovery.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DiscoveryDocument() {
    }

    /** A.6 exactly as the contract gives it. */
    static JsonNode template() {
        try (InputStream in = DiscoveryDocument.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Where this instance serves {@code of} for a closure; the contract leaves the path to it (W4). */
    static String ofPath(String closure) {
        return "/closures/" + closure + "/of";
    }

    /** Where this instance serves a closure's cycle diagnostic (W4, W36). */
    static String cyclesPath(String closure) {
        return "/closures/" + closure + "/cycles";
    }

    /** The document for an instance at {@code base} consuming {@code ingestTopic}. */
    static byte[] render(URI base, String ingestTopic) {
        return render(base, ingestTopic, Variant.CORRECT);
    }

    static byte[] render(URI base, String ingestTopic, Variant variant) {
        JsonNode document = template();
        ObjectNode attributes = (ObjectNode) document.path("data").path("attributes");
        attributes.put("ingestTopic", ingestTopic);
        if (variant == Variant.MISCONFIGURED) {
            for (JsonNode type : attributes.path("edgeTypes")) {
                if (type.has("inverse") && !type.path("authored").asText().equals(type.path("name").asText())) {
                    ((ObjectNode) type).put("authored", type.path("name").asText());
                }
            }
        }
        for (JsonNode closure : attributes.path("closures")) {
            String name = closure.path("name").asText();
            ObjectNode links = (ObjectNode) closure.path("links");
            links.put("of", base.resolve(ofPath(name).substring(1)).toString());
            links.put("cycles", base.resolve(cyclesPath(name).substring(1)).toString());
        }
        try {
            return JSON.writeValueAsBytes(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
