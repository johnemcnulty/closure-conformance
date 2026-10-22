package com.example.closure.suite.fixtures;

import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixture 8: a set argument is the union of its members, and an empty set is empty (§7). */
@Fixture
class SetArgumentFixtureTest {

    @TestFactory
    Stream<DynamicTest> setArgument() { // L14, L17, L21
        return ExampleRun.rows(SetArgument.example(Instance.get().runId()));
    }
}
