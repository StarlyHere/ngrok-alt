package com.tunnel.control.strategy;

import com.tunnel.control.PodInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Default assignment strategy (PRD-v2.md §10): pick the pod holding the fewest
 * connections, so new sessions spread evenly across the fleet.
 */
@Component
public class LeastConnectionsStrategy implements LoadStrategy {

    @Override
    public String name() {
        return "least-connections";
    }

    @Override
    public Optional<PodInfo> select(List<PodInfo> candidates) {
        return candidates.stream().min(Comparator.comparingInt(PodInfo::conns));
    }
}
