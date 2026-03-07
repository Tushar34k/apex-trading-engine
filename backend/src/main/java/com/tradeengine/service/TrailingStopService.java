package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trailing stop-loss tracker.
 * Tracks the highest price since entry for each bot and calculates a dynamic stop level.
 *
 * Example: entry=100, trailingStopPercent=2%, price goes 100→115
 *   highWaterMark = 115
 *   trailingStop = 115 * (1 - 0.02) = 112.7
 *   If price drops to 112.7 → trigger SELL
 */
@Service
@Slf4j
public class TrailingStopService {

    // botId -> highest price since position opened
    private final Map<UUID, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();

    /**
     * Update the high water mark and check if trailing stop is triggered.
     * @return true if price hit trailing stop → should SELL
     */
    public boolean checkTrailingStop(UUID botId, BigDecimal currentPrice, BigDecimal entryPrice,
                                      double trailingStopPercent) {
        // Update high water mark
        BigDecimal hwm = highWaterMarks.compute(botId, (id, existing) -> {
            if (existing == null) return currentPrice.max(entryPrice);
            return existing.max(currentPrice);
        });

        // Calculate trailing stop level
        BigDecimal trailingLevel = hwm.multiply(
            BigDecimal.ONE.subtract(BigDecimal.valueOf(trailingStopPercent / 100))
        ).setScale(8, RoundingMode.HALF_UP);

        if (currentPrice.compareTo(trailingLevel) <= 0) {
            log.warn("Bot {} trailing stop triggered: price={} <= trailing={} (hwm={})",
                botId, currentPrice, trailingLevel, hwm);
            return true;
        }

        return false;
    }

    /**
     * Reset tracking when position is closed.
     */
    public void resetBot(UUID botId) {
        highWaterMarks.remove(botId);
    }

    public BigDecimal getHighWaterMark(UUID botId) {
        return highWaterMarks.get(botId);
    }

    public BigDecimal getTrailingStopLevel(UUID botId, double trailingStopPercent) {
        BigDecimal hwm = highWaterMarks.get(botId);
        if (hwm == null) return null;
        return hwm.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(trailingStopPercent / 100)))
            .setScale(8, RoundingMode.HALF_UP);
    }
}
