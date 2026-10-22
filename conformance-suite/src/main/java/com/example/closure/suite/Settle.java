package com.example.closure.suite;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * Polling bounded by the tester's settle timeout (§7). The contract fixes no consistency bound
 * (§1.3), so a fixture waits for an effect to become visible rather than expecting it at once; the
 * timeout is test configuration, and running out of it fails the fixture with the last value seen.
 */
public record Settle(Duration timeout) {

    static final Duration INTERVAL = Duration.ofMillis(100);

    public Settle {
        Objects.requireNonNull(timeout, "timeout");
    }

    /** Polls until {@code done} holds for what {@code poll} returns, and returns that value. */
    public <T> T until(String what, Callable<T> poll, Predicate<? super T> done) {
        AtomicReference<T> last = new AtomicReference<>();
        try {
            return Awaitility.await(what)
                .atMost(timeout)
                .pollInterval(INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    T value = poll.call();
                    last.set(value);
                    return value;
                }, done::test);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("did not settle within " + timeout + ": " + what + "; last saw " + last.get(), e);
        }
    }
}
