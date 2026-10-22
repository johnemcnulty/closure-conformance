package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * The gate spans both engines (suite-design §6): when fixture 1 fails, a Jupiter fixture and a
 * jqwik fixture are both skipped, not failed, and when it passes both run. The samples are nested
 * classes, which Surefire does not pick up on its own.
 */
class ProfileGateTest {

    static final ProfileVerdict FAILED = ProfileVerdict.failed("http://instance/discovery", List.of("closure x is missing"));
    static final ProfileVerdict PASSED =
        ProfileVerdict.passed("http://instance/discovery", new Discovery("edges", Map.of(), Map.of()));

    @AfterEach
    void restoreTheRealCheck() {
        ProfileGate.reset();
    }

    @Fixture
    static class JupiterSample {
        @Test
        void runs() {
        }
    }

    @Fixture
    static class JqwikSample {
        @Example
        void runs() {
        }
    }

    static Events run(String engine, Class<?> sample) {
        return EngineTestKit.engine(engine).selectors(selectClass(sample)).execute().allEvents();
    }

    static Events jupiter() {
        return run("junit-jupiter", JupiterSample.class);
    }

    static Events jqwik() {
        return run("jqwik", JqwikSample.class);
    }

    static void assertSkippedWithTheReport(Events events) {
        assertEquals(0, events.started().filter(e -> e.getTestDescriptor().isTest()).count());
        assertEquals(0, events.failed().count());
        assertTrue(events.skipped().stream()
            .anyMatch(e -> e.getPayload(String.class).orElse("").contains("closure x is missing")));
    }

    @Test
    void aFailedProfileSkipsJupiterFixtures() { // §7 fixture 1
        ProfileGate.useForTesting(() -> FAILED);
        assertSkippedWithTheReport(jupiter());
    }

    @Test
    void aFailedProfileSkipsJqwikFixtures() { // §7 fixture 1
        ProfileGate.useForTesting(() -> FAILED);
        assertSkippedWithTheReport(jqwik());
    }

    @Test
    void aPassedProfileRunsFixturesOnBothEngines() { // §7 fixture 1
        ProfileGate.useForTesting(() -> PASSED);
        assertEquals(1, jupiter().succeeded().filter(e -> e.getTestDescriptor().isTest()).count());
        assertEquals(0, jupiter().skipped().count());
        Events events = jqwik();
        assertEquals(1, events.succeeded().filter(e -> e.getTestDescriptor().isTest()).count());
        assertEquals(0, events.skipped().count());
    }

    @Test
    void theCheckRunsOnceForEveryFixture() { // suite-design §6
        AtomicInteger runs = new AtomicInteger();
        ProfileGate.useForTesting(() -> {
            runs.incrementAndGet();
            return PASSED;
        });
        jupiter();
        jqwik();
        assertEquals(1, runs.get());
    }
}
