package com.example.closure.suite.fixtures;

import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixture 7: hostile ids round-trip unchanged through ingest and through {@code of} (§7). */
@Fixture
class HostileReferencesFixtureTest {

    @TestFactory
    Stream<DynamicTest> hostileReferences() { // L10
        return ExampleRun.rows(HostileReferences.example(Instance.get().runId()));
    }
}
