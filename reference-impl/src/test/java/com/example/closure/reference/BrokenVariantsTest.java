package com.example.closure.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.suite.SuiteConfig;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The suite rejects a wrong implementation (suite-design §5). Each {@link Variant} is started on its
 * own topic and port, and the fixtures it names are run against it in a separate JVM through the
 * Console Launcher — as the suite's image will run them — and must fail. {@link Variant#CORRECT}
 * runs every named fixture and must pass them all, which is what shows a failure comes from the
 * variant's defect and not from the runner. A separate JVM per run is needed because the suite
 * reads its configuration and reaches its profile verdict once per run.
 */
class BrokenVariantsTest {

    static final String FIXTURES = "com.example.closure.suite.fixtures.";
    /** Short, since a broken instance makes waits run to their bound. */
    static final String SETTLE_TIMEOUT = "PT5S";
    static final int PARALLEL_RUNS = 4;
    static final long RUN_LIMIT_MINUTES = 10;
    /** Attempts a probabilistic variant gets to be caught; any one failure of its fixtures is enough. */
    static final int PROBABILISTIC_ATTEMPTS = 3;

    /** One fixture class's tests in one run. */
    record Tally(int passed, int failed, int skipped) {
        int ran() {
            return passed + failed + skipped;
        }
    }

    /** What one run against one variant produced. */
    record Outcome(Variant variant, int exitCode, Map<String, Tally> byFixture, String log) {
        Tally of(String fixture) {
            return byFixture.getOrDefault(fixture, new Tally(0, 0, 0));
        }

        String tail() {
            String[] lines = log.split("\n");
            return String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 40), lines.length));
        }
    }

    /** The fixtures each run selects: every named one for the control, a variant's own otherwise. */
    static Set<String> selected(Variant variant) {
        if (variant == Variant.CORRECT) {
            return Arrays.stream(Variant.values()).flatMap(v -> Stream.concat(v.caughtBy().stream(), v.passes().stream()))
                .collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> fixtures = new TreeSet<>(variant.caughtBy());
        fixtures.addAll(variant.passes());
        if (variant == Variant.MISCONFIGURED) {
            fixtures.add("TypeRestrictionFixtureTest"); // a gated fixture, which must be skipped
        }
        return fixtures;
    }

    @TestFactory
    Stream<DynamicTest> everyVariantFailsTheFixturesNamedForIt() throws Exception { // suite-design §5
        String bootstrap = SelfTestInstance.bootstrap();
        Map<Variant, Future<Outcome>> runs = new EnumMap<>(Variant.class);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_RUNS);
        try {
            for (Variant variant : Variant.values()) {
                runs.put(variant, pool.submit(() -> attempts(variant, bootstrap)));
            }
            Map<Variant, Outcome> outcomes = new EnumMap<>(Variant.class);
            for (Map.Entry<Variant, Future<Outcome>> run : runs.entrySet()) {
                outcomes.put(run.getKey(), run.getValue().get(RUN_LIMIT_MINUTES, TimeUnit.MINUTES));
            }
            return outcomes.values().stream().map(outcome -> DynamicTest.dynamicTest(
                describe(outcome.variant()), () -> check(outcome)));
        } finally {
            pool.shutdownNow();
        }
    }

    static String describe(Variant variant) {
        return variant == Variant.CORRECT ? "CORRECT passes every named fixture"
            : variant.caughtBy().isEmpty() ? variant + " passes " + variant.passes()
            : variant + " fails " + variant.caughtBy();
    }

    static void check(Outcome outcome) {
        Variant variant = outcome.variant();
        for (String fixture : selected(variant)) {
            Tally tally = outcome.of(fixture);
            assertTrue(tally.ran() > 0, () -> fixture + " ran no tests against " + variant + ":\n" + outcome.tail());
            if (variant == Variant.CORRECT || variant.passes().contains(fixture)) {
                assertEquals(new Tally(tally.ran(), 0, 0), tally,
                    () -> fixture + " must pass against " + variant + ", which is correct:\n" + outcome.tail());
            } else if (variant.caughtBy().contains(fixture)) {
                assertTrue(tally.failed() > 0, () -> fixture + " must fail against " + variant + ", but " + tally + ":\n" + outcome.tail());
            } else {
                assertEquals(new Tally(0, 0, tally.ran()), tally,
                    () -> fixture + " must be skipped, not failed, once fixture 1 fails (§6):\n" + outcome.tail());
            }
        }
    }

    /** One run, or for a probabilistic variant up to {@link #PROBABILISTIC_ATTEMPTS}, stopping once its fixtures fail. */
    static Outcome attempts(Variant variant, String bootstrap) throws Exception {
        Outcome outcome = run(variant, bootstrap, 1);
        for (int attempt = 2; variant.probabilistic() && !caught(outcome) && attempt <= PROBABILISTIC_ATTEMPTS; attempt++) {
            outcome = run(variant, bootstrap, attempt);
        }
        return outcome;
    }

    static boolean caught(Outcome outcome) {
        return outcome.variant().caughtBy().stream().allMatch(fixture -> outcome.of(fixture).failed() > 0);
    }

    /** Starts {@code variant}, runs its selected fixtures against it in a fresh JVM, and tallies the report. */
    static Outcome run(Variant variant, String bootstrap, int attempt) throws Exception {
        String topic = "edges-" + variant.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + attempt;
        SelfTestInstance.createIngestTopic(bootstrap, topic);
        Path reports = Files.createTempDirectory("variant-" + variant.name());
        try (ReferenceInstance instance = ReferenceInstance.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), bootstrap, topic, variant)) {
            List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", suiteClasspath(),
                "-D" + SuiteConfig.DISCOVERY_URL + "=" + instance.discoveryUrl(),
                "-D" + SuiteConfig.KAFKA_BOOTSTRAP + "=" + bootstrap,
                "-D" + SuiteConfig.SETTLE_TIMEOUT + "=" + SETTLE_TIMEOUT,
                "org.junit.platform.console.ConsoleLauncher", "execute",
                "--disable-banner", "--details=none", "--reports-dir=" + reports));
            for (String fixture : selected(variant)) {
                List<String> tests = variant.narrowedTo().stream().filter(t -> t.startsWith(fixture + "#")).toList();
                if (tests.isEmpty()) {
                    command.add("--select-class=" + FIXTURES + fixture);
                } else {
                    tests.forEach(test -> command.add("--select-method=" + FIXTURES + test));
                }
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new Outcome(variant, exit, tally(reports), log);
        }
    }

    /**
     * This JVM's classpath, less this module's own test classes: those register the listener that
     * starts a self-test instance, which the forked suite must not do — it is pointed at a variant.
     */
    static String suiteClasspath() {
        String testClasses = Path.of("target", "test-classes").toAbsolutePath().toString();
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> !Path.of(entry).toAbsolutePath().toString().equals(testClasses))
            .collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * Passed, failed and skipped tests per fixture class, from the Console Launcher's XML reports:
     * one per engine, Jupiter's for the example fixtures and jqwik's for the generated ones.
     */
    static Map<String, Tally> tally(Path reports) throws Exception {
        Map<String, Tally> byFixture = new HashMap<>();
        List<Path> files;
        try (Stream<Path> listed = Files.list(reports)) {
            files = listed.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).toList();
        }
        for (Path report : files) {
            tally(report, byFixture);
        }
        return byFixture;
    }

    static void tally(Path report, Map<String, Tally> byFixture) throws Exception {
        NodeList cases = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.toFile())
            .getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            String fixture = testCase.getAttribute("classname").replace(FIXTURES, "");
            boolean failed = testCase.getElementsByTagName("failure").getLength() > 0
                || testCase.getElementsByTagName("error").getLength() > 0;
            boolean skipped = testCase.getElementsByTagName("skipped").getLength() > 0;
            Tally was = byFixture.getOrDefault(fixture, new Tally(0, 0, 0));
            byFixture.put(fixture, new Tally(
                was.passed() + (failed || skipped ? 0 : 1), was.failed() + (failed ? 1 : 0), was.skipped() + (skipped ? 1 : 0)));
        }
    }
}
