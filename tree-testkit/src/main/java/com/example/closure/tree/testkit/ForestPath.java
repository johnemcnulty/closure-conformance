package com.example.closure.tree.testkit;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A materialized path, root first. Decoded: the kit compares paths structurally, so the tree's
 * segment encoding (brief §3.3) is tested separately.
 */
public record ForestPath(List<Segment> segments) {

    public ForestPath {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) throw new IllegalArgumentException("a path has at least one segment");
        if (segments.get(0).edgeType() != null) throw new IllegalArgumentException("the root segment has no edge type");
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).edgeType() == null)
                throw new IllegalArgumentException("only the root segment may omit its edge type");
        }
    }

    public static ForestPath root(Ref ref) {
        return new ForestPath(List.of(Segment.root(ref)));
    }

    /** The resource this path belongs to. */
    public Ref ref() {
        return segments.get(segments.size() - 1).ref();
    }

    public Ref root() {
        return segments.get(0).ref();
    }

    public boolean isRoot() {
        return segments.size() == 1;
    }

    /** The resource's materialized parent edge, read from the path itself (brief §4.1). */
    public Optional<Edge> materializedEdge() {
        if (isRoot()) return Optional.empty();
        Segment last = segments.get(segments.size() - 1);
        Segment previous = segments.get(segments.size() - 2);
        return Optional.of(new Edge(previous.ref(), last.edgeType(), last.ref()));
    }

    public Optional<ForestPath> parentPath() {
        if (isRoot()) return Optional.empty();
        return Optional.of(new ForestPath(segments.subList(0, segments.size() - 1)));
    }

    public ForestPath child(String edgeType, Ref ref) {
        var extended = new java.util.ArrayList<>(segments);
        extended.add(new Segment(edgeType, ref));
        return new ForestPath(extended);
    }

    /** True when {@code other} lies strictly beneath this path. */
    public boolean isStrictPrefixOf(ForestPath other) {
        return other.segments.size() > segments.size()
                && other.segments.subList(0, segments.size()).equals(segments);
    }

    /** {@code /NE/S1/C}: ids only, edge types omitted, as the brief's traces show paths. */
    public String render() {
        return segments.stream().map(s -> s.ref().id()).collect(Collectors.joining("/", "/", ""));
    }

    @Override
    public String toString() {
        return render();
    }
}
