package com.tradeengine.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.model.*;
import com.tradeengine.repository.*;
import com.tradeengine.service.ApiKeyService;
import com.tradeengine.ws.TradeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs every 30 seconds. For each RUNNING bot:
 * 1. Skip if isProcessing
 * 2. Skip if trade cooldown not met (60s min)
 * 3. Resolve exchange endpoint from bot.exchangeMode
 * 4. Fetch candles
 * 5. Resolve strategy from bot.strategyType
 * 6. Parse strategyParams JSON
 * 7. Execute BUY or SELL if signal
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
    private final ObjectMapper objectMapper;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    private static final Duration TRADE_COOLDOWN = Duration.ofSeconds(60);

    @Scheduled(fixedDelay = 30000)
    public void runStrategies() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");

        for (TradingBot bot : runningBots) {
            try {
                processBot(bot);
            } catch (Exception e) {
                log.error("Error processing bot {}: {}", bot.getId(), e.getMessage(), e);
                bot.setProcessing(false);
                botRepo.save(bot);
            }
        }
    }

    private void processBot(TradingBot bot) {
        if (bot.isProcessing()) {
            log.debug("Bot {} is already processing, skipping", bot.getId());
            return;
        }

        if (bot.getLastTradeTime() != null) {
            Duration sinceLastTrade = Duration.between(bot.getLastTradeTime(), Instant.now());
            if (sinceLastTrade.compareTo(TRADE_COOLDOWN) < 0) {
                log.debug("Bot {} cooldown active ({}s remaining)", bot.getId(),
                    TRADE_COOLDOWN.minus(sinceLastTrade).toSeconds());
                return;
            }
        }

        bot.setProcessing(true);
        botRepo.save(bot);

        try {
            // Resolve exchange base URL
            String exchangeBaseUrl = resolveExchangeUrl(bot.getExchangeMode());

            // Get API keys
            UserApiKey apiKey = apiKeyRepo.findById(bot.getApiKeyId())
                .orElseThrow(() -> new RuntimeException("API key not found for bot " + bot.getId()));

            String decryptedKey = apiKeyService.decryptApiKey(apiKey);
            String decryptedSecret = apiKeyService.decryptApiSecret(apiKey);

            // Fetch candles
            List<double[]> candles = binance.getCandles(bot.getSymbol(), bot.getTimeframe(), 100, exchangeBaseUrl);

            if (candles.size() < 50) {
                log.warn("Bot {}: Only {} candles available, need 50+. Skipping.", bot.getId(), candles.size());
                return;
            }

            List<Double> closingPrices = candles.stream().map(c -> c[4]).collect(Collectors.toList());

            // Resolve strategy
            TradingStrategy strategy = StrategyFactory.get(bot.getStrategyType());

            // Parse strategy params
            Map<String, Object> params = parseStrategyParams(bot);
            // Inject fastEma/slowEma from bot fields as defaults
            params.putIfAbsent("fastEma", bot.getFastEma());
            params.putIfAbsent("slowEma", bot.getSlowEma());

            // Evaluate
            TradingStrategy.SignalResult signal = strategy.evaluate(
                closingPrices, candles, params, bot.isHasOpenPosition()
            );

            log.info("Bot {} [{}] strategy={} signal={} - {}",
                bot.getId(), bot.getSymbol(), bot.getStrategyType(), signal.signal(), signal.reason());

            // Act on signal
            if (signal.signal() == TradingStrategy.Signal.BUY && !bot.isHasOpenPosition()) {
                executeBuy(bot, decryptedKey, decryptedSecret, signal, exchangeBaseUrl);
            } else if (signal.signal() == TradingStrategy.Signal.SELL && bot.isHasOpenPosition()) {
                executeSell(bot, decryptedKey, decryptedSecret, signal, exchangeBaseUrl);
            }
        } finally {
            bot.setProcessing(false);
            botRepo.save(bot);
        }
    }

    private String resolveExchangeUrl(String exchangeMode) {
        if ("LIVE".equalsIgnoreCase(exchangeMode)) {
            if (!liveTradingEnabled) {
                log.warn("Live trading disabled. Forcing TESTNET.");
                return "https://testnet.binance.vision";
            }
            return "https://api.binance.com";
        }
        return "https://testnet.binance.vision";
    }

    private Map<String, Object> parseStrategyParams(TradingBot bot) {
        if (bot.getStrategyParams() == null || bot.getStrategyParams().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(bot.getStrategyParams(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Bot {}: Failed to parse strategyParams: {}", bot.getId(), e.getMessage());
            return new HashMap<>();
        }
    }

    private void executeBuy(TradingBot bot, String apiKey, String secret,
                            TradingStrategy.SignalResult signal, String exchangeBaseUrl) {
        var balances = binance.getBalances(apiKey, secret, exchangeBaseUrl);
        BigDecimal usdtBalance = balances.getOrDefault("USDT", BigDecimal.ZERO);

        if (usdtBalance.compareTo(BigDecimal.ONE) <= 0) {
            log.warn("Bot {}: USDT balance too low ({})", bot.getId(), usdtBalance);
            return;
        }

        BigDecimal allocationAmount = usdtBalance.multiply(bot.getTradeSizePercent())
            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        BigDecimal currentPrice = BigDecimal.valueOf(signal.price());
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal quantity = allocationAmount.divide(currentPrice, 6, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) return;

        log.info("Bot {}: BUY {} {} @ ~{}", bot.getId(), quantity, bot.getSymbol(), currentPrice);

        BinanceClient.OrderResult result = binance.placeMarketOrder(
            apiKey, secret, bot.getSymbol(), "BUY", quantity, exchangeBaseUrl
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

        publisher.publishOrderFilled(bot.getUserId().toString(), order);
        publisher.publishPositionOpened(bot.getUserId().toString(), position);

        log.info("Bot {}: BUY filled. Entry={}, Qty={}", bot.getId(), result.getAvgPrice(), result.getExecutedQty());
    }

    private void executeSell(TradingBot bot, String apiKey, String secret,
                             TradingStrategy.SignalResult signal, String exchangeBaseUrl) {
        if (bot.getQuantity() == null || bot.getQuantity().compareTo(BigDecimal.ZERO) <= 0) return;

        log.info("Bot {}: SELL {} {} @ ~{}", bot.getId(), bot.getQuantity(), bot.getSymbol(), signal.price());

        BinanceClient.OrderResult result = binance.placeMarketOrder(
            apiKey, secret, bot.getSymbol(), "SELL", bot.getQuantity(), exchangeBaseUrl
        );

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

        BigDecimal pnl = BigDecimal.ZERO;
        if (bot.getEntryPrice() != null) {
            pnl = result.getAvgPrice().subtract(bot.getEntryPrice()).multiply(bot.getQuantity());
        }

        TradePosition position = positionRepo
            .findByBotIdAndSymbolAndStatus(bot.getId(), bot.getSymbol(), "OPEN").orElse(null);
        if (position != null) {
            position.setStatus("CLOSED");
            position.setExitPrice(result.getAvgPrice());
            position.setRealizedPnl(pnl);
            position.setClosedAt(Instant.now());
            positionRepo.save(position);
            publisher.publishPositionClosed(bot.getUserId().toString(), position, pnl);
        }

        bot.setHasOpenPosition(false);
        bot.setEntryPrice(null);
        bot.setQuantity(null);
        bot.setLastTradeTime(Instant.now());
        botRepo.save(bot);

        publisher.publishOrderFilled(bot.getUserId().toString(), order);
        log.info("Bot {}: SELL filled. Exit={}, PnL={}", bot.getId(), result.getAvgPrice(), pnl);
    }
}
