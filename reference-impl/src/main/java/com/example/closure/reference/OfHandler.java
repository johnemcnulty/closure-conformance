package com.example.closure.reference;

import com.example.closure.model.EdgeId;
import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@code of} for one closure, or its cycle diagnostic. Both take a {@code POST} of resource
 * reference objects (W5, W36) and reject a malformed one alike (W11).
 *
 * <p>{@code of} answers with the reached references (W8) and a {@code meta} naming the closure, the
 * argument set and the qualifications (W9): {@code cycle_detected}, by its code alone, if the result
 * holds a cycle, and one {@code cardinality} per rule breached, listing the first ten breaching
 * references with the total (L28, W10). The diagnostic answers with {@code meta} alone, holding
 * every cycle group in the same result with its members and edges (W36).
 */
final class OfHandler implements HttpHandler {

    /** Which of a closure's two operations this handler serves. */
    enum Mode {
        OF, CYCLES;

        String path(String closure) {
            return this == OF ? DiscoveryDocument.ofPath(closure) : DiscoveryDocument.cyclesPath(closure);
        }
    }

    static final String JSON_API = "application/vnd.api+json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String closure;
    private final Mode mode;
    private final Graph graph;
    private final Variant variant;

    OfHandler(String closure, Mode mode, Graph graph) {
        this(closure, mode, graph, Variant.CORRECT);
    }

    OfHandler(String closure, Mode mode, Graph graph, Variant variant) {
        this.closure = closure;
        this.mode = mode;
        this.graph = graph;
        this.variant = variant;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(mode.path(closure))) {
                exchange.sendResponseHeaders(404, -1); // contexts match by prefix
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!acceptable(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                exchange.sendResponseHeaders(415, -1);
                return;
            }
            JsonNode request;
            try {
                request = JSON.readTree(exchange.getRequestBody());
            } catch (IOException e) {
                send(exchange, 400, error(400, "request_malformed", "the body is not JSON"));
                return;
            }
            JsonNode data = request == null ? null : request.get("data");
            if (data == null || !data.isArray()) {
                send(exchange, 400, error(400, "request_malformed", "the body has no data array (W5)"));
                return;
            }
            Set<Ref> arguments = new LinkedHashSet<>();
            for (int i = 0; i < data.size(); i++) {
                JsonNode argument = data.get(i);
                Ref ref = reference(argument);
                if (ref == null) {
                    send(exchange, 400, malformed("/data/" + i, argument)); // L7, L9, W11
                    return;
                }
                arguments.add(ref);
            }
            Graph.Answer answer = graph.answer(closure, arguments);
            if (mode == Mode.OF && variant == Variant.CEILING && answer.refs().size() > Variant.LIMIT) {
                send(exchange, 422, error(422, "result_too_large", "more than " + Variant.LIMIT + " references")); // W7
                return;
            }
            send(exchange, 200, mode == Mode.OF ? result(arguments, answer) : diagnosis(arguments, answer));
        }
    }

    /** {@code application/vnd.api+json}, or {@code application/json} as W6 recommends; parameters are not refused. */
    private static boolean acceptable(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals(JSON_API) || mediaType.equals("application/json");
    }

    /** A resource reference object, or null if it lacks a type or an id, or either is empty (L7, L9). */
    private Ref reference(JsonNode argument) {
        String type = argument.path("type").textValue();
        String id = argument.path("id").textValue();
        if (type == null || id == null || type.isEmpty() || id.isEmpty() || type.contains(":")) {
            return null;
        }
        return Ref.of(type, variant == Variant.CASE_FOLDS ? id.toLowerCase(Locale.ROOT) : id);
    }

    private ObjectNode result(Set<Ref> arguments, Graph.Answer answer) {
        ObjectNode document = JSON.createObjectNode();
        ArrayNode data = document.putArray("data");
        answer.refs().stream().limit(variant == Variant.TRUNCATES ? Variant.LIMIT : Long.MAX_VALUE)
            .forEach(ref -> data.add(returned(ref)));
        ObjectNode meta = document.putObject("meta");
        meta.put("closure", closure);
        ArrayNode of = meta.putArray("of");
        arguments.forEach(ref -> of.add(object(ref)));
        ArrayNode qualifications = meta.putArray("qualifications");
        if (!answer.cycles().isEmpty()) { // the code alone: the diagnostic says where (L28, W10)
            qualifications.addObject().put("code", "cycle_detected");
        }
        answer.breaches().forEach((rule, breaching) -> { // one per rule, the first ten listed, all counted (L28)
            ObjectNode q = qualifications.addObject().put("code", "cardinality").put("rule", rule);
            ArrayNode sources = q.putArray("sources");
            breaching.stream().limit(variant == Variant.ONE_BREACH_LISTED ? 1 : BREACHES_LISTED)
                .forEach(ref -> sources.add(object(ref)));
            q.put("total", variant == Variant.SHORT_TOTAL ? sources.size() : breaching.size());
        });
        return document;
    }

    /** The cycle diagnostic's answer: {@code meta} alone, with every cycle group in the result (W36). */
    private ObjectNode diagnosis(Set<Ref> arguments, Graph.Answer answer) {
        ObjectNode document = JSON.createObjectNode();
        ObjectNode meta = document.putObject("meta");
        meta.put("closure", closure);
        ArrayNode of = meta.putArray("of");
        arguments.forEach(ref -> of.add(object(ref)));
        ArrayNode cycles = meta.putArray("cycles");
        for (Graph.CycleGroup group : answer.cycles()) {
            ObjectNode entry = cycles.addObject();
            ArrayNode members = entry.putArray("members");
            group.members().forEach(ref -> members.add(object(ref)));
            ArrayNode edges = entry.putArray("edges");
            if (variant != Variant.EDGELESS_GROUPS) {
                group.edges().forEach(edge -> edges.add(object(new Ref(EdgeId.EDGE, EdgeId.of(edge)))));
            }
        }
        return document;
    }

    /** The floor L28 sets: at least this many breaching references are listed, or all if fewer. */
    static final int BREACHES_LISTED = 10;

    private static ObjectNode object(Ref ref) {
        return JSON.createObjectNode().put("type", ref.type()).put("id", ref.id());
    }

    /** A reference as a result returns it, which only a variant makes differ from {@link #object}. */
    private ObjectNode returned(Ref ref) {
        if (variant == Variant.PLACEHOLDER_FAR_END && !graph.isEverSubject(ref)) {
            return object(Ref.of("resource", ref.id()));
        }
        if (variant == Variant.TRIMS_IDS) {
            return object(Ref.of(ref.type(), ref.id().strip()));
        }
        return object(ref);
    }

    /** {@code reference_malformed}, locating the argument and carrying it as received (W11). */
    private ObjectNode malformed(String pointer, JsonNode argument) {
        ObjectNode document = error(400, "reference_malformed", "an argument that is not a (type, id) reference");
        ObjectNode error = (ObjectNode) document.path("errors").get(0);
        error.putObject("source").put("pointer", pointer);
        if (variant != Variant.OMITS_ARGUMENT) {
            error.putObject("meta").set("argument", argument);
        }
        return document;
    }

    private static ObjectNode error(int status, String code, String detail) {
        ObjectNode document = JSON.createObjectNode();
        document.putArray("errors").addObject()
            .put("status", Integer.toString(status)).put("code", code).put("detail", detail);
        return document;
    }

    private static void send(HttpExchange exchange, int status, JsonNode document) throws IOException {
        byte[] body = JSON.writeValueAsBytes(document);
        exchange.getResponseHeaders().set("Content-Type", JSON_API);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
