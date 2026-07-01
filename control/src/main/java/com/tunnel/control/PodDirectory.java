package com.tunnel.control;

import com.tunnel.protocol.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the set of currently-live pods from Redis (PRD-v2.md §5, §10). Pods
 * self-register a {@code pod:{id}} hash with a TTL they refresh; an expired pod
 * simply drops out of the scan, so dead pods leave the assignment pool
 * automatically (pod failure detection for free).
 */
@Component
public class PodDirectory {

    private static final Logger log = LoggerFactory.getLogger(PodDirectory.class);

    private final StringRedisTemplate redis;

    public PodDirectory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** All pods whose liveness key is still present. */
    public List<PodInfo> livePods() {
        Set<String> keys = redis.keys(RedisKeys.POD_SCAN_PATTERN);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<PodInfo> pods = new ArrayList<>(keys.size());
        for (String key : keys) {
            Map<Object, Object> h = redis.opsForHash().entries(key);
            String host = str(h.get(RedisKeys.F_HOST));
            String port = str(h.get(RedisKeys.F_PORT));
            if (host == null || port == null) {
                continue; // expired between scan and read
            }
            String id = key.substring("pod:".length());
            int conns = parseInt(str(h.get(RedisKeys.F_CONNS)));
            boolean secure = Boolean.parseBoolean(str(h.get(RedisKeys.F_SECURE)));
            pods.add(new PodInfo(id, host, Integer.parseInt(port), conns, secure));
        }
        log.debug("live pods: {}", pods);
        return pods;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
