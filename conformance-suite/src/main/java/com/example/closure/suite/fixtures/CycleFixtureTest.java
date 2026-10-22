package com.example.closure.suite.fixtures;

import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixture 3: a result over a cycle is returned and qualified, and only where the cycle is admitted (§7). */
@Fixture
class CycleFixtureTest {

    @TestFactory
    Stream<DynamicTest> cycle() { // L25–L28
        return ExampleRun.rows(Cycle.example(Instance.get().runId()));
    }
}
