package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a response's {@code meta.qualifications} against what a row expects (L25–L28, W10).
 *
 * <ul>
 *   <li>{@code cycle_detected} is reported once exactly when a cycle is expected, carrying its
 *       code and nothing else (L28, W10); the cycles themselves are the diagnostic's to report
 *       ({@link CycleDiagnosis}, W36).</li>
 *   <li>{@code cardinality} is reported once per rule breached, and for no other rule. It names the
 *       rule by its published identifier, lists at least {@code min(10, total)} references, each a
 *       genuine breach and none twice, and gives {@code total} as the number breaching (L28).</li>
 * </ul>
 *
 * Codes beyond these two are not asserted on: L25 defines two, and does not forbid more.
 */
public final class Qualifications {

    public static final String CYCLE_DETECTED = "cycle_detected";
    public static final String CARDINALITY = "cardinality";

    private Qualifications() {
    }

    public static void assertQualifications(JsonNode meta, List<Set<Ref>> cycles, Map<String, Set<Ref>> breaches) {
        JsonNode qualifications = meta.path("qualifications");
        assertTrue(qualifications.isArray(), () -> "meta carries the qualifications (W9): " + meta);

        List<JsonNode> reportedCycles = new ArrayList<>();
        Map<String, List<JsonNode>> reportedRules = new HashMap<>();
        for (JsonNode q : qualifications) {
            switch (q.path("code").asText()) {
                case CYCLE_DETECTED -> reportedCycles.add(q);
                case CARDINALITY -> reportedRules.computeIfAbsent(q.path("rule").asText(), r -> new ArrayList<>()).add(q);
                default -> { }
            }
        }

        if (cycles.isEmpty()) {
            assertTrue(reportedCycles.isEmpty(),
                () -> "no cycle of the closure's admitted edges lies in the result, but one is reported (L27): " + reportedCycles);
        } else {
            assertEquals(1, reportedCycles.size(),
                () -> "the result contains a cycle, so cycle_detected is reported once (L25, L27, L28): " + qualifications);
            JsonNode q = reportedCycles.get(0);
            Set<String> members = new HashSet<>();
            q.fieldNames().forEachRemaining(members::add);
            assertEquals(Set.of("code"), members, () -> "cycle_detected carries its code and nothing else (W10): " + q);
        }

        for (String rule : TestProfile.CARDINALITY_RULES.stream().map(r -> r.id()).toList()) {
            List<JsonNode> reported = reportedRules.getOrDefault(rule, List.of());
            Set<Ref> breaching = breaches.get(rule);
            if (breaching == null) {
                assertTrue(reported.isEmpty(), () -> "the result breaches no rule " + rule + " admits, but it is reported (L27): " + reported);
                continue;
            }
            assertEquals(1, reported.size(), () -> "a response carries one cardinality qualification for " + rule + " (L28): " + qualifications);
            JsonNode q = reported.get(0);
            List<Ref> listed = referenceList(q.path("sources"));
            assertEquals(new HashSet<>(listed).size(), listed.size(), () -> rule + " lists a reference twice: " + listed);
            for (Ref ref : listed) {
                if (!breaching.contains(ref)) {
                    fail(rule + " lists " + ref + ", which does not breach it (L28); the breaches are " + breaching);
                }
            }
            int floor = Math.min(TestProfile.BREACH_REPORT_FLOOR, breaching.size());
            assertTrue(listed.size() >= floor,
                () -> rule + " lists " + listed.size() + " breaching references; at least " + floor + " are required (L28)");
            assertEquals(breaching.size(), q.path("total").asInt(-1),
                () -> rule + " gives the wrong total (L28): " + q);
        }
        for (String rule : reportedRules.keySet()) {
            assertTrue(TestProfile.CARDINALITY_RULES.stream().anyMatch(r -> r.id().equals(rule)),
                () -> "a cardinality qualification names " + rule + ", which is not a published rule identifier (L28, W4)");
        }
    }

    private static List<Ref> referenceList(JsonNode sources) {
        List<Ref> refs = new ArrayList<>();
        sources.forEach(s -> refs.add(Ref.of(s.path("type").asText(), s.path("id").asText())));
        return refs;
    }
}
