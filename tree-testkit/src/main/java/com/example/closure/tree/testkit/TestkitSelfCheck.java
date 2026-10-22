package com.example.closure.tree.testkit;

import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the kit against its own reference model, then against deliberately broken models to show
 * the oracles catch each kind of mistake. Needs no test framework:
 * {@code java -cp target/classes closure.tree.testkit.TestkitSelfCheck [scenarios]}.
 */
public final class TestkitSelfCheck {

    public static void main(String[] args) {
        int scenarios = args.length > 0 ? Integer.parseInt(args[0]) : 2000;

        var summary = ForestProperties.run(ForestModel::new, scenarios, 0, 0.3);
        System.out.println("reference model: " + summary);
        ForestTraces.runAll(ForestModel::new);
        System.out.println("reference model: traces T1-T5 pass");

        boolean allCaught = true;
        for (var broken : List.<Supplier<ForestModel>>of(
                BrokenModels.NoOldRootRecheck::new, BrokenModels.NoHops::new,
                BrokenModels.MaterializesCycles::new, BrokenModels.PromotesAnyParent::new)) {
            String name = broken.get().getClass().getSimpleName();
            String property = caught(() -> ForestProperties.run(broken, scenarios, 0, 0.3));
            String traces = caught(() -> ForestTraces.runAll(broken));
            System.out.println(name + ": property " + property + "; traces " + traces);
            allCaught &= property.startsWith("caught") && traces.startsWith("caught");
        }
        if (!allCaught) {
            System.out.println("some broken model was not caught");
            System.exit(1);
        }
    }

    private static String caught(Runnable r) {
        try {
            r.run();
            return "NOT caught";
        } catch (AssertionError e) {
            String first = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            return "caught (" + first + ")";
        }
    }
}
