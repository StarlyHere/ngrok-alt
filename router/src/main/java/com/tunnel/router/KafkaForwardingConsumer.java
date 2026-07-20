package com.tunnel.router;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes all {@code notifications_<sessionId>} topics from Redpanda via pattern
 * subscription and forwards each record through the existing HTTP tunnel to the
 * developer's local process.
 *
 * <p>Pattern subscription (via {@code topicPattern} in {@code @KafkaListener}) discovers
 * new per-session topics automatically as sessions start — no manual subscription
 * management. {@code metadata.max.age.ms=10000} in application.yml ensures a new topic
 * is visible within ~10 s of creation.
 *
 * <p>Only active when {@code router.kafka-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "router.kafka-enabled", havingValue = "true")
public class KafkaForwardingConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaForwardingConsumer.class);
    private final PodResolver podResolver;
    private final HttpClient httpClient;
    private final Counter droppedCounter;
    private final String topicPrefix;

    public KafkaForwardingConsumer(PodResolver podResolver, MeterRegistry meters,
                                   @Value("${router.kafka-topic-prefix:notifications_}") String topicPrefix) {
        this.podResolver = podResolver;
        this.topicPrefix = topicPrefix;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.droppedCounter = Counter.builder("kafka_forward_dropped_total")
                .description("Kafka records dropped because session/pod was not found")
                .register(meters);
    }

    @KafkaListener(
            topicPattern = "${router.kafka-topic-pattern:notifications_.*}",
            groupId = "${spring.kafka.consumer.group-id:tunnel-router}")
    public void onRecord(ConsumerRecord<String, byte[]> record) {
        String topic = record.topic();
        if (!topic.startsWith(topicPrefix)) {
            log.warn("kafka forward: ignoring unexpected topic {}", topic);
            return;
        }
        String sessionId = topic.substring(topicPrefix.length());

        PodResolver.PodTarget pod = podResolver.targetForSession(sessionId).orElse(null);
        if (pod == null) {
            log.warn("kafka forward: no pod for session {} — dropping record topic={} offset={}",
                    sessionId, topic, record.offset());
            droppedCounter.increment();
            return;
        }

        String body = buildBody(record, sessionId);
        String url = pod.baseUrl() + "/_kafka/record";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Tunnel-Session", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                log.warn("kafka forward: pod returned {} for session {} topic={} offset={}",
                        resp.statusCode(), sessionId, topic, record.offset());
            } else {
                log.debug("kafka forward: delivered topic={} offset={} session={}",
                        topic, record.offset(), sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("kafka forward: interrupted delivering to session {}", sessionId);
        } catch (Exception e) {
            log.warn("kafka forward: failed to deliver to session {} pod={}: {}",
                    sessionId, url, e.toString());
        }
    }

    private String buildBody(ConsumerRecord<String, byte[]> record, String sessionId) {
        // The local consumer sees the logical source name, independent of QA topic namespacing.
        String baseTopic = "notifications";

        String valueEncoded = record.value() == null ? "null"
                : "\"" + Base64.getEncoder().encodeToString(record.value()) + "\"";
        String keyEncoded = record.key() == null ? "null"
                : "\"" + jsonEscape(record.key()) + "\"";

        StringBuilder headers = new StringBuilder("{");
        boolean first = true;
        for (Header h : record.headers()) {
            if (!first) headers.append(',');
            headers.append('"').append(jsonEscape(h.key())).append("\":\"")
                   .append(Base64.getEncoder().encodeToString(h.value())).append('"');
            first = false;
        }
        headers.append('}');

        return "{"
                + "\"topic\":\"" + jsonEscape(baseTopic) + "\","
                + "\"partition\":" + record.partition() + ","
                + "\"offset\":" + record.offset() + ","
                + "\"timestamp\":" + record.timestamp() + ","
                + "\"sessionId\":\"" + jsonEscape(sessionId) + "\","
                + "\"key\":" + keyEncoded + ","
                + "\"value\":" + valueEncoded + ","
                + "\"headers\":" + headers
                + "}";
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
