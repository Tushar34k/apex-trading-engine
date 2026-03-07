package com.tradeengine.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.exchange.SymbolInfo;
import com.tradeengine.exchange.SymbolInfoCache;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.model.*;
import com.tradeengine.repository.*;
import com.tradeengine.service.ApiKeyService;
import com.tradeengine.service.NotificationService;
import com.tradeengine.service.RiskManagementService;
import com.tradeengine.service.TrailingStopService;
import com.tradeengine.ws.BinanceStreamClient;
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
    private final SymbolInfoCache symbolInfoCache;
    private final TradeEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final RiskManagementService riskService;
    private final TrailingStopService trailingStopService;
    private final NotificationService notificationService;
    private final BinanceStreamClient streamClient;
    private final TradeExecutionQueue executionQueue;

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
                log.error("Bot {} [{}] error: {}", bot.getId(), bot.getName(), e.getMessage(), e);
                bot.setProcessing(false);
                botRepo.save(bot);
            }
        }
    }

    private void processBot(TradingBot bot) {
        if (bot.isProcessing()) {
            log.debug("Bot {} already processing, skip", bot.getId());
            return;
        }

        if (bot.getLastTradeTime() != null) {
            Duration since = Duration.between(bot.getLastTradeTime(), Instant.now());
            if (since.compareTo(TRADE_COOLDOWN) < 0) {
                log.debug("Bot {} cooldown ({}s left)", bot.getId(), TRADE_COOLDOWN.minus(since).toSeconds());
                return;
            }
        }

        bot.setProcessing(true);
        botRepo.save(bot);

        try {
            String exchangeBaseUrl = resolveExchangeUrl(bot.getExchangeMode());

            UserApiKey apiKey = apiKeyRepo.findById(bot.getApiKeyId())
                .orElseThrow(() -> new RuntimeException("API key not found for bot " + bot.getId()));
            String decryptedKey = apiKeyService.decryptApiKey(apiKey);
            String decryptedSecret = apiKeyService.decryptApiSecret(apiKey);

            SymbolInfo symbolInfo = symbolInfoCache.getOrFetch(bot.getSymbol(), exchangeBaseUrl);
            Map<String, Object> params = parseStrategyParams(bot);

            // --- SL/TP/Trailing checks for open positions ---
            if (bot.isHasOpenPosition() && bot.getEntryPrice() != null) {
                Double cachedPrice = streamClient.getLatestPrice(bot.getSymbol());
                BigDecimal currentPrice = cachedPrice != null
                    ? BigDecimal.valueOf(cachedPrice)
                    : binance.getTickerPrice(bot.getSymbol(), exchangeBaseUrl);

                // Trailing stop check
                if (params.containsKey("trailingStopPercent")) {
                    double tsPct = ((Number) params.get("trailingStopPercent")).doubleValue();
                    if (trailingStopService.checkTrailingStop(bot.getId(), currentPrice, bot.getEntryPrice(), tsPct)) {
                        log.warn("Bot {} TRAILING STOP triggered @ {}", bot.getId(), currentPrice);
                        submitSell(bot, decryptedKey, decryptedSecret, currentPrice,
                            "Trailing stop triggered", exchangeBaseUrl, symbolInfo, "BOT_TRAILING_SL");
                        return;
                    }
                }

                // Stop-loss check
                if (params.containsKey("stopLossPercent")) {
                    double slPct = ((Number) params.get("stopLossPercent")).doubleValue();
                    BigDecimal slPrice = bot.getEntryPrice().multiply(
                        BigDecimal.ONE.subtract(BigDecimal.valueOf(slPct / 100)));
                    if (currentPrice.compareTo(slPrice) <= 0) {
                        log.warn("Bot {} STOP-LOSS triggered: price {} <= SL {}", bot.getId(), currentPrice, slPrice);
                        submitSell(bot, decryptedKey, decryptedSecret, currentPrice,
                            "Stop-loss triggered at " + slPct + "%", exchangeBaseUrl, symbolInfo, "BOT_SL");
                        return;
                    }
                }

                // Take-profit check
                if (params.containsKey("takeProfitPercent")) {
                    double tpPct = ((Number) params.get("takeProfitPercent")).doubleValue();
                    BigDecimal tpPrice = bot.getEntryPrice().multiply(
                        BigDecimal.ONE.add(BigDecimal.valueOf(tpPct / 100)));
                    if (currentPrice.compareTo(tpPrice) >= 0) {
                        log.info("Bot {} TAKE-PROFIT triggered: price {} >= TP {}", bot.getId(), currentPrice, tpPrice);
                        submitSell(bot, decryptedKey, decryptedSecret, currentPrice,
                            "Take-profit triggered at " + tpPct + "%", exchangeBaseUrl, symbolInfo, "BOT_TP");
                        return;
                    }
                }
            }

            // --- Fetch candles ---
            List<double[]> candles = binance.getCandles(bot.getSymbol(), bot.getTimeframe(), 100, exchangeBaseUrl);
            if (candles.size() < 50) {
                log.warn("Bot {}: only {} candles, need 50+. Skip.", bot.getId(), candles.size());
                return;
            }

            List<Double> closingPrices = candles.stream().map(c -> c[4]).collect(Collectors.toList());

            // Inject order book depth
            double[] depth = streamClient.getDepth(bot.getSymbol());
            if (depth != null) {
                params.put("bidVolume", depth[0]);
                params.put("askVolume", depth[1]);
            }

            // --- Evaluate strategy ---
            TradingStrategy strategy = StrategyFactory.get(bot.getStrategyType());
            params.putIfAbsent("fastEma", bot.getFastEma());
            params.putIfAbsent("slowEma", bot.getSlowEma());

            TradingStrategy.SignalResult signal = strategy.evaluate(closingPrices, candles, params, bot.isHasOpenPosition());

            log.info("Bot {} [{}] strategy={} signal={} reason={}",
                bot.getId(), bot.getName(), bot.getStrategyType(), signal.signal(), signal.reason());

            if (signal.signal() == TradingStrategy.Signal.BUY && !bot.isHasOpenPosition()) {
                submitBuy(bot, decryptedKey, decryptedSecret, signal, exchangeBaseUrl, symbolInfo, params);
            } else if (signal.signal() == TradingStrategy.Signal.SELL && bot.isHasOpenPosition()) {
                submitSell(bot, decryptedKey, decryptedSecret,
                    BigDecimal.valueOf(signal.price()), signal.reason(),
                    exchangeBaseUrl, symbolInfo, "BOT_SELL");
            }
        } finally {
            bot.setProcessing(false);
            botRepo.save(bot);
        }
    }

    /**
     * Submit BUY trade to the execution queue instead of executing directly.
     */
    private void submitBuy(TradingBot bot, String apiKey, String secret,
                           TradingStrategy.SignalResult signal, String exchangeBaseUrl,
                           SymbolInfo symbolInfo, Map<String, Object> params) {
        var balances = binance.getBalances(apiKey, secret, exchangeBaseUrl);
        BigDecimal usdtBalance = balances.getOrDefault("USDT", BigDecimal.ZERO);

        RiskManagementService.RiskCheck riskCheck = riskService.validateBuy(bot, usdtBalance, params);
        if (!riskCheck.allowed()) {
            log.warn("Bot {} RISK BLOCKED: {}", bot.getId(), riskCheck.reason());
            notificationService.notifyRiskBlocked(bot.getUserId().toString(), bot.getName(), bot.getSymbol(), riskCheck.reason());
            return;
        }

        BigDecimal allocationAmount = usdtBalance.multiply(bot.getTradeSizePercent())
            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal currentPrice = BigDecimal.valueOf(signal.price());
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal rawQty = allocationAmount.divide(currentPrice, 8, RoundingMode.DOWN);
        BigDecimal quantity = symbolInfo != null ? symbolInfo.roundQuantity(rawQty) : rawQty.setScale(6, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bot {}: quantity rounds to 0 after LOT_SIZE", bot.getId());
            return;
        }

        if (symbolInfo != null) {
            String error = symbolInfo.validate(quantity, currentPrice);
            if (error != null) {
                log.warn("Bot {}: LOT_SIZE validation failed: {}", bot.getId(), error);
                return;
            }
        }

        log.info("Bot {} [{}]: Submitting BUY {} {} @ ~{} to execution queue",
            bot.getId(), bot.getName(), quantity, bot.getSymbol(), currentPrice);

        TradeRequest request = TradeRequest.builder()
            .botId(bot.getId())
            .userId(bot.getUserId())
            .symbol(bot.getSymbol())
            .side("BUY")
            .quantity(quantity)
            .orderType("MARKET")
            .apiKey(apiKey)
            .apiSecret(secret)
            .exchangeBaseUrl(exchangeBaseUrl)
            .notificationType("BOT_BUY")
            .timestamp(Instant.now())
            .build();

        executionQueue.submitTrade(request);

        // Handle result asynchronously
        request.getResultFuture().thenAccept(result -> {
            if (result.isSuccess()) {
                handleBuyFilled(bot, result);
            } else {
                log.error("Bot {} BUY execution failed: {}", bot.getId(), result.getErrorMessage());
            }
        });
    }

    /**
     * Submit SELL trade to the execution queue.
     */
    private void submitSell(TradingBot bot, String apiKey, String secret,
                            BigDecimal currentPrice, String reason,
                            String exchangeBaseUrl, SymbolInfo symbolInfo,
                            String notificationType) {
        if (bot.getQuantity() == null || bot.getQuantity().compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal quantity = bot.getQuantity();
        if (symbolInfo != null) {
            quantity = symbolInfo.roundQuantity(quantity);
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Bot {}: sell quantity rounds to 0", bot.getId());
                return;
            }
        }

        log.info("Bot {} [{}]: Submitting SELL {} {} @ ~{} ({}) to execution queue",
            bot.getId(), bot.getName(), quantity, bot.getSymbol(), currentPrice, notificationType);

        TradeRequest request = TradeRequest.builder()
            .botId(bot.getId())
            .userId(bot.getUserId())
            .symbol(bot.getSymbol())
            .side("SELL")
            .quantity(quantity)
            .orderType("MARKET")
            .apiKey(apiKey)
            .apiSecret(secret)
            .exchangeBaseUrl(exchangeBaseUrl)
            .notificationType(notificationType)
            .timestamp(Instant.now())
            .build();

        executionQueue.submitTrade(request);

        request.getResultFuture().thenAccept(result -> {
            if (result.isSuccess()) {
                handleSellFilled(bot, result, notificationType);
            } else {
                log.error("Bot {} SELL execution failed: {}", bot.getId(), result.getErrorMessage());
            }
        });
    }

    /**
     * Post-fill handler for BUY orders — persists order, position, and updates bot state.
     */
    private void handleBuyFilled(TradingBot bot, TradeRequest.TradeResult result) {
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

        bot.setHasOpenPosition(true);
        bot.setEntryPrice(result.getAvgPrice());
        bot.setQuantity(result.getExecutedQty());
        bot.setLastTradeTime(Instant.now());
        botRepo.save(bot);

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
        notificationService.notifyBuy(bot.getUserId().toString(), bot.getName(), bot.getSymbol(),
            result.getAvgPrice(), result.getExecutedQty());

        log.info("Bot {} [{}]: BUY filled via queue. Entry={}, Qty={}",
            bot.getId(), bot.getName(), result.getAvgPrice(), result.getExecutedQty());
    }

    /**
     * Post-fill handler for SELL orders — closes position, calculates PnL.
     */
    private void handleSellFilled(TradingBot bot, TradeRequest.TradeResult result, String notificationType) {
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

        trailingStopService.resetBot(bot.getId());
        publisher.publishOrderFilled(bot.getUserId().toString(), order);

        String userId = bot.getUserId().toString();
        switch (notificationType) {
            case "BOT_SL" -> notificationService.notifyStopLoss(userId, bot.getName(), bot.getSymbol(), result.getAvgPrice(), pnl);
            case "BOT_TP" -> notificationService.notifyTakeProfit(userId, bot.getName(), bot.getSymbol(), result.getAvgPrice(), pnl);
            case "BOT_TRAILING_SL" -> notificationService.notifyTrailingStop(userId, bot.getName(), bot.getSymbol(), result.getAvgPrice(), pnl);
            default -> notificationService.notifySell(userId, bot.getName(), bot.getSymbol(), result.getAvgPrice(), pnl);
        }

        log.info("Bot {} [{}]: SELL filled via queue. Exit={}, PnL={}",
            bot.getId(), bot.getName(), result.getAvgPrice(), pnl);
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
            log.warn("Bot {}: bad strategyParams JSON: {}", bot.getId(), e.getMessage());
            return new HashMap<>();
        }
    }
}
