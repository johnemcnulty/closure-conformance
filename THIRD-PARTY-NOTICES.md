# Third-party notices

This repository builds an **internal tool**: a conformance suite run against implementations of the relationship closure contract inside the organization's own environments. It is not distributed outside the organization.

## Approvals

Recorded under the repository's licensing policy (`CLAUDE.md`), which asks that copyleft licenses be approved explicitly and that EPL be reviewed.

| Date | Decision | Approved by |
|---|---|---|
| 2026-10-12 | Components under **MIT**, **BSD** and **Apache-2.0** may be used and shipped, provided this file carries their notices and source links. | john.mcnulty.us@gmail.com |
| 2026-10-12 | **EPL-2.0** components — JUnit and jqwik — may be shipped in the suite's jar and image, listed here with links to their source. The suite is a JUnit and jqwik program and cannot run without them. | john.mcnulty.us@gmail.com |
| 2026-10-12 | The container image may use **Eclipse Temurin 21 JRE** as its base: OpenJDK, under **GPLv2 with the Classpath Exception**. Approved for this internal tool, which is not meant for distribution; distributing the image outside the organization would need this approval revisited. | john.mcnulty.us@gmail.com |
| 2026-10-12 | **JNA**, which is dual-licensed LGPL-2.1-or-later / Apache-2.0, is used under its **Apache-2.0** option. It is a test-only dependency (Testcontainers' Docker client) and is never shipped. | john.mcnulty.us@gmail.com |

## Original code

The code in this repository was written originally, following the contract in `docs/contract-spec.md` and the documented APIs of the libraries listed below. No third-party source code has been copied or adapted into it. Where it relies on a library's behaviour — Kafka's default partitioner, for example — it calls that library rather than reproducing it.

## Shipped in the suite's jar and image

These are the suite's runtime dependencies (`mvn -pl conformance-suite -am dependency:list -DincludeScope=runtime`), plus the JUnit Console Launcher that the jar runs as its entry point. Licenses are as each artifact's POM declares them.

| Component | Version | License | Source |
|---|---|---|---|
| JUnit Jupiter (`junit-jupiter`, `-api`, `-engine`, `-params`) | 5.13.4 | EPL-2.0 | https://github.com/junit-team/junit-framework |
| JUnit Platform (`junit-platform-commons`, `-engine`, `-launcher`, `-console`, `-reporting`) | 1.13.4 | EPL-2.0 | https://github.com/junit-team/junit-framework |
| jqwik (`jqwik`, `-api`, `-engine`, `-time`, `-web`) | 1.9.3 | EPL-2.0 | https://github.com/jqwik-team/jqwik |
| Apache Kafka clients (`kafka-clients`) | 4.3.1 | Apache-2.0 | https://kafka.apache.org (https://github.com/apache/kafka) |
| Jackson (`jackson-core`, `jackson-databind`) | 2.22.3 | Apache-2.0 | https://github.com/FasterXML/jackson-core, https://github.com/FasterXML/jackson-databind |
| Jackson annotations (`jackson-annotations`) | 2.22 | Apache-2.0 | https://github.com/FasterXML/jackson-annotations |
| Awaitility | 4.3.0 | Apache-2.0 | http://awaitility.org (https://github.com/awaitility/awaitility) |
| Open Test Alliance (`opentest4j`) | 1.3.0 | Apache-2.0 | https://github.com/ota4j-team/opentest4j |
| Open Test Reporting (`open-test-reporting-tooling-spi`) | 0.2.3 | Apache-2.0 | https://github.com/ota4j-team/open-test-reporting |
| API Guardian (`apiguardian-api`) | 1.1.2 | Apache-2.0 | https://github.com/apiguardian-team/apiguardian |
| lz4-java | 1.10.2 | Apache-2.0 | https://github.com/yawkat/lz4-java |
| snappy-java | 1.1.10.7 | Apache-2.0 | https://github.com/xerial/snappy-java |
| zstd-jni | 1.5.6-10 | BSD-2-Clause | https://github.com/luben/zstd-jni |
| Hamcrest (through Awaitility) | 2.1 | BSD-3-Clause | https://github.com/hamcrest/JavaHamcrest |
| SLF4J API (`slf4j-api`) | 1.7.36 | MIT | https://www.slf4j.org (https://github.com/qos-ch/slf4j) |

License texts: Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0, EPL-2.0 https://www.eclipse.org/legal/epl-v20.html, BSD-2-Clause https://opensource.org/licenses/BSD-2-Clause, BSD-3-Clause https://opensource.org/licenses/BSD-3-Clause, MIT https://opensource.org/licenses/MIT.

**The EPL-2.0 components' source** is published at the repositories above, with a release for each version listed, and on Maven Central as `-sources` jars alongside each artifact. It is available to anyone who receives the jar or image.

**Bundled inside other artifacts**, so not visible as dependencies:

- `junit-platform-console` contains a relocated copy of **picocli** (Apache-2.0, https://github.com/remkop/picocli), whose license is carried in the jar as `META-INF/LICENSE-picocli.md`.
- `zstd-jni`, `snappy-java` and `lz4-java` contain native builds of **Zstandard**, **Snappy** and **LZ4**. Their projects publish them under BSD licenses (Zstandard is dual BSD / GPLv2 and is used under BSD). This has not been verified independently of the projects' own statements.

## The container image's base

| Component | License | Source |
|---|---|---|
| Eclipse Temurin 21 JRE (OpenJDK), `eclipse-temurin:21-jre` | GPLv2 with the Classpath Exception | https://adoptium.net, https://github.com/adoptium |

The base image also carries operating-system packages under their own licenses, listed by the base image's publisher. It is a build argument (`BASE_IMAGE`), so an organization-approved runtime image can replace it without editing the Dockerfile.

## Used only to build and test, never shipped

| Component | Version | License | Source |
|---|---|---|---|
| Testcontainers (`testcontainers`, `testcontainers-kafka`) | 2.0.5 | MIT | https://github.com/testcontainers/testcontainers-java |
| duct-tape | 1.0.8 | MIT | https://github.com/rnorth/duct-tape |
| docker-java (`docker-java-api`, `-transport`, `-transport-zerodep`) | 3.7.1 | Apache-2.0 | https://github.com/docker-java/docker-java |
| JNA (`jna`) | 5.18.1 | LGPL-2.1-or-later / Apache-2.0, used under Apache-2.0 | https://github.com/java-native-access/jna |
| Apache Commons (`commons-codec`, `commons-io`, `commons-compress`, `commons-lang3`) | 1.19.0, 2.20.0, 1.28.0, 3.18.0 | Apache-2.0 | https://commons.apache.org |
| JetBrains annotations | 17.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| JUnit Platform Testkit (`junit-platform-testkit`) | 1.13.4 | EPL-2.0 | https://github.com/junit-team/junit-framework |
| Kafka broker image (`apache/kafka`), run by Testcontainers | 4.3.1 | Apache-2.0 | https://github.com/apache/kafka |
| Maven plugins (compiler, surefire, jar, dependency) | — | Apache-2.0 | https://maven.apache.org |

## Keeping this file current

A dependency added, removed or upgraded changes this file. The lists above come from `mvn dependency:list` and each artifact's POM; a version bump of a listed component needs only its version updated here, but a new component needs its license checked against the policy in `CLAUDE.md` first.
