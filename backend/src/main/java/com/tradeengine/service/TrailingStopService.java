package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production-grade Trailing Stop Service
 *
 * FIXES INCLUDED:
 * - No instant trigger on first tick
 * - Minimum movement filter (noise protection)
 * - Breakeven enforcement
 * - Duplicate trigger prevention
 * - Safe reset handling
 * - Thread-safe
 *
 * Supports LONG (default) and extensible for SHORT
 */
@Service
@Slf4j
public class TrailingStopService {

    // botId -> highest price since entry (LONG)
    private final Map<UUID, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();

    // botId -> breakeven stop (entry price after TP1)
    private final Map<UUID, BigDecimal> breakevenStops = new ConcurrentHashMap<>();

    // botId -> exit already triggered (prevents duplicate SELL)
    private final Map<UUID, Boolean> exitTriggered = new ConcurrentHashMap<>();

    /**
     * MAIN METHOD — Check if trailing stop is triggered (LONG)
     */
    public boolean checkTrailingStop(
            UUID botId,
            BigDecimal currentPrice,
            BigDecimal entryPrice,
            double trailingStopPercent
    ) {

        // 0. Prevent duplicate trigger
        if (exitTriggered.getOrDefault(botId, false)) {
            return false;
        }

        // 1. Initialize High Water Mark safely
        BigDecimal hwm = highWaterMarks.compute(botId, (id, existing) -> {
            if (existing == null) {
                return entryPrice; // 🔥 FIX: start from entry, not first tick
            }
            return existing.max(currentPrice);
        });

        // 2. Minimum movement filter (avoid noise trigger)
        BigDecimal minMove = entryPrice.multiply(BigDecimal.valueOf(0.002)); // 0.2%

        if (currentPrice.subtract(entryPrice).abs().compareTo(minMove) < 0) {
            return false;
        }

        // 3. Calculate trailing stop level
        BigDecimal trailingLevel = hwm.multiply(
                BigDecimal.ONE.subtract(BigDecimal.valueOf(trailingStopPercent / 100))
        ).setScale(8, RoundingMode.HALF_UP);

        // 4. Apply breakeven protection (never go below entry)
        BigDecimal breakeven = breakevenStops.get(botId);
        if (breakeven != null) {
            trailingLevel = trailingLevel.max(breakeven);
        }

        // 5. Final trigger check
        if (currentPrice.compareTo(trailingLevel) <= 0) {

            log.warn(
                    "[TRAILING_TRIGGER] botId={} price={} trailing={} hwm={} entry={}",
                    botId, currentPrice, trailingLevel, hwm, entryPrice
            );

            exitTriggered.put(botId, true); // prevent duplicate triggers
            return true;
        }

        return false;
    }

    /**
     * OPTIONAL: SHORT POSITION SUPPORT (if using futures)
     */
    public boolean checkTrailingStopShort(
            UUID botId,
            BigDecimal currentPrice,
            BigDecimal entryPrice,
            double trailingStopPercent
    ) {

        if (exitTriggered.getOrDefault(botId, false)) {
            return false;
        }

        // LOW WATER MARK for SHORT
        BigDecimal lwm = highWaterMarks.compute(botId, (id, existing) -> {
            if (existing == null) {
                return entryPrice;
            }
            return existing.min(currentPrice);
        });

        BigDecimal minMove = entryPrice.multiply(BigDecimal.valueOf(0.002));

        if (currentPrice.subtract(entryPrice).abs().compareTo(minMove) < 0) {
            return false;
        }

        BigDecimal trailingLevel = lwm.multiply(
                BigDecimal.ONE.add(BigDecimal.valueOf(trailingStopPercent / 100))
        ).setScale(8, RoundingMode.HALF_UP);

        BigDecimal breakeven = breakevenStops.get(botId);
        if (breakeven != null) {
            trailingLevel = trailingLevel.min(breakeven);
        }

        if (currentPrice.compareTo(trailingLevel) >= 0) {

            log.warn(
                    "[TRAILING_TRIGGER_SHORT] botId={} price={} trailing={} lwm={} entry={}",
                    botId, currentPrice, trailingLevel, lwm, entryPrice
            );

            exitTriggered.put(botId, true);
            return true;
        }

        return false;
    }

    /**
     * Set breakeven stop (after TP1)
     */
    public void setBreakevenStop(UUID botId, BigDecimal entryPrice) {
        breakevenStops.put(botId, entryPrice);

        // Ensure HWM is at least entry
        highWaterMarks.compute(botId, (id, existing) -> {
            if (existing == null) return entryPrice;
            return existing.max(entryPrice);
        });

        log.info("[BREAKEVEN_SL] botId={} stop moved to entry={}", botId, entryPrice);
    }

    /**
     * Reset AFTER position is fully CLOSED
     */
    public void resetBot(UUID botId) {
        highWaterMarks.remove(botId);
        breakevenStops.remove(botId);
        exitTriggered.remove(botId);

        log.info("[TRAILING_RESET] botId={}", botId);
    }

    /**
     * Getters (for debugging / monitoring)
     */
    public BigDecimal getHighWaterMark(UUID botId) {
        return highWaterMarks.get(botId);
    }

    public BigDecimal getBreakevenStop(UUID botId) {
        return breakevenStops.get(botId);
    }

    public BigDecimal getTrailingStopLevel(UUID botId, double trailingStopPercent) {
        BigDecimal hwm = highWaterMarks.get(botId);
        if (hwm == null) return null;

        BigDecimal trailing = hwm.multiply(
                BigDecimal.ONE.subtract(BigDecimal.valueOf(trailingStopPercent / 100))
        );

        BigDecimal breakeven = breakevenStops.get(botId);
        if (breakeven != null) {
            trailing = trailing.max(breakeven);
        }

        return trailing.setScale(8, RoundingMode.HALF_UP);
    }
}