package com.tunnel.control;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a {@link KubernetesClient} bean only when ingress management is enabled.
 * Fabric8 auto-detects in-cluster config from the ServiceAccount token mount at
 * {@code /var/run/secrets/kubernetes.io/serviceaccount/}; locally it falls back to
 * {@code ~/.kube/config}.
 */
@Configuration
@ConditionalOnProperty(name = "control.ingress-enabled", havingValue = "true")
public class KubernetesConfig {

    @Bean
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
