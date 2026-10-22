#!/usr/bin/env bash
# Runs the packaged suite image against a reference instance, all on this machine, the way a
# pipeline runs it against a real one (suite-design §7). Needs Docker and Java 21; run from the
# repository root after `mvn package` and `docker build -f deploy/Dockerfile -t closure-conformance:0.1.0 .`
#
#   deploy/run-locally.sh             # against the correct reference instance
#   VARIANT=TRUNCATES deploy/run-locally.sh   # against a broken variant (suite-design §5)
#
# Kafka runs in a container with two listeners: one advertised as localhost for the reference
# instance on the host, one on a Docker network for the suite's container. The suite reaches the
# reference instance through host.docker.internal, which Docker Desktop provides.
set -euo pipefail

IMAGE="${IMAGE:-closure-conformance:0.1.0}"
VARIANT="${VARIANT:-CORRECT}"
KAFKA_IMAGE="${KAFKA_IMAGE:-apache/kafka:4.3.1}"
KAFKA_PORT="${KAFKA_PORT:-9092}"
REFERENCE_PORT="${REFERENCE_PORT:-8080}"
TOPIC=conformance-edges
NETWORK=closure-conformance
KAFKA=closure-conformance-kafka
REPORTS="$(pwd)/target/local-run-reports"

reference_pid=""
cleanup() {
    [ -n "$reference_pid" ] && kill "$reference_pid" 2>/dev/null || true
    docker stop "$KAFKA" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "--- starting Kafka ($KAFKA_IMAGE)"
docker network create "$NETWORK" >/dev/null
docker run -d --rm --name "$KAFKA" --network "$NETWORK" -p "$KAFKA_PORT:9092" \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=INTERNAL://0.0.0.0:19092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
    -e KAFKA_ADVERTISED_LISTENERS="INTERNAL://$KAFKA:19092,EXTERNAL://localhost:$KAFKA_PORT" \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    "$KAFKA_IMAGE" >/dev/null

topics() { docker exec "$KAFKA" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "$@"; }
for _ in $(seq 1 60); do topics --list >/dev/null 2>&1 && break; sleep 1; done
echo "--- creating $TOPIC: compacted, three partitions (A.5)"
topics --create --if-not-exists --topic "$TOPIC" --partitions 3 --config cleanup.policy=compact >/dev/null

echo "--- starting the reference instance ($VARIANT)"
# through the reactor (-am), so sibling modules resolve to this checkout rather than to ~/.m2
if ! mvn -q -pl reference-impl -am -DskipTests package dependency:build-classpath \
        -Dmdep.includeScope=runtime -Dmdep.outputFile=target/classpath.txt; then
    echo "could not build the reference implementation or its classpath" >&2
    exit 1
fi
classpath="$(cat reference-impl/target/classpath.txt)"
KAFKA_BOOTSTRAP="localhost:$KAFKA_PORT" INGEST_TOPIC="$TOPIC" PORT="$REFERENCE_PORT" VARIANT="$VARIANT" \
    ADVERTISED_URL="http://host.docker.internal:$REFERENCE_PORT/" \
    java -cp "reference-impl/target/classes:$classpath" com.example.closure.reference.ReferenceMain &
reference_pid=$!
for _ in $(seq 1 30); do curl -sf "http://localhost:$REFERENCE_PORT/discovery" >/dev/null && break; sleep 1; done

echo "--- running the suite ($IMAGE)"
mkdir -p "$REPORTS"
set +e
docker run --rm --network "$NETWORK" \
    -e DISCOVERY_URL="http://host.docker.internal:$REFERENCE_PORT/discovery" \
    -e KAFKA_BOOTSTRAP="$KAFKA:19092" \
    -e SETTLE_TIMEOUT="${SETTLE_TIMEOUT:-PT30S}" \
    -v "$REPORTS:/reports" \
    "$IMAGE"
status=$?
set -e
echo "--- the suite exited $status; JUnit XML is in $REPORTS"
exit "$status"
