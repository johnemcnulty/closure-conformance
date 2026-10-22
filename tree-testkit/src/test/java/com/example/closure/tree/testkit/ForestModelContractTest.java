package com.example.closure.tree.testkit;

import org.junit.jupiter.api.Test;

/**
 * The kit's contract tests run against its own reference model. The real implementation's test
 * looks the same with {@code ForestModel::new} replaced by a factory for its adapter (see README).
 *
 * <p>{@code -Dforest.scenarios=N} and {@code -Dforest.firstSeed=S} widen a run or replay one seed.
 */
class ForestModelContractTest {

    private static final int SCENARIOS = Integer.getInteger("forest.scenarios", 2000);
    private static final long FIRST_SEED = Long.getLong("forest.firstSeed", 0L);

    @Test
    void invariantsAndReadsHoldAcrossRandomScenarios() {
        ForestProperties.run(ForestModel::new, SCENARIOS, FIRST_SEED, 0.3);
    }

    @Test
    void workedTracesMatch() {
        ForestTraces.runAll(ForestModel::new);
    }
}
