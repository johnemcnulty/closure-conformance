# Tree unit test specification

**Status.** A companion to the *Tree implementation brief*, replacing the earlier forest test specification. The brief says what the tree does. This document says how the tree's own tests show that it does it, in enough detail to write them without any other source.

**Where it sits.** The contract governs, then the brief, then this document, then the design extract. If this document seems to disagree with the brief, report it rather than choosing one (*brief* §1). Section references prefixed *brief* are to the brief, and L*n*/W*n* are contract rules.

---

## 1. What the tests are for

**Why test the inside of an instance that passes the conformance suite?** The suite checks the tree only through its public surface, and the contract keeps it that way on purpose: it may not branch on the kind of implementation, look at storage, or judge cost (contract §1.2, §7). So a green suite says that the answers were right in the states it drove. It can't say two things the tree's value depends on:

- **The forest stays a forest, and recovers.** Hops across extra edges keep answers correct even when the materialized structure is wrong. A tree that never re-materializes a resource after a ring breaks answers every query correctly, by hopping, and the suite stays green. Meanwhile the instance degrades, one stranded edge at a time, into a slow emulation of a DAG. That defeats the reason the tree exists.
- **Correctness in states the suite never creates.** The tree's hard cases depend on arrival order: a second parent before or after the first one leaves, a ring broken from either side, a crash part-way through a move. The suite drives only a handful. The read algorithm is complete in *any* state where the invariants hold (*brief* §7.3), so checking the invariants after every event covers states that checking answers in sampled states can't.

The tests therefore come in two parts:

- **Part A, component tests** (§3): the ordinary unit coverage any module of this kind needs. Each component is tested against the brief's rules for it.
- **Part B, failure-mode tests** (§4): tests aimed at the specific ways the tree can be wrong while the suite stays green. Most of them run through a narrow test contract (§5), so they can be written and checked independently of the implementation and then run against it.

The conformance suite itself runs in the same build (`suite-integration-spec.md`), so nothing here re-tests what its fixtures check through the public surface.

---

## 2. Ground rules

These apply to every test in both parts.

- **Expectations come from outside the code under test.** An expected value is a literal from this document or the brief, the result of an oracle that recomputes from the edges, or the reference model's answer (§7). It is never computed with a production helper. A test that derives expected paths with the same function the code uses passes whether or not that function is right.
- **Oracles live in test sources only** and stay naive: recomputed from scratch on every call, with no index and no cache, so they can be checked by reading. Main code never calls them.
- **Observations report what is stored.** Where a test reads state, it reads the stored rows, never a value recomputed from other stored rows.
- **Determinism.** Every randomized test takes a seed, prints it on failure, and can replay one seed from a system property.
- **Both stores.** Every Part B test, and every Part A test of a component that touches storage, runs over the in-memory store and, from slice 7, again over Cassandra (*brief* §10.1).
- **Naming.** Test names say what is asserted and carry the rule or failure-mode id in a trailing comment, e.g. `brokenRingRematerializes() // FM1, brief §6.2`.
- **A test that looks wrong is reported, never edited to pass** (`CLAUDE.md`).

---

## 3. Part A — component tests

**What this part covers.** Each component below has one test class or a small group of them. The cases listed are the minimum. The implementer adds whatever else its own code needs, under the same ground rules. The slice column says when each group must exist (*brief* §10.3).

### 3.1 Configuration and discovery (slice 1)

| Case | Source |
|---|---|
| The conformance profile loads, and the discovery document rendered from it equals contract A.6 with links masked: lists compared as sets, nothing extra emitted | *brief* §3.1, §7.6 |
| Each startup refusal of *brief* §3.1, one test per bullet, including a closure admitting both directions of one type only once its pairs are normalized (`(fed_by, forward)` beside `(feeds, forward)`) | L2, L3, W4, W23 |
| A pair given under the unauthored name is published under the authored name with its direction flipped | W4 |
| Settings are read from an injected environment and property map, never from the JVM's own, so a test isn't affected by what the suite wiring sets JVM-wide. Each deployment setting's default; `KAFKA_CLIENT_SECURITY_PROTOCOL` maps to `security.protocol`; a system property overrides its environment variable; `TREE_ADVERTISED_URL` defaults to `localhost` on a wildcard bind | *brief* §3.1 |
| Direction classification: under the profile, `contents`, `consumers`, `dependents` are downward and their inverses upward; a closure admitting `(holds, forward)` and `(tracks, reverse)` is mixed | *brief* §3.2 |

### 3.2 Codec and path encoding (slice 1 for the codec, slice 2 for paths)

| Case | Source |
|---|---|
| Every edge-id and canonical-reference vector of *brief* §3.3, both directions | L8, L9, W21 |
| A round-trip property over generated references whose ids draw from `/ : % > ( )`, space, quotes, non-ASCII, astral characters and characters below `/`: parse(write(x)) = x for references, edge ids and path segments | L9, L10 |
| Malformed references (empty type, empty id, `:` in a type) are refused by the codec | L7, L9 |
| Every path vector of *brief* §3.3, including `omega:a%2Fb` | *brief* §3.3 |
| **Range bounds.** For a parent `P` and generated siblings whose ids extend `P`'s last id with characters below `/` (`-`, `.`, `!`, space): the store's raw range read `[P, P+'0')` returns `P`, its subtree, *and* those siblings, the same rows in the same order over both stores; the downward read then returns exactly `P` and its subtree, the siblings discarded by the `P + '/'` prefix test | *brief* §7.3 |
| The in-memory store orders paths by unsigned UTF-8 bytes, so both stores return the same rows in the same order | *brief* §10.1 |

### 3.3 Record validation and rejection (slice 2)

| Case | Source |
|---|---|
| One rejected record per row of *brief* §5.2's table, each built from a valid record by breaking that one check | W15–W28 |
| Each parse failure listed in *brief* §5.2 is a rejection, never an exception out of the consumer | W27 |
| A `delete` carrying `scope` and an unrelated attribute is honored | W20 |
| A null-valued record is a tombstone: nothing derived, offset committed | W32 |
| A rejection is logged with its offset and reason, and counted | *brief* §5.3 |

### 3.4 Placement and moves (slices 2–3)

| Case | Source |
|---|---|
| `inSubtree` as a string test, including a sibling whose id extends the other's with a character below `/`, which is not beneath it | *brief* §6.2 |
| A first parent is materialized; a second is extra; a parent lying beneath the child is extra | *brief* §6.2 |
| Promotion takes the byte-least eligible `(parent, type)` | *brief* §6.2, D8 |
| `parentEndpoint: object`: `x fed_by y` makes `y` the parent | *brief* §3.2 |
| A delete of an edge never held, and a redelivered delete after its endpoints are gone, change nothing and don't fail | W31, *brief* §6.1 |
| A resource that loses its last edge loses its rows in both path tables and leaves every static set | *brief* §6.1, F4 |

### 3.5 Reads (slices 2–3)

| Case | Source |
|---|---|
| Unknown argument → `∅`; known argument → itself included | L15, L16 |
| Upward read: stops at the first unadmitted segment | *brief* §7.3 |
| Upward read hops across an admitted extra parent edge (slice 3) | *brief* §7.3 |
| Downward read: prunes at the first unadmitted segment, and discards siblings that extend the id | *brief* §7.3 |
| Downward read hops across an admitted extra child edge (slice 3) | *brief* §7.3 |
| A resource pruned by type is still reached by a hop across an admitted extra edge (slice 3) | *brief* §7.3 |
| Mixed closures answer by traversal (slice 3) | *brief* §7.3 |
| A set argument: deduplicated, union of singles, `meta.of` deduplicated | L21, *brief* §7.2 |

### 3.6 Cardinality (slice 4)

| Case | Source |
|---|---|
| In range only for known resources of a listed type; `alpha` and `omega` are never in `one-holder`'s range | W4, *brief* §6.4 |
| Below the minimum, including a `beta` known only through a `notes` edge; above the maximum; the subject end mirrors the object end | *brief* §6.4 |
| Extra and materialized edges count alike | *brief* §6.4 |
| Every rule is re-evaluated at both endpoints after an event of any type | *brief* §6.4 |
| The qualification: `sources` the first ten breaching references in byte order, `total` the full count, attached only when the closure admits the rule's edge type in either direction | L27, L28, W10 |

### 3.7 Cycles and the diagnostic (slice 5)

| Case | Source |
|---|---|
| Detection when the edge's parent is already beneath its child, and when it isn't but is reachable downward | *brief* §6.5 |
| A self-loop is a cycle | W36 |
| Tarjan's algorithm runs iteratively: a 100,000-resource ring doesn't overflow the stack | *brief* §6.5 |
| The cycle id is the byte-least member; a cycle whose key persists keeps its `since`, even when its edges change | *brief* §6.5, D8, D25 |
| A uniformly directed closure reports a cycle only if its admitted types close one; `contents` doesn't report a `fed_by` cycle | L27 |
| A mixed closure finds a cycle no parent/child component contains: `A holds B` with `A tracks B`, read as `holds` forward and `tracks` reverse | *brief* §6.5 |
| The diagnostic's shape: `meta.closure`, `meta.of`, `meta.cycles` always present; edges as `{type: edge, id}` objects in authored orientation; no `data` | W36, *brief* §7.5 |

### 3.8 The HTTP surface (slice 1, qualifications from slice 4)

| Case | Source |
|---|---|
| One test per row of *brief* §7.1's table, in its order: the first failing check decides | *brief* §7.1 |
| `Content-Type` absent gives `415`; parameters are ignored when matching | W6 |
| Several malformed arguments give one `reference_malformed` each, with `source.pointer` and `meta.argument` as received | W11, D18 |
| `not_ready` carries `Retry-After`; discovery is still served during a rebuild | W35, D23 |
| A reader starts refusing within one second of `rebuilding` being set | *brief* §7.1 |
| `meta.qualifications` is always present, empty when nothing qualifies | W9 |

### 3.9 Consumer, lease and rebuild (slices 1 and 7)

These need fakes of the Kafka consumer and the lease, owned by the implementer.

| Case | Source |
|---|---|
| On joining: `ready` resumes from committed offsets; absent or `rebuilding` runs the rebuild | *brief* §5.1 |
| `OffsetOutOfRangeException` and `NoOffsetForPartitionException` while `ready` start a rebuild; during a rebuild a partition without an offset seeks to the beginning | *brief* §5.1 |
| An offset is committed only after its event's writes are acknowledged; a failed write leaves it uncommitted | *brief* §5.1 |
| A failed lease renewal closes the consumer at once | *brief* §5.1 |
| The consumer's timeouts are no longer than the lease duration | *brief* §5.1 |
| Rebuild: `not_ready` throughout, the cache window waited out before clearing, the lease row and instance state untouched by the clear, `ready` only after every recorded end offset; a crash mid-rebuild restarts it | *brief* §8 |
| After a rebuild, every answer equals the answer before it | *brief* §8 |

---

## 4. Part B — failure modes the suite can't see

**What this part covers.** Each failure mode below is a way the tree can be wrong while every suite fixture still passes. The table says why the suite can't see it and which test catches it. The tests are specified in §8–§12.

| # | Failure mode | Why the suite can't see it | Caught by | Slice |
|---|---|---|---|---|
| FM1 | The forest doesn't recover: after a ring breaks or a materialized parent goes, a resource stays a root, or keeps a worse parent, though a valid one exists | Hops keep every answer correct. Only cost suffers, and the contract excludes cost | Convergence check (§8.1), traces T2, T4 (§11), broken model "no old-root re-check" (§12) | 3 |
| FM2 | The materialized structure is invalid but still answers: a parent materialized beneath its child, a promotion from inside the subtree, a resource with two rows or none, a row in the wrong partition | Answers may stay right until a later event or read meets the damage | Structural oracle after every event (§8.1), traces T3, T5, broken models (§12) | 2–3 |
| FM3 | Stale static sets: a resource missing from `extra_tails` or `extra_heads`, so a later read misses a hop; or listed when it shouldn't be, so reads hop needlessly | The miss shows only if some later read needs that hop. The surplus costs reads but never changes an answer | Exact F4 check after every event (§8.2) | 3 |
| FM4 | Anomaly records drift: a cycle record outlives its cycle, a breach isn't cleared, `anomalous_refs` disagrees with the records, or `since` resets while the anomaly stands | Shows only when a result happens to include the affected resource; `since` is never exposed | Exact F5 check after every event (§8.2), qualification check (§8.4) | 4–5 |
| FM5 | A read is wrong only after particular arrival orders, through a mistake in hop logic or pruning | The suite drives few orderings, and none aimed at these | Read check after events of the property test (§8.3, §9) | 3 |
| FM6 | A crash part-way through a move leaves stale rows, loses part of the subtree, or leaves the journal non-empty | The suite never crashes the instance | Crash injection at every move step (§10.2) | 2 |
| FM7 | A read that overlaps a move misses a resource that exists both before and after it | The suite can't time a read into the middle of a move | Reads at every move step (§10.1) | 2 |
| FM8 | A redelivered event re-decides placement or moves a subtree again | The answers are the same either way | Redelivery stability (§9.3) | 3 |
| FM9 | A deposed leader's stale writes survive, or the audit fails to repair them | The suite never runs two leaders | Fencing tests (§13) | 7 |
| FM10 | The instance answers from a store that doesn't reflect what it has consumed: wrong start decision, or answers during a rebuild | The contract leaves W35 untested by the suite | §3.9, rebuild tests (§13) | 1, 7 |

**Why nothing measures cost directly.** FM1 and FM3 are about cost, but no test counts reads or hops. That would need instrumentation the brief doesn't require, and it isn't needed: when the edges form a proper tree, convergence means no edge is extra, and F4's exactness means neither `extra_tails` nor `extra_heads` lists anything. So the steady state takes no hops, and the two checks together guarantee it.

---

## 5. The test contract

### 5.1 What it is for

**Why a contract between tests and implementation?** The failure-mode tests are the most valuable tests the tree has, and the least likely to be written well by the same hand that writes the code, because they encode the same understanding. A small Java contract lets them be written and proven first, against the reference model, then run unchanged against the implementation through a thin adapter. Two people, or two agents, can then develop the tests and the implementation independently.

**Where the line is.** The contract observes and drives. It doesn't design. It names only what the brief already defines as observable state, which is the tables of *brief* §4.1 in decoded form, and only the procedure steps the brief already defines, which are the move steps of *brief* §6.3. It doesn't name production classes, the storage port, algorithms or threading. The implementer remains free to build the tree however the brief allows. The one requirement it places on main code is a seam at the move steps, in §5.4.

**Ownership.** The contract's types are transcribed from §5.3 into the implementation repository's test sources, in a package of their own (`…tree.testcontract`). The failure-mode tests and the reference model depend only on that package. The adapter is the only class that depends on both the contract and the implementation. Neither side edits the contract to suit itself: a change goes through whoever owns this document.

### 5.2 Terms

- **Edges are in subject/object terms**, as ingested. A test that needs parent and child maps them through the configuration's `parentEndpoints`. Under the forest configuration (§6.1) every type has `parentEndpoint: subject`, so the parent is the subject.
- **A path** is decoded: a list of segments, root first. The root's segment has no edge type. Every other segment names the edge type that reaches its resource. In this document a path is written with ids only, such as `/NE/S1/C`.
- **A partition** is identified by its `root_ref`.
- **Byte order** of references is the unsigned byte order of the UTF-8 canonical form `type:id`. Edges compare by subject, then edge type, then object, each in byte order. This is the brief's tie-break wherever a choice must be deterministic (D8).

### 5.3 The types

```java
// Test sources only, package <base>.tree.testcontract.
// Imports: java.util.*, java.time.Instant, java.util.function.BiConsumer,
// static java.nio.charset.StandardCharsets.UTF_8.

/** A resource reference. Ordered by the unsigned UTF-8 bytes of canonical(). */
public record Ref(String type, String id) implements Comparable<Ref> {
    public String canonical() { return type + ":" + id; }   // for ordering in tests; not the production codec
    @Override public int compareTo(Ref other) {
        return Arrays.compareUnsigned(canonical().getBytes(UTF_8), other.canonical().getBytes(UTF_8));
    }
}

/** An edge as ingested: subject, authored edge type, object. Ordered by subject, edge type, object. */
public record Edge(Ref subject, String edgeType, Ref object) implements Comparable<Edge> {
    @Override public int compareTo(Edge other) {
        int c = subject.compareTo(other.subject);
        if (c == 0) c = Arrays.compareUnsigned(edgeType.getBytes(UTF_8), other.edgeType.getBytes(UTF_8));
        return c != 0 ? c : object.compareTo(other.object);
    }
}

public record EdgeEvent(Kind kind, Edge edge) {
    public enum Kind { SNAPSHOT, DELETE }
}

/** One path segment; edgeType is null for the root's segment. */
public record Segment(String edgeType, Ref ref) {}

/** A stored path row: the partition it is stored in, and its decoded segments. */
public record StoredPath(Ref partition, List<Segment> segments) {}

public record StoredEdge(Edge edge, boolean extra) {}

public record Statics(Set<Ref> extraTails, Set<Ref> extraHeads, Set<Ref> anomalousRefs) {}

/** A tree_anomaly row. cycleEdges are edge references, "edge:" plus the edge id (brief §3.3). */
public record AnomalyRecord(Ref resource, String code, String detail,
                            Set<Ref> sources, Set<String> cycleEdges, Instant since) {}

/** A tree_anomaly_by_age row. */
public record AnomalyIndexEntry(Instant since, Ref resource, String code, String detail) {}

/** One qualification as returned; rule, sources and total are null for cycle_detected. */
public record Qualification(String code, String rule, List<Ref> sources, Integer total) {}

public record OfResult(Set<Ref> data, List<Qualification> qualifications) {}

/** One cycle group of the diagnostic; edges are bare edge ids, as the response carries them, unlike AnomalyRecord.cycleEdges. */
public record CycleGroup(Set<Ref> members, Set<String> edges) {}

/** The steps of a subtree move, brief §6.3. A hook runs after the named step completes. */
public enum MoveStep { JOURNALED, FLAGS_APPLIED, NEW_ROWS_INSERTED, SWITCHED, OLD_ROWS_DELETED }

/** Thrown by a hook to abandon the procedure where it stands, as a crash would. */
public final class SimulatedCrash extends RuntimeException {}

/** What every tree under test offers, the reference model included. */
public interface ForestView {
    void apply(EdgeEvent event);
    Set<StoredEdge> edges();
    Optional<StoredPath> pathOf(Ref ref);                  // from tree_path_by_ref; empty if unknown
    OfResult of(String closure, Set<Ref> arguments);
}

/** The implementation's stored state, for the checks the reference model can't support. */
public interface StoreView extends ForestView {
    /** Applies the event, calling the hook after each step of each move it makes. */
    void apply(EdgeEvent event, BiConsumer<MoveStep, StoreView> hook);
    Set<Ref> partitions();                                  // every root_ref with at least one clustering row, not statics only
    List<StoredPath> rows(Ref partition);                   // every tree_path row there, in stored order
    Statics statics(Ref partition);
    Set<AnomalyRecord> anomalies();
    Set<AnomalyIndexEntry> anomalyIndex();
    int pendingMoves();                                     // rows in tree_move_journal
    List<CycleGroup> cycles(String closure, Set<Ref> arguments);
}

/** Creates a fresh, empty instance from a domain configuration (brief §3.1). */
public interface TreeFactory {
    String name();                                          // "memory", later "cassandra"
    StoreView create(String domainConfigJson);
}
```

The reference model implements `ForestView` only, and is built directly from the same domain configuration (`new ReferenceModel(domainConfigJson)`), not through a factory. Its `pathOf` gives the root as the partition, and its `of` returns no qualifications.

Factories are found with `java.util.ServiceLoader<TreeFactory>`, so the failure-mode tests run against every active store without naming the adapter. Which factories are active is set per Surefire execution by a system property: only `memory` until slice 7, then `memory` in one execution and `cassandra` in the other (`suite-integration-spec.md` §6).

### 5.4 What the adapter must do

| Operation | Obligation |
|---|---|
| `create` | A new instance over an empty store, with no Kafka consumer and no HTTP server: the core of *brief* §10.1. Each call is independent of every other |
| `apply(event)` | Build a real, validated ingest event and call the write procedure in process, with a synthetic offset. Every step runs: no shortcut past placement, statics, anomalies or the journal. A rejected event throws |
| `apply(event, hook)` | As `apply`, calling `hook(step, this)` after each step of each move, in order. A hook may read through `this`. If it throws `SimulatedCrash`, the procedure stops where it is and the exception propagates, with nothing rolled back and nothing finished |
| `edges`, `pathOf`, `rows`, `statics`, `anomalies`, `anomalyIndex`, `pendingMoves` | Read the stored rows through the storage port and decode them. **Never recompute** a value from other stored values: an adapter that derived paths from edges would let the oracle check itself |
| `of`, `cycles` | Call the real read path and diagnostic in process, by closure name |

**The seam in main code.** `apply(event, hook)` needs the move procedure to call out after each step. Give the procedure a listener, with a no-op default, injected where the core is built. That is the only thing the contract asks of main code, and it changes no behavior when the listener does nothing.

### 5.5 What stays outside the contract

**Fencing, the consumer, the lease and rebuild** are tested by the implementer's own tests (§3.9, §13), against its own internals. Expressing them through the contract would mean prescribing how the store applies timestamps, or how the consumer is built, which is design, not observation.

---

## 6. Configurations

### 6.1 The forest configuration

**Why a configuration of its own.** The forest tests need edge types with a trivial parent mapping and closures in every direction. The conformance profile has neither, so this configuration is the domain for Part B. Edge types `h` and `s` are what the property test generates, and `hosts` is what the traces use. Every type has `parentEndpoint: subject`, and every resource has type `node`.

```json
{
  "id": "forest-tests",
  "ingestTopic": "forest-tests",
  "closures": [
    { "name": "hDown",     "inverse": "hUp",      "admits": [ { "edgeType": "h", "direction": "forward" } ] },
    { "name": "hUp",       "inverse": "hDown" },
    { "name": "sDown",     "inverse": "sUp",      "admits": [ { "edgeType": "s", "direction": "forward" } ] },
    { "name": "sUp",       "inverse": "sDown" },
    { "name": "hsDown",    "inverse": "hsUp",     "admits": [ { "edgeType": "h", "direction": "forward" },
                                                              { "edgeType": "s", "direction": "forward" } ] },
    { "name": "hsUp",      "inverse": "hsDown" },
    { "name": "hDownSUp",  "inverse": "hUpSDown", "admits": [ { "edgeType": "h", "direction": "forward" },
                                                              { "edgeType": "s", "direction": "reverse" } ] },
    { "name": "hUpSDown",  "inverse": "hDownSUp" },
    { "name": "hostsDown", "inverse": "hostsUp",  "admits": [ { "edgeType": "hosts", "direction": "forward" } ] },
    { "name": "hostsUp",   "inverse": "hostsDown" }
  ],
  "edgeTypes": [
    { "name": "h",     "authored": "h" },
    { "name": "s",     "authored": "s" },
    { "name": "hosts", "authored": "hosts" }
  ],
  "cardinalityRules": [],
  "parentEndpoints": { "h": "subject", "s": "subject", "hosts": "subject" }
}
```

An inverse closure admits each of its partner's pairs in the opposite direction (contract §2.1), so `hUp` admits `(h, reverse)` and `hUpSDown` admits `(h, reverse)` and `(s, forward)`. None admits both directions of one type (L2). In parent/child terms, `forward` follows an edge from parent to child, written DOWN below, and `reverse` follows it from child to parent, written UP.

**The eight standard closures** of the read check are the first eight above: `hDown`, `hUp`, `sDown`, `sUp`, `hsDown`, `hsUp`, `hDownSUp`, `hUpSDown`. The last two are mixed, so traversal is exercised from slice 3.

### 6.2 The forest configuration with rules

From slice 4, the property test runs a second time under the forest configuration plus two rules, so that F5's cardinality part and the qualification check have breaches to see:

```json
  "cardinalityRules": [
    { "id": "one-h-parent", "edgeType": "h", "objectTypes": ["node"],
      "minEdgesPerObject": 1, "maxEdgesPerObject": 1 },
    { "id": "s-fanout", "edgeType": "s", "subjectTypes": ["node"], "maxEdgesPerSubject": 2 }
  ]
```

---

## 7. The reference model

**What the model is for.** The placement rules (*brief* §6.2) are short, but their consequences in odd states, such as a broken ring or a parent that lies beneath its child, are not obvious. The model is those rules and the reads in executable form, in memory, with nothing else. It holds the same state the implementation does: the edge set, and which parent edge each resource materializes. Write it first, and use it three ways:
- to prove the Part B tests before the real tree exists, which is what lets them be developed independently;
- as the source of exact expected paths for new traces, since promotion is deterministic;
- as the reading of a rule when the brief's prose is unclear. If the model and the brief seem to disagree, report it.

It isn't a model of the write procedure. It returns early on a redelivered event, which the implementation must not do (*brief* §5.1). It has no static sets, anomaly records, journal or fencing. It implements `ForestView` (§5.3). Internally it works in parent/child terms, mapping subject and object through the configuration's `parentEndpoints`. Under §6's configurations every parent is the subject, so "the least candidate in edge order" below is the byte-least `(parent, type)` the brief requires.

### 7.1 State and derived queries

```
edges:        set of Edge
materialized: map Resource -> Edge         # the child's materialized parent edge, if any

known(x)        = some edge has x as parent or child
pathOf(x)       = if not known(x): nothing
                  else walk up from x through materialized[...] to a resource with no entry,
                  collecting segments; the root's segment has no edge type
isExtra(e)      = e in edges and materialized[e.child] != e
inSubtree(q, x) = walking up from q through materialized[...] reaches x   # true when q = x
rootOf(x)       = the first segment of pathOf(x), or x itself if unknown
```

If walking up ever revisits a resource, the materialized edges form a cycle. That is a bug in the model or its hooks, and it should throw.

### 7.2 Writes

```
apply(snapshot e = (p, t, c)):
    if e already in edges: return                       # redelivery: the stored flag stands
    add e to edges
    if c has no materialized entry and mayMaterialize(p, c):
        materialized[c] = e

apply(delete e = (p, t, c)):
    if e not in edges: return                           # nothing left to do
    remove e from edges
    if materialized[c] == e:
        oldRoot = rootOf(p)                             # taken before c is detached: p stays in that tree
        remove materialized[c]
        enforceRootRule(c)                              # promotion
        recheckOldRoot(oldRoot)                         # the ring case

mayMaterialize(p, c) = not inSubtree(p, c)
mayPromote(p, c)     = not inSubtree(p, c)

enforceRootRule(x):
    if x has a materialized entry: return
    candidates = edges with child x whose parent satisfies mayPromote(parent, x)
    if any: materialized[x] = the least candidate in edge order       # byte-least (parent, type)

recheckOldRoot(r):
    if known(r): enforceRootRule(r)
```

`mayMaterialize`, `mayPromote`, `recheckOldRoot` and "follow extra edges" (below) are separate hooks only so that §12's broken models can break one rule at a time.

### 7.3 Reads

**The model answers reads the way the tree does,** by materialized structure plus hops across extra edges, so that a model with wrong hops would fail the read check too.

```
of(closure, {x}):
    if not known(x): return {}
    if closure is downward: return descendants(x, closure)
    if closure is upward:   return ancestors(x, closure)
    return bfs(edges, x, closure)                       # mixed: traversal

descendants(x, closure):
    result = {}; todo = [x]
    while todo not empty:
        v = pop(todo); if v in result: continue
        got = [v]; add v to result
        # the range read under v's path, pruned at the first unadmitted segment:
        depth-first from v over materialized edges m with m.parent = u and (m.type, DOWN) admitted:
            if m.child not in result: add it to result and to got, and continue from it
        if followExtraEdges:
            for each u in got, each extra edge e with e.parent = u and (e.type, DOWN) admitted:
                if e.child not in result: push e.child onto todo           # a hop

ancestors(x, closure):
    result = {}; todo = [x]
    while todo not empty:
        v = pop(todo); if v in result: continue
        got = [v]; add v to result
        # split v's path, walking up while each segment's edge type is admitted:
        segs = pathOf(v)
        for i from last index down to 1:
            if (segs[i].type, UP) not admitted: stop
            add segs[i-1].resource to result and to got
        if followExtraEdges:
            for each u in got, each extra edge e with e.child = u and (e.type, UP) admitted:
                if e.parent not in result: push e.parent onto todo         # a hop
```

---

## 8. Oracles

**What the oracles establish.** Each checks the stored state from scratch against the stored edges `E`, as the adapter reports them. Parent and child come from `E` through the configuration's `parentEndpoints`.

### 8.1 The structural oracle (F1–F3, FM1, FM2)

**Against any `ForestView`.** For every known resource `x`:

1. **A path exists** (F1). `pathOf(x)` is present, and its last segment names `x`.
2. **No repeats** (F2). No resource appears twice in `pathOf(x)`.
3. **The materialized edge is real** (F1). If `x` isn't a root, its materialized edge, read from the path itself, is `(resource of the second-to-last segment, edge type of the last segment, x)` in parent/child terms. That edge is in `E` and not extra, and `pathOf` of its parent equals `x`'s path without its last segment.
4. **Every other parent edge is extra** (F1). Every other edge in `E` with child `x` is flagged extra.
5. **Roots are justified** (F3). If `x` is a root, every edge in `E` with child `x` has a parent in `x`'s subtree: `x` itself, or a resource whose path has `x`'s path as a strict prefix, segment by segment.
6. **No unknown resource has a path.** For a sample of references from the scenario's vocabulary that no edge mentions, `pathOf` is empty.

**Against a `StoreView`, additionally** (F1's "exactly one row in each path table", *brief* §4.1):

7. Every row in every partition names a known resource, and every known resource has **exactly one** row across all partitions.
8. That row is in the partition named by the path's first segment, and its segments equal `pathOf(x)`.
9. The journal is empty: `pendingMoves() = 0`.

**Convergence** (FM1). Once the edge set is a proper tree, the forest must be exactly that tree (*brief* §4.2). Given the target tree `T`, run the checks above, then check that every edge of `T` is its child's materialized edge, and that no stored edge is extra.

### 8.2 The storage oracle (F4, F5, FM3, FM4)

**Against a `StoreView`.** F4 and F5 are checked as exact equality with sets computed from scratch, so a surplus fails as surely as a gap.

**F4, static sets.** For every partition `P` returned by `partitions()`, let `R(P)` be the resources with a row in `P`:
- `extraTails(P)` = the resources in `R(P)` that are the parent of some extra edge;
- `extraHeads(P)` = the resources in `R(P)` that are the child of some extra edge;
- `anomalousRefs(P)` = the resources in `R(P)` that have any anomaly record. This one is taken from `anomalies()` rather than recomputed from `E`. F5 checks those records against `E` separately, and this way each part can be staged by slice without the other.

Also, for every partition that held rows earlier in the scenario and holds none now, every static set is empty. The test tracks those partitions itself. In Cassandra a partition whose last row has gone can keep its static columns, so a static set left behind by the last row to leave shows up only here.

**F5, anomaly records.** Compute the expected records from `E`:

```
cycleRecords(E):
    over the parent/child graph of E (all edge types), find the strongly connected components
    for each component C with more than one member, or a single member with an edge to itself:
        id = the byte-least member of C
        edgesAmong = every edge of E with both ends in C, as an edge reference in authored orientation
        for each member m: record (m, "cycle_detected", id, sources = C, cycleEdges = edgesAmong)

breachRecords(E, rules):
    for each rule R and each resource x known in E whose type R lists at the bounded end:
        n = the number of edges of R.edgeType with x at that end
        if (min is set and n < min) or (max is set and n > max): record (x, "cardinality", R.id, sources = {x}, cycleEdges = {})
```

The anomaly records must equal the union, ignoring `since`. The index must hold exactly one entry per open anomaly: `(since, cycleId, "cycle_detected", cycleId)` per cycle, and `(since, x, "cardinality", R.id)` per breach, each `since` equal to its records' `since`.

The edge reference in `edgesAmong` needs the edge id function. The test sources carry their own, independent of the production codec, and check it against *brief* §3.3's vectors.

**`since` carries over** (FM4, *brief* §6.5). Keep the previous event's records. An anomaly is identified by `(resource, code, detail)`. Every key present before and after an event keeps its `since` unchanged, even if its `sources` or `cycleEdges` changed.

**Staging.** F4's `extraTails` and `extraHeads` are checked from slice 3, F5's cardinality part with its `anomalousRefs` entries from slice 4, and F5's cycle part with its entries from slice 5. Until a part's slice, it is skipped by configuration, never weakened.

### 8.3 The read oracle (FM5)

**Every read must equal breadth-first reachability over the stored edges** (L23):

```
bfs(E, x, closure):
    if x is not known in E: return {}                   # L16
    seen = {x}; queue = [x]                             # L15
    while queue not empty:
        v = dequeue(queue)
        for each edge e in E (in parent/child terms):
            if e.parent = v and (e.type, DOWN) admitted and e.child not in seen: add, enqueue
            if e.child = v and (e.type, UP) admitted and e.parent not in seen: add, enqueue
    return seen
```

**A full read check** reads every known resource, plus one reference no scenario mentions (`node:never-mentioned`), under each of the eight standard closures of §6.1, as single arguments. Each `of(...).data` must equal `bfs` exactly.

### 8.4 The qualification oracle (FM4)

**Against a `StoreView`, from slice 4.** For a result `D = bfs(E, x, closure)`:
- **`cardinality`**: for each rule whose edge type the closure admits in either direction, the breaching resources in `D`. If there are any, exactly one qualification for the rule, with `total` their count and `sources` the first ten in byte order.
- **`cycle_detected`** (from slice 5): present exactly once if some member of `D` lies on a cycle of the closure's admitted edges. That is: a strongly connected component of more than one member, or a self-loop, in the graph of the edges the closure admits, followed in their admitted direction (L27).
- **The diagnostic** (from slice 5): `cycles(closure, {x})` holds exactly those components that intersect `D`, each with its members and the admitted edges among them.

The full qualification check runs this for every known resource under every standard closure, under the configuration of §6.2. Scenarios have at most nine resources, so `sources` never reaches its ten-item cut here. That cut is covered by §3.6 and by the suite's fixture 4.

---

## 9. The property test

### 9.1 Scenarios

**Why random scenarios, and why small ones.** The forest's hard states come from orderings. Hand-written cases cover the orderings someone thought of, and random edit sequences cover the rest. Small graphs make second parents and cycles frequent, and failures readable.

A scenario is generated from a seed:

1. **Size.** `n` resources, `node:n0` to `node:n(n-1)`, with `n` uniform in 3..9.
2. **Target tree.** For each `i` in 1..`n-1`, one edge from `n(j)` to `n(i)`, with `j` uniform in 0..`i-1` and type uniform over `{h, s}`.
3. **Chaos.** From an empty current set, take 5..40 steps (uniform). At each step, if the current set is non-empty, with probability 0.4 delete a uniformly chosen current edge. Otherwise add an edge whose parent, child and type are each uniform, parent and child over all `n` resources, so self-loops occur. An add of an edge already present is dropped.
4. **Converge.** Delete every current edge not in the target and add every target edge not current, in random order, so deletes and adds interleave.

The exact random-number generator doesn't matter, but the generator must be deterministic in its seed.

### 9.2 Running a scenario

The harness takes any `Supplier<? extends ForestView>`: one per registered factory, the reference model, and §12's broken models. It applies the `StoreView` checks only when the instance is one. For each scenario, on a fresh instance from each supplier:

1. Apply every chaos event, then every convergence event, one at a time.
2. After each event:
   - `edges()` equals the expected edge set, tracked independently by the test;
   - the structural oracle (§8.1);
   - against a `StoreView`, the storage oracle (§8.2) for the parts the current slice has built;
   - with probability 0.3, a full read check (§8.3), and from slice 4, under §6.2's configuration, a full qualification check (§8.4).
3. After the last event, the convergence check against the target, then a full read check.

Run 2,000 scenarios, seeds 0 to 1,999, in every build, under §6.1's configuration, and from slice 4 again under §6.2's. Allow system properties to widen the run (say to 20,000) and to replay one seed.

**A failure must be reproducible by reading it.** Report the seed, the failed check, and every event applied so far as lines that can be pasted into a trace. Cut that down by hand to the events that matter, take its expected paths from the reference model, never from the implementation under suspicion, and add it to §11 as a permanent regression test.

### 9.3 Redelivery stability (FM8)

**What it guards.** A redelivered `snapshot` must not re-decide placement, and a redelivered `delete` must change nothing, but the procedure must still run in full (*brief* §5.1, §6.1). After each event of a sample of scenarios, apply the same event again, and check that every stored path, extra flag, static set and anomaly record, `since` included, is unchanged.

---

## 10. Move-step tests

**What they guard.** A subtree move changes many rows across two partitions (*brief* §6.3). Its step order is what keeps readers safe (FM7), and its journal is what makes a crash recoverable (FM6). Both are tested through the hook of §5.3, against the `hosts` closures of §6.1.

**Two scenarios, each set up on a fresh instance before its moving event:**

| | Setup | Moving event | Before | After |
|---|---|---|---|---|
| M1, placing a transient root (slice 2) | `+R>X`, `+A>B`, `+B>C` | `+R>A` | `A=/A`, `B=/A/B`, `C=/A/B/C`; extra none | `A=/R/A`, `B=/R/A/B`, `C=/R/A/B/C`; extra none |
| M2, promotion (slice 3) | `+R>S1`, `+R>S2`, `+S1>C`, `+C>P`, `+S2>C` | `−S1>C` | `C=/R/S1/C`, `P=/R/S1/C/P`; extra `S2>C` | `C=/R/S2/C`, `P=/R/S2/C/P`; extra none |
| M3, statics crossing partitions (slice 3) | `+R>X`, `+A>B`, `+B>C`, `+C>B` | `+R>A` | `A=/A`, `B=/A/B`, `C=/A/B/C`; extra `C>B`; partition `A` has `extra_tails={C}`, `extra_heads={B}` | `A=/R/A`, `B=/R/A/B`, `C=/R/A/B/C`; extra `C>B`; partition `R` has `extra_tails={C}`, `extra_heads={B}`, partition `A` has none. From slice 5, `B` and `C` form a cycle, so `anomalous_refs={B, C}` too, in `A` before and in `R` after |

### 10.1 Reads during a move (FM7)

Apply the moving event with a hook that, at every step, reads and asserts:
- **M1:** `of(hostsDown, {A}) = {A, B, C}`; `of(hostsUp, {C})` is `{C, B, A}` or `{C, B, A, R}`; `of(hostsDown, {R})` contains `{R, X}` and is contained in `{R, X, A, B, C}`.
- **M2:** `of(hostsDown, {C}) = {C, P}`; `of(hostsUp, {P})` contains `{P, C, R}` and is contained in `{P, C, S1, S2, R}`; `of(hostsDown, {R}) = {R, S1, S2, C, P}`.

- **M3:** `of(hostsDown, {A}) = {A, B, C}`; `of(hostsDown, {C}) = {C, B}`, by the hop across `C>B`.

Each assertion says the same thing: a resource that exists both before and after the move is never missed, whichever paths the reader resolves to at that moment.

### 10.2 Crashes during a move (FM6)

For each move step `s`, on a fresh instance with the setup applied:
1. Apply the moving event with a hook that throws `SimulatedCrash` at `s`.
2. Redeliver the same event with plain `apply`.
3. Check the structural and storage oracles, including an empty journal and, for M3, the static sets of both partitions, and that the paths and extra flags equal the "After" column.

Also crash at `s` and then apply a *different* event touching neither subtree, such as `+Y>Z` for fresh `Y` and `Z`. Right after it, before any redelivery: the journal is empty, the paths and extra flags equal the "After" column, and the structural and storage oracles hold, because the pending move was finished first (*brief* §6.1). This holds at `JOURNALED` too, since the journal carries the added edge (*brief* §6.3 step 2). Then redeliver the original event, and check the same again.

---

## 11. Worked traces

**What the traces pin down.** The property test checks invariants and reads, but not exact paths, since several forests can satisfy the invariants. The traces fix the exact paths and extra edges at each step for the cases the design turned on. They are the same traces as *brief* §9. Reads are left to the property test.

Every edge has type `hosts` and is written `parent>child`. The steps are:
- **apply / delete:** apply each event in order, running the structural oracle after each;
- **paths:** the known resources are exactly the ones listed, each with the path shown;
- **extra:** the extra edges are exactly the ones listed, or none.

Each trace runs on a fresh instance, against the reference model and every registered factory.

**T1 — a card moves between shelves, the new parent arriving first.**

| Step | Check |
|---|---|
| apply `NE>S1`, `NE>S2`, `S1>C`, `C>P` | paths `NE=/NE`, `S1=/NE/S1`, `S2=/NE/S2`, `C=/NE/S1/C`, `P=/NE/S1/C/P`; extra none |
| apply `S2>C` | paths unchanged; extra `S2>C` |
| delete `S1>C` | paths `NE=/NE`, `S1=/NE/S1`, `S2=/NE/S2`, `C=/NE/S2/C`, `P=/NE/S2/C/P`; extra none |

**T1b — the same move, the old parent's delete arriving first.**

| Step | Check |
|---|---|
| apply `NE>S1`, `NE>S2`, `S1>C`, `C>P` | |
| delete `S1>C` | paths `NE=/NE`, `S1=/NE/S1`, `S2=/NE/S2`, `C=/C`, `P=/C/P`; extra none |
| apply `S2>C` | paths `NE=/NE`, `S1=/NE/S1`, `S2=/NE/S2`, `C=/NE/S2/C`, `P=/NE/S2/C/P`; extra none |

**T2 — a two-cycle forms and clears.**

| Step | Check |
|---|---|
| apply `A>B` | paths `A=/A`, `B=/A/B` |
| apply `B>A` | paths unchanged; extra `B>A` |
| delete `A>B` | paths `A=/B/A`, `B=/B`; extra none. The re-check of the old root `A` promotes `B>A` |

**T3 — a ring under a root.**

| Step | Check |
|---|---|
| apply `A>B`, `B>C`, `B>D`, `C>A` | paths `A=/A`, `B=/A/B`, `C=/A/B/C`, `D=/A/B/D`; extra `C>A` |
| apply `R>A` | paths `R=/R`, `A=/R/A`, `B=/R/A/B`, `C=/R/A/B/C`, `D=/R/A/B/D`; extra `C>A` |

**T4 — a ring broken, and the old root re-checked.**

| Step | Check |
|---|---|
| apply `A>B`, `B>C`, `C>A` | paths `A=/A`, `B=/A/B`, `C=/A/B/C`; extra `C>A` |
| delete `A>B` | paths `A=/B/C/A`, `B=/B`, `C=/B/C`; extra none |

**T5 — the only remaining parent lies beneath.**

| Step | Check |
|---|---|
| apply `R>A`, `A>B`, `B>A` | paths `R=/R`, `A=/R/A`, `B=/R/A/B`; extra `B>A` |
| delete `R>A` | paths `A=/A`, `B=/A/B`; extra `B>A`. `A` must become a root: promoting `B>A` would make the forest cyclic |

---

## 12. Showing the tests have teeth

**Why this check exists.** A failure-mode test that passes everything looks like evidence and isn't. So the property test and the traces must each reject four models with one rule broken each, built by overriding the reference model's hooks:

| Broken model | Hook changed | Failure mode it stands for |
|---|---|---|
| No old-root re-check | `recheckOldRoot` does nothing | FM1: a broken ring never rematerializes (T4) |
| Materializes cycles | `mayMaterialize` always true | FM2: the forest gets a cycle |
| Promotes any parent | `mayPromote` always true | FM2: a resource promotes a parent from its own subtree (T5) |
| No hops | reads don't follow extra edges | FM5: reads miss second parents and cycle-closing edges |

For each, assert that the property test fails within 500 scenarios and that at least one trace fails, except for "no hops", which only the property test's read check is expected to catch. An exception thrown by the model, such as the loop check of §7.1, counts as a failure. If a broken model survives 500 scenarios, report it rather than raising the bound: the scenarios have lost their reach.

The storage oracles have no broken model, since the reference model has no storage. Their teeth come from exact equality: a single missing or surplus entry fails.

---

## 13. Implementation-owned tests

**What these are.** Fencing and rebuild can't be expressed through the test contract without prescribing internals (§5.5), so the implementer writes them against its own design, under the ground rules of §2.

- **Fencing (FM9, slice 7).** Using the Cassandra store, interleave writes from two lease generations out of order: every insert, delete, static-set change and journal write of a later term wins over the earlier term's, whatever the arrival order. Then leave a stale row that the later term never overwrites, run the audit, and check the structural and storage oracles hold afterwards and the repair was logged.
- **Rebuild (FM10, slice 7).** Beyond §3.9: populate an instance, record every answer under every closure, rebuild, and check that every answer is unchanged, the oracles hold, and `since` values are rebuild-time.

---

## 14. Summary by slice

| Slice | Part A (§3) | Part B |
|---|---|---|
| 1 | 3.1, the codec of 3.2, 3.8, and 3.9's rows on the start decision, offset exceptions, lease renewal, timeouts and the rebuild window, against fakes | — |
| 2 | Paths of 3.2, 3.3, single-parent cases of 3.4, the slice-2 rows of 3.5, and 3.9's rows on committing offsets and answers after a rebuild | The test contract and the adapter; the reference model; move-step tests for M1, with the structural oracle (FM6, FM7) |
| 3 | The rest of 3.4 and 3.5 | Property test and traces (FM1, FM2, FM5); F4 statics (FM3); redelivery stability (FM8); M2 and M3; broken models |
| 4 | 3.6 | F5's cardinality part; the qualification check under §6.2 (FM4) |
| 5 | 3.7 | F5's cycle part; `cycle_detected` and the diagnostic in the qualification check (FM4) |
| 6 | — | — (the generated laws are the suite's fixtures) |
| 7 | 3.9 again over the Cassandra store, including resuming from `ready` against a real store | Everything again over Cassandra; fencing and rebuild (FM9, FM10) |

---

## 15. Keeping this specification honest

**The reference model is the arbiter of the forest rules within these tests.** If a rule changes, change the model first, run the tests, then update the traces from what the model does. That change belongs to whoever owns this document, not to the implementer: an implementer who finds a rule wrong reports it, and doesn't change the model, the oracles, the contract or the traces to make the implementation pass.
