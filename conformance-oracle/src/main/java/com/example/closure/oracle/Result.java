package com.example.closure.oracle;

import com.example.closure.model.Ref;

import java.util.List;
import java.util.Set;

/**
 * What the oracle expects of one {@code of} request.
 *
 * <p>{@code refs} is the exact reach set. {@code cycles} lists every cyclic strongly connected
 * component inside it, which is what the cycle diagnostic must report, member for member
 * (W36). {@code breaches} lists every cardinality breach the result carries (L27).
 */
public record Result(Set<Ref> refs, List<Set<Ref>> cycles, Set<Breach> breaches) {
    public Result {
        refs = Set.copyOf(refs);
        cycles = List.copyOf(cycles);
        breaches = Set.copyOf(breaches);
    }

    public boolean cycleDetected() {
        return !cycles.isEmpty();
    }

    /** Every reference in the result breaching the given rule; the response must list min(10, total) of them (L28). */
    public Set<Ref> breachesOf(String ruleId) {
        return breaches.stream().filter(b -> b.ruleId().equals(ruleId)).map(Breach::ref)
            .collect(java.util.stream.Collectors.toSet());
    }
}
