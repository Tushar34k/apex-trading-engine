package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * RejectionMetricsService — central observability for trade pipeline outcomes.
 *
 * Stages:
 *   - "SCORER"     → TradeQualityScorer rejection
 *   - "RISK"       → RiskManagementService rejection (validateBuy / validateRiskReward)
 *   - "EXECUTION"  → trade ALLOWED through all gates (positive telemetry)
 *
 * Design constraints:
 *   - In-memory ring buffer per botId (last N events) — no DB schema change required
 *   - Thread-safe via ConcurrentHashMap + ConcurrentLinkedDeque
 *   - O(1) record(); aggregation done lazily at query time
 *   - Bounded memory: MAX_EVENTS_PER_BOT cap with FIFO eviction
 */
@Service
@Slf4j
public class RejectionMetricsService {

    private static final int MAX_EVENTS_PER_BOT = 5000;

    public record Event(Instant ts, String stage, String reason, Integer score) {}

    private final Map<String, ConcurrentLinkedDeque<Event>> events = new ConcurrentHashMap<>();

    /** Record a single pipeline outcome. botId may be null for global events. */
    public void record(String botId, String stage, String reason, Integer score) {
        if (botId == null) botId = "GLOBAL";
        ConcurrentLinkedDeque<Event> q = events.computeIfAbsent(botId, k -> new ConcurrentLinkedDeque<>());
        q.add(new Event(Instant.now(), stage, reason, score));
        // Bounded eviction
        while (q.size() > MAX_EVENTS_PER_BOT) {
            q.pollFirst();
        }
        log.debug("[METRICS] botId={} stage={} reason={} score={}", botId, stage, reason, score);
    }

    /**
     * Aggregate rejection counts grouped as "STAGE:REASON" within a time window.
     * Includes "EXECUTION:TRADE_ALLOWED" so the caller can compute pass-through ratio.
     */
    public Map<String, Long> breakdown(String botId, Duration window) {
        ConcurrentLinkedDeque<Event> q = events.get(botId);
        if (q == null) return Collections.emptyMap();
        Instant cutoff = Instant.now().minus(window);
        Map<String, Long> out = new LinkedHashMap<>();
        for (Event e : q) {
            if (e.ts.isBefore(cutoff)) continue;
            String key = e.stage + ":" + e.reason;
            out.merge(key, 1L, Long::sum);
        }
        return out;
    }

    /** Recent raw events for deeper inspection. */
    public List<Event> recent(String botId, int limit) {
        ConcurrentLinkedDeque<Event> q = events.get(botId);
        if (q == null) return Collections.emptyList();
        List<Event> all = new ArrayList<>(q);
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    /** Count of allowed executions in window. */
    public long executions(String botId, Duration window) {
        return breakdown(botId, window).getOrDefault("EXECUTION:TRADE_ALLOWED", 0L);
    }
}
