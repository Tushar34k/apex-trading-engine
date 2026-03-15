package com.tradeengine.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.ExchangeSymbolRegistry;
import com.tradeengine.exchange.SymbolInfo;
import com.tradeengine.exchange.SymbolMapperService;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.model.*;
import com.tradeengine.repository.*;
import com.tradeengine.service.*;
import com.tradeengine.service.CandleCacheService;
import com.tradeengine.ws.MarketDataStreamService;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private final ExchangeFactory exchangeFactory;
    private final ExchangeSymbolRegistry symbolRegistry;
    private final SymbolMapperService symbolMapper;
    private final TradeEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final RiskManagementService riskService;
    private final TrailingStopService trailingStopService;
    private final NotificationService notificationService;
    private final MarketDataStreamService streamService;
    private final TradeExecutionQueue executionQueue;
    private final KillSwitchService killSwitch;
    private final SignalDebounceService debounceService;
    private final CircuitBreakerService circuitBreaker;
    private final PositionTracker positionTracker;
    private final CandleCacheService candleCacheService;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    private static final Duration TRADE_COOLDOWN = Duration.ofSeconds(60);

    // Bot processing locks — prevents concurrent execution per bot
    private final ConcurrentHashMap<UUID, Boolean> botLocks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 30000)
    public void runStrategies() {
        if (killSwitch.isActive()) {
            log.warn("[STRATEGY] Kill switch active — skipping all bots. Reason: {}", killSwitch.getActivationReason());
            return;
        }
        if (!circuitBreaker.isAllowed()) {
            log.warn("[STRATEGY] Circuit breaker open — trading paused");
            return;
        }

        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");

        for (TradingBot bot : runningBots) {
            if (botLocks.putIfAbsent(bot.getId(), true) != null) {
                log.debug("Bot {} locked by another cycle, skip", bot.getId());
                continue;
            }
            try {
                processBot(bot);
            } catch (Exception e) {
                log.error("Bot {} [{}] error: {}", bot.getId(), bot.getName(), e.getMessage(), e);
                bot.setProcessing(false);
                botRepo.save(bot);
            } finally {
                botLocks.remove(bot.getId());
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
            // Resolve exchange client dynamically from API key
            UserApiKey apiKey = apiKeyRepo.findById(bot.getApiKeyId())
                .orElseThrow(() -> new RuntimeException("API key not found for bot " + bot.getId()));

            String exchangeName = apiKey.getExchange();
            ExchangeClient exchangeClient = exchangeFactory.getClient(exchangeName);
            log.debug("Bot {} using exchange: {}", bot.getId(), exchangeName);

            String decryptedKey = apiKeyService.decryptApiKey(apiKey);
            String decryptedSecret = apiKeyService.decryptApiSecret(apiKey);
            String exchangeBaseUrl = resolveExchangeUrl(bot.getExchangeMode(), exchangeClient);

            // Resolve exchange-native symbol from universal format
            String exchangeSymbol = symbolMapper.resolveSymbol(exchangeName, bot.getSymbol());
            log.debug("Bot {} symbol resolved: {} → {} ({})", bot.getId(), bot.getSymbol(), exchangeSymbol, exchangeName);

            SymbolInfo symbolInfo = symbolRegistry.getOrFetch(exchangeName, exchangeSymbol, exchangeBaseUrl);
            Map<String, Object> params = parseStrategyParams(bot);

            // --- SL/TP/Trailing checks for open positions ---
            if (bot.isHasOpenPosition() && bot.getEntryPrice() != null) {
                Double freshPrice = streamService.getFreshPrice(exchangeName, exchangeSymbol);
                BigDecimal currentPrice;
                if (freshPrice != null) {
                    currentPrice = BigDecimal.valueOf(freshPrice);
                } else {
                    log.debug("Bot {} price stale/missing for {}, falling back to REST via {}", bot.getId(), exchangeSymbol, exchangeName);
                    currentPrice = exchangeClient.getPrice(exchangeSymbol, exchangeBaseUrl);
                }

                // Trailing stop check
                if (params.containsKey("trailingStopPercent")) {
                    double tsPct = ((Number) params.get("trailingStopPercent")).doubleValue();
                    if (trailingStopService.checkTrailingStop(bot.getId(), currentPrice, bot.getEntryPrice(), tsPct)) {
                        log.warn("Bot {} TRAILING STOP triggered @ {}", bot.getId(), currentPrice);
                        submitSell(bot, decryptedKey, decryptedSecret, currentPrice,
                            "Trailing stop triggered", exchangeBaseUrl, symbolInfo, "BOT_TRAILING_SL", exchangeName, exchangeSymbol);
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
                            "Stop-loss triggered at " + slPct + "%", exchangeBaseUrl, symbolInfo, "BOT_SL", exchangeName, exchangeSymbol);
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
                            "Take-profit triggered at " + tpPct + "%", exchangeBaseUrl, symbolInfo, "BOT_TP", exchangeName, exchangeSymbol);
                        return;
                    }
                }
            }

            // --- Fetch candles via exchange client (with cache) ---
            String entryTf = params.containsKey("entryTf") ? params.get("entryTf").toString() : bot.getTimeframe();
            String trendTfParam = params.containsKey("trendTf") ? params.get("trendTf").toString() : "15m";
            String macroTfParam = params.containsKey("macroTf") ? params.get("macroTf").toString() : "1h";

            List<double[]> candles = candleCacheService.getCandles(
                exchangeClient, decryptedKey, decryptedSecret, exchangeSymbol, entryTf, 300, exchangeBaseUrl);
            if (candles.size() < 50) {
                log.warn("Bot {}: only {} candles, need 50+. Skip.", bot.getId(), candles.size());
                return;
            }

            List<Double> closingPrices = candles.stream().map(c -> c[4]).collect(Collectors.toList());

            // Fetch higher timeframe candles for multi-TF confirmation (with fallback)
            List<double[]> trendCandles = null;
            List<double[]> macroCandles = null;
            if ("ENHANCED_EMA".equals(bot.getStrategyType())) {
                trendCandles = candleCacheService.getCandles(
                    exchangeClient, decryptedKey, decryptedSecret, exchangeSymbol, trendTfParam, 300, exchangeBaseUrl);
                macroCandles = candleCacheService.getCandles(
                    exchangeClient, decryptedKey, decryptedSecret, exchangeSymbol, macroTfParam, 300, exchangeBaseUrl);

                if (trendCandles.isEmpty()) {
                    log.warn("Bot {} [{}]: failed to fetch {}m candles, falling back to entry-only", bot.getId(), bot.getName(), trendTfParam);
                }
                if (macroCandles.isEmpty()) {
                    log.warn("Bot {} [{}]: failed to fetch {} candles, falling back to entry-only", bot.getId(), bot.getName(), macroTfParam);
                }
            }

            // Inject order book depth
            double[] depth = streamService.getDepth(exchangeName, exchangeSymbol);
            if (depth != null) {
                params.put("bidVolume", depth[0]);
                params.put("askVolume", depth[1]);
            }

            // --- Evaluate strategy (multi-TF if available) ---
            TradingStrategy strategy = StrategyFactory.get(bot.getStrategyType());
            params.putIfAbsent("fastEma", bot.getFastEma());
            params.putIfAbsent("slowEma", bot.getSlowEma());

            TradingStrategy.SignalResult signal = strategy.evaluate(
                closingPrices, candles, trendCandles, macroCandles, params, bot.isHasOpenPosition());

            log.info("Bot {} [{}] exchange={} strategy={} signal={} reason={}",
                bot.getId(), bot.getName(), exchangeName, bot.getStrategyType(), signal.signal(), signal.reason());

            // --- Signal debounce ---
            if (signal.signal() != TradingStrategy.Signal.HOLD) {
                if (!debounceService.shouldProcess(bot.getId(), signal.signal().name())) {
                    log.debug("Bot {} signal {} debounced", bot.getId(), signal.signal());
                    return;
                }
            }

            if (signal.signal() == TradingStrategy.Signal.BUY && !bot.isHasOpenPosition()) {
                // If strategy provides SL/TP (e.g. ENHANCED_EMA), inject into params
                if (signal.stopLoss() != null) {
                    double slPercent = Math.abs(signal.price() - signal.stopLoss()) / signal.price() * 100;
                    params.put("stopLossPercent", slPercent);
                }
                if (signal.takeProfit() != null) {
                    double tpPercent = Math.abs(signal.takeProfit() - signal.price()) / signal.price() * 100;
                    params.put("takeProfitPercent", tpPercent);
                }
                params.put("__signalPrice", signal.price());
                submitBuy(bot, decryptedKey, decryptedSecret, signal, exchangeBaseUrl, symbolInfo, params, exchangeName, exchangeClient, exchangeSymbol);
            } else if (signal.signal() == TradingStrategy.Signal.SELL && bot.isHasOpenPosition()) {
                submitSell(bot, decryptedKey, decryptedSecret,
                    BigDecimal.valueOf(signal.price()), signal.reason(),
                    exchangeBaseUrl, symbolInfo, "BOT_SELL", exchangeName, exchangeSymbol);
            }
        } finally {
            bot.setProcessing(false);
            botRepo.save(bot);
        }
    }

    private void submitBuy(TradingBot bot, String apiKey, String secret,
                           TradingStrategy.SignalResult signal, String exchangeBaseUrl,
                           SymbolInfo symbolInfo, Map<String, Object> params,
                           String exchangeName, ExchangeClient exchangeClient, String exchangeSymbol) {
        if (killSwitch.isActive()) {
            log.warn("Bot {} BUY blocked — kill switch active", bot.getId());
            return;
        }

        log.info("Executing trade on exchange: {} for botId={} symbol={}", exchangeName, bot.getId(), exchangeSymbol);

        var balanceList = exchangeClient.getBalances(apiKey, secret, exchangeBaseUrl);
        BigDecimal usdtBalance = balanceList.stream()
            .filter(b -> "USDT".equals(b.getAsset()))
            .map(com.tradeengine.exchange.Balance::getFree)
            .findFirst()
            .orElse(BigDecimal.ZERO);

        RiskManagementService.RiskCheck riskCheck = riskService.validateBuy(bot, usdtBalance, params);
        if (!riskCheck.allowed()) {
            log.warn("Bot {} RISK BLOCKED: {}", bot.getId(), riskCheck.reason());
            notificationService.notifyRiskBlocked(bot.getUserId().toString(), bot.getName(), bot.getSymbol(), riskCheck.reason());
            return;
        }

        // --- Funding rate safety filter ---
        double maxFundingRate = getDoubleParam(params, "maxFundingRate", 0.001); // default 0.1%
        try {
            BigDecimal fundingRate = exchangeClient.getFundingRate(exchangeSymbol, exchangeBaseUrl);
            if (fundingRate != null && fundingRate.abs().doubleValue() > maxFundingRate) {
                log.warn("[RISK_REJECTED] Bot {} funding rate {} exceeds max {} for {}",
                    bot.getId(), fundingRate, maxFundingRate, exchangeSymbol);
                notificationService.notifyRiskBlocked(bot.getUserId().toString(), bot.getName(), bot.getSymbol(),
                    "Funding rate " + fundingRate + " exceeds safety limit " + maxFundingRate);
                return;
            }
        } catch (Exception e) {
            log.warn("Bot {} funding rate check failed (proceeding): {}", bot.getId(), e.getMessage());
        }

        // --- Spread guard (exchange-level, applies to ALL strategies) ---
        double maxSpreadPercent = getDoubleParam(params, "maxSpreadPercent", 0.2); // default 0.2%
        double[] depth = streamService.getDepth(exchangeName, exchangeSymbol);
        if (depth != null && depth[0] > 0 && depth[1] > 0) {
            // Estimate spread from order book: best bid ≈ price * (bidVol/(bidVol+askVol))
            // More robust: use actual price and check bid-ask imbalance as spread proxy
            BigDecimal price = BigDecimal.valueOf(signal.price());
            // If bid volume is very low relative to ask, spread is likely wide
            double bidAskRatio = depth[0] / (depth[0] + depth[1]);
            // Approximate spread: when ratio deviates significantly from 0.5, spread is wide
            double impliedSpreadPct = Math.abs(0.5 - bidAskRatio) * 4 * 100; // scale to percentage
            if (impliedSpreadPct > maxSpreadPercent) {
                log.warn("[RISK_REJECTED] Bot {} spread too wide: implied={}% max={}% bidVol={} askVol={}",
                    bot.getId(), String.format("%.4f", impliedSpreadPct), maxSpreadPercent, depth[0], depth[1]);
                notificationService.notifyRiskBlocked(bot.getUserId().toString(), bot.getName(), bot.getSymbol(),
                    "Spread too wide: " + String.format("%.4f%%", impliedSpreadPct));
                return;
            }
        }

        BigDecimal currentPrice = BigDecimal.valueOf(signal.price());
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        // --- Risk-based position sizing ---
        // If SL is available, size position so max loss = riskPercent% of balance
        double riskPercent = getDoubleParam(params, "riskPercentPerTrade", 1.0); // default 1%
        BigDecimal quantity;

        if (signal.stopLoss() != null && signal.stopLoss() > 0) {
            BigDecimal riskAmount = usdtBalance.multiply(BigDecimal.valueOf(riskPercent / 100));
            BigDecimal slDistance = currentPrice.subtract(BigDecimal.valueOf(signal.stopLoss())).abs();
            if (slDistance.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal riskBasedQty = riskAmount.divide(slDistance, 8, RoundingMode.DOWN);
                // Also cap by tradeSizePercent allocation
                BigDecimal allocationAmount = usdtBalance.multiply(bot.getTradeSizePercent())
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
                BigDecimal maxQty = allocationAmount.divide(currentPrice, 8, RoundingMode.DOWN);
                quantity = riskBasedQty.min(maxQty);
                log.info("[RISK_SIZING] botId={} riskAmt={} slDist={} riskQty={} maxQty={} finalQty={}",
                    bot.getId(), riskAmount, slDistance, riskBasedQty, maxQty, quantity);
            } else {
                BigDecimal allocationAmount = usdtBalance.multiply(bot.getTradeSizePercent())
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
                quantity = allocationAmount.divide(currentPrice, 8, RoundingMode.DOWN);
            }
        } else {
            BigDecimal allocationAmount = usdtBalance.multiply(bot.getTradeSizePercent())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            quantity = allocationAmount.divide(currentPrice, 8, RoundingMode.DOWN);
        }

        quantity = symbolInfo != null ? symbolInfo.roundQuantity(quantity) : quantity.setScale(6, RoundingMode.DOWN);
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

        log.info("[TRADE_SIGNAL] botId={} symbol={} side=BUY qty={} price={} exchange={} confidence={}",
            bot.getId(), exchangeSymbol, quantity, currentPrice, exchangeName,
            signal.confidence() != null ? signal.confidence() : "N/A");

        TradeRequest request = TradeRequest.builder()
            .botId(bot.getId())
            .userId(bot.getUserId())
            .symbol(exchangeSymbol)
            .side("BUY")
            .quantity(quantity)
            .orderType("MARKET")
            .apiKey(apiKey)
            .apiSecret(secret)
            .exchangeBaseUrl(exchangeBaseUrl)
            .exchange(exchangeName)
            .exchangeMode(bot.getExchangeMode())
            .notificationType("BOT_BUY")
            .timestamp(Instant.now())
            .build();

        executionQueue.submitTrade(request);

        final Map<String, Object> riskParams = new HashMap<>(params);
        final String exName = exchangeName;
        final String exMode = bot.getExchangeMode();
        final String exBaseUrl = exchangeBaseUrl;

        request.getResultFuture()
            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .thenAccept(result -> {
                if (result.isSuccess()) {
                    killSwitch.recordTradeSuccess();
                    handleBuyFilled(bot, result, apiKey, secret, exName, exMode, exBaseUrl, riskParams, exchangeSymbol);
                } else {
                    log.error("Bot {} BUY execution failed: {}", bot.getId(), result.getErrorMessage());
                    killSwitch.recordTradeFailure();
                    circuitBreaker.recordFailure();
                    killSwitch.recordExchangeError();
                }
            })
            .exceptionally(ex -> {
                log.error("Bot {} BUY execution timed out: {}", bot.getId(), ex.getMessage());
                killSwitch.recordTradeFailure();
                circuitBreaker.recordFailure();
                killSwitch.recordExchangeError();
                return null;
            });
    }

    private void submitSell(TradingBot bot, String apiKey, String secret,
                            BigDecimal currentPrice, String reason,
                            String exchangeBaseUrl, SymbolInfo symbolInfo,
                            String notificationType, String exchangeName, String exchangeSymbol) {
        if (bot.getQuantity() == null || bot.getQuantity().compareTo(BigDecimal.ZERO) <= 0) return;

        if (killSwitch.isActive() && "BOT_SELL".equals(notificationType)) {
            log.warn("Bot {} SELL blocked — kill switch active", bot.getId());
            return;
        }

        BigDecimal quantity = bot.getQuantity();
        if (symbolInfo != null) {
            quantity = symbolInfo.roundQuantity(quantity);
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Bot {}: sell quantity rounds to 0", bot.getId());
                return;
            }
        }

        log.info("Bot {} [{}]: Submitting SELL {} {} @ ~{} ({}) to execution queue via {}",
            bot.getId(), bot.getName(), quantity, exchangeSymbol, currentPrice, notificationType, exchangeName);

        TradeRequest request = TradeRequest.builder()
            .botId(bot.getId())
            .userId(bot.getUserId())
            .symbol(exchangeSymbol)
            .side("SELL")
            .quantity(quantity)
            .orderType("MARKET")
            .apiKey(apiKey)
            .apiSecret(secret)
            .exchangeBaseUrl(exchangeBaseUrl)
            .exchange(exchangeName)
            .exchangeMode(bot.getExchangeMode())
            .notificationType(notificationType)
            .timestamp(Instant.now())
            .build();

        executionQueue.submitTrade(request);

        final BigDecimal triggerPrice = currentPrice;
        request.getResultFuture()
            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .thenAccept(result -> {
                if (result.isSuccess()) {
                    killSwitch.recordTradeSuccess();
                    handleSellFilled(bot, result, notificationType, triggerPrice);
                } else {
                    log.error("Bot {} SELL execution failed: {}", bot.getId(), result.getErrorMessage());
                    killSwitch.recordTradeFailure();
                    circuitBreaker.recordFailure();
                    killSwitch.recordExchangeError();
                }
            })
            .exceptionally(ex -> {
                log.error("Bot {} SELL execution timed out: {}", bot.getId(), ex.getMessage());
                killSwitch.recordTradeFailure();
                circuitBreaker.recordFailure();
                killSwitch.recordExchangeError();
                return null;
            });
    }

    private void handleBuyFilled(TradingBot bot, TradeRequest.TradeResult result,
                                   String apiKey, String apiSecret,
                                   String exchangeName, String exchangeMode, String exchangeBaseUrl,
                                   Map<String, Object> params, String exchangeSymbol) {
        // Reload bot from DB to avoid race condition with async callback
        TradingBot freshBot = botRepo.findById(bot.getId()).orElse(bot);

        TradeOrder order = new TradeOrder();
        order.setBotId(freshBot.getId());
        order.setUserId(freshBot.getUserId());
        order.setExchangeOrderId(result.getOrderId());
        order.setSymbol(freshBot.getSymbol());
        order.setSide("BUY");
        order.setType("MARKET");
        order.setQuantity(result.getExecutedQty());
        order.setFilledQuantity(result.getExecutedQty());
        order.setAvgFillPrice(result.getAvgPrice());
        order.setStatus("FILLED");
        order.setFilledAt(Instant.now());
        orderRepo.save(order);

        log.info("[TRADE_EXECUTED] botId={} symbol={} side=BUY qty={} avgPrice={} orderId={}",
            freshBot.getId(), exchangeSymbol, result.getExecutedQty(), result.getAvgPrice(), result.getOrderId());

        freshBot.setHasOpenPosition(true);
        freshBot.setEntryPrice(result.getAvgPrice());
        freshBot.setQuantity(result.getExecutedQty());
        freshBot.setLastTradeTime(Instant.now());
        botRepo.save(freshBot);

        TradePosition position = new TradePosition();
        position.setBotId(freshBot.getId());
        position.setUserId(freshBot.getUserId());
        position.setSymbol(freshBot.getSymbol());
        position.setExchange(exchangeName);
        position.setSide("LONG");
        position.setQuantity(result.getExecutedQty());
        position.setEntryPrice(result.getAvgPrice());
        position.setCurrentPrice(result.getAvgPrice());
        positionRepo.save(position);

        // --- Slippage guard on entry ---
        BigDecimal expectedPrice = BigDecimal.valueOf(
            params.containsKey("__signalPrice") ? ((Number) params.get("__signalPrice")).doubleValue() : 0);
        if (expectedPrice.compareTo(BigDecimal.ZERO) > 0 && result.getAvgPrice() != null) {
            BigDecimal entrySlippage = result.getAvgPrice().subtract(expectedPrice).abs()
                .divide(expectedPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            if (entrySlippage.doubleValue() > 0.5) {
                log.warn("[SLIPPAGE_WARNING] botId={} symbol={} expected={} filled={} slippage={}%",
                    freshBot.getId(), exchangeSymbol, expectedPrice, result.getAvgPrice(), entrySlippage);
            }
        }

        // --- Calculate SL/TP prices ---
        double defaultSlPercent = 0.7;
        double defaultTpPercent = 1.4;
        BigDecimal slPrice = params.containsKey("stopLossPercent")
            ? result.getAvgPrice().multiply(BigDecimal.ONE.subtract(
                BigDecimal.valueOf(((Number) params.get("stopLossPercent")).doubleValue() / 100)))
            : result.getAvgPrice().multiply(BigDecimal.ONE.subtract(
                BigDecimal.valueOf(defaultSlPercent / 100)));
        BigDecimal tpPrice = params.containsKey("takeProfitPercent")
            ? result.getAvgPrice().multiply(BigDecimal.ONE.add(
                BigDecimal.valueOf(((Number) params.get("takeProfitPercent")).doubleValue() / 100)))
            : result.getAvgPrice().multiply(BigDecimal.ONE.add(
                BigDecimal.valueOf(defaultTpPercent / 100)));
        BigDecimal trailingPct = params.containsKey("trailingStopPercent")
            ? BigDecimal.valueOf(((Number) params.get("trailingStopPercent")).doubleValue())
            : null;

        // Round SL/TP to exchange tick size (not hardcoded 2dp)
        SymbolInfo symInfo = symbolRegistry.getOrFetch(exchangeName, exchangeSymbol, exchangeBaseUrl);
        if (symInfo != null) {
            slPrice = symInfo.roundPrice(slPrice);
            tpPrice = symInfo.roundPrice(tpPrice);
        }

        // --- Place exchange-side protective orders (STOP_MARKET + TAKE_PROFIT_MARKET) ---
        try {
            ExchangeClient client = exchangeFactory.getClient(exchangeName);

            // STOP_MARKET for stop loss (reduceOnly=true built into the method)
            OrderResponse slOrder = client.placeStopMarketOrder(
                apiKey, apiSecret, exchangeSymbol, "SELL",
                result.getExecutedQty(), slPrice, exchangeBaseUrl);
            log.info("[STOP_LOSS_PLACED] botId={} symbol={} stopPrice={} orderId={}",
                freshBot.getId(), exchangeSymbol, slPrice, slOrder.getOrderId());

            // TAKE_PROFIT_MARKET for take profit (reduceOnly=true built into the method)
            OrderResponse tpOrder = client.placeTakeProfitMarketOrder(
                apiKey, apiSecret, exchangeSymbol, "SELL",
                result.getExecutedQty(), tpPrice, exchangeBaseUrl);
            log.info("[TAKE_PROFIT_PLACED] botId={} symbol={} stopPrice={} orderId={}",
                freshBot.getId(), exchangeSymbol, tpPrice, tpOrder.getOrderId());

        } catch (Exception e) {
            log.error("[PROTECTIVE_ORDER_FAILED] botId={} symbol={} error={} — falling back to PositionRiskManager monitoring",
                freshBot.getId(), exchangeSymbol, e.getMessage());
            // Fall back to internal monitoring — PositionRiskManager will still watch this position
        }

        // Register with PositionTracker for real-time risk monitoring (backup to exchange orders)
        positionTracker.registerPosition(PositionTracker.TrackedPosition.builder()
            .botId(freshBot.getId())
            .userId(freshBot.getUserId())
            .symbol(freshBot.getSymbol())
            .side("LONG")
            .exchange(exchangeName)
            .exchangeMode(exchangeMode)
            .entryPrice(result.getAvgPrice())
            .quantity(result.getExecutedQty())
            .apiKey(apiKey)
            .apiSecret(apiSecret)
            .exchangeBaseUrl(exchangeBaseUrl)
            .stopLossPrice(slPrice)
            .takeProfitPrice(tpPrice)
            .trailingStopPercent(trailingPct)
            .highestPriceSeen(result.getAvgPrice())
            .lowestPriceSeen(result.getAvgPrice())
            .openedAt(Instant.now())
            .build());

        publisher.publishOrderFilled(freshBot.getUserId().toString(), order);
        publisher.publishPositionOpened(freshBot.getUserId().toString(), position);
        notificationService.notifyBuy(freshBot.getUserId().toString(), freshBot.getName(), freshBot.getSymbol(),
            result.getAvgPrice(), result.getExecutedQty());

        log.info("Bot {} [{}]: BUY filled via queue. Entry={}, Qty={}, SL={}, TP={}",
            freshBot.getId(), freshBot.getName(), result.getAvgPrice(), result.getExecutedQty(),
            slPrice.setScale(2, RoundingMode.HALF_UP), tpPrice.setScale(2, RoundingMode.HALF_UP));
    }

    private void handleSellFilled(TradingBot bot, TradeRequest.TradeResult result, String notificationType, BigDecimal currentPrice) {
        // Reload bot from DB to avoid race condition with async callback
        TradingBot freshBot = botRepo.findById(bot.getId()).orElse(bot);

        TradeOrder order = new TradeOrder();
        order.setBotId(freshBot.getId());
        order.setUserId(freshBot.getUserId());
        order.setExchangeOrderId(result.getOrderId());
        order.setSymbol(freshBot.getSymbol());
        order.setSide("SELL");
        order.setType("MARKET");
        order.setQuantity(result.getExecutedQty());
        order.setFilledQuantity(result.getExecutedQty());
        order.setAvgFillPrice(result.getAvgPrice());
        order.setStatus("FILLED");
        order.setFilledAt(Instant.now());
        orderRepo.save(order);

        // --- Slippage guard (compare fill price to the current market price that triggered the exit) ---
        PositionTracker.TrackedPosition trackedPos = positionTracker.getPosition(freshBot.getId()).orElse(null);
        if (result.getAvgPrice() != null && currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal slippage = result.getAvgPrice().subtract(currentPrice).abs()
                .divide(currentPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            if (slippage.doubleValue() > 0.5) {
                log.warn("[SLIPPAGE_WARNING] botId={} symbol={} triggerPrice={} fillPrice={} slippage={}%",
                    freshBot.getId(), freshBot.getSymbol(), currentPrice, result.getAvgPrice(), slippage);
            }
        }

        BigDecimal pnl = BigDecimal.ZERO;
        if (freshBot.getEntryPrice() != null) {
            pnl = result.getAvgPrice().subtract(freshBot.getEntryPrice()).multiply(freshBot.getQuantity());
        }

        // Search by botId + OPEN status (more reliable than symbol match for multi-exchange)
        TradePosition position = positionRepo
            .findFirstByBotIdAndStatusOrderByOpenedAtDesc(freshBot.getId(), "OPEN").orElse(null);
        if (position != null) {
            position.setStatus("CLOSED");
            position.setExitPrice(result.getAvgPrice());
            position.setRealizedPnl(pnl);
            position.setClosedAt(Instant.now());
            positionRepo.save(position);
            publisher.publishPositionClosed(freshBot.getUserId().toString(), position, pnl);
        }

        // --- Cancel orphan exchange-side SL/TP orders ---
        cancelOrphanProtectiveOrders(freshBot, trackedPos);

        freshBot.setHasOpenPosition(false);
        freshBot.setEntryPrice(null);
        freshBot.setQuantity(null);
        freshBot.setLastTradeTime(Instant.now());
        botRepo.save(freshBot);

        trailingStopService.resetBot(freshBot.getId());
        positionTracker.removePosition(freshBot.getId());
        publisher.publishOrderFilled(freshBot.getUserId().toString(), order);

        String userId = freshBot.getUserId().toString();
        switch (notificationType) {
            case "BOT_SL" -> notificationService.notifyStopLoss(userId, freshBot.getName(), freshBot.getSymbol(), result.getAvgPrice(), pnl);
            case "BOT_TP" -> notificationService.notifyTakeProfit(userId, freshBot.getName(), freshBot.getSymbol(), result.getAvgPrice(), pnl);
            case "BOT_TRAILING_SL" -> notificationService.notifyTrailingStop(userId, freshBot.getName(), freshBot.getSymbol(), result.getAvgPrice(), pnl);
            default -> notificationService.notifySell(userId, freshBot.getName(), freshBot.getSymbol(), result.getAvgPrice(), pnl);
        }

        log.info("Bot {} [{}]: SELL filled via queue. Exit={}, PnL={}",
            freshBot.getId(), freshBot.getName(), result.getAvgPrice(), pnl);
    }

    /**
     * Cancel all open STOP_MARKET and TAKE_PROFIT_MARKET orders for a symbol when a position closes.
     * Prevents orphan protective orders from triggering on future positions.
     */
    private void cancelOrphanProtectiveOrders(TradingBot bot, PositionTracker.TrackedPosition trackedPos) {
        if (trackedPos == null) return;

        try {
            ExchangeClient client = exchangeFactory.getClient(trackedPos.getExchange());
            String exchangeSymbol = symbolMapper.resolveSymbol(trackedPos.getExchange(), bot.getSymbol());

            List<com.fasterxml.jackson.databind.JsonNode> openOrders = client.getOpenOrders(
                trackedPos.getApiKey(), trackedPos.getApiSecret(), exchangeSymbol, trackedPos.getExchangeBaseUrl());

            int cancelled = 0;
            for (com.fasterxml.jackson.databind.JsonNode order : openOrders) {
                String type = order.path("type").asText("");
                if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                    String orderId = order.path("orderId").asText();
                    try {
                        client.cancelOrder(trackedPos.getApiKey(), trackedPos.getApiSecret(),
                            exchangeSymbol, orderId, trackedPos.getExchangeBaseUrl());
                        cancelled++;
                        log.info("[ORPHAN_ORDER_CANCELLED] botId={} symbol={} type={} orderId={}",
                            bot.getId(), exchangeSymbol, type, orderId);
                    } catch (Exception e) {
                        log.warn("[ORPHAN_ORDER_CANCEL_FAILED] botId={} orderId={} error={}", bot.getId(), orderId, e.getMessage());
                    }
                }
            }
            if (cancelled > 0) {
                log.info("[ORPHAN_CLEANUP] botId={} symbol={} cancelled {} protective orders", bot.getId(), exchangeSymbol, cancelled);
            }
        } catch (Exception e) {
            log.error("[ORPHAN_CLEANUP_FAILED] botId={} error={} — manual cleanup may be required", bot.getId(), e.getMessage());
        }
    }

    private String resolveExchangeUrl(String exchangeMode, ExchangeClient exchangeClient) {
        if ("LIVE".equalsIgnoreCase(exchangeMode)) {
            if (!liveTradingEnabled) {
                log.warn("Live trading disabled. Forcing TESTNET for {}", exchangeClient.getExchangeName());
                return exchangeClient.resolveBaseUrl("TESTNET");
            }
            return exchangeClient.resolveBaseUrl("LIVE");
        }
        return exchangeClient.resolveBaseUrl("TESTNET");
    }

    private Map<String, Object> parseStrategyParams(TradingBot bot) {
        if (bot.getStrategyParams() == null || bot.getStrategyParams().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(bot.getStrategyParams(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Bot {}: invalid strategyParams JSON", bot.getId());
            return new HashMap<>();
        }
    }

    private double getDoubleParam(Map<String, Object> params, String key, double defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultVal;
    }
}
