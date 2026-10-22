# Building the tree implementation

You are implementing the **tree** implementation of the relationship closure contract, as a new Maven module here, `tree-impl`. It doesn't exist yet apart from this file. Add it to the root `pom.xml`'s modules when you create it.

## What to read, and which wins

1. **`docs/contract-spec.md`** is the specification. It wins every disagreement.
2. **`docs/tree-implementation-brief.md`** is your working document: what to build, in what order, and why each choice was made. Read §1 and §2 first, then §10.3, whose table says what each slice builds and which fixtures accept it. Each slice draws on the brief's sections for what it builds: ingest in §5, writes in §6, reads in §7. The rule numbers it cites (`L`n, `W`n) are valid against the contract in this repository only.
3. **The conformance suite** (`conformance-suite`) is the acceptance test. Where the contract is silent, what its fixtures assert decides.
4. **`tree-testkit/`** is the tree's own test kit: invariant and read oracles, traces T1–T5, and `ForestModel`, the reference reading of the placement rules. Adopt it in slice 3 (brief §10.2).
5. **`docs/tree-design-extract.md`** is background only. Read it when the brief's reasoning isn't enough.

The repository's own `CLAUDE.md` applies here too: Java 21, Maven, no Spring, records for value types, camelCase, the licensing policy, and test names carrying their rule id.

## Rules

- **Build one slice at a time, in the order of brief §10.3.** A slice is done when `mvn verify -pl tree-impl -am` is green: its fixtures, every earlier slice's, and the tree's own tests. Then stop and report (below) before starting the next.
- **Never edit the contract, the suite, the oracle, `reference-impl` or the test kit**, even where the kit's README describes changing it: that note is for the kit's maintainers. If a forest rule turns out to be wrong, report it. Never weaken, skip or exclude a fixture beyond what brief §10.3's include list prescribes for the current slice. If one of them looks wrong, report it.
- **`reference-impl` is not a design source.** Read it only for its test wiring (`SelfTestInstance` and its Surefire configuration) and to check what a fixture expects. Copy nothing from it.
- **Main code never references `conformance-oracle`.** It reaches your tests through the suite, so add the guard test brief §10.2 describes.
- **Build references and edge ids with `conformance-model`'s `Canonical` and `EdgeId`**, never by string concatenation.
- **Every decision the brief doesn't make goes in the brief's decisions log** (§11), as a new numbered row with its source, in the same change as the code that depends on it.
- **A gap or contradiction you can't resolve from the documents goes in `tree-impl/QUESTIONS.md`**, with where it arises and the options you see. Never in a workaround. If it blocks the slice, stop there. If it doesn't, carry on with the rest.
- **Do not optimize ahead of the brief.** No coalescing, parallel partitions, caching beyond what §7.1 describes, or bulk rebuild, until a slice calls for it.

## Environment

The build needs Docker, for Testcontainers' Kafka and later Cassandra, and access to Maven Central. If either is missing, say so at once. Without them no slice can be accepted, and work done without running the suite is guesswork.

## Reporting at the end of each slice

Write `tree-impl/SLICES.md`, appending one section per slice:
- what was built
- the exact `mvn` command run and its result, with the fixture and test counts
- the decisions log rows added
- the open entries in `QUESTIONS.md`
- anything in the brief that turned out wrong or unclear, even if you resolved it
