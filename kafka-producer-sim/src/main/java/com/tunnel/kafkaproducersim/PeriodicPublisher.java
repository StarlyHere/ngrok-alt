package com.tunnel.kafkaproducersim;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * POC replacement for the Kafka Producer Interceptor + ThreadLocal mechanism.
 *
 * In production, the real interceptor reads a session ID from a thread-local set
 * by the servlet filter, then appends it as a topic suffix at publish time. Here,
 * SESSION_ID is injected via env var by the demo script — same effect, no servlet.
 */
@Component
public class PeriodicPublisher {

    private static final Logger log = LoggerFactory.getLogger(PeriodicPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String sessionId;
    private final String topic;
    private long sequence = 0;

    public PeriodicPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${SESSION_ID}") String sessionId) {
        this.kafkaTemplate = kafkaTemplate;
        this.sessionId = sessionId;
        this.topic = "notifications_" + sessionId;
        log.info("kafka-producer-sim started: publishing to {} every 5s", topic);
    }

    @Scheduled(fixedDelay = 5000)
    public void publish() {
        String value = String.format(
                "{\"event\":\"tts_preview\",\"sessionId\":\"%s\",\"seq\":%d,\"ts\":%d}",
                sessionId, ++sequence, Instant.now().toEpochMilli());
        kafkaTemplate.send(topic, value)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("publish failed to {}: {}", topic, ex.toString());
                    } else {
                        log.info("published seq={} to {} offset={}",
                                sequence, topic,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
