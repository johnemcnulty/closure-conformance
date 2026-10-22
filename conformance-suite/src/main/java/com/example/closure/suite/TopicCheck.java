package com.example.closure.suite;

import com.example.closure.model.TestProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * The part of fixture 1 that looks at the ingest topic itself: the profile fixes that it is
 * log-compacted (W29) and has at least {@link TestProfile#MIN_INGEST_PARTITIONS} partitions (A.5).
 */
public final class TopicCheck {

    private static final long TIMEOUT_SECONDS = 30;

    private TopicCheck() {
    }

    /** What the broker reports about one topic. */
    public record Observed(int partitions, String cleanupPolicy) {
    }

    /** The comparison, separate from fetching so it can be tested without a broker. */
    public static List<String> discrepancies(String topic, Observed observed) {
        List<String> problems = new ArrayList<>();
        if (observed.partitions() < TestProfile.MIN_INGEST_PARTITIONS) {
            problems.add("the ingest topic " + topic + " has " + observed.partitions()
                + " partition(s), but the profile requires at least " + TestProfile.MIN_INGEST_PARTITIONS + " (A.5)");
        }
        List<String> policies = Arrays.stream(observed.cleanupPolicy().split(",")).map(String::trim).toList();
        if (!policies.contains(TopicConfig.CLEANUP_POLICY_COMPACT)) {
            problems.add("the ingest topic " + topic + " has cleanup.policy " + observed.cleanupPolicy()
                + ", but an ingest topic must be log-compacted (W29)");
        }
        return problems;
    }

    /** Fetches and checks; a topic that cannot be described is itself a discrepancy. */
    public static List<String> discrepancies(Properties client, String topic) {
        String bootstrap = client.getProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
        try (Admin admin = Admin.create(client)) {
            return discrepancies(topic, observe(admin, topic));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return List.of("the ingest topic " + topic + " the document names does not exist at " + bootstrap);
            }
            return List.of("could not describe the ingest topic " + topic + " at " + bootstrap + ": " + e.getCause());
        } catch (TimeoutException e) {
            return List.of("timed out describing the ingest topic " + topic + " at " + bootstrap);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("interrupted describing the ingest topic " + topic);
        }
    }

    private static Observed observe(Admin admin, String topic)
            throws ExecutionException, InterruptedException, TimeoutException {
        TopicDescription description = admin.describeTopics(Set.of(topic)).allTopicNames()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic);
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Map<ConfigResource, Config> configs = admin.describeConfigs(Set.of(resource)).all()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new Observed(description.partitions().size(),
            configs.get(resource).get(TopicConfig.CLEANUP_POLICY_CONFIG).value());
    }
}
