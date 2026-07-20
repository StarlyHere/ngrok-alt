package com.tunnel.control.strategy;

import com.tunnel.control.PodInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Rotates assignments across pods in a stable order. Provided to show the
 * {@link LoadStrategy} seam is real (PRD-v2.md §10).
 */
@Component
public class RoundRobinStrategy implements LoadStrategy {

    private final AtomicInteger cursor = new AtomicInteger();

    @Override
    public String name() {
        return "round-robin";
    }

    @Override
    public Optional<PodInfo> select(List<PodInfo> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Sort by id for a deterministic ring regardless of Redis scan order.
        List<PodInfo> ordered = candidates.stream()
                .sorted(Comparator.comparing(PodInfo::id))
                .toList();
        int idx = Math.floorMod(cursor.getAndIncrement(), ordered.size());
        return Optional.of(ordered.get(idx));
    }
}
