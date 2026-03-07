package com.tradeengine.service;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tracker for open positions with SL/TP/trailing stop metadata.
 * Thread-safe via ConcurrentHashMap. Positions are keyed by botId (one position per bot).
 */
@Service
@Slf4j
public class PositionTracker {

    private final ConcurrentHashMap<UUID, TrackedPosition> positions = new ConcurrentHashMap<>();

    @Data
    @Builder
    public static class TrackedPosition {
        private final UUID botId;
        private final UUID userId;
        private final String symbol;
        private final String exchange;
        private final String exchangeMode;
        private final BigDecimal entryPrice;
        private final BigDecimal quantity;
        private final String apiKey;
        private final String apiSecret;
        private final String exchangeBaseUrl;

        // Risk parameters — all optional
        private final BigDecimal stopLossPrice;
        private final BigDecimal takeProfitPrice;
        private final BigDecimal trailingStopPercent;

        // Mutable tracking fields
        private volatile BigDecimal highestPriceSeen;
        private volatile BigDecimal lowestPriceSeen;
        private final Instant openedAt;
    }

    /**
     * Register a new open position for risk monitoring.
     */
    public void registerPosition(TrackedPosition position) {
        positions.put(position.getBotId(), position);
        log.info("[POSITION_OPENED] botId={} symbol={} entry={} stopLoss={} takeProfit={} trailingStop={}%",
            position.getBotId(), position.getSymbol(), position.getEntryPrice(),
            position.getStopLossPrice(), position.getTakeProfitPrice(), position.getTrailingStopPercent());
    }

    /**
     * Remove a position when closed.
     */
    public void removePosition(UUID botId) {
        TrackedPosition removed = positions.remove(botId);
        if (removed != null) {
            log.info("[POSITION_REMOVED] botId={} symbol={}", botId, removed.getSymbol());
        }
    }

    /**
     * Get all currently tracked positions.
     */
    public Collection<TrackedPosition> getAllPositions() {
        return Collections.unmodifiableCollection(positions.values());
    }

    /**
     * Get a specific position by botId.
     */
    public Optional<TrackedPosition> getPosition(UUID botId) {
        return Optional.ofNullable(positions.get(botId));
    }

    /**
     * Update the high/low watermarks for a position.
     */
    public void updatePriceExtremes(UUID botId, BigDecimal currentPrice) {
        TrackedPosition pos = positions.get(botId);
        if (pos == null) return;

        if (pos.getHighestPriceSeen() == null || currentPrice.compareTo(pos.getHighestPriceSeen()) > 0) {
            pos.setHighestPriceSeen(currentPrice);
        }
        if (pos.getLowestPriceSeen() == null || currentPrice.compareTo(pos.getLowestPriceSeen()) < 0) {
            pos.setLowestPriceSeen(currentPrice);
        }
    }

    public int getOpenPositionCount() {
        return positions.size();
    }

    public boolean hasPosition(UUID botId) {
        return positions.containsKey(botId);
    }
}
