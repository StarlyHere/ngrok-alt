package com.tunnel.control.strategy;

import com.tunnel.control.PodInfo;
import java.util.List;
import java.util.Optional;

/**
 * Pluggable pod-assignment policy (PRD-v2.md §10). This is the seam behind which
 * Least-Connections (default), RoundRobin, and Random live — assignment (path A)
 * is one of the two distinct load-balancing decisions, and making it swappable
 * is part of the distributed-systems demo.
 */
public interface LoadStrategy {

    /** Strategy id, matched against {@code control.load-strategy}. */
    String name();

    /** Pick a pod from the live candidates, or empty if none are available. */
    Optional<PodInfo> select(List<PodInfo> candidates);
}
