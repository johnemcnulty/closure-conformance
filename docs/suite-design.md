# Conformance suite — implementation design

**Status.** Working document. It records how this repository implements the conformance suite described in §7 of the contract (`docs/contract-spec.md`), and is updated as decisions are made.

**What belongs here, and what does not.** The contract is implementation-agnostic: it says what a conforming instance does and what the suite must assert, and nothing about Maven, JUnit or Helm. Those choices are this suite's own, so they live here. The one shared boundary is the contract's Appendix A, the test profile, which stays in the contract because both the suite and every implementation under test must agree on it. The canonical serialization (L8) and the edge id function (W21) are fixed in the contract itself.

---

## 1. Stack

The suite is a test client: it publishes events to Kafka, sends HTTP requests, and compares what comes back with what it expects. That needs very little framework, so it uses none.

| Concern | Choice | Why |
|---|---|---|
| Language, build | Java 21, Maven | Team standard. |
| Test engine | JUnit Jupiter 5.13, jqwik 1.9 | Both run on the JUnit Platform, so one launcher runs examples and properties together. Check jqwik's support before moving to JUnit 6. |
| HTTP | JDK `HttpClient` + Jackson | No framework needed for `POST` and JSON. |
| Kafka | `kafka-clients` | The suite assigns partitions explicitly (fixture 15) and checks topic configuration with `AdminClient`. |
| Polling | Awaitility | Bounded by the tester's settle timeout. |
| Self-test infrastructure | Testcontainers (Kafka) | Used only by the suite's own build, against the reference implementation (§5). |

**Why not Spring Boot?** It would contribute configuration binding and little else, and it would bring a second compatibility question with jqwik. Pipeline integration is easier with a plain JUnit Platform jar: JUnit XML reports and a non-zero exit code on failure are all a CI system needs.

## 2. Modules

| Module | Contents | Status |
|---|---|---|
| `conformance-model` | The contract's value types, the canonical serialization (L8), the edge id function (W21) and the Appendix A profile. No runtime dependencies. | Done |
| `conformance-oracle` | The reference closure computer and its tests. Depends only on the model. | Done |
| `conformance-suite` | Fixtures, HTTP and Kafka clients, configuration, the profile gate, the runnable jar. | In progress: harness; fixtures 1–9, 11–14, 16 |
| `reference-impl` | A deliberately simple in-memory implementation, plus broken variants, for self-test (§5). Its build runs the suite against it, and needs Docker. | In progress: discovery, ingest, `of` with qualifications, the cycle diagnostic, and nineteen broken variants |
| `tree-testkit` | The tree implementation's own test kit: the forest invariant and read oracles, the scenario generator, traces T1–T5 and a reference forest model. It is not part of the conformance suite: it tests one implementation's internals, which the suite never may (contract §7). Plain Java, no runtime dependencies. | Done; the tree implementation is not started (see the tree implementation brief) |
| `deploy/` | Dockerfile, Helm chart, and a script running the image locally (§7). | Image built and verified; chart written, not yet run |

## 3. The oracle

**What it is for.** For most fixtures, the suite computes each expected result instead of reading it from a table. That raises an obvious question: if the suite tests closure computation with closure computation, what tests the oracle?

The answer starts with how simple it is. The oracle holds a set of edges and answers every question by running a breadth-first search over that set from scratch. Implementations are hard for reasons the oracle never encounters: incremental maintenance, materialized reach sets, concurrency, storage encodings and compaction. The bugs the suite looks for live in that gap. The oracle's reachability is the contract's definition in L23, written as about twenty lines of search, and it is meant to be checked by reading it. **Do not optimize it.**

**How it is verified.** Reading it is not the only check:

1. **Hand-written expectations** (`OracleTest`): small graphs whose expected results were worked out by hand, covering reflexivity, direction, admitted paths, set arguments, ingest idempotence, identity, cycles and cardinality.
2. **An independent algorithm** (`OraclePropertiesTest`): on generated graphs, reachability and cyclic components are recomputed with a Floyd–Warshall reachability matrix and must match the breadth-first search exactly.
3. **The laws** (`OraclePropertiesTest`): reflexivity, emptiness, inverse, transitivity, idempotence and distributivity, all checked on the oracle itself.
4. **Mutation check** (done once by hand, worth automating with PIT later): four deliberately planted bugs each made at least one test fail.

**The remaining risk** is a misreading of the contract that the oracle, the hand-written cases and an implementation all share. No test finds that, and only review of the contract does. That risk is highest for qualifications, which is why they have the most hand-written cases.

**The reference implementation must never share oracle code.** If it did, the suite's self-test would be checking the oracle against itself, and a misreading built into the shared code would go unseen.

**What the oracle returns for a qualification.** `cycle_detected` carries its code alone, and the closure's cycle diagnostic reports every cycle group in the result, each a strongly connected component with the admitted edges among its members (L28, W36). So the oracle returns every cyclic strongly connected component inside the result, and the suite expects the diagnostic to report exactly those, member for member; the edges of each are a filter over the fixture's own graph, not a closure computation (`CycleDiagnosis.expected`). For cardinality, the oracle returns every breach (`Result.breachesOf`). The suite asserts that the response lists at least `min(10, total)` references, that every one listed is a genuine breach, and that `total` equals the oracle's count (L28).

## 3a. The wire codec

**What it does.** `Canonical` writes and parses a resource reference (`port:PT-B1`), and `EdgeId` writes and parses an edge id (`(card:X>hosts>port:PT/1,2)`) and an edge reference (`edge:` followed by that id). The suite needs them to build Kafka keys and request payloads; the reference implementation needs them to read what the suite publishes.

**Both sides share it, deliberately.** That weakens fixture 13, which asserts an implementation rejects an event whose id disagrees with its triple: sharing the codec makes the reference implementation agree with the suite for free. The alternative — writing the parse twice — buys little, because the format is a deterministic string function rather than an algorithm with scale-dependent behaviour. What proves fixture 13 bites is the `trusts-triple` broken variant (§5), not duplicated code.

**Escaping, and why the order matters.** Within each of the three components, `%` becomes `%25` and then `>` becomes `%3E`; unescaping reverses both. Doing `%` first is what lets an id that already contains the text `%3E` survive a round trip — it is written `%253E` and comes back intact. An id carrying a comma, a colon, a parenthesis, a space or non-ASCII passes through literally, which is the point of choosing `>` over a comma.

## 3b. The test profile

**Where it lives, and why in two places.** Appendix A of the contract is the normative statement: it fixes the resource types, edge types, closures and cardinality rules a deployment stands up, and explains why each part is there. `TestProfile` in `conformance-model` is the same configuration in machine-readable form, for the suite to publish events against and for fixture 1 to compare the discovery document with.

**Two tests guard it.** `TestProfileTest` checks internal consistency — closure pairings are symmetric, a pair's admitted sets are exact flips, exactly one of each pair states them, every admitted pair and every rule names a declared authored name — so a typo fails there rather than as a confusing fixture failure later. It then checks *adequacy*: Appendix A claims each part of the profile is load-bearing, and these tests pin the mechanically checkable claims, so trimming the profile breaks a test instead of quietly weakening a fixture.

`ProfileFixturesTest` in the oracle module goes further by building the graph each demanding fixture would publish and running it through the oracle. That is what turns "every fixture is writable against this profile" from an argument into a check, before the harness exists. Each of those tests also asserts the negative case — that a clean graph is unqualified, that a reference is known rather than absent — so an assertion cannot pass by being vacuous.

**A.6 is read by the build. Editing it is a code change.** Appendix A exists in three transcriptions: the contract's A.6 document, `TestProfile`, and the reference implementation's `discovery.json`. Two tests keep them in step by parsing the JSON block under `### A.6` straight out of `docs/contract-spec.md`. `ProfileCheckTest` checks that A.6 passes fixture 1's comparison against `TestProfile`, and `DiscoveryDocumentTest` checks that `discovery.json` is A.6 verbatim. So an edit to A.6 fails the build until the other two follow, and renaming the heading or moving the block fails it too. This is deliberate: a drifted appendix fails here, not as a confusing fixture failure later. Only these tests read the file. The packaged suite never does, and needs no `docs/` at run time. An HTML comment above the block in the contract says the same to anyone editing it.

**One invariant fixtures must respect.** `one-holder` carries a minimum, so a `beta` or `gamma` with no holder breaches it and any result containing one comes back qualified. A fixture not provoking the rule gives every `beta` and `gamma` it creates exactly one holder, and uses `omega` when it wants a resource no rule constrains. The graph builder will enforce this.

## 4. How fixtures get their expectations

**Example fixtures** (2–9 and 11–14) use small graphs, of about five to fifteen references. Each is written down as a graph together with a table of expected results. The suite asserts the written values, and the build also checks the oracle against the same tables, so the hand-written answers test the oracle as well.

**Generated and large fixtures** (the property tests, the concurrency fixture and the result-size fixture) are too large to tabulate, so their expectations come from the oracle.

**Properties against a live instance are expensive.** The model and oracle properties run in memory, so jqwik's default of 1000 tries costs milliseconds. In the suite, every try publishes to Kafka and polls until the result settles, and with no reset operation, the edges of earlier tries stay on the instance. So the suite's properties:

- set `@Property(tries = …)` in the tens, not the default;
- give each try a fresh id sub-prefix under the run id, so tries cannot see each other's edges;
- expect shrinking to be slow, since every shrink step publishes and polls again. Limit or disable it per property if it dominates a failing run.

## 5. Self-test: a reference implementation and broken variants

**Why the suite needs something to fail against.** A suite that has never run against anything proves nothing by passing. The repository therefore carries a deliberately simple implementation: an in-memory Kafka consumer and HTTP server, with no attention to performance. The suite's own build runs against it, with Testcontainers supplying Kafka.

**Why it does not reuse the oracle.** Its job is to test the suite, not itself, so reusing the already-verified oracle looks tempting. It is not done, for three reasons. Several broken variants need structure the oracle deliberately lacks: `racy-recompute` needs affected sets maintained incrementally, and `ignores-admission` would mean forking the oracle's traversal. Real implementations fail in incremental maintenance and concurrency, which a reference shaped like one, however naive, gives the variants somewhere to break. And closure logic is a small part of it; ingest, rejection, validation and the HTTP layer are new code either way. So the reference implementation maintains reach sets as edges arrive, rather than searching per query as the oracle does, and keeps the independence §3 asks for as a side effect.

**Broken variants.** A passing run shows that the suite accepts a correct implementation, but not that it rejects a wrong one. Each variant below plants one known defect, and the build asserts that the named fixtures fail against it:

| Variant | Defect | Fixtures that must fail |
|---|---|---|
| `IGNORES_ADMISSION` | traverses edge types the closure does not admit | 2 |
| `HALTS_ON_REJECT` | stops consuming at a rejected event | 12, 13, 16 |
| `NO_REFLEXIVE_UNKNOWN` | returns `{x}` for an unknown `x` | 2, 8 |
| `TRUSTS_TRIPLE` | applies an event whose inner id, key or resource disagrees with its triple | 13 |
| `MISCONFIGURED` | serves a discovery document departing from Appendix A | 1, with a gated fixture skipped |
| `NO_CYCLES` | never reports `cycle_detected` | 3 |
| `UNRESTRICTED_BREACHES` | reports a rule's breaches on closures not admitting its edge type | 4 |
| `SHORT_TOTAL` | gives `total` as the number listed rather than the number breaching | 4 |
| `ONE_BREACH_LISTED` | lists one breaching reference whatever the total | 4 |
| `EDGELESS_GROUPS` | the cycle diagnostic reports members without their edges | 3 |
| `UNADMITTED_GROUP_EDGES` | the cycle diagnostic lists edges among members the closure does not admit | 3 |
| `PLACEHOLDER_FAR_END` | returns a reference seen only as an object under a placeholder type | 5 |
| `CASE_FOLDS` | case-folds argument ids | 6 |
| `TRIMS_IDS` | trims the ids it returns | 7 |
| `OMITS_ARGUMENT` | rejects a malformed argument without `meta.argument` | 9 |
| `TOGGLES_ON_REDELIVERY` | a redelivered snapshot retracts its edge | 11 |
| `ACCEPTS_UNAUTHORED` | accepts an event under an unauthored name | 12 |
| `FORGETS_HELD_OBJECT` | a delete forgets an object another edge still holds | 14 |
| `KINDS_AS_DELETES` | applies an event of an unknown kind as a delete | 16 |
| `UNFLIPPED_INVERSE` | gives a pair's derived closure its carrier's pairs without flipping them | the law properties (L18), 2 |
| `TRUNCATES` | returns the first hundred references of a larger result as though whole | 10 |
| `RACY_RECOMPUTE` | applies records on four threads and recomputes overlapping reach sets outside the lock | 15 (probabilistic) |
| `CEILING` | *correct alternative*: refuses any result over a hundred with `result_too_large` | must **pass** 10 |

`IGNORES_ADMISSION` is caught by the law properties as well (L23). Every fixture has at least one variant that it catches, and every new fixture needs one.

**Correct alternatives.** A fixture with more than one passing branch has some the correct instance never takes: the reference implementation has no size ceiling, so against it fixture 10 only ever sees the whole result. `CEILING` is a variant that is *right* in a different way the contract allows, and names the fixtures that must still pass against it — here, fixture 10 on its refusal branch. Without it, that branch's assertions would never have run against anything.

**How the build checks them.** The variants are values of `Variant` in `reference-impl`, each guarding its defect at the one place it acts and naming its fixtures; the table above is that enum, and the two must be kept in step. `BrokenVariantsTest` starts each variant on its own topic and port, sharing the self-test broker, and runs the fixtures it names against it in a separate JVM through the Console Launcher — the way the suite's image will run them — with `SETTLE_TIMEOUT=PT5S`, since a broken instance makes waits run to their bound. It reads the launcher's JUnit XML: each named fixture must have failed, and under `MISCONFIGURED` the gated fixture must have been skipped rather than failed. A control run of the correct instance against every named fixture must pass them all, so a failure is the defect's and not the runner's. A JVM per run is needed because the suite reads its configuration and reaches its profile verdict once per run. Runs go four at a time and take about 27 seconds together. The slowest is `HALTS_ON_REJECT`: once consumption stops, every wait runs to the settle timeout, so it is narrowed to one rejection test per fixture (`Variant.narrowedTo`) — the first rejection in a run is the only one that fails for its own event, and the rest of each fixture would fail only because consumption had already stopped. Narrowed, it runs five tests in about 26 seconds rather than fifteen in 77; fixture 12's rejection test is a factory of three, which is why it is five rather than three. Claiming a variant is caught by a fixture it cannot break fails the build, so the check itself has been shown to bite.

## 6. Running against an instance

**Isolation.** The contract has no reset operation, and fixtures share one long-lived instance. Each run therefore prefixes every id it creates with a run identifier. This keeps runs from colliding, lets one run continue after another fails partway, and means every run exercises unusual characters in ids as a side effect.

**Fixture 1 gates the rest.** If the profile check fails, the remaining fixtures are skipped, not failed, each carrying the check's report as its reason, so a misconfigured instance produces one readable failure rather than sixteen confusing ones.

The gate has to span two test engines. The example fixtures run on Jupiter and the generated ones on jqwik. A Jupiter extension cannot skip a jqwik property, and the Platform does not promise which engine runs first. So the gate is not tied to either engine:

- The profile check lives in one shared holder that runs it on first demand and memoizes the result, thread-safely.
- A Jupiter `ExecutionCondition` and a jqwik `SkipExecutionHook` ask the holder before each fixture and skip it if the check failed. Both are attached by one annotation, `@Fixture`, which every fixture class but fixture 1 carries; each engine ignores the other's half. They are not registered globally, because the suite's jar is on the classpath of its own unit tests and of the reference implementation's, which the gate must not touch.
- Fixture 1 itself is an ordinary Jupiter test that asserts the holder's result. That is where the failure is reported, with its detail.

Whichever test happens to run first triggers the check, so fixture 1 does not need to run first. A `LauncherSessionListener` was considered for running the check up front, but a launcher may open a session per discovery and per execution request, so the check could run more than once.

**What fixture 1 compares.** Lists (closures, edge types, rules, admitted pairs, a rule's types) are compared as sets, since the contract fixes no order, and members the contract does not name are ignored. An admitted pair published under the unauthored name is flagged, not normalized: W4 requires the instance to normalize before publishing, so the unauthored spelling in the document is itself the fault. A link "the suite can resolve" is read as a URI reference that resolves, against the document's URL, to an absolute `http` or `https` URL; that the endpoint answers is shown by the fixtures that query it. Once the document passes, fixture 1 also describes the ingest topic it names with `AdminClient`: at least two partitions (A.5), and `compact` among its cleanup policies (W29). A topic that does not exist, or cannot be described, fails fixture 1 like any other discrepancy.

**Observing a rejection.** A rejected ingest event is skipped (W27), so its only effect is an absence. The suite publishes the invalid event, then a valid sentinel edge on a different key in the same partition, and waits until the sentinel is visible. Seeing the sentinel proves consumption continued, and that part is conclusive. That the invalid event was not applied is evidence rather than proof. The contract's §7 describes this technique. `EdgePublisher.sentinelAfter` implements it: it computes each candidate key's partition the way Kafka's default partitioner does (murmur2 over the key's bytes, which W28 asks producers to use) and publishes the first candidate that shares the invalid event's partition.

**Configuration** comes from environment variables, so the same image runs anywhere. A system property of the same name takes precedence; the suite's own build uses that to point the suite at a reference instance it starts in-process on a free port. `SuiteConfig` holds only the settings some fixture already uses, and the rest of this table joins it as fixtures need them. It is read once per run (`SuiteConfig.current()`), so a generated run id is the same for every fixture. `DISCOVERY_URL` and `KAFKA_BOOTSTRAP` are required, since fixture 1 needs both. `SETTLE_TIMEOUT` defaults to `PT30S`, and `RESULT_SIZE_FANOUT` to 1000. A generated `RUN_ID` is a random token followed by `~ /,>%é:`, so every id a run creates carries a space, a slash, a comma, the edge id separator and escape, a colon and non-ASCII. Kafka authentication is not wired yet; it joins with packaging, the first time the suite meets a broker that needs it.

| Variable | Meaning |
|---|---|
| `DISCOVERY_URL` | the instance's discovery document |
| `KAFKA_BOOTSTRAP` | broker address reachable from the suite |
| `KAFKA_CLIENT_*` / mounted files | Kafka client properties for authentication (SASL, TLS): `KAFKA_CLIENT_SECURITY_PROTOCOL` becomes `security.protocol`; a `KAFKA_CLIENT_SSL_*_LOCATION` names a mounted file |
| `SETTLE_TIMEOUT` | polling bound, e.g. `PT30S` |
| `RESULT_SIZE_FANOUT` | fan-out size for fixture 10 |
| `RUN_ID` | optional; generated if absent |
| `REPORT_UPLOAD_URL` | optional object-store destination for JUnit XML; *not implemented yet* |

## 7. Packaging and deployment

**Image.** An executable jar launched by the JUnit Platform Console Launcher, in a minimal JRE image. The exit code is non-zero on any failure.

**The launcher is the main.** The suite has no `main` of its own. `ConsoleLauncher` (`junit-platform-console`) runs every engine on the classpath, exits `1` on any failure (run it with `--fail-if-no-tests` so an empty run exits `2` rather than passing), prints a tree with `--details=tree`, and writes JUnit XML per engine with `--reports-dir`.

**Fixtures live in `src/main/java`.** They are what the jar ships, so JUnit, jqwik and the clients are compile-scope dependencies of `conformance-suite`. The suite's own self-test runs them with Surefire's `dependenciesToScan` from the module that depends on the suite. Surefire's default includes only match names like `*Test`, so fixture class names must follow that or the includes must be widened.

**Packaging: a thin jar plus `lib/`.** `maven-jar-plugin` sets `Main-Class: org.junit.platform.console.ConsoleLauncher` and a manifest `Class-Path` into `lib/`, and `maven-dependency-plugin` copies the dependencies there. That keeps `java -jar`, merges nothing, and lets the image cache dependencies in their own layer. Two alternatives were rejected:

- *A shaded jar* must merge `META-INF/services/org.junit.platform.engine.TestEngine` with `ServicesResourceTransformer`. If it does not, Jupiter's and jqwik's entries overwrite each other and one engine silently disappears, so the suite passes with half its fixtures never run.
- *`junit-platform-console-standalone`* bundles its own Jupiter, which would be a second version to keep in step with the one the suite compiles against. It also lacks jqwik, so the suite's dependencies would still need to be supplied separately.

**Helm.** A standalone chart, independent of any implementation's chart, because the suite is implementation-agnostic. Its pod is annotated `helm.sh/hook: test`, so a pipeline runs:

```
helm install <instance-release> ...        # the implementation under test
helm install conformance ./deploy/helm --set discoveryUrl=...
helm test conformance --logs
```

and `helm test` passes or fails with the suite. Credentials come from referenced Kubernetes Secrets, never from values.

**What was built.** `mvn package` produces `conformance-suite-<version>.jar`, whose manifest names `ConsoleLauncher` as main and puts the 29 jars in `target/lib/` on its class path; `THIRD-PARTY-NOTICES.md` travels inside it. `deploy/Dockerfile` copies both onto a Java 21 runtime — `BASE_IMAGE`, default `eclipse-temurin:21-jre`, approved for this internal tool in the notices — runs as a non-root user, writes JUnit XML to `/reports`, and selects the fixtures package with `--fail-if-no-tests`. Unconfigured, the jar fails fixture 1 ("DISCOVERY_URL is not set"), skips the rest and exits 1, as a pipeline needs.

`deploy/run-locally.sh` runs the image against the reference implementation on one machine: Kafka in a container with one listener for the host and one for a Docker network, the topic created to A.5, the reference instance on the host (`ReferenceMain`, with an advertised URL so its links work from inside the network), and the suite's container on that network. Against the correct instance it passes all 175 tests and exits 0; with `VARIANT=TRUNCATES` it fails fixtures 10 and 15 and exits 1.

The Helm chart (`deploy/helm`) runs the suite as its release's test pod: `discoveryUrl` and `kafkaBootstrap` are required values; Kafka credentials come only from referenced Secrets — `kafkaClient.envSecret` for `KAFKA_CLIENT_*` variables, `kafkaClient.filesSecret` for truststores and keystores mounted read-only — never from values; `activeDeadlineSeconds` bounds a hung run.

**Getting reports out.** A completed test pod can't be copied from, so the suite prints a summary to stdout, which `--logs` shows. It can also upload the JUnit XML to an object store configured in the values, which is what CI dashboards consume.

## 8. Milestones

- [x] **Oracle.** The reference closure computer, with hand-written cases, independent-algorithm checks and law properties.
- [x] **Canonical serialization and edge id function.** Fixed in the contract: `type:id`, and `(subject>edgeType>object)` with `%` and `>` escaped in each component.
- [x] **Wire codec.** `Canonical` and `EdgeId`, with hand-written cases, hostile-id round trips and generated properties.
- [x] **Appendix A test domain.** Four resource types, four edge types, three closure pairs and two cardinality rules, with the discovery document they imply. Encoded as `TestProfile`.
- [x] **Suite harness and reference implementation, together.** The harness is configuration, the discovery client, the Kafka publisher, polling, run-id isolation, the graph builder that enforces the one-holder invariant (§3b), and fixture 1 with its cross-engine gate (§6). The reference implementation is the in-memory consumer and HTTP server, plus the Testcontainers wiring for the suite's own build.

  They are built as one thin end-to-end slice, then widened, because the harness has nothing to run against until the reference implementation exists and the two meet only at the wire. Each step touches both modules in one narrow area, and ends with `mvn verify` green before the next starts. Each side is written from `docs/contract-spec.md`, not from the other's code. A decision made during a step is recorded here when it is made.

  - [x] **Step 1: discovery.** Create both modules. The reference implementation serves the discovery document. The harness gets configuration, the discovery client, the memoized profile-check holder, both engines' gate hooks, and fixture 1, which runs end to end.

    The reference implementation's discovery document is the A.6 document, held verbatim as a resource, with only the `of` links and the topic name filled in from the running instance. It is not built from `TestProfile`, which would make fixture 1 pass by construction (the weakness §3a accepts for the codec). Fixture 1 then compares two independent sources, and a test checks the resource still matches A.6.

    Fixture 1's own tests run in memory, against the A.6 document with one clause broken per case: a closure missing its inverse or its `of` link, an unresolvable link, admitted pairs on the wrong closure of a pair or on both, a pair under the unauthored name, a missing or extra closure, an edge type with the wrong authored name or an inverse it should not have, a missing or altered cardinality rule, and no ingest topic. Each must fail and say why, and the unbroken document must pass. Testkit runs of a small Jupiter test and jqwik property check that the gate skips both when the check fails.
  - [x] **Step 2: ingest and `of`.** The reference implementation consumes edge events and answers `of`. The harness gets the Kafka publisher, polling bounded by `SETTLE_TIMEOUT`, and run-id prefixing.

    The harness: `EdgeEvents` builds well-formed events, checked against the contract's §6.5 examples written out in its test; `EdgePublisher` waits for each send's acknowledgement, publishes a delete as envelope then tombstone (W30), and can send raw records for fixtures that need malformed ones; `OfClient` posts W5's request and keeps the whole response; `Settle` polls with Awaitility and fails with the last value it saw; `Instance` gives a fixture all of them, built once from the configuration and the discovery fixture 1 accepted.

    The reference implementation is configured from the A.6 document it publishes, so what it computes and what it reports cannot disagree, and it refuses a configuration that breaks L2 or W23. One thread reads every partition from the beginning, without a consumer group, and applies each record in order: the plainest serial application W34 allows, and a rebuild from the topic on every start (W29). It rejects (skips, W27) what W23, W25 and W26 say it must, and never validates `scope`. Each closure keeps a reach set per known reference; an edge change recomputes the sets of every reference reaching either of its ends, and a query only reads. Qualifications came later, with fixtures 3 and 4 (batch 4 below). A request body that is not JSON, or has no `data` array, gets its own code, `request_malformed` (W13); the optional `GET` form of W5 is not served.

    The self-test: a `LauncherSessionListener` in `reference-impl`'s tests starts Kafka (Testcontainers, `apache/kafka`), creates the ingest topic with three partitions and compaction, starts the reference instance, and sets `DISCOVERY_URL` and `KAFKA_BOOTSTRAP` as system properties. It runs for every test session in that module, so all of `reference-impl`'s tests need Docker; the other modules do not. `IngestAndOfTest` drove the instance through the harness as an interim end-to-end check, until the fixtures covered the same ground (retired in batch 3).
  - [x] **Step 3: graph builder and fixture 2.** The graph builder, enforcing one holder per `beta` and `gamma`, then fixture 2 as the first real closure check.

    `GraphBuilder` takes types and edge types only from the profile, puts every id behind the fixture's run-id prefix, and refuses a graph in which any known reference breaches *any* cardinality rule, not only `one-holder`; a fixture provoking one says so with `buildBreaching()`, which in turn refuses a graph that breaches nothing.

    An example fixture is an `Example`: its graph and a hand-written table of rows, each `closure.of(arguments) = expected`, unqualified, named for what it asserts and citing its rules. `ExampleRun` publishes the graph once and runs each row as its own dynamic test. Before any row is asserted it waits until every edge is visible — a closure admitting the edge's type steps across it, or, for a type no closure admits, both ends are known — so a row asserting that something is *not* reached is never checked before the edge that would reach it has been consumed. Visibility is checked as containment, so an instance that reaches too much gets past the wait and fails on the rows, with a precise reason.

    The build checks every table against the oracle (`ExampleTablesTest`, using the oracle at test scope, which does not reach `reference-impl`), and also checks that each table is sharp: removing any one edge from the graph must change some row. A table failing that would carry an edge nothing shows was consumed. New examples are registered in `Examples.ALL`.

    Fixture 2 uses the graph the oracle's `ProfileFixturesTest` already showed writable, with 14 rows. Planting `ignores-admission` in the reference implementation by hand fails 6 of them, so the fixture catches the variant §5 names for it; the broken-variants milestone automates that check.
- [x] **Example fixtures 3–9, 11–14** (and 16), each with its written expectation table. Fixture 2 is done (step 3). Done in batches, each ending with `mvn verify` green:

  - [x] **Batch 1: fixtures 5 and 8.** Both fit the example table; rows gained set arguments (`Row.ofSet`). Every row now also checks that the references in a result all have the same members (L24), so none is marked or role-tagged. Planting `no-reflexive-unknown` by hand fails fixture 8 (two rows) and fixture 2 (its control row), as §5 says it should.
  - [x] **Batch 2: fixtures 6 and 9,** the HTTP surface: media types, statuses, malformed arguments and the `errors` document.

    Fixture 6's L8 half is an example table: a case pair under two holders, so a merged pair would show in either holder's result, plus a trailing space, a leading space, a decomposed `é` and an upper-cased type, none published, each of which must come back unknown. Its L7 half and fixture 9 send arguments as given, through `Rejections`: an id with no type, a bare string, an empty type, an empty id, a type with no id, a type containing `:` (L9), and one malformed argument among well-formed ones. Each must be `400` with an `errors` document whose first error is `reference_malformed`, no `data`, a `status` member, if present, of `"400"`, a `source.pointer` to the argument, and `meta.argument` equal, as JSON, to the argument sent. An id containing `:` and an unknown reference must each be a `200` with no `errors` (L9, L14, W12).

    *Where the argument goes* (W11) was settled during this batch. The contract first said only that `reference_malformed` "carries the argument as received", and the suite had to guess at `detail`. W11 now follows JSON:API: `source.pointer` locates the argument in the request, `meta.argument` repeats it as a JSON value, and `detail` is free text nothing may depend on. The suite compares `meta.argument` structurally, so formatting cannot fail it, and puts a malformed argument second among well-formed ones, so a pointer that is always `/data/0` fails. Planting either mistake in the reference implementation — the argument as a string, or a constant pointer — is caught.

    *Fixture 9's "a qualified result is a success" (W12)* waits for qualifications, with fixtures 3 and 4.

    Planting case-folding and trimming in the reference implementation's argument parsing fails fixture 6. It fails at the visibility wait rather than row by row, since folding the run id's own characters hides the fixture's edges. Planting an error that omits the argument fails all nine rejection tests.
  - [x] **Batch 3: fixtures 7, 11–14 and 16,** the ingest side, in two halves. Fixture 16 joined because it shares the rejection machinery with 12 and 13.

    *State as events arrive (7, 11, 14).* Fixture 7 is an example table: one `alpha` holds thirteen `omega`s whose ids carry the id function's separator and escape, an already-escaped separator, parentheses, `:`, an edge-reference lookalike, commas and slashes, leading and trailing spaces, quotes, a backslash, URI query characters, non-ASCII and an astral character; one row asks for them all at once, and one per id asks about it as an argument. Fixtures 11 and 14 are sequences, each step polled until its effect is visible before the next is published (`Instance.settledOf`). A redelivery has no visible effect of its own, so fixture 11 waits for a sentinel past it (`Instance.awaitConsumedPast`) before asserting nothing changed. Fixture 14 runs its two orders on separate ids and asserts both against one written final state.

    *Rejections (12, 13, 16).* `IngestRejections.assertRejected` publishes the invalid event about fresh references, waits for a sentinel on the same partition, and asserts the references are still unknown, which they would not be had the event been applied (L12). Every rejection case is followed by its control, `assertAccepted`: the same triple, well-formed, is applied, so an instance that applies nothing cannot pass. Fixture 12 covers every type with two names (`held_by`, `feeds`, `tracked_by`). Fixture 13's three W25 cases each break one identifier and keep the others consistent; its distinctness cases use `holds` and `tracks` over one pair, and the endpoints `>` and `%3E`, which collide if `%` is not escaped first. Fixture 16 sends each unknown kind twice: about a fresh edge, which catches an instance treating it as a snapshot, and about a visible edge, which catches one treating it as a delete. The first version had only the fresh case, and planting "unknown kinds are deletes" passed it.

    Planted by hand, each caught by its own fixture and no other: ids trimmed on output (7), a redelivered snapshot toggling its edge (11), a delete forgetting an object another edge still holds (14), `trusts-triple` (13's three W25 cases), unauthored names accepted (12), unknown kinds and inner types applied (16). `halts-on-reject` fails every fixture from the first rejection on; run with `SETTLE_TIMEOUT=PT5S`, since a halted consumer makes every later wait run to its bound.

    `IngestAndOfTest`, the interim check from step 2, is retired: fixtures 7, 8, 9, 11 and 12 now cover what it did.

  - [x] **Batch 4: qualifications, fixtures 3 and 4, and fixture 9's qualified success.**

    *Rows expect qualifications.* `Example.Row` carries the cyclic components inside its result and, per rule, every reference in the result breaching it; both must lie inside the result, and the build checks both against the oracle alongside the result set. The sharpness check now compares the whole answer, so an edge whose only effect is a qualification — a second holder — is shown to matter too.

    *How a response is checked* (`Qualifications`). `cycle_detected` is reported exactly when the row expects a cycle. (As first written, each one named references on a cycle, accepted if within one expected component; batch 5 replaced that with the cycle diagnostic.) `cardinality` is reported once per rule breached and for no other, listing at least `min(10, total)` references, each a genuine breach and none twice, with `total` the number breaching. Codes beyond the two L25 defines are not asserted on.

    *The reference implementation* computes both at query time from its materialized reach sets: a reference is on a cycle when a reference one admitted step on reaches it back, and its component is what it reaches that reaches it in turn. It keeps per-type edge counts at each end for the rules, reports a rule only where the closure admits its edge type (L27), names every cycle in full, and lists the first ten breaching references with the total.

    Fixture 3 uses the cycle graph `ProfileFixturesTest` showed writable (8 rows); fixture 4 its cardinality graph (9 rows) plus a second graph of twelve betas with two holders each, for the floor (2 rows). Fixture 9 gains the qualified-success case: a beta with no holder is answered `200`, with the whole result and the `one-holder` qualification. Planting each of the five qualification variants in §5's table fails the fixture it names.

  - [x] **Batch 5: the cycle diagnostic (W36).** The contract changed: `cycle_detected` now carries its code alone, at most once per response, and each closure gains a second link, `cycles`, to a diagnostic that takes `of`'s request and reports every cycle group in that result — a strongly connected component, with the admitted edges among its members, which are the edges a repair chooses among. A cycle's members say *that* the data needs repair; only its edges say *where*, and an `of` response had no place for them. Scoping to the arguments that drew the qualification keeps the answer about that result, and makes an empty answer the normal way of learning a transient cycle has cleared.

    In the suite, fixture 1 requires the `cycles` link; `Qualifications` asserts `cycle_detected` carries nothing but its code; and `ExampleRun` asks the diagnostic for every row of every example — a "diagnosed" twin per row — expecting exactly the row's cycle groups, so every unqualified row also checks the diagnostic reports none. Fixture 9 sends it a malformed argument, which must be rejected exactly as `of` rejects it. The oracle's `isCycleWitness` is retired; its tests assert components directly.

    Fixture 3 first had no unadmitted edge between cycle members, so a diagnostic listing every edge among them would have passed. G2's holder is now G1, putting a `holds` edge inside the `suppliers` cycle; planting "include unadmitted edges" then fails, as do "groups without edges" and "`cycle_detected` still carries `sources`".
- [x] **Generated and large fixtures:** properties for L18, L19, L21 and L23, then fixtures 10 and 15. Their expectations come from the oracle, so the suite now ships it (compile scope); `NoOracleTest` in `reference-impl` scans its compiled main classes for any reference to the oracle's package, with a control that the scan finds one where there is one.

  *The law properties* (`LawPropertiesFixtureTest`, jqwik) draw 2–7 references of the profile's types and 1–12 edges of its authored types among them, self-loops included, publish each try under its own prefix, and check the law over every reference and closure: L18 across each pair, L19 over every reach set, L21 over every pair of references, the whole set and the whole set with an unknown, and L23 as exact equality with the oracle, qualifications included. Eight tries each, shrinking off, as §4 planned; about 2.5 seconds for all four. `GeneratedGraphTest` checks the generator gives cyclic and acyclic graphs alike (at least 10% each, over 500 samples) — §7 asks for both — and was shown to fail when 99% cyclic was demanded.

  *Fixture 10* publishes `RESULT_SIZE_FANOUT` betas (default 1000) beneath one alpha and accepts exactly two answers: a `200` with the whole result, unqualified, or a `422` `result_too_large` with no `data`. Publishing was the cost — 7.5 seconds, one acknowledgement at a time — until `EdgePublisher.snapshotAll` sent a graph's edges together and then waited for them all (a key's records keep their order); it now takes a second. The visibility wait also stopped re-asking about edges it had already seen.

  *Fixture 15* publishes 220 keys at once — a spine two deep under one alpha, so every change overlaps on the alpha's reach set — some keys with several events, and checks the batch spans the topic's partitions. It waits for every `contents` and `containers` reach set to converge on the oracle's state from each key's last event, then checks qualifications. Two lessons from making `RACY_RECOMPUTE` fail it. A stale reach set is repaired by the next recompute of it, so only overlapping *final* writes leave the final state wrong; each key's history is therefore aligned to the end of the batch, so the last round holds every key's final event. And the variant's delay between snapshot and write had to *vary*: a fixed one kept writes in snapshot order, so the last write always carried the newest snapshot and nothing was ever lost. Even then one burst caught it about three times in four, so the fixture runs three independent bursts on fresh ids, and `RACY_RECOMPUTE` is marked probabilistic: the self-test gives it up to three attempts and counts it caught if any fails fixture 15, since one failure is conclusive and a pass only evidence (§7). In five consecutive builds it was caught on the first attempt every time.

  *Infrastructure, observed once.* In one of many back-to-back builds the self-test's Kafka broker stopped answering for a while; fixture 1 then failed on its topic check in several variant runs and gated their fixtures, failing checks that had nothing to do with their variants. It did not recur. If it does, the place to look is Docker Desktop's resources, since every variant run shares the one broker.
- [x] **Broken variants,** each asserted to fail its fixtures (§5): nineteen, one or more for every written fixture, plus a control. `truncates` and `racy-recompute` join with fixtures 10 and 15.
- [ ] **Packaging:** container image, Helm chart, and `helm test` against a local cluster. The jar, the image, Kafka authentication and the notices are done and verified (§7); the chart is written but not yet linted or run, since this machine has neither Helm nor a cluster. Report upload (`REPORT_UPLOAD_URL`) is not implemented.

## 9. Open questions

- **`result_too_large` status.** `422` was chosen because no standard code means "the response would be too large". Revisit it if gateways treat 422 specially.
- **groupId and package names** are placeholders (`com.example.closure`).

**Settled since the first draft**, kept here so the reasoning isn't lost:

- *The test domain's types are Greek letters*, not equipment nouns. A recognizable vocabulary would invite a reader to reject a fixture's graph on domain grounds — the cyclic fixture needs a cycle in a containment-like relationship, and `card hosts port hosts card` reads as a broken fixture rather than a deliberate one.

- *The edge id separator* is `>`, not a comma (W21). Equipment ids carry commas routinely, so a comma separator escaped them to `%2C` in the record key, the log line and the payload alike. A `>` is rare in equipment naming, so escaping almost never fires. The cost is that `>` must be percent-encoded in a URI query, which only affects an edge reference passed to `of` — a case that would mean edges between edges.

- *Closures admitting both directions of one edge type* are forbidden (L2). Relationships are directional with named inverses, and a both-ways closure made every edge a cycle with itself. The oracle's cycle detection is therefore the plain one: a reference is on a cycle when it reaches itself.
- *Cycles are diagnosed, not named in the qualification* (W36). A result over a cycle is still correct over the graph as it stands (L26); what the caller needs is where the data needs repair, which means edges. A qualification naming one cycle's members gave neither. The diagnostic reports whole strongly connected components — "tangles" — rather than individual cycles: each reference belongs to exactly one, so there is one right answer to test, and they are linear to find where cycles can be exponential. The cost, accepted on the expectation that tangles of several overlapping cycles are rare, is that a caller facing one must still work out which edges to cut.
- *`meta.generations` is withdrawn* from the contract (it was a SHOULD of W9). Keyed by argument, it showed which members of a set contributed, which fixture 8 says nothing may; no caller could act on it; and a composite could not report it without becoming distinguishable. Fixture 8 asserts the result set and that every reference in it is returned the same way (L24).
- *Cardinality reporting* is bounded with a floor: at least ten breaching references per rule, or all of them if there are fewer, plus a total (L28). This is the approach compilers take: show enough breaches to work through, and use the count to tell a patchable situation from one that needs a reload.
