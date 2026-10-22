package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Closure;
import com.example.closure.model.Edge;
import com.example.closure.model.EdgeId;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a cycle diagnostic response (W36): a {@code meta}-only document naming the closure, and
 * {@code cycles} holding exactly the expected cycle groups, each with exactly its members and the
 * admitted edges among them. Groups, members and edges are compared as sets (L6).
 */
public final class CycleDiagnosis {

    /** A cycle group as the suite expects it: its members, and the edges among them as edge references. */
    record Group(Set<Ref> members, Set<Ref> edges) {
    }

    private CycleDiagnosis() {
    }

    /**
     * The group {@code members} forms in {@code graph} under {@code closure}: the members, and every
     * edge of the graph with both ends among them whose type the closure admits. In a strongly
     * connected group every such edge lies on a cycle (W36), so this is a filter over the graph,
     * not a closure computation.
     */
    static Group expected(Closure closure, Set<Edge> graph, Set<Ref> members) {
        Set<Ref> edges = graph.stream()
            .filter(e -> closure.admitsType(e.edgeType()))
            .filter(e -> members.contains(e.subject()) && members.contains(e.object()))
            .map(e -> Ref.of(EdgeId.EDGE, EdgeId.of(e)))
            .collect(Collectors.toSet());
        return new Group(Set.copyOf(members), edges);
    }

    /** Asserts the diagnostic for {@code closure.of(arguments)} reports exactly {@code cycles}. */
    public static void assertDiagnosis(OfResponse response, String closure, Set<Edge> graph, List<Set<Ref>> cycles) {
        assertEquals(200, response.status(), () -> "the cycle diagnostic answers a well-formed request (W36): " + response.body());
        assertFalse(response.body().has("data"), () -> "a diagnosis has no primary data (W36): " + response.body());
        JsonNode meta = response.meta();
        assertEquals(closure, meta.path("closure").asText(null), () -> "meta names the closure (W36): " + meta);
        assertTrue(meta.path("of").isArray(), () -> "meta carries the argument set (W36): " + meta);
        assertTrue(meta.path("cycles").isArray(), () -> "meta carries cycles, empty when there are none (W36): " + meta);

        Closure admits = TestProfile.closure(closure);
        Set<Group> expected = cycles.stream().map(members -> expected(admits, graph, members)).collect(Collectors.toSet());
        Set<Group> reported = new HashSet<>();
        for (JsonNode group : meta.path("cycles")) {
            reported.add(new Group(references(group.path("members")), references(group.path("edges"))));
        }
        assertEquals(expected, reported, () -> "the diagnostic does not report exactly the result's cycle groups (W36)");
    }

    private static Set<Ref> references(JsonNode array) {
        Set<Ref> refs = new HashSet<>();
        array.forEach(r -> refs.add(Ref.of(r.path("type").asText(), r.path("id").asText())));
        return refs;
    }
}
