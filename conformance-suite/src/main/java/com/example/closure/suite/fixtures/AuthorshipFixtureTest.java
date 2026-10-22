package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.suite.IngestRejections.assertAccepted;
import static com.example.closure.suite.IngestRejections.assertRejected;
import static com.example.closure.suite.IngestRejections.ends;

import com.example.closure.model.Edge;
import com.example.closure.model.EdgeTypeDecl;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.EdgeEvents;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import com.example.closure.suite.RunId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Fixture 12 (§7): an event whose {@code edgeType} is the unauthored name of an edge type is
 * rejected (W23), for every type the profile gives two names, taking both from the profile. The
 * event is otherwise well-formed for the triple it states — its id and key computed over it — so the
 * name is the only fault. The same endpoints under the authored name are then accepted.
 */
@Fixture
class AuthorshipFixtureTest {

    final Instance instance = Instance.get();

    @TestFactory
    Stream<DynamicTest> anEventUnderTheUnauthoredNameIsRejected() { // W23, W27
        return TestProfile.EDGE_TYPES.stream()
            .filter(type -> type.unauthored().isPresent())
            .map(type -> DynamicTest.dynamicTest(type.unauthored().get() + " is rejected; " + type.authored() + " is not",
                () -> check(type)));
    }

    void check(EdgeTypeDecl type) {
        RunId run = instance.runId().child("authorship-" + type.name());
        Edge unauthored = Edge.of(run.ref(OMEGA, "S"), type.unauthored().get(), run.ref(OMEGA, "O"));
        assertRejected(instance, EdgeEvents.key(unauthored),
            EdgeEvents.snapshot(unauthored, UUID.randomUUID().toString(), List.of("fixture-12")), ends(unauthored));
        assertAccepted(instance, Edge.of(unauthored.subject(), type.authored(), unauthored.object()));
    }
}
