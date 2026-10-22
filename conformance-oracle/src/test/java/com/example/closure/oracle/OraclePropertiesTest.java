package com.example.closure.oracle;

import com.example.closure.model.Admit;
import com.example.closure.model.Closure;
import com.example.closure.model.Direction;
import com.example.closure.model.Edge;
import com.example.closure.model.Ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Generated graphs, checked two ways: against a second, independent algorithm (a reachability
 * matrix filled in by Floyd-Warshall), and against the laws of §3. The small universe — six
 * references, two edge types — keeps cycles, diamonds and self-loops frequent.
 */
class OraclePropertiesTest {

    static final List<Ref> UNIVERSE = List.of(
        Ref.of("a", "0"), Ref.of("a", "1"), Ref.of("a", "2"),
        Ref.of("b", "0"), Ref.of("b", "1"), Ref.of("b", "2"));
    static final List<String> TYPES = List.of("e", "f");

    record Case(List<Edge> edges, Closure closure) {
        Oracle oracle() {
            Oracle o = new Oracle();
            edges.forEach(o::snapshot);
            return o;
        }
    }

    @Provide
    Arbitrary<Case> cases() {
        Arbitrary<Ref> ref = Arbitraries.of(UNIVERSE);
        Arbitrary<Edge> edge = Combinators.combine(ref, Arbitraries.of(TYPES), ref).as(Edge::new);
        // each type admitted forward, reverse, or not at all — never both (L2)
        Arbitrary<Set<Admit>> admits = Combinators.combine(admitOf("e"), admitOf("f")).as((a, b) -> {
            Set<Admit> s = new HashSet<>();
            a.ifPresent(s::add);
            b.ifPresent(s::add);
            return s;
        });
        Arbitrary<Closure> closure = admits.map(s -> new Closure("generated", s));
        return Combinators.combine(edge.list().ofMaxSize(14), closure).as(Case::new);
    }

    static Arbitrary<Optional<Admit>> admitOf(String type) {
        return Arbitraries.of(List.of(Optional.empty(), Optional.of(Admit.forward(type)), Optional.of(Admit.reverse(type))));
    }

    @Provide
    Arbitrary<Set<Ref>> refSets() {
        return Arbitraries.subsetOf(UNIVERSE);
    }

    // ---- the independent algorithm ----

    /** m[i][j]: j is reachable from i in admitted steps — zero or more if reflexive, else one or more. */
    static boolean[][] floydWarshall(Case c, boolean reflexive) {
        int n = UNIVERSE.size();
        boolean[][] m = new boolean[n][n];
        for (Edge e : c.edges()) {
            int s = UNIVERSE.indexOf(e.subject()), o = UNIVERSE.indexOf(e.object());
            if (c.closure().admits(e.edgeType(), Direction.FORWARD)) m[s][o] = true;
            if (c.closure().admits(e.edgeType(), Direction.REVERSE)) m[o][s] = true;
        }
        if (reflexive) for (int i = 0; i < n; i++) m[i][i] = true;
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (m[i][k] && m[k][j]) m[i][j] = true;
        return m;
    }

    // ---- cross-checks ----

    @Property
    void reachMatchesFloydWarshall(@ForAll("cases") Case c, @ForAll("refSets") Set<Ref> args) {
        Oracle o = c.oracle();
        boolean[][] m = floydWarshall(c, true);
        Set<Ref> expected = new HashSet<>();
        for (Ref x : args) {
            if (!o.isKnown(x)) continue;
            int i = UNIVERSE.indexOf(x);
            for (int j = 0; j < UNIVERSE.size(); j++) if (m[i][j]) expected.add(UNIVERSE.get(j));
        }
        assertEquals(expected, o.reach(c.closure(), args));
    }

    @Property
    void cyclesMatchFloydWarshall(@ForAll("cases") Case c, @ForAll("refSets") Set<Ref> args) {
        Oracle o = c.oracle();
        Set<Ref> result = o.reach(c.closure(), args);
        boolean[][] strict = floydWarshall(c, false); // one or more steps
        Set<Set<Ref>> expected = new HashSet<>();
        for (Ref r : result) {
            int i = UNIVERSE.indexOf(r);
            if (!strict[i][i]) continue;
            Set<Ref> component = new HashSet<>();
            for (int j = 0; j < UNIVERSE.size(); j++) if (strict[i][j] && strict[j][i]) component.add(UNIVERSE.get(j));
            expected.add(component);
        }
        assertEquals(expected, Set.copyOf(o.cycles(c.closure(), result)));
    }

    // ---- laws (§3), checked on the oracle itself ----

    @Property
    void reflexivityAndEmptiness(@ForAll("cases") Case c) { // L15, L16
        Oracle o = c.oracle();
        for (Ref x : UNIVERSE) {
            Set<Ref> r = o.reach(c.closure(), Set.of(x));
            assertEquals(o.isKnown(x), r.contains(x));
            assertEquals(!o.isKnown(x), r.isEmpty());
        }
    }

    @Property
    void inverse(@ForAll("cases") Case c) { // L18
        Oracle o = c.oracle();
        Closure inv = c.closure().inverse("inverse");
        for (Ref x : UNIVERSE)
            for (Ref y : UNIVERSE)
                assertEquals(o.reach(c.closure(), Set.of(x)).contains(y), o.reach(inv, Set.of(y)).contains(x));
    }

    @Property
    void transitivityAndIdempotence(@ForAll("cases") Case c) { // L19, L20
        Oracle o = c.oracle();
        for (Ref x : UNIVERSE) {
            Set<Ref> rx = o.reach(c.closure(), Set.of(x));
            for (Ref y : rx) assertTrue(rx.containsAll(o.reach(c.closure(), Set.of(y))));
            assertEquals(rx, o.reach(c.closure(), rx));
        }
    }

    @Property
    void distributivity(@ForAll("cases") Case c, @ForAll("refSets") Set<Ref> args) { // L21
        Oracle o = c.oracle();
        Set<Ref> union = new HashSet<>();
        for (Ref x : args) union.addAll(o.reach(c.closure(), Set.of(x)));
        assertEquals(union, o.reach(c.closure(), args));
    }

    @Property
    void cycleComponentsLieInsideTheResult(@ForAll("cases") Case c, @ForAll("refSets") Set<Ref> args) { // L27
        Oracle o = c.oracle();
        Result r = o.of(c.closure(), args);
        for (Set<Ref> component : r.cycles()) assertTrue(r.refs().containsAll(component));
    }
}
