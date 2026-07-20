package com.tunnel.control.strategy;

import com.tunnel.control.PodInfo;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Picks a live pod uniformly at random. The simplest baseline behind the
 * {@link LoadStrategy} seam (PRD-v2.md §10).
 */
@Component
public class RandomStrategy implements LoadStrategy {

    @Override
    public String name() {
        return "random";
    }

    @Override
    public Optional<PodInfo> select(List<PodInfo> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }
}
