package com.example.closure.reference;

import com.example.closure.suite.SuiteConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Stands up what a tester would before the suite's fixtures run in this module's build: a Kafka
 * broker (Testcontainers), an ingest topic configured to the profile (A.5), and a reference
 * instance consuming it. It points the suite at them through system properties. A launcher may
 * open more than one session in a run, so starting is idempotent, and everything lives until the
 * JVM exits.
 */
public final class SelfTestInstance implements LauncherSessionListener {

    static final String KAFKA_IMAGE = "apache/kafka:4.3.1";
    static final String INGEST_TOPIC = "conformance-edges";
    static final int PARTITIONS = 3;

    private static ReferenceInstance instance;
    private static String bootstrap;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        ensureStarted();
    }

    static synchronized ReferenceInstance ensureStarted() {
        if (instance == null) {
            KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);
            kafka.start();
            bootstrap = kafka.getBootstrapServers();
            createIngestTopic(bootstrap, INGEST_TOPIC);
            try {
                instance = ReferenceInstance.start(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), bootstrap, INGEST_TOPIC);
            } catch (IOException e) {
                throw new UncheckedIOException("could not start the reference instance", e);
            }
            ReferenceInstance started = instance;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                started.close();
                kafka.stop();
            }));
            System.setProperty(SuiteConfig.DISCOVERY_URL, instance.discoveryUrl().toString());
            System.setProperty(SuiteConfig.KAFKA_BOOTSTRAP, bootstrap);
        }
        return instance;
    }

    /** The broker's address, once started; broken variants share it, each on its own topic. */
    static synchronized String bootstrap() {
        ensureStarted();
        return bootstrap;
    }

    /** Creates an ingest topic configured to the profile: compacted, with several partitions (A.5). */
    static void createIngestTopic(String bootstrap, String name) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (Admin admin = Admin.create(properties)) {
            NewTopic topic = new NewTopic(name, PARTITIONS, (short) 1)
                .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
            admin.createTopics(Set.of(topic)).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not create " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
