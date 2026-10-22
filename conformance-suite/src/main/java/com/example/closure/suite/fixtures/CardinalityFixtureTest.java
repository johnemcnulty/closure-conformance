package com.example.closure.suite.fixtures;

import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixture 4: every bound of every rule, reported where it applies and nowhere else (§7). */
@Fixture
class CardinalityFixtureTest {

    @TestFactory
    Stream<DynamicTest> cardinality() { // L25, L27, L28, W10
        return ExampleRun.rows(Cardinality.example(Instance.get().runId()));
    }

    @TestFactory
    Stream<DynamicTest> breachesBeyondTheReportingFloor() { // L28
        return ExampleRun.rows(Cardinality.floor(Instance.get().runId()));
    }
}
