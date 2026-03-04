package com.tradeengine.strategy;

import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.model.*;
import com.tradeengine.repository.*;
import com.tradeengine.service.ApiKeyService;
import com.tradeengine.ws.TradeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs every 60 seconds. For each RUNNING bot:
 * 1. Fetch candles from Binance
 * 2. Run EMA crossover strategy
 * 3. If BUY signal and no open position → place buy order
 * 4. If SELL signal and has open position → close position
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyRunner {

    private final BotRepository botRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final OrderRepository orderRepo;
    private final PositionRepository positionRepo;
    private final BinanceClient binance;
    private final TradeEventPublisher publisher;

    @Scheduled(fixedDelay = 60000) // Every 60 seconds
    public void runStrategies() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");

        for (TradingBot bot : runningBots) {
            try {
                processBot(bot);
            } catch (Exception e) {
                log.error("Error processing bot {}: {}", bot.getId(), e.getMessage());
            }
        }
    }

    private void processBot(TradingBot bot) {
        // 1. Get API keys
        UserApiKey apiKey = apiKeyRepo.findById(bot.getApiKeyId())
            .orElseThrow(() -> new RuntimeException("API key not found for bot " + bot.getId()));

        String decryptedKey = apiKeyService.decryptApiKey(apiKey);
        String decryptedSecret = apiKeyService.decryptApiSecret(apiKey);

        // 2. Fetch candles (50 candles for EMA calculation)
        List<double[]> candles = binance.getCandles(bot.getSymbol(), mapTimeframe(bot.getTimeframe()), 50);
        List<Double> closingPrices = candles.stream()
            .map(c -> c[4]) // close price
            .collect(Collectors.toList());

        // 3. Evaluate strategy
        EmaCrossover.SignalResult signal = EmaCrossover.evaluate(closingPrices);
        log.debug("Bot {} signal: {} - {}", bot.getId(), signal.getSignal(), signal.getReason());

        // 4. Check if we have an open position
        boolean hasPosition = positionRepo.existsByBotIdAndSymbolAndStatus(
            bot.getId(), bot.getSymbol(), "OPEN"
        );

        // 5. Act on signal
        if (signal.getSignal() == EmaCrossover.Signal.BUY && !hasPosition) {
            executeBuy(bot, decryptedKey, decryptedSecret, signal);
        } else if (signal.getSignal() == EmaCrossover.Signal.SELL && hasPosition) {
            executeSell(bot, decryptedKey, decryptedSecret, signal);
        }
    }

    private void executeBuy(TradingBot bot, String apiKey, String secret,
                            EmaCrossover.SignalResult signal) {
        // Calculate position size: riskPercent of balance
        var balances = binance.getBalances(apiKey, secret);
        BigDecimal usdtBalance = balances.getOrDefault("USDT", BigDecimal.ZERO);

        BigDecimal allocationAmount = usdtBalance.multiply(bot.getRiskPercent())
            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        BigDecimal currentPrice = BigDecimal.valueOf(signal.getPrice());
        BigDecimal quantity = allocationAmount.divide(currentPrice, 6, RoundingMode.DOWN);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bot {}: Calculated quantity is 0, skipping buy", bot.getId());
            return;
        }

        log.info("Bot {}: Executing BUY {} {} @ ~{}", bot.getId(), quantity, bot.getSymbol(), currentPrice);

        // Place order
        BinanceClient.OrderResult result = binance.placeMarketOrder(
            apiKey, secret, bot.getSymbol(), "BUY", quantity
        );

        // Save order
        TradeOrder order = new TradeOrder();
        order.setBotId(bot.getId());
        order.setUserId(bot.getUserId());
        order.setExchangeOrderId(result.getOrderId());
        order.setSymbol(bot.getSymbol());
        order.setSide("BUY");
        order.setType("MARKET");
        order.setQuantity(result.getExecutedQty());
        order.setFilledQuantity(result.getExecutedQty());
        order.setAvgFillPrice(result.getAvgPrice());
        order.setStatus("FILLED");
        order.setFilledAt(Instant.now());
        orderRepo.save(order);

        // Open position
        TradePosition position = new TradePosition();
        position.setBotId(bot.getId());
        position.setUserId(bot.getUserId());
        position.setSymbol(bot.getSymbol());
        position.setSide("LONG");
        position.setQuantity(result.getExecutedQty());
        position.setEntryPrice(result.getAvgPrice());
        position.setCurrentPrice(result.getAvgPrice());
        positionRepo.save(position);

        // Publish events
        publisher.publishOrderFilled(bot.getUserId().toString(), order);
        publisher.publishPositionOpened(bot.getUserId().toString(), position);
    }

    private void executeSell(TradingBot bot, String apiKey, String secret,
                             EmaCrossover.SignalResult signal) {
        TradePosition position = positionRepo
            .findByBotIdAndSymbolAndStatus(bot.getId(), bot.getSymbol(), "OPEN")
            .orElse(null);

        if (position == null) return;

        log.info("Bot {}: Executing SELL {} {} @ ~{}", bot.getId(), position.getQuantity(),
            bot.getSymbol(), signal.getPrice());

        // Place sell order
        BinanceClient.OrderResult result = binance.placeMarketOrder(
            apiKey, secret, bot.getSymbol(), "SELL", position.getQuantity()
        );

        // Save order
        TradeOrder order = new TradeOrder();
        order.setBotId(bot.getId());
        order.setUserId(bot.getUserId());
        order.setExchangeOrderId(result.getOrderId());
        order.setSymbol(bot.getSymbol());
        order.setSide("SELL");
        order.setType("MARKET");
        order.setQuantity(result.getExecutedQty());
        order.setFilledQuantity(result.getExecutedQty());
        order.setAvgFillPrice(result.getAvgPrice());
        order.setStatus("FILLED");
        order.setFilledAt(Instant.now());
        orderRepo.save(order);

        // Close position
        BigDecimal pnl = result.getAvgPrice().subtract(position.getEntryPrice())
            .multiply(position.getQuantity());

        position.setStatus("CLOSED");
        position.setExitPrice(result.getAvgPrice());
        position.setRealizedPnl(pnl);
        position.setClosedAt(Instant.now());
        positionRepo.save(position);

        // Publish events
        publisher.publishOrderFilled(bot.getUserId().toString(), order);
        publisher.publishPositionClosed(bot.getUserId().toString(), position, pnl);
    }

    private String mapTimeframe(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> "1m";
            case "5m" -> "5m";
            case "15m" -> "15m";
            case "1h" -> "1h";
            case "4h" -> "4h";
            case "1d" -> "1d";
            default -> "1h";
        };
    }
}
