package com.example.closure.tree.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Each broken model must be rejected by both the property runner and the traces. */
class OracleSensitivityTest {

    private static void rejected(Supplier<? extends ForestModel> broken) {
        assertThrows(AssertionError.class, () -> ForestProperties.run(broken, 500, 0, 0.3));
        assertThrows(AssertionError.class, () -> ForestTraces.runAll(broken));
    }

    @Test
    void missingOldRootRecheckIsCaught() {
        rejected(BrokenModels.NoOldRootRecheck::new);
    }

    @Test
    void missingHopsAreCaught() {
        rejected(BrokenModels.NoHops::new);
    }

    @Test
    void materializingACycleClosingEdgeIsCaught() {
        rejected(BrokenModels.MaterializesCycles::new);
    }

    @Test
    void promotingAParentFromBeneathIsCaught() {
        rejected(BrokenModels.PromotesAnyParent::new);
    }
}
