# Conformance suite integration specification

**Status.** A companion to the *Tree implementation brief*. It says how the tree's build runs the organization's conformance suite against a tree instance started in Testcontainers, so that every `mvn verify` checks the tree against the real suite.

**Where it sits.** The contract governs, then the brief, then this document and the unit test specification. Section references prefixed *brief* are to the brief, and L*n*/W*n* are contract rules. Fixture numbers are contract §7's.

---

## 1. What this is for

**Why run the suite inside the tree's build?** The suite is the authority on conformance (*brief* §1), and the earlier the tree meets it, the cheaper each failure is. Run as a library inside the build, it tests the tree end to end, through real Kafka ingest and real HTTP, on every `mvn verify`. So no hand-written copy of its fixtures is needed, and none can drift from it. The tree's own unit tests (`tree-test-spec.md`) cover what the suite can't see; this document covers only what the suite can.

**The suite is not the implementer's to change.** It was built in this organization, independently of the tree, from a design document the implementer doesn't have. The tree's build depends on it as a test library, reads its results, and never edits, patches or forks it.

---

## 2. What the tree's build expects of the suite

**Why check before relying on it.** The suite's module, class and configuration names are its own, and this document can't know them. It states what the build needs as behavior instead. Before slice 1, confirm each row below against the suite, and record what you found in `SLICES.md` under slice 1.

**Reading the suite.** Confirming these rows needs the suite's sources: a sources jar alongside its artifact, or read access to its repository. If only compiled classes are published, map fixtures by class and test display names, and record that the mapping is unconfirmed.

**What a missing row means** depends on the row. A **blocking** row that doesn't hold stops slice 1: record it in `QUESTIONS.md`, with what you found, for the suite's owner to settle. An **informational** row only changes how you do something, as its last column says.

| Expectation | Kind | What to record, and what to do if it doesn't hold |
|---|---|---|
| **The fixtures are JUnit Platform tests that another build can run** through Surefire or Failsafe, shipped in the suite's main artifact rather than its test sources | Blocking | The artifact's Maven coordinates and version, the JUnit Platform version, and the test engines it uses. If the suite is only a standalone runner, this document's approach doesn't apply: report it, and don't build a substitute |
| **It takes its configuration from system properties**: at least the discovery document's URL and the Kafka bootstrap address, readable after the launcher session opens | Blocking | The property names, and when it reads them. §4 step 6 sets them when the session opens |
| **Each contract fixture maps to one or more test classes** whose names Surefire can select | Blocking | A table: contract fixture number and name, then the suite's class names |
| **Fixture 9's qualified-result case is a separate test method**, and nothing outside it publishes edges, such as class-level setup | Informational | The method's name. If either part doesn't hold, fixture 9 is claimed whole in slice 4 instead of partly in slice 1. Record that, and leave it out of §5's slice 1 row |
| **A run identifier isolates runs**: every id the suite creates is prefixed with one, generated per run or taken from configuration | Informational | Whether it generates one. If it needs one supplied, §4 step 6 sets a fresh value per JVM |
| **A settle timeout bounds its polling** | Informational | Its name and default. §4 leaves it at the default |
| **Fixture 1 gates the rest**: if the profile check fails, the other fixtures are skipped rather than failed | Informational | How a skip is reported. If they fail instead, a fixture-1 failure shows as many failures; read fixture 1's first |
| **The generated law properties** run on an engine of their own, such as jqwik | Informational | The engine and the classes. Surefire runs every engine on the test classpath |
| **A self-test** that starts a real instance against Kafka in Testcontainers | Informational | Where its wiring lives. If there is one, you may read it as the pattern for §4. If not, record that §4 is the first wiring of its kind. Copy no closure logic from whatever it runs against |
| **The suite's packages** | Informational | The packages in its artifact, for the guard test of §3 |

---

## 3. The dependency

- **Test scope only.** The tree's main code never depends on the suite.
- **Pin the version,** and record it in each slice's report. A new suite version is adopted deliberately, in a change of its own, never silently by a version range.
- **A guard test keeps main code clean.** Everything the suite ships, any oracle included, is on the test classpath. A test scans the tree's compiled main classes and fails if any refers to a class in the suite's packages. Give it a control: the same scan over a test class that does refer to the suite must find the reference, so the guard is shown to work. Without the guard, the tree could end up checking its answers against the suite's own oracle.
- **The build tooling** must support launcher session listeners: Surefire 3.0 or later, on JUnit Platform 1.8 or later. Run tests in one forked JVM per execution, reused for all its classes.

---

## 4. Standing the instance up

**What the session needs.** Before the first fixture runs, there must be a Kafka broker, the ingest topic, a ready tree instance, and the suite's configuration pointing at both. All of it lives in the tree's test sources.

1. **A `LauncherSessionListener`,** registered in `META-INF/services/org.junit.platform.launcher.LauncherSessionListener`, does the work when a launcher session opens, but only when a system property set by the Surefire executions that run the suite asks it to. That way a unit-test run from an IDE doesn't start Docker. A launcher may open more than one session per JVM, so the setup runs once per JVM, behind a static guard, and a second session reuses it.
2. **Kafka.** Start a single-broker Kafka container in KRaft mode, with a pinned image version. Testcontainers' cleanup removes it when the JVM exits.
3. **The topic.** With an `AdminClient`, create the conformance profile's ingest topic (`conformance-edges`) with three partitions and `cleanup.policy=compact`. Creating the topic is the deployer's job, not the instance's (*brief* §7.6), and here the listener is the deployer. Fixture 1 checks it.
4. **The instance,** started in process through the same entry point `java -jar` uses, with the whole stack running: lease, consumer and HTTP. Pass its settings explicitly to that entry point, not as JVM-wide system properties (*brief* §10.1). The suite's configuration is set JVM-wide in step 6, and the tree reads a setting of the same name, `KAFKA_BOOTSTRAP`. Explicit settings keep the two from mixing.

   | Setting | Value |
   |---|---|
   | `TREE_CONFIG` | the default, the conformance profile |
   | `TREE_HTTP_BIND` | `127.0.0.1:0`, so the instance binds a free port |
   | `TREE_ADVERTISED_URL` | not set: it defaults from the bound port, and the listener reads it back from the handle the start returns (*brief* §3.1, §10.1) |
   | `KAFKA_BOOTSTRAP` | the container's bootstrap address |
   | `TREE_CONSUMER_GROUP` | unique to this JVM, so no other instance shares its partitions (*brief* §5.1) |
   | `TREE_STORE` | `memory`, or `cassandra` in slice 7's second execution (§6) |

5. **Wait until it is ready.** Every start of the in-memory store is a rebuild (*brief* §5.1), and the suite doesn't retry `not_ready`. Read the discovery document, which is served during a rebuild (*brief* §7.1). Then `POST` `{"data": []}` with `Content-Type: application/vnd.api+json` to any closure's `of` link, until it answers `200` rather than `503`. Wait at most 60 seconds, and fail the session clearly if it never does.
6. **Configure the suite.** Set its configuration as system properties, under the names recorded in §2: the discovery URL is the advertised URL followed by `discovery`, and the Kafka bootstrap address is the container's. Set a fresh run identifier only if §2 found the suite needs one supplied, and leave the settle timeout at its default. This must happen before any fixture runs, and doing it when the session opens guarantees that.
7. **Shut down** the instance, through its handle, in a JVM shutdown hook.

**One instance serves every fixture in the run.** The suite isolates fixtures with its run identifier, so it never needs an empty topic or a fresh instance.

---

## 5. Claiming fixtures slice by slice

**Why an explicit list.** Without one, every fixture runs as soon as fixture 1 passes, and the build stays red until the last slice. So each slice adds the fixtures it claims to Surefire's configuration in the tree's `pom.xml`:

- `dependenciesToScan` names the suite's artifact, so Surefire finds the fixtures inside it.
- `<includes>` lists the tree's own test classes, then the suite's classes for every fixture claimed so far, from §2's table. An explicit `<includes>` replaces Surefire's default patterns, so the tree's own tests need a pattern of their own, and every one of their classes must match it.
- `<excludes>` holds slices 1–3's one method-level exclusion, fixture 9's qualified-result case, in Surefire's `Class#method` form. That applies only if §2 found the case separable. Otherwise fixture 9 joins whole in slice 4.

| Slice | Fixtures added | Notes |
|---|---|---|
| 1 | 1 profile; 9 errors, except its qualified-result case | Fixture 1 must pass for anything else to run |
| 2 | 5 far end; 6 identity; 7 hostile references; 8 set argument; 10 result size; 11 edge lifecycle; 12 authorship; 16 ingest kind | |
| 3 | 2 type restriction; 13 edge id; 14 reordering | |
| 4 | 4 cardinality; fixture 9 whole, the exclusion removed | |
| 5 | 3 cycle | |
| 6 | the generated law properties; 15 concurrency | From here, one pattern can select every fixture |
| 7 | everything, in both executions of §6 | |

**If method-level exclusion doesn't work** with the Surefire and JUnit versions in use, leave fixture 9 out of `<includes>` until slice 4. In slices 1–3, run its other methods with a recorded `mvn verify -Dtest=…` command. That command is then part of the slice's acceptance alongside the plain `mvn verify`, and the slice report says so.

Fixtures are otherwise claimed whole. A fixture whose cases are generated, or built from shared graphs, can't be split by method.

---

## 6. Slice 7: two executions

**Why twice.** The in-memory store and the Cassandra store implement the same storage port, and both must conform, both to the suite and to the unit tests. The listener starts one instance per JVM, behind its static guard, and the suite may read its configuration only once per JVM. So each store gets its own Surefire execution, in a JVM of its own. A system property set per execution tells the listener and the unit tests which store it is for.

- **The first execution, `memory`,** runs as in slices 1–6: the suite's fixtures, and every unit test, with only the in-memory `TreeFactory` active (`tree-test-spec.md` §5.3).
- **The second execution, `cassandra`,** starts a Cassandra container as well, pinned like Kafka. The listener passes `TREE_CASSANDRA_CONTACT_POINTS`, `TREE_CASSANDRA_LOCAL_DC` and `TREE_CASSANDRA_KEYSPACE` to the instance, and also publishes them as system properties for the unit tests' Cassandra factory. That factory is active only in this execution, and only the Cassandra factory is active here. So it runs the suite's fixtures against a Cassandra-backed instance, and every unit test again, those built on `TreeFactory` now over Cassandra. Tests that touch no store run in both executions, which is cheap and harmless.

**Who creates the schema** — the instance at startup, or the listener and the factory from *brief* §4.1 — is the implementer's decision, recorded in the brief's decisions log. Each Cassandra `TreeFactory.create` needs a fresh, empty store: a keyspace of its own, or truncated tables.

---

## 7. When a fixture fails

- **Read the assertion first.** The suite reports what it expected and what it got, and the fixture's description in contract §7 says what it is for.
- **The suite decides where the contract is silent** (*brief* §1). If a failure seems to contradict the contract, record it in `QUESTIONS.md` with the assertion message and the contract rule, and follow neither until it is settled.
- **Never edit, exclude or skip a fixture** beyond the slice table of §5, and never raise the settle timeout to make a fixture pass. A fixture that only passes with a longer timeout is reporting a real problem: the instance is applying events too slowly, or not at all.
- **A failure of fixture 1** usually means the discovery document or the topic is wrong, not the closure logic.

---

## 8. What this replaces

**Hand-written copies of the fixtures are no longer part of the plan.** The tree's acceptance tests are the suite's own fixtures, run by this wiring, together with the unit tests of `tree-test-spec.md`. The project owner may still run the suite against a deployed instance, for example before a release. That is a confirmation, not part of a slice's acceptance.
