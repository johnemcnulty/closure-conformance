package com.example.closure.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Admit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

    static ObjectNode a6() {
        return (ObjectNode) DiscoveryDocument.template();
    }

    static ObjectNode closure(ObjectNode document, String name) {
        for (var closure : document.path("data").path("attributes").path("closures")) {
            if (closure.path("name").asText().equals(name)) {
                return (ObjectNode) closure;
            }
        }
        throw new AssertionError(name);
    }

    @Test
    void theDerivedSideGetsItsCarriersPairsFlipped() { // W4
        Configuration configuration = Configuration.from(a6());
        assertEquals(Set.of(Admit.reverse("holds"), Admit.reverse("tracks")),
            configuration.closures().get("dependencies").admits());
    }

    @Test
    void aPairConfiguredUnderTheUnauthoredNameIsNormalized() throws Exception { // W4
        ObjectNode document = a6();
        closure(document, "suppliers").set("admits",
            new ObjectMapper().readTree("[{ \"edgeType\": \"feeds\", \"direction\": \"reverse\" }]"));
        assertEquals(Set.of(Admit.forward("fed_by")), Configuration.from(document).closures().get("suppliers").admits());
    }

    @Test
    void bothDirectionsOfOneTypeRefuseToStart() throws Exception { // L2
        ObjectNode document = a6();
        // (fed_by, forward) and (feeds, forward) read like two types but are both directions of one
        closure(document, "suppliers").set("admits", new ObjectMapper().readTree(
            "[{ \"edgeType\": \"fed_by\", \"direction\": \"forward\" }, { \"edgeType\": \"feeds\", \"direction\": \"forward\" }]"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Configuration.from(document));
        assertTrue(refused.getMessage().contains("both directions"));
    }
}
