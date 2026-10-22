package com.example.closure.tree.testkit;

import java.util.Optional;
import java.util.Set;

/**
 * What the kit needs from a tree implementation. The reference {@link ForestModel} implements it
 * directly; the real implementation implements it through a thin adapter over its storage port and
 * its {@code of} (brief §10.1), so the same oracles and traces run against both.
 */
public interface TreeUnderTest {

    /** Apply one ingest event through the implementation's full write procedure. */
    void apply(EdgeEvent event);

    /** Every edge currently stored, in parent/child terms. */
    Set<Edge> edges();

    /** The resource's materialized path, or empty when the resource is unknown. */
    Optional<ForestPath> pathOf(Ref ref);

    /** The stored {@code extra} flag of a stored edge (brief §4.1). */
    boolean isExtra(Edge edge);

    /**
     * {@code of} for a single argument under a closure admitting exactly these pairs. The adapter
     * resolves the set to a configured closure; {@link ReadOracle#STANDARD_CLOSURES} lists every set
     * the kit asks for, so a test instance can be configured with one closure per set.
     */
    Set<Ref> of(Ref argument, Set<Admitted> closure);
}
