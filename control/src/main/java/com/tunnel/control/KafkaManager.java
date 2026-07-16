package com.tunnel.control;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "control.kafka-enabled", havingValue = "true")
public class KafkaManager {

    private static final Logger log = LoggerFactory.getLogger(KafkaManager.class);

    private final AdminClient adminClient;

    public KafkaManager(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    public void createTopic(String topicName) {
        NewTopic topic = new NewTopic(topicName, 1, (short) 1);
        int attempts = 3;
        for (int i = 1; i <= attempts; i++) {
            try {
                adminClient.createTopics(Collections.singleton(topic)).all().get();
                log.info("kafka topic created: {}", topicName);
                return;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TopicExistsException) {
                    log.debug("kafka topic already exists: {}", topicName);
                    return;
                }
                if (i < attempts) {
                    log.warn("kafka createTopic attempt {}/{} failed for {}: {} — retrying in 2s",
                            i, attempts, topicName, e.getCause().toString());
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted creating topic " + topicName, ie);
                    }
                } else {
                    throw new RuntimeException("failed to create topic " + topicName + " after " + attempts + " attempts", e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted creating topic " + topicName, e);
            }
        }
    }

    public void deleteTopic(String topicName) {
        try {
            adminClient.deleteTopics(Collections.singleton(topicName)).all().get();
            log.info("kafka topic deleted: {}", topicName);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                log.debug("kafka topic already gone: {}", topicName);
            } else {
                log.warn("failed to delete kafka topic {}: {}", topicName, e.getCause().toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted deleting kafka topic {}", topicName);
        }
    }

    /** Returns all topic names in the broker. Callers filter by their own naming conventions. */
    public Set<String> listTopics() {
        try {
            return adminClient.listTopics().names().get();
        } catch (ExecutionException e) {
            log.warn("failed to list kafka topics: {}", e.getCause().toString());
            return Collections.emptySet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptySet();
        }
    }
}
