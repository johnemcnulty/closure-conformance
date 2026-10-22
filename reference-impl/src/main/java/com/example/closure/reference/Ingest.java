package com.example.closure.reference;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Consumes the ingest topic from its beginning on one thread, applying every record in order,
 * which is the plainest serial application W34 allows. It reads every partition itself rather than
 * joining a consumer group, so its state is always a rebuild from the topic alone (W29).
 */
final class Ingest implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Ingest.class.getName());
    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaConsumer<String, byte[]> consumer;
    private final String topic;
    private final EventReader reader;
    private final Graph graph;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong rejected = new AtomicLong();

    private final Variant variant;

    Ingest(String bootstrap, String topic, EventReader reader, Graph graph) {
        this(bootstrap, topic, reader, graph, Variant.CORRECT);
    }

    Ingest(String bootstrap, String topic, EventReader reader, Graph graph, Variant variant) {
        this.variant = variant;
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
        this.topic = topic;
        this.reader = reader;
        this.graph = graph;
        this.thread = new Thread(this::run, "ingest-" + topic);
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** How many records have been rejected; W27 lets an implementation count them. */
    long rejected() {
        return rejected.get();
    }

    private void run() {
        ExecutorService[] workers = variant == Variant.RACY_RECOMPUTE ? racyWorkers() : null;
        try {
            assignEveryPartition();
            while (running.get()) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(POLL)) {
                    if (workers == null) {
                        apply(record);
                    } else { // each key to one worker, so its order holds; different keys overlap (W34)
                        workers[Math.floorMod(String.valueOf(record.key()).hashCode(), workers.length)].execute(() -> apply(record));
                    }
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } finally {
            if (workers != null) {
                for (ExecutorService worker : workers) {
                    worker.shutdownNow();
                }
            }
            consumer.close();
        }
    }

    private static ExecutorService[] racyWorkers() {
        ExecutorService[] workers = new ExecutorService[4];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = Executors.newSingleThreadExecutor();
        }
        return workers;
    }

    private void assignEveryPartition() {
        List<PartitionInfo> partitions = consumer.partitionsFor(topic);
        while (partitions == null || partitions.isEmpty()) {
            LOG.info("waiting for topic " + topic);
            sleep();
            partitions = consumer.partitionsFor(topic);
        }
        List<TopicPartition> assigned = partitions.stream()
            .map(p -> new TopicPartition(topic, p.partition())).toList();
        consumer.assign(assigned);
        consumer.seekToBeginning(assigned);
    }

    private void apply(ConsumerRecord<String, byte[]> record) {
        EventReader.Read read;
        try {
            read = reader.read(record.key(), record.value());
        } catch (RuntimeException e) { // a record nothing anticipated is still skipped, never fatal (W27)
            read = new EventReader.Reject("unreadable: " + e);
        }
        switch (read) {
            case EventReader.Apply apply when apply.exists() -> graph.assertEdge(apply.edge());
            case EventReader.Apply apply -> graph.retractEdge(apply.edge());
            case EventReader.Tombstone tombstone -> { }
            case EventReader.Reject reject -> {
                rejected.incrementAndGet();
                if (variant == Variant.HALTS_ON_REJECT) {
                    running.set(false);
                }
                LOG.warning("rejected " + record.topic() + "-" + record.partition() + "@" + record.offset()
                    + ": " + reject.reason());
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        consumer.wakeup();
        try {
            thread.join(Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
