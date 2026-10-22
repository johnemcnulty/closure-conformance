package com.example.closure.tree.testkit;

import java.util.Objects;
import java.util.Set;

/**
 * One admitted pair of a closure, in parent/child terms: {@code DOWN} follows edges from parent to
 * child, {@code UP} from child to parent. An implementation's adapter maps its configured
 * {@code (edgeType, forward|reversed)} pairs to these through {@code parentEndpoint} (brief §3.2).
 */
public record Admitted(String edgeType, Dir dir) {

    public enum Dir { DOWN, UP }

    public Admitted {
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(dir, "dir");
    }

    public static Admitted down(String edgeType) {
        return new Admitted(edgeType, Dir.DOWN);
    }

    public static Admitted up(String edgeType) {
        return new Admitted(edgeType, Dir.UP);
    }

    public static boolean allDown(Set<Admitted> closure) {
        return !closure.isEmpty() && closure.stream().allMatch(a -> a.dir() == Dir.DOWN);
    }

    public static boolean allUp(Set<Admitted> closure) {
        return !closure.isEmpty() && closure.stream().allMatch(a -> a.dir() == Dir.UP);
    }

    @Override
    public String toString() {
        return edgeType + (dir == Dir.DOWN ? " down" : " up");
    }
}
