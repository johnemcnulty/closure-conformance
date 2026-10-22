package com.example.closure.model;

/**
 * The edge id function (W21): an edge's subject, edge type and object, in that order, separated by
 * {@code >} and enclosed in parentheses.
 *
 * <pre>
 * id        (card:X&gt;hosts&gt;port:PT/1,2)
 * reference  edge:(card:X&gt;hosts&gt;port:PT/1,2)
 * </pre>
 *
 * <p>The id is what an event's inner object carries; the <em>reference</em> is that id behind an
 * {@code edge:} type, and is what keys the records (W24). Within each component {@code %} becomes
 * {@code %25} and {@code >} becomes {@code %3E}, in that order — so nothing an id may contain is
 * forbidden (L10), and an id carrying a comma stays literal. The parentheses are positional, never
 * searched for, so an id containing one needs no treatment.
 */
public final class EdgeId {

    /** The resource type every edge has (W20). */
    public static final String EDGE = "edge";

    private static final String DELIMITER = ">";
    private static final String ESCAPED_DELIMITER = "%3E";
    private static final String ESCAPE = "%";
    private static final String ESCAPED_ESCAPE = "%25";

    private EdgeId() {
    }

    /** The id of the edge a triple names. */
    public static String of(Edge edge) {
        return "(" + escape(Canonical.write(edge.subject()))
            + DELIMITER + escape(edge.edgeType())
            + DELIMITER + escape(Canonical.write(edge.object())) + ")";
    }

    /** The edge's canonical reference: its id behind the {@code edge} type. */
    public static String referenceOf(Edge edge) {
        return Canonical.write(new Ref(EDGE, of(edge)));
    }

    /**
     * The triple an edge id names.
     *
     * @throws IllegalArgumentException if it is not an edge id
     */
    public static Edge parse(String id) {
        if (id.length() < 2 || id.charAt(0) != '(' || id.charAt(id.length() - 1) != ')') {
            throw new IllegalArgumentException("not an edge id, which is parenthesized: " + id);
        }
        String[] parts = id.substring(1, id.length() - 1).split(DELIMITER, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                "an edge id has three components, found " + parts.length + ": " + id);
        }
        return new Edge(Canonical.parse(unescape(parts[0])), unescape(parts[1]), Canonical.parse(unescape(parts[2])));
    }

    /**
     * The triple an edge reference names.
     *
     * @throws IllegalArgumentException if it is not a reference of type {@code edge}
     */
    public static Edge parseReference(String reference) {
        Ref ref = Canonical.parse(reference);
        if (!EDGE.equals(ref.type())) {
            throw new IllegalArgumentException("not an edge reference, its type is " + ref.type() + ": " + reference);
        }
        return parse(ref.id());
    }

    // The escape character goes first, so an id already holding "%3E" survives the round trip.
    private static String escape(String component) {
        return component.replace(ESCAPE, ESCAPED_ESCAPE).replace(DELIMITER, ESCAPED_DELIMITER);
    }

    private static String unescape(String component) {
        return component.replace(ESCAPED_DELIMITER, DELIMITER).replace(ESCAPED_ESCAPE, ESCAPE);
    }
}
