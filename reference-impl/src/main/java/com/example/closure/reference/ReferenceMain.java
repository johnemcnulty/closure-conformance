package com.example.closure.reference;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs a reference instance on its own, for trying the packaged suite against something outside
 * the build. Configured, like the suite, from the environment:
 *
 * <ul>
 *   <li>{@code KAFKA_BOOTSTRAP}, required: the broker to consume from</li>
 *   <li>{@code INGEST_TOPIC}: the topic, default {@code conformance-edges}; it must already exist,
 *       compacted and with at least two partitions (A.5)</li>
 *   <li>{@code PORT}: the HTTP port, default 8080</li>
 *   <li>{@code ADVERTISED_URL}: the base URL others reach it at, default {@code http://localhost:PORT/}</li>
 *   <li>{@code VARIANT}: a {@link Variant} to plant, default {@code CORRECT}</li>
 * </ul>
 */
public final class ReferenceMain {

    private ReferenceMain() {
    }

    public static void main(String[] args) throws Exception {
        String bootstrap = setting("KAFKA_BOOTSTRAP").orElseThrow(() -> new IllegalStateException("KAFKA_BOOTSTRAP is not set"));
        String topic = setting("INGEST_TOPIC").orElse("conformance-edges");
        int port = setting("PORT").map(Integer::parseInt).orElse(8080);
        URI advertised = URI.create(setting("ADVERTISED_URL").orElse("http://localhost:" + port + "/"));
        Variant variant = Variant.valueOf(setting("VARIANT").orElse("CORRECT").toUpperCase(Locale.ROOT));

        ReferenceInstance instance = ReferenceInstance.start(new InetSocketAddress(port), advertised, bootstrap, topic, variant);
        Runtime.getRuntime().addShutdownHook(new Thread(instance::close));
        System.out.println("reference instance (" + variant + ") consuming " + topic + " at " + bootstrap
            + "; discovery document at " + instance.discoveryUrl());
        Thread.currentThread().join();
    }

    private static Optional<String> setting(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }
}
