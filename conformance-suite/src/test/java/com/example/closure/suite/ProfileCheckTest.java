package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fixture 1's comparison, against the contract's own A.6 document with one clause broken per case.
 * Each broken case must be flagged with a reason naming what broke; the unbroken document, and
 * variations the contract permits, must pass, so no case can pass by the check flagging everything.
 */
class ProfileCheckTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final URI DOCUMENT_URL = URI.create("https://gw.example/conformance/discovery");

    ObjectNode document;

    /** The JSON block under the contract's heading A.6, read from the contract itself. */
    static ObjectNode contractA6() throws IOException {
        String spec = Files.readString(Path.of("..", "docs", "contract-spec.md"));
        Matcher block = Pattern.compile("### A\\.6 .*?```json\\n(.*?)```", Pattern.DOTALL).matcher(spec);
        assertTrue(block.find(), "contract-spec.md has no JSON block under A.6");
        return (ObjectNode) JSON.readTree(block.group(1));
    }

    @BeforeEach
    void loadA6() throws IOException {
        document = contractA6();
    }

    ObjectNode attributes() {
        return (ObjectNode) document.path("data").path("attributes");
    }

    ArrayNode list(String key) {
        return (ArrayNode) attributes().get(key);
    }

    ObjectNode entry(String key, String idKey, String id) {
        for (JsonNode entry : list(key)) {
            if (entry.path(idKey).asText().equals(id)) {
                return (ObjectNode) entry;
            }
        }
        throw new AssertionError("A.6 has no " + id + " in " + key);
    }

    ObjectNode closure(String name) {
        return entry("closures", "name", name);
    }

    ObjectNode edgeType(String name) {
        return entry("edgeTypes", "name", name);
    }

    ObjectNode rule(String id) {
        return entry("cardinalityRules", "id", id);
    }

    void remove(String key, String idKey, String id) {
        ArrayNode array = list(key);
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).path(idKey).asText().equals(id)) {
                array.remove(i);
                return;
            }
        }
        throw new AssertionError("A.6 has no " + id + " in " + key);
    }

    static ArrayNode admits(String edgeType, String direction) {
        ArrayNode admits = JSON.createArrayNode();
        admits.addObject().put("edgeType", edgeType).put("direction", direction);
        return admits;
    }

    List<String> problems() {
        return ProfileCheck.discrepancies(document, DOCUMENT_URL);
    }

    /** Some reported problem mentions every fragment. */
    void assertFlagged(String... fragments) {
        List<String> problems = problems();
        assertTrue(problems.stream().anyMatch(p -> Arrays.stream(fragments).allMatch(p::contains)),
            () -> "expected a problem mentioning " + Arrays.toString(fragments) + ", got " + problems);
    }

    // ---- what must pass ----

    @Test
    void theContractsA6DocumentMatches() { // W4, Appendix A.6
        assertEquals(List.of(), problems());
    }

    @Test
    void theOrderOfListsDoesNotMatter() { // W4
        for (String key : List.of("closures", "edgeTypes", "cardinalityRules")) {
            List<JsonNode> reversed = new ArrayList<>();
            list(key).forEach(reversed::addFirst);
            list(key).removeAll().addAll(reversed);
        }
        ArrayNode admits = (ArrayNode) closure("dependents").get("admits");
        admits.insert(0, admits.remove(1));
        ArrayNode types = (ArrayNode) rule("one-holder").get("objectTypes");
        types.insert(0, types.remove(1));
        assertEquals(List.of(), problems());
    }

    @Test
    void aRelativeOfLinkResolvesAgainstTheDocument() { // W4
        ((ObjectNode) closure("contents").get("links")).put("of", "contents/of");
        assertEquals(List.of(), problems());
    }

    // ---- closures ----

    @Test
    void aClosureNamingNoInverseIsFlagged() { // W4
        closure("contents").remove("inverse");
        assertFlagged("closure contents", "no inverse");
    }

    @Test
    void aClosureNamingTheWrongInverseIsFlagged() { // W4
        closure("containers").put("inverse", "suppliers");
        assertFlagged("closure containers", "suppliers", "contents");
    }

    @Test
    void aClosureWithoutAnOfLinkIsFlagged() { // W4
        closure("consumers").remove("links");
        assertFlagged("closure consumers", "no of link");
    }

    @Test
    void aClosureWithoutACyclesLinkIsFlagged() { // W4, W36
        ((ObjectNode) closure("suppliers").get("links")).remove("cycles");
        assertFlagged("closure suppliers", "no cycles link");
    }

    @Test
    void aCyclesLinkThatIsNotHttpIsFlagged() { // W4, W36
        ((ObjectNode) closure("contents").get("links")).put("cycles", "mailto:cycles@gw.example");
        assertFlagged("closure contents", "cycles link", "does not resolve to an http URL");
    }

    @Test
    void anOfLinkThatIsNotAUriIsFlagged() { // W4
        ((ObjectNode) closure("dependents").get("links")).put("of", "http://[not a host]/of");
        assertFlagged("closure dependents", "not a URI");
    }

    @Test
    void anOfLinkThatIsNotHttpIsFlagged() { // W4
        ((ObjectNode) closure("dependents").get("links")).put("of", "mailto:closures@gw.example");
        assertFlagged("closure dependents", "does not resolve to an http URL");
    }

    @Test
    void admittedPairsStatedOnTheDerivedSideAreFlagged() { // W4
        closure("containers").remove("admits");
        closure("contents").set("admits", admits("holds", "forward"));
        assertFlagged("closure containers", "states no admitted pairs");
        assertFlagged("closure contents", "states admitted pairs", "containers");
    }

    @Test
    void admittedPairsStatedOnBothSidesAreFlagged() { // W4
        closure("contents").set("admits", admits("holds", "forward"));
        assertFlagged("closure contents", "states admitted pairs");
    }

    @Test
    void aPairUnderTheUnauthoredNameIsFlagged() { // W4, W23
        // (feeds, reverse) is the same traversal as (fed_by, forward), but only the authored
        // spelling may be published, so it is flagged rather than normalized
        closure("suppliers").set("admits", admits("feeds", "reverse"));
        assertFlagged("closure suppliers", "feeds", "not an authored name");
    }

    @Test
    void aPairInTheWrongDirectionIsFlagged() { // W4
        closure("containers").set("admits", admits("holds", "forward"));
        assertFlagged("closure containers", "(holds, forward)", "(holds, reverse)");
    }

    @Test
    void aMissingClosureIsFlagged() { // W4
        remove("closures", "name", "consumers");
        assertFlagged("closure consumers", "missing");
    }

    @Test
    void anExtraClosureIsFlagged() { // W4
        list("closures").addObject().put("name", "extras").put("inverse", "contents");
        assertFlagged("closure extras", "not in the profile");
    }

    @Test
    void aClosureListedTwiceIsFlagged() { // W4
        list("closures").add(closure("contents").deepCopy());
        assertFlagged("contents", "more than once");
    }

    // ---- edge types ----

    @Test
    void anEdgeTypeWithTheWrongAuthoredNameIsFlagged() { // W23
        edgeType("feeds").put("authored", "feeds");
        assertFlagged("edge type feeds", "authored as feeds", "fed_by");
    }

    @Test
    void anInverseOnATypeDeclaringNoneIsFlagged() { // W4
        edgeType("notes").put("inverse", "noted_by");
        assertFlagged("edge type notes", "noted_by", "(none)");
    }

    @Test
    void aMissingEdgeTypeIsFlagged() { // W4
        remove("edgeTypes", "name", "tracks");
        assertFlagged("edge type tracks", "missing");
    }

    // ---- cardinality rules ----

    @Test
    void aMissingRuleIsFlagged() { // W4, L25
        remove("cardinalityRules", "id", "tracks-fanout");
        assertFlagged("cardinality rule tracks-fanout", "missing");
    }

    @Test
    void aRuleWithAnAlteredBoundIsFlagged() { // W4
        rule("one-holder").put("maxEdgesPerObject", 2);
        assertFlagged("cardinality rule one-holder", "max 2", "max 1");
    }

    @Test
    void aRuleCountedAtTheWrongEndIsFlagged() { // W4
        ObjectNode rule = rule("one-holder");
        rule.set("subjectTypes", rule.remove("objectTypes"));
        rule.set("minEdgesPerSubject", rule.remove("minEdgesPerObject"));
        rule.set("maxEdgesPerSubject", rule.remove("maxEdgesPerObject"));
        assertFlagged("cardinality rule one-holder", "subject end", "object end");
    }

    @Test
    void aRuleBoundingTheEndItDoesNotListIsFlagged() { // W4
        rule("tracks-fanout").put("maxEdgesPerObject", 2);
        assertFlagged("cardinality rule tracks-fanout", "bounds the object end");
    }

    // ---- the topic ----

    @Test
    void aDocumentNamingNoIngestTopicIsFlagged() { // W4
        attributes().remove("ingestTopic");
        assertFlagged("no ingest topic");
    }
}
