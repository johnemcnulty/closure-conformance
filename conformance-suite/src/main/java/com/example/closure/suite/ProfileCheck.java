package com.example.closure.suite;

import com.example.closure.model.Admit;
import com.example.closure.model.CardinalityRule;
import com.example.closure.model.ClosureDecl;
import com.example.closure.model.Direction;
import com.example.closure.model.EdgeTypeDecl;
import com.example.closure.model.End;
import com.example.closure.model.TestProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fixture 1's comparison: the discovery document against {@link TestProfile}, which must match
 * exactly (§7, W4). It returns every discrepancy rather than the first, each a sentence naming
 * what is wrong, so a misconfigured instance gets one readable report.
 *
 * <p>Lists are compared as sets: the contract fixes no order for closures, edge types, rules or
 * admitted pairs. Members the contract does not name are ignored.
 */
public final class ProfileCheck {

    private final List<String> problems = new ArrayList<>();

    private ProfileCheck() {
    }

    /**
     * @param documentUrl where the document was fetched from, which a relative {@code of} link
     *     resolves against
     * @return every discrepancy found; empty if the document matches the profile
     */
    public static List<String> discrepancies(JsonNode document, URI documentUrl) {
        ProfileCheck check = new ProfileCheck();
        check.document(document, documentUrl);
        return List.copyOf(check.problems);
    }

    private void document(JsonNode document, URI documentUrl) {
        JsonNode attributes = document.path("data").path("attributes");
        if (!attributes.isObject()) {
            problems.add("the document has no data.attributes object");
            return;
        }
        if (text(attributes, "ingestTopic").filter(topic -> !topic.isEmpty()).isEmpty()) {
            problems.add("the document names no ingest topic (W4)");
        }
        closures(attributes, documentUrl);
        edgeTypes(attributes);
        cardinalityRules(attributes);
    }

    // ---- closures ----

    private void closures(JsonNode attributes, URI documentUrl) {
        Map<String, JsonNode> published = entries(attributes, "closures", "name");
        Map<String, ClosureDecl> expected = TestProfile.CLOSURES.stream()
            .collect(Collectors.toMap(ClosureDecl::name, Function.identity()));
        compareNames("closure", published.keySet(), expected.keySet());
        for (ClosureDecl declared : TestProfile.CLOSURES) {
            JsonNode entry = published.get(declared.name());
            if (entry != null) {
                closure(declared, entry, documentUrl);
            }
        }
    }

    private void closure(ClosureDecl declared, JsonNode entry, URI documentUrl) {
        String name = declared.name();
        Optional<String> inverse = text(entry, "inverse");
        if (inverse.isEmpty()) {
            problems.add("closure " + name + " names no inverse (W4)");
        } else if (!inverse.get().equals(declared.inverse())) {
            problems.add("closure " + name + " names its inverse " + inverse.get()
                + ", but the profile's is " + declared.inverse());
        }

        for (String rel : List.of("of", "cycles")) { // W4: the resolution endpoint (W5) and the cycle diagnostic (W36)
            Optional<String> link = text(entry.path("links"), rel);
            if (link.isEmpty()) {
                problems.add("closure " + name + " carries no " + rel + " link (W4)");
            } else {
                link(name, rel, link.get(), documentUrl);
            }
        }

        boolean states = entry.has("admits");
        if (declared.statesAdmits() && !states) {
            problems.add("closure " + name + " states no admitted pairs, but the profile designates it"
                + " to state them for its pair (W4)");
        } else if (!declared.statesAdmits() && states) {
            problems.add("closure " + name + " states admitted pairs, but the profile designates its inverse "
                + declared.inverse() + " to state them; the pair's are stated once (W4)");
        } else if (states) {
            admits(name, entry.get("admits")).ifPresent(published -> {
                if (!published.equals(declared.closure().admits())) {
                    problems.add("closure " + name + " admits " + describe(published)
                        + ", but the profile's admits " + describe(declared.closure().admits()));
                }
            });
        }
    }

    /** A link the suite can resolve: a URI reference resolving to an absolute http(s) URL. */
    private void link(String closure, String rel, String link, URI documentUrl) {
        URI resolved;
        try {
            resolved = documentUrl.resolve(new URI(link));
        } catch (URISyntaxException e) {
            problems.add("closure " + closure + " has an " + rel + " link that is not a URI: " + link);
            return;
        }
        String scheme = Optional.ofNullable(resolved.getScheme()).orElse("").toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || resolved.getHost() == null) {
            problems.add("closure " + closure + " has an " + rel + " link that does not resolve to an http URL: " + link);
        }
    }

    /**
     * The admitted pairs as published, or empty if any entry is malformed. The published edge type
     * must already be the authored name (W4, W23); this does not normalize an unauthored one,
     * because publishing it is the fault.
     */
    private Optional<Set<Admit>> admits(String closure, JsonNode admits) {
        if (!admits.isArray()) {
            problems.add("closure " + closure + " has admits that is not an array");
            return Optional.empty();
        }
        Set<String> authored = TestProfile.authoredNames();
        Set<Admit> published = new HashSet<>();
        boolean wellFormed = true;
        for (JsonNode pair : admits) {
            Optional<String> edgeType = text(pair, "edgeType");
            Optional<Direction> direction = text(pair, "direction").flatMap(ProfileCheck::direction);
            if (edgeType.isEmpty() || direction.isEmpty()) {
                problems.add("closure " + closure + " has an admitted pair without an edgeType and a direction"
                    + " of forward or reverse: " + pair);
                wellFormed = false;
            } else if (!authored.contains(edgeType.get())) {
                problems.add("closure " + closure + " admits " + edgeType.get()
                    + ", which is not an authored name; an admitted pair names the authored one (W4, W23)");
                wellFormed = false;
            } else {
                published.add(new Admit(edgeType.get(), direction.get()));
            }
        }
        return wellFormed ? Optional.of(published) : Optional.empty();
    }

    private static Optional<Direction> direction(String given) {
        return switch (given) {
            case "forward" -> Optional.of(Direction.FORWARD);
            case "reverse" -> Optional.of(Direction.REVERSE);
            default -> Optional.empty();
        };
    }

    private static String describe(Set<Admit> admits) {
        return admits.stream()
            .map(a -> "(" + a.edgeType() + ", " + a.direction().name().toLowerCase(Locale.ROOT) + ")")
            .collect(Collectors.toCollection(TreeSet::new))
            .toString();
    }

    // ---- edge types ----

    private void edgeTypes(JsonNode attributes) {
        Map<String, JsonNode> published = entries(attributes, "edgeTypes", "name");
        compareNames("edge type", published.keySet(),
            TestProfile.EDGE_TYPES.stream().map(EdgeTypeDecl::name).collect(Collectors.toSet()));
        for (EdgeTypeDecl declared : TestProfile.EDGE_TYPES) {
            JsonNode entry = published.get(declared.name());
            if (entry == null) {
                continue;
            }
            Optional<String> inverse = text(entry, "inverse");
            if (!inverse.equals(declared.inverse())) {
                problems.add("edge type " + declared.name() + " declares inverse " + inverse.orElse("(none)")
                    + ", but the profile's is " + declared.inverse().orElse("(none)"));
            }
            Optional<String> authored = text(entry, "authored");
            if (!authored.equals(Optional.of(declared.authored()))) {
                problems.add("edge type " + declared.name() + " is authored as " + authored.orElse("(nothing)")
                    + ", but the profile authors it as " + declared.authored() + " (W23)");
            }
        }
    }

    // ---- cardinality rules ----

    private void cardinalityRules(JsonNode attributes) {
        Map<String, JsonNode> published = entries(attributes, "cardinalityRules", "id");
        compareNames("cardinality rule", published.keySet(),
            TestProfile.CARDINALITY_RULES.stream().map(CardinalityRule::id).collect(Collectors.toSet()));
        for (CardinalityRule declared : TestProfile.CARDINALITY_RULES) {
            JsonNode entry = published.get(declared.id());
            if (entry != null) {
                rule(declared.id(), entry).ifPresent(rule -> {
                    if (!rule.equals(declared)) {
                        problems.add("cardinality rule " + declared.id() + " is defined as " + describe(rule)
                            + ", but the profile defines it as " + describe(declared));
                    }
                });
            }
        }
    }

    private Optional<CardinalityRule> rule(String id, JsonNode entry) {
        boolean objectEnd = entry.has("objectTypes");
        if (objectEnd == entry.has("subjectTypes")) {
            problems.add("cardinality rule " + id + " must list objectTypes or subjectTypes, and not both (W4)");
            return Optional.empty();
        }
        End end = objectEnd ? End.OBJECT : End.SUBJECT;
        String suffix = objectEnd ? "Object" : "Subject";
        String other = objectEnd ? "Subject" : "Object";
        if (entry.has("minEdgesPer" + other) || entry.has("maxEdgesPer" + other)) {
            problems.add("cardinality rule " + id + " lists " + suffix.toLowerCase(Locale.ROOT)
                + "Types but bounds the " + other.toLowerCase(Locale.ROOT) + " end (W4)");
            return Optional.empty();
        }
        Optional<String> edgeType = text(entry, "edgeType");
        JsonNode types = entry.get(suffix.toLowerCase(Locale.ROOT) + "Types");
        if (edgeType.isEmpty() || !types.isArray()) {
            problems.add("cardinality rule " + id + " needs an edgeType and an array of types: " + entry);
            return Optional.empty();
        }
        for (String key : List.of("minEdgesPer" + suffix, "maxEdgesPer" + suffix)) {
            JsonNode value = entry.get(key);
            if (value != null && !value.isNull() && !value.isInt()) {
                problems.add("cardinality rule " + id + " has a " + key + " that is not an integer: " + value);
                return Optional.empty();
            }
        }
        Set<String> typeSet = new HashSet<>();
        types.forEach(type -> typeSet.add(type.asText()));
        try {
            return Optional.of(new CardinalityRule(id, edgeType.get(), end, typeSet,
                bound(entry, "minEdgesPer" + suffix), bound(entry, "maxEdgesPer" + suffix)));
        } catch (IllegalArgumentException e) {
            problems.add("cardinality rule " + id + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Integer bound(JsonNode entry, String key) {
        JsonNode value = entry.get(key);
        return value == null || value.isNull() ? null : value.intValue();
    }

    private static String describe(CardinalityRule rule) {
        return rule.edgeType() + " at the " + rule.end().name().toLowerCase(Locale.ROOT) + " end of "
            + new TreeSet<>(rule.types()) + ", min " + bound(rule.min()) + ", max " + bound(rule.max());
    }

    private static String bound(Integer value) {
        return value == null ? "none" : value.toString();
    }

    // ---- shared ----

    /** The array {@code key}'s objects, by their {@code idKey}, reporting any it cannot index. */
    private Map<String, JsonNode> entries(JsonNode attributes, String key, String idKey) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        JsonNode array = attributes.get(key);
        if (array == null || !array.isArray()) {
            problems.add("the document has no " + key + " array (W4)");
            return byId;
        }
        for (JsonNode entry : array) {
            Optional<String> id = text(entry, idKey);
            if (id.isEmpty()) {
                problems.add("an entry in " + key + " has no " + idKey + ": " + entry);
            } else if (byId.putIfAbsent(id.get(), entry) != null) {
                problems.add(key + " lists " + id.get() + " more than once");
            }
        }
        return byId;
    }

    private void compareNames(String what, Set<String> published, Set<String> expected) {
        new TreeSet<>(expected).stream().filter(name -> !published.contains(name))
            .forEach(name -> problems.add(what + " " + name + " is missing"));
        new TreeSet<>(published).stream().filter(name -> !expected.contains(name))
            .forEach(name -> problems.add(what + " " + name + " is not in the profile"));
    }

    private static Optional<String> text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? Optional.of(value.textValue()) : Optional.empty();
    }
}
