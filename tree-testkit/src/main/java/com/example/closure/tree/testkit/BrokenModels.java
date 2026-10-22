package com.example.closure.tree.testkit;

/**
 * Reference models with one forest rule broken each. They exist to prove the oracles have teeth:
 * the property runner and the traces must reject every one of them.
 */
final class BrokenModels {

    private BrokenModels() {}

    /** Forgets the old-root re-check: a broken ring never rematerializes. */
    static final class NoOldRootRecheck extends ForestModel {
        @Override protected void recheckOldRoot(Ref oldRoot) {}
    }

    /** Never follows extra edges: reads miss second parents and cycle-closing edges. */
    static final class NoHops extends ForestModel {
        @Override protected boolean followExtraEdges() { return false; }
    }

    /** Materializes an edge even when its parent lies beneath its child: the forest gets a cycle. */
    static final class MaterializesCycles extends ForestModel {
        @Override protected boolean mayMaterialize(Ref parent, Ref child) { return true; }
    }

    /** Promotes any remaining parent, even one beneath the resource. */
    static final class PromotesAnyParent extends ForestModel {
        @Override protected boolean mayPromote(Ref parent, Ref child) { return true; }
    }
}
