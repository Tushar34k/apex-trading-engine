package com.tradeengine.ws;

import com.tradeengine.model.TradingBot;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;

/**
 * Manages market data stream subscriptions for running bots.
 * Publishes price updates to frontend via STOMP.
 * Exchange-agnostic: delegates to MarketDataStreamService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricePublisher {

    private final MarketDataStreamService streamService;
    private final TradeEventPublisher publisher;
    private final BotRepository botRepo;
    private final ApiKeyRepository apiKeyRepo;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    // Tracks active subscriptions as "EXCHANGE:SYMBOL"
    private final Set<String> activeSubscriptions = new HashSet<>();

    @PostConstruct
    public void init() {
        // Forward price updates to STOMP for frontend
        streamService.setPriceUpdateListener((symbol, price) -> {
            publisher.publishPriceUpdate(symbol, BigDecimal.valueOf(price), 0);
        });

        // Default subscriptions for dashboard (Binance streams available)
        streamService.subscribe("BINANCE", "BTCUSDT", true);
        streamService.subscribe("BINANCE", "ETHUSDT", true);
        activeSubscriptions.add("BINANCE:BTCUSDT");
        activeSubscriptions.add("BINANCE:ETHUSDT");
    }

    /**
     * Sync subscriptions with running bots every 10 seconds.
     */
    @Scheduled(fixedDelay = 10000)
    public void syncSubscriptions() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");

        Set<String> neededSubscriptions = new HashSet<>();
        neededSubscriptions.add("BINANCE:BTCUSDT");
        neededSubscriptions.add("BINANCE:ETHUSDT");

        for (TradingBot bot : runningBots) {
            // Resolve exchange from API key
            String exchange = "BINANCE"; // default
            try {
                Optional<UserApiKey> apiKey = apiKeyRepo.findById(bot.getApiKeyId());
                if (apiKey.isPresent()) {
                    exchange = apiKey.get().getExchange().toUpperCase();
                }
            } catch (Exception e) {
                log.debug("[PricePublisher] Could not resolve exchange for bot {}", bot.getId());
            }
            neededSubscriptions.add(exchange + ":" + bot.getSymbol().toUpperCase());
        }

        // Subscribe to new symbols
        for (String sub : neededSubscriptions) {
            if (!activeSubscriptions.contains(sub)) {
                String[] parts = sub.split(":", 2);
                boolean testnet = !liveTradingEnabled;
                streamService.subscribe(parts[0], parts[1], testnet);
                activeSubscriptions.add(sub);
                log.info("[PricePublisher] Added stream for {} on {}", parts[1], parts[0]);
            }
        }

        // Unsubscribe from symbols no longer needed
        Set<String> toRemove = new HashSet<>();
        for (String sub : activeSubscriptions) {
            if (!neededSubscriptions.contains(sub)) {
                String[] parts = sub.split(":", 2);
                streamService.unsubscribe(parts[0], parts[1]);
                toRemove.add(sub);
                log.info("[PricePublisher] Removed stream for {} on {}", parts[1], parts[0]);
            }
        }
        activeSubscriptions.removeAll(toRemove);
    }
}
