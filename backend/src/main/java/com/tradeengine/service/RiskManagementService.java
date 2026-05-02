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
 * Phase 3/4 changes (preserves edge, removes deadlocks):
 *   - maxConsecutiveLosses: time-decay reset (>60min since last loss → counter resets)
 *   - postLossCooldownSec: decays with winsSinceLastLoss (cooldown * (1 - 0.25*wins), min 0)
 *   - minRiskReward: dynamic — clamp(2.4 - score/100, 1.2, 2.0)
 *   - minSlDistancePercent: dynamic — max(0.15, 0.6 * ATR%)
 *   - All rejections recorded via RejectionMetricsService for observability.
 *
 * Optional params recognized for adaptive logic:
 *   - "__qualityScore" (int)        → enables score-aware minRR
 *   - "__atrPercent"  (double)      → enables ATR-aware minSL
 *   - "__botId"       (String)      → telemetry tag (auto-set if bot passed)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private final PositionRepository positionRepo;
    private final OrderRepository orderRepo;
    private final RejectionMetricsService metrics;

    public record RiskCheck(boolean allowed, String reason) {}

    private RiskCheck reject(TradingBot bot, String code, String human) {
        metrics.record(bot != null ? bot.getId().toString() : null, "RISK", code, null);
        return new RiskCheck(false, human);
    }

    /**
     * Validate a BUY order against all risk rules.
     */
    public RiskCheck validateBuy(TradingBot bot, BigDecimal usdtBalance, Map<String, Object> params) {
        // 1. Max open positions
        int maxPositions = getInt(params, "maxPositions", 1);
        List<TradePosition> openPositions = positionRepo.findByBotIdAndStatus(bot.getId(), "OPEN");
        if (openPositions.size() >= maxPositions) {
            log.warn("Bot {} RISK: Max open positions reached ({}/{})", bot.getId(), openPositions.size(), maxPositions);
            return reject(bot, "MAX_POSITIONS", "Max open positions reached (" + maxPositions + ")");
        }

        // 2. Max trade size percent
        double maxTradePercent = getDouble(params, "maxTradePercent", 10.0);
        if (bot.getTradeSizePercent().doubleValue() > maxTradePercent) {
            log.warn("Bot {} RISK: Trade size {}% exceeds max {}%", bot.getId(), bot.getTradeSizePercent(), maxTradePercent);
            return reject(bot, "TRADE_SIZE_TOO_LARGE",
                "Trade size " + bot.getTradeSizePercent() + "% exceeds max " + maxTradePercent + "%");
        }

        // 3. Minimum balance
        BigDecimal minBalance = BigDecimal.valueOf(getDouble(params, "minBalance", 5.0));
        if (usdtBalance.compareTo(minBalance) <= 0) {
            log.warn("Bot {} RISK: Insufficient balance: {} (min: {})", bot.getId(), usdtBalance, minBalance);
            return reject(bot, "INSUFFICIENT_BALANCE",
                "Insufficient USDT balance: " + usdtBalance + " (min $" + minBalance + ")");
        }

        // 4. Max daily loss
        double maxDailyLossPercent = getDouble(params, "maxDailyLossPercent", 3.0);
        if (maxDailyLossPercent > 0) {
            BigDecimal dailyPnl = calculateDailyPnl(bot);
            BigDecimal maxLoss = usdtBalance.multiply(BigDecimal.valueOf(maxDailyLossPercent / 100)).negate();
            if (dailyPnl.compareTo(maxLoss) <= 0) {
                log.warn("Bot {} RISK: Daily loss limit reached: {} (max: {})", bot.getId(), dailyPnl, maxLoss);
                return reject(bot, "DAILY_LOSS_LIMIT",
                    "Daily loss limit reached: $" + dailyPnl.setScale(2, RoundingMode.HALF_UP)
                    + " (max " + maxDailyLossPercent + "%)");
            }
        }

        // 5. Max trades per day — Phase 6 safety guard hint
        int maxTradesPerDay = getInt(params, "maxTradesPerDay", 10);
        if (maxTradesPerDay > 0) {
            int todayTrades = countTodayTrades(bot);
            if (todayTrades >= maxTradesPerDay) {
                log.warn("Bot {} RISK: Max trades per day reached ({}/{})", bot.getId(), todayTrades, maxTradesPerDay);
                return reject(bot, "MAX_TRADES_PER_DAY", "Max trades per day reached (" + maxTradesPerDay + ")");
            }
        }

        // 6. Max trades per hour
        int maxTradesPerHour = getInt(params, "maxTradesPerHour", 3);
        if (maxTradesPerHour > 0) {
            int hourlyTrades = countHourlyTrades(bot);
            if (hourlyTrades >= maxTradesPerHour) {
                log.warn("Bot {} RISK: Max trades per hour reached ({}/{})", bot.getId(), hourlyTrades, maxTradesPerHour);
                return reject(bot, "MAX_TRADES_PER_HOUR", "Max trades per hour reached (" + maxTradesPerHour + ")");
            }
        }

        // 7. Max position size in USDT
        double maxPositionSize = getDouble(params, "maxPositionSize", 500.0);
        if (maxPositionSize > 0) {
            BigDecimal allocAmount = usdtBalance.multiply(bot.getTradeSizePercent())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            if (allocAmount.doubleValue() > maxPositionSize) {
                log.warn("Bot {} RISK: Position size ${} exceeds max ${}", bot.getId(), allocAmount, maxPositionSize);
                return reject(bot, "POSITION_SIZE_EXCEEDED",
                    "Position size $" + allocAmount.setScale(2, RoundingMode.HALF_UP) + " exceeds max $" + maxPositionSize);
            }
        }

        // 8. Consecutive losses with TIME-DECAY RESET (Phase 3)
        // If the last loss was more than `consecutiveLossDecayMin` minutes ago, the streak is reset.
        int maxConsecutiveLosses = getInt(params, "maxConsecutiveLosses", 3);
        int decayMinutes = getInt(params, "consecutiveLossDecayMin", 60);
        if (maxConsecutiveLosses > 0) {
            int consecutiveLosses = countConsecutiveLossesWithDecay(bot, decayMinutes);
            if (consecutiveLosses >= maxConsecutiveLosses) {
                log.warn("Bot {} RISK: {} consecutive losses (max {}) within {}min window",
                    bot.getId(), consecutiveLosses, maxConsecutiveLosses, decayMinutes);
                return reject(bot, "CONSECUTIVE_LOSSES",
                    consecutiveLosses + " consecutive losses — auto-paused (resets after " + decayMinutes + " min)");
            }
        }

        // 9. Post-loss cooldown — DECAYING with wins-since-last-loss (Phase 3)
        int postLossCooldownSec = getInt(params, "postLossCooldownSec", 300);
        if (postLossCooldownSec > 0) {
            Instant lastLossTime = getLastLossTime(bot);
            if (lastLossTime != null) {
                int wins = countWinsSince(bot, lastLossTime);
                int effectiveCooldown = (int) Math.max(0, postLossCooldownSec * (1.0 - 0.25 * wins));
                if (effectiveCooldown > 0) {
                    long secsSinceLoss = java.time.Duration.between(lastLossTime, Instant.now()).toSeconds();
                    if (secsSinceLoss < effectiveCooldown) {
                        long remaining = effectiveCooldown - secsSinceLoss;
                        log.warn("Bot {} RISK: Post-loss cooldown active ({}s remaining, wins={}, base={}, eff={})",
                            bot.getId(), remaining, wins, postLossCooldownSec, effectiveCooldown);
                        return reject(bot, "POST_LOSS_COOLDOWN",
                            "Post-loss cooldown: " + remaining + "s remaining");
                    }
                }
            }
        }

        log.info("Bot {} RISK: All checks passed", bot.getId());
        return new RiskCheck(true, "OK");
    }

    /**
     * Validate risk:reward ratio and SL distance before allowing a trade.
     * Uses adaptive thresholds when score / ATR are provided in params.
     */
    public RiskCheck validateRiskReward(TradingBot bot, double entryPrice, Double stopLoss,
                                        Double takeProfit, Map<String, Object> params) {
        // CRITICAL: Reject trades without a stop loss
        if (stopLoss == null || stopLoss <= 0) {
            log.warn("Bot {} RISK: No stop loss defined — trade REJECTED", bot.getId());
            return reject(bot, "NO_STOP_LOSS", "No stop loss defined — every trade MUST have a stop loss");
        }

        double slDistance = Math.abs(entryPrice - stopLoss);
        double slPercent = (slDistance / entryPrice) * 100;

        // --- Adaptive min SL: max(0.15, 0.6 * ATR%) — falls back to static 0.3% if ATR unknown.
        double staticMinSl = getDouble(params, "minSlDistancePercent", 0.3);
        double atrPercent = getDouble(params, "__atrPercent", -1);
        double minSlPercent = atrPercent > 0
            ? Math.max(0.15, 0.6 * atrPercent)
            : staticMinSl;
        // Never let the dynamic floor exceed the static configured ceiling.
        minSlPercent = Math.min(minSlPercent, staticMinSl);

        if (slPercent < minSlPercent) {
            double userSlInput = getDouble(params, "__userSlPercent", -1);
            double atrSlInput = getDouble(params, "__atrSlPercent", -1);
            String diagnostic = String.format(
                "SL too tight: %.2f%% (min %.2f%%) | userInput=%.2f%% atrCalc=%.2f%% entry=%.2f slPrice=%.2f",
                slPercent, minSlPercent, userSlInput, atrSlInput, entryPrice, stopLoss);
            log.warn("Bot {} RISK: {}", bot.getId(), diagnostic);
            return reject(bot, "SL_TOO_TIGHT", diagnostic);
        }

        // Max SL distance
        double maxSlPercent = getDouble(params, "maxSlDistancePercent", 5.0);
        if (slPercent > maxSlPercent) {
            log.warn("Bot {} RISK: SL too wide: {}% > max {}%", bot.getId(), String.format("%.2f", slPercent), maxSlPercent);
            return reject(bot, "SL_TOO_WIDE",
                "SL too wide: " + String.format("%.2f", slPercent) + "% (max " + maxSlPercent + "%)");
        }

        // --- Adaptive minimum R:R: clamp(2.4 - score/100, 1.2, 2.0)
        if (takeProfit != null && takeProfit > 0) {
            double tpDistance = Math.abs(takeProfit - entryPrice);
            double rr = slDistance > 0 ? tpDistance / slDistance : 0;

            double staticMinRR = getDouble(params, "minRiskReward", 2.0);
            int score = getInt(params, "__qualityScore", -1);
            double minRR;
            if (score > 0) {
                minRR = Math.max(1.2, Math.min(2.0, 2.4 - (score / 100.0)));
            } else {
                minRR = staticMinRR;
            }

            if (rr < minRR) {
                log.warn("Bot {} RISK: R:R too low: 1:{} < min 1:{} (score={})",
                    bot.getId(), String.format("%.1f", rr), String.format("%.1f", minRR), score);
                return reject(bot, "RR_TOO_LOW",
                    "R:R too low: 1:" + String.format("%.1f", rr) + " (min 1:" + String.format("%.1f", minRR) + ")");
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
     * Count consecutive losses, but reset the streak if the most recent loss is older
     * than `decayMinutes`. Prevents permanent lockout after a quiet period.
     */
    private int countConsecutiveLossesWithDecay(TradingBot bot, int decayMinutes) {
        List<TradePosition> closed = positionRepo.findByBotId(bot.getId()).stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) && p.getRealizedPnl() != null && p.getClosedAt() != null)
            .sorted((a, b) -> b.getClosedAt().compareTo(a.getClosedAt()))
            .toList();

        if (closed.isEmpty()) return 0;

        // If most recent loss is older than decayMinutes → streak is stale, treat as 0.
        Instant decayCutoff = Instant.now().minus(decayMinutes, ChronoUnit.MINUTES);
        TradePosition mostRecent = closed.get(0);
        if (mostRecent.getRealizedPnl().compareTo(BigDecimal.ZERO) >= 0) return 0;
        if (mostRecent.getClosedAt().isBefore(decayCutoff)) return 0;

        int consecutive = 0;
        for (TradePosition p : closed) {
            if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) consecutive++;
            else break;
        }
        return consecutive;
    }

    private int countWinsSince(TradingBot bot, Instant since) {
        return (int) positionRepo.findByBotId(bot.getId()).stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) && p.getRealizedPnl() != null && p.getClosedAt() != null)
            .filter(p -> p.getClosedAt().isAfter(since))
            .filter(p -> p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
            .count();
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
