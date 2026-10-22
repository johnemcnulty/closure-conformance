# The conformance suite's image (suite-design §7): the thin jar, its dependencies beside it in lib/,
# run by the JUnit Console Launcher. Build from the repository root after `mvn package`:
#
#   docker build -f deploy/Dockerfile -t closure-conformance:0.1.0 .
#
# BASE_IMAGE defaults to Eclipse Temurin 21 JRE (GPLv2 with the Classpath Exception), approved in
# THIRD-PARTY-NOTICES.md for this internal tool, which is not meant for distribution. An
# organization-approved Java 21 runtime image can replace it with --build-arg BASE_IMAGE=...
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

WORKDIR /suite
COPY conformance-suite/target/lib/ lib/
COPY conformance-suite/target/conformance-suite-*.jar conformance-suite.jar
COPY THIRD-PARTY-NOTICES.md .

# JUnit XML goes to /reports; jqwik keeps its failure database in the working directory.
RUN mkdir /reports /work && chown 10001 /reports /work
USER 10001
WORKDIR /work

# Configuration is the environment (suite-design §6): DISCOVERY_URL and KAFKA_BOOTSTRAP are
# required; SETTLE_TIMEOUT, RESULT_SIZE_FANOUT, RUN_ID and KAFKA_CLIENT_* are optional. The exit
# code is non-zero if any fixture fails, or if none is found.
ENTRYPOINT ["java", "-jar", "/suite/conformance-suite.jar", "execute", \
            "--disable-banner", "--details=tree", "--fail-if-no-tests", "--reports-dir=/reports", \
            "--select-package=com.example.closure.suite.fixtures"]
