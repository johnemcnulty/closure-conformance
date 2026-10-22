package com.example.closure.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reading ingest records, from JSON written out here by hand in the shape of §6.5's examples. */
class EventReaderTest {

    static final String KEY = "edge:(alpha:A>holds>beta:B)";

    final EventReader reader = new EventReader(Configuration.from(DiscoveryDocument.template()));

    static String event(String kind, String innerId, String edgeType, String resourceId) {
        return """
            { "data": {
                "type": "%s", "id": "event-1",
                "attributes": { "%s": {
                  "type": "edge", "id": "%s",
                  "attributes": { "edgeType": "%s", "scope": ["s"] },
                  "relationships": {
                    "subject": { "data": { "type": "alpha", "id": "A" } },
                    "object":  { "data": { "type": "beta", "id": "B" } } } } },
                "relationships": { "resource": { "data": { "type": "edge", "id": "%s" } } } } }
            """.formatted(kind, kind, innerId, edgeType, resourceId);
    }

    static String snapshot() {
        return event("snapshot", "(alpha:A>holds>beta:B)", "holds", "(alpha:A>holds>beta:B)");
    }

    EventReader.Read read(String key, String value) {
        return reader.read(key, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    void assertRejected(String key, String value, String because) {
        EventReader.Reject reject = assertInstanceOf(EventReader.Reject.class, read(key, value));
        assertTrue(reject.reason().contains(because), reject::reason);
    }

    @Test
    void aSnapshotAssertsItsEdge() { // W20
        assertEquals(new EventReader.Apply(Edge.of(Ref.of("alpha", "A"), "holds", Ref.of("beta", "B")), true),
            read(KEY, snapshot()));
    }

    @Test
    void aDeleteRetractsItsEdge() { // W20
        EventReader.Apply apply = assertInstanceOf(EventReader.Apply.class,
            read(KEY, event("delete", "(alpha:A>holds>beta:B)", "holds", "(alpha:A>holds>beta:B)")));
        assertEquals(false, apply.exists());
    }

    @Test
    void aTombstoneIsNotMalformed() { // W32
        assertInstanceOf(EventReader.Tombstone.class, read(KEY, null));
    }

    @Test
    void anUnauthoredNameIsRejected() { // W23
        assertRejected("edge:(alpha:A>feeds>beta:B)",
            event("snapshot", "(alpha:A>feeds>beta:B)", "feeds", "(alpha:A>feeds>beta:B)"), "authored");
    }

    @Test
    void anUndeclaredTypeIsRejected() { // W23
        assertRejected("edge:(alpha:A>owns>beta:B)",
            event("snapshot", "(alpha:A>owns>beta:B)", "owns", "(alpha:A>owns>beta:B)"), "authored");
    }

    @Test
    void anInnerIdDisagreeingWithTheTripleIsRejected() { // W25
        assertRejected(KEY, event("snapshot", "(alpha:A>holds>beta:C)", "holds", "(alpha:A>holds>beta:B)"), "inner id");
    }

    @Test
    void aKeyDisagreeingWithTheTripleIsRejected() { // W25
        assertRejected("edge:(alpha:A>holds>beta:C)", snapshot(), "record key");
    }

    @Test
    void aResourceDisagreeingWithTheInnerObjectIsRejected() { // W25
        assertRejected(KEY, event("snapshot", "(alpha:A>holds>beta:B)", "holds", "(alpha:A>holds>beta:C)"), "resource");
    }

    @Test
    void anotherEventKindIsRejected() { // W26
        assertRejected(KEY, snapshot().replace("\"snapshot\"", "\"upsert\""), "snapshot and delete only");
    }

    @Test
    void somethingThatIsNotJsonIsRejected() { // W27
        assertRejected(KEY, "not json {", "not JSON");
    }
}
