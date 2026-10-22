package com.example.closure.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reference instance's materialized reach sets, on small graphs whose answers are written out
 * by hand. These check the reference implementation alone; the suite checks it again, end to end.
 */
class GraphTest {

    static final Ref A = Ref.of("alpha", "A");
    static final Ref B = Ref.of("beta", "B");
    static final Ref G = Ref.of("gamma", "G");
    static final Ref O = Ref.of("omega", "O");
    static final Ref UNKNOWN = Ref.of("omega", "nowhere");

    Graph graph;

    @BeforeEach
    void configureFromA6() {
        graph = new Graph(Configuration.from(DiscoveryDocument.template()));
    }

    Set<Ref> of(String closure, Ref... refs) {
        return graph.of(closure, List.of(refs));
    }

    @Test
    void aChainIsReachedTransitively() { // L19, L23
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(B, "holds", G));
        assertEquals(Set.of(A, B, G), of("contents", A));
        assertEquals(Set.of(G, B, A), of("containers", G));
    }

    @Test
    void aReferenceIsInItsOwnResultAndAnUnknownOneHasNone() { // L15, L16
        graph.assertEdge(Edge.of(A, "notes", O));
        assertEquals(Set.of(A), of("contents", A));
        assertEquals(Set.of(), of("contents", UNKNOWN));
    }

    @Test
    void anEdgeAdmittedInTheOtherDirectionIsNotFollowed() { // L23
        graph.assertEdge(Edge.of(A, "holds", B));
        assertEquals(Set.of(B), of("contents", B));
    }

    @Test
    void aClosureAdmittingTwoTypesCrossesBoth() { // L23
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(B, "tracks", O));
        assertEquals(Set.of(A, B, O), of("dependents", A));
        assertEquals(Set.of(A, B), of("contents", A));
    }

    @Test
    void deletingAnEdgeRemovesWhatOnlyItReached() { // W20
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(B, "holds", G));
        graph.retractEdge(Edge.of(B, "holds", G));
        assertEquals(Set.of(A, B), of("contents", A));
        assertEquals(Set.of(), of("contents", G));
    }

    @Test
    void aStepGivenByTwoEdgesSurvivesLosingOne() { // L23
        graph.assertEdge(Edge.of(B, "holds", O));
        graph.assertEdge(Edge.of(B, "tracks", O));
        graph.retractEdge(Edge.of(B, "holds", O));
        assertEquals(Set.of(B, O), of("dependents", B));
    }

    @Test
    void repeatingAnEventChangesNothing() { // W24, W31
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.retractEdge(Edge.of(A, "holds", B));
        graph.retractEdge(Edge.of(A, "holds", B));
        assertEquals(Set.of(), of("contents", A));
    }

    @Test
    void aCycleGivesEveryMemberTheSameReach() { // L19, L26
        graph.assertEdge(Edge.of(G, "fed_by", O));
        graph.assertEdge(Edge.of(O, "fed_by", G));
        assertEquals(Set.of(G, O), of("suppliers", G));
        assertEquals(Set.of(G, O), of("suppliers", O));
    }

    @Test
    void aResultOverACycleNamesTheCycle() { // L25, L28
        graph.assertEdge(Edge.of(G, "fed_by", O));
        graph.assertEdge(Edge.of(O, "fed_by", G));
        assertEquals(List.of(Set.of(G, O)), graph.answer("suppliers", List.of(G)).cycles().stream().map(Graph.CycleGroup::members).toList());
    }

    @Test
    void aResultReachingACycleFromOutsideNamesOnlyTheCycle() { // L27, L28
        graph.assertEdge(Edge.of(A, "fed_by", G));
        graph.assertEdge(Edge.of(G, "fed_by", O));
        graph.assertEdge(Edge.of(O, "fed_by", G));
        assertEquals(List.of(Set.of(G, O)), graph.answer("suppliers", List.of(A)).cycles().stream().map(Graph.CycleGroup::members).toList());
    }

    @Test
    void aCycleGroupCarriesTheAdmittedEdgesAmongItsMembersAndNoOthers() { // W36
        Edge in = Edge.of(A, "fed_by", G);
        Edge there = Edge.of(G, "fed_by", O);
        Edge back = Edge.of(O, "fed_by", G);
        Edge unadmitted = Edge.of(G, "notes", O);
        List.of(in, there, back, unadmitted).forEach(graph::assertEdge);
        assertEquals(List.of(new Graph.CycleGroup(Set.of(G, O), Set.of(there, back))),
            graph.answer("suppliers", List.of(A)).cycles());
    }

    @Test
    void aCycleOfATypeTheClosureDoesNotAdmitIsNotReported() { // L27
        graph.assertEdge(Edge.of(G, "fed_by", O));
        graph.assertEdge(Edge.of(O, "fed_by", G));
        assertEquals(List.of(), graph.answer("contents", List.of(G)).cycles().stream().map(Graph.CycleGroup::members).toList());
    }

    @Test
    void anAcyclicResultHasNoCycle() { // L27
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(B, "holds", G));
        assertEquals(List.of(), graph.answer("contents", List.of(A)).cycles().stream().map(Graph.CycleGroup::members).toList());
    }

    @Test
    void twoHoldersBreachTheMaximum() { // L25, L28
        Ref a2 = Ref.of("alpha", "A2");
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(a2, "holds", B));
        assertEquals(Map.of("one-holder", List.of(B)), graph.answer("containers", List.of(B)).breaches());
    }

    @Test
    void noHolderBreachesTheMinimumOnlyWhereHoldsIsAdmitted() { // L27
        graph.assertEdge(Edge.of(B, "notes", O));
        assertEquals(Map.of("one-holder", List.of(B)), graph.answer("contents", List.of(B)).breaches());
        assertEquals(Map.of(), graph.answer("suppliers", List.of(B)).breaches());
    }

    @Test
    void anUnlistedTypeWithTwoHoldersIsNotABreach() { // L27
        graph.assertEdge(Edge.of(A, "holds", O));
        graph.assertEdge(Edge.of(Ref.of("alpha", "A2"), "holds", O));
        assertEquals(Map.of(), graph.answer("containers", List.of(O)).breaches());
    }

    @Test
    void trackingThreeThingsBreachesTheSubjectEnd() { // L25
        graph.assertEdge(Edge.of(A, "holds", B));
        for (String id : List.of("T1", "T2", "T3")) {
            graph.assertEdge(Edge.of(B, "tracks", Ref.of("omega", id)));
        }
        assertEquals(Map.of("tracks-fanout", List.of(B)), graph.answer("dependents", List.of(B)).breaches());
    }

    @Test
    void aBreachEndsWhenItsEdgeIsDeleted() { // L25
        Ref a2 = Ref.of("alpha", "A2");
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(a2, "holds", B));
        graph.retractEdge(Edge.of(a2, "holds", B));
        assertEquals(Map.of(), graph.answer("containers", List.of(B)).breaches());
    }

    @Test
    void aSetIsTheUnionOfItsMembers() { // L21
        graph.assertEdge(Edge.of(A, "holds", B));
        graph.assertEdge(Edge.of(G, "holds", O));
        assertEquals(Set.of(A, B, G, O), of("contents", A, G, UNKNOWN));
    }
}
