package com.example.closure.suite;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.BETA;
import static com.example.closure.model.TestProfile.GAMMA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.NOTES;
import static com.example.closure.model.TestProfile.OMEGA;
import static com.example.closure.model.TestProfile.TRACKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Ref;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

    final GraphBuilder graph = new GraphBuilder(new RunId("run/"));

    @Test
    void everyIdCarriesTheRunPrefix() { // suite-design §6
        assertEquals("run/B1", graph.ref(BETA, "B1").id());
    }

    @Test
    void aBetaWithOneHolderBuilds() { // A.4
        graph.edge(graph.ref(ALPHA, "A"), HOLDS, graph.ref(BETA, "B"));
        assertEquals(1, graph.build().edges().size());
    }

    @Test
    void aBetaWithNoHolderIsRefused() { // A.4
        graph.edge(graph.ref(BETA, "B"), NOTES, graph.ref(OMEGA, "O"));
        IllegalStateException refused = assertThrows(IllegalStateException.class, graph::build);
        assertTrue(refused.getMessage().contains("one-holder"));
    }

    @Test
    void aGammaWithTwoHoldersIsRefused() { // A.4
        Ref gamma = graph.ref(GAMMA, "G");
        graph.edge(graph.ref(ALPHA, "A1"), HOLDS, gamma).edge(graph.ref(ALPHA, "A2"), HOLDS, gamma);
        assertThrows(IllegalStateException.class, graph::build);
    }

    @Test
    void aBetaTrackingThreeThingsIsRefused() { // A.4
        Ref beta = graph.ref(BETA, "B");
        graph.edge(graph.ref(ALPHA, "A"), HOLDS, beta);
        for (String o : new String[] {"O1", "O2", "O3"}) {
            graph.edge(beta, TRACKS, graph.ref(OMEGA, o));
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class, graph::build);
        assertTrue(refused.getMessage().contains("tracks-fanout"));
    }

    @Test
    void omegaMayHaveAnyNumberOfEdges() { // A.1
        Ref omega = graph.ref(OMEGA, "O");
        graph.edge(graph.ref(ALPHA, "A1"), HOLDS, omega).edge(graph.ref(ALPHA, "A2"), HOLDS, omega);
        assertEquals(2, graph.build().edges().size());
    }

    @Test
    void aDeliberateBreachBuildsOnlyWhenAsked() { // A.4
        graph.edge(graph.ref(BETA, "B"), NOTES, graph.ref(OMEGA, "O"));
        assertEquals(1, graph.buildBreaching().edges().size());
    }

    @Test
    void askingForABreachWithoutOneIsRefused() { // A.4
        graph.edge(graph.ref(ALPHA, "A"), HOLDS, graph.ref(OMEGA, "O"));
        assertThrows(IllegalStateException.class, graph::buildBreaching);
    }

    @Test
    void anUnauthoredEdgeTypeIsRefused() { // W23
        assertThrows(IllegalArgumentException.class,
            () -> graph.edge(graph.ref(OMEGA, "O1"), "feeds", graph.ref(OMEGA, "O2")));
    }

    @Test
    void aTypeOutsideTheProfileIsRefused() { // A.1
        assertThrows(IllegalArgumentException.class, () -> graph.ref("card", "C"));
    }
}
