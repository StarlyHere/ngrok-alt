package com.tunnel.control;

import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.dto.SessionStatus;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Periodic cleanup for orphaned tunnel ingresses. Runs every
 * {@code control.ingress-reconcile-ms} (default 60 s) and deletes any ingress
 * labelled {@code tunnel-managed=true} whose session is no longer ACTIVE in Redis
 * (expired TTL or explicit delete).
 *
 * <p>Only active when {@code control.ingress-enabled=true}. Each ingress is handled
 * independently — one failure never stops the loop.
 */
@Component
@ConditionalOnProperty(name = "control.ingress-enabled", havingValue = "true")
public class IngressReconciler {

    private static final Logger log = LoggerFactory.getLogger(IngressReconciler.class);

    private final io.fabric8.kubernetes.client.KubernetesClient k8s;
    private final StringRedisTemplate redis;
    private final IngressManager ingressManager;
    private final ControlProperties props;

    public IngressReconciler(io.fabric8.kubernetes.client.KubernetesClient k8s,
                             StringRedisTemplate redis,
                             IngressManager ingressManager,
                             ControlProperties props) {
        this.k8s = k8s;
        this.redis = redis;
        this.ingressManager = ingressManager;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${control.ingress-reconcile-ms:60000}")
    public void reconcile() {
        List<Ingress> ingresses;
        try {
            ingresses = k8s.network().v1().ingresses()
                    .inNamespace(props.ingressNamespace())
                    .withLabel(IngressManager.LABEL_MANAGED, "true")
                    .list()
                    .getItems();
        } catch (RuntimeException e) {
            log.warn("reconciler could not list ingresses: {}", e.toString());
            return;
        }

        if (ingresses.isEmpty()) {
            return;
        }
        log.debug("reconciler checking {} tunnel-managed ingress(es)", ingresses.size());

        for (Ingress ingress : ingresses) {
            try {
                reconcileOne(ingress);
            } catch (RuntimeException e) {
                String name = ingress.getMetadata() != null ? ingress.getMetadata().getName() : "?";
                log.warn("reconciler error processing ingress {}: {}", name, e.toString());
            }
        }
    }

    private void reconcileOne(Ingress ingress) {
        String sessionId = ingress.getMetadata().getLabels().get(IngressManager.LABEL_SESSION);
        if (sessionId == null || sessionId.isBlank()) {
            // Ingress is missing the session label — delete it to be safe.
            log.warn("reconciler found tunnel-managed ingress {} with no session label — deleting",
                    ingress.getMetadata().getName());
            deleteByName(ingress);
            return;
        }

        String statusStr = null;
        try {
            Object val = redis.opsForHash().get(RedisKeys.session(sessionId), RedisKeys.F_STATUS);
            statusStr = val == null ? null : val.toString();
        } catch (RuntimeException e) {
            // Redis unavailable: skip this ingress this cycle, try again next tick.
            log.warn("reconciler skipping ingress {} — redis error: {}", ingress.getMetadata().getName(), e.toString());
            return;
        }

        // Keep ingress for PENDING (client connecting) and ACTIVE (connected) sessions.
        // Only delete when the session is gone from Redis or explicitly closed/expired.
        boolean shouldDelete = statusStr == null
                || SessionStatus.EXPIRED.name().equals(statusStr)
                || SessionStatus.CLOSED.name().equals(statusStr);
        if (shouldDelete) {
            log.info("reconciler removing orphaned ingress {} (session {} status={})",
                    ingress.getMetadata().getName(), sessionId, statusStr == null ? "missing" : statusStr);
            ingressManager.deleteIngress(sessionId);
        }
    }

    private void deleteByName(Ingress ingress) {
        try {
            k8s.network().v1().ingresses()
                    .inNamespace(props.ingressNamespace())
                    .withName(ingress.getMetadata().getName())
                    .delete();
        } catch (RuntimeException e) {
            log.warn("reconciler failed to delete ingress {}: {}", ingress.getMetadata().getName(), e.toString());
        }
    }
}
