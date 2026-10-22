# Relationship closure implementation contract

**Status.** Draft. Normative companion to *Relationship closure implementations* (the design document). Where the design document argues, this document asserts.

**Audience.** Implementers of a relationship implementation, authors of the conformance suite, and consumers coding against an implementation.

**Conformance language.** MUST, MUST NOT, SHOULD, MAY per RFC 2119. Every normative statement carries an identifier — `L`n for laws, `W`n for wire-format rules — and identifiers run in document order.

**Numbering.** Identifiers run in document order. While this document is a draft they are renumbered whenever rules are added or withdrawn, so a reference is valid only within one revision. Once it leaves draft, identifiers become **stable**: a withdrawn identifier is retired and never reused, and a new rule takes the next free number even if that puts it out of document order.

**Relationship to the design document.** The design document explains *why* the contract has this shape — why `of` is the sole operation, why closure names are noun phrases, why the pair of inverse closures is unnamed, why a tree implementation must be indistinguishable from a DAG holding the same data. Those arguments are not repeated here and are not normative. This document is what a test asserts and what generated code implements.

---

## 1. Scope

### 1.1 What the contract constrains

This document is to a relationship closure implementation what SQL is to a database engine: the shape of the questions, and nothing about how they are answered.

| Database analog                                   | This document                                                                     |
|---------------------------------------------------|-----------------------------------------------------------------------------------|
| SQL                                               | this contract                                                                     |
| Postgres, MySQL                                   | an **implementation** of this contract — tree, DAG, composite, etc                |
| a running database, ready to hold tables and data | an **instance** — an implementation deployed                                      |
| a table — a name and a set of columns             | a **closure** — a name and a set of `(edge type, direction)` pairs                |
| SELECT ... FROM ... WHERE ...                     | `of` query against a named closure like "the (supporting equipment) of service X" |  

A **relationship closure instance** exposes closures and answers `of` against them (for example, "the supporting equipment `of` service ___"). What it provides is the closures; relationships themselves are facts it receives from sources of truth elsewhere (§1.5).

**The contract spans one boundary at each end: the events an instance consumes, and the queries it answers.** Everything between them is unconstrained. An instance may hold whatever it likes, publish whatever it likes to whatever internal topics it likes, and derive whatever stores it finds useful; none of that is visible here and none of it is tested. What is specified is the shape of a well-formed ingest stream and the shape of the answers that must follow from it.

Ingest is taken as already clean. How a bridge turns what its sources publish into that stream — diffing baselines, attributing scopes, deciding which source is authoritative for what — is a separate contract between a bridge and its sources, and the design document covers it. Here the stream is assumed to arrive in the form §6 describes.

The contract binds the running instance. "An implementation MUST…" throughout means *an implementation must be implemented so that its instances do*. It constrains:

- how a closure is named and selected
- what `of` accepts and returns
- how a resource is identified or referenced
- the laws the sets of resource references returned by `of` must satisfy
- the qualifications a response declares
- how a composite implementation's answers relate to its delegates'
- the wire format of requests, responses and ingest events

### 1.2 What the contract does not constrain

Storage, materialization strategy, traversal algorithm, latency, partitioning, and freshness are implementation matters. A conforming implementation MAY answer from a materialized reach set, a live traversal, a string split, or a delegation chain.

This is the point of a single suite: **the same tests MUST pass against a tree implementation, a DAG implementation, and a composite implementation**, with no knowledge of which is under test (§7).

### 1.3 Consistency is not part of the contract

An implementation MUST NOT declare a consistency class, and a caller MUST NOT be given one to depend on. The platform is eventually consistent throughout and acting on eventually consistent data is the norm.

The suite consequently treats *not yet visible* as a timing matter resolved by polling to a fixture-supplied timeout (§7), which is test configuration rather than a contractual bound.

### 1.4 Terms

| Term                    | Meaning                                                                                                                                                                                                                                                                            |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **this contract**       | a standard behavior independent of implementation.  Database analog: the SQL standard                                                                                                                                                                                              |
| **implementation**      | an implementation of this contract over a particular structure: tree, DAG, or composite<br/>Database analog: different implementations like MySQL or Postgres                                                                                                                      |
| **instance**            | an implementation deployed; the thing that answers a query<br/>Database analog:  an instance of MySQL that can answer SQL queries                                                                                                                                                  |
| **closure**             | a named, declared set of `(edge type, direction)` pairs, and the traversal it defines<br/>Database analog: a table with a declared set of columns and indexes to make specific queries efficient                                                                                   |
| **closure pair**        | the two mutually inverse closures arising from one declaration                                                                                                                                                                                                                     |
| **resource reference**  | a `(type, id)` pair; the type is a namespace (§2.3)                                                                                                                                                                                                                                |
| **canonical reference** | the single agreed serialization of a resource reference across all instances in a deployment                                                                                                                                                                                       |
| **edge**                | a typed, directed relationship between two resource references                                                                                                                                                                                                                     |
| **edge id**             | the id of an edge: its subject, edge type and object, written as W21 fixes — `(card:X>hosts>port:P1)`                                                                                                                                                                                   |
| **edge reference**      | an edge's canonical reference: `edge:` followed by its edge id — `edge:(card:X>hosts>port:P1)`                                                                                                                                                                                          |
| **scope**               | a set of edges some source asserts as a complete set like all the ports in a card; identified opaquely, and a source may own several. On an edge, `scope` is the union of the scopes asserting it (W22)                                                                            |
| **assertion**           | A source asserts that some relationship exists in its own vocabulary; a bridge converts that assertion into a statement about the existence of an edge (§1.5)                                                                                                                      |
| **baseline**            | an event in which a source states a scope's current members in full                                                                                                                                                                                                                |
| **known reference**     | one appearing in at least one of the implementation's edges (§2.4)                                                                                                                                                                                                                 |
| **reach set**           | the result of `of` for one argument under one closure                                                                                                                                                                                                                              |
| **qualification**       | a declared caveat on a result: that it was computed over a cycle, or over data breaching a declared cardinality rule (§4). Distinct from a *condition*, which in the alarms domain is a state of concern on a resource; the contract has no term for that and no opinion about it. |


### 1.5 The only structure the contract knows

**An implementation owns none of the resources it deals with.** It models relationships and nothing else, and knows nothing about any resource beyond its type and id. This is a standing constraint on every rule below, not a simplification: no rule in this document may be read as giving an implementation authority over a resource, or knowledge of one.

So the contract's entire model of an implementation's data is **a set of typed, directed edges between resource references**. An edge relates one `(type, id)` to another and carries an edge type. A closure admits some of those edge types, each edge type in a specified direction. A result is the set of references reached by traversing admitted edges starting from the reference(s) specified in the `of` request.

**An edge is an aggregation, which matters only at the boundary.** A source states a relationship in its own vocabulary, within some **scope** it asserts completely; a bridge, which knows the declared edge types, renders that as an **assertion** that one scope asserts one edge. Several scopes may assert the same edge independently, so an edge is whatever assertions currently stand for a given `(from, type, to)`, and it exists while at least one does. An implementation never sees assertions — a producer aggregates them and sends edges (§6.5) — so nothing in §2 to §5 depends on this. It surfaces in exactly one place: an edge carries the union of the scopes asserting it as a provenance attribute, which is why that attribute is a set and why it can change while the edge does not (W22).

Nothing else is visible, and nothing else is specified. An implementation MAY hold node records, materialized reach sets, paths, or none of these; the contract neither knows nor cares nor reveals. A tree implementation in particular holds something quite different — the design document describes it — and MUST still answer as though it held the edge set above. Where this document refers to an implementation's data, it means its edges and the references appearing in them.

> The design document uses **node** throughout, because storage is what it is about. That word is deliberately absent here. Introducing it would make the contract describe one model's internals and then require the others to emulate them, when what every implementation actually emulates is the edge set.

---

## 2. Surface

### 2.1 Selecting a closure

**L1.** An implementation MUST expose its closures by name. A name is a noun phrase; the contract does not constrain its spelling. Each closure is resolved at its own link (W5), so there is no default closure and no request that names none, and a closure the instance does not define has no link to send a request to.

**L2 — A closure admits each edge type in one direction.** A closure MUST NOT admit both directions of one edge type, and an instance MUST refuse to start with a configuration in which one does.

> Why rule out a closure such as "everything connected by `hosts`, up or down"? The relationships this contract models are directional, each with a named inverse, and a closure is a walk in one sense along each of them. A closure admitting both directions treats a type as symmetric, and then every edge of that type is a cycle with itself — `a` reaches `b` and `b` reaches `a` across one edge — so cycle detection (L27) would qualify every result it returns. Symmetric relationships are not modeled; a caller wanting both directions asks a closure and its inverse.

**L3.** An implementation MUST be able to enumerate its defined closures, so that a caller can navigate without out-of-band knowledge and a conformance run can check what an instance holds against the configuration it was meant to be given (§7). The enumeration MUST make the **closure pairing** explicit: for each closure it MUST identify the closure that is its inverse. Nothing about a closure's name says which closure inverts it — there is no `forward`/`reverse` alias, by design — so the pairing MUST come from the implementation, and L18 is untestable without it.

### 2.2 `of`

**L4.** `of` MUST be the only operation that resolves relationships. An implementation MUST NOT expose a second resolution operation, and MUST NOT offer direction vocabulary (`forward`, `reverse`, `up`, `down`) as a parameter anywhere in its request surface. The cycle diagnostic (W36) is not a resolution operation: it returns no result, only the cycles inside the one `of` would return.

**L5.** `of` MUST accept requests with a single resource reference and requests with a set of them. 

**L6.** The result MUST have set semantics. An implementation MUST NOT return duplicates and MUST NOT guarantee an order; a caller MUST NOT depend on order. An implementation MAY return different orders for identical requests.

### 2.3 Resource identity

**L7.** A resource MUST be identified by a `(type, id)` pair. An implementation MUST reject an argument carrying an id with no type. Ids are not unique across types — `1` may be the id for a port and a card — so a bare id does not identify a resource. An empty type or an empty id counts as absent: `{ "type": "port", "id": "" }` is rejected exactly as an id with no type is.

**L8.** The **canonical serialization** of a pair is its type, then `:`, then its id — `port:PT-B1` — and an implementation MUST NOT accept or emit a second form. Equality of references MUST be byte equality of the canonical form: an implementation MUST NOT case-fold, trim, or Unicode-normalize at comparison time. Normalization, if wanted, happens once at ingest before anything is stored.

> **Why one form, deployment-wide.** A composite intersects one implementation's reach set against another's. If the equipment implementation canonicalizes as `port:9` and the service implementation as `(port,9)`, the intersection is empty — and an empty intersection is indistinguishable from *no services over this equipment*. A serialization disagreement produces a confident wrong answer rather than an error. Elasticsearch enforces the same thing mechanically: `terms` is exact match on a keyword field and nothing normalizes a mismatch away.

**L9.** The canonical form MUST round-trip: serializing a `(type, id)` pair and parsing the result MUST yield the same pair. One rule is enough to secure that — **`:` MUST NOT appear in a type, and parsing MUST split at the first `:`**, so an id MAY contain `:` and anything else.

Nothing further is required of an id. A type is a controlled vocabulary and can be constrained; ids come from feeds this system does not control, and any character an implementation finds inconvenient is one some inventory already uses.

**L10 — Storage constraints belong to the implementation that has them.** An implementation MUST accept every well-formed reference (L7, L9). Where its own storage reserves a character — a path delimiter, a segment separator, a key encoding — it MUST encode around it and MUST NOT reject the reference, and that encoding MUST be invisible: what goes in comes back out unchanged.

> This is not a courtesy. If a tree implementation refused `port:PT/1` while a DAG implementation accepted it, the same data would be expressible in one and not the other, which breaks the design document's requirement that a tree be indistinguishable from a DAG holding the same data. An implementation's delimiters are its own business precisely because no contract rule may depend on them — so the obligation to cope with them is its own too.
>
> Encoding is safe in a clustering key, which is the case worth being sure of. Percent-encoding is not order-preserving against raw bytes, so sort order changes; but a subtree range boundary is computed from the *encoded* prefix and therefore lands in the same encoded space the rows are sorted in. Prefix containment survives, which is all a range read needs.

**L11.** An implementation MUST return every reference with the type it holds. An implementation MUST NOT substitute a placeholder type, and MUST NOT infer a type it does not hold. Because the type travels with every id, an implementation always knows the type of every resource in its edges.

### 2.4 Known references and the closed world

**L12.** A reference is **known** to an implementation when it appears in at least one of the implementation's edges, at either end. An implementation storing `link:LINK-311 terminates_on port:PT-B1` knows both, while holding nothing about either beyond its type and id.

> A resource in no relationship at all is therefore *not* known, and `of` over it returns the empty set (L16). That is deliberate: a relationship implementation has nothing to say about a resource that participates in no relationship, and any other answer would depend on whether the implementation happened to hold state for it — exactly the implementation detail §1.5 removes from view.

**L13.** An implementation MUST return only known references. A reference appearing nowhere in the instance's data MUST NOT appear in any result.

**L14.** An implementation MUST NOT reject or error on an unknown argument. `of` over an unknown reference is a well-formed question with an empty answer.

> **Note on the closed-world reading.** L13 says an implementation answers about its own graph and asserts nothing about resources outside it. A service implementation returning no containers for a port is not claiming the port has none; it is claiming not to know. The composite is the only component that can say more, because it is the only one holding both facts.

---

## 3. Laws

These are the conformance suite's spine. Each is independently testable, and each is expected to hold for **every** closure an implementation defines.

Throughout: `A` is a closure, `B` is its inverse from the same closure pair, `x` and `y` are resource references, `S` a set of them, `A.of(x)` the reach set.

### 3.1 Reflexivity and emptiness

**L15 — Reflexivity.** For every known `x`: `x ∈ A.of(x)`.

The argument is part of its own result. A caller wanting the strict set removes it; the contract does not offer a variant that omits it, because an implementation cannot know which the caller wants and the removal is trivial while the reconstruction is not.

**This holds whether or not the closure can move from `x` at all.** A reference is known if it appears in any edge (L12), admitted or not, so a resource reachable only by edge types a closure excludes is still known to it, and `A.of(x)` is exactly `{x}`. The empty set is reserved for ignorance (L16) and must not be returned for a reference the implementation has simply nothing further to say about under this closure.

**L16 — Emptiness characterises ignorance.** `A.of(x) = ∅` **iff** `x` is not known to the implementation.

L16 follows from L15 and L13 and is stated separately because it is the law consumers rely on. It makes *unknown* and *known but reaching nothing* distinguishable without a second operation: a known reference always returns at least itself, so an empty result carries exactly one meaning. Guards depend on it — §7.2 of the design document distinguishes *this port is in no service* from *I have never heard of this port*, and under L16 the two cannot be confused.

**L17 — Empty argument.** `A.of(∅) = ∅`.

### 3.2 Structure

**L18 — Inverse.** For every closure pair `A`, `B` and all `x`, `y`: `y ∈ A.of(x)` **iff** `x ∈ B.of(y)`.

The test MUST name both closures explicitly, taking the pairing from L3's enumeration. L18 is consistent with L15: reflexivity on one side requires reflexivity on the other, and L15 supplies it.

**L19 — Transitivity.** If `y ∈ A.of(x)` and `z ∈ A.of(y)` then `z ∈ A.of(x)`.

This is what makes the result a closure rather than a neighbour list, and it is the law most likely to catch a materialization bug: a reach set assembled from a partial sweep fails L19 long before it fails anything else.

**L20 — Idempotence.** `A.of(A.of(x)) = A.of(x)`.

Implied by L15 and L19. Worth testing separately because it exercises the set-valued argument path against the single-valued one.

**L21 — Distributivity over sets.** `A.of(S) = ⋃{ A.of(x) : x ∈ S }`.

A set-valued call MUST return exactly the union of the individual calls. This is load-bearing: it is what lets a composite resolve 32 ports in one request rather than 32 (design document §7.5), and it is what makes L20 well-defined.

It also settles the mixed case without a separate rule: a set containing unknown references contributes nothing for those and the known ones answer as they would alone, so an implementation MUST NOT reject a set because one member is unknown, and MUST NOT report which members contributed. A caller needing to know which of its arguments were known asks for them individually, or observes that each known one appears in its own result (L15).

**L22 — Closure independence.** `A.of(x)` MUST depend only on `A`'s declared `(type, direction)` pairs and the instance's data. Defining, redefining, or removing any other closure MUST NOT change it.

### 3.3 Type restriction is path-wise

A closure, as a set of admitted (edge type, direction) pairs, in a given instance answers queries exactly as if all non-admitted edges had been removed from the instance.

**L23 — Admitted paths.** `y ∈ A.of(x)` **iff** there exists a path from `x` to `y` every step of which is an edge whose `(type, direction)` pair is admitted by `A`.

If the only path from `x` to `y` crosses an unadmitted edge, `y` is not reached — and neither is anything below `y` whose only path crosses it. A result is prefix-closed under its argument.

L23 is the observable form of the design document's requirement that a tree implementation be indistinguishable from a DAG holding the same data. That requirement is stated there, in §3, as a property of the models; here only its testable consequence appears.

**L24 — Results are undifferentiated.** An implementation MUST return every reference in a result the same way. It MUST NOT mark, role-tag, rank, or otherwise distinguish one reference from another — by how it was reached, by which edge type led to it, or by anything the implementation happens to hold about it. An implementation MUST NOT treat the non-existence of a referent elsewhere as an error.

L24 concerns roles, not types. The type is always present (L11); what is absent for every reference alike is any claim about what the resource is for or whether it exists.

---

## 4. Qualifications

**L25.** An implementation MUST report a qualification on any result computed over data the instance treats as malformed. Two are defined:

- `cycle_detected` — the result contains a cycle of the closure's admitted edges (design document §4.7)
- `cardinality` — the result contains a resource breaching a declared cardinality rule (W4) on an edge type the closure admits (design document §4.8)

**L26.** A qualified result MUST still be returned. An implementation MUST NOT express a qualification as an error or as an empty set. The set returned is the correct set of reachable resources; the qualification simply qualifies it.

**L27 — What a qualification attaches to.** Which results does a breach qualify — only the one whose argument sits on it, or every result that reaches it? Every result that reaches it. A result `A.of(x)` MUST carry `cycle_detected` if any reference in it lies on a cycle of `A`'s admitted edges, and `cardinality` if any reference in it breaches a rule whose edge type `A` admits, in either direction. So if `x` reaches a cycle several hops away, `x`'s result is qualified.

The two phrasings of the cycle case agree: every member of a cycle reaches every other, so a result containing one member contains the whole cycle, and "the result contains a cycle" and "a member of the result lies on one" are the same condition.

Nothing outside the result qualifies it. A cycle or breach elsewhere in the instance, or one formed only by edge types `A` does not admit, played no part in computing `A.of(x)`, and reporting it would make every result in an instance carry every caveat the instance holds. Qualifications accumulate as a union across a set-valued call (L21) and across a composite's delegates (L32).

**L28.** A qualification MUST identify what it arose from. For `cardinality`, it is the rule breached, by its published identifier; the references in the result breaching it — at least ten, or all of them if there are fewer; and the total number breaching it. A response carries one `cardinality` qualification per rule breached. For `cycle_detected`, the code alone suffices, and a response carries at most one: which references and edges form the cycles is the business of the closure's cycle diagnostic (W36), asked with the same arguments.

> Why do the two differ? A breach is a fact about particular members of the result, and a caller acts on it from the answer itself: a guard needs to know that *this* resource has two parents. A cycle tells a caller something different — that the data beneath the answer needs repair — and repairing it takes the edges to cut, which an `of` response has no place for. So the qualification says only that the answer rests on a cycle, and the diagnostic, shaped for repair, says where.

> Specificity is what makes a qualification actionable rather than noise. Breaches are routine and transient by design — a link has one port between its two edge events, a resource has two parents while a reparent is in flight — so a caller refusing on the bare presence of a qualification would refuse during ordinary writes. A guard on a destructive operation needs to know that *this* resource has two parents, not that some link elsewhere is mid-provisioning.

> Why a list with a floor, and why a count? Breaches are usually transient and few, and an implementation could name just one. But a caller recovering a system who is shown one breach fixes it, believes the system healthy, and finds the next — and then the next. Naming up to ten at once removes that, in the way a compiler reports a batch of errors rather than stopping at the first. The count says when the list is not the whole story: ten breaching references out of ten is a repair job, and ten out of four hundred points at something a reload fixes rather than one-by-one patching. Counting costs work proportional to the result, which an implementation has already computed in full (W7).

**L29.** A caller MUST be able to act on a qualification. An implementation MUST NOT suppress one on the grounds that most callers ignore it, and MUST NOT resolve one on a caller's behalf by filtering the result.

---

## 5. Composite implementations

**L30.** A composite MUST satisfy every law in §2–§4. A caller MUST NOT be able to determine from the contract surface that an implementation is a composite.

**L31 — Composition.** A composite closure MUST be declared as an ordered list of stages — a delegate closure for the first, and a delegate closure plus a join for each one after — and its result MUST equal the result of performing that composition by hand. A **join** is a non-empty set of resource types; two instances may meet at more than one, as service and equipment do once a service can terminate on a sub-port as well as a port. Writing `Sᵢ` for the output of stage `i` and `Tᵢ` for the join selecting what feeds it:

```
S₀      = D₀.of(x)
Sᵢ      = Dᵢ.of( { y ∈ Sᵢ₋₁ : type(y) ∈ Tᵢ } )
C.of(x) = S₀ ∪ S₁ ∪ … ∪ Sₙ
```

**The result is the union of every stage's output, and a join filters only what passes forward.** Returning the last stage alone would violate L15: no later delegate has heard of `x`, so `x` would be absent from its own result.

**A join is required and must be non-empty, though correctness does not depend on it.** An instance returns empty for a reference it does not know (L14), so forwarding a `card` to a service instance yields nothing and an unfiltered join would give the same answer in a correctly configured deployment. What the join buys is traffic — thirty-two ports forwarded rather than thirty-two ports and two hundred cards — and a written record of where two domains are intended to meet, so that a type they come to share by accident does not quietly become a path between them. An empty join is well-defined and useless, composing nothing, and MUST be rejected at configuration.

The number of stages is not constrained. A composite MAY itself be a delegate, and chaining produces the same set as a longer list — the list exists so a chain need not name every prefix of itself, not as a second semantics.

**L32 — Qualification union.** A composite MUST report the union of every delegate's qualifications, at every stage. Its cycle diagnostic (W36) likewise MUST report the union of every delegate's cycles, at every stage.

**L33 — No cross-delegate snapshot.** A composite MUST NOT claim that its delegate reads form a consistent snapshot of one another. Delegates are separate implementations with separate stores, read in sequence; a write landing between two stages is visible to the later one and not the earlier. The suite MUST NOT assert cross-delegate atomicity, and an implementation MUST NOT advertise it.

> With no consistency class to declare (§1.3), L33 is the whole of what a composite says about freshness — and it is worth saying, because staleness and internal disagreement are different failures. A composite fully caught up on every delegate still read them a moment apart.

**L34 — A composite returns a plain set.** A composite MUST NOT attach join-point attribution, delegate provenance, or any other per-result metadata a single-instance implementation does not produce. Its response MUST be byte-for-byte the shape of any other implementation's.

> A composite knows which join point each result came through and declines to report it. Reporting it would give a composite's response a shape no single-instance implementation has, and a caller could then tell a composite from an instance by inspecting a result — which is exactly what L30 forbids. A caller wanting attribution asks a second question about the specific result, which is one more round trip on a path that is not hot.

---

## 6. API and wire formats

All payloads follow **JSON:API**. Three shapes are specified: **requests** (§6.2), **responses** (§6.3) and **ingest events** (§6.5), with errors (§6.4) and the guarantees an ingest topic provides (§6.6). The contract specifies no URL structure: a caller, including this conformance test suite, is given the address of one discovery document and follows links from there (W4). Anything an instance publishes for its own use is internal (§1.1) and not specified here.

### 6.1 Identifiers on the wire

**W1.** The `type` member of a resource object MUST be the resource's type as the implementation holds it (L11) — `circuit`, `path`, `link`, `shelf`, `port`. An implementation MUST NOT emit a placeholder type.

**W2.** The API surface carries the pair structurally: the JSON:API object is already `{type, id}`, so nothing parses a serialized form at the boundary. The **canonical serialization** (L8) is used wherever a single value crosses a boundary — closure-array members, Elasticsearch keyword terms, Kafka record keys, and any payload position. An implementation's internal encodings are its own (L10), provided a reference that goes in comes back out unchanged.

**W3.** Where a payload carries a canonical reference in a single-value position, it MUST be the canonical form verbatim and MUST NOT be a re-derived or re-formatted variant.

### 6.2 Requests

**W4 — Discovery.** An instance MUST publish a discovery document describing what it provides and where (kafka topics and API endpoints). It is the one thing a caller needs to be told how to reach; everything else is found by following it. An instance MAY expose the document, and the endpoints it links to, at whatever hostname and path suit its deployment — behind a gateway, under an ingress prefix, at a cluster-local name — and the contract says nothing about URL structure.

The document MUST carry:

- each **closure**, with its name, the name of its inverse (L3), a link to its resolution endpoint (W5) and a link to its cycle diagnostic (W36); and for **exactly one closure of each pair**, the `(edge type, direction)` pairs it admits; which of the two carries them is the deployment's choice
- each **edge type**, with its name, its inverse name if declared, and which of the two is authored (W23)
- each **cardinality rule**, with a stable identifier (L25) and its definition, read as described below
- the **ingest topic** the instance consumes

The admitted pairs matter most, because L23 defines reachability in terms of them. A closure publishing only its name would leave the contract's central structural law stated over something no caller can see.

**A pair's definition is stated once; each closure in it is reached on its own.** A closure's inverse admits exactly the same edge types with every direction flipped, so stating both sets would repeat derived data — two copies that could disagree, with nothing to say which is right. One closure of each pair therefore carries `admits`, and its inverse carries none: the inverse's admitted pairs are found by taking its inverse's and flipping each direction.

Access is a different matter, and stays symmetric. A closure and its inverse are two closures, each with its own name and its own entry, and each is reached only by its own `of` link. Neither is reached through its relationship to the other — there is no `inverseOf`, and the pair itself has no name and no endpoint — because a second route to a closure, defined by its relation to another, is the direction vocabulary L4 forbids.

**Direction is relative to the authored name.** A type has two names, and walking `actively_transports` forward goes the opposite way to walking `actively_transported_by` forward, so a direction means nothing until it says which name it reads from. The `edgeType` in an admitted pair MUST be the authored name (W23), and `forward` means from subject to object as that name reads. This is also the vocabulary ingest events use, so the discovery document and the stream describe edge types in the same terms.

**That restriction is on what an instance publishes, not on how it is configured.** Could a closure be declared against the unauthored name instead, keeping its direction when the name is the authored one and flipping it when it is not? It could: `(feeds, forward)` and `(fed_by, reverse)` denote the same traversal, and the mapping between them is total and loses nothing. So an implementation MAY accept either name wherever it takes its own configuration, and the contract has no opinion about that — it constrains the boundary, and a configuration file is not one (§1.1). What it MUST do is normalize before publishing, so that one traversal has one spelling in the document and in the stream alike, and a caller comparing two closures' admitted pairs can compare them as given rather than having to learn which names alias which.

Normalization has to come first in one place in particular. L2 forbids a closure admitting both directions of one edge type, and `(fed_by, forward)` alongside `(feeds, forward)` is exactly that while reading like two unrelated types; an implementation checking L2 against the names as written would let the configuration it is required to refuse start up.

> **This is a weaker rule than it looks, and weaker than W23's.** An ingest event's `edgeType` must be the authored name because the edge id is computed from it (W21): two spellings would key one fact twice, so a `delete` under one could not retract an assertion under the other, and recovering means rebuilding from empty. An admitted pair never becomes a key. Nothing here is load-bearing in that way — the reason is legibility for callers, which is worth a normalization step and no more.

**The ingest topic is the instance's to declare.** It knows which topic it consumes, so the document names it, and a caller need not be told separately. What it does not name is a broker address, because an instance knows its topic but not necessarily an address another party can reach — that stays with whoever is connecting.

**A cardinality rule bounds how many edges of one type a resource may have at one end.** "Every shelf, card and port sits in exactly one parent" is a rule about `hosts` counted at its object end: for each known resource of type `shelf`, `card` or `port`, the number of `hosts` edges with that resource as object is at least 1 and at most 1. A rule therefore states:

- `edgeType` — the edge type, by its authored name (W23); `object` and `subject` read as that name reads
- for the object end, `objectTypes` with `minEdgesPerObject`, `maxEdgesPerObject` or both; or, for the subject end, `subjectTypes` with `minEdgesPerSubject`, `maxEdgesPerSubject` or both

A rule constrains one end. A bound on both ends of one edge type is two rules, each with its own identifier, so that a qualification naming a rule names one bound. Since an edge is identified by its triple (W21), two edges of one type at one object always have different subjects, so `maxEdgesPerObject: 1` can equally be read as "at most one subject".

Why list the types a rule applies to, rather than the ones it exempts? A minimum is breached by a resource with *no* edge of the type, so a rule must say which resources it ranges over independently of the edges themselves. An exemption list ranges over every known reference not on it, including resources known only through unrelated edge types — a `link` known through `terminates_on` would breach "everything has a `hosts` parent" — and that range widens silently whenever a new type is introduced. Listing the types a rule applies to keeps its reach where it was written.

```json
{
  "data": {
    "type": "instance",
    "id": "equipment",
    "attributes": {
      "ingestTopic": "equipment-edges",
      "closures": [
        { "name":    "contained-equipment",
          "inverse": "containing-equipment",
          "admits":  [ { "edgeType": "hosts", "direction": "forward" } ],
          "links":   { "of": "https://gw.example/equip/v1/closures/contained-equipment/of" } },
        { "name":    "containing-equipment",
          "inverse": "contained-equipment",
          "links":   { "of": "https://gw.example/equip/v1/closures/containing-equipment/of" } }
      ],
      "edgeTypes": [
        { "name": "hosts", "inverse": "hosted_by", "authored": "hosts" }
      ],
      "cardinalityRules": [
        { "id": "one-parent", "edgeType": "hosts",
          "objectTypes": ["shelf", "card", "port"],
          "minEdgesPerObject": 1, "maxEdgesPerObject": 1 }
      ]
    }
  }
}
```

**W5 — Resolving a closure.** A closure is resolved at the link its discovery entry gives. A request names one closure — by which link it is sent to — and one or more resource references. A single reference and a set are the same request differing only in length:

```
POST {link}
```

with the references as resource reference objects in the body:

```json
{ "data": [ { "type": "port", "id": "PT-B1" },
            { "type": "port", "id": "PT-B2" } ] }
```

An empty set is well-formed and returns an empty result (L17).

An instance MAY also accept `GET` on the same link, carrying each reference as a `ref` query parameter in its canonical serialization:

```
GET {link}?ref=port:PT-B1&ref=port:PT-B2
```

It answers exactly as the `POST` would. The references travel in the query string rather than in the path because a canonical reference may contain `/`, and an encoded slash in a path segment is refused or decoded-and-rerouted by much of the infrastructure an instance may sit behind; a query string is not routed on, and intermediaries leave it alone. The `GET` exists for humans and tools that want something cacheable and pasteable, and nothing in this contract depends on it — the suite uses the `POST`.

**W6 — Media types.** An implementation MUST accept a `POST` whose `Content-Type` is `application/vnd.api+json`, the JSON:API media type, and SHOULD also accept `application/json`. Its responses SHOULD carry `application/vnd.api+json`. JSON:API's negotiation rules beyond this — refusing media-type parameters with 415 or 406 — are not required: they test an HTTP framework rather than a closure computation, and a caller of this contract gains nothing from them.

**W7 — A result is complete or refused.** A successful response MUST carry the whole result. An implementation MUST NOT truncate, page or otherwise return part of a result, and an implementation unable to return a whole result MUST reject the request with `result_too_large` (W11) and return no references at all.

> Why refuse rather than return what fits? A partial answer is useful to almost no caller of this contract. A guard deciding whether a destructive operation is safe needs every affected resource, and a set missing members it cannot see is a confident wrong answer — the one failure a caller cannot detect. A refusal is detectable, and a caller can act on it. Pagination would also bring its own problem: pages are read at different times from a store that is changing, so their union matches no single moment.
>
> Whether an implementation has a ceiling, and where, is its own business. Even thousands of references make a response of a few hundred kilobytes, so the expectation is that most implementations never refuse. Should results grow beyond what one response carries comfortably, streaming with a required terminating record — whose absence marks the stream incomplete — is the expected direction, rather than cursors.

### 6.3 Responses

**W8.** A successful `of` MUST return HTTP `200` with a document whose primary data is an array of resource reference objects, one per reached reference. An empty result and a qualified result are both successes (L14, L26).

**W9.** Response `meta` MUST carry the closure name, the argument set, and the qualifications (L25). It MUST NOT carry a consistency class (§1.3), and MUST NOT carry per-result metadata (L34).

```json
{
  "data": [
    { "type": "path", "id": "PATH-200" },
    { "type": "link", "id": "LINK-311" },
    { "type": "port", "id": "PT-B1" },
    { "type": "port", "id": "PT-B2" }
  ],
  "meta": {
    "closure": "active-path-constituents",
    "of": [ { "type": "path", "id": "PATH-200" } ],
    "qualifications": []
  }
}
```

`PATH-200` appears in its own result, per L15. The ports carry type `port`, per L11, though the service implementation holds nothing about them beyond that.

**W10.** A qualified response MUST name the qualification and what it arose from (L28). A `cycle_detected` carries its code and nothing else; the cycles themselves are found through the closure's cycle diagnostic (W36). A `cardinality` names the rule by the identifier W4 published, lists breaching references under `sources`, and gives the total as `total`:

```json
{
  "meta": {
    "qualifications": [
      { "code": "cycle_detected" },
      { "code": "cardinality",
        "rule": "one-parent",
        "sources": [ { "type": "card", "id": "CD-3" } ],
        "total": 1 }
    ]
  }
}
```

### 6.4 Errors

**W11.** A rejected request MUST return a JSON:API `errors` document whose members identify the failure well enough to act on. Each error MUST carry a `code` from the set below, and the members the table lists for it.

| `code` | Status | Raised when | Carries |
|---|---|---|---|
| `reference_malformed` | `400` | an argument carries an id with no type, an empty type or id, or does not parse as a canonical reference (L7, L9) | `source.pointer` and `meta.argument`, below |
| `result_too_large` | `422` | the whole result exceeds what the implementation will return in one response (W7) | — |
| `not_ready` | `503` | the instance's internal state is not currently a correct closure over the events it has applied (W35); the response SHOULD carry `Retry-After` | — |

A response carrying an error MUST carry the status the table gives for its code. Where a response carries several errors, they MUST share a status class, and the response status is that of the first.

A `reference_malformed` error locates and repeats the argument it rejects, in the members JSON:API defines for that:

- `source.pointer` — a JSON Pointer (RFC 6901) to the argument within the request document, such as `/data/1`
- `meta.argument` — the argument as received, as a JSON value rather than a string: an object stays an object and a bare string stays a string, so a caller compares it with what it sent rather than parsing it out of prose

`detail`, which JSON:API defines as a human-readable explanation, MAY say what was wrong in words. Nothing may depend on its content.

```json
{
  "errors": [
    { "status": "400",
      "code": "reference_malformed",
      "source": { "pointer": "/data/1" },
      "meta": { "argument": { "id": "PT-B2" } },
      "detail": "an argument with no type" }
  ]
}
```

**W12.** An unknown *reference* is not an error (L14) and MUST NOT appear in an `errors` document. Nor is a qualification (L26): a qualified result is a success.

**W13.** An implementation MAY define further codes for failures the contract does not enumerate, but MUST NOT reuse a code above for a different failure, and MUST NOT collapse two of them into one.

### 6.5 Ingest events

**An event is a statement that something changed.** That something is the event's **subject**. Every event therefore has two objects in play, and the envelope keeps them separate:

- the **outer** resource object *is the event*: its type is the kind of change, its id identifies this occurrence
- the **inner** resource object *is the subject*: its type and id are the subject's own, and its members carry the state being asserted about it

Throughout §6.5 and §6.6, "the event" means the outer object and "the subject" means what the inner object describes.

**The subject of an ingest event is an edge**, and the only event types are `snapshot` and `delete` of an edge. Everything else an instance may publish is internal (§1.1) and outside this contract.

**W14 — Ingest events are about edges and every event is complete for its key.** An ingest topic carries events about individual edges, never about resources and never about sets of edges. An implementation MUST NOT consume resource-level domain events — *this card looks like this now* — and MUST NOT consume a baseline, which asserts a whole scope in a single message.

> Both are resolved upstream, by a bridge the design document describes. A resource-level event needs translating into edges of this instance's declared types, which takes domain knowledge an implementation is designed not to have. A baseline — *these are all of card X's ports* — needs diffing against what was previously asserted to find what it implicitly removed, which takes prior state; the bridge holds that state as its own compacted output, and emits the result as edge `snapshot`s and `delete`s.
>
> The reason a baseline cannot reach an implementation's topic is compaction. A compacted topic is a correct rebuild source only if every key's current state is determined by records on that key alone. A baseline determines the state of many edge keys from one record on another key, so a later edge `delete` retracting part of it compacts away while the baseline itself survives — and a consumer rebuilding from empty then reads an edge that no longer exists. That is not the familiar hazard of a lagging consumer missing a delete; it corrupts a fresh one, which is the property that makes compaction worth having.

**W15.** The outer resource object's `type` MUST be the event kind: `snapshot` or `delete`.

**W16.** The outer `id` MUST be unique per event and MUST NOT be the subject's id. It identifies the event for deduplication and tracing.

**W17.** Outer `attributes` MAY carry `sourceVersion`, a producer-assigned version of the asserted state. It is advisory: a consumer MUST NOT use it to order, reject, or deduplicate events, because ordering is a property of the transport (W24) rather than of the payload. It is useful for tracing and for reconciling against a source system.

- for `snapshot`, `attributes` MUST carry the inner resource object under `snapshot`
- for `delete`, it MUST carry one under `delete`, holding only what identifies the edge (W20)

**W18.** Outer `relationships` SHOULD carry `resource`: a resource reference object naming what the event is about. It duplicates the inner object's `type` and `id`, and exists so a consumer can route and filter without parsing the payload.

**W19.** The inner resource object MUST use the resource's type as its `type` and the resource's id as its `id`. On an ingest topic its `relationships` carry the edge's endpoints as W20 specifies. Its `attributes` MUST be ignored, `scope` (W22) included: an edge is a resource and carries attributes, but an implementation knows nothing about any resource beyond its type and id (§1.5).

#### Edges

**W20 — An edge is a resource.** It has a lifecycle of its own, so it takes the same kinds a resource does in its own domain: `snapshot` asserts that the edge exists, `delete` asserts that it no longer does. Neither says anything about any other edge.

**Every edge has the same resource type, `edge`.** Which relationship an edge expresses is data, not type: all edges carry two endpoints, an edge type and a scope, so the shape is one shape and `edgeType` is an attribute of it. A schema or a router therefore stays fixed when a new edge type is declared.

**An edge is identified by its triple**, and every event about one carries that triple in full, whichever kind it is. The inner object's `type` MUST be `edge`, and it MUST carry:

- `attributes.edgeType` — the edge type's **authored name** (W23)
- `relationships.subject` — a to-one relationship to the endpoint the authored name reads from
- `relationships.object` — a to-one relationship to the endpoint it reads to

so the inner object reads as the triple it is: *subject, edge type, object*.

A `snapshot` adds whatever else it has to say — `scope` (W22), and any attributes an implementation will ignore. A `delete` needs only the triple, since there is nothing to say about an edge that no longer exists. Anything further it carries is ignored, as W19 requires of every inner attribute, and the delete is honored: a delete that identifies its edge unambiguously retracts it, whatever else it says.

**W21 — An edge id is a deterministic function of its triple.** The id is what every event about one edge is keyed by, so compaction works only if the same triple always yields the same id — across producers, across restarts, and over time. A deployment therefore fixes one function from `(edge type, subject, object)` to an id and every producer computes it; an id assigned rather than computed would need a map that survives restarts and agrees between producers, which is strictly harder than computing one.

The contract fixes the function, so that every producer, every implementation and the conformance suite compute the same id without configuring one. **An edge id is the edge's subject, edge type and object, in that order, separated by `>` and enclosed in parentheses:**

```
(card:X>hosts>port:PT/1,2)
```

The subject and object are written as canonical references (L8), the edge type as its authored name (W23), and the order is the order the authored name reads: *card X hosts port PT/1,2*. Within each of the three components, `%` is written `%25` and `>` is written `%3E`, in that order and in upper case; nothing else is escaped. A component therefore never contains a bare `>`, and an id splits into exactly three components at its separators once its first and last characters are removed. The parentheses are positional — always the first and last characters, never searched for — so an id containing `(` or `)` needs no treatment.

The function constrains nothing a reference may contain: it escapes its separator rather than forbidding it, as L10 requires. And it is computed over the authored representation (W23): only that representation is ever written, so one fact yields one key.

**Why `>` rather than a comma, when a comma is the conventional triple separator?** Because escaping is what a reader actually sees. Equipment ids carry commas routinely — `PT/1,2` is an ordinary port id — so a comma separator would escape them to `PT/1%2C2` in every context the id appears: the record key, the log line, the payload. A `>` is rare in equipment naming, so escaping almost never fires and ids stay literal. It also reads in the direction the authored name reads, which is the direction W23 fixes.

The cost is that `>` is not allowed unencoded in a URI query (RFC 3986 permits only the unreserved characters and the sub-delimiters there), so an edge reference in the `GET` form of W6 appears as `edge:(card:X%3Ehosts%3Eport:PT/1,2)`. That is accepted rather than worked around: an edge reference is an argument to `of` only where edges are themselves endpoints of edges, which this contract does not anticipate, while the record key and the log line are read constantly.

**An edge id is not an edge reference.** The id is what the function produces and what an inner object's `id` carries (W20). The edge's **reference** is the canonical serialization of `(edge, id)`, like any other resource's — `edge:(card:X,hosts,port:P1)` — and is what keys its records (W24). The `edge:` prefix comes from the reference convention, not from the id function.

> Why parentheses the parser does not need? An edge reference is a reference whose id contains two more references, and without them `edge:card:X>hosts>port:P1` reads at a glance as though `edge:card` were a type. The parentheses make the triple read as one thing, at the cost of two characters, and they make a cut-off id visible: an id that does not end in `)` is not an edge id.
>
> Why a readable id rather than a digest? An operator reading a topic or a log can see which edge a record is about, and a consumer can filter on an endpoint without an index. A digest would bound key length however long references run, which has not been worth the opacity.

> There is no resource-level `delete` on ingest. When a resource leaves, its bridge emits a `delete` for each edge it authors. Edges authored from the other end are that end's to retract; until they are, the departed resource remains known (L12), which is correct — a card removed from inventory while its shelf still lists it is a disagreement between sources, and an implementation does not adjudicate it.

#### Authorship and keying

**W22 — `scope`.** A **scope** is a set of edges some source asserts completely, and an **assertion** is one scope asserting one edge. An edge is the aggregation of the assertions currently standing for one `(subject, edge type, object)`, so it carries a scope attribute of its own: the union of the scopes asserting it. An edge event's inner object MUST carry that union as `attributes.scope`. Usually one element; more where several scopes assert the same edge. It MUST NOT be empty — an edge no assertion stands for is a `delete`, not a `snapshot` with no scope.

> An edge's scope is derived, not independent: a producer MUST compute it from the assertions it holds and MUST NOT set it to anything else. Which is also why a change to it republishes the edge — a derived attribute changed, so the record carrying it is reissued, even when the edge's existence is untouched. Compaction makes that obligation sharp rather than optional: the surviving record for a key must be current in every field it carries, and a producer emitting only on appearance and disappearance would leave the topic attributing an edge to a scope that had stopped asserting it, which a consumer rebuilding from that topic would read as fact.
>
> The field exists for consumers that need provenance, chiefly the bridge that emitted the edge. A bridge reading its own output back must be able to enumerate everything it previously asserted under one scope in order to diff the next baseline against it, and for scopes that are not a single resource's slice — an NE inventory dump yields `NE-7 hosts shelf:3` and `shelf:3 hosts card:5` alike — nothing in the edge itself identifies the scope. Other consumers may use it to attribute an edge to a feed, or to drain everything one source asserted.
>
> A producer MUST use the same scope value for every edge asserted within one scope. The values are opaque: no consumer may parse one or infer structure from it.
>
> The requirement binds producers. An implementation ignores `scope` as it ignores any other attribute (W19) and MUST NOT validate it — refusing a well-formed edge over a provenance field would discard a true fact for a reason that has nothing to do with the graph.
>
> A producer that issues no baselines — one emitting edges it discovers individually — still names a scope. Its own identity serves, and the union is then a constant, so it republishes only when the edge does.

**W23 — Each edge type is authored in one direction.** Every declared edge type has a name and MAY declare an inverse name reading the other way. Exactly one of the two is the **authored** name, and every ingest event for that type MUST carry it as its `edgeType`. An implementation MUST reject an ingest event whose `edgeType` is an unauthored name, exactly as it rejects an undeclared type, and MUST refuse to start if its configuration authors both directions of one edge type.

> **An edge has two equivalent representations, and only one is ever written.** `LINK-311 actively_transports PATH-200` and `PATH-200 actively_transported_by LINK-311` state the same fact, but the key is computed over the representation used (W21), so the two would land on different keys and one fact would compact twice. Rather than make every producer normalize, each edge type is written one way always. That is what the authored name is, and it keeps a producer simple: it is configured with one name per type and never needs to know the other.
>
> The inverse name is vocabulary — it lets a closure be named from whichever end reads naturally. Switching which name is authored changes every key for that type, and cannot be done incrementally: retracting an edge under its old key and asserting it under its new one are events on two keys with no relative order, so the retraction can land last and remove an edge the assertion had just restated. A switch therefore means rebuilding from empty. Closure definitions need no change, since they name edge types rather than keys, and the closures they compute come out the same.
>
> **It does not keep two producers apart.** That needs one bridge per edge type, and direction has nothing to do with it: two bridges writing the same direction still share a key, and each would overwrite the scope set the other published (W22). Several scopes asserting one edge through a single bridge is the normal case; two bridges writing one key is the failure. An event carries no identity of its producer, so this is a deployment obligation an implementation cannot check (design document §8).

**W24 — Every event MUST be keyed by its unit of complete state.** A record is keyed by the thing it states completely, so that the latest record for a key is always the whole truth about it:

- an edge `snapshot` or `delete` is keyed by the edge — its edge reference, `edge:` followed by its edge id (W21)

The key is the canonical serialization of a `(type, id)` pair (L8): for an edge, of `(edge, id)`.

This one rule serves both purposes a key has. It makes a topic **compactable**, since the record compaction retains for a key is complete for it. And it makes ordering **sufficient**: every event able to affect a given piece of state shares its key, and therefore its partition, and therefore its order — two events on different keys describe disjoint state and commute.

Same key means same partition means in-order delivery, which is what lets a consumer apply events as they arrive without a version check. This is the only ordering guarantee in the contract, and it is deliberately a property of the transport rather than of the payload. A consumer MUST NOT reconstruct it from `sourceVersion` or any other payload member, and a producer MUST NOT rely on a consumer doing so. Nothing in an implementation can verify at runtime that a topic is correctly keyed, so a deployment SHOULD assert it at startup: a mis-keyed topic yields silently wrong state rather than an error.

Redelivery needs no separate defence. Both kinds assert an end state — an edge exists, an edge does not — so applying an event twice is a no-op, which is also why a consumer MUST NOT skip work when an event turns out to change nothing, since a redelivery and the retry of a partially-applied run are indistinguishable from the payload.

> Two edge events on different keys may be applied in either order, and an intermediate state between them is observable. A bridge replacing one supporting link with another emits a `delete` and a `snapshot` on two different keys, so a consumer may briefly see neither or both. Both orders converge, which is all an eventually consistent platform requires; and if an intermediate state happens to contain a cycle, it is detected, qualified and cleared by the ordinary recompute (design document §4.7).

**W25 — An event's identifiers MUST agree with its triple.** An implementation MUST reject (W27) an edge event whose inner `id` is not the edge id function (W21) applied to its triple, whose record key is not the canonical serialization of `(edge, id)` (W24), or whose outer `relationships.resource`, when present (W18), names anything other than the inner object.

> What goes wrong if a producer computes an id incorrectly? The record lands on the wrong key, and compaction is keyed. A `delete` keyed differently from its `snapshot` retires nothing on the topic: the snapshot survives compaction, and a consumer rebuilding from the topic resurrects an edge every live consumer saw removed. An implementation trusting the triple over the key would apply both events and look correct while the topic decayed underneath it. Rejecting the event surfaces the producer's mistake immediately, as an edge missing now, rather than months later as an edge come back.

**W26.** An ingest topic MUST carry `snapshot` and `delete` only, and every inner object MUST be of type `edge`.

**W27 — Rejecting an ingest event means skipping it.** Wherever this document says an implementation MUST reject an ingest event, it means: apply nothing from that event, and continue consuming. An implementation MUST NOT halt, pause or retry a partition on a rejected event, and MUST NOT require the record to be removed before it consumes further. It MAY log or count rejections; nothing in the contract observes them.

> Why skip rather than fail? Several instances may consume one topic, each taking the edge types it declares and passing over the rest, so an event one instance rejects is routine rather than a fault — an edge type another instance declares is, to this one, an undeclared type (W23). A consumer that stopped on such a record would stop on ordinary traffic, and a dead-letter topic would never be empty and would tell nobody anything.

#### Examples

An edge snapshot, keyed `edge:(card:X>hosts>port:P1)`. It originated as one entry in `NE-7`'s inventory dump, diffed upstream by the bridge, which is what `scope` records (W22). The implementation ignores that field entirely (W19):

```json
{
  "data": {
    "type": "snapshot",
    "id": "01JBQK4Z7N8XVT2R",
    "attributes": {
      "snapshot": {
        "type": "edge",
        "id": "(card:X>hosts>port:P1)",
        "attributes": {
          "edgeType": "hosts",
          "scope": ["ne-inventory:NE-7"]
        },
        "relationships": {
          "subject": { "data": { "type": "card", "id": "X" } },
          "object":  { "data": { "type": "port", "id": "P1" } }
        }
      }
    },
    "relationships": {
      "resource": { "data": { "type": "edge", "id": "(card:X>hosts>port:P1)" } }
    }
  }
}
```

Its delete names the same edge and carries the triple and nothing else (W20). Same key, so it is ordered after the snapshot, and on a compacted topic it is followed by a tombstone (W30):

```json
{
  "data": {
    "type": "delete",
    "id": "01JBQK51M3YHWP7D",
    "attributes": {
      "delete": {
        "type": "edge",
        "id": "(card:X>hosts>port:P1)",
        "attributes": {
          "edgeType": "hosts"
        },
        "relationships": {
          "subject": { "data": { "type": "card", "id": "X" } },
          "object":  { "data": { "type": "port", "id": "P1" } }
        }
      }
    },
    "relationships": {
      "resource": { "data": { "type": "edge", "id": "(card:X>hosts>port:P1)" } }
    }
  }
}
```

### 6.6 The ingest topic

The stream an instance consumes is assumed well-formed (§1.1). What follows is what a producer must deliver and what an instance may therefore rely on. Nothing here constrains what an instance does with the stream once it has it.

**W28 — Record encoding.** A record's key MUST be the canonical serialization (W24) encoded as UTF-8 bytes. A record's value MUST be the event's JSON:API document (§6.5) encoded as UTF-8 JSON, with no schema-registry framing or other envelope, or null for a tombstone (W30). Nothing in the contract is carried in record headers: a producer MAY set headers, and an implementation MUST NOT depend on any. A producer SHOULD use Kafka's default partitioner, so a key's partition is a function of its bytes alone.

**W29.** An ingest topic MUST be log-compacted. Every event on it is complete for its key (W24), so compaction retains a correct current state, which is what lets an instance reach correct state by reading the topic alone rather than being loaded separately.

**W30.** A delete MUST be published as **two records under the same key, in this order**: the `delete` envelope (§6.5), then a null-valued record. Compaction drops a key only on a null value, and the envelope carries a payload, so both are required.

**W31.** The pair is NOT required to be atomic. A producer crashing between the two leaves a delete with no tombstone, which is a retention leak rather than an incorrect state, since consumers have seen the delete. A consumer MUST treat a repeated delete for the same subject as idempotent.

**W32.** A consumer MUST tolerate a null-valued record and MUST NOT treat one as a malformed event. It MUST NOT derive anything from one beyond the absence it creates.

**W33 — A compacted topic holds at least the latest record per key, not only the latest.** Compaction is lazy: the active segment is never compacted and older segments are cleaned on the broker's schedule, so a reader may see several records for one key, the superseded along with the current. A consumer MUST NOT assume one record per key. What it must ensure is that the state for each key reflects **the last record for that key**. Applying every record in order achieves this; so does discarding all but the last, since each is complete for its key (W24) — which is what compaction itself does on the broker, done early in the consumer. Discarding may look only at records already received: a consumer cannot skip ahead to a record it has not yet read.

**W34 — Concurrency is free, provided the outcome is serial.** An implementation MAY schedule its work however it likes: consuming partitions in parallel, processing events within one partition concurrently, or anything else. Whatever it does, its results MUST equal those of some serial application of the events in which each key ends at its last record (W33). Applying records one at a time in order is one such application; coalescing each key to its last record before applying is another.

> Events for different keys commute when applied one after another, which is why reordering them is harmless and why the contract asks only that each key end at its last record. Applying them *at the same time* is a different matter. Two events on different keys often share an affected resource — two links beneath one path, both feeding the same circuit's reach set — and an implementation recomputing that resource on two threads from the state each read at the start will publish twice, the later result never having seen the earlier's change. Every serial order gives the right answer; the overlap gives a wrong one.
>
> A boundary read fails the same way. Reading a neighbour's published reach set instead of recomputing it is safe only while nothing else is writing that neighbour, and a neighbour outside one event's affected set may be inside a concurrent event's, mid-recompute.
>
> The fence is no defence here. It stops a deposed leader writing, but two threads of one leader carry the same generation, so a conditional flip passes for both. Serializability has to come from how an implementation schedules work that touches overlapping affected sets — which is why an implementation applying events one at a time satisfies this trivially, and why it is the first thing to lose when parallelism is added for throughput.

**W35 — An instance whose own state cannot answer correctly MUST refuse, not answer.** If an instance's internal state is not a correct closure over the events it has applied, it MUST reject every `of` with `not_ready` (W11), and MUST NOT return a result — empty or partial.

> **Why not simply "until it has finished loading"?** Because on a stream there is no finishing. An instance consuming a topic has applied some prefix of each partition, and a correct closure over exactly those events is a correct answer — during a backlog just as in steady state. The only difference is how far behind the head it is, and being behind is the eventual consistency the contract already accepts (§1.3). An empty result for a resource not yet reached is truthful as well: under L16 empty means unknown *to this instance*, and the closed-world reading in §2.4 is explicit that an instance answers only about what it has been told. The nearest thing to "done" is catching up to the end offsets observed at some moment, but the topic has more events a second later and nothing about correctness changes when that line is crossed.
>
> What does make an answer wrong rather than stale is an instance whose state does not reflect even the events it has already consumed. Two cases are real. A rebuild that clears its stores first holds, for a while, nothing that corresponds to any prefix of the stream. And a bulk load that defers closure computation until the end — writing edges as they arrive and computing reach sets only once the backlog is consumed — holds edges whose reach sets do not yet exist, so an answer then would be neither current nor stale but simply wrong. In both cases the implementation knows exactly when it is in that state, because it put itself there, which is what makes this rule enforceable where "until loaded" was not.
>
> An implementation that maintains closures incrementally as it consumes is never in such a state and answers throughout. Incomplete or anomalous data is not grounds for refusing either: a correct answer over suspicious inputs is returned with a qualification (§4). Refusal is reserved for having no correct answer to give.
### 6.7 Diagnostics

**W36 — The cycle diagnostic.** Each closure MUST also be reachable at a second link its discovery entry gives (W4), its **cycle diagnostic**, which reports the cycles behind a `cycle_detected` qualification. A request takes exactly the form of an `of` request (W5, W6), and is rejected for exactly the same malformed arguments, with the same errors (W11). The answer is about the result `of` would return for the same closure and arguments: every cycle of the closure's admitted edges lying in that result — exactly the cycles that would qualify it (L27) — reported in full, or refused with `result_too_large` (W7).

A cycle is reported as a **cycle group**: a set of references each of which reaches every other along the closure's admitted edges, and which no further reference could join — a strongly connected component, together with any single reference with an admitted edge to itself. A group carries its members, and the admitted edges among them as edge references (W24); in a group, every such edge lies on a cycle, so these are the edges a repair chooses among.

```json
{
  "meta": {
    "closure": "suppliers",
    "of": [ { "type": "gamma", "id": "G0" } ],
    "cycles": [
      { "members": [ { "type": "gamma", "id": "G1" }, { "type": "gamma", "id": "G2" } ],
        "edges":   [ { "type": "edge", "id": "(gamma:G1>fed_by>gamma:G2)" },
                     { "type": "edge", "id": "(gamma:G2>fed_by>gamma:G1)" } ] }
    ]
  }
}
```

The response is a JSON:API document carrying `meta` alone: a diagnosis has no primary data. `meta` MUST carry the closure name, the argument set, and `cycles`, which is empty when the result holds none. The groups and the members and edges within each are a set (L6).

The diagnostic answers about the instance's state when it is asked, not when a qualified result was computed (§1.3). A cycle can be transient — formed between two edge events and cleared by the next (W24) — so an empty answer after a `cycle_detected` is the expected way of learning that a cycle has already gone.

> Why scope it to an argument set rather than report every cycle in the instance? Because the question a caller holds is about one answer: *what was wrong beneath this result?* An instance-wide list would bury those cycles among unrelated ones, and would change under the caller for reasons unrelated to the result. Asking with the arguments that drew the qualification keeps the answer focused on it.

## 7. Conformance suite

**What the suite is testing.** The question is whether an implementation is a correct closure computer, not whether some production domain behaves like one. Those are different claims, and only the first is in scope here: a green suite says the implementation gets reachability, qualification and ingest right, and says nothing about whether a particular deployment's edge types and closures model its domain sensibly. That second question needs its own tests.

This settles who supplies the domain. **The suite does.** The graph a fixture needs — a cycle, a resource with two parents, a closure admitting only some of the declared types, an edge type whose authored name is its inverse — is not something a real domain is obliged to contain, and equipment contains almost none of it. A suite that had to work with whatever domain it found could only test what it happened to find.

Supplying its own domain also lets the suite **compute** expectations rather than be told them. Having fed the graph in, it knows every reach set, so it derives what each `of` must return instead of reading answers from a fixture table written by whoever wrote the implementation.

**The suite publishes a profile; the tester deploys to it.** The division is that anything which changes the meaning of a test is mandated, and anything which is purely how to reach the instance is the tester's. So the suite's **test profile** (Appendix A) fixes:

| | |
|---|---|
| at least two partitions on the ingest topic | so that the parallelism W34 permits — most often one consumer thread per partition — is exercised at all |
| resource types and edge types | with inverse names and which direction is authored |
| closures | each with its admitted `(edge type, direction)` pairs, in inverse pairs |
| cardinality rules | with their identifiers and definitions |

The tester supplies what is left, which is how to reach the instance rather than anything about what it means: **the URL of its discovery document**, the broker address, authentication, storage, and a deployment configured to the profile. Everything else the suite needs to reach — each closure's endpoint, the topic to publish to — it reads from the discovery document (W4), so an instance behind a gateway, under a prefix or at a cluster-local name needs no adjustment to the suite. Standing the instance up correctly is the tester's responsibility, and a suite run against a misconfigured instance is expected to fail.

**Discovery is a check, not a source.** Because the suite already knows the configuration, W4's document is not where it learns anything — the first fixture asserts that what the instance reports matches the profile it was supposed to be given. The value is diagnostic: a misconfigured instance then fails at the first fixture with a legible reason rather than at the seventh with a baffling one.

**The profile may only use what the contract guarantees.** This is a useful constraint rather than an inconvenience: if a fixture needs something an implementation is not required to support, that is a gap in the contract, not a liberty the suite may take.

**Shape.** One suite, parameterized by an implementation endpoint and the profile. The suite MUST NOT branch on implementation; a test that needs to know whether it is talking to a tree is testing an implementation rather than the contract.

**What the tester still supplies beyond deployment.** A settle timeout for polling, which is test configuration rather than a contractual bound (§1.3).

**Fixtures the suite MUST include.** Every one is runnable against a single instance through its declared surface; none requires a second implementation, a particular storage shape, or access to anything an instance keeps internally.

1. **A profile fixture**, asserting the discovery document matches the test profile exactly — every closure naming its inverse and carrying its own `of` and `cycles` links, exactly one closure of each pair — the one the profile designates — stating admitted pairs against authored names, every edge type with its authored name, every cardinality rule with its identifier and definition — and that every closure carries links the suite can resolve and the document names an ingest topic (W4). It runs first so that a misconfigured instance fails here, legibly, rather than part-way through a later fixture for reasons that look like a contract violation.
2. **A type-restricted closure over a graph where an unadmitted edge sits above an admitted one**, asserting the reference beyond it is *not* reached (L23). It then asserts that a reference returns exactly itself rather than nothing (L15, L16) in each of the three ways a closure can decline to move from one: the far side of an edge whose type the closure admits only in the *other* direction, a reference known only through an edge type **no** closure admits, and one known only through a type another closure admits but this one does not. A reference appearing in no edge at all returns empty, as the control that distinguishes the three from ignorance (L16). The three are worth separating because an implementation can fail them independently — one that filters by edge type without checking direction passes the second and third and fails the first, and one that derives known-ness from a closure's admitted edges rather than from the instance's fails the second while passing the others.
3. **A cyclic fixture**, asserting the result is returned (L26), that every member of the cycle has the same reach set, and that it carries `cycle_detected` (L25, W10). It also asserts that a reference reaching the cycle from outside it is qualified, while one the cycle does not reach is not, and that a cycle formed only by edges a closure does not admit does not qualify that closure's results (L27). For each argument it asks, it also asks the cycle diagnostic, asserting that it reports exactly the cycle groups the suite computes in that result, each with exactly its members and the admitted edges among them — and none where the result is unqualified (W36).
4. **A cardinality fixture**, provoking each bound of a declared rule — a resource of a listed type with too few edges, and one with too many — and asserting the result is returned with a qualification naming the rule by its published identifier and the references breaching it (L28, W10). A second case builds more breaches of one rule than the floor of ten, asserting that the qualification lists at least ten references, every one of them a genuine breach, and a `total` equal to the number the suite computes. It also asserts that a resource of an unlisted type with the same counts is not qualified, and that a closure not admitting the rule's edge type does not report the breach (L27).
5. **A far-end fixture** — a reference appearing only as the object of edges and never as a subject — asserting it is returned indistinguishably from any other (L24) and carries its real type rather than a placeholder (L11, W1). The contract draws no distinction here; the fixture exists because implementations reliably do, treating references they hold state for differently from ones they only see in edges.
6. **An identity fixture**: requests are sent as `application/vnd.api+json` and every response is checked for the status W8 or W11 gives it (W6); a bare id with no type, an empty type and an empty id are each rejected as `reference_malformed` (L7, W11); two references differing only in case or whitespace are distinct (L8).
7. **A hostile-reference fixture** — ids carrying the deployment's delimiters, spaces, quotes and non-ASCII — asserted to round-trip unchanged through `of` and through an ingest event (L10).
8. **A set-argument fixture**, asserting a set returns exactly the union of its members taken singly (L21), that an empty set returns empty (L17), and that a set mixing known and unknown references is answered rather than rejected, with nothing indicating which members contributed.
9. **An error fixture**, asserting that a malformed argument produces a well-formed `errors` document carrying `reference_malformed` and the argument as received (W11), that an unknown reference produces no error (L14), and that a qualified result is a success rather than an error (W12). `not_ready` is excluded with W35, below.
10. **A result-size fixture**, building a fan-out large enough to exceed a modest ceiling — its size is suite configuration — and asserting the response is either the whole result the suite computes, or a `result_too_large` error carrying no references (W7). An instance that never refuses passes on the first branch; one that returns part of the result fails.
11. **An edge fixture** for one edge, polling through `of` until each event's effect is visible before publishing the next: `snapshot`, redelivered `snapshot`, `delete` then tombstone — asserting the edge exists, is unchanged by the redelivery, then is gone (W20, W30). Publishing all three before looking would let an implementation that coalesces records for one key (W33) show only the final state, so the intermediate assertions depend on the polling. A second case sends a `delete` carrying `scope` and an unrelated attribute for a visible edge, and asserts the edge is gone (W20).
12. **An authorship fixture**, asserting an event whose `edgeType` is the unauthored name of an edge type is rejected (W23), taking both names from the profile.
13. **An edge-id fixture**, asserting that an event whose inner `id` is not the id function of its triple, one whose record key disagrees with its inner `id`, and one whose outer `resource` names a different edge are each rejected (W25); and that two edges differing only in edge type, or only in an endpoint containing characters the profile's id function treats specially, are held as distinct edges, so that deleting one leaves the other (W21).
14. **A reordering fixture**: a `delete` of one edge and a `snapshot` of another touching the same resource, applied in both orders, asserting both converge to the same final state (W24). The suite controls the order by publishing one event, polling through `of` until its effect is visible, and only then publishing the other — so the order is deterministic and no partition arrangement is needed.
15. **A concurrency fixture**: a large batch of events whose affected sets overlap heavily — many edges beneath one shared ancestor, so that any concurrent workers are forced onto the same reach sets — published across partitions the suite assigns deliberately, keeping every key on one partition. Some keys receive several events, so a consumer sees several records for one key and an implementation that coalesces is exercised alongside one that does not. It asserts the final state equals the one the suite computes (W33, W34). The fixture is probabilistic, since races are timing-dependent: a pass is evidence rather than proof, and a single failure is conclusive. Random interleaving over unrelated subgraphs would almost never collide and would pass broken implementations, which is why overlap is the point.
16. **An ingest-kind fixture**, asserting that any kind other than `snapshot` and `delete`, and any inner object whose `type` is not `edge`, is rejected (W26).

**Observing a rejection.** Fixtures 12, 13 and 16 assert that an event is rejected, and a rejected event is skipped (W27), so its only effect is an absence — which, on its own, looks exactly like an event not yet applied. Each rejection case therefore publishes the invalid event followed by a valid **sentinel** edge on a different key and the same partition, polls until the sentinel is visible, and then asserts that the invalid event's effect is absent. Seeing the sentinel proves consumption continued past the invalid record, which is the obligation W27 most needs tested. That the invalid record was not applied is evidence rather than proof, since W34 lets an implementation apply different keys in either order; as with fixture 15, a pass is evidence and a single observed application is conclusive.

**What is not tested here.** W35 is not tested here: the states in which an instance must refuse are ones particular implementations enter deliberately — clearing stores for a rebuild, deferring closure computation during a bulk load — and the suite has no way to put an arbitrary implementation into one, so it belongs with implementation-specific tests. Composition algebra (L31) needs access to a composite's delegates, which is a property of a deployment rather than of the instance under test; a composite instance is otherwise exercised by this suite like any other, and L30 is the rule that makes that sufficient. Tree and DAG implementations are likewise not compared against each other — if the contract is complete, passing it is what makes them agree, and a comparison test would be measuring the suite rather than the implementations. Implementation-specific behaviour belongs in implementation-specific suites.

**What the suite MUST NOT assert.** Cross-delegate atomicity in a composite (L33), any consistency class (§1.3), and result ordering (L6). Each is a guarantee the contract declines to make, and a test asserting it would pass against an implementation that had accidentally provided it, then break when that accident was optimized away.

**Laws needing generative testing.** L18, L19, L21 and L23 are quantified over all pairs and all subsets. Example-based cases will not find the interesting failures. These SHOULD be property tests over a generated graph, with the generator producing both acyclic and cyclic instances.

---

## 8. Open items

- **Discovery and the error codes are provisional.** W4 and W11 were written to close gaps rather than in response to use, and nothing has pushed on them since. Their shape is expected to change as the suite is built — the error set wants to be the set a test needs to tell apart. An implementer should read them as an invitation to push rather than as settled.

- **Who owns the type vocabulary.** L8 fixes the serialization, and a type may not contain `:`. What remains open is who owns the set of type names shared across a deployment, so that two instances never name one type two ways — which would produce exactly the silent empty intersection L8's note describes.

- **The cycle diagnostic is provisional.** W36 was written when `cycle_detected` stopped naming its cycle, and nothing has used it yet. Its shape — groups of members and edges, scoped to an argument set — is a first guess at what repair needs, and is expected to change once someone repairs a graph with it.

- **Whether two qualifications are enough.** Partial delegate failure and staleness beyond some threshold are all candidates. Adding one later is a contract revision; enumerating them now risks specifying qualifications no implementation can raise.

---

## 9. Withdrawn requirements

Recorded by name rather than by identifier, since identifiers are renumbered while this document is a draft (see **Numbering**, above).

| Withdrawn | Why |
|---|---|
| Tree/DAG model equivalence as a law | The assertion belongs in the design document as a property of the models. Its testable consequence survives as L23, and suite fixture 2 covers it. |
| Closures declare a consistency class | Withdrawn with §1.3. The platform is eventually consistent throughout and the bar is deliberately not raised. |
| Responses report the consistency class | Withdrawn with the above. |
| Suites poll to an implementation-published bound | Withdrawn with the above. Polling survives as a fixture-supplied settle timeout, which is test configuration, not contract. |
| A composite declares the weakest class | Withdrawn with the above. L33, which is about atomicity rather than staleness, is unaffected and survives. |
| The path-delimiter prohibition and the segment-annotation clause (two bullets of L9) | Both made a contract rule depend on one implementation's internal storage format. A delimiter is an implementation's own choice, and constraining every future implementation's choices — or naming a shared one — is worse than requiring each to cope with its own. Replaced by L10. |
| Keying by unit of retraction, with edge events keyed by their enclosing slice | Replaced by keying by unit of complete state (W24). Slice keying ordered edge events against baselines, but made an ingest topic uncompactable: the latest record for a slice could be a single edge, and compaction would discard the baseline that established the others. |
| Baselines on ingest, one form per edge type, and the empty-baseline tombstone | Retired together. A baseline cannot reach a compacted topic without corrupting a fresh consumer: it determines many edge keys' state from one record on another key, so a later edge `delete` compacts away while the baseline survives. One form per type avoided that at the cost of forbidding sources from sending both; moving the diff into the bridge avoids it without the restriction. Baselines now live entirely upstream (W14). |
| Resource subjects on ingest | Withdrawn with W14. An ingest event about a card purported to describe the card while describing one facet of it, and left completeness to be inferred from direction and naming. Ingest subjects are now edges; translating domain events is upstream. |
| The present/empty/absent reading of relationship members | It existed so a feed could be authoritative for one edge type while claiming nothing about others. Completeness moved into the event kind, and then out of the contract entirely: an implementation now receives only single edges, so the "absent" case cannot arise. |
| `snapshot-delta`, later `baseline-delta` | Withdrawn with baselines. An advisory delta only means something beside a baseline, and an implementation no longer receives any; an edge `snapshot` asserts one edge and has nothing to summarize. A bridge MAY accept a delta from its own sources. |
| Resource-level `delete` on ingest | Withdrawn with W14. A departing resource is expressed as a `delete` for each edge it authors. |
| Reserved type `resource` for foreign references | Unnecessary once references are fully qualified: the type travels with the id, so an implementation always holds it. Replaced by L11. |
| Join attribution on composite results | Withdrawn in favour of L34. Reporting it would make a composite distinguishable from an instance by inspecting a result, which L30 forbids. |
| No default closure; an undefined closure is rejected (two laws), and `closure_not_named`, `closure_unknown` | A closure is selected by the link a request is sent to (W5), and the contract has no URL structure, so neither request can be constructed from the contract surface. The intent survives as a consequence of L1. |
| `direction_vocabulary` error code | A request body carries only references, so there is no member in which direction vocabulary could arrive and nothing to reject. L4's prohibition on offering it stands. |
| Cardinality exemption list (`except`) and `objectMin`/`objectMax` | Replaced by naming the types a rule applies to and bounds per end (W4). A minimum ranges over resources with no edge of the type, and an exemption list made that range every known reference. |
| Replay fixture | Needed an instance restarted against a pre-populated topic and broker control over compaction timing, neither reachable through the contract. Several records per key are still exercised by the concurrency fixture. |
| Truncation with `meta.truncated` and a continuation cursor | Replaced by W7: a result is whole or refused. A partial result is a confident wrong answer for the callers this contract serves, and pages read from a changing store match no single moment. |
| Rejecting a `delete` that carries more than its triple | A delete that identifies its edge unambiguously is honored, and anything else it carries is ignored as W19 requires. Rejecting it contradicted W19, and under skip semantics would have left the edge standing indefinitely. |
| Self-inverse closures | Withdrawn with L2. A closure admitting both directions of one edge type made every edge of that type a cycle with itself, and the relationships modeled here are directional with named inverses. |
| A deployment-chosen canonical serialization and edge id function | Fixed in L8 and W21. Every producer, implementation and the suite compute both, and leaving them open meant carrying them as configuration everywhere. A digest form of the edge id was dropped in favour of a readable one, and a comma separator in favour of `>`, which equipment ids rarely contain. |
| `meta.generations`: the generation of each contributing reach set (a SHOULD of W9) | No caller could act on it: interpreting a generation takes the current state of the graph, which only someone diagnosing the instance has, and that belongs to the implementation's own surface (§1.1). Keyed by argument, it also showed which members of a set contributed, which fixture 8 says nothing may; and a composite, whose delegates share no clock, could neither report it meaningfully nor omit it without becoming distinguishable (L30, L34). |
| `cycle_detected` naming the references of a cycle, under `sources` (L28, W10) | It told a caller that some references were on a cycle, but not which edges to cut, and a caller could act on neither part from the answer: the result is correct over the graph as it stands (L26), and repair needs edges an `of` response has no place for. The qualification now carries its code alone, and the cycle diagnostic (W36) reports the cycle groups, with their edges, for the same arguments. |
| Producer ordering configuration for the delete pair | Removed as an implementation concern. The ingest-topic rule still requires the order; the reasoning for why it matters is in the design document's §4.3 rather than here. |

---

## Appendix A — Test profile

The configuration a conforming deployment stands up for the suite to run against (§7). It is versioned with the suite rather than with the laws, and it changes whenever a fixture needs something new; nothing in §1 to §6 depends on it.

The domain is deliberately artificial. It exists to make every case the fixtures need reachable in one instance — a closure admitting a subset of the declared types, an edge type authored through its inverse, a cardinality rule bounding each end, a graph that can be made to cycle — none of which a real domain is obliged to provide.

**Status: provisional.** This appendix is expected to be refined as the suite is built, and a fixture that cannot be written against it is evidence the profile is wrong rather than evidence the fixture is. Where a fixture needs something an implementation is not required to support, that is a gap in the contract rather than a liberty the profile may take.

### A.1 Resource types

**Why are the types named after Greek letters rather than after equipment?** Because a recognizable vocabulary would invite a reader to reject a fixture's graph on domain grounds. The cyclic fixture needs a cycle in a containment-like relationship and the cardinality fixture needs a resource with two parents, neither of which any real inventory would hold, and a reviewer reading `card hosts port hosts card` would reasonably conclude the fixture was wrong. These types therefore mean nothing beyond the role each plays in the rules below.

| type | its role |
|---|---|
| `alpha` | named by no cardinality rule; the top of the containment spine fixtures build, so it bears no holder of its own |
| `beta` | bounded at the object end of `holds` and at the subject end of `tracks`, so both ends of a rule are exercised on one type |
| `gamma` | bounded at the object end of `holds`; the type the cyclic fixture puts in a cycle |
| `omega` | named by no cardinality rule, so a fixture may give it any number of edges without provoking one; carries the hostile ids of fixture 7 |

The ids are the suite's to choose and are not fixed here, except that every one carries the run identifier the suite prefixes (§7).

### A.2 Edge types

Four types, each present for something a fixture needs rather than for completeness.

| name | inverse | authored | why it is here |
|---|---|---|---|
| `holds` | `held_by` | `holds` | the containment spine: high fan-out, and the subject of the `one-holder` rule |
| `feeds` | `fed_by` | **`fed_by`** | authored through its inverse, so fixture 12 has an unauthored name to reject and W4's "direction is relative to the authored name" is exercised rather than asserted |
| `tracks` | `tracked_by` | `tracks` | connects the same resource types `holds` does, so fixture 13 can build two edges differing only in edge type; also the subject of the `tracks-fanout` rule |
| `notes` | — | `notes` | declares no inverse, so fixture 1 checks that branch of W4; and no closure admits it, so a reference can be **known** (L12) while no closure anywhere reaches through it, which is what separates known-ness as a property of the instance from a property of a closure |

An edge type no closure admits is worth a word, because an implementation might take it for a configuration error. It is not: a closure admits a subset of the declared types by design (L23), the empty subset included, and L12 makes a reference known from any edge whether or not a closure admits its type. An implementation that refuses to start on this configuration has found a disagreement with the contract rather than a mistake in the profile.

### A.3 Closures

Three pairs. Each closure names its inverse, and exactly one of each pair carries `admits`, with the pairs' carriers chosen so that discovery is checked on a `forward` statement and a `reverse` one alike (W4).

| closure | inverse | carries `admits` | admitted pairs |
|---|---|---|---|
| `containers` | `contents` | yes | `holds` reverse |
| `contents` | `containers` | no | *(derived: `holds` forward)* |
| `suppliers` | `consumers` | yes | `fed_by` forward |
| `consumers` | `suppliers` | no | *(derived: `fed_by` reverse)* |
| `dependents` | `dependencies` | yes | `holds` forward, `tracks` forward |
| `dependencies` | `dependents` | no | *(derived: `holds` reverse, `tracks` reverse)* |

Reading them: `contents.of(x)` is everything held beneath `x`, `suppliers.of(x)` is everything feeding `x` transitively — `fed_by` reads *x fed_by y*, so walking it forward arrives at what feeds `x` — and `dependents.of(x)` follows containment and tracking together.

Three properties of this set matter to the fixtures. `contents` and `containers` admit one type out of four, so L23 has something to prune. `dependents` admits two, so a path may cross types. And no closure admits `fed_by` except the `suppliers` pair, which is what lets the cyclic fixture build a cycle that one closure reports and another, looking at the same instance, does not (L27).

### A.4 Cardinality rules

Two rules, between them covering a bound at each end and a rule with both bounds.

```json
[
  { "id": "one-holder", "edgeType": "holds",
    "objectTypes": ["beta", "gamma"],
    "minEdgesPerObject": 1, "maxEdgesPerObject": 1 },

  { "id": "tracks-fanout", "edgeType": "tracks",
    "subjectTypes": ["beta"],
    "maxEdgesPerSubject": 2 }
]
```

`one-holder` says every `beta` and `gamma` sits in exactly one holder, and both of its bounds bite: a resource with no holder breaches the minimum, one with two breaches the maximum. `alpha` and `omega` are absent from its `objectTypes`, so a fixture can give either any number of holders and expect no qualification — which is how fixture 4 shows that a rule reaches only the types it names. `tracks-fanout` bounds the other end of a different type, and is admitted by the `dependents` pair alone, so fixture 4 can also show a breach going unreported by a closure that does not admit its edge type.

**An invariant the fixtures respect.** Because `one-holder` carries a minimum, a `beta` or `gamma` with no `holds` parent breaches it, and any result containing one comes back qualified. A fixture that is not provoking the rule therefore gives every `beta` and `gamma` it creates exactly one holder, and reaches for `omega` when it wants a resource no rule constrains. This is a constraint on how fixtures build graphs rather than on the contract, and the suite's graph builder enforces it.

### A.5 The ingest topic

The topic is named by the instance (W4), not by the profile. The profile fixes only that it is log-compacted (W29) and carries **at least two partitions**, so that the parallelism W34 permits — most often one consumer thread per partition — is exercised at all.

### A.6 The discovery document this implies

<!--
  EDITORS: THE JSON BLOCK BELOW IS READ BY THE BUILD.
  Tests in conformance-suite (ProfileCheckTest) and reference-impl (DiscoveryDocumentTest) parse it
  from this file. Changing it fails the build until TestProfile and reference-impl's discovery.json
  are changed to match; renaming this heading or moving the block breaks those tests too.
  See docs/suite-design.md §3b.
-->

Fixture 1 compares what the instance publishes against the profile, so the document below is what a correctly configured instance reports. The endpoints are the deployment's own; nothing but their presence and resolvability is checked.

```json
{
  "data": {
    "type": "instance",
    "id": "conformance",
    "attributes": {
      "ingestTopic": "conformance-edges",
      "closures": [
        { "name": "containers", "inverse": "contents",
          "admits": [ { "edgeType": "holds", "direction": "reverse" } ],
          "links": { "of": "https://gw.example/conformance/v1/containers/of",
                     "cycles": "https://gw.example/conformance/v1/containers/cycles" } },
        { "name": "contents", "inverse": "containers",
          "links": { "of": "https://gw.example/conformance/v1/contents/of",
                     "cycles": "https://gw.example/conformance/v1/contents/cycles" } },
        { "name": "suppliers", "inverse": "consumers",
          "admits": [ { "edgeType": "fed_by", "direction": "forward" } ],
          "links": { "of": "https://gw.example/conformance/v1/suppliers/of",
                     "cycles": "https://gw.example/conformance/v1/suppliers/cycles" } },
        { "name": "consumers", "inverse": "suppliers",
          "links": { "of": "https://gw.example/conformance/v1/consumers/of",
                     "cycles": "https://gw.example/conformance/v1/consumers/cycles" } },
        { "name": "dependents", "inverse": "dependencies",
          "admits": [ { "edgeType": "holds",  "direction": "forward" },
                      { "edgeType": "tracks", "direction": "forward" } ],
          "links": { "of": "https://gw.example/conformance/v1/dependents/of",
                     "cycles": "https://gw.example/conformance/v1/dependents/cycles" } },
        { "name": "dependencies", "inverse": "dependents",
          "links": { "of": "https://gw.example/conformance/v1/dependencies/of",
                     "cycles": "https://gw.example/conformance/v1/dependencies/cycles" } }
      ],
      "edgeTypes": [
        { "name": "holds",  "inverse": "held_by",    "authored": "holds" },
        { "name": "feeds",  "inverse": "fed_by",     "authored": "fed_by" },
        { "name": "tracks", "inverse": "tracked_by", "authored": "tracks" },
        { "name": "notes",  "authored": "notes" }
      ],
      "cardinalityRules": [
        { "id": "one-holder", "edgeType": "holds",
          "objectTypes": ["beta", "gamma"],
          "minEdgesPerObject": 1, "maxEdgesPerObject": 1 },
        { "id": "tracks-fanout", "edgeType": "tracks",
          "subjectTypes": ["beta"],
          "maxEdgesPerSubject": 2 }
      ]
    }
  }
}
```

### A.7 What each fixture draws on

The profile is minimal in the sense that removing any part of it would leave a fixture unwritable. This table is the evidence, and it is also the first thing to revisit when a fixture changes.

| fixture | what it needs from the profile |
|---|---|
| 1 profile | every row of §A.2 to §A.5, including the type with no inverse and the one authored through its inverse |
| 2 type restriction | `contents` admitting one type of four; `suppliers` admitting `fed_by` in one direction only; `notes`, admitted by no closure at all |
| 3 cycle | `fed_by` and the `suppliers` pair, with `gamma` at both ends of a cycle; `contents`, which does not admit `fed_by`, to show the same cycle unreported |
| 4 cardinality | both rules; `alpha` and `omega` as types no rule names; `suppliers`, which admits neither rule's edge type |
| 5 far end | `gamma` as the object of a `holds` edge and the subject of none |
| 6 identity | `omega`, whose ids no rule constrains |
| 7 hostile references | `omega` carrying ids with the id function's separators, spaces, quotes and non-ASCII, reached through `holds` |
| 8 set argument | any two disjoint spines |
| 9 errors | nothing beyond a declared type |
| 10 result size | `holds` from one `alpha` to many `beta`s, each holder-count 1, so a large result carries no qualification |
| 11 edge lifecycle | one `holds` edge |
| 12 authorship | `feeds`, whose unauthored name is the declared `name` |
| 13 edge id | `holds` and `tracks` over the same pair of resources; `omega` ids carrying `>` and `%` |
| 14 reordering | two `holds` edges sharing an object |
| 15 concurrency | a `holds` spine of depth two beneath one `alpha`, and the topic's two partitions |
| 16 ingest kind | nothing beyond a declared type |
| property tests | the four resource types, the four edge types and all six closures, as the generator's vocabulary |
