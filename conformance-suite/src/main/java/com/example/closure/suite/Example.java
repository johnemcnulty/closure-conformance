package com.example.closure.suite;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An example fixture: a small graph and its hand-written expectation table (suite-design §4). The
 * fixture asserts each row against the instance; the suite's own build checks every row against
 * the oracle, so the written answers test the oracle as well.
 */
public record Example(String name, FixtureGraph graph, List<Row> rows) {

    public Example {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(graph, "graph");
        rows = List.copyOf(rows);
    }

    /**
     * One row: {@code closure.of(arguments)} must be exactly {@code expected}, carrying exactly the
     * qualifications listed (L25–L28) — none, unless the row says otherwise.
     *
     * @param assertion what the row asserts, written as a test name, e.g. {@code traversalStopsAtTheUnadmittedEdge}
     * @param rules the rule identifiers it rests on, e.g. {@code L23}
     * @param cycles every cyclic component of the closure's admitted edges inside the result; a
     *     reported cycle must lie within one of them, since which cycle is named is the instance's
     *     choice (L28)
     * @param breaches for each rule breached, every reference in the result breaching it (L27, L28)
     */
    public record Row(String assertion, String rules, String closure, Set<Ref> arguments, Set<Ref> expected,
                      List<Set<Ref>> cycles, Map<String, Set<Ref>> breaches) {
        public Row {
            Objects.requireNonNull(assertion, "assertion");
            Objects.requireNonNull(rules, "rules");
            TestProfile.declared(closure); // only a profile closure
            arguments = Set.copyOf(arguments);
            expected = Set.copyOf(expected);
            cycles = cycles.stream().map(Set::copyOf).toList();
            breaches = Map.copyOf(breaches);
            for (Set<Ref> cycle : cycles) {
                if (!expected.containsAll(cycle)) {
                    throw new IllegalArgumentException(assertion + ": a cycle lies inside the result (L27)");
                }
            }
            for (Map.Entry<String, Set<Ref>> breach : breaches.entrySet()) {
                if (breach.getValue().isEmpty() || !expected.containsAll(breach.getValue())) {
                    throw new IllegalArgumentException(assertion + ": breaches of " + breach.getKey() + " lie inside the result (L27)");
                }
            }
        }

        public static Row of(String assertion, String rules, String closure, Ref argument, Ref... expected) {
            return new Row(assertion, rules, closure, Set.of(argument), Set.of(expected), List.of(), Map.of());
        }

        /** A row whose argument is a set, empty included (L5, L17). */
        public static Row ofSet(String assertion, String rules, String closure, Set<Ref> arguments, Ref... expected) {
            return new Row(assertion, rules, closure, arguments, Set.of(expected), List.of(), Map.of());
        }

        /** This row, with a cyclic component expected inside its result (L25, L27). */
        public Row withCycle(Ref... component) {
            List<Set<Ref>> more = new ArrayList<>(cycles);
            more.add(Set.of(component));
            return new Row(assertion, rules, closure, arguments, expected, more, breaches);
        }

        /** This row, with {@code rule} breached by exactly {@code breaching} inside its result (L25, L27). */
        public Row withBreaches(String rule, Set<Ref> breaching) {
            Map<String, Set<Ref>> more = new HashMap<>(breaches);
            more.put(rule, breaching);
            return new Row(assertion, rules, closure, arguments, expected, cycles, more);
        }

        public Row withBreaches(String rule, Ref... breaching) {
            return withBreaches(rule, Set.of(breaching));
        }

        public boolean qualified() {
            return !cycles.isEmpty() || !breaches.isEmpty();
        }

        public String displayName() {
            return assertion + " (" + rules + "): " + closure + ".of(" + arguments + ")";
        }
    }
}
