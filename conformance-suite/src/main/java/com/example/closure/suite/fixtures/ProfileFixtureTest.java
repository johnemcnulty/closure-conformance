package com.example.closure.suite.fixtures;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.suite.ProfileGate;
import com.example.closure.suite.ProfileVerdict;
import org.junit.jupiter.api.Test;

/**
 * Fixture 1: the discovery document matches the test profile exactly (§7, W4). It is the one
 * fixture not marked {@code @Fixture}, because it is the gate rather than gated by it.
 */
class ProfileFixtureTest {

    @Test
    void discoveryDocumentMatchesTheProfile() { // W4, §7 fixture 1
        ProfileVerdict verdict = ProfileGate.verdict();
        assertTrue(verdict.passed(), verdict::report);
    }
}
