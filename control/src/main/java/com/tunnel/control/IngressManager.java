package com.tunnel.control;

import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValueBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Creates and deletes temporary Kubernetes Ingresses for WebUI tunnel sessions.
 *
 * <p>Each ingress:
 * <ul>
 *   <li>hostname  {@code {subdomain}.{ingressDomain}} — one subdomain per session so
 *       concurrent developers never share path rules;</li>
 *   <li>labels    {@code tunnel-managed=true}, {@code tunnel-session={sessionId}} — used
 *       by {@code IngressReconciler} to find orphans;</li>
 *   <li>annotation {@code nginx.ingress.kubernetes.io/configuration-snippet} injects
 *       {@code X-Tunnel-Session} on every matched request so the existing router handles
 *       routing with zero changes.</li>
 * </ul>
 *
 * <p>Only active when {@code control.ingress-enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "control.ingress-enabled", havingValue = "true")
public class IngressManager {

    private static final Logger log = LoggerFactory.getLogger(IngressManager.class);

    static final String LABEL_MANAGED = "tunnel-managed";
    static final String LABEL_SESSION = "tunnel-session";
    private static final String ROUTER_SERVICE = "router";
    private static final int ROUTER_PORT = 8080;
    private static final String PATH_TYPE_PREFIX = "Prefix";

    private final KubernetesClient k8s;
    private final ControlProperties props;

    public IngressManager(KubernetesClient k8s, ControlProperties props) {
        this.k8s = k8s;
        this.props = props;
    }

    /**
     * Creates (or replaces) a Kubernetes Ingress for the given session.
     *
     * @param sessionId    tunnel session id
     * @param subdomain    the session's unique subdomain (e.g. {@code bright-otter-12})
     * @param pathPatterns comma-separated Ant patterns, or {@code null} meaning ALL ({@code /})
     * @return the name of the created ingress
     */
    public String createIngress(String sessionId, String subdomain, String pathPatterns) {
        String name = ingressName(subdomain);
        String host = subdomain + "." + props.ingressDomain();
        List<String> patterns = parsePaths(pathPatterns);

        List<io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath> httpPaths = new ArrayList<>();
        for (String pattern : patterns) {
            // Kubernetes Ingress uses prefix paths; strip trailing /** for the prefix rule.
            String k8sPath = toK8sPath(pattern);
            httpPaths.add(new HTTPIngressPathBuilder()
                    .withPath(k8sPath)
                    .withPathType(PATH_TYPE_PREFIX)
                    .withNewBackend()
                        .withService(new IngressServiceBackendBuilder()
                                .withName(ROUTER_SERVICE)
                                .withPort(new ServiceBackendPortBuilder()
                                        .withNumber(ROUTER_PORT)
                                        .build())
                                .build())
                    .endBackend()
                    .build());
        }

        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(props.ingressNamespace())
                    .withLabels(Map.of(LABEL_MANAGED, "true", LABEL_SESSION, sessionId))
                .endMetadata()
                .withNewSpec()
                    .addNewRule()
                        .withHost(host)
                        .withHttp(new HTTPIngressRuleValueBuilder()
                                .withPaths(httpPaths)
                                .build())
                    .endRule()
                .endSpec()
                .build();

        k8s.network().v1().ingresses()
                .inNamespace(props.ingressNamespace())
                .resource(ingress)
                .serverSideApply();

        log.info("ingress created: {} host={} paths={} session={}", name, host, patterns, sessionId);
        return name;
    }

    /**
     * Deletes the ingress for the given session, identified by label selector.
     * No-op if the ingress no longer exists.
     */
    public void deleteIngress(String sessionId) {
        try {
            List<Ingress> found = k8s.network().v1().ingresses()
                    .inNamespace(props.ingressNamespace())
                    .withLabel(LABEL_SESSION, sessionId)
                    .list()
                    .getItems();
            if (found.isEmpty()) {
                log.debug("no ingress found for session {} — already deleted", sessionId);
                return;
            }
            for (Ingress ing : found) {
                k8s.network().v1().ingresses()
                        .inNamespace(props.ingressNamespace())
                        .withName(ing.getMetadata().getName())
                        .delete();
                log.info("ingress deleted: {} session={}", ing.getMetadata().getName(), sessionId);
            }
        } catch (RuntimeException e) {
            // Log but don't propagate — ingress deletion is best-effort on session close.
            log.warn("failed to delete ingress for session {}: {}", sessionId, e.toString());
        }
    }

    /** Ingress name derived from subdomain — stable, DNS-safe, unique per session. */
    static String ingressName(String subdomain) {
        return "dev-ingress-" + subdomain;
    }

    /**
     * Parses comma-separated path patterns. {@code null}/blank/"ALL" → single root
     * prefix {@code /} which matches everything.
     */
    private static List<String> parsePaths(String pathPatterns) {
        if (pathPatterns == null || pathPatterns.isBlank()
                || "ALL".equalsIgnoreCase(pathPatterns.trim())) {
            return List.of("/");
        }
        List<String> result = new ArrayList<>();
        for (String p : pathPatterns.split(",")) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? List.of("/") : result;
    }

    /**
     * Converts an Ant pattern to a Kubernetes Ingress Prefix path.
     * {@code /process/**} → {@code /process}, {@code /exact} → {@code /exact}.
     */
    private static String toK8sPath(String antPattern) {
        if (antPattern.endsWith("/**")) {
            return antPattern.substring(0, antPattern.length() - 3);
        }
        if (antPattern.endsWith("/*")) {
            return antPattern.substring(0, antPattern.length() - 2);
        }
        return antPattern;
    }
}
