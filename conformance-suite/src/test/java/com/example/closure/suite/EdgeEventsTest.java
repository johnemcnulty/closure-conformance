package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The events the suite publishes, against the contract's own examples in §6.5, written out here. */
class EdgeEventsTest {

    static final Edge CARD_HOSTS_PORT = Edge.of(Ref.of("card", "X"), "hosts", Ref.of("port", "P1"));

    static JsonNode json(String text) throws IOException {
        return EdgeEvents.JSON.readTree(text);
    }

    @Test
    void aSnapshotIsTheContractsExample() throws IOException { // W15–W22
        JsonNode expected = json("""
            { "data": {
                "type": "snapshot", "id": "01JBQK4Z7N8XVT2R",
                "attributes": { "snapshot": {
                  "type": "edge", "id": "(card:X>hosts>port:P1)",
                  "attributes": { "edgeType": "hosts", "scope": ["ne-inventory:NE-7"] },
                  "relationships": {
                    "subject": { "data": { "type": "card", "id": "X" } },
                    "object":  { "data": { "type": "port", "id": "P1" } } } } },
                "relationships": { "resource": { "data": { "type": "edge", "id": "(card:X>hosts>port:P1)" } } } } }
            """);
        assertEquals(expected, EdgeEvents.snapshot(CARD_HOSTS_PORT, "01JBQK4Z7N8XVT2R", List.of("ne-inventory:NE-7")));
    }

    @Test
    void aDeleteIsTheContractsExample() throws IOException { // W20
        JsonNode expected = json("""
            { "data": {
                "type": "delete", "id": "01JBQK51M3YHWP7D",
                "attributes": { "delete": {
                  "type": "edge", "id": "(card:X>hosts>port:P1)",
                  "attributes": { "edgeType": "hosts" },
                  "relationships": {
                    "subject": { "data": { "type": "card", "id": "X" } },
                    "object":  { "data": { "type": "port", "id": "P1" } } } } },
                "relationships": { "resource": { "data": { "type": "edge", "id": "(card:X>hosts>port:P1)" } } } } }
            """);
        assertEquals(expected, EdgeEvents.delete(CARD_HOSTS_PORT, "01JBQK51M3YHWP7D"));
    }

    @Test
    void theKeyIsTheEdgeReference() { // W24
        assertEquals("edge:(card:X>hosts>port:P1)", EdgeEvents.key(CARD_HOSTS_PORT));
    }

    @Test
    void aSnapshotWithNoScopeIsRefused() { // W22
        assertThrows(IllegalArgumentException.class, () -> EdgeEvents.snapshot(CARD_HOSTS_PORT, "e", List.of()));
    }
}
