package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.model.TestProfile.TRACKS;
import static com.example.closure.suite.IngestRejections.assertAccepted;
import static com.example.closure.suite.IngestRejections.assertRejected;
import static com.example.closure.suite.IngestRejections.ends;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.closure.model.Edge;
import com.example.closure.model.EdgeId;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.EdgeEvents;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.example.closure.suite.RunId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fixture 13 (§7): an event whose identifiers disagree with its triple is rejected (W25), and edges
 * differing only in edge type, or only in an endpoint the id function escapes, are distinct, so
 * deleting one leaves the other (W21).
 */
@Fixture
class EdgeIdFixtureTest {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String DEPENDENTS = TestProfile.DEPENDENTS.name();

    final Instance instance = Instance.get();

    /** A triple on fresh references, and a second one the event can be made to name instead. */
    record Pair(Edge stated, Edge other) {
        static Pair on(RunId run) {
            return new Pair(Edge.of(run.ref(OMEGA, "S"), HOLDS, run.ref(OMEGA, "O")),
                Edge.of(run.ref(OMEGA, "S2"), HOLDS, run.ref(OMEGA, "O2")));
        }
    }

    Pair pair(String name) {
        return Pair.on(instance.runId().child("edge-id-" + name));
    }

    ObjectNode snapshot(Edge edge) {
        return EdgeEvents.snapshot(edge, UUID.randomUUID().toString(), List.of("fixture-13"));
    }

    static ObjectNode inner(ObjectNode event) {
        return (ObjectNode) event.path("data").path("attributes").path("snapshot");
    }

    @Test
    void anInnerIdThatIsNotTheIdOfItsTripleIsRejected() { // W21, W25
        Pair p = pair("inner");
        ObjectNode event = snapshot(p.stated());
        inner(event).put("id", EdgeId.of(p.other()));
        assertRejected(instance, EdgeEvents.key(p.stated()), event, ends(p.stated(), p.other()));
        assertAccepted(instance, p.stated());
    }

    @Test
    void aRecordKeyDisagreeingWithTheInnerIdIsRejected() { // W24, W25
        Pair p = pair("key");
        assertRejected(instance, EdgeEvents.key(p.other()), snapshot(p.stated()), ends(p.stated(), p.other()));
        assertAccepted(instance, p.stated());
    }

    @Test
    void anOuterResourceNamingAnotherEdgeIsRejected() { // W18, W25
        Pair p = pair("resource");
        ObjectNode event = snapshot(p.stated());
        ((ObjectNode) event.path("data").path("relationships").path("resource").path("data"))
            .put("id", EdgeId.of(p.other()));
        assertRejected(instance, EdgeEvents.key(p.stated()), event, ends(p.stated(), p.other()));
        assertAccepted(instance, p.stated());
    }

    @Test
    void edgesDifferingOnlyInEdgeTypeAreDistinct() { // W21
        RunId run = instance.runId().child("edge-id-types");
        Ref a = run.ref(ALPHA, "A");
        Ref o = run.ref(OMEGA, "O");
        Edge holds = Edge.of(a, HOLDS, o);
        Edge tracks = Edge.of(a, TRACKS, o);
        instance.publisher().snapshot(holds);
        instance.publisher().snapshot(tracks);
        instance.settledOf(CONTENTS, a, Set.of(a, o));

        instance.publisher().delete(holds);
        instance.settledOf(CONTENTS, a, Set.of(a));
        assertEquals(Set.of(a, o), instance.of(DEPENDENTS, List.of(a)).references(),
            "deleting the holds edge leaves the tracks edge over the same pair (W21)");
    }

    @Test
    void edgesDifferingOnlyInAnEscapedEndpointAreDistinct() { // W21
        // ">" escapes to "%3E", and "%3E" to "%253E": distinct only if "%" is escaped first
        RunId run = instance.runId().child("edge-id-escapes");
        Ref a = run.ref(ALPHA, "A");
        Ref separator = run.ref(OMEGA, ">");
        Ref escaped = run.ref(OMEGA, "%3E");
        instance.publisher().snapshot(Edge.of(a, HOLDS, separator));
        instance.publisher().snapshot(Edge.of(a, HOLDS, escaped));
        instance.settledOf(CONTENTS, a, Set.of(a, separator, escaped));

        instance.publisher().delete(Edge.of(a, HOLDS, separator));
        instance.settledOf(CONTENTS, a, Set.of(a, escaped));
    }
}
