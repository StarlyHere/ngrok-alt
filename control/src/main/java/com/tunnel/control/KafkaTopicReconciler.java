package com.tunnel.control;

import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.dto.SessionStatus;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backup crash-recovery reconciler for per-session Kafka topics. Runs every 60 s
 * and deletes any {@code notifications_<sessionId>} topic whose session is gone,
 * EXPIRED, or CLOSED. Primary deletion is
 * {@link SessionCoordinator#deleteSession(String, String)}.
 * This reconciler only fires when the client disconnects without a clean shutdown.
 *
 * <p>Only active when {@code control.kafka-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "control.kafka-enabled", havingValue = "true")
public class KafkaTopicReconciler {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicReconciler.class);
    private final KafkaManager kafkaManager;
    private final StringRedisTemplate redis;
    private final String topicPrefix;

    public KafkaTopicReconciler(KafkaManager kafkaManager, StringRedisTemplate redis,
                                @Value("${control.kafka-topic-prefix:notifications_}") String topicPrefix) {
        this.kafkaManager = kafkaManager;
        this.redis = redis;
        this.topicPrefix = topicPrefix;
    }

    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        Set<String> topics;
        try {
            topics = kafkaManager.listTopics();
        } catch (RuntimeException e) {
            log.warn("topic reconciler could not list topics: {}", e.toString());
            return;
        }

        for (String topic : topics) {
            if (!topic.startsWith(topicPrefix)) {
                continue;
            }
            try {
                reconcileOne(topic);
            } catch (RuntimeException e) {
                log.warn("topic reconciler error processing {}: {}", topic, e.toString());
            }
        }
    }

    private void reconcileOne(String topic) {
        String sessionId = topic.substring(topicPrefix.length());
        if (sessionId.isBlank()) {
            return;
        }

        String statusStr = null;
        try {
            Object val = redis.opsForHash().get(RedisKeys.session(sessionId), RedisKeys.F_STATUS);
            statusStr = val == null ? null : val.toString();
        } catch (RuntimeException e) {
            log.warn("topic reconciler skipping {} — redis error: {}", topic, e.toString());
            return;
        }

        boolean shouldDelete = statusStr == null
                || SessionStatus.EXPIRED.name().equals(statusStr)
                || SessionStatus.CLOSED.name().equals(statusStr);
        if (shouldDelete) {
            log.info("topic reconciler removing orphaned topic {} (session status={})",
                    topic, statusStr == null ? "missing" : statusStr);
            kafkaManager.deleteTopic(topic);
        }
    }
}
