package com.example.closure.reference;

import com.example.closure.model.Admit;
import com.example.closure.model.Direction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What this instance is configured with, read from the document it publishes (A.6): its edge types
 * and its closures, each with its admitted pairs. The derived side of each pair gets its carrier's
 * pairs flipped (W4). It refuses to build from a configuration the contract says an instance must
 * refuse to start with (L2, W23).
 */
record Configuration(Map<String, EdgeType> edgeTypes, Map<String, ClosureDef> closures, List<Rule> rules) {

    record EdgeType(String name, Optional<String> inverse, String authored) {
    }

    /** A cardinality rule (W4): the edges of {@code edgeType} counted at one end of each listed type. */
    record Rule(String id, String edgeType, boolean atObject, Set<String> types, Integer min, Integer max) {
        boolean breachedBy(int count) {
            return (min != null && count < min) || (max != null && count > max);
        }
    }

    record ClosureDef(String name, String inverse, Set<Admit> admits) {
        /** The admitted pairs naming {@code edgeType}, which an edge of that type is stepped along. */
        Set<Direction> directionsFor(String edgeType) {
            return admits.stream().filter(a -> a.edgeType().equals(edgeType)).map(Admit::direction)
                .collect(Collectors.toSet());
        }
    }

    Configuration {
        edgeTypes = Map.copyOf(edgeTypes);
        closures = Map.copyOf(closures);
        rules = List.copyOf(rules);
    }

    static Configuration from(JsonNode document) {
        return from(document, Variant.CORRECT);
    }

    static Configuration from(JsonNode document, Variant variant) {
        JsonNode attributes = document.path("data").path("attributes");

        Map<String, EdgeType> edgeTypes = new LinkedHashMap<>();
        for (JsonNode entry : attributes.path("edgeTypes")) {
            EdgeType type = new EdgeType(entry.path("name").asText(),
                Optional.ofNullable(entry.path("inverse").textValue()), entry.path("authored").asText());
            if (!type.authored().equals(type.name()) && !type.inverse().equals(Optional.of(type.authored()))) {
                throw new IllegalArgumentException("edge type " + type.name() + " is authored as neither of its names (W23)");
            }
            edgeTypes.put(type.authored(), type);
        }

        Map<String, Set<Admit>> stated = new HashMap<>();
        Map<String, String> inverses = new LinkedHashMap<>();
        for (JsonNode entry : attributes.path("closures")) {
            String name = entry.path("name").asText();
            inverses.put(name, entry.path("inverse").asText());
            if (entry.has("admits")) {
                Set<Admit> admits = new HashSet<>();
                for (JsonNode pair : entry.path("admits")) {
                    admits.add(admitted(edgeTypes, name, pair));
                }
                stated.put(name, admits);
            }
        }

        Map<String, ClosureDef> closures = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : inverses.entrySet()) {
            String name = pair.getKey();
            String inverse = pair.getValue();
            if (!name.equals(inverses.get(inverse))) {
                throw new IllegalArgumentException("closure " + name + " names " + inverse + " as its inverse, which does not name it back (L3)");
            }
            Set<Admit> admits;
            if (stated.containsKey(name) == stated.containsKey(inverse)) {
                throw new IllegalArgumentException("exactly one of " + name + " and " + inverse + " states admitted pairs (W4)");
            } else if (stated.containsKey(name)) {
                admits = stated.get(name);
            } else {
                admits = variant == Variant.UNFLIPPED_INVERSE
                    ? Set.copyOf(stated.get(inverse))
                    : stated.get(inverse).stream().map(Admit::flip).collect(Collectors.toSet());
            }
            for (Admit admit : admits) {
                if (admits.contains(admit.flip())) {
                    throw new IllegalArgumentException("closure " + name + " admits both directions of " + admit.edgeType() + " (L2)");
                }
            }
            closures.put(name, new ClosureDef(name, inverse, Set.copyOf(admits)));
        }

        List<Rule> rules = new ArrayList<>();
        for (JsonNode entry : attributes.path("cardinalityRules")) {
            boolean atObject = entry.has("objectTypes");
            String end = atObject ? "Object" : "Subject";
            Set<String> types = new HashSet<>();
            entry.path(atObject ? "objectTypes" : "subjectTypes").forEach(t -> types.add(t.asText()));
            JsonNode min = entry.path("minEdgesPer" + end);
            JsonNode max = entry.path("maxEdgesPer" + end);
            rules.add(new Rule(entry.path("id").asText(), entry.path("edgeType").asText(), atObject, Set.copyOf(types),
                min.isInt() ? min.intValue() : null, max.isInt() ? max.intValue() : null));
        }
        return new Configuration(edgeTypes, closures, rules);
    }

    /** A pair as configured, normalized to the authored name: an unauthored one flips direction (W4). */
    private static Admit admitted(Map<String, EdgeType> edgeTypes, String closure, JsonNode pair) {
        String given = pair.path("edgeType").asText();
        Direction direction = switch (pair.path("direction").asText()) {
            case "forward" -> Direction.FORWARD;
            case "reverse" -> Direction.REVERSE;
            default -> throw new IllegalArgumentException("closure " + closure + " has a pair with no direction: " + pair);
        };
        for (EdgeType type : edgeTypes.values()) {
            if (type.authored().equals(given)) {
                return new Admit(given, direction);
            }
            if (List.of(type.name(), type.inverse().orElse(type.name())).contains(given)) {
                return new Admit(type.authored(), direction.flip());
            }
        }
        throw new IllegalArgumentException("closure " + closure + " admits an undeclared edge type " + given);
    }

    /** Whether ingest accepts {@code edgeType}: only a declared type's authored name (W23). */
    boolean isAuthored(String edgeType) {
        return edgeTypes.containsKey(edgeType);
    }

    /** Whether {@code name} is either name of a declared edge type; used only by {@link Variant#ACCEPTS_UNAUTHORED}. */
    boolean isDeclaredName(String name) {
        return edgeTypes.values().stream()
            .anyMatch(t -> t.name().equals(name) || t.inverse().filter(name::equals).isPresent());
    }
}
