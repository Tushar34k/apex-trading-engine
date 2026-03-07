package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exchange circuit breaker.
 * If 5 exchange failures occur within 1 minute, pause trading for 60 seconds.
 */
@Service
@Slf4j
public class CircuitBreakerService {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long PAUSE_DURATION_SECONDS = 60;

    private final ConcurrentLinkedQueue<Instant> failures = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(false);
    private volatile Instant openedAt = null;

    /**
     * Returns true if trading is allowed (circuit is closed).
     */
    public boolean isAllowed() {
        if (!open.get()) return true;

        // Check if pause duration has elapsed
        if (openedAt != null && Instant.now().isAfter(openedAt.plusSeconds(PAUSE_DURATION_SECONDS))) {
            if (open.compareAndSet(true, false)) {
                openedAt = null;
                failures.clear();
                log.info("[CIRCUIT BREAKER] Circuit closed — trading resumed");
            }
            return true;
        }

        return false;
    }

    /**
     * Record an exchange failure.
     */
    public void recordFailure() {
        Instant now = Instant.now();
        failures.add(now);

        // Purge old failures
        Instant cutoff = now.minus(1, ChronoUnit.MINUTES);
        failures.removeIf(t -> t.isBefore(cutoff));

        if (failures.size() >= FAILURE_THRESHOLD && open.compareAndSet(false, true)) {
            openedAt = Instant.now();
            log.error("[CIRCUIT BREAKER] OPENED — {} failures in 1 minute. Trading paused for {}s",
                failures.size(), PAUSE_DURATION_SECONDS);
        }
    }

    public boolean isOpen() { return open.get(); }
    public Instant getOpenedAt() { return openedAt; }
    public int getRecentFailureCount() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);
        failures.removeIf(t -> t.isBefore(cutoff));
        return failures.size();
    }
}
