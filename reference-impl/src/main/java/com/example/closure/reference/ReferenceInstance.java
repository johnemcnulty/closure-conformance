package com.example.closure.reference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;

/**
 * A running reference instance: the discovery document (W4), ingest from one topic (§6.5, §6.6),
 * and {@code of} on every closure the document declares (W5). Its configuration is the document
 * it publishes, so what it computes and what it reports cannot disagree.
 */
public final class ReferenceInstance implements AutoCloseable {

    public static final String DISCOVERY_PATH = "/discovery";

    private final HttpServer server;
    private final Ingest ingest;
    private final URI base;

    private ReferenceInstance(HttpServer server, Ingest ingest, URI base) {
        this.server = server;
        this.ingest = ingest;
        this.base = base;
    }

    /**
     * Starts an instance serving HTTP on {@code address} (port 0 picks a free one) and consuming
     * {@code ingestTopic} at {@code kafkaBootstrap}.
     *
     * @throws IllegalArgumentException if the configuration is one the contract says an instance
     *     must refuse to start with (L2, W23)
     */
    public static ReferenceInstance start(InetSocketAddress address, String kafkaBootstrap, String ingestTopic)
            throws IOException {
        return start(address, kafkaBootstrap, ingestTopic, Variant.CORRECT);
    }

    /** Starts an instance with {@code variant}'s defect planted (suite-design §5). */
    public static ReferenceInstance start(InetSocketAddress address, String kafkaBootstrap, String ingestTopic,
                                          Variant variant) throws IOException {
        return start(address, null, kafkaBootstrap, ingestTopic, variant);
    }

    /**
     * Starts an instance whose links name {@code advertised}, the address others reach it at, which
     * differs from the one it binds to behind a container network or a gateway; null takes the
     * bound address.
     */
    public static ReferenceInstance start(InetSocketAddress address, URI advertised, String kafkaBootstrap,
                                          String ingestTopic, Variant variant) throws IOException {
        Configuration configuration = Configuration.from(DiscoveryDocument.template(), variant);
        Graph graph = new Graph(configuration, variant);

        HttpServer server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        URI base = advertised != null ? advertised
            : URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/");
        byte[] discovery = DiscoveryDocument.render(base, ingestTopic, variant);
        server.createContext(DISCOVERY_PATH, exchange -> serveDiscovery(exchange, discovery));
        for (String closure : configuration.closures().keySet()) {
            server.createContext(DiscoveryDocument.ofPath(closure), new OfHandler(closure, OfHandler.Mode.OF, graph, variant));
            server.createContext(DiscoveryDocument.cyclesPath(closure), new OfHandler(closure, OfHandler.Mode.CYCLES, graph, variant));
        }

        Ingest ingest = new Ingest(kafkaBootstrap, ingestTopic, new EventReader(configuration, variant), graph, variant);
        ingest.start();
        server.start();
        return new ReferenceInstance(server, ingest, base);
    }

    public URI discoveryUrl() {
        return base.resolve(DISCOVERY_PATH.substring(1));
    }

    /** How many ingest records this instance has rejected (W27). */
    public long rejected() {
        return ingest.rejected();
    }

    private static void serveDiscovery(HttpExchange exchange, byte[] document) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(DISCOVERY_PATH)) {
                exchange.sendResponseHeaders(404, -1);
            } else if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", OfHandler.JSON_API);
                exchange.sendResponseHeaders(200, document.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(document);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        ingest.close();
    }
}
