package com.example.closure.suite.fixtures;

import static com.example.closure.suite.Rejections.ONLY_ARGUMENT;
import static com.example.closure.suite.Rejections.assertMalformed;
import static com.example.closure.suite.Rejections.ask;

import com.example.closure.model.TestProfile;
import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Fixture 6 (§7): a reference is a {@code (type, id)} pair, so an argument missing either, or with
 * either empty, is rejected (L7, W11), and references differing only in case or whitespace are
 * distinct (L8). Every request goes as {@code application/vnd.api+json} and every response is
 * checked for its status (W6, W8, W11).
 */
@Fixture
class IdentityFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final ObjectMapper JSON = new ObjectMapper();

    final Instance instance = Instance.get();

    String id(String local) {
        return instance.runId().value() + local;
    }

    void assertRejected(JsonNode argument) {
        assertMalformed(ask(instance, CONTENTS, argument), argument, ONLY_ARGUMENT);
    }

    @TestFactory
    Stream<DynamicTest> referencesDifferingOnlyInCaseOrWhitespaceAreDistinct() { // L8
        return ExampleRun.rows(Identity.example(instance.runId()));
    }

    @Test
    void anIdWithNoTypeIsRejected() { // L7, W11
        assertRejected(JSON.createObjectNode().put("id", id("no-type")));
    }

    @Test
    void aBareIdIsRejected() { // L7, W11
        assertRejected(TextNode.valueOf(id("bare")));
    }

    @Test
    void anEmptyTypeIsRejected() { // L7, W11
        assertRejected(JSON.createObjectNode().put("type", "").put("id", id("empty-type")));
    }

    @Test
    void anEmptyIdIsRejected() { // L7, W11
        assertRejected(JSON.createObjectNode().put("type", TestProfile.OMEGA).put("id", ""));
    }

    @Test
    void aTypeWithNoIdIsRejected() { // L7, W11
        assertRejected(JSON.createObjectNode().put("type", TestProfile.OMEGA));
    }
}
