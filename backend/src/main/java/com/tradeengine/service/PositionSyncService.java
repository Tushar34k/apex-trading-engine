package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.ExchangePosition;
import com.tradeengine.exchange.SymbolMapperService;
import com.tradeengine.model.TradingBot;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Periodically reconciles PositionTracker with real exchange state.
 *
 * Handles:
 *   CASE A — Bot thinks position OPEN but exchange shows none → mark CLOSED
 *   CASE B — Exchange shows position but bot has none → register as EXTERNAL_POSITION
 *   CASE C — Position size mismatch → update internal size
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSyncService {

    private final PositionTracker positionTracker;
    private final BotRepository botRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ExchangeFactory exchangeFactory;
    private final SymbolMapperService symbolMapper;
    private final NotificationService notificationService;

    /**
     * Scheduled sync — runs every 10 seconds.
     */
    @Scheduled(fixedDelay = 10_000)
    public void syncPositions() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");
        if (runningBots.isEmpty()) return;

        log.debug("[POSITION_SYNC_START] Syncing {} running bots", runningBots.size());

        // Group bots by (apiKeyId + exchangeMode) to minimize API calls
        Map<String, List<TradingBot>> grouped = runningBots.stream()
            .collect(Collectors.groupingBy(b -> b.getApiKeyId() + ":" + b.getExchangeMode()));

        for (var entry : grouped.entrySet()) {
            List<TradingBot> bots = entry.getValue();
            TradingBot representative = bots.get(0);

            try {
                syncBotGroup(bots, representative);
            } catch (Exception e) {
                log.error("[POSITION_SYNC] Failed for apiKey={} mode={}: {}",
                    representative.getApiKeyId(), representative.getExchangeMode(), e.getMessage());
            }
        }
    }

    private void syncBotGroup(List<TradingBot> bots, TradingBot representative) {
        // Resolve API credentials
        Optional<UserApiKey> apiKeyOpt = apiKeyRepo.findById(representative.getApiKeyId());
        if (apiKeyOpt.isEmpty()) {
            log.warn("[POSITION_SYNC] API key {} not found, skipping", representative.getApiKeyId());
            return;
        }

        UserApiKey apiKeyEntity = apiKeyOpt.get();
        String apiKey = apiKeyService.decryptApiKey(apiKeyEntity);
        String secret = apiKeyService.decryptApiSecret(apiKeyEntity);
        String exchangeName = apiKeyEntity.getExchange().toUpperCase();

        ExchangeClient client = exchangeFactory.getClient(exchangeName);
        String baseUrl = client.resolveBaseUrl(representative.getExchangeMode());

        // Fetch all exchange positions in one call
        List<ExchangePosition> exchangePositions = client.getOpenPositions(apiKey, secret, null, baseUrl);

        // Build lookup: exchange-native symbol → ExchangePosition
        Map<String, ExchangePosition> exchangeMap = new HashMap<>();
        for (ExchangePosition ep : exchangePositions) {
            exchangeMap.put(ep.getSymbol().toUpperCase(), ep);
        }

        // Track which exchange positions we've matched
        Set<String> matchedSymbols = new HashSet<>();

        for (TradingBot bot : bots) {
            // Resolve the exchange-native symbol for this bot
            String exchangeSymbol;
            try {
                exchangeSymbol = symbolMapper.resolveSymbol(exchangeName, bot.getSymbol());
            } catch (Exception e) {
                exchangeSymbol = bot.getSymbol().toUpperCase();
            }

            PositionTracker.TrackedPosition internal = positionTracker.getPosition(bot.getId()).orElse(null);
            ExchangePosition external = exchangeMap.get(exchangeSymbol.toUpperCase());

            if (external != null) {
                matchedSymbols.add(exchangeSymbol.toUpperCase());
            }

            reconcile(bot, internal, external, exchangeSymbol, exchangeName, baseUrl, apiKey, secret);
        }

        // CASE B — Exchange positions with no matching bot (external/manual trades)
        for (ExchangePosition unmatched : exchangePositions) {
            if (!matchedSymbols.contains(unmatched.getSymbol().toUpperCase())) {
                log.warn("[POSITION_SYNC_MISMATCH] CASE_B: External position detected — " +
                    "exchange={} symbol={} side={} size={} entryPrice={}. No bot owns this position.",
                    unmatched.getExchange(), unmatched.getSymbol(),
                    unmatched.getSide(), unmatched.getSize(), unmatched.getEntryPrice());
            }
        }
    }

    private void reconcile(TradingBot bot,
                           PositionTracker.TrackedPosition internal,
                           ExchangePosition external,
                           String exchangeSymbol,
                           String exchangeName,
                           String baseUrl,
                           String apiKey,
                           String secret) {

        boolean hasInternal = internal != null;
        boolean hasExternal = external != null && external.getSize().compareTo(BigDecimal.ZERO) > 0;

        // ─── CASE A: Bot thinks OPEN, exchange shows NONE ───
        if (hasInternal && !hasExternal) {
            log.warn("[POSITION_SYNC_MISMATCH] CASE_A: Phantom position detected — " +
                "botId={} symbol={} internalQty={}. Exchange shows no position. Marking CLOSED.",
                bot.getId(), exchangeSymbol, internal.getQuantity());

            // Cancel orphan exchange-side SL/TP orders BEFORE removing internal state
            cancelOrphanOrders(bot, internal, exchangeName, exchangeSymbol, apiKey, secret, baseUrl);

            positionTracker.removePosition(bot.getId());

            // Update bot state
            bot.setHasOpenPosition(false);
            bot.setEntryPrice(null);
            bot.setQuantity(null);
            botRepo.save(bot);

            notificationService.notifyRiskBlocked(
                bot.getUserId().toString(), bot.getName(), bot.getSymbol(),
                "Position sync: position no longer exists on exchange");

            log.info("[POSITION_SYNC_RECOVERED] botId={} phantom position cleared", bot.getId());
            return;
        }

        // ─── CASE B: Exchange shows position, bot has NONE ───
        if (!hasInternal && hasExternal) {
            log.warn("[POSITION_SYNC_MISMATCH] CASE_B: Untracked exchange position — " +
                "botId={} symbol={} exchangeSide={} exchangeSize={} entryPrice={}. Registering internally.",
                bot.getId(), exchangeSymbol, external.getSide(), external.getSize(), external.getEntryPrice());

            PositionTracker.TrackedPosition newPos = PositionTracker.TrackedPosition.builder()
                .botId(bot.getId())
                .userId(bot.getUserId())
                .symbol(exchangeSymbol)
                .side(external.getSide()) // preserve LONG/SHORT from exchange
                .exchange(exchangeName)
                .exchangeMode(bot.getExchangeMode())
                .entryPrice(external.getEntryPrice())
                .quantity(external.getSize())
                .apiKey(apiKey)
                .apiSecret(secret)
                .exchangeBaseUrl(baseUrl)
                .highestPriceSeen(external.getEntryPrice())
                .lowestPriceSeen(external.getEntryPrice())
                .openedAt(Instant.now())
                .build();

            positionTracker.registerPosition(newPos);

            // Update bot state
            bot.setHasOpenPosition(true);
            bot.setEntryPrice(external.getEntryPrice());
            bot.setQuantity(external.getSize());
            botRepo.save(bot);

            log.info("[POSITION_SYNC_RECOVERED] botId={} external position registered (EXTERNAL_POSITION)", bot.getId());
            return;
        }

        // ─── CASE C: Both exist but size differs ───
        if (hasInternal && hasExternal) {
            if (internal.getQuantity().compareTo(external.getSize()) != 0) {
                log.warn("[POSITION_SYNC_MISMATCH] CASE_C: Size mismatch — " +
                    "botId={} symbol={} internalSize={} exchangeSize={}. Updating internal.",
                    bot.getId(), exchangeSymbol, internal.getQuantity(), external.getSize());

                // Remove old and register corrected
                positionTracker.removePosition(bot.getId());

                PositionTracker.TrackedPosition corrected = PositionTracker.TrackedPosition.builder()
                    .botId(bot.getId())
                    .userId(bot.getUserId())
                    .symbol(internal.getSymbol())
                    .side(internal.getSide() != null ? internal.getSide() : external.getSide())
                    .exchange(internal.getExchange())
                    .exchangeMode(internal.getExchangeMode())
                    .entryPrice(external.getEntryPrice())
                    .quantity(external.getSize())
                    .apiKey(internal.getApiKey())
                    .apiSecret(internal.getApiSecret())
                    .exchangeBaseUrl(internal.getExchangeBaseUrl())
                    .stopLossPrice(internal.getStopLossPrice())
                    .takeProfitPrice(internal.getTakeProfitPrice())
                    .trailingStopPercent(internal.getTrailingStopPercent())
                    .highestPriceSeen(internal.getHighestPriceSeen())
                    .lowestPriceSeen(internal.getLowestPriceSeen())
                    .openedAt(internal.getOpenedAt())
                    .build();

                positionTracker.registerPosition(corrected);

                // Update bot
                bot.setQuantity(external.getSize());
                bot.setEntryPrice(external.getEntryPrice());
                botRepo.save(bot);

                log.info("[POSITION_SYNC_RECOVERED] botId={} size corrected {} → {}",
                    bot.getId(), internal.getQuantity(), external.getSize());
            }
        }

        // Both null — no action needed
    }

    /**
     * Cancel orphan exchange-side SL/TP orders when position sync detects a phantom position.
     * This prevents orphan STOP_MARKET/TAKE_PROFIT_MARKET orders from triggering on future positions.
     */
    private void cancelOrphanOrders(TradingBot bot, PositionTracker.TrackedPosition internal,
                                     String exchangeName, String exchangeSymbol,
                                     String apiKey, String secret, String baseUrl) {
        try {
            ExchangeClient client = exchangeFactory.getClient(exchangeName);
            List<com.fasterxml.jackson.databind.JsonNode> openOrders = client.getOpenOrders(
                apiKey, secret, exchangeSymbol, baseUrl);

            int cancelled = 0;
            for (com.fasterxml.jackson.databind.JsonNode order : openOrders) {
                String type = order.path("type").asText("");
                if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                    String orderId = order.path("orderId").asText();
                    try {
                        client.cancelOrder(apiKey, secret, exchangeSymbol, orderId, baseUrl);
                        cancelled++;
                        log.info("[POSITION_SYNC_ORPHAN_CANCELLED] botId={} symbol={} type={} orderId={}",
                            bot.getId(), exchangeSymbol, type, orderId);
                    } catch (Exception e) {
                        log.warn("[POSITION_SYNC_ORPHAN_CANCEL_FAILED] botId={} orderId={} error={}",
                            bot.getId(), orderId, e.getMessage());
                    }
                }
            }
            if (cancelled > 0) {
                log.info("[POSITION_SYNC_ORPHAN_CLEANUP] botId={} cancelled {} orphan protective orders",
                    bot.getId(), cancelled);
            }
        } catch (Exception e) {
            log.error("[POSITION_SYNC_ORPHAN_CLEANUP_FAILED] botId={} error={}", bot.getId(), e.getMessage());
        }
    }


    /**
     * One-time startup recovery: rebuild PositionTracker from exchange state.
     */
    public void recoverPositionsOnStartup() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");
        if (runningBots.isEmpty()) {
            log.info("[POSITION_SYNC] No running bots at startup, nothing to recover");
            return;
        }

        log.info("[POSITION_SYNC_START] Startup recovery for {} running bots", runningBots.size());

        Map<String, List<TradingBot>> grouped = runningBots.stream()
            .collect(Collectors.groupingBy(b -> b.getApiKeyId() + ":" + b.getExchangeMode()));

        int recovered = 0;

        for (var entry : grouped.entrySet()) {
            List<TradingBot> bots = entry.getValue();
            TradingBot representative = bots.get(0);

            try {
                Optional<UserApiKey> apiKeyOpt = apiKeyRepo.findById(representative.getApiKeyId());
                if (apiKeyOpt.isEmpty()) continue;

                UserApiKey apiKeyEntity = apiKeyOpt.get();
                String apiKey = apiKeyService.decryptApiKey(apiKeyEntity);
                String secret = apiKeyService.decryptApiSecret(apiKeyEntity);
                String exchangeName = apiKeyEntity.getExchange().toUpperCase();

                ExchangeClient client = exchangeFactory.getClient(exchangeName);
                String baseUrl = client.resolveBaseUrl(representative.getExchangeMode());

                List<ExchangePosition> exchangePositions = client.getOpenPositions(apiKey, secret, baseUrl);

                Map<String, ExchangePosition> exchangeMap = new HashMap<>();
                for (ExchangePosition ep : exchangePositions) {
                    exchangeMap.put(ep.getSymbol().toUpperCase(), ep);
                }

                for (TradingBot bot : bots) {
                    String exchangeSymbol;
                    try {
                        exchangeSymbol = symbolMapper.resolveSymbol(exchangeName, bot.getSymbol());
                    } catch (Exception e) {
                        exchangeSymbol = bot.getSymbol().toUpperCase();
                    }

                    ExchangePosition ep = exchangeMap.get(exchangeSymbol.toUpperCase());
                    if (ep != null && ep.getSize().compareTo(BigDecimal.ZERO) > 0) {
                        PositionTracker.TrackedPosition pos = PositionTracker.TrackedPosition.builder()
                            .botId(bot.getId())
                            .userId(bot.getUserId())
                            .symbol(exchangeSymbol)
                            .side(ep.getSide()) // preserve LONG/SHORT from exchange
                            .exchange(exchangeName)
                            .exchangeMode(bot.getExchangeMode())
                            .entryPrice(ep.getEntryPrice())
                            .quantity(ep.getSize())
                            .apiKey(apiKey)
                            .apiSecret(secret)
                            .exchangeBaseUrl(baseUrl)
                            .highestPriceSeen(ep.getEntryPrice())
                            .lowestPriceSeen(ep.getEntryPrice())
                            .openedAt(Instant.now())
                            .build();

                        positionTracker.registerPosition(pos);
                        bot.setHasOpenPosition(true);
                        bot.setEntryPrice(ep.getEntryPrice());
                        bot.setQuantity(ep.getSize());
                        botRepo.save(bot);
                        recovered++;

                        log.info("[POSITION_SYNC_RECOVERED] Startup: botId={} symbol={} size={} entryPrice={}",
                            bot.getId(), exchangeSymbol, ep.getSize(), ep.getEntryPrice());
                    }
                }
            } catch (Exception e) {
                log.error("[POSITION_SYNC] Startup recovery failed for group {}: {}",
                    entry.getKey(), e.getMessage());
            }
        }

        log.info("[POSITION_SYNC] Startup recovery complete: {} positions recovered, {} total tracked",
            recovered, positionTracker.getOpenPositionCount());
    }
}
