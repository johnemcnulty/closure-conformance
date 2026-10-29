# Building the tree implementation

You are implementing the **tree** implementation of the relationship closure contract: a service that consumes edge events from a Kafka topic, keeps a materialized forest in Cassandra, and answers closure queries over HTTP. This repository starts with design documents only, under `docs/`, and no code to start from. Everything in it is written here from the documents. The one existing code it uses is the organization's conformance suite, as a test dependency.

Put this file at the repository root and the five documents under `docs/`.

## What to read, and which wins

1. **`docs/contract-spec.md`** is the specification. It wins every disagreement.
2. **`docs/tree-implementation-brief.md`** is your working document: what to build, in what order, and why each choice was made.
   - Read §1 and §2 first.
   - Then read §10.3, whose table says what each slice builds and which fixtures accept it, and §10.2, which says what tests accept it.
   - Each slice draws on the brief's sections for what it builds: the codec in §3.3, ingest in §5, writes in §6, reads in §7.
   - The rule numbers it cites (`L`n, `W`n) are valid against `docs/contract-spec.md` only.
3. **`docs/suite-integration-spec.md`** says how your build runs the organization's conformance suite against the tree in Testcontainers, and which fixtures each slice claims. Do its §2 checks before writing any code.
4. **`docs/tree-test-spec.md`** specifies the tree's unit tests: component tests (Part A), and tests for the failure modes the suite can't see (Part B), with a reference model, oracles, a small Java test contract and traces T1–T5. Its §14 says which tests each slice needs.
5. **`docs/tree-design-extract.md`** is background only. Read it when the brief's reasoning isn't enough.

**The conformance suite decides where the contract is silent.** Its fixtures run in your build on every `mvn verify`. If a fixture seems to contradict the contract, record it in `QUESTIONS.md` rather than following either.

## Rules

- **Build one slice at a time, in the order of brief §10.3.** A slice is done when `mvn verify` is green, plus any command `docs/suite-integration-spec.md` §5 has you record: the suite's fixtures for the slice and every earlier slice's, and the unit tests `docs/tree-test-spec.md` §14 lists for it and every earlier slice. Then stop and report (below). Start the next slice when the project owner says to.
- **Never edit the suite.** Don't patch, fork, shade or work around it, and claim fixtures only through the include list of `docs/suite-integration-spec.md` §5. You may read its self-test wiring as the pattern for your own. Copy no closure logic from anything it ships or runs against.
- **Never edit the contract, the two specifications or the extract.** The test contract's types (test spec §5.3) are transcribed into test sources as written. The adapter is yours; the contract, the reference model, the oracles and the traces are not yours to change. Edit the brief only by adding rows to its decisions log (§11). If a rule in any of them looks wrong, report it rather than working around it.
- **Never weaken, skip or edit a test to make the build green,** and never raise the suite's settle timeout to make a fixture pass. If a test looks wrong, report it.
- **Every decision the brief doesn't make goes in the brief's decisions log** (§11), as a new numbered row with its source, in the same change as the code that depends on it.
- **A gap or contradiction you can't resolve from the documents goes in `QUESTIONS.md`**, with where it arises and the options you see, never in a workaround. If it blocks the slice, stop there. If it doesn't, carry on with the rest.
- **Build references and edge ids through one codec** (brief §3.3), never by string concatenation elsewhere. Its unit tests reproduce the brief's test vectors exactly. The escaping order, `%` before `>`, matters.
- **Oracles live in test sources only.** Main code never calls the unit tests' breadth-first oracle, nor anything from the suite: a guard test enforces the second (`docs/suite-integration-spec.md` §3). Keep your own oracle naive: recomputed from the edge set on every call, with no index and no cache.
- **Take domain names from the configuration in tests,** not scattered string literals. The suite runs on the conformance profile (brief §3.1, contract Appendix A). The failure-mode tests use the configurations of `docs/tree-test-spec.md` §6.
- **Never assert result order or a consistency class.** The contract declines both (L6, §1.3).
- **Do not optimize ahead of the brief.** No coalescing, parallel partitions, caching beyond what brief §7.1 describes, or bulk rebuild, until a slice calls for it.

## Conventions

- Java 21 and Maven, with the JDK's `HttpServer`, Jackson and `kafka-clients`. No Spring or other framework.
- camelCase for JSON attribute names and Java members.
- Records for value types, validated in the canonical constructor.
- Test names say what is asserted, e.g. `unadmittedEdgeStopsThePath`, and carry the rule or failure-mode id in a trailing comment.
- Rule identifiers are renumbered with each draft of the contract. Cite them in comments, and re-check the citations if the contract is replaced.

## Licensing and provenance

- Write original implementations from the documents. Don't copy or closely reproduce code from other repositories, websites or tutorials.
- Prefer dependencies licensed under MIT, BSD, ISC or Apache-2.0, checking transitive dependencies too. Treat copyleft and unknown licenses as needing approval, and follow the organization's dependency policy where it says more.
- If code resembles a known third-party implementation, flag it rather than claiming it is original.
- Report new dependencies and their licenses in each slice report.

## Environment

The build needs Docker, for Testcontainers' Kafka and later Cassandra, and access to the Maven repository that holds the conformance suite and the other dependencies. If either is missing, say so at once: without them, the suite can't run and no slice can be finished.

## Reporting at the end of each slice

Append one section per slice to `SLICES.md`:
- what was built
- the exact `mvn` command run and its result: the suite's fixtures passed, failed and skipped, and the unit test counts for Part A and Part B
- the conformance suite version the build ran
- for slice 1, the findings of `docs/suite-integration-spec.md` §2
- the decisions log rows added
- the open entries in `QUESTIONS.md`
- new dependencies and their licenses
- anything in the brief or the specifications that turned out wrong or unclear, even if you resolved it
