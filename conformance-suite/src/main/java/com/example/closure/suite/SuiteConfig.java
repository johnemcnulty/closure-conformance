package com.example.closure.suite;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * How to reach the instance under test (suite-design §6). Each setting is read from an environment
 * variable, so the same image runs anywhere. A system property of the same name takes precedence,
 * which is how the suite's own build points it at a reference instance started in-process.
 *
 * <p>Only the settings a fixture already uses are here; the rest of §6's table joins as the
 * fixtures that need them are written.
 */
public record SuiteConfig(URI discoveryUrl, String kafkaBootstrap, Duration settleTimeout, RunId runId,
                          int resultSizeFanout, Map<String, String> kafkaClient) {

    public static final String DISCOVERY_URL = "DISCOVERY_URL";
    public static final String KAFKA_BOOTSTRAP = "KAFKA_BOOTSTRAP";
    public static final String SETTLE_TIMEOUT = "SETTLE_TIMEOUT";
    public static final String RUN_ID = "RUN_ID";
    public static final String RESULT_SIZE_FANOUT = "RESULT_SIZE_FANOUT";
    /**
     * Any setting with this prefix is a Kafka client property, for authentication against a real
     * broker: {@code KAFKA_CLIENT_SECURITY_PROTOCOL} becomes {@code security.protocol}, and
     * {@code KAFKA_CLIENT_SSL_TRUSTSTORE_LOCATION} may name a mounted file (suite-design §6).
     */
    public static final String KAFKA_CLIENT_PREFIX = "KAFKA_CLIENT_";

    static final Duration DEFAULT_SETTLE_TIMEOUT = Duration.ofSeconds(30);
    /** Large enough to exceed a modest ceiling (fixture 10), small enough to publish in seconds. */
    static final int DEFAULT_RESULT_SIZE_FANOUT = 1000;

    public SuiteConfig {
        Objects.requireNonNull(discoveryUrl, "discoveryUrl");
        Objects.requireNonNull(kafkaBootstrap, "kafkaBootstrap");
        Objects.requireNonNull(settleTimeout, "settleTimeout");
        Objects.requireNonNull(runId, "runId");
        if (!discoveryUrl.isAbsolute()) {
            throw new IllegalArgumentException(DISCOVERY_URL + " must be an absolute URL, not " + discoveryUrl);
        }
        if (settleTimeout.isNegative() || settleTimeout.isZero()) {
            throw new IllegalArgumentException(SETTLE_TIMEOUT + " must be positive, not " + settleTimeout);
        }
        kafkaClient = Map.copyOf(kafkaClient);
        if (resultSizeFanout < 1) {
            throw new IllegalArgumentException(RESULT_SIZE_FANOUT + " must be positive, not " + resultSizeFanout);
        }
    }

    private static SuiteConfig current;

    /** The run's configuration, read once: a generated run id must be the same for every fixture. */
    public static synchronized SuiteConfig current() {
        if (current == null) {
            current = fromEnvironment();
        }
        return current;
    }

    public static SuiteConfig fromEnvironment() {
        return new SuiteConfig(
            URI.create(required(DISCOVERY_URL)),
            required(KAFKA_BOOTSTRAP),
            setting(SETTLE_TIMEOUT).map(Duration::parse).orElse(DEFAULT_SETTLE_TIMEOUT),
            setting(RUN_ID).map(RunId::new).orElseGet(RunId::generate),
            setting(RESULT_SIZE_FANOUT).map(Integer::parseInt).orElse(DEFAULT_RESULT_SIZE_FANOUT),
            kafkaClientProperties(System.getenv(), System.getProperties()));
    }

    /** The {@link #KAFKA_CLIENT_PREFIX} settings as Kafka properties; a system property overrides the variable. */
    static Map<String, String> kafkaClientProperties(Map<String, String> environment, Properties systemProperties) {
        Map<String, String> settings = new TreeMap<>(environment);
        systemProperties.stringPropertyNames().forEach(name -> settings.put(name, systemProperties.getProperty(name)));
        Map<String, String> client = new TreeMap<>();
        settings.forEach((name, value) -> {
            if (name.startsWith(KAFKA_CLIENT_PREFIX) && name.length() > KAFKA_CLIENT_PREFIX.length() && !value.isBlank()) {
                client.put(name.substring(KAFKA_CLIENT_PREFIX.length()).toLowerCase(Locale.ROOT).replace('_', '.'), value);
            }
        });
        return client;
    }

    /** Properties for a Kafka client of this run: the bootstrap address and any {@link #KAFKA_CLIENT_PREFIX} settings. */
    public Properties kafkaProperties() {
        Properties properties = new Properties();
        properties.putAll(kafkaClient);
        properties.put("bootstrap.servers", kafkaBootstrap);
        return properties;
    }

    /** Never prints the client settings' values, which may hold credentials. */
    @Override
    public String toString() {
        return "SuiteConfig[discoveryUrl=" + discoveryUrl + ", kafkaBootstrap=" + kafkaBootstrap + ", settleTimeout="
            + settleTimeout + ", runId=" + runId + ", resultSizeFanout=" + resultSizeFanout
            + ", kafkaClient=" + kafkaClient.keySet() + "]";
    }

    private static String required(String name) {
        return setting(name).orElseThrow(() -> new IllegalStateException(name + " is not set"));
    }

    private static Optional<String> setting(String name) {
        return Optional.ofNullable(System.getProperty(name))
            .or(() -> Optional.ofNullable(System.getenv(name)))
            .filter(value -> !value.isBlank());
    }
}
