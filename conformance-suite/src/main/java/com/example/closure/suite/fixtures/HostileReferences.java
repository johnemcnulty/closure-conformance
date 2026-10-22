package com.example.closure.suite.fixtures;

import static com.example.closure.model.TestProfile.ALPHA;
import static com.example.closure.model.TestProfile.HOLDS;
import static com.example.closure.model.TestProfile.OMEGA;

import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import com.example.closure.suite.Example;
import com.example.closure.suite.Example.Row;
import com.example.closure.suite.GraphBuilder;
import com.example.closure.suite.RunId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixture 7's graph and table (§7): ids carrying the deployment's delimiters, spaces, quotes and
 * non-ASCII round-trip unchanged through an ingest event and through {@code of} (L10). One holder
 * holds every hostile {@code omega}, so a single result carries them all back out, and each is
 * then asked about as an argument in its own right.
 */
public final class HostileReferences {

    static final String CONTENTS = TestProfile.CONTENTS.name();
    static final String CONTAINERS = TestProfile.CONTAINERS.name();

    /** Each hostile id, by what it stresses. */
    static final Map<String, String> IDS = hostileIds();

    private static Map<String, String> hostileIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("theEdgeIdSeparator", "a>b");
        ids.put("theEdgeIdEscape", "100%");
        ids.put("anAlreadyEscapedSeparator", "a%3Eb");
        ids.put("parentheses", "(x)");
        ids.put("theReferenceDelimiter", "a:b:c");
        ids.put("anEdgeReferenceLookalike", "edge:(alpha:A>holds>omega:O)");
        ids.put("commasAndSlashes", "PT/1,2");
        ids.put("spaces", " leading and trailing ");
        ids.put("quotes", "it's \"quoted\"");
        ids.put("aBackslash", "back\\slash");
        ids.put("queryAndFragmentCharacters", "?a=1&b=2#frag");
        ids.put("nonAscii", "日本語-Ωμέγα");
        ids.put("anAstralCharacter", "🚀");
        return ids;
    }

    private HostileReferences() {
    }

    public static Example example(RunId run) {
        GraphBuilder graph = new GraphBuilder(run.child("hostile"));
        Ref holder = graph.ref(ALPHA, "holder");
        List<Ref> everything = new ArrayList<>(List.of(holder));
        List<Row> rows = new ArrayList<>();
        IDS.forEach((stresses, id) -> {
            Ref hostile = graph.ref(OMEGA, id);
            graph.edge(holder, HOLDS, hostile);
            everything.add(hostile);
            rows.add(Row.of("anIdCarrying" + Character.toUpperCase(stresses.charAt(0)) + stresses.substring(1)
                + "RoundTripsAsAnArgument", "L10", CONTAINERS, hostile, hostile, holder));
        });
        rows.add(0, Row.of("everyHostileIdComesBackUnchangedInOneResult", "L10", CONTENTS, holder,
            everything.toArray(Ref[]::new)));
        return new Example("hostile references", graph.build(), rows);
    }
}
