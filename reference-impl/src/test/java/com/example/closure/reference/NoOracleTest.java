package com.example.closure.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The reference implementation must not use the oracle (suite-design §3, §5): sharing code would
 * make the self-test check the oracle against itself. The oracle is on this module's test classpath,
 * through the suite, so the compiler alone does not stop main code from reaching it. This scans the
 * compiled main classes for any reference to the oracle's package.
 */
class NoOracleTest {

    static final String ORACLE_PACKAGE = "com/example/closure/oracle";

    @Test
    void noMainClassReferencesTheOracle() throws IOException {
        Path classes = Path.of("target", "classes");
        assertTrue(Files.isDirectory(classes), "the main classes are compiled before tests run");
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(f -> f.toString().endsWith(".class"))
                .filter(NoOracleTest::mentionsTheOracle)
                .toList();
        }
        assertEquals(List.of(), offenders, "reference-impl's main code references the oracle");
    }

    /** A reference to the oracle, so this class's own bytes show what the scan looks for. */
    static final Class<?> AN_ORACLE_REFERENCE = com.example.closure.oracle.Oracle.class;

    @Test
    void theScanFindsAReferenceWhereThereIsOne() { // the check is not vacuous
        assertTrue(mentionsTheOracle(Path.of("target", "test-classes", "com", "example", "closure", "reference",
            "NoOracleTest.class")));
    }

    static boolean mentionsTheOracle(Path classFile) {
        try {
            return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1).contains(ORACLE_PACKAGE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
