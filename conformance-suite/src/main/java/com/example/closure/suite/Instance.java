package com.example.closure.suite;

import com.example.closure.model.Edge;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * The instance under test, as a fixture reaches it: publish to its topic, ask its closures, and
 * wait for an effect to settle. One per run, built on first use from the configuration and the
 * discovery fixture 1 accepted; a fixture only runs once fixture 1 has passed, so it is never
 * built from a document that failed.
 */
public final class Instance {

    private static Instance instance;

    private final SuiteConfig config;
    private final Discovery discovery;
    private final EdgePublisher publisher;
    private final OfClient ofClient = new OfClient();

    private Instance(SuiteConfig config, Discovery discovery) {
        this.config = config;
        this.discovery = discovery;
        this.publisher = new EdgePublisher(config.kafkaProperties(), discovery.ingestTopic(), config.runId());
    }

    public static synchronized Instance get() {
        if (instance == null) {
            ProfileVerdict verdict = ProfileGate.verdict();
            Discovery discovery = verdict.discovery()
                .orElseThrow(() -> new IllegalStateException("fixture 1 has not passed: " + verdict.report()));
            Instance created = new Instance(SuiteConfig.current(), discovery);
            Runtime.getRuntime().addShutdownHook(new Thread(created.publisher::close));
            instance = created;
        }
        return instance;
    }

    public RunId runId() {
        return config.runId();
    }

    public SuiteConfig config() {
        return config;
    }

    public Discovery discovery() {
        return discovery;
    }

    public EdgePublisher publisher() {
        return publisher;
    }

    public Settle settle() {
        return new Settle(config.settleTimeout());
    }

    /** {@code of} over {@code refs} on the closure named {@code closure}. */
    public OfResponse of(String closure, Collection<Ref> refs) {
        return ofClient.of(discovery.ofLink(closure), refs);
    }

    /** The cycle diagnostic over {@code refs} on the closure named {@code closure} (W36). */
    public OfResponse cycles(String closure, Collection<Ref> refs) {
        return ofClient.of(discovery.cyclesLink(closure), refs);
    }

    /** Polls {@code closure.of(ref)} until it is exactly {@code expected}, failing at the settle timeout. */
    public Set<Ref> settledOf(String closure, Ref ref, Set<Ref> expected) {
        return settle().until(closure + ".of(" + ref + ") = " + expected,
            () -> of(closure, List.of(ref)).references(), expected::equals);
    }

    /** Whether {@code ref} is known: any closure's result over a known reference holds it (L12, L15, L16). */
    public boolean isKnown(Ref ref) {
        return of(TestProfile.CLOSURES.get(0).name(), List.of(ref)).references().contains(ref);
    }

    /**
     * Publishes a fresh sentinel edge on {@code key}'s partition, under a different key, and waits
     * until it is visible: consumption has then continued past everything sent on that partition
     * before it (suite-design §6). That an earlier record on another key was applied, or not, is
     * evidence rather than proof, since W34 lets keys be applied in either order.
     */
    public void awaitConsumedPast(String key) {
        RunId sentinels = runId().child("sentinel-" + SENTINELS.incrementAndGet());
        List<Edge> candidates = IntStream.range(0, 200)
            .mapToObj(i -> Edge.of(sentinels.ref(TestProfile.OMEGA, "S" + i), TestProfile.NOTES,
                sentinels.ref(TestProfile.OMEGA, "T" + i)))
            .toList();
        Edge sentinel = publisher.sentinelAfter(key, candidates);
        settle().until("the sentinel " + sentinel + " after " + key + " is visible",
            () -> isKnown(sentinel.subject()), known -> known);
    }

    private static final AtomicInteger SENTINELS = new AtomicInteger();
}
