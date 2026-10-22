# tree-testkit

The test oracle and worked traces for the tree implementation, from *Tree implementation brief* §9 and §10.2, Plain Java 21 with no runtime dependencies, so it works under any test framework. JUnit 5 is used only to run the kit's own tests.

## What is here

| Class | Role |
|---|---|
| `TreeUnderTest` | The five operations the kit needs from a tree: `apply`, `edges`, `pathOf`, `isExtra`, `of` |
| `ForestInvariants` | **Structural oracle.** Checks F1–F3 of brief §4.2 from scratch, plus convergence to the target once the edges are a proper tree |
| `Bfs`, `ReadOracle` | **Read oracle.** Every `of` must equal breadth-first reachability (contract L23), checked under eight standard closures: downward and upward over each type and both, and the two that mix directions across types. None admits both directions of one type, which contract L2 forbids |
| `ScenarioGenerator` | Random edit sequences over 3–9 resources and two edge types `h` and `s`: second parents, cycles, self-loops and transient roots, then convergence in random order to a random tree |
| `ForestProperties` | **The property test.** Applies each scenario event by event and checks edges, invariants and (sampled) reads after every one |
| `ForestTraces` | **Traces T1–T5** of brief §9, with exact paths, extra edges and reads after each event |
| `ForestModel` | **The reference model.** The forest rules in about 60 lines, in memory. It is the executable reading of the placement rules and the reads (design §3.1, brief §6.2, §7.3). It is not a model of the write procedure: it returns early on a redelivered event, which the implementation must not, and it has no anomalies, static sets or journal |
| `BrokenModels`, `TestkitSelfCheck` | Four models with one rule broken each, and a runner showing the oracles reject all four |

## Running it

```
mvn test -pl tree-testkit                                             # the kit's own tests, against ForestModel
mvn test -pl tree-testkit -Dforest.scenarios=20000                    # a wider run
mvn test -pl tree-testkit -Dforest.firstSeed=1234 -Dforest.scenarios=1   # replay one scenario
java -cp tree-testkit/target/classes com.example.closure.tree.testkit.TestkitSelfCheck 2000   # no JUnit needed
```

The tree implementation depends on this module at test scope. The kit has its own `Ref` and `Edge` types, in parent/child terms. They are deliberately not `conformance-model`'s, which are in subject/object terms, so the adapter maps between the two.

## Using it against the real implementation

Write an adapter that implements `TreeUnderTest` over the implementation, then call the two runners from the implementation's own tests:

```java
class TreeContractTest {
    @Test void invariantsAndReads() { ForestProperties.run(TreeAdapter::freshInMemory, 2000, 0, 0.3); }
    @Test void traces()             { ForestTraces.runAll(TreeAdapter::freshInMemory); }
}
```

What the adapter has to do:

- **`apply`** builds an ingest event for the edge and runs it through the **full write procedure** (brief §6), not a shortcut. The kit works in parent/child terms, so configure the test instance's edge types `h`, `s` and `hosts` with `parentEndpoint: subject`. An edge `parent -h-> child` is then the ingest triple `(subject = parent, h, object = child)`.
- **`edges`, `pathOf`, `isExtra`** read straight from the storage port: the edge tables, `tree_path_by_ref` decoded to segments, and the `extra` flag. They must report what is stored, not recompute it, or the oracle checks itself.
- **`of(x, closure)`** goes through the implementation's real read path. Configure the test instance with one closure per entry of `ReadOracle.STANDARD_CLOSURES`, plus `hosts` downward and upward for the traces. The adapter looks up the closure whose admitted pairs match. `DOWN` is `(type, forward)` and `UP` is `(type, reversed)` under `parentEndpoint: subject`.

Run it first with the in-memory storage port, then again with Cassandra, as brief §10.1 orders it.

## When something fails

A property failure names the seed and prints every event applied so far as Java:

```
scenario seed 0 failed after 33 events: F3 violated at n6: a root has parent n5 ...
reproduce with:
    tree.apply(EdgeEvent.add("n3", "h", "n6"));
    ...
```

Paste those lines into a new method in `ForestTraces`, ideally cut down by hand to the events that matter, with `expectPaths` and `expectExtra` assertions. Take the expected values from `ForestModel`, not from the implementation under suspicion. It then becomes a permanent regression test that every implementation must pass.

## What the kit does not cover

It checks the forest and the reads. It deliberately leaves to the implementation's own tests:

- invariants F4 (static sets) and F5 (cycle records and cardinality)
- qualifications
- segment encoding and range-read bounds
- `parentEndpoint` mapping beyond `subject`
- the move journal and crash recovery
- fencing and rebuild

Brief §10.2 lists those tests. The conformance suite covers the contract surface.

## Keeping it in step with the documents

This section is for the kit's maintainers. An implementer who finds a rule wrong reports it rather than changing the kit (`tree-impl/CLAUDE.md`).

`ForestModel` is the reference. If a forest rule changes:
1. change the model first
2. run the kit
3. update the traces and then brief §9 from what the model does

The traces cross-check themselves against BFS, so a trace whose expected read is wrong fails as "trace is wrong, not the tree", rather than blaming the implementation.
