package com.example.closure.suite;

import com.example.closure.model.Edge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;

/**
 * Publishes ingest events to the topic the instance names (W4). Every send waits for the broker's
 * acknowledgement, so a fixture's events are on the topic, in the order it sent them, before it
 * starts polling. Keys go through Kafka's default partitioner, as W28 asks of a producer.
 */
public final class EdgePublisher implements AutoCloseable {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final List<String> scope;

    public EdgePublisher(Properties client, String topic, RunId runId) {
        Properties properties = new Properties();
        properties.putAll(client);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        this.producer = new KafkaProducer<>(properties, new StringSerializer(), new ByteArraySerializer());
        this.topic = topic;
        this.scope = List.of("conformance-suite:" + runId.value());
    }

    /** Asserts that {@code edge} exists (W20). */
    public RecordMetadata snapshot(Edge edge) {
        return send(EdgeEvents.key(edge), EdgeEvents.snapshot(edge, eventId(), scope));
    }

    /**
     * Asserts every edge, sending them all before waiting for the acknowledgements. Records for one
     * key keep their order (the producer is idempotent), and nothing here depends on an order
     * between keys, which W24 does not give anyway.
     */
    public void snapshotAll(Iterable<Edge> edges) {
        List<Future<RecordMetadata>> sent = new ArrayList<>();
        for (Edge edge : edges) {
            byte[] value;
            try {
                value = EdgeEvents.JSON.writeValueAsBytes(EdgeEvents.snapshot(edge, eventId(), scope));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
            sent.add(producer.send(new ProducerRecord<>(topic, EdgeEvents.key(edge), value)));
        }
        for (Future<RecordMetadata> acknowledgement : sent) {
            await(acknowledgement);
        }
    }

    /** One record of a batch: a key and its value, or a null value for a tombstone (W28). */
    public record Outgoing(String key, JsonNode value) {
        public static Outgoing snapshot(Edge edge) {
            return new Outgoing(EdgeEvents.key(edge), EdgeEvents.snapshot(edge, eventId(), List.of("batch")));
        }

        /** A delete is two records under the edge's key: the envelope, then the tombstone (W30). */
        public static List<Outgoing> delete(Edge edge) {
            return List.of(new Outgoing(EdgeEvents.key(edge), EdgeEvents.delete(edge, eventId())),
                new Outgoing(EdgeEvents.key(edge), null));
        }
    }

    /** Sends {@code batch} in order without waiting between records, then waits for every acknowledgement. */
    public void sendAll(List<Outgoing> batch) {
        List<Future<RecordMetadata>> sent = new ArrayList<>();
        for (Outgoing record : batch) {
            byte[] value;
            try {
                value = record.value() == null ? null : EdgeEvents.JSON.writeValueAsBytes(record.value());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
            sent.add(producer.send(new ProducerRecord<>(topic, record.key(), value)));
        }
        for (Future<RecordMetadata> acknowledgement : sent) {
            await(acknowledgement);
        }
    }

    /** Retracts {@code edge}: the {@code delete} envelope, then its tombstone (W30). */
    public RecordMetadata delete(Edge edge) {
        send(EdgeEvents.key(edge), EdgeEvents.delete(edge, eventId()));
        return send(EdgeEvents.key(edge), (byte[]) null);
    }

    /** Publishes {@code value} under {@code key} as given, for fixtures sending malformed events. */
    public RecordMetadata send(String key, JsonNode value) {
        try {
            return send(key, EdgeEvents.JSON.writeValueAsBytes(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Publishes raw bytes under {@code key}; a null value is a tombstone. */
    public RecordMetadata send(String key, byte[] value) {
        return await(producer.send(new ProducerRecord<>(topic, key, value)));
    }

    private RecordMetadata await(Future<RecordMetadata> acknowledgement) {
        try {
            return acknowledgement.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish to " + topic + ": " + e.getMessage(), e);
        }
    }

    /**
     * The partition the default partitioner puts {@code key} on: a function of its bytes alone
     * (W28). Observing a rejection uses it to put a sentinel after an invalid event, on a different
     * key in the same partition (suite-design §6).
     */
    public int partitionOf(String key) {
        int partitions = producer.partitionsFor(topic).size();
        return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % partitions;
    }

    /**
     * A snapshot of the first of {@code candidates} sharing {@code key}'s partition, which, once
     * visible, shows everything sent on that partition before it has been consumed.
     */
    public Edge sentinelAfter(String key, Iterable<Edge> candidates) {
        int partition = partitionOf(key);
        for (Edge candidate : candidates) {
            if (!EdgeEvents.key(candidate).equals(key) && partitionOf(EdgeEvents.key(candidate)) == partition) {
                snapshot(candidate);
                return candidate;
            }
        }
        throw new IllegalArgumentException("no candidate sentinel shares the partition of " + key);
    }

    private static String eventId() {
        return UUID.randomUUID().toString(); // unique per event, never the subject's id (W16)
    }

    @Override
    public void close() {
        producer.close(SEND_TIMEOUT);
    }
}
