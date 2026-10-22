package com.example.closure.oracle;

import com.example.closure.model.Ref;

/** A reference breaching a cardinality rule, named by the rule's published identifier. */
public record Breach(String ruleId, Ref ref) {
}
