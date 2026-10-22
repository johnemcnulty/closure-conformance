package com.example.closure.suite.fixtures;

import com.example.closure.suite.Example;
import com.example.closure.suite.RunId;
import java.util.List;
import java.util.function.Function;

/** Every example fixture's graph and table, so the build can check each against the oracle (suite-design §4). */
public final class Examples {

    public static final List<Function<RunId, Example>> ALL = List.of(
        TypeRestriction::example,
        Cycle::example,
        Cardinality::example,
        Cardinality::floor,
        FarEnd::example,
        Identity::example,
        HostileReferences::example,
        SetArgument::example);

    private Examples() {
    }
}
