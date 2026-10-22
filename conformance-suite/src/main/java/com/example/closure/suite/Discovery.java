package com.example.closure.suite;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the suite reads from a discovery document that has passed fixture 1: where to publish, and
 * where each closure is resolved and diagnosed (W4, W5, W36). Discovery is a check rather than a
 * source of meaning (§7), but addresses are the deployment's own, so these come from the document.
 */
public record Discovery(String ingestTopic, Map<String, URI> ofLinks, Map<String, URI> cyclesLinks) {

    public Discovery {
        Objects.requireNonNull(ingestTopic, "ingestTopic");
        ofLinks = Map.copyOf(ofLinks);
        cyclesLinks = Map.copyOf(cyclesLinks);
    }

    /** Reads a document {@link ProfileCheck} has already accepted. */
    static Discovery from(JsonNode document, URI documentUrl) {
        JsonNode attributes = document.path("data").path("attributes");
        Map<String, URI> of = new LinkedHashMap<>();
        Map<String, URI> cycles = new LinkedHashMap<>();
        for (JsonNode closure : attributes.path("closures")) {
            String name = closure.path("name").textValue();
            of.put(name, documentUrl.resolve(URI.create(closure.path("links").path("of").textValue())));
            cycles.put(name, documentUrl.resolve(URI.create(closure.path("links").path("cycles").textValue())));
        }
        return new Discovery(attributes.path("ingestTopic").textValue(), of, cycles);
    }

    /** The {@code of} link of the closure named {@code closure} (W5). */
    public URI ofLink(String closure) {
        return link(ofLinks, closure);
    }

    /** The cycle diagnostic link of the closure named {@code closure} (W36). */
    public URI cyclesLink(String closure) {
        return link(cyclesLinks, closure);
    }

    private static URI link(Map<String, URI> links, String closure) {
        URI link = links.get(closure);
        if (link == null) {
            throw new IllegalArgumentException("the instance publishes no closure named " + closure);
        }
        return link;
    }
}
