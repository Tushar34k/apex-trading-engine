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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs every 60 seconds. For each RUNNING bot:
 * 1. Skip if isProcessing (prevent parallel execution)
 * 2. Skip if trade cooldown not met (3 min)
 * 3. Fetch candles from Binance
 * 4. Run EMA crossover with bot's dynamic params
 * 5. Execute BUY or SELL if signal
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

    private static final Duration TRADE_COOLDOWN = Duration.ofMinutes(3);

    @Scheduled(fixedDelay = 60000)
    public void runStrategies() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");

        for (TradingBot bot : runningBots) {
            try {
                processBot(bot);
            } catch (Exception e) {
                log.error("Error processing bot {}: {}", bot.getId(), e.getMessage());
                // Reset processing flag on error
                bot.setProcessing(false);
                botRepo.save(bot);
            }
        }
    }

    private void processBot(TradingBot bot) {
        // Guard: prevent parallel execution
        if (bot.isProcessing()) {
            log.debug("Bot {} is already processing, skipping", bot.getId());
            return;
        }

        // Trade cooldown check
        if (bot.getLastTradeTime() != null) {
            Duration sinceLastTrade = Duration.between(bot.getLastTradeTime(), Instant.now());
            if (sinceLastTrade.compareTo(TRADE_COOLDOWN) < 0) {
                log.debug("Bot {} cooldown active ({} remaining)", bot.getId(),
                    TRADE_COOLDOWN.minus(sinceLastTrade).toSeconds() + "s");
                return;
            }
        }

        // Set processing flag
        bot.setProcessing(true);
        botRepo.save(bot);

        try {
            // 1. Get API keys
            UserApiKey apiKey = apiKeyRepo.findById(bot.getApiKeyId())
                .orElseThrow(() -> new RuntimeException("API key not found for bot " + bot.getId()));

            String decryptedKey = apiKeyService.decryptApiKey(apiKey);
            String decryptedSecret = apiKeyService.decryptApiSecret(apiKey);

            // 2. Fetch candles (100 candles)
            List<double[]> candles = binance.getCandles(bot.getSymbol(), bot.getTimeframe(), 100);

            if (candles.size() < 50) {
                log.warn("Bot {}: Only {} candles available, need at least 50. Skipping.", bot.getId(), candles.size());
                return;
            }

            // Sort oldest → newest (Binance returns oldest first already)
            List<Double> closingPrices = candles.stream()
                .map(c -> c[4]) // close price
                .collect(Collectors.toList());

            // 3. Evaluate strategy with dynamic EMA params
            EmaCrossover.SignalResult signal = EmaCrossover.evaluate(
                closingPrices, bot.getFastEma(), bot.getSlowEma()
            );
            log.info("Bot {} [{}] signal: {} - {}", bot.getId(), bot.getSymbol(), signal.getSignal(), signal.getReason());

            // 4. Act on signal
            if (signal.getSignal() == EmaCrossover.Signal.BUY && !bot.isHasOpenPosition()) {
                executeBuy(bot, decryptedKey, decryptedSecret, signal);
            } else if (signal.getSignal() == EmaCrossover.Signal.SELL && bot.isHasOpenPosition()) {
                executeSell(bot, decryptedKey, decryptedSecret, signal);
            }
        } finally {
            // Always reset processing flag
            bot.setProcessing(false);
            botRepo.save(bot);
        }
    }

    private void executeBuy(TradingBot bot, String apiKey, String secret,
                            EmaCrossover.SignalResult signal) {
        // Calculate position size
        var balances = binance.getBalances(apiKey, secret);
        BigDecimal usdtBalance = balances.getOrDefault("USDT", BigDecimal.ZERO);

        if (usdtBalance.compareTo(BigDecimal.ONE) <= 0) {
            log.warn("Bot {}: USDT balance too low ({}), skipping buy", bot.getId(), usdtBalance);
            return;
        }

        BigDecimal allocationAmount = usdtBalance.multiply(bot.getTradeSizePercent())
            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        BigDecimal currentPrice = BigDecimal.valueOf(signal.getPrice());
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bot {}: Invalid price {}, skipping", bot.getId(), currentPrice);
            return;
        }

        BigDecimal quantity = allocationAmount.divide(currentPrice, 6, RoundingMode.DOWN);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bot {}: Calculated quantity is 0, skipping buy", bot.getId());
            return;
        }

        log.info("Bot {}: BUY {} {} @ ~{} ({}% of {} USDT = {} USDT)",
            bot.getId(), quantity, bot.getSymbol(), currentPrice,
            bot.getTradeSizePercent(), usdtBalance, allocationAmount);

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

        // Update bot state
        bot.setHasOpenPosition(true);
        bot.setEntryPrice(result.getAvgPrice());
        bot.setQuantity(result.getExecutedQty());
        bot.setLastTradeTime(Instant.now());
        botRepo.save(bot);

        // Open position record
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

        log.info("Bot {}: BUY order filled. Entry={}, Qty={}", bot.getId(), result.getAvgPrice(), result.getExecutedQty());
    }

    private void executeSell(TradingBot bot, String apiKey, String secret,
                             EmaCrossover.SignalResult signal) {
        if (bot.getQuantity() == null || bot.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bot {}: No quantity to sell", bot.getId());
            return;
        }

        log.info("Bot {}: SELL {} {} @ ~{}",
            bot.getId(), bot.getQuantity(), bot.getSymbol(), signal.getPrice());

        // Place sell order
        BinanceClient.OrderResult result = binance.placeMarketOrder(
            apiKey, secret, bot.getSymbol(), "SELL", bot.getQuantity()
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

        // Calculate PnL
        BigDecimal pnl = BigDecimal.ZERO;
        if (bot.getEntryPrice() != null) {
            pnl = result.getAvgPrice().subtract(bot.getEntryPrice()).multiply(bot.getQuantity());
        }

        // Close position
        TradePosition position = positionRepo
            .findByBotIdAndSymbolAndStatus(bot.getId(), bot.getSymbol(), "OPEN")
            .orElse(null);

        if (position != null) {
            position.setStatus("CLOSED");
            position.setExitPrice(result.getAvgPrice());
            position.setRealizedPnl(pnl);
            position.setClosedAt(Instant.now());
            positionRepo.save(position);
            publisher.publishPositionClosed(bot.getUserId().toString(), position, pnl);
        }

        // Reset bot state
        bot.setHasOpenPosition(false);
        bot.setEntryPrice(null);
        bot.setQuantity(null);
        bot.setLastTradeTime(Instant.now());
        botRepo.save(bot);

        // Publish events
        publisher.publishOrderFilled(bot.getUserId().toString(), order);

        log.info("Bot {}: SELL order filled. Exit={}, PnL={}", bot.getId(), result.getAvgPrice(), pnl);
    }
}
