package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-trade position risk validator.
 * Rejects orders that would exceed configured max exposure limits.
 * Includes liquidation safety and leverage checks.
 */
@Service
@Slf4j
public class PositionRiskValidator {

    @Value("${risk.maxPositionPercent:10}")
    private double maxPositionPercent;

    @Value("${risk.maxSingleTradePercent:2}")
    private double maxSingleTradePercent;

    @Value("${risk.maxLeverage:5}")
    private int maxLeverage;

    @Value("${risk.liquidationSafetyPercent:5}")
    private double liquidationSafetyPercent;

    // bot+symbol → 2-second cooldown lock
    private final ConcurrentHashMap<String, Long> orderLocks = new ConcurrentHashMap<>();
    private static final long ORDER_LOCK_MS = 2000;

    /**
     * Validate that the order does not exceed position size limits.
     *
     * @return null if valid, or rejection reason string
     */
    public String validatePositionSize(String exchange, String symbol, BigDecimal quantity,
                                        BigDecimal price, BigDecimal accountBalance) {
        if (accountBalance == null || accountBalance.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[RISK_VALIDATOR] No account balance available — skipping position size check");
            return null;
        }

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // MARKET order without price — can't validate notional
        }

        BigDecimal orderNotional = quantity.multiply(price);
        BigDecimal maxSingleTrade = accountBalance.multiply(BigDecimal.valueOf(maxSingleTradePercent))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        if (orderNotional.compareTo(maxSingleTrade) > 0) {
            String msg = String.format(
                "Order notional %s exceeds maxSingleTradePercent %.1f%% of balance %s (max=%s) for %s:%s",
                orderNotional.toPlainString(), maxSingleTradePercent,
                accountBalance.toPlainString(), maxSingleTrade.toPlainString(),
                exchange, symbol);
            log.warn("[ORDER_REJECTED] reason=MAX_SINGLE_TRADE {}", msg);
            return msg;
        }

        BigDecimal maxPosition = accountBalance.multiply(BigDecimal.valueOf(maxPositionPercent))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        if (orderNotional.compareTo(maxPosition) > 0) {
            String msg = String.format(
                "Order notional %s exceeds maxPositionPercent %.1f%% of balance %s (max=%s) for %s:%s",
                orderNotional.toPlainString(), maxPositionPercent,
                accountBalance.toPlainString(), maxPosition.toPlainString(),
                exchange, symbol);
            log.warn("[ORDER_REJECTED] reason=MAX_POSITION_EXCEEDED {}", msg);
            return msg;
        }

        return null;
    }

    /**
     * Validate leverage does not exceed maximum allowed.
     *
     * @return null if valid, or rejection reason string
     */
    public String validateLeverage(int requestedLeverage, String exchange, String symbol) {
        if (requestedLeverage > maxLeverage) {
            String msg = String.format(
                "Requested leverage %dx exceeds max allowed %dx for %s:%s",
                requestedLeverage, maxLeverage, exchange, symbol);
            log.warn("[ORDER_REJECTED] reason=MAX_LEVERAGE_EXCEEDED {}", msg);
            return msg;
        }
        return null;
    }

    /**
     * Validate that the estimated liquidation price is not within the safety threshold of entry.
     * For LONG: liquidation price must be more than liquidationSafetyPercent% below entry.
     * For SHORT: liquidation price must be more than liquidationSafetyPercent% above entry.
     *
     * @param side       BUY (LONG) or SELL (SHORT)
     * @param entryPrice the expected entry price
     * @param leverage   the leverage being used
     * @return null if safe, or rejection reason string
     */
    public String validateLiquidationSafety(String side, BigDecimal entryPrice,
                                             int leverage, String exchange, String symbol) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0 || leverage <= 0) {
            return null;
        }

        // Estimated liquidation distance = entryPrice / leverage
        // For LONG: liqPrice ≈ entryPrice * (1 - 1/leverage)
        // For SHORT: liqPrice ≈ entryPrice * (1 + 1/leverage)
        BigDecimal liqDistance = entryPrice.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
        BigDecimal liqDistancePercent = liqDistance.divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (liqDistancePercent.doubleValue() < liquidationSafetyPercent) {
            BigDecimal estimatedLiqPrice;
            if ("BUY".equalsIgnoreCase(side)) {
                estimatedLiqPrice = entryPrice.subtract(liqDistance);
            } else {
                estimatedLiqPrice = entryPrice.add(liqDistance);
            }

            String msg = String.format(
                "Liquidation price %s is within %.1f%% of entry %s (distance=%.2f%%, leverage=%dx) for %s:%s",
                estimatedLiqPrice.toPlainString(), liquidationSafetyPercent,
                entryPrice.toPlainString(), liqDistancePercent.doubleValue(),
                leverage, exchange, symbol);
            log.warn("[ORDER_REJECTED] reason=LIQUIDATION_TOO_CLOSE {}", msg);
            return msg;
        }

        return null;
    }

    /**
     * Check and acquire a short-term order lock for bot+symbol (2s cooldown).
     *
     * @return true if lock acquired (order may proceed), false if blocked
     */
    public boolean acquireOrderLock(UUID botId, String symbol) {
        String key = botId.toString() + ":" + symbol;
        long now = System.currentTimeMillis();

        Long lastOrder = orderLocks.get(key);
        if (lastOrder != null && (now - lastOrder) < ORDER_LOCK_MS) {
            log.warn("[ORDER_BLOCKED] reason=DUPLICATE_ORDER botId={} symbol={} cooldownRemaining={}ms",
                botId, symbol, ORDER_LOCK_MS - (now - lastOrder));
            return false;
        }

        orderLocks.put(key, now);
        return true;
    }

    /**
     * Cleanup expired locks (call periodically).
     */
    public void cleanupLocks() {
        long now = System.currentTimeMillis();
        orderLocks.entrySet().removeIf(e -> (now - e.getValue()) > ORDER_LOCK_MS * 2);
    }

    // For testing
    public void setMaxPositionPercent(double pct) { this.maxPositionPercent = pct; }
    public void setMaxSingleTradePercent(double pct) { this.maxSingleTradePercent = pct; }
    public void setMaxLeverage(int lev) { this.maxLeverage = lev; }
    public void setLiquidationSafetyPercent(double pct) { this.liquidationSafetyPercent = pct; }
}
