# Tree implementation brief

**Status.** Draft, for the first implementation of the tree.

**Targets.** The contract revision in the conformance suite's repository, `closure-conformance/docs/contract-spec.md` as of 13 October 2026, and design document revision `relationship-closure-modules-12oct26-1`. That contract supersedes `module-contract-spec-06oct26-4`, which the first version of this brief targeted. Rule identifiers (`L`n, `W`n) and fixture numbers below are valid against the suite's revision only, since a draft contract renumbers when rules change. Section references (§n) are to the design document unless prefixed *brief* or *contract*; the sections cited are reproduced in `docs/tree-design-extract.md`.

**What changed from the first version.** The suite's revision forbids a closure from admitting both directions of one edge type (L2). It forbids truncation (W7), fixes the canonical serialization and the edge id function (L8, W21), and requires an instance to reject an event whose identifiers disagree with its triple (W25). It honors a `delete` carrying extra attributes (W20), and defines which results a qualification attaches to (L27). It gives `cycle_detected` its code alone, adds a per-closure cycle diagnostic (W36), reshapes cardinality rules (W4), and reduces the error set to three codes (W11). The decisions log (*brief* §11) records each consequence.

---

## 1. How to use this brief

This brief is the one document an implementation session needs open. Everything else stays authoritative but is background:

- **The contract is the specification.** It says what the instance must do at its two boundaries, the ingest topic and the HTTP surface.
- **The conformance suite is the arbiter.** Where the contract is silent and the suite or its reference implementation has an opinion, the suite's behavior decides, and the choice goes in the decisions log (*brief* §11).
- **The design document is rationale.** It explains why the tree has this shape. When it disagrees with the contract, the contract wins and the design document gets corrected.
- **This brief is derived from both** and settles what they leave open. Every rule here cites its source. A question this brief doesn't answer gets decided once, written into the decisions log, and pushed upstream if it reveals an error in either source document. It is not resolved silently in code.

The implementation is Java 21 with Maven, in the suite's repository as module `tree-impl`, with the same stack as the reference implementation: the JDK's `HttpServer` on virtual threads, Jackson and `kafka-clients`, and no framework. Its main code depends on `conformance-model` for the wire codec, as the reference implementation does. It never depends on `conformance-oracle`, which matters because the suite ships the oracle at compile scope. So the tree's tests, which depend on the suite, have the oracle on their classpath, and a guard test asserts the tree's *main* classes never reference it, as `reference-impl`'s `NoOracleTest` does (suite-design §3a, §5).

**`reference-impl` may be read for two things only:** its test wiring (`SelfTestInstance` and the Surefire configuration in its `pom.xml`, *brief* §10.3), and to check what the suite actually expects of a behavior. It is never a design source, and no code is copied from it.

**Wire shapes the suite checks exactly.** Three details are easy to get wrong, and each fails many fixtures at once:
- `meta.qualifications` is always present, an empty array when nothing qualifies (W9).
- In the cycle diagnostic, `meta.closure`, `meta.of` and `meta.cycles` are always present. Each edge in a group is a resource reference object `{"type": "edge", "id": "(s>t>o)"}`, never a bare string (W36).
- A known reference always appears in its own result, and an unknown one yields `data: []`, never an error (L15, L16).

---

## 2. Scope

**What the instance must do.** It consumes edge `snapshot` and `delete` events from one compacted ingest topic. For each declared closure it answers `of` and the cycle diagnostic, through the discovery document's links. Between those two boundaries the contract constrains nothing (contract §1.1), so everything in *brief* §4–§8 is an implementation choice made for performance and simplicity, as long as it never changes an answer.

**The governing constraint.** A tree instance must be indistinguishable from a DAG holding the same edges, by results and qualifications, though not by cost or timing (§3). Every answer must equal breadth-first reachability over the stored edge set under the closure's admitted pairs (L23). That holds in every state the edges can be in, including two parents, cycles, and closures whose pairs run in different directions relative to parents. Paths are an optimization over the edges and never a source of truth.

**Deliberately out of scope for the first implementation:**

| Item | Decision | Why it is safe |
|---|---|---|
| `GET` on `of` links | Not implemented | MAY (W5); the suite uses `POST` |
| A result ceiling | None: the tree never answers `result_too_large` | W7 permits a ceiling but doesn't require one; fixture 10 accepts the whole result |
| Sharding | One updater, shard `global` | §8 lists tree writes under sharding as open |
| Bulk-mode rebuild | Rebuild replays through the ordinary write path | One write path to verify. The one-pass computation of §7.4 is a later optimization behind the same `not_ready` window (*brief* §8) |
| Search, projection, change topic | None | §3.2: the tree has no derived store |

---

## 3. Model and configuration

### 3.1 Configuration

**The instance has two kinds of configuration.** The *domain configuration* says what the instance computes. It is the `attributes` object of the discovery document the instance will publish, flattened, plus the instance `id` and one tree-specific map. Closures, edge types, cardinality rules and the ingest topic take exactly the shape of W4's discovery attributes, so the discovery document is rendered from it, and what the instance reports can't drift from what it computes. The `parentEndpoints` map says, for each edge type's authored name, which end is the parent (§3.4). The *deployment settings* say how the instance is reached and run, and are covered at the end of this section. The conformance profile's domain configuration is:

```json
{
  "id": "conformance",
  "ingestTopic": "conformance-edges",
  "closures": [
    { "name": "containers", "inverse": "contents",
      "admits": [ { "edgeType": "holds", "direction": "reverse" } ] },
    { "name": "contents", "inverse": "containers" },
    { "name": "suppliers", "inverse": "consumers",
      "admits": [ { "edgeType": "fed_by", "direction": "forward" } ] },
    { "name": "consumers", "inverse": "suppliers" },
    { "name": "dependents", "inverse": "dependencies",
      "admits": [ { "edgeType": "holds", "direction": "forward" },
                  { "edgeType": "tracks", "direction": "forward" } ] },
    { "name": "dependencies", "inverse": "dependents" }
  ],
  "edgeTypes": [
    { "name": "holds",  "inverse": "held_by",    "authored": "holds" },
    { "name": "feeds",  "inverse": "fed_by",     "authored": "fed_by" },
    { "name": "tracks", "inverse": "tracked_by", "authored": "tracks" },
    { "name": "notes",  "authored": "notes" }
  ],
  "cardinalityRules": [
    { "id": "one-holder", "edgeType": "holds", "objectTypes": ["beta", "gamma"],
      "minEdgesPerObject": 1, "maxEdgesPerObject": 1 },
    { "id": "tracks-fanout", "edgeType": "tracks", "subjectTypes": ["beta"],
      "maxEdgesPerSubject": 2 }
  ],
  "parentEndpoints": { "holds": "subject", "tracks": "subject", "fed_by": "object", "notes": "subject" }
}
```

**Startup refuses** a configuration that:
- authors neither name of an edge type, or names a type twice (W23)
- has a closure whose inverse doesn't name it back, or a pair in which both closures or neither state `admits` (L3, W4)
- has a closure admitting both directions of one edge type, checked after normalizing each pair to the authored name, since `(fed_by, forward)` beside `(feeds, forward)` is exactly that (L2, W4)
- admits or bounds an undeclared edge type
- has an edge type without a `parentEndpoint`, or a cardinality rule bounding no end or with neither bound

**Pairs given under an unauthored name are normalized** to the authored name with their direction flipped, before both the L2 check and publishing (W4). A configuration may use either spelling, but the discovery document always shows the authored one.

**Deployment settings** come from environment variables, each overridable by a system property of the same name, as the suite does:

| Setting | Meaning | Default |
|---|---|---|
| `TREE_CONFIG` | path to the domain configuration | the bundled conformance profile |
| `TREE_HTTP_BIND` | address and port to bind | `0.0.0.0:8080` |
| `TREE_ADVERTISED_URL` | base URL callers reach the instance at, which every link in the discovery document is built from (*brief* §7.1) | `http://localhost:<bound port>/`, since a wildcard bind address such as `0.0.0.0` can't be reached |
| `KAFKA_BOOTSTRAP` | broker address | required |
| `KAFKA_CLIENT_*` | further Kafka client properties, mapped as the suite maps them (suite-design §6) | none |
| `TREE_CONSUMER_GROUP` | the Kafka consumer group the updater joins. Its committed offsets are part of the store's state, so changing it against an existing store means a rebuild (*brief* §5.1) | `tree-<id>` |
| `TREE_LEASE_DURATION` | the updater lease (§4.5) | `PT45S` |
| `TREE_RETRY_AFTER` | seconds sent in `Retry-After` with `not_ready` | `5` |
| `TREE_CASSANDRA_*` | contact points, local datacenter and keyspace (slice 7 only) | none |

Slice 7 adds the Cassandra driver: the Apache Cassandra Java driver 4.x (`org.apache.cassandra:java-driver-core`, Apache-2.0), with Testcontainers' Cassandra module for its tests. Both go under the root `pom.xml`'s dependency management and into `THIRD-PARTY-NOTICES.md`, per the repository's licensing policy.

### 3.2 Parents, children, and uniform direction

**Which end is the parent** comes from `parentEndpoint`, read against the authored name. For `holds` with `parentEndpoint: subject`, `A holds B` makes `A` the parent. For `fed_by` with `parentEndpoint: object`, `x fed_by y` makes `y`, the supplier, the parent. The profile authors `fed_by` through its inverse deliberately (contract A.2).

**An admitted pair runs downward or upward.** A pair `(edgeType, direction)` runs parent-to-child when `direction = forward` and `parentEndpoint = subject`, or when `direction = reverse` and `parentEndpoint = object`. Otherwise it runs child-to-parent. A closure is **uniformly directed** when all its admitted pairs run the same way (§3.2). The closure's direction is computed once at startup and decides its read algorithm (*brief* §7.3).

Under the profile's parent endpoints all six closures are uniformly directed: `contents`, `consumers` and `dependents` run downward, and their inverses upward. L2 rules out the self-inverse closure, but a closure can still mix directions across types, such as `holds` forward with `tracks` reverse. Such a closure is legal, and is answered by traversal.

### 3.3 References and encoding

**References and edge ids use `conformance-model`'s codec.** The canonical serialization is `type:id`, parsed at the first `:` (L8, L9), and is written and read only through `Canonical`. An edge id is `(subject>edgeType>object)` with `%` and then `>` escaped in each component (W21), written and read only through `EdgeId`. Keys, `resource_ref`, `root_ref` and the members of every set column are canonical references (W2). Equality is byte equality, with no case-folding, trimming or normalization (L8).

**Path segments use the tree's own encoding, built from the pair rather than the canonical string** (§3.1, L10). A root segment is `type:id`. A non-root segment is `edgeType:type:id`. In every field `%` encodes as `%25` and `/` as `%2F`. In the edge type and resource type fields `:` also encodes as `%3A`, though L9 already keeps `:` out of types. An id keeps raw `:`, because it is parsed last. A path is `/` followed by its segments joined with `/`.

Decoding must be the exact inverse of encoding. A property test should round-trip arbitrary pairs, including ids containing `/`, `:`, `%`, `>`, spaces, quotes, non-ASCII, astral characters and characters below `/` such as `-`, `.`, `!` and space. Those are exactly the characters fixture 7 sends. Every id the suite creates also starts with its run id, whose tail is `~ /,>%é:` (suite-design §6). So encoding is needed from the very first edge a fixture publishes, not only for fixture 7.

---

## 4. Storage

### 4.1 Schema

The design's schema (§3.1), plus three operational tables this brief adds: the move journal, the instance state and the updater lease. Where ingest resumes isn't a table: it's the consumer group's committed offsets (*brief* §5.1).

```cql
CREATE TABLE tree_edge_by_child (
    child_ref  text,
    edge_type  text,          -- authored name
    parent_ref text,
    extra      boolean,       -- true unless this is the child's materialized parent edge
    PRIMARY KEY ((child_ref), edge_type, parent_ref)
);

CREATE TABLE tree_edge_by_parent (
    parent_ref text,
    edge_type  text,
    child_ref  text,
    extra      boolean,
    PRIMARY KEY ((parent_ref), edge_type, child_ref)
);

CREATE TABLE tree_path (
    root_ref       text,
    path           text,
    extra_tails    set<text> static,   -- resources with a row here that have an extra child edge
    extra_heads    set<text> static,   -- resources with a row here that have an extra parent edge
    anomalous_refs set<text> static,   -- resources with a row here that are cycle members or breach a rule
    PRIMARY KEY ((root_ref), path)
);

CREATE TABLE tree_path_by_ref (
    resource_ref text PRIMARY KEY,
    path         text
);

CREATE TABLE tree_anomaly (
    resource_ref text,
    code         text,          -- 'cycle_detected' | 'cardinality'
    detail       text,          -- rule id, or cycle id (*brief* §6.5)
    sources      set<text>,     -- breaching resource, or every cycle member
    cycle_edges  set<text>,     -- for a cycle: every edge among its members, as edge references (`edge:(s>t>o)`)
    since        timestamp,
    PRIMARY KEY ((resource_ref), code, detail)
);

CREATE TABLE tree_anomaly_by_age (
    bucket       int,           -- constant 0
    since        timestamp,
    resource_ref text,
    code         text,
    detail       text,
    PRIMARY KEY ((bucket), since, resource_ref, code, detail)
);

CREATE TABLE tree_move_journal (     -- pending subtree moves (*brief* §6.3)
    bucket     int,             -- constant 0
    offset_key text,            -- 'partition:offset' of the event that planned the move
    seq        int,             -- order of moves within the event
    old_prefix text,
    new_prefix text,
    flag_sets  set<text>,       -- edges whose extra flag this event changes, as 'parent|edgeType|child=true|false'
    PRIMARY KEY ((bucket), offset_key, seq)
);

CREATE TABLE tree_instance_state (
    singleton text PRIMARY KEY,  -- constant 'instance'
    state     text,              -- 'ready' | 'rebuilding'
    since     timestamp
);

CREATE TABLE tree_updater_lease (    -- same shape and protocol as §4.5
    shard_key        text PRIMARY KEY,
    holder           uuid,
    lease_generation bigint,
    expires_at       timestamp
);
```

**Every known resource has exactly one row in each path table, in every state.** A resource's materialized parent edge is read from its own path: the last segment's edge type, and the reference in the second-to-last segment. The `extra` flag on the edge rows says the same thing from the edge's side, so a reader or the audit can tell extra edges apart without reading paths.

**In a forest, a resource's ancestors and its whole subtree have their rows in its own partition.** That is why per-partition static sets are enough for every read: a path split or a range read returns rows from one partition, and that partition's statics describe every resource the read can return from it.

### 4.2 Invariants

**What must be true after every applied event.** The stored paths depend on arrival order (§3.1), so no function of the edge set alone says what the store must hold. What can be checked instead is a set of invariants over the edges `E` and the store:

- **F1 — one materialized parent.** Every known resource has exactly one path. If it isn't a root, its path ends in a segment naming an edge `(p, t, c)` that exists in `E`, has `extra = false`, and `path(c) = path(p) + segment(t, c)`. Every other parent edge of `c` in `E` has `extra = true`.
- **F2 — a forest.** It follows from F1 that the materialized edges form no cycle, since each path strictly extends its parent's.
- **F3 — roots are justified.** A root has no parent edge from outside its own subtree, where "its subtree" means the resources whose paths start with its path plus `/`.
- **F4 — statics.** `extra_tails`, `extra_heads` and `anomalous_refs` in each partition contain exactly the resources with a row there that meet their definitions. A resource that loses its row, by moving or by becoming unknown, leaves every static set of its old partition at the same time.
- **F5 — anomalies.** The cycle records are exactly the strongly connected components of `E` (all edge types) with more than one member, plus self-loops. The cardinality records are exactly the breaches of *brief* §6.4 over `E`.

**These invariants, together with "every read equals breadth-first search over `E`", are the test oracle** (*brief* §10.2). Recovery follows from F1 and F3. When `E` is a proper tree, no parent lies in its child's subtree, so F3 forces every non-root's parent to be materialized, and the forest is the tree. The test kit's reference model (`ForestModel`) checks F1–F3 after every event of 2,000 random edit sequences per run. In every one, reads match breadth-first search and the forest equals the tree once the sequence converges to a proper tree.

---

## 5. Ingest

### 5.1 The consumer loop

**Only the lease holder consumes, and it is the consumer group's only member** (§4.5). Acquire `tree_updater_lease` for shard `global` by compare-and-set, and heartbeat at a third of the lease duration. Only then subscribe to the ingest topic in the group `TREE_CONSUMER_GROUP`; as the only member, the updater is assigned every partition. On a failed renewal, close the consumer at once, which leaves the group. A process that doesn't hold the lease never joins, so there is one election, the lease, rather than two. The group's committed offsets are where this leader, or the next, resumes.

**A deposed leader must be out of the group before a successor can take the lease.** A leader that stalls doesn't close its consumer; it stays a member until Kafka times it out. If the successor joined first, the partitions would be split between the two, and the stalled member's share would stall with it. So `session.timeout.ms` and `max.poll.interval.ms` are both set no longer than `TREE_LEASE_DURATION`. Records are applied one at a time, so a short poll interval only needs a small `max.poll.records`. Once the successor's join has rebalanced the group, Kafka rejects any commit from the old member, so a deposed leader can't move the committed offsets either.

**On joining, the updater decides between resuming and rebuilding by asking one question: does the store's state already reflect the committed offsets?** `tree_instance_state` answers it, so the consumer never needs to know which store it is feeding:
- **`ready`**: resume from the committed offsets.
- **Absent, or `rebuilding`**: the store has never been built from this topic, or a rebuild didn't finish. Run the rebuild (*brief* §8), which seeks every partition to the beginning.

The in-memory store (slices 1–6) holds its instance state in memory, so it is absent at every start and every start is a rebuild. That's what W35 requires of a store that starts empty: resuming from committed offsets would answer `∅` for references it had already applied. The Cassandra store (slice 7) reads `ready` after an ordinary restart and resumes. After its keyspace is wiped it finds no state and rebuilds, rather than resuming over nothing.

**A partition with no usable committed offset also means a rebuild.** Set `auto.offset.reset=none`, so the consumer reports both cases instead of silently resetting. `OffsetOutOfRangeException` means records the store never applied have been compacted away, deletes whose tombstones aged out among them (§4.3). `NoOffsetForPartitionException` while the state is `ready` means the group's offsets were lost, for example by expiring while the instance was down, or that the partition is new. Adding partitions to a keyed topic moves keys between partitions, which breaks the per-key order the tree relies on (W24). In every case the state can no longer be brought up to date incrementally. During a rebuild, a partition without an offset is simply sought to the beginning.

Records are applied **one at a time, in partition order, by one thread**. That satisfies W34 trivially, so fixture 15 passes by construction. Coalescing records per key (W33) and parallel partitions are later optimizations, and fixture 15 guards them when they come. With several partitions assigned, any interleaving across partitions is fine. Only order within a partition matters (W24).

For each record:

1. **A null-valued record is a tombstone.** Skip it and derive nothing from it, but its offset is committed like any other (W32).
2. **Parse and validate it** (*brief* §5.2). A record that fails is rejected (*brief* §5.3).
3. **Apply** it by the procedure in *brief* §6.
4. **Commit its offset,** only after every write in step 3 is acknowledged. Commits may be batched or asynchronous, since a redelivered event is re-applied harmlessly. What must never happen is committing an offset whose event isn't fully applied.

**No early exit on "nothing changed".** A redelivered `snapshot` of an edge already present still runs cycle detection and cardinality evaluation (W24, §3.3 step 2). Placement is already decided by the stored flag, so re-deriving it would change nothing (*brief* §6.1). A redelivery and the retry of a run that died part-way are indistinguishable from the payload, and the procedure is idempotent.


### 5.2 Record validation

The key is read as UTF-8 and the value as UTF-8 JSON, with no framing, and headers are never consulted (W28). **Anything that fails to parse is rejected, not thrown:** a null key, a value that isn't UTF-8 or isn't JSON, a missing `data`, a missing `attributes.snapshot` or `attributes.delete`, or a relationship whose `data` isn't an object. The checks below are what a record must satisfy to be applied. Anything else, whether listed or not, is a rejection.

| Check | Source |
|---|---|
| The value is a JSON:API document whose `data.type` is `snapshot` or `delete` | W15, W26 |
| The inner object sits under `data.attributes.snapshot` or `data.attributes.delete`, matching the outer `type` | W17 |
| The inner `type` is `edge` | W20, W26 |
| `attributes.edgeType` is the **authored** name of a declared edge type | W23 |
| `relationships.subject.data` and `relationships.object.data` are both present, each with a non-empty `type` containing no `:` and a non-empty `id` | W20, L7, L9 |
| The inner `id` equals `EdgeId.of(subject, edgeType, object)` | W25 |
| The record key equals `edge:` followed by the inner `id` | W24, W25 |
| `data.relationships.resource`, when present, is `{type: edge, id: <the inner id>}` | W18, W25 |

**Ignored, never validated:** the outer `id` beyond logging (W16), `sourceVersion` (W17), and every inner attribute other than `edgeType`, including `scope` (W19, W22). **A `delete` carrying anything beyond its triple is honored**: the extra members are ignored and the edge is retracted (W20). Once the checks pass, the triple is what the tree keys everything by.

### 5.3 Rejection

**A rejected record is skipped: nothing from it is applied, consumption continues, and its offset is committed like any other** (W27). It is logged with its offset and reason and counted in a metric. Halting or retrying the partition is forbidden: several instances may share a topic, so a record one rejects can be routine traffic for another (W27).

The suite observes a rejection by publishing a valid sentinel edge on the same partition after the invalid record, waiting until the sentinel is visible, and then checking that the invalid record had no effect (contract §7). So rejection has to be cheap and must never stall the partition.

---

## 6. The write procedure

### 6.1 Overview

**What one event does.** Each event adds or removes one edge `p → c` (parent `p`, child `c`, via `parentEndpoint`). Most events move no path at all. A path moves only when a resource gains its first parent, or loses its materialized one. The procedure below follows §3.3, with the detail that section leaves open.

**Every step is either idempotent or journaled**, so a crash at any point is repaired by redelivery of the same event. Edge writes and static-set changes are idempotent. Placement decisions are re-derived from the edges and their `extra` flags, and a stored flag is never re-decided. Subtree moves are journaled (*brief* §6.3).

```
apply(event):
    (p, t, c) = parent, type, child of the event's edge
    ts        = fencedTimestamps(leaseGeneration)                    # *brief* §6.6
    finishPendingMoves()                                              # *brief* §6.3

    if snapshot:
        if edge already stored: keep its flag; go to cycles           # redelivery: placement is already decided
        create root rows for p and c if either is new
        if c has no materialized parent and not inSubtree(p, c):     # *brief* §6.2
            plan: edge materialized, move c under p via t
        else:
            plan: edge extra
        journal and apply the plan; write the edge with its flag
        refresh extra_tails / extra_heads for p and c
        if c reaches p: recomputeCycles(descendantsOrSelf(c))         # *brief* §6.5

    if delete:
        delete the edge from both edge tables                         # a no-op if it was never stored
        remove p's and c's rows, and their static-set entries, if no edge mentions them any more
        if c still has a row: enforceRootRule(c)                      # *brief* §6.2
        if p still has a row: enforceRootRule(rootOf(p))              # the old tree's root
        refresh extra_tails / extra_heads for p and c
        if (p,t,c) is in a recorded cycle: recomputeCycles(that cycle's members)

    evaluateCardinality({p, c})                                       # *brief* §6.4
    write anomaly changes and anomalous_refs                         # *brief* §6.5
    commit the record's offset once the writes above are acknowledged  # *brief* §5.1
```

**A partition may briefly hold rows and no root row.** Removing `p`'s row before `c`'s subtree has moved leaves rows under `p`'s old prefix whose root has none. That's expected inside one event: the move that follows range-reads by `root_ref`, which doesn't need the root's row. Only the state *between* events must satisfy F1–F5, and the audit checks it only there.

**A delete of an edge the instance never held**, or a redelivered delete after its endpoints have gone, changes nothing and must not fail. Every step above is then a no-op: the edge isn't there to delete, an unknown resource has no row to enforce anything on, and the root rule only acts on a resource with a row. That's how W31's idempotent repeated delete is met without an early exit.

### 6.2 Placing an added edge

**`inSubtree(p, c)`** is a string test: `p = c`, or `path(p)` starts with `path(c) + '/'`. It is what keeps the forest acyclic. An edge whose parent already lies beneath its child would close a cycle, so it is stored as extra.

**`c` has no materialized parent** when none of its parent edges in `tree_edge_by_child` has `extra = false`, and then its path has one segment. That covers a new resource, a transient root, and a resource whose parent edges are all extra because each lies in its subtree.

**`enforceRootRule(x)`** applies §3.1's last two rules, which are the same rule seen from two places: a resource with no materialized parent must take one if any of its parents lies outside its subtree. If `x` has no materialized parent, find its parent edges whose parent is not `inSubtree(·, x)`. If there are any, take the byte-least `(parent, type)`, set that edge's `extra` to false, and move `x` under it. Otherwise do nothing. Applied to `c` after its materialized edge is deleted, this is the promotion. Applied to `rootOf(p)`, the root of the tree `c` was detached from, it is the re-check. It is a no-op everywhere else, which is what makes the delete path safe to re-run: on redelivery the edge is already gone, and both calls find nothing to do. `rootOf(p)` is found by splitting `p`'s path, since `p` stays in the old tree whatever happens to `c`.

**Why only those two resources.** Deleting a materialized edge detaches exactly one subtree, `c`'s, from exactly one tree, `rootOf(p)`'s. Only `c` can have lost its parent, and only that tree's root can have lost a parent that lay inside its subtree. Promotion only ever adds resources to a subtree, so it can't break the rule anywhere else. The simulation confirmed this narrower rule against one that re-checked every root.

When `enforceRootRule(c)` finds no eligible parent after `c`'s materialized edge is deleted, `c` becomes a root: it moves to `/c`, in a partition of its own.

### 6.3 Moving a subtree

**Why moves are journaled.** A move changes many rows across two partitions, and a crash part-way through must not leave rows behind. Once a resource's path in `tree_path_by_ref` has been updated, nothing records where its old row was. A stale row left under the old prefix would make the moved resources appear, wrongly, under their old ancestors. So every move is written down before it starts:

1. **Journal.** Write one `tree_move_journal` row per move the event plans, with the old prefix, the new prefix, and the `extra` flag changes that go with it.
2. **Apply flag changes** to both edge tables.
3. **Insert new rows.** Range-read under the old prefix, which returns exactly the subtree. For each row, insert the path with the old prefix replaced by the new one into `tree_path`. Add the moved resources to the new partition's static sets, recomputed from their edges and anomaly records.
4. **Switch.** Update `tree_path_by_ref` for every moved resource to its new path.
5. **Delete old rows** under the old prefix, and remove the moved resources from the old partition's static sets.
6. **Clear** the journal row.

Every step is idempotent given the journal row, so `finishPendingMoves()` simply re-runs steps 2–6 for every row still in the journal. It runs before each event and on acquiring the lease. Moves within an event run in `seq` order.

**Why this order.** It is what makes a read overlapping a move never miss a resource that exists both before and after (§3.3):
- Until step 4, every reader resolves the moved resources to their old paths, and all old rows still exist.
- From step 3 on, every new row exists, along with its statics, so a reader that has already switched finds a complete subtree.
- In step 5, old rows disappear only from readers looking down from the old ancestors, and losing the subtree is exactly the change being made there.

When old and new prefixes share a partition, the static sets need no change.

### 6.4 Cardinality evaluation

**Which resources a rule can change.** A rule ranges over the known resources of the types it lists, and bounds how many edges of one type each has at one end (W4). An edge event changes edge counts only at its two endpoints. It can also make only those two resources known or unknown, since known-ness comes from edges of any type (L12). So after every event, whatever its edge type, the procedure re-evaluates every configured rule at `p` and at `c`, each rule over its own `edgeType`, and nowhere else. An event on a `notes` edge can change `one-holder`'s verdict, by making a `beta` known.

**The rule shape** is W4's. A rule names an `edgeType` by its authored name, plus either `objectTypes` with `minEdgesPerObject` and/or `maxEdgesPerObject`, or `subjectTypes` with `minEdgesPerSubject` and/or `maxEdgesPerSubject`. For a resource `x` and a rule `R` bounding the object end:

- `x` is in the rule's range if it is known (it has an edge of any type) and its type is in `R.objectTypes`. A rule never reaches a type it doesn't list. That is why the profile can give `alpha` and `omega` any number of holders and expect no qualification (contract A.4).
- `count(x)` is the number of edges of `R.edgeType` with `x` as object, read from the edge tables, mapping subject and object to parent and child through `parentEndpoint`. Extra and materialized edges count alike.
- `x` breaches `R` if it is in range and `count(x) < minEdgesPerObject` or `count(x) > maxEdgesPerObject`.

The subject end mirrors this. A minimum is breached by a resource with *no* edge of the type. So a `beta` known only through a `tracks` edge breaches `one-holder`, which is why the profile's fixtures give every `beta` and `gamma` exactly one holder unless they mean to provoke the rule.

A breach is recorded as `(resource_ref = x, code = 'cardinality', detail = R.id, sources = {x})`.

### 6.5 Cycles and anomaly records

**When a cycle can form.** Cycles are recorded in parent/child orientation, over edges of every type. An added edge `p → c` closes one exactly when `c` already reaches `p` over parent/child edges, or when `p = c`. If `inSubtree(p, c)` holds, that's immediate. Otherwise the writer runs the downward read of *brief* §7.3 from `c`, admitting every edge type downward, and stops as soon as it reaches `p`. In the steady state that's one range read with no hops.

**`recomputeCycles(region)`** runs Tarjan's algorithm iteratively (recursion depth is unbounded in principle) over the edges with both ends in the region, in parent/child orientation and counting every edge type. It replaces the cycle records of every resource in the region. When a cycle forms, the region is `descendantsOrSelf(c)`, read from `tree_edge_by_parent`. Any cycle with a member there lies wholly there, since all its members are each other's descendants. When an edge of a recorded cycle is deleted, the region is that cycle's members. The computation runs in memory, over the record's `cycle_edges` without the deleted edge.

- **The cycle id** is the byte-least canonical reference among its members. It is stable while the membership stays the same, which lets `since` carry over.
- **One row per member** in `tree_anomaly`: `(member, 'cycle_detected', cycleId)`, with `sources` set to every member and `cycle_edges` set to every edge among the members, encoded as edge references in the authored orientation (`edge:` plus `EdgeId.of`), so the cycle diagnostic can return them as they are (*brief* §7.5).
- **One row per open anomaly** in `tree_anomaly_by_age`. For a cycle, `resource_ref` and `detail` are both the cycle id and `code` is `cycle_detected`. For a breach, `resource_ref` is the breaching resource, `code` is `cardinality` and `detail` is the rule id.
- **An anomaly that is still open keeps its `since`.** Insert records that are new, delete records that are gone, and leave unchanged ones alone.
- **`anomalous_refs`** gains or loses an entry, in the partition of the resource's row, for every resource whose cycle or breach status changed.

**Why parent/child orientation is enough for uniformly directed closures.** A uniformly directed closure's admitted pairs all run downward, or all upward. So a cycle of its admitted edges is a directed cycle in the parent/child graph restricted to its admitted types, or the same cycle reversed. Either way it lies inside one recorded component, and the read finds it by filtering that component's `cycle_edges` to the admitted types and recomputing components in memory. A closure that mixes directions can have a cycle no parent/child component contains: `A holds B` with `A tracks B`, read as `holds` forward and `tracks` reverse. Such a closure is answered by traversal, and its cycles are found over the edges that traversal visited (*brief* §7.4).

### 6.6 Fencing

**What goes wrong without a fence.** A deposed updater that stalled mid-event and then resumes could write an old event's rows after the new leader has moved on. That would reinsert a path the new leader deleted, or delete one it inserted. The DAG fences with one conditional flip per reach set (§4.5). A tree event writes many rows across partitions, and a conditional write per row would be a lightweight transaction on every insert.

**The tree fences with write timestamps instead.** Every write to every tree table other than `tree_updater_lease` uses `USING TIMESTAMP ts`, where:

```
ts = leaseGeneration × 2^40 + seq        # seq: a counter for this term, incremented per statement
```

Cassandra resolves concurrent writes to a cell or row by timestamp, and a tombstone shadows every write with a lower timestamp. So any write from a later term beats any write from an earlier term, whatever order they arrive in. Within a term, `seq` orders the leader's own writes in the order it issued them. This covers inserts, deletes, static columns and set elements alike, and it costs nothing per write.

**The residue it doesn't cover, and what does.** A stale write to a row that the new leader never writes again isn't shadowed by anything. The obvious case is a moved subtree's row written under a path the new leader never produced. That residue would violate F1, and it is repaired by an **audit**. The audit runs as a task inside the event loop, between events, so it stays serial. For each tree partition it checks F1–F5 against the edge tables. Where a resource violates them, it re-places that resource by the rules of *brief* §6.2–§6.3, preferring its currently materialized parent where that is still valid, and writes the difference. Run it after acquiring the lease and periodically thereafter. It doubles as a production check of the invariants, and any repair it makes is logged as a defect, since in the absence of a deposed leader there should be none.

**Rules this imposes:**
- Nothing writes these tables without a client timestamp. A server-assigned microsecond timestamp is about 1.7 × 10^15 today, which outranks every fenced write until the lease generation passes roughly 1,500, so operator tools must not write them.
- `tree_updater_lease` is the exception, and must be. Its updates are conditional (§4.5), and Cassandra doesn't allow `USING TIMESTAMP` on a conditional update. It needs no fence anyway, since it is the source of the fence.
- `TRUNCATE` (*brief* §8) is unaffected, since it drops everything regardless of timestamp.
- The lease row is never deleted (§4.5).
- Committed offsets aren't fenced by timestamps. Kafka's group generation fences them: once a successor's join has rebalanced the group, a deposed member's commit is rejected (*brief* §5.1).
- Every write to `tree_instance_state` comes from the updater too, including a rebuild started by an operator: the command asks the updater, which starts the rebuild under its own lease (*brief* §8).

---

## 7. The HTTP surface

### 7.1 Request handling

**What the instance serves.** It serves the discovery document, and for each closure two `POST` endpoints, `of` (W5) and the cycle diagnostic (W36). The URL scheme is the implementation's own (W4): `/discovery`, `/closures/{name}/of` and `/closures/{name}/cycles`, with the closure name percent-encoded as a path segment. Links in the discovery document are absolute, built from a configured advertised base URL, because the address an instance binds to can differ from the one callers reach it at.

`of` and the diagnostic take the same request and reject the same malformed arguments the same way (W36). Checks run in this order, and the first that fails decides the response:

| Condition | Response | Source |
|---|---|---|
| A path the instance doesn't serve, including an undefined closure | `404`, no body | L1: an undefined closure has no link |
| A method other than `POST` (`GET` for discovery) | `405` with `Allow` | — |
| `Content-Type` absent, or other than `application/vnd.api+json` or `application/json`, ignoring parameters | `415` | W6 |
| `tree_instance_state.state = rebuilding` | `503`, error `not_ready`, with `Retry-After: TREE_RETRY_AFTER` | W11, W35 |
| A body that isn't JSON, or has no `data` array | `400`, error `request_malformed` | W13 (an implementation-defined code, as the reference implementation uses) |
| An element of `data` that isn't an object with a non-empty string `type` containing no `:` and a non-empty string `id` | `400`, one `reference_malformed` error per malformed element, each with `source.pointer` (`/data/i`) and `meta.argument`, the element as received as a JSON value | L7, L9, W11 |
| An unknown reference | **not an error**: it contributes nothing | L14, W12 |
| `data: []` | `200`, `data: []`, and `meta` with `closure`, `of: []` and `qualifications: []` (or `cycles: []` for the diagnostic) | L17, W9, W36 |

Every response with a body carries `Content-Type: application/vnd.api+json` (W6). An error's `status` member is the HTTP status as a string, and errors never mix with a result: a qualified result is a `200` (W8, W12).

**Readiness is read, not pushed.** Each reader process caches `tree_instance_state` for at most one second, so a reader starts refusing within a second of a rebuild beginning (*brief* §8). The discovery document is served throughout a rebuild: it describes the configuration, which a rebuild doesn't change, and fixture 1 must not fail because a rebuild happens to be in progress.

### 7.2 Evaluating a set

`of(S) = ⋃ of(x)` over the distinct members of `S`, deduplicated by canonical reference (L21, L6). Each member is evaluated independently, in parallel. Qualifications are computed over the union (L27). Nothing in the response reveals which arguments were known (L21). `meta.of` is the argument set, deduplicated, each member as a resource reference object.

### 7.3 Evaluating one argument

**Which algorithm answers depends on the closure.** In order:

1. **Read `tree_path_by_ref(x)`.** If there is no row, `x` is unknown, and the result is `∅` (L16). Otherwise `x` is in its own result (L15).
2. **A closure that isn't uniformly directed is answered by traversal.**
3. **Upward closure.**
   - Split `x`'s path and walk upward from `x`, admitting each ancestor while its segment's edge type, and every one below it, is admitted.
   - Read the static columns of the path's partition (`SELECT extra_heads, anomalous_refs FROM tree_path WHERE root_ref = ? LIMIT 1`).
   - For each admitted resource listed in `extra_heads`, read `tree_edge_by_child` for it. Each parent reached by an admitted edge type and not yet in the result is a **hop**: read that parent's path, and continue from step 3 with it. Repeat until no hop adds anything.
4. **Downward closure.**
   - Range-read under `x`'s path:

     ```cql
     SELECT path, extra_tails, anomalous_refs FROM tree_path
      WHERE root_ref = ? AND path >= :P AND path < :P_with_trailing_0
     ```

     The upper bound is the encoded `P` followed by `0` (`0x30`, the codepoint after `/`). Keep `x`'s own row, and every row that begins with `P + '/'`. Discard the rest, which are siblings whose ids extend `x`'s with a character below `/`.
   - Take the segments after `x`'s position in each kept row, pruning at the first unadmitted edge type. Rows arrive in path order, so a parent precedes its children.
   - For each admitted resource listed in `extra_tails`, read `tree_edge_by_parent` for it. Each child reached by an admitted edge type and not yet in the result is a hop: read that child's path, and continue from step 4 with it. Repeat until no hop adds anything.

**Why this is complete.** Every edge is either materialized, and so covered by a path split or a range read, or extra, and so found through the static sets and followed by a hop. The read therefore follows every admitted edge from everything it reaches, which is breadth-first search over `E`. A resource that type-pruning stopped can still be reached by a hop across an admitted extra edge, and is then read from its own path. A cycle needs nothing further, since the hop across its closing edge lands on a resource already in the result.

**Traversal**, for closures that aren't uniformly directed, is a breadth-first search from `x` over the edge tables. It follows `tree_edge_by_parent` for pairs running downward and `tree_edge_by_child` for pairs running upward, keeps a visited set, and reads each level in parallel. It is the specification computed literally.

**Cost.** In the steady state each direction costs two reads in sequence, with no hops. A hop costs three reads in sequence: the extra edges of the resource being hopped from, the path of the target, and the target's range or statics. Hops from one read run in parallel.

### 7.4 Qualifications

**Which results a qualification attaches to** is fixed by L27. A result carries `cycle_detected` if any reference in it lies on a cycle of the closure's admitted edges. It carries one `cardinality` per rule that some reference in it breaches, provided the closure admits the rule's edge type in either direction. Nothing outside the result qualifies it.

**Why the candidates are already in hand.** Every resource a read returns has its own row in a partition the read has already read: ancestors and subtrees share their resource's partition, and each hop reads its target's partition. So the `anomalous_refs` of the partitions read name every candidate, and nothing is looked up in the steady state. A traversal reads no statics, so it looks up every member directly.

- **`cardinality`.** For each candidate in the result, read its `tree_anomaly` breach records. Group them by rule, keeping only rules whose edge type the closure admits. Each group becomes `{code: "cardinality", rule: R.id, sources: [...], total: n}`, where `sources` lists the group's first ten references in byte order of their canonical form, or all of them if fewer, and `total` is the group's size (L28, W10).
- **`cycle_detected`.** For a uniformly directed closure: for each recorded cycle with a member in the result, filter its `cycle_edges` to the closure's admitted types and compute components in memory. If any component has more than one member, or is a self-loop, and intersects the result, add `{code: "cycle_detected"}`, at most once per response, carrying nothing else (L28, W10). For a traversal: compute components over the admitted edges the traversal visited.

### 7.5 The cycle diagnostic

**What it answers.** Given the same request as `of`, it reports every cycle group in the result `of` would return: each strongly connected component of the closure's admitted edges with more than one member, or a single reference with an admitted edge to itself (W36). Each group carries its members, and the admitted edges among them as edge references. The groups are exactly the ones whose presence makes `of` add `cycle_detected`, so the two share one computation (*brief* §7.4). The diagnostic just returns the groups instead of a flag.

```json
{ "meta": { "closure": "suppliers",
            "of": [ { "type": "gamma", "id": "G0" } ],
            "cycles": [ { "members": [ ... ], "edges": [ { "type": "edge", "id": "(gamma:G1>fed_by>gamma:G2)" } ] } ] } }
```

An edge in a group is written in its authored orientation. The cycle record stores it as the canonical string `edge:` plus `EdgeId.of(subject, authoredType, object)` (*brief* §6.5). The response emits it as a resource reference object, `{"type": "edge", "id": "<EdgeId.of(...)>"}`, never as the bare string. The response has no `data`, and `cycles` is empty when the result holds no cycle. The diagnostic answers about the instance's state when it is asked, so an empty answer after a `cycle_detected` means the cycle has since cleared (W36).

### 7.6 Discovery

The discovery document is rendered from the configuration at startup and served at `/discovery` (W4):

- **`closures`**: one entry per closure, with `name`, `inverse`, `links.of` and `links.cycles`. The closure the configuration designates carries `admits`, normalized to authored names, with `direction` as `forward` or `reverse`. Its inverse carries none.
- **`edgeTypes`**: `name`, `inverse` if declared, and `authored`. `parentEndpoints` is internal and is not published.
- **`cardinalityRules`**: each rule as configured, with its `id`.
- **`ingestTopic`**.

Fixture 1 compares this document with the profile, ignoring members the contract doesn't name (suite-design §6), so a misconfiguration fails there, legibly. Its comparison, `ProfileCheck.discrepancies`, is public in the suite's jar, so the tree's own unit tests run it in-process against the rendered document. No Kafka is needed for that.

### 7.7 Response

```json
{ "data": [ {"type": "...", "id": "..."} ],
  "meta": { "closure": "<name>", "of": [ <the argument set, deduplicated> ],
            "qualifications": [ ... ] } }
```

A successful `of` is a `200` (W8). Its `meta` carries the closure name, the argument set and the qualifications, and nothing else of the contract's: no consistency class and no per-result metadata (W9, L24). Results come back in no guaranteed order (L6).

---

## 8. Rebuild and `not_ready`

**When the instance must refuse.** It must refuse when its state isn't a correct closure over any prefix of the stream (W35). Incremental application is always correct over the prefix it has applied, so the only such window is the rebuild.

1. Set `tree_instance_state` to `rebuilding`, then wait out the readers' cache window of one second before step 2. From then on every `of` and every diagnostic gets `not_ready`, so no reader can see the cleared store.
2. `TRUNCATE` every tree table except `tree_updater_lease` and `tree_instance_state`. The lease survives so the fence stays monotonic (§7.4).
3. Record each partition's end offset, seek every partition to the beginning, and consume through the ordinary write procedure, committing offsets as usual (*brief* §5.1).
4. When every recorded offset is reached, run the audit (*brief* §6.6) once over every partition, set `ready`, and continue. From then on the committed offsets match the state, so a restart resumes from them. A crash before this step leaves the state `rebuilding`, so the next leader starts the rebuild again from step 1.

**Initialization is the same procedure.** A store with no instance state, which includes the in-memory store at every start, takes this path from step 1, where step 2 finds nothing to clear (§7.4). With an empty topic, the end offsets are all zero and the window closes at once.

`since` values after a rebuild are rebuild-time, since the rebuild can't know how long an anomaly stood before it. Replaying through the write path costs more than §7.4's one-pass computation, because a subtree replayed bottom-up moves once for each level that arrives above it. For equipment that's a few hundred rows per NE, rewritten at most once per level. The one-pass version is an optimization to add behind the same window if rebuild time ever matters. A rebuild may materialize different parents than the live instance did for any anomaly open at the time. That is valid, and it is invisible to callers (§3.1).

**Triggers:** a store with no instance state, or one left `rebuilding` (*brief* §5.1); a partition with an offset out of range or no committed offset (*brief* §5.1); an operator command; or an audit that finds more than a configured number of repairs. The operator command is a request to the updater, never a direct write: an admin endpoint the updater serves, or a flag it reads at startup, so that every write of the rebuild carries the updater's fence (*brief* §6.6). With the in-memory store a rebuild is simply a restart, since its instance state is absent at every start (*brief* §5.1).

---

## 9. Worked traces

These traces are executable: `ForestTraces` in the test kit asserts every path, extra edge and read shown here, and runs against any implementation of `TreeUnderTest`. The traces configure no cardinality rules, so the breach notes are illustrations, not something the kit checks. Paths are shown without encoding, and edge types are omitted (all are `hosts`). "Extra" lists the extra edges after each event.

**T1 — a card moves between shelves, add first.** Start from `NE hosts S1`, `NE hosts S2`, `S1 hosts C`, `C hosts P`.

| Event | `C` | `P` | Extra | Notes |
|---|---|---|---|---|
| (start) | `/NE/S1/C` | `/NE/S1/C/P` | — | |
| `+ S2 hosts C` | `/NE/S1/C` | `/NE/S1/C/P` | `S2→C` | no path changes. Under a one-holder rule, `C` would breach its maximum |
| `− S1 hosts C` | `/NE/S2/C` | `/NE/S2/C/P` | — | `S2→C` promoted, subtree moved once |

While `S2→C` is extra, the descendants read from `S2` sees `S2` in `extra_tails`, hops to `C`, and returns `{S2, C, P}`. The ancestors read from `P` sees `C` in `extra_heads`, hops to `S2`, and returns `{P, C, S1, NE, S2}`. Each takes one hop.

**T1b — the same move, delete first.** After `− S1 hosts C`, `C` is a transient root: `C` has `/C` and `P` has `/C/P`. Under a one-holder rule, `C` would breach its minimum. After `+ S2 hosts C`, `C` takes `S2` as its first parent, and the state is the same as T1's final state. Both orders converge (fixture 14).

**T2 — a two-cycle forms and clears.**

| Event | `A` | `B` | Extra | Cycle |
|---|---|---|---|---|
| `+ A hosts B` | `/A` | `/A/B` | — | — |
| `+ B hosts A` | `/A` | `/A/B` | `B→A` | `{A, B}`. `B` is in `A`'s subtree, so the edge is extra |
| `− A hosts B` | `/B/A` | `/B` | — | `B` becomes a root, and the re-check of the old root `A` promotes `B→A` |

**T3 — a ring under a root.** The edges are `A hosts B`, `B hosts C`, `B hosts D`, `C hosts A`, then `R hosts A`. `C hosts A` is extra, since `C` is in `A`'s subtree. `R hosts A` is materialized, since `A` is a root and `R` is not beneath it. That gives `R` at `/R`, `A` at `/R/A`, `B` at `/R/A/B`, `C` at `/R/A/B/C`, and `D` at `/R/A/B/D`.

- **Downward from `A`** returns `{A, B, C, D}` from one range read. The hop across `C→A` finds nothing new.
- **Downward from `R`** returns `{R, A, B, C, D}`, also from one range read.
- **Upward from `D`** splits to `{D, B, A, R}`. `A` is in `extra_heads`, so the read hops to `C` and adds it, giving `{D, B, A, R, C}`.
- In all three the result contains the cycle, so `cycle_detected` names `{A, B, C}` when the closure admits `hosts`.

**T4 — a ring broken, and the old-root re-check.** Start from `A hosts B`, `B hosts C`, `C hosts A`. `C→A` is extra, and `A` is the root at `/A`. Deleting `A hosts B` detaches `B`'s subtree: `B` becomes a root at `/B`, and `C` moves to `/B/C`. The re-check of the old root `A` finds its parent `C` no longer beneath it, and promotes it, giving `/B/C/A`. The data is now the tree `B → C → A`, and the forest matches it.

**T5 — the only remaining parent lies beneath.** Start from `R hosts A`, `A hosts B`, `B hosts A`. `B→A` is extra, since `B` is beneath `A`. Deleting `R hosts A` leaves `A` with one parent, `B`, which lies in its own subtree. So `A` becomes a root at `/A` with `B` at `/A/B`, and `B→A` stays extra. Promoting `B` would have made the forest cyclic. A descendants read from `B` still reaches `A` by hopping across `B→A`.

---

## 10. Build plan

### 10.1 Structure

**Separate the algorithm from Cassandra.** The write procedure and the reads depend only on a storage port, so the same code runs over an in-memory store in slices 1–6 and over Cassandra in slice 7:

```
EdgeStore:      put/delete with extra flag; setExtra; parentsOf(x); childrenOf(x)
PathStore:      pathOf(x); range(root, prefix) + statics; statics(root);
                insert/delete path rows; set-element add/remove on each static
AnomalyStore:   get(x); put/delete with since; byAge()
JournalStore:   append(moves); pending(); clear(entry)                  # brief §6.3
LeaseStore:     acquire(); renew(); release(); generation()             # §4.5
InstanceState:  get/set                                                 # brief §5.1, §8
```

The in-memory store is not a stub. It holds every table, the journal included, so crash recovery can be tested in memory by abandoning a procedure part-way and redelivering its event. Its lease is granted to the one process that asks, at generation 1, and never expires, so renewal always succeeds and fenced timestamps start from generation 1 (*brief* §6.6). Its instance state is absent at every start, which is what makes every start a rebuild (*brief* §5.1). The consumer is the same for both stores: it reads the instance state and never asks which store it has. The Cassandra store implements the same port with fenced timestamps. That separates logic errors from storage errors, such as encoded range bounds, static columns and timestamps.

### 10.2 Tests the suite doesn't provide

- **The test kit (module `tree-testkit`), from slice 3.** Write an adapter implementing the kit's `TreeUnderTest` over the tree, then call `ForestProperties.run` and `ForestTraces.runAll` from the tree's tests. The kit's README says what the adapter must do. The main rule is that it reports what is stored, never a recomputation. Its eight standard closures include two that mix directions, which the tree answers by traversal, so traversal is part of slice 3 (*brief* §10.3).
  - `ForestModel`, the kit's reference model, is the executable reading of the placement rules and of the reads (*brief* §6.2, §7.3). It is not a model of the write procedure: it returns early on a redelivered event, which the tree must not (*brief* §5.1), and it has no anomalies, statics or journal. It always reaches the same forest the procedure must reach.
- **F4 and F5**, which the kit can't check because they need the storage: after every event of the kit's scenarios, every static set and every anomaly record equals what the definitions in *brief* §4.2 give, computed from scratch from the edges.
- **An oracle guard.** Scan the tree's compiled main classes for any reference to `com.example.closure.oracle`, with a control showing the scan finds one where there is one, as `reference-impl`'s `NoOracleTest` does (*brief* §1).
- **An encoding round-trip** over hostile references (*brief* §3.3).
- **Move ordering and crash recovery.** A read issued at each step of a subtree move never misses a resource that exists both before and after it. A crash injected after each step, followed by redelivery, leaves F1–F5 holding and no stale rows (*brief* §6.3).
- **Fencing** (slice 7). Using the Cassandra store, interleave writes from two terms out of order. Check that the later term wins, and that the audit repairs any residue.
- **Rebuild** (slice 7). `not_ready` during the window, a final state satisfying F1–F5 with the same answers as before, and the start decision of *brief* §5.1: resume when the state is `ready`, rebuild when it is absent or `rebuilding`, and rebuild on an offset out of range or a missing committed offset. Also check that a deposed leader closes its consumer on a failed renewal. The suite doesn't cover W35, so this is its only test.

### 10.3 Slices and how each is accepted

**What decides a slice's order.** The suite's fixtures read through real closures. The ingest fixtures check their effects with `contents` and `containers`, and every rejection check waits for a sentinel made of `notes` edges (contract §7). Several example graphs also give a resource two parents. So a slice can only claim the fixtures whose reads and graph shapes it has actually built. The order below follows from that, not from the contract's numbering.

| # | Slice | Fixtures it makes pass (class) | Contract |
|---|---|---|---|
| 1 | Configuration, discovery, the HTTP surface, errors, readiness, and the test wiring that stands up Kafka and the topic | 1 `ProfileFixtureTest`; 9 `ErrorsFixtureTest` except `aQualifiedResultIsASuccessAndNotAnError` | L1–L4, L7, L9, W4–W6, W11–W13, W35, W36 |
| 2 | Ingest end to end over single-parent data: consumption, validation and rejection, the edge tables, materialized paths with segment encoding, both uniform reads with type pruning, sets | 5 `FarEnd`; 6 `Identity`; 7 `HostileReferences`; 8 `SetArgument`; 10 `ResultSize`; 11 `EdgeLifecycle`; 12 `Authorship`; 16 `IngestKind` | §2.4, L8–L10, L15–L22, L24, W14–W33 |
| 3 | Extra edges, hops, promotion, the root re-check, the move journal, traversal for closures that mix directions; the test kit adopted | 2 `TypeRestriction`; 13 `EdgeId`; 14 `Reordering` | L23, W21, W24 |
| 4 | Cardinality | 4 `Cardinality`; 9's `aQualifiedResultIsASuccessAndNotAnError` | L25–L29, W4, W10 |
| 5 | Cycles: detection, records, qualification, the diagnostic | 3 `Cycle` | L27, L28, W36 |
| 6 | Generated laws and concurrency | `LawPropertiesFixtureTest`; 15 `Concurrency` | L18, L19, L21, L23, W33, W34 |
| 7 | The Cassandra store, fencing, resuming from committed offsets, the audit, rebuild | all of the above, rerun | W35 |

Every class name above ends in `FixtureTest`. Slice 2 can't claim fixtures 2 or 13: fixture 2's `G1` has both a `tracks` and a `holds` parent, and fixture 13 puts `holds` and `tracks` over the same pair, so which edge is materialized depends on arrival order. Both need hops. The laws come last because they compare qualifications too, over generated graphs with cycles and self-loops.

**The test wiring is part of slice 1**, because fixture 1 fails without it. Fixture 1 doesn't only compare the discovery document: it then checks that the ingest topic it names exists, has at least two partitions and is compacted (contract A.5, W29). Every other fixture is skipped until fixture 1 passes. Creating the topic is the tester's job, not the instance's. So the tree's build needs what `reference-impl`'s build has, and the brief permits reading those two files for it:
- **A `LauncherSessionListener` in the tree's test sources**, registered in `META-INF/services`, which starts Kafka with Testcontainers, creates the topic (three partitions, `cleanup.policy=compact`), starts a tree instance against it, and sets the suite's `DISCOVERY_URL` and `KAFKA_BOOTSTRAP` system properties. `reference-impl`'s `SelfTestInstance` is the pattern.
- **Surefire's `dependenciesToScan`** naming `com.example.closure:conformance-suite`, so the fixtures in the suite's main jar run in the tree's build.

**Each slice adds its fixtures to an explicit include list in `tree-impl/pom.xml`,** under Surefire's `<includes>`, alongside the tree's own tests. Without the list every fixture runs as soon as fixture 1 passes, and the build stays red until the last slice. Surefire's patterns match class files, also for classes scanned from a dependency:

```
<includes>
  <include>com/example/closure/tree/**/*Test.java</include>
  <include>**/fixtures/ProfileFixtureTest.java</include>
  <include>**/fixtures/ErrorsFixtureTest.java</include>
</includes>
<excludes>
  <exclude>**/fixtures/ErrorsFixtureTest.java#aQualifiedResultIsASuccessAndNotAnError</exclude>
</excludes>
```

An explicit `<includes>` replaces Surefire's default patterns, so the first entry is the only thing that picks up the tree's own tests: name every test class in `com.example.closure.tree` with a `Test` suffix, or it won't run. The exclusion is removed in slice 4, and from slice 6 the fixture entries become `**/fixtures/*FixtureTest.java`. Method-level exclusion is Surefire's `Class#method` syntax. It should work with the JUnit Platform provider in the version the repository pins, but if it doesn't, check before relying on it. The fallback is to leave `ErrorsFixtureTest` out until slice 4 and run its other methods by hand with `-Dtest=` in slice 1. The fixture graphs are built by the suite, and an example fixture's dynamic tests can't be selected one by one, so a fixture is claimed whole or not at all, except for the named methods above.

**A slice is done** when `mvn verify -pl tree-impl -am` is green, which means:
- the slice's fixtures and every earlier slice's
- the tree's own tests, including the kit's from slice 3
- no fixture weakened, skipped or edited to get there

This needs Docker and access to Maven Central.

---

## 11. Decisions log

Settled here unless noted. **Upstream** marks a change to push to the design document or contract.

| # | Decision | Source | Upstream |
|---|---|---|---|
| D1 | Fencing by `USING TIMESTAMP` with lease generation in the high bits, plus a serial audit of F1–F5 (*brief* §6.6) | §8 open item | Design §3.3 and §8: replace "not yet settled" |
| D2 | A materialized forest plus extra edges, with arrival order deciding which parent is materialized | Discussion of 13 Oct | Done in design §3 |
| D3 | After a materialized edge is deleted, only `c` and the old tree's root are re-checked (*brief* §6.2). Checked by simulation | — | Design §3.1 states it |
| D4 | Rebuild replays through the write path. The one-pass computation is deferred (*brief* §8) | Design §7.4 | Design §7.4: mark the tree's one-pass computation as an optimization |
| D5 | **Revised.** The inner `id`, the record key and the outer `resource` are each checked against the triple, and a mismatch is rejected. The checked triple keys storage (*brief* §5.2) | W25 | — |
| D6 | **Revised.** No `GET`, and no result ceiling, so `result_too_large` is never sent (*brief* §2). `generations` is withdrawn from the contract | W5, W7; contract §9 | Design §2's note on truncation |
| D7 | **Obsolete.** `except` is gone; rules list the types they range over, with bounds per end (*brief* §6.4) | W4 | Design §4.8's rule grammar and examples, and §6.1's rule |
| D8 | The cycle id is the byte-least member reference; an open anomaly keeps its `since` while its record is unchanged; the byte-least eligible parent is promoted; `cardinality.sources` lists the first ten breaching references in byte order | — | — |
| D9 | **Confirmed by the contract.** A rejected ingest record is skipped, logged and counted, and its offset is committed like any other (*brief* §5.3) | W27 | — |
| D10 | **Settled by the contract** for its codes: `reference_malformed` 400, `result_too_large` 422, `not_ready` 503. HTTP-level failures (404, 405, 415) carry no errors document. An unparseable body gets the implementation-defined `request_malformed`, 400, as the reference implementation does | W11, W13 | — |
| D11 | **Settled by the contract.** A qualification attaches to every result containing the cycle member or breaching reference, on closures admitting the rule's edge type (*brief* §7.4) | L27 | Design §3.2 and §8 |
| D12 | Subtree moves insert new rows, switch `tree_path_by_ref`, then delete old rows (*brief* §6.3) | — | Done in design §3.3 |
| D13 | Subtree moves are journaled, so a crash mid-move is finished on restart or redelivery (*brief* §6.3) | Found while drafting | Done in design §3.3 |
| D14 | References and edge ids go through `conformance-model`'s `Canonical` and `EdgeId`, shared with the suite as the reference implementation shares them. The oracle is never a dependency | suite-design §3a, §5 | — |
| D15 | The configuration is a discovery-shaped document plus `parentEndpoints`; discovery is rendered from it, and its fixture-1 conformance is unit-tested with the suite's `ProfileCheck` (*brief* §3.1, §7.6) | — | — |
| D16 | Every declared edge type takes part in the forest, including `notes`, which no closure admits. A type nothing admits only adds extra edges and hops, never wrong answers | — | Open: exempting such types from materialization is an optimization (*brief* §12) |
| D17 | Cycles are recorded in parent/child orientation. Uniformly directed closures find their admitted cycles by filtering records; closures that mix directions compute them over their traversal (*brief* §6.5, §7.4) | L2, L27 | Design §3.2 |
| D18 | Several malformed arguments produce one `reference_malformed` error each, all `400` | W11 | — |
| D19 | **Revised.** The updater consumes through a Kafka consumer group with committed offsets, joined only while it holds the lease, and closes its consumer on losing it. Whether to resume or rebuild is decided from `tree_instance_state` alone, so the consumer is the same for every store: the in-memory store has no state at start and always rebuilds (*brief* §5.1, §8). An earlier draft replaced the group with explicit assignment and a hand-built position table | W35, §4.5, §7.4 | — |
| D20 | Slices are ordered by what each fixture reads and the graphs it builds. Ingest lands with paths and both uniform reads, and second parents come before fixtures 2 and 13 (*brief* §10.3) | Cold-read review, 13 Oct | — |
| D21 | The tree's build runs the suite as `reference-impl`'s does: a session listener stands up Kafka, the topic and the instance, and a per-slice include list keeps the build green (*brief* §10.3) | Cold-read review, 13 Oct | — |
| D22 | `meta.qualifications`, and the diagnostic's `meta.of` and `meta.cycles`, are always present arrays. A cycle group's edges are `{type: edge, id}` objects (*brief* §1, §7.1) | W9, W36; suite `Qualifications`, `CycleDiagnosis` | — |
| D23 | The discovery document is served during a rebuild. Rebuilds are started through the updater, never by a direct write (*brief* §7.1, §8) | — | — |

**Upstream edits still owed to the design document** from the new contract revision:
- §2's sections on qualifications, truncation and error codes (`closure_not_named`, `closure_unknown`, `direction_vocabulary` are withdrawn)
- §4.8 and §6's rule grammar (`objectTypes`, per-end bounds)
- §7.5's composite pseudocode, which still forwards unrestricted
- the REST and truncation notes in §7.5–§7.6

---

## 12. Not settled here

- **Whether edge types no closure admits should stay out of the forest** (D16). Leaving them out would cut hops in a profile like the suite's, where `notes` edges give resources extra parents. It would also mean a resource's known-ness and its path can come from different edges, which the invariants then have to allow for.
- **Tree writes under a sharded DAG scheme** (§8). The tree runs as one shard until a scheme exists.
- **Edge cases the suite may reveal.** Any fixture that can't pass under this brief is a gap in the brief, or in the contract, and gets a decision log entry rather than a workaround.
