package com.example.closure.suite.fixtures;

import com.example.closure.suite.ExampleRun;
import com.example.closure.suite.Fixture;
import com.example.closure.suite.Instance;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixture 2: type restriction is path-wise, and a closure that cannot move returns the argument (§7). */
@Fixture
class TypeRestrictionFixtureTest {

    @TestFactory
    Stream<DynamicTest> typeRestriction() { // L12, L15, L16, L23
        return ExampleRun.rows(TypeRestriction.example(Instance.get().runId()));
    }
}
