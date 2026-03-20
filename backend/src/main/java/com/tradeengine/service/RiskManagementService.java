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
 * - maxTradePercent: max trade size as % of balance (default 100)
 * - maxDailyLossPercent: max daily loss as % of balance (default 0 = disabled)
 * - maxTradesPerDay: max number of trades per day (default 0 = unlimited)
 * - maxPositionSize: max position size in USDT (default 0 = unlimited)
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
        // 1. Max open positions (default 3 for institutional grade)
        int maxPositions = getInt(params, "maxPositions", 3);
        List<TradePosition> openPositions = positionRepo.findByBotIdAndStatus(bot.getId(), "OPEN");
        if (openPositions.size() >= maxPositions) {
            log.warn("Bot {} RISK: Max open positions reached ({}/{})", bot.getId(), openPositions.size(), maxPositions);
            return new RiskCheck(false, "Max open positions reached (" + maxPositions + ")");
        }

        // 2. Max trade size percent
        double maxTradePercent = getDouble(params, "maxTradePercent", 100);
        if (bot.getTradeSizePercent().doubleValue() > maxTradePercent) {
            log.warn("Bot {} RISK: Trade size {}% exceeds max {}%", bot.getId(), bot.getTradeSizePercent(), maxTradePercent);
            return new RiskCheck(false, "Trade size " + bot.getTradeSizePercent() + "% exceeds max " + maxTradePercent + "%");
        }

        // 3. Minimum balance
        if (usdtBalance.compareTo(BigDecimal.ONE) <= 0) {
            log.warn("Bot {} RISK: Insufficient balance: {}", bot.getId(), usdtBalance);
            return new RiskCheck(false, "Insufficient USDT balance: " + usdtBalance);
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

        // 5. Max trades per day
        int maxTradesPerDay = getInt(params, "maxTradesPerDay", 0);
        if (maxTradesPerDay > 0) {
            int todayTrades = countTodayTrades(bot);
            if (todayTrades >= maxTradesPerDay) {
                log.warn("Bot {} RISK: Max trades per day reached ({}/{})", bot.getId(), todayTrades, maxTradesPerDay);
                return new RiskCheck(false, "Max trades per day reached (" + maxTradesPerDay + ")");
            }
        }

        // 6. Max position size (USDT)
        double maxPositionSize = getDouble(params, "maxPositionSize", 0);
        if (maxPositionSize > 0) {
            BigDecimal allocAmount = usdtBalance.multiply(bot.getTradeSizePercent())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            if (allocAmount.doubleValue() > maxPositionSize) {
                log.warn("Bot {} RISK: Position size ${} exceeds max ${}", bot.getId(), allocAmount, maxPositionSize);
                return new RiskCheck(false, "Position size $" + allocAmount.setScale(2, RoundingMode.HALF_UP) + " exceeds max $" + maxPositionSize);
            }
        }

        // 7. Consecutive losses auto-stop (default 3)
        int maxConsecutiveLosses = getInt(params, "maxConsecutiveLosses", 3);
        if (maxConsecutiveLosses > 0) {
            int consecutiveLosses = countConsecutiveLosses(bot);
            if (consecutiveLosses >= maxConsecutiveLosses) {
                log.warn("Bot {} RISK: {} consecutive losses (max {})", bot.getId(), consecutiveLosses, maxConsecutiveLosses);
                return new RiskCheck(false, consecutiveLosses + " consecutive losses — auto-paused (max " + maxConsecutiveLosses + ")");
            }
        }

        log.info("Bot {} RISK: All checks passed", bot.getId());
        return new RiskCheck(true, "OK");
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

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultVal;
    }
}
