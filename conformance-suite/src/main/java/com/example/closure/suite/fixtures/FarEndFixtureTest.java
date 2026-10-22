package com.example.closure.suite.fixtures;

import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixture 5: a reference seen only at the far end of edges is returned like any other (§7). */
@Fixture
class FarEndFixtureTest {

    @TestFactory
    Stream<DynamicTest> farEnd() { // L11, L24, W1
        return ExampleRun.rows(FarEnd.example(Instance.get().runId()));
    }
}
