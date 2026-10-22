package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.closure.model.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Runs an {@link Example} against the instance: publishes its graph once, waits until every edge is
 * visible, then asserts each row as its own test, named for what it asserts.
 */
public final class ExampleRun {

    private ExampleRun() {
    }

    public static Stream<DynamicTest> rows(Example example) {
        Instance instance = Instance.get();
        example.graph().publishTo(instance);
        return example.rows().stream().flatMap(row -> Stream.of(
            DynamicTest.dynamicTest(row.displayName(), () -> assertRow(instance, row)),
            DynamicTest.dynamicTest(row.displayName() + ", diagnosed", () -> CycleDiagnosis.assertDiagnosis(
                instance.cycles(row.closure(), row.arguments()), row.closure(), example.graph().edges(), row.cycles()))));
    }

    private static Set<String> fieldNames(JsonNode element) {
        Set<String> names = new HashSet<>();
        element.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertRow(Instance instance, Example.Row row) {
        OfResponse response = instance.of(row.closure(), row.arguments());
        assertEquals(200, response.status(), () -> "of did not succeed (W8): " + response.body());
        List<?> listed = response.referenceList();
        assertEquals(new HashSet<>(listed).size(), listed.size(), () -> "the result holds duplicates (L6): " + listed);
        Set<Set<String>> shapes = new HashSet<>();
        response.body().path("data").forEach(element -> shapes.add(fieldNames(element)));
        assertTrue(shapes.size() <= 1,
            () -> "the result's references are not all returned the same way (L24): " + response.body().path("data"));
        Set<Ref> actual = response.references();
        if (!actual.equals(row.expected())) {
            Set<Ref> missing = new HashSet<>(row.expected());
            missing.removeAll(actual);
            Set<Ref> extra = new HashSet<>(actual);
            extra.removeAll(row.expected());
            fail(row.displayName() + ": missing " + missing + ", should not have reached " + extra);
        }
        Qualifications.assertQualifications(response.meta(), row.cycles(), row.breaches());
    }
}
