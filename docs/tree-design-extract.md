# Design document extract: the tree, the lease, rebuild

**Background only; not normative.** These sections are taken from *Relationship closure implementations*, revision `relationship-closure-modules-12oct26-1`, updated on 13 October to the contract's rule numbering. They explain why the tree has the shape the brief specifies. Where this text and the contract (`docs/contract-spec.md`) disagree, the contract governs. Where it and the brief disagree, the brief governs, and the disagreement is worth reporting.

Section numbers are the design document's own, which is what the brief means by an unprefixed §n. The extract covers:
- §3, the tree implementation
- §4.5, the single updater with its lease and fence, which the tree adopts, consumer group and committed offsets included. The fence differs: the tree fences its writes with timestamps rather than conditional flips (*brief* §6.6). The brief adds how the updater decides between resuming and rebuilding (*brief* §5.1).
- §7.4, rebuild from the compacted topics. The brief replaces the tree's one-pass computation with replay through the ordinary write path (*brief* §8).
- §8, the open questions

The rest of the design document isn't needed to build the tree: the DAG's storage, projection and cycle handling (§4.1–§4.4, §4.6–§4.8), the composite (§5), the deployment (§6), the other worked procedures (§7) and the glossary. It also still carries pre-revision text on error codes and rule grammar (§2, §4.8).

---

## 3. Tree implementation

The purpose of the tree implementation is to present DAG closure behavior without graph traversals at run time. That is possible because a resource in a tree normally has a single parent, and therefore a unique root-to-resource path that can be stored as a string — a "materialized path". With every resource's path stored, a closure is essentially free: ancestors come from splitting a string, descendants from a Cassandra range read.

**One parent, expected rather than enforced.** An instance may declare several edge types, and edges along one path can be different types. What matters is that a resource is normally the child in at most one edge *across all types*, which is what keeps the structure a tree. This is an assumption about the domain rather than a rule imposed on it: **the tree is an optimization that pays off when resources have one parent, and it should not be used where they do not.** A relation that would routinely give a resource an additional parent belongs in a DAG instance (§2).

**The tree must be indistinguishable from a DAG holding the same edges, by results and qualifications.** This is the governing constraint on everything below. The materialized path, the subtree range and the absence of any stored reach set are optimizations available because the data is *usually* a tree, and none of them may change an answer. Where the two could diverge, the DAG's answer is the specification and the tree is what is wrong — and there is no exception for data that has temporarily stopped being a tree. What may differ is cost and timing, which the contract leaves to each implementation: a tree answering a query over anomalous data may take a few more reads than it would over a proper tree, but never returns a different set.

Two such anomalous states arrive in ordinary operation, and both are stored rather than resolved:

- **A second parent.** A card moving between shelves arrives as two independent edge events on different keys, so the new parent's `snapshot` can land before the old parent's `delete`. In between, the card has two parents. Refusing the second edge would discard a fact, and picking one to keep would discard the other.
- **A cycle.** A containment correction applied out of order can leave `A hosts B` and `B hosts A` standing together for a while. Both edges are facts.

**The tree handles both the same way: it materializes one parent per resource and stores every other parent edge as an *extra* edge.** The materialized edges always form a forest, so every resource has exactly one path, whatever the data is doing; the extra edges are followed at read time by a hop, which costs a few more reads each (§3.2). So an anomaly changes what a query costs and never what it returns, and the tree's storage is exactly one path per resource in every state. The cardinality and cycle qualifications report the anomaly to callers while it lasts (§4.8), which is how a relationship that was never tree-shaped makes itself known: its extra edges never go away.

### 3.1 Storage

**What the tree stores is edges, and its paths are derived from them.** An earlier version of this design stored paths alone, on the reasoning that a path already says everything about an ancestry. That holds while the data is a tree and fails as soon as it is not: a second parent or a cycle-closing edge has nowhere to live in a structure with one path per resource, a cardinality rule needs to know how many parents a resource has, and a closure need not run uniformly up or down the tree (§3.2). So the tree keeps its edges, indexed both ways, as ground truth, and treats paths as a materialization of a chosen subset of them.

```cql
CREATE TABLE tree_edge_by_child (
    child_ref  text,
    edge_type  text,     -- the authored name (§2)
    parent_ref text,
    extra      boolean,  -- true unless this is the child's materialized parent edge
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
    path           text,   -- '/ne:R-1/hosts:shelf:A-1/hosts:card:B-2/hosts:port:C-3'
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
    code         text,       -- 'cycle_detected' | 'cardinality'
    detail       text,       -- the rule id, or a stable id for the cycle
    sources      set<text>,  -- the breaching resource, or the cycle's members
    cycle_edges  set<text>,  -- for a cycle: every edge among its members
    since        timestamp,
    PRIMARY KEY ((resource_ref), code, detail)
);

CREATE TABLE tree_anomaly_by_age (
    bucket       int,        -- constant: one partition per instance
    since        timestamp,
    resource_ref text,
    code         text,
    detail       text,
    PRIMARY KEY ((bucket), since, resource_ref, code, detail)
);
```

**The edge tables are keyed by parent and child rather than subject and object**, because those are the terms paths are built in, and which end of an edge is the parent is declared per edge type (§3.4). The authored name is stored, so subject and object are always recoverable from the parent endpoint. `tree_edge_by_child` is the one-hop parent map: in the steady state one row per resource, so "how many parents does this resource have" is a single-partition read. `tree_edge_by_parent` answers the same question for children, which subject-side cardinality bounds and reads both need. The `extra` flag records which edges the forest left out, so a hop can find them without consulting the paths.

**A resource is known exactly when an edge mentions it** (§2), here as everywhere: it has rows in the edge tables and exactly one path. A resource whose last edge is removed loses both, and there is nothing else to clean up.

#### The materialized forest

**What decides which parent is materialized?** Arrival order, subject to one rule that keeps the forest a forest. The rules are few, and each exists for a reason worth stating:

- **A resource's first parent edge is materialized, unless that parent lies beneath it.** A resource with no materialized parent is a root, and its path is just its own segment. When a parent edge arrives for it, the resource takes that parent and its whole subtree moves under the parent's path — unless the parent is already in the resource's own subtree, in which case the edge would close a cycle and is stored as extra instead. This is what keeps the materialized edges acyclic, so there is never a path to cut.
- **Every later parent edge is extra.** A second parent changes no path. It sets the `extra` flag on the edge and adds the two endpoints to the static sets of their partitions, which is all a read needs to find it (§3.2).
- **When a materialized edge is deleted, a remaining parent is promoted.** The resource takes any remaining parent that is not in its own subtree — the byte-least, for determinism — and its subtree moves there; if every remaining parent lies beneath it, it becomes a root. This is the only operation that rewrites paths besides a root acquiring its first parent.
- **When a subtree detaches, the root of the tree it left is re-checked.** That root's parents were all in its own subtree, or it would not be a root; the detached subtree may have taken one of them away, and if so the root is promoted under it. Take a ring `A hosts B`, `B hosts C`, `C hosts A`: `C hosts A` closed the cycle, so it is extra and `A` is the root. Delete `A hosts B` and `B` becomes a root with `C` beneath it, which leaves `A` with a parent, `C`, that is no longer beneath it — so `A` moves to `/B/C/A`. Without this rule `A` would stay a root forever, though the data is now a proper tree. No other root can be affected, because only that one tree lost members.

**Recovery follows from these rules.** They maintain three invariants: each resource has at most one materialized parent edge; the materialized edges form no cycle; and a root has no parent outside its own subtree. When the edges form a proper tree again, every parent is outside its child's subtree, so the third invariant forces every non-root to have its parent materialized — and the forest is exactly the tree. No sweep and no timer is needed; the edit that restores a tree restores its paths. A simulation of these rules checked it across thousands of random edit sequences with second parents, cycles and both delete orders: the invariants held after every event, every read matched breadth-first search over the same edges, and every sequence that converged to a proper tree ended with the forest equal to it.

**The paths depend on arrival order, not only on the edges.** Two instances fed the same events in a different interleaving may, while an anomaly is open, materialize different parents for the same resource. Both are valid, both answer identically, and both converge once the anomaly clears. The cost is that "what is stored" is no longer a function of the edges, so checking a store means checking the invariants above rather than comparing it to a recomputation.

#### Segments and encoding

**A segment names the edge that reaches a resource, and the resource it reaches.** `/ne:R-1/hosts:shelf:A-1/hosts:card:B-2/hosts:port:C-3` says that `ne:R-1` hosts `shelf:A-1`, which hosts `card:B-2`, which hosts `port:C-3`. Splitting the path on `/` gives the ancestry; the row's own resource is the last segment's reference, here `port:C-3`, and the second-to-last segment with the last segment's edge type is the resource's materialized parent edge.

A non-root segment is `edge-type:resource-type:id`, parsed by two splits at first occurrences of `:`, so the id is whatever remains, any colons included. Leading with the edge type makes a segment read in the direction the path runs. The root segment carries no edge type, having no parent, and is recognized by position, being always first.

**The tree's delimiters are its own business, so it encodes around them** (contract L10). Segments are built from the `(type, id)` pair rather than from its canonical string, so the tree's format is independent of whatever canonical serialization the deployment chooses. Within a segment, `%` and `/` are percent-encoded in every field, and `:` in the edge type and resource type fields; an id may keep a raw `:` because it is parsed last. Decoding on read restores the reference exactly. The range-read boundaries in §3.2 are unaffected: they are computed over the encoded prefix, which is the form the rows are sorted in, and an encoded segment never contains a raw `/`.

**Carrying the edge type keeps a path unambiguous.** Two edge types may join the same ordered pair of resources — a correction in flight leaves `hosts` and `powers` both connecting `ne:R-1` to `shelf:A-1` for a while. Only one of them is materialized, and the segment says which; the other is an extra edge like any other.

#### What each path table holds, and why

**`tree_path` holds nothing beside its key but three static columns.** Everything a row could carry is in the path: the resource is the last segment's reference, its type is that segment's second field, and the edge from its parent is the first. `root_ref` duplicates the first segment because it is the partition key, which is what makes a subtree range a single-partition read. In a forest a resource's ancestors and its subtree all have their rows in its own partition, so a partition's static columns describe everything a range read or a path split there can return, and a read learns from them whether to look further. `extra_tails` and `extra_heads` say where a read must hop; `anomalous_refs` says which results carry a qualification. All three are empty in the steady state.

**`tree_path_by_ref` answers *where is this resource* without knowing which tree to look in.** It holds exactly one path per known resource.

**`tree_anomaly` holds the current qualifications by resource**: for a cardinality breach, the rule and the breaching resource; for a cycle, its members and every edge among them, so a read can decide whether a given closure's admitted edges still form a cycle without walking anything (§3.2). It is written by the same procedure that writes paths, so it is never staler than they are.

**`since` makes a long-lived anomaly visible.** A flaw expected to last seconds and lasting days is invisible in the results, which stay correct, and shows up only as cost. Each anomaly record keeps the time it was first found, carried across the rewrites that leave it standing, and `tree_anomaly_by_age` lists every open anomaly in one partition ordered by that time. It is read by monitoring, never by queries, so the age of the oldest open anomaly, and how many there are, can be exported as a metric and alerted on.

#### Sizing

One tree is one partition of `tree_path`, so a tree must fit in one. At these path lengths ~100k rows is only tens of megabytes, so the number of rows is the limiting factor before bytes. Read latency is not the constraint either, since a subtree range reads a slice rather than the whole partition. Several hundred thousand resources in a tree is comfortable; a million is worth measuring first. What degrades is compaction and repair, which work on whole partitions.

The sharper limit is the subtree rewrite: moving a resource rewrites every path beneath it, so a reparent near the root of a large tree costs far more than a large tree does by itself. A reparent rewrites the subtree once whichever order its two halves arrive in: add-first stores the new parent as extra and moves the subtree when the old edge is deleted, and delete-first detaches the subtree to a transient root and moves it when the new edge arrives. An instance may hold as many trees as it likes — the limit is per tree, not per instance. Equipment sits three orders of magnitude inside all of this: an NE with 256 ports over four to six levels is a few hundred rows, and however anomalous the data, a tree never holds more than one path row per resource.

### 3.2 Reads

**The question this section answers is how a read built on one path per resource reaches everything the edges do.** For the closures this deployment declares, over data that is a proper tree, a stored path answers alone and the read costs are what the original design promised. Extra edges are where it does not, and the static columns say exactly where those are.

**A closure is uniformly directed when every pair it admits runs the same way relative to parents** — every one from parent to child, so that it reaches descendants, or every one from child to parent, so that it reaches ancestors. Which way a pair runs follows from its direction and the edge type's declared parent endpoint: with `hosts` declared parent-at-subject, `(hosts, forward)` runs parent to child. Both closures in the equipment instance are uniformly directed, and one is the inverse of the other, so a closure in each direction comes from one definition.

**Ancestors.** Read `tree_path_by_ref` for the resource, split the path, and walk upward while each segment's edge type is admitted. Then read the static columns of the path's partition, and for each admitted ancestor listed in `extra_heads`, read its parent edges from `tree_edge_by_child`; every parent reached by an admitted edge type and not yet in the result is a **hop**, answered by the same procedure from that parent's own path, until nothing new is reached. In the steady state that is two reads in sequence, the path and the statics, and no hops — one more than the original design's single read, which is the price of learning where the extra edges are.

**Descendants.** A **subtree range** under the resource's path: one contiguous clustering-key read within the root partition, because a child's path is its parent's path plus a segment, so descendants sort contiguously.

```cql
SELECT path, extra_tails, anomalous_refs FROM tree_path
 WHERE root_ref = 'ne:R-1'
   AND path >= '/ne:R-1/hosts:shelf:A-1/hosts:card:B-2'
   AND path <  '/ne:R-1/hosts:shelf:A-1/hosts:card:B-20';
```

The upper bound works because `'0'` (`0x30`) is the next codepoint after `'/'` (`0x2F`). The lower bound includes the resource's own row, so the read always returns at least one row and with it the partition's static columns; a sibling whose id extends this one's with a character below `/` can fall inside the bounds, so every row other than the resource's own is kept only if it begins with the path plus `/`. Then, for each admitted resource in the result that is listed in `extra_tails`, read its child edges from `tree_edge_by_parent`; every child reached by an admitted edge type and not yet in the result is a hop, answered by looking up that child's path and range-reading under it, until nothing new is reached. In the steady state that is two reads, the path and the range, and no hops.

**Why the hops make the answer complete.** Every edge is either materialized or extra. A materialized edge is covered by the path split or the range read, because it is part of the stored path; an extra edge is found because its endpoint is listed in the partition's static set, and the hop follows it. So the read follows every admitted edge from everything it reaches, which is breadth-first search over the edges, with whole stretches of materialized edges answered by one read instead of one per resource. A cycle needs nothing further: the edge that closed it is extra, and the hop across it finds a resource already in the result. In the ring `A hosts B`, `B hosts C`, `C hosts A`, a descendants read from `A` returns `A`, `B` and `C` from its range, sees `C` in `extra_tails`, follows `C hosts A`, and stops, since `A` is already there.

**What a hop costs.** Three reads in sequence: the edges of the resource being hopped from, the path of the resource hopped to, and then its range read or its partition's static columns. Hops from one resource run in parallel, and hops reachable only through other hops take further rounds. For a card moving between shelves, add-first, a descendants read from the new shelf and an ancestors read from a port beneath the card each take one hop, for as long as the move is in flight. For the pathological case of a feed that makes every card on a shelf also appear to host the shelf, an ancestors read from a port hops to all sixteen cards at once, one round of parallel reads. Nothing grows faster than the number of extra edges a query actually reaches.

**Closures that are not uniformly directed are answered by traversal.** The contract forbids a closure admitting both directions of one type (L2), but a closure may still mix directions across types — `hosts` forward with some other type reversed — and such a closure alternates up and down the tree, so no path read follows it. The tree answers such closures by breadth-first search over the edge tables, one partition read per resource reached. The conformance profile declares none: under the tree's parent endpoints all six of its closures are uniformly directed. Nor is one declared in this deployment's equipment instance, and one that became performance-critical would be a reason to reconsider whether the relationship belongs in a tree.

**Closures admitting only some edge types** stop at any segment whose edge type they do not admit, and **a type-restricted read returns what the admitted edges reach**: stopping at an unadmitted segment excludes that resource *and everything beneath it on that path*, because reachability requires a path made entirely of admitted edges. Every segment carries its own edge type, so the range read has already returned what the pruning needs, and the pruning is one linear pass: rows return ordered by path, so a parent always precedes its children, and a segment is admitted iff its own edge type is admitted and every segment between it and the argument was. A resource pruned here can still be reached by a hop across an admitted extra edge, and is then read from its own path like any other.

**Qualifications come from `tree_anomaly`.** Every resource in a result has its own row in some partition the read has already read — its ancestors and subtree share its partition, and each hop reads the hopped-to resource's partition — so the `anomalous_refs` of those partitions name every candidate, and only candidates in the result are looked up. A cardinality anomaly is reported when its breaching resource is in the result and the rule's edge type is admitted by the closure. A cycle is reported when the closure's admitted pairs, applied to a recorded cycle's edges, still contain a cycle and the result includes one of its members; that check is in memory, over edges the record already holds. The qualification carries its code alone, and the same computation answers the closure's cycle diagnostic, which reports the cycle groups with their edges (contract L28, W10, W36). Which results a qualification attaches to is now fixed by the contract (L27): every result containing a cycle member, or a resource breaching a rule on an edge type the closure admits.

**No derived store and no search index.** In the steady state each direction costs two single-partition reads. A projection would add refresh lag and a second store while answering the same questions no faster. An instance whose operators need to *search* resources by attribute has a genuine case for one, but that is search over attributes rather than traversal, and belongs to the instance rather than the implementation.

### 3.3 Writes

The implementation assumes writes are rare relative to reads. The cost of a materialized path is that moving a subtree rewrites every path beneath it, which is acceptable only because reparenting is infrequent.

**Writes are applied one event at a time by a single updater**, elected by the lease of §4.5. Two edge events often affect overlapping subtrees, and rewriting one subtree on two threads from the state each read at the start would leave whichever finished last without the other's change; applying events serially satisfies the contract's requirement that concurrent work produce some serial outcome (W34) trivially. How a deposed updater is fenced is not yet settled for the tree, whose writes are not the pointer flips the DAG fences (§8).

**Every edge event runs the same procedure:**

1. **Validate.** The edge type must be declared and its authored name used (contract W23), and the event's identifiers must agree with its triple (W25). A `delete` carrying more than its triple is honored, the extra members ignored (W20). Nothing about the graph is checked — not the parent count, not acyclicity — since those are properties of the edge set rather than of one edge, and refusing a well-formed fact over the state it lands in would lose it permanently.
2. **Apply the edge** to both edge tables. A redelivered `snapshot` or `delete` changes nothing here, and the procedure continues anyway, since a redelivery and the retry of a run that died part-way are indistinguishable from the payload.
3. **Place it in the forest** by the rules of §3.1. An added edge is materialized if its child has no materialized parent and its parent is not in the child's subtree — a prefix comparison of the two paths — and the child's subtree moves under the parent; otherwise it is extra, its flag is set, and its endpoints join `extra_tails` and `extra_heads`. A deleted extra edge leaves its endpoints in those sets only if they still have other extra edges. A deleted materialized edge promotes a remaining parent or makes its child a root, moving the subtree, and then the root of the tree the subtree left is re-checked. A resource seen for the first time gets a root path; one no longer mentioned by any edge loses its row.
4. **Move subtrees in a fixed order, and journal them.** Insert the new rows, with their static-set entries; then switch each moved resource's `tree_path_by_ref` to its new path; then delete the old rows. A read that overlaps the move sees each resource at its new path or its old one, and is never missing one that exists before and after. A move is written to a small journal before it starts, recording its old and new prefixes, and finished from the journal after a crash. Without the journal, a resource whose path had already switched would leave no record of where its old rows were, and stale rows would keep it under its old ancestors. This is the one place the forest needs more than redelivery to recover: the earlier design recomputed paths from the edges alone, but which parent is materialized depends on arrival order, so it can't be recomputed after the fact.
5. **Detect cycles.** An added edge closes a cycle exactly when its child already reaches its parent, which the writer answers with the read machinery above, over every edge type. Only then does it compute the strongly connected components over the child's descendants and rewrite the cycle records for their members; a cycle with any member there lies wholly there. A deleted edge can only break cycles, and only one whose recorded `cycle_edges` include it; the writer recomputes that record's components without the edge, in memory.
6. **Evaluate cardinality rules** for both endpoints of the edge, whose edge counts are the only ones that changed, from the edge tables (§4.8). Breaches and cycles are written to `tree_anomaly` and `tree_anomaly_by_age`, keeping the `since` of any that were already open, records no longer true are removed, and `anomalous_refs` is updated in the partition of every resource whose status changed.
7. **Commit the offset last.**

**A reparent, add first.** The new parent edge is extra: one edge write and two static-set entries, and no path changes. When the old edge is deleted, the new parent is promoted and the subtree moves once.

**A reparent, delete first — transient roots are normal.** Deleting the old edge leaves the card with no parent, so it becomes a root and its subtree moves to its own partition; when the new edge arrives it is materialized and the subtree moves again. The same happens without any reparent when an edge assigning a card to a shelf arrives before the shelf has been assigned to an NE: the shelf is a root until the NE-to-shelf edge arrives. Meanwhile a rule like `{edgeType: hosts, objectTypes: [shelf, card, port], minEdgesPerObject: 1, maxEdgesPerObject: 1}` is breached by a shelf hosted by no NE, so any result touching the shelf carries the qualification — expected to be transient, and what qualifications exist to cover.

**A cycle forms and clears on this path like anything else.** The edge that closes it is extra and produces a cycle record; the edge that breaks it removes the record, and if the break detaches a subtree, the old root's re-check rematerializes what the extra edge described. `A hosts B` then `B hosts A` leaves `A` at `/A`, `B` at `/A/B`, and `B hosts A` extra; deleting `A hosts B` makes `B` a root and promotes `A` under it, giving `/B` and `/B/A`.

**Destructive changes may need a guard the implementation cannot supply.** Whether a resource may be removed can depend on relations held by another instance. A tree instance applies the edge deletes it is given; such guards belong to the orchestrator. See §7.2.

### 3.4 Instance configuration

- **Edge types** — each with its name, an optional inverse name, and which of the two is **authored**, as in a DAG instance (§2, §4.6). Several are permitted: `hosts`, `slotted_into`, `mounted_in`. The tree constrains parents, not types. An instance refuses to start if both names of a type are authored.
- **Parent endpoint, per edge type** — `subject` or `object`, read against the authored name: which end of an edge is the parent. `(A, boggles, B)` with `parentEndpoint: subject` means `A` is `B`'s parent.
- **Closure definitions** — each with a name, its inverse's name, and on one closure of each pair the admitted `(edge type, direction)` pairs against authored names. Uniformly directed closures are answered from paths and hops; any other is answered by traversal of the edge tables (§3.2).
- **Cardinality rules** — declared bounds on a resource's edges of a type, each with a stable identifier, evaluated on every write (§3.3) and reported as qualifications (§4.8).

Closures are named and selected explicitly here as in every implementation, so consumers state their intent and the contract stays uniform.

**Why the parent endpoint must be declared.** A closure does not need it: `(boggles, forward)` and `(boggles, reversed)` are both well-defined without knowing which end is the parent, and an instance may define either. The writer needs it to build a path at all, since a path appends a child's segment to its parent's path, and so does the reader, to tell whether a closure is uniformly directed. Declaring it per type is what lets several types coexist when they are authored in opposite orders.

That leaves the one-parent expectation with no enforcement point in the writer, which is deliberate: it is a cardinality rule like any other, evaluated on write and reported rather than refused (§4.8).

---

## 4. DAG implementation (excerpt)

### 4.5 Write path: single updater, lease, fence

Writes to a DAG instance are serialized: exactly one process applies them for a shard at a time. This section covers how that process is chosen, how it holds the role, and what stops a process that has lost the role from continuing to write anyway.

```cql
CREATE TABLE dag_updater_lease (
    shard_key        text PRIMARY KEY,
    holder           uuid,
    lease_generation bigint,
    expires_at       timestamp
);
```

**Three mechanisms, each doing one job.** The **lease** elects a writer: a process that acquires one takes the role, and a process that has not acquired one does nothing. The **heartbeat** guards against that writer stalling or dying — if the holder stops renewing, the lease expires and another process may take it. The **fence** covers the gap neither of the first two can: a former holder that stalled, lost the lease without noticing, and then resumed, still believing it leads. The heartbeat cannot catch this, because the stall happens after a successful renewal and before the next one is due, so there is no missed renewal to observe. What catches it is that every write the process makes carries the lease generation it acquired, and that generation is now behind.

**A process that does not hold the lease does not consume the inbound topic at all.** This matters more than it may look. If a process consumed Kafka and separately checked for leadership, there would be two independent elections — the consumer group's partition assignment and the lease — and they can disagree. A consumer group that assigns partitions to a process which then finds it does not lead would stall those partitions entirely, since no other process is assigned them. Tying consumption to the lease keeps one election.

#### Acquiring the lease

A candidate reads the row for its shard. If the lease is unheld or `expires_at` has passed, it tries to take it, and the attempt must be a **compare-and-set against the generation it just read**:

```cql
UPDATE dag_updater_lease
   SET holder = ?, lease_generation = ?, expires_at = ?
 WHERE shard_key = ?
    IF lease_generation = ?        -- the value the candidate read
```

The condition is what makes the generation both monotonic and uniquely owned. Without it, two candidates could read generation 6, both decide the lease is free, and both write 7 — the table would end up with one holder, but *both processes would believe they held generation 7*, and both would stamp their writes with that fence. The fence would then accept both, because it has no way to tell them apart, and the instance would have two concurrent writers and no sign of it. With the condition, exactly one candidate's write succeeds; the other's fails, and it re-reads to find the lease already taken.

The first acquisition for a shard has no generation to compare against, so it uses `IF NOT EXISTS` instead. A lease row is never deleted once created — only superseded by a higher generation.

**The generation must come from the compare-and-set, never from a local counter or a clock.** It is the value the fence compares, so two processes computing their own could collide on the same number while believing they hold different terms.

#### Holding it: the heartbeat

The holder runs a background heartbeat that extends its expiry and changes nothing else:

```cql
UPDATE dag_updater_lease
   SET expires_at = ?
 WHERE shard_key = ?
    IF holder = ? AND lease_generation = ?     -- its own
```

**A renewal must not touch the generation.** Only acquisition increments it, because the generation marks a term of leadership rather than a tick of the clock. If a renewal bumped it, the holder's own in-flight procedure — stamped with the generation it held when it started — would fail its flip against state the renewal had just advanced, so a perfectly healthy leader would fence itself on every heartbeat.

The `IF` is what stops a deposed process from extending someone else's lease. A process that stalls past expiry, loses the lease, then wakes and renews would otherwise push out the new holder's expiry while still believing it leads. The condition fails instead, because `lease_generation` has moved on.

**A failed renewal means stop consuming, immediately** — not merely stop renewing. The process has lost the role, so it drops its Kafka subscription and may re-enter the acquisition loop as an ordinary candidate.

**Lease duration is 30–60 seconds, with the heartbeat at roughly a third of that.** Two consecutive misses are then survivable, which matters because a GC pause stalls the heartbeat thread and the work thread together rather than independently. Long is better than short here: a false expiry costs a leadership transition and a fenced mid-flight procedure, whereas a slow failover costs only dead time. A candidate also compares `expires_at` against its own clock while the value was written by a different process, so the duration has to absorb clock skew between them as well.

A process shutting down cleanly can set `expires_at` to now under the same condition, which releases the lease immediately rather than making the next candidate wait it out.

**Expiry is an explicit column, not a row TTL.** A TTL'd row disappears entirely and takes `lease_generation` with it, and a fence that resets to nothing is not a fence — a stalled process carrying generation 7 would then outrank a fresh leader starting again from 1.

#### Fencing the writes

The fence is the conditional flip in `publish_reach`. Every write carries the lease generation its procedure started under, and the flip applies only if the stored fence is not already higher. A deposed leader carrying generation 7 therefore fails every flip against state stamped 8, and fails it harmlessly: nothing is written, the procedure abandons, and its Kafka offset stays uncommitted.

**Recovery is the Kafka offset, and nothing else.** An abandoned procedure leaves its offset uncommitted, so the event is redelivered and re-run from the start by whoever holds the lease then. This works only because the write path has **no early exit on an empty diff** (§7.1): recomputing is idempotent, so re-running a procedure that had in fact completed is harmless, and no recovery log is needed.

#### Shard keys

**What a shard key is, and why it is `'global'` for now.** A shard key names which updater serializes writes for a subject, and `dag_updater_lease` holds one row per shard. The label must be shared across everything a procedure touches, because a procedure rewrites the whole affected set. Hence the invariant: **every subject whose affected sets can overlap must fall in the same shard.**

**A shard key is computed from the canonical reference, not stored.** It is a pure function evaluated from configuration — today the constant `'global'`, under a technology partition a lookup keyed by the id's type prefix. That is what allows there to be no subject table at all, and it disposes of a problem the stored version had: an assigned label needs a creation moment and a rule about what a new subject inherits, and a shared subject has many parents with none privileged, so there is no correct label to inherit. A function of the id has neither a creation moment nor anything to inherit from.

The cost is a constraint on the type vocabulary: any partitioning basis must be recoverable from the type. A technology partition therefore requires technology to be encoded in the declared types — `otn-path` and `eth-path` rather than `path` plus an attribute — since an attribute the instance does not hold cannot be consulted.

**Graph connectivity is not a viable basis.** Partitioning by weakly-connected component fails because shared resources merge components: one resource under three thousand others above it joins all of them. Followed honestly, that collapses the whole graph into one component.

A workable basis must come from the instance's domain — a type-compatibility rule such that the resources above a given resource always share its shard, making composition closed within a shard by construction. The invariant to enforce is exactly that, checked at edge-creation time as a type-level fact. §6 records the candidate for this deployment.

Under the single-updater model, **sharding becomes a deployment change rather than a concurrency change**: run N updaters, each holding its own lease row and consuming its shard's partitions. Until a scheme is chosen, the function returns `'global'` for everything and the write path is serialized graph-wide.

---

## 7. Worked procedures (excerpt)

### 7.4 B1 — initialize or rebuild from the compacted ingest topics

**Scenario.** Every ingest topic is compacted and keyed by its unit of complete state (§2), so together they hold the current state of the world. A rebuild **clears the fact tables** and reads each topic from its beginning; initialization is the same procedure starting from tables that happen to be empty already. There is no restore and no cutover: nothing is merged into existing data and no second copy is built, because every edge here is a reflection of a fact held upstream and can always be derived again. At start the rebuild records each partition's end offset, and reaching all of them is the completion signal.

The governing decision: **bulk mode consumes state, steady state consumes deltas.** A compacted topic is a snapshot of the world, not a sequence of changes, which makes the phase safe to parallelize and pointless to compute over until it ends.

**It is not quite one record per key, and that decides how it parallelizes.** Compaction is lazy — the active segment is never compacted and older segments are cleaned on the broker's schedule — so the log holds *at least* the latest record per key, sometimes alongside superseded ones — an edge `snapshot` still followed by the `delete` that retracted it. Order within a key therefore still matters. Partitions are consumed in parallel, since different keys describe disjoint state; each partition is consumed in order.

**Phase 1 — land ground truth**

1. **Enter bulk mode and report not-ready** — *to suspend machinery that is not merely wasteful here but wrong.* Per-event recompute, document production, and fenced publishing are disabled, and every `of` is refused with the contract's `not_ready` error until step 9 (W35). A closure computed over a partial graph is not a stale answer, it is a false one — and an empty one would assert that known resources are unknown.

   **Clear the fact tables, and only those.** `dag_edge`, `dag_edge_reversed`, `dag_reach`, `dag_reach_version`, the tree's edge, path and anomaly tables, and the projection index are all derived from upstream facts and are emptied. **`dag_updater_lease` is not.** It holds coordination state, not facts, and it is what the fence is drawn from: reset it and the rebuild's leader starts again at generation 1, below the fence of any stalled pre-rebuild writer, which could then wake and publish stale reach sets over rebuilt ones. The rebuild acquires leadership through the surviving table, so its generation exceeds every one before it.

2. **Write facts in parallel across partitions, in order within each** — *because records for different keys commute and records for one key do not.* Every write is blind by edge key: a `snapshot` inserts, a `delete` deletes, and a delete whose snapshot compaction already removed is a harmless no-op. No locks, no LWT, no traversals, no reads.

3. **Defer every validation** — *because a dangling reference is expected in this mode.* Cycle detection cannot even be attempted: a cycle is a property of the whole graph, and the graph is still arriving.

4. **Record the resume offsets** — *so steady-state consumption knows where to start.* The end offsets captured at start are the resume point; replay beyond them is idempotent, a gap is not recoverable.

**Phase 2 — on reaching the recorded offsets**

5. **Validate and audit** — *because this is the one point at which the instance sees the whole graph at once.* An event with an undeclared or unauthored edge type, or a malformed `delete`, is rejected and logged rather than applied, exactly as in steady state (contract W23, W25); deferring the check to here only batches it. Cardinality rules are evaluated over every resource — the instance's audit of rules the bridge is meant to enforce — and breaches are recorded as qualifications, as they would be in steady state, not grounds for halting: a correct answer over suspicious data is still a correct answer (contract §4, W35).

   Two checks earlier versions ran here are now meaningless rather than merely skipped: *edges naming absent subjects* and *subjects reaching nothing under any closure* both presupposed a table of subjects that an edge could fail to match. With existence defined by edges, a dangling reference is not a defect — it is a resource this instance has nothing further to say about, which is the normal case for every port.

6. **Compute every reach set in one condensed bottom-up sweep per closure** — *because the topological order is computed once over the whole graph here rather than per edit, so no BFS, no affected sets, no boundary reads.* One condensation pass per closure over its admitted pairs yields the components and their order; resources with nothing beneath them take their direct references, and each component above unions the sets below it. One pass per closure, linear in edges and touching each resource once — dramatically cheaper than replaying the load as N incremental W1s, which is the reason for deferring.

   This is also the most informative cycle report the system ever produces, being the only time the entire graph is examined at once. Components spanning many resources here are evidence about the feed rather than about those resources.

   **A tree instance builds its forest instead**, in one pass over the edges: each resource materializes one parent that is not beneath it, if it has one, and every other parent edge is extra — any such choice satisfies §3.1's invariants, so a rebuild may materialize different parents than the live instance did while an anomaly is open, with identical answers. Paths follow top down from the roots, cycles from one strongly connected components pass, and the anomaly records and the partitions' static columns are written at the same time, with `since` set to the rebuild's time, since a rebuild cannot know how long an anomaly stood before it.

7. **Publish at a generation seeded above any prior value** — *so nothing still in flight from before the rebuild can shadow it.* Epoch milliseconds at rebuild start needs no coordination.

8. **Verify counts** — *so a bad load is caught before it is served.* A failed rebuild is repeated from step 1; there is no previous store to fall back on, and none is needed, since the upstream facts are unaffected by anything that went wrong here.

9. **Report ready, leave bulk mode, and resume from the recorded offsets.**

**Notes.** **A rebuild is a planned unavailability of the instance**, for as long as it takes — the price of building in place rather than alongside. It is accepted deliberately: the data is recoverable by construction, and a second copy would buy availability during rebuild at the cost of space and a cutover. **Rebuild never merges.** Clearing first is what makes an absent key mean the same thing as a tombstoned one, and it is why there is no restore procedure: a restore would have to reconcile against existing data, and every hazard in that reconciliation is one a build from empty never meets. Steps 5 and 6 are where the whole-graph view pays: validation sees every dangling reference at once, and one condensation pass per closure supplies both the sweep order and a complete cycle report.

---

## 8. Open questions

- **Whether the service feed is well-behaved enough.** Dropping the rank rule trades *cycles are unrepresentable* for *cycles are detected once they exist* (§4.7). Against correctly-stated facts nothing fires. What is not yet known is how often facts arrive encoded wrongly or reparented out of order, and therefore how often a region is marked and how large those regions get. Worth instrumenting from the first bulk load, since B1 step 6 counts it for free.
- **Whether the implementation holds a `name` at all.** It is the last resource attribute anywhere in the instance and the only reason a subject table might return. The per-closure projection makes the case against it sharper: a document is now keyed by `(closure, subject)`, so a name would be duplicated once per closure per resource and would have to be rewritten across all of them when it changed — for a field no closure, law or write path reads. It exists solely so the projection can answer a partial-name search — the requirement that drove the Elasticsearch split in the first place — and nothing in any closure, law, or write path reads it. Either it stays as a declared exception to §2, reintroducing a one-column table, or name search is not a relationship implementation's job and the projection is joined against whatever service does hold inventory attributes. Until this is settled the projection's `name` field has no source in ground truth.

- **Whether the bridge should order the halves of a move.** A card moving between shelves arrives as a `delete` of the old parent edge and a `snapshot` of the new one, on different keys, in either order. The tree now holds both ancestries in between, so neither order loses anything and both converge (§3). What is still open is whether a bridge holding both halves in one diff should emit the add first: doing so keeps the subtree attached throughout and avoids a window in which the card has no parent and a closure from a port beneath it stops short. It is a preference rather than a guarantee, since the two halves may come from separate sources.

- **Deployment assertions for the properties that moved into topic configuration.** Two correctness guarantees now rest on Kafka setup that no implementation can verify at runtime: every ingest topic must be keyed by its unit of complete state (§2, replacing `source_version`), and a delete must be published as envelope-then-tombstone (§4.3). A mis-keyed ingest topic produces silently wrong state rather than an error. Worth a startup check that reads topic configuration and refuses to run, rather than trusting it.

   A third belongs beside them and is worse, because it cannot be checked even at startup: **one bridge per edge type** (§2). An instance enforces that each type is authored in one direction, which is a different thing, and does nothing to stop two bridges writing the same edge: they share a key, arrive in order, and still corrupt it — the later erasing the other's assertions, which it cannot see. Events carry no identity of their source, so this is purely a deployment discipline.

- **The canonical reference serialization, the edge id function, and who owns the type vocabulary.** The contract now fixes both: the serialization is `type:id` (L8, L9) and the edge id is `(subject>edgeType>object)` with `%` and `>` escaped (W21). Since storage constraints belong to the implementations that have them (L10), nothing lands on feed providers' ids. What remains open is who owns the type vocabulary across a deployment (contract §8).
- **What the shard key is derived from.** That the write path shards is settled — the lease exists for it, and a single global lease would need no key at all. What is open is the basis: connected components are ruled out (§4.5), technology partitioning is plausible but unverified, and §4.5 states what any candidate must satisfy.
- **Tree writes under a sharded DAG scheme.** A tree instance has no shard key of its own, so it runs under `'global'`. A card hosting ports of mixed technology would need coordination across every affected shard.
- **Whether the DAG needs a result ceiling.** The contract forbids truncation: a result is whole, or refused with `result_too_large` (W7). Neither implementation has a ceiling. The DAG's inverse direction is the one likely to need one, once card-level results are observed to reach the high hundreds, and the contract's expected direction beyond that is a streamed response with a terminating record, not cursors.
- **Attribute search.** Whether operators need to find equipment by attribute rather than by containment. If so, that instance gains a projection — on its own merits, not for symmetry.
- **The cost of condensation at the affected-set sizes that actually occur.** §4.7 is linear in the affected subgraph, which at dozens of writes a day is irrelevant. It has not been measured against the worst realistic case — an edit beneath a resource carrying 3,000 services, where the affected set is the expensive part whether or not it cycles.
- **Closure count and write amplification.** Every closure multiplies reach-set writes and projection fields. Two is comfortable; the point at which curation stops being enough has not been established.
- **Several trees in one tree instance.** One parent is a property of a tree, not of an instance (§2): a resource in two trees has two names, one per tree. The storage obstacle is gone — `tree_path_by_ref` is keyed `(resource_ref, path)` and reads already union across paths (§3) — so a resource with an ancestry in each tree needs nothing new. What is left is that the two ancestries would be indistinguishable: a closure admitting only one tree's edge types prunes by segment and gets the right answer, but a closure admitting both would union two unrelated hierarchies and call the result a subtree. Deciding this means deciding whether an instance's edge types are partitioned into named groups and whether a closure may span two, which is the same question as the item below reached from a different direction.

- **The DAG's qualification semantics.** The contract now fixes which results a qualification attaches to (L27): every result containing a cycle member, or a resource breaching a rule on an edge type the closure admits. The tree follows it (§3.2). The DAG as written in §4.7 reports a cycle flagged on any reach set the result consulted, which in its inverse direction can name a cycle none of whose members is in the result, so §4.7 needs revising to L27 before the DAG is built.

- **How the tree's updater is fenced.** The tree adopts §4.5's single updater and lease, but its writes are path inserts and deletes rather than the DAG's conditional pointer flips, so a stalled former leader that wakes could still write stale paths. Candidates are a conditional batch per `tree_path` partition against a static fence column, which covers the rows that matter most but not the by-reference index or the edge tables, or a write-ahead record of each event's intended diff that a new leader replays. Until it is settled, a tree instance relies on the lease alone.

- **The cross-instance guard in worked procedure W2 (§7.2), not to be confused with contract rule W2.** Removing a card is guarded by a read of the service instance's ground truth (`edges_into`), which is an operation outside the contract surface — the contract says `of` is the only resolution operation (L4). Either the guard is reframed as `of` over a closure admitting every edge type that can reference a port, which the contract already supports, or the contract gains a recognized operational surface for ground-truth checks. Separately, nothing serializes the guard against service provisioning now that the two instances have separate updaters (§7.2); the orchestrator needs a mechanism, whether a reservation on the ports or a re-check after the deletes are emitted.

- **Multiple instances per keyspace.** The implementation schemas assume one instance per keyspace, so an instance is a deployment. Co-locating instances would mean adding an instance discriminator to every partition key; worth doing only if instance count grows enough to make separate deployments burdensome.

---
