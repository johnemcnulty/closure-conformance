package com.example.closure.suite;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of fixture 1: where the document came from, every discrepancy found, and — only if
 * there were none — what the suite reads from the document to reach the instance.
 */
public record ProfileVerdict(String source, List<String> problems, Optional<Discovery> discovery) {

    public ProfileVerdict {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(discovery, "discovery");
        problems = List.copyOf(problems);
        if (problems.isEmpty() == discovery.isEmpty()) {
            throw new IllegalArgumentException("a verdict carries a discovery exactly when it passed");
        }
    }

    public static ProfileVerdict passed(String source, Discovery discovery) {
        return new ProfileVerdict(source, List.of(), Optional.of(discovery));
    }

    public static ProfileVerdict failed(String source, List<String> problems) {
        return new ProfileVerdict(source, problems, Optional.empty());
    }

    public boolean passed() {
        return problems.isEmpty();
    }

    /** One readable report, for fixture 1's failure and every skipped fixture's reason. */
    public String report() {
        if (passed()) {
            return "the instance at " + source + " matches the test profile";
        }
        StringBuilder report = new StringBuilder("the instance at " + source
            + " does not match the test profile (Appendix A):");
        problems.forEach(problem -> report.append("\n  - ").append(problem));
        return report.toString();
    }
}
