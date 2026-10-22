package com.example.closure.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.closure.model.Canonical;
import com.example.closure.model.Edge;
import com.example.closure.model.EdgeId;
import com.example.closure.model.Ref;
import com.example.closure.model.TestProfile;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HarnessTest {

    @Nested
    class RunIds {
        @Test
        void generatedRunIdsDiffer() { // suite-design §6
            assertNotEquals(RunId.generate(), RunId.generate());
        }

        @Test
        void aGeneratedRunIdCarriesCharactersThatStressTheEncodings() { // L10, W21
            String value = RunId.generate().value();
            for (String hostile : List.of(" ", "/", ",", ">", "%", "é", ":")) {
                assertTrue(value.contains(hostile), () -> value + " lacks " + hostile);
            }
        }

        @Test
        void aRefCarriesThePrefixAndRoundTrips() { // L9
            RunId run = new RunId("run-1/");
            Ref ref = run.ref("beta", "B1");
            assertEquals("run-1/B1", ref.id());
            assertEquals(ref, Canonical.parse(Canonical.write(ref)));
        }

        @Test
        void aChildRunIdExtendsItsParent() { // suite-design §4
            RunId child = new RunId("run-1/").child("try-3");
            assertTrue(child.value().startsWith("run-1/try-3"));
        }
    }

    @Nested
    class Topics {
        @Test
        void aCompactedTopicWithTwoPartitionsPasses() { // W29, A.5
            assertEquals(List.of(), TopicCheck.discrepancies("edges", new TopicCheck.Observed(2, "compact")));
        }

        @Test
        void compactAlongsideDeleteStillCounts() { // W29
            assertEquals(List.of(), TopicCheck.discrepancies("edges", new TopicCheck.Observed(3, "compact,delete")));
        }

        @Test
        void oneTopicPartitionIsFlagged() { // A.5
            List<String> problems = TopicCheck.discrepancies("edges", new TopicCheck.Observed(1, "compact"));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("1 partition"));
        }

        @Test
        void anUncompactedTopicIsFlagged() { // W29
            List<String> problems = TopicCheck.discrepancies("edges", new TopicCheck.Observed(2, "delete"));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("log-compacted"));
        }
    }

    @Nested
    class KafkaClientSettings {
        @Test
        void prefixedSettingsBecomeKafkaProperties() { // suite-design §6
            Map<String, String> client = SuiteConfig.kafkaClientProperties(Map.of(
                "KAFKA_CLIENT_SECURITY_PROTOCOL", "SASL_SSL",
                "KAFKA_CLIENT_SSL_TRUSTSTORE_LOCATION", "/etc/conformance/kafka/truststore.jks",
                "KAFKA_BOOTSTRAP", "broker:9093",
                "PATH", "/usr/bin"), new Properties());
            assertEquals(Map.of("security.protocol", "SASL_SSL",
                "ssl.truststore.location", "/etc/conformance/kafka/truststore.jks"), client);
        }

        @Test
        void aSystemPropertyOverridesTheVariable() { // suite-design §6
            Properties system = new Properties();
            system.setProperty("KAFKA_CLIENT_SASL_MECHANISM", "SCRAM-SHA-512");
            assertEquals(Map.of("sasl.mechanism", "SCRAM-SHA-512"),
                SuiteConfig.kafkaClientProperties(Map.of("KAFKA_CLIENT_SASL_MECHANISM", "PLAIN"), system));
        }

        @Test
        void theBootstrapAddressJoinsTheClientProperties() { // suite-design §6
            SuiteConfig config = new SuiteConfig(URI.create("http://instance/discovery"), "broker:9093",
                Duration.ofSeconds(1), new RunId("r/"), 10, Map.of("security.protocol", "SSL"));
            assertEquals("broker:9093", config.kafkaProperties().getProperty("bootstrap.servers"));
            assertEquals("SSL", config.kafkaProperties().getProperty("security.protocol"));
        }

        @Test
        void credentialsNeverAppearWhenTheConfigurationIsPrinted() { // suite-design §6
            SuiteConfig config = new SuiteConfig(URI.create("http://instance/discovery"), "broker:9093",
                Duration.ofSeconds(1), new RunId("r/"), 10, Map.of("sasl.jaas.config", "secret-password"));
            assertTrue(config.toString().contains("sasl.jaas.config"));
            assertTrue(!config.toString().contains("secret-password"), config.toString());
        }
    }

    @Nested
    class CycleGroups {
        @Test
        void aGroupHoldsTheAdmittedEdgesAmongItsMembersAndNoOthers() { // W36
            Ref a = Ref.of("alpha", "A");
            Ref g0 = Ref.of("gamma", "G0");
            Ref g1 = Ref.of("gamma", "G1");
            Ref g2 = Ref.of("gamma", "G2");
            Set<Edge> graph = Set.of(
                Edge.of(g0, "fed_by", g1),   // leads into the group: not in it
                Edge.of(g1, "fed_by", g2),
                Edge.of(g2, "fed_by", g1),
                Edge.of(a, "holds", g1),     // a type suppliers does not admit
                Edge.of(g1, "notes", g2));   // between members, but not admitted
            CycleDiagnosis.Group group = CycleDiagnosis.expected(TestProfile.closure("suppliers"), graph, Set.of(g1, g2));
            assertEquals(Set.of(g1, g2), group.members());
            assertEquals(Set.of(
                Ref.of("edge", EdgeId.of(Edge.of(g1, "fed_by", g2))),
                Ref.of("edge", EdgeId.of(Edge.of(g2, "fed_by", g1)))), group.edges());
        }
    }

    @Nested
    class Settling {
        @Test
        void settlingReturnsTheFirstValueThatHolds() { // §1.3
            AtomicInteger polls = new AtomicInteger();
            assertEquals(3, new Settle(Duration.ofSeconds(5)).until("count to 3", polls::incrementAndGet, n -> n == 3));
        }

        @Test
        void notSettlingFailsWithTheLastValueSeen() { // §1.3
            AssertionError failure = assertThrows(AssertionError.class,
                () -> new Settle(Duration.ofMillis(300)).until("never", () -> "stuck", value -> false));
            assertTrue(failure.getMessage().contains("never"));
            assertTrue(failure.getMessage().contains("stuck"));
        }
    }
}
