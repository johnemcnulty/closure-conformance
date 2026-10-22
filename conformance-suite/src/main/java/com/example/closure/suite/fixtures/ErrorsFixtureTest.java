package com.example.closure.suite.fixtures;

import static com.example.closure.suite.Rejections.ONLY_ARGUMENT;
import static com.example.closure.suite.Rejections.assertAnswered;
import static com.example.closure.suite.Rejections.assertMalformed;
import static com.example.closure.suite.Rejections.ask;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.Instance;
import com.example.closure.suite.OfClient;
import com.example.closure.suite.OfResponse;
import com.example.closure.suite.Qualifications;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixture 9 (§7): a malformed argument produces a well-formed {@code errors} document carrying
 * {@code reference_malformed}, a {@code source.pointer} to the argument and the argument as received
 * in {@code meta.argument} (W11), an unknown reference produces no error (L14, W12), and a
 * qualified result is a success rather than an error (L26, W12). {@code not_ready} is excluded with
 * W35.
 */
@Fixture
class ErrorsFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final ObjectMapper JSON = new ObjectMapper();

    final Instance instance = Instance.get();

    ObjectNode reference(String type, String id) {
        return JSON.createObjectNode().put("type", type).put("id", id);
    }

    @Test
    void aMalformedArgumentGetsAnErrorsDocumentCarryingIt() { // W11
        JsonNode argument = JSON.createObjectNode().put("id", instance.runId().value() + "PT/1,2");
        assertMalformed(ask(instance, CONTENTS, argument), argument, ONLY_ARGUMENT);
    }

    @Test
    void theArgumentIsCarriedWholeAsAJsonValue() { // W11
        ObjectNode argument = JSON.createObjectNode().put("id", instance.runId().value() + "extra");
        argument.putArray("unrelated").add(1).add(true).addNull();
        assertMalformed(ask(instance, CONTENTS, argument), argument, ONLY_ARGUMENT);
    }

    @Test
    void aTypeContainingTheDelimiterIsMalformed() { // L9, W11
        JsonNode argument = reference(TestProfile.OMEGA + ":x", instance.runId().value() + "id");
        assertMalformed(ask(instance, CONTENTS, argument), argument, ONLY_ARGUMENT);
    }

    @Test
    void theErrorPointsAtTheMalformedArgumentAmongWellFormedOnes() { // W11
        ObjectNode body = JSON.createObjectNode();
        JsonNode malformed = JSON.createObjectNode().put("id", instance.runId().value() + "untyped");
        body.putArray("data")
            .add(reference(TestProfile.OMEGA, instance.runId().value() + "fine"))
            .add(malformed);
        assertMalformed(new OfClient().post(instance.discovery().ofLink(CONTENTS), body), malformed, "/data/1");
    }

    @Test
    void theCycleDiagnosticRejectsAMalformedArgumentTheSameWay() { // W11, W36
        ObjectNode body = JSON.createObjectNode();
        JsonNode malformed = JSON.createObjectNode().put("id", instance.runId().value() + "untyped");
        body.putArray("data").add(malformed);
        assertMalformed(new OfClient().post(instance.discovery().cyclesLink(CONTENTS), body), malformed, ONLY_ARGUMENT);
    }

    @Test
    void anIdContainingTheDelimiterIsWellFormed() { // L9
        OfResponse response = ask(instance, CONTENTS, reference(TestProfile.OMEGA, instance.runId().value() + "a:b"));
        assertAnswered(response);
        assertEquals(List.of(), response.referenceList());
    }

    @Test
    void aQualifiedResultIsASuccessAndNotAnError() { // L26, W12
        GraphBuilder graph = new GraphBuilder(instance.runId().child("errors-qualified"));
        Ref unheld = graph.ref(TestProfile.BETA, "B");
        Ref noted = graph.ref(TestProfile.OMEGA, "O");
        graph.edge(unheld, TestProfile.NOTES, noted).buildBreaching().publishTo(instance);

        OfResponse response = instance.of(CONTENTS, List.of(unheld));
        assertAnswered(response);
        assertEquals(Set.of(unheld), response.references(), "a qualified result is still the whole result (L26)");
        Qualifications.assertQualifications(response.meta(), List.of(),
            Map.of(TestProfile.ONE_HOLDER.id(), Set.of(unheld)));
    }

    @Test
    void anUnknownReferenceIsAnsweredEmptyAndNotAnError() { // L14, W12
        Ref unknown = instance.runId().ref(TestProfile.OMEGA, "never-published");
        OfResponse response = instance.of(CONTENTS, List.of(unknown));
        assertAnswered(response);
        assertEquals(List.of(), response.referenceList());
    }
}
