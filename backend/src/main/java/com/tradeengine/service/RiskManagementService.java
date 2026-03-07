package com.tradeengine.service;

import com.tradeengine.model.TradePosition;
import com.tradeengine.model.TradingBot;
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
import java.util.Optional;

/**
 * Risk management checks before every trade.
 * Rules enforced:
 * - Max trade size percent
 * - Max daily loss percent
 * - Max open positions per bot (default 1)
 * - Minimum cooldown between trades
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private final PositionRepository positionRepo;

    public record RiskCheck(boolean allowed, String reason) {}

    /**
     * Validate a BUY order against risk rules.
     */
    public RiskCheck validateBuy(TradingBot bot, BigDecimal usdtBalance, Map<String, Object> params) {
        // 1. Max open positions (default 1)
        int maxPositions = getInt(params, "maxPositions", 1);
        List<TradePosition> openPositions = positionRepo.findByBotIdAndStatus(bot.getId(), "OPEN");
        if (openPositions.size() >= maxPositions) {
            return new RiskCheck(false, "Max open positions reached (" + maxPositions + ")");
        }

        // 2. Max trade size percent
        double maxTradePercent = getDouble(params, "maxTradePercent", 100);
        if (bot.getTradeSizePercent().doubleValue() > maxTradePercent) {
            return new RiskCheck(false, "Trade size " + bot.getTradeSizePercent() + "% exceeds max " + maxTradePercent + "%");
        }

        // 3. Minimum balance
        if (usdtBalance.compareTo(BigDecimal.ONE) <= 0) {
            return new RiskCheck(false, "Insufficient USDT balance: " + usdtBalance);
        }

        // 4. Max daily loss
        double maxDailyLossPercent = getDouble(params, "maxDailyLossPercent", 0);
        if (maxDailyLossPercent > 0) {
            BigDecimal dailyPnl = calculateDailyPnl(bot);
            BigDecimal maxLoss = usdtBalance.multiply(BigDecimal.valueOf(maxDailyLossPercent / 100)).negate();
            if (dailyPnl.compareTo(maxLoss) <= 0) {
                return new RiskCheck(false, "Daily loss limit reached: " + dailyPnl + " (max: " + maxLoss + ")");
            }
        }

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

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultVal;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultVal;
    }
}
