# Relationship closure conformance suite

A JUnit Platform test suite that checks any implementation of the relationship closure contract through its public surface: Kafka ingest and HTTP `of` queries.

- The contract (normative): `docs/contract-spec.md`
- Implementation decisions, milestones, open questions: `docs/suite-design.md`. Read it before starting work, and update it when a decision changes.
- Working on the tree implementation (`tree-impl`, `tree-testkit`): read `tree-impl/CLAUDE.md` first.

## Build

- `mvn verify`: builds every module and runs all tests.
- `mvn -pl conformance-oracle test`: oracle only. It needs no infrastructure.
- `reference-impl`'s tests start Kafka with Testcontainers, so `mvn verify` needs a running Docker. The other modules don't. With Docker Desktop and `DOCKER_HOST` set to its per-user socket, also set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, or Testcontainers' Ryuk container fails to start.
- Build a single module with `-am` (e.g. `mvn verify -pl reference-impl -am`). Without it, Maven takes sibling modules from `~/.m2`, which may be stale.
- Java 21, Maven. No Spring.
- `mvn package` builds the runnable suite jar; `docker build -f deploy/Dockerfile -t closure-conformance:0.1.0 .` the image; `deploy/run-locally.sh` runs the image against the reference implementation. The jar needs Java 21: a `java` on the PATH from an older JDK fails discovery with `UnsupportedClassVersionError`.

## Rules that are easy to break

- **Fixtures never branch on the implementation under test.** A test that needs to know whether it is talking to a tree or a DAG is testing an implementation, not the contract (§7).
- **Never assert result order, a consistency class, or cross-delegate atomicity.** The contract declines all three (L6, §1.3, L33).
- **Keep the oracle naive.** It is a breadth-first search recomputed from the edge set on every call, and its value is that it can be checked by reading. Do not optimize it or add caching.
- **The reference implementation must not import or copy oracle code.** Sharing code would make the self-test check the oracle against itself.
- **Example fixtures carry hand-written expectation tables.** Only generated or large fixtures take expectations from the oracle. Write each one as an `Example` and register it in `Examples.ALL`, or the build won't check its table against the oracle.
- **Build fixture graphs with `GraphBuilder`.** It applies the run-id prefix and refuses a graph that breaches a cardinality rule.
- **Every id a fixture creates carries the run-id prefix.**
- **Every fixture needs a broken variant that it catches.** Add it to `Variant` in `reference-impl`, guarding the defect where it acts and naming the fixture class, and to the table in `suite-design.md` §5. `BrokenVariantsTest` then asserts the fixture fails against it.
- **Every fixture class but fixture 1 carries `@Fixture`,** which gates it on fixture 1 for both engines. Fixture classes live in `conformance-suite/src/main/java` and their names end in `Test`, or the reference implementation's build will not find them.
- **Take the domain from `TestProfile`,** never hard-coded type or closure names. It is Appendix A of the contract in code.
- **Give every `beta` and `gamma` a fixture creates exactly one `holds` parent,** unless the fixture is deliberately provoking `one-holder`. Use `omega` for a resource no rule constrains.
- **Normalize an admitted pair with `EdgeTypeDecl.normalize` before comparing or checking L2.** A pair given under the unauthored name is the same traversal with its direction flipped, and L2's both-directions check misses the aliased case otherwise.
- **Build references and edge ids with `Canonical` and `EdgeId`,** never by concatenating strings. The escaping order (`%` before `>`) is load-bearing.
- **Keep `THIRD-PARTY-NOTICES.md` current.** Adding, removing or upgrading a dependency changes it, and a new component's license is checked against the policy below before it goes in.
- Rule identifiers (L*n*, W*n*) are renumbered with each draft of the contract. Cite them in comments, and re-check the citations when `docs/contract-spec.md` is updated.

## Conventions

- camelCase for JSON attribute names and Java members.
- Records for value types. Validate in the canonical constructor.
- Test names say what is asserted, e.g. `unadmittedEdgeStopsThePath`, and carry the rule id in a trailing comment.

## Intellectual Property and Open-Source Licensing Policy

When writing or modifying code in this repository:

1. **Write original implementations.** Do not copy or closely reproduce code from third-party repositories, websites, tutorials, Stack Overflow, or other sources unless explicitly authorized.
2. **Respect licenses.** Do not introduce code from GPL, AGPL, SSPL, or other copyleft or source-available licenses without explicit approval. Treat LGPL, MPL, EPL, and unknown or custom licenses as requiring review.
3. **Use approved dependencies.** Prefer dependencies licensed under MIT, BSD, ISC, or Apache-2.0, subject to their license obligations. Check direct and transitive dependencies before adding them.
4. **Preserve provenance.** If code is adapted from an external source, record the source URL, original author if known, applicable license, and required attribution. Never remove existing copyright notices or license headers.
5. **Do not assume AI generation guarantees originality.** If an implementation resembles a known third-party implementation or contains recognizable third-party code, flag it for review rather than claiming it is original.
6. **Report uncertainty.** If a source, license, or provenance cannot be established, disclose that uncertainty and do not silently incorporate the material.
7. **Verify before completion.** Report new dependencies, their licenses, any third-party code incorporated, and any unresolved licensing concerns. Run the repository's license-compliance checks when available.
8. **Never bypass a licensing failure.** Do not suppress a failing compliance check or replace a prohibited dependency without explaining the change.

When possible, implement functionality from documented behavior and specifications rather than copying an existing implementation.
