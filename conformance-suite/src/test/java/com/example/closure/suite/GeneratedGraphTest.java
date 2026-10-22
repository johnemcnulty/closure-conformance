package com.example.closure.suite;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.oracle.Oracle;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.api.statistics.StatisticsReport;

/** The law properties' generator yields acyclic and cyclic instances alike, as §7 asks. */
class GeneratedGraphTest {

    @Provide
    Arbitrary<GeneratedGraph.Spec> graphs() {
        return GeneratedGraph.specs();
    }

    @Property(tries = 500)
    @StatisticsReport(StatisticsReport.StatisticsReportMode.OFF)
    void theGeneratorProducesCyclicAndAcyclicGraphs(@ForAll("graphs") GeneratedGraph.Spec spec) { // §7
        Oracle oracle = new Oracle(TestProfile.CARDINALITY_RULES);
        List<Ref> refs = new ArrayList<>();
        for (int n = 0; n < spec.types().size(); n++) {
            refs.add(Ref.of(spec.types().get(n), "N" + n));
        }
        spec.edges().forEach(e -> oracle.snapshot(Edge.of(refs.get(e.subject()), e.edgeType(), refs.get(e.object()))));
        boolean cyclic = TestProfile.CLOSURES.stream()
            .anyMatch(c -> refs.stream().anyMatch(r -> oracle.of(c.closure(), r).cycleDetected()));
        Statistics.label("cyclic").collect(cyclic);
        Statistics.label("cyclic").coverage(coverage -> {
            coverage.check(true).percentage(p -> p >= 10);
            coverage.check(false).percentage(p -> p >= 10);
        });
    }
}
