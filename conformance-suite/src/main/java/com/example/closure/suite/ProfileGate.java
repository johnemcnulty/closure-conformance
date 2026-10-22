package com.example.closure.suite;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.function.Supplier;
import net.jqwik.api.lifecycle.LifecycleContext;
import net.jqwik.api.lifecycle.SkipExecutionHook;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fixture 1 gates the rest (suite-design §6). The profile check runs once, on first demand from
 * whichever test asks first, and every fixture after it reads the memoized verdict. The gate spans
 * both engines: {@link JupiterCondition} for example fixtures and {@link JqwikHook} for generated
 * ones, both attached through {@link Fixture}. A failed check skips a fixture rather than failing
 * it, so a misconfigured instance yields one failure, fixture 1's, carrying the whole report.
 */
public final class ProfileGate {

    private static final Supplier<ProfileVerdict> CHECK_THE_INSTANCE = ProfileGate::checkTheInstance;

    private static Supplier<ProfileVerdict> source = CHECK_THE_INSTANCE;
    private static ProfileVerdict verdict;

    private ProfileGate() {
    }

    /** The verdict, computing it on the first call. */
    public static synchronized ProfileVerdict verdict() {
        if (verdict == null) {
            verdict = source.get();
        }
        return verdict;
    }

    /** The document against the profile, then — once the document names one — its topic. */
    private static ProfileVerdict checkTheInstance() {
        SuiteConfig config;
        try {
            config = SuiteConfig.current();
        } catch (RuntimeException e) {
            return ProfileVerdict.failed("(unconfigured)", List.of("the suite is not configured: " + e.getMessage()));
        }
        URI discoveryUrl = config.discoveryUrl();
        String source = discoveryUrl.toString();
        JsonNode document;
        try {
            document = new DiscoveryClient().fetch(discoveryUrl);
        } catch (IOException e) {
            return ProfileVerdict.failed(source, List.of(e.getMessage()));
        }
        List<String> problems = ProfileCheck.discrepancies(document, discoveryUrl);
        if (!problems.isEmpty()) {
            return ProfileVerdict.failed(source, problems);
        }
        Discovery discovery = Discovery.from(document, discoveryUrl);
        problems = TopicCheck.discrepancies(config.kafkaProperties(), discovery.ingestTopic());
        return problems.isEmpty() ? ProfileVerdict.passed(source, discovery) : ProfileVerdict.failed(source, problems);
    }

    /** For the gate's own tests: replace the check, and forget any verdict already reached. */
    static synchronized void useForTesting(Supplier<ProfileVerdict> replacement) {
        source = replacement;
        verdict = null;
    }

    /** For the gate's own tests: restore the real check. */
    static synchronized void reset() {
        useForTesting(CHECK_THE_INSTANCE);
    }

    private static String skipReason() {
        return "skipped because fixture 1 failed: " + verdict().report();
    }

    /** Skips a Jupiter fixture when fixture 1 has failed. */
    public static final class JupiterCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return verdict().passed()
                ? ConditionEvaluationResult.enabled("fixture 1 passed")
                : ConditionEvaluationResult.disabled(skipReason());
        }
    }

    /** Skips a jqwik fixture when fixture 1 has failed. */
    public static final class JqwikHook implements SkipExecutionHook {
        @Override
        public SkipResult shouldBeSkipped(LifecycleContext context) {
            return verdict().passed() ? SkipResult.doNotSkip() : SkipResult.skip(skipReason());
        }
    }
}
