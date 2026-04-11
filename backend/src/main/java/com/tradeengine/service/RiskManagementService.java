package com.tradeengine.service;

import com.tradeengine.model.TradePosition;
import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.OrderRepository;
import com.tradeengine.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Central risk management layer consulted before every trade.
 *
 * Rules enforced:
 * - maxPositions: max open positions per bot (default 1)
 * - maxTradePercent: max trade size as % of balance (default 10%)
 * - maxDailyLossPercent: max daily loss as % of balance (default 3%)
 * - maxTradesPerDay: max number of trades per day (default 10)
 * - maxTradesPerHour: max trades per rolling hour (default 3)
 * - maxPositionSize: max position size in USDT (default 500)
 * - minRiskReward: minimum risk:reward ratio (default 2.0)
 * - minSlDistancePercent: minimum SL distance % (default 0.3%)
 * - maxSlDistancePercent: maximum SL distance % (default 5.0%)
 * - postLossCooldownSec: cooldown after a losing trade (default 300s = 5min)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private final PositionRepository positionRepo;
    private final OrderRepository orderRepo;

    public record RiskCheck(boolean allowed, String reason) {}

    /**
     * Validate a BUY order against all risk rules.
     */
    public RiskCheck validateBuy(TradingBot bot, BigDecimal usdtBalance, Map<String, Object> params) {
        // 1. Max open positions (default 1 — conservative)
        int maxPositions = getInt(params, "maxPositions", 1);
        List<TradePosition> openPositions = positionRepo.findByBotIdAndStatus(bot.getId(), "OPEN");
        if (openPositions.size() >= maxPositions) {
            log.warn("Bot {} RISK: Max open positions reached ({}/{})", bot.getId(), openPositions.size(), maxPositions);
            return new RiskCheck(false, "Max open positions reached (" + maxPositions + ")");
        }

        // 2. Max trade size percent (default 10% — never risk entire balance)
        double maxTradePercent = getDouble(params, "maxTradePercent", 10.0);
        if (bot.getTradeSizePercent().doubleValue() > maxTradePercent) {
            log.warn("Bot {} RISK: Trade size {}% exceeds max {}%", bot.getId(), bot.getTradeSizePercent(), maxTradePercent);
            return new RiskCheck(false, "Trade size " + bot.getTradeSizePercent() + "% exceeds max " + maxTradePercent + "%");
        }

        // 3. Minimum balance ($5 minimum to prevent dust trades)
        BigDecimal minBalance = BigDecimal.valueOf(getDouble(params, "minBalance", 5.0));
        if (usdtBalance.compareTo(minBalance) <= 0) {
            log.warn("Bot {} RISK: Insufficient balance: {} (min: {})", bot.getId(), usdtBalance, minBalance);
            return new RiskCheck(false, "Insufficient USDT balance: " + usdtBalance + " (min $" + minBalance + ")");
        }

        // 4. Max daily loss (default 3%)
        double maxDailyLossPercent = getDouble(params, "maxDailyLossPercent", 3.0);
        if (maxDailyLossPercent > 0) {
            BigDecimal dailyPnl = calculateDailyPnl(bot);
            BigDecimal maxLoss = usdtBalance.multiply(BigDecimal.valueOf(maxDailyLossPercent / 100)).negate();
            if (dailyPnl.compareTo(maxLoss) <= 0) {
                log.warn("Bot {} RISK: Daily loss limit reached: {} (max: {})", bot.getId(), dailyPnl, maxLoss);
                return new RiskCheck(false, "Daily loss limit reached: $" + dailyPnl.setScale(2, RoundingMode.HALF_UP) + " (max " + maxDailyLossPercent + "%)");
            }
        }

        // 5. Max trades per day (default 10)
        int maxTradesPerDay = getInt(params, "maxTradesPerDay", 10);
        if (maxTradesPerDay > 0) {
            int todayTrades = countTodayTrades(bot);
            if (todayTrades >= maxTradesPerDay) {
                log.warn("Bot {} RISK: Max trades per day reached ({}/{})", bot.getId(), todayTrades, maxTradesPerDay);
                return new RiskCheck(false, "Max trades per day reached (" + maxTradesPerDay + ")");
            }
        }

        // 6. Max trades per hour (default 3 — prevents overtrading)
        int maxTradesPerHour = getInt(params, "maxTradesPerHour", 3);
        if (maxTradesPerHour > 0) {
            int hourlyTrades = countHourlyTrades(bot);
            if (hourlyTrades >= maxTradesPerHour) {
                log.warn("Bot {} RISK: Max trades per hour reached ({}/{})", bot.getId(), hourlyTrades, maxTradesPerHour);
                return new RiskCheck(false, "Max trades per hour reached (" + maxTradesPerHour + ")");
            }
        }

        // 7. Max position size in USDT (default $500 — prevents oversized positions)
        double maxPositionSize = getDouble(params, "maxPositionSize", 500.0);
        if (maxPositionSize > 0) {
            BigDecimal allocAmount = usdtBalance.multiply(bot.getTradeSizePercent())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            if (allocAmount.doubleValue() > maxPositionSize) {
                log.warn("Bot {} RISK: Position size ${} exceeds max ${}", bot.getId(), allocAmount, maxPositionSize);
                return new RiskCheck(false, "Position size $" + allocAmount.setScale(2, RoundingMode.HALF_UP) + " exceeds max $" + maxPositionSize);
            }
        }

        // 8. Consecutive losses auto-stop (default 3)
        int maxConsecutiveLosses = getInt(params, "maxConsecutiveLosses", 3);
        if (maxConsecutiveLosses > 0) {
            int consecutiveLosses = countConsecutiveLosses(bot);
            if (consecutiveLosses >= maxConsecutiveLosses) {
                log.warn("Bot {} RISK: {} consecutive losses (max {})", bot.getId(), consecutiveLosses, maxConsecutiveLosses);
                return new RiskCheck(false, consecutiveLosses + " consecutive losses — auto-paused (max " + maxConsecutiveLosses + ")");
            }
        }

        // 9. Post-loss cooldown (default 300s = 5 minutes)
        int postLossCooldownSec = getInt(params, "postLossCooldownSec", 300);
        if (postLossCooldownSec > 0) {
            Instant lastLossTime = getLastLossTime(bot);
            if (lastLossTime != null) {
                long secsSinceLoss = java.time.Duration.between(lastLossTime, Instant.now()).toSeconds();
                if (secsSinceLoss < postLossCooldownSec) {
                    log.warn("Bot {} RISK: Post-loss cooldown active ({}s remaining)", bot.getId(), postLossCooldownSec - secsSinceLoss);
                    return new RiskCheck(false, "Post-loss cooldown: " + (postLossCooldownSec - secsSinceLoss) + "s remaining");
                }
            }
        }

        log.info("Bot {} RISK: All checks passed", bot.getId());
        return new RiskCheck(true, "OK");
    }

    /**
     * Validate risk:reward ratio and SL distance before allowing a trade.
     * Called separately with signal data.
     */
    public RiskCheck validateRiskReward(TradingBot bot, double entryPrice, Double stopLoss, Double takeProfit, Map<String, Object> params) {
        // CRITICAL: Reject trades without a stop loss
        if (stopLoss == null || stopLoss <= 0) {
            log.warn("Bot {} RISK: No stop loss defined — trade REJECTED", bot.getId());
            return new RiskCheck(false, "No stop loss defined — every trade MUST have a stop loss");
        }

        double slDistance = Math.abs(entryPrice - stopLoss);
        double slPercent = (slDistance / entryPrice) * 100;

        // Min SL distance (default 0.3% — prevents getting stopped out by noise)
        double minSlPercent = getDouble(params, "minSlDistancePercent", 0.3);
        if (slPercent < minSlPercent) {
            double userSlInput = getDouble(params, "__userSlPercent", -1);
            double atrSlInput = getDouble(params, "__atrSlPercent", -1);
            String diagnostic = String.format(
                "SL too tight: %.2f%% (min %.1f%%) | userInput=%.2f%% atrCalc=%.2f%% entry=%.2f slPrice=%.2f",
                slPercent, minSlPercent, userSlInput, atrSlInput, entryPrice, stopLoss);
            log.warn("Bot {} RISK: {}", bot.getId(), diagnostic);
            return new RiskCheck(false, diagnostic);
        }

        // Max SL distance (default 5% — prevents oversized losses)
        double maxSlPercent = getDouble(params, "maxSlDistancePercent", 5.0);
        if (slPercent > maxSlPercent) {
            log.warn("Bot {} RISK: SL too wide: {}% > max {}%", bot.getId(), String.format("%.2f", slPercent), maxSlPercent);
            return new RiskCheck(false, "SL too wide: " + String.format("%.2f", slPercent) + "% (max " + maxSlPercent + "%)");
        }

        // Minimum R:R ratio (default 2.0 — mandatory 1:2 minimum)
        if (takeProfit != null && takeProfit > 0) {
            double tpDistance = Math.abs(takeProfit - entryPrice);
            double rr = slDistance > 0 ? tpDistance / slDistance : 0;
            double minRR = getDouble(params, "minRiskReward", 2.0);
            if (rr < minRR) {
                log.warn("Bot {} RISK: R:R too low: 1:{} < min 1:{}", bot.getId(), String.format("%.1f", rr), minRR);
                return new RiskCheck(false, "R:R too low: 1:" + String.format("%.1f", rr) + " (min 1:" + String.format("%.1f", minRR) + ")");
            }
        }

        return new RiskCheck(true, "R:R validated");
    }

    private BigDecimal calculateDailyPnl(TradingBot bot) {
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        List<TradePosition> todayPositions = positionRepo.findByBotId(bot.getId());
        return todayPositions.stream()
            .filter(p -> "CLOSED".equals(p.getStatus()))
            .filter(p -> p.getClosedAt() != null && p.getClosedAt().isAfter(startOfDay))
            .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int countTodayTrades(TradingBot bot) {
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return (int) positionRepo.findByBotId(bot.getId()).stream()
            .filter(p -> p.getOpenedAt() != null && p.getOpenedAt().isAfter(startOfDay))
            .count();
    }

    /**
     * Count how many recent consecutive trades were losses (most recent first).
     */
    private int countConsecutiveLosses(TradingBot bot) {
        List<TradePosition> positions = positionRepo.findByBotId(bot.getId());
        // Sort by closedAt desc to get most recent first
        List<TradePosition> closed = positions.stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) && p.getRealizedPnl() != null && p.getClosedAt() != null)
            .sorted((a, b) -> b.getClosedAt().compareTo(a.getClosedAt()))
            .toList();

        int consecutive = 0;
        for (TradePosition p : closed) {
            if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                consecutive++;
            } else {
                break;
            }
        }
        return consecutive;
    }

    private int countHourlyTrades(TradingBot bot) {
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        return (int) positionRepo.findByBotId(bot.getId()).stream()
            .filter(p -> p.getOpenedAt() != null && p.getOpenedAt().isAfter(oneHourAgo))
            .count();
    }

    private Instant getLastLossTime(TradingBot bot) {
        List<TradePosition> positions = positionRepo.findByBotId(bot.getId());
        return positions.stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) && p.getRealizedPnl() != null && p.getClosedAt() != null)
            .filter(p -> p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0)
            .map(TradePosition::getClosedAt)
            .max(Instant::compareTo)
            .orElse(null);
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultVal;
    }
}
