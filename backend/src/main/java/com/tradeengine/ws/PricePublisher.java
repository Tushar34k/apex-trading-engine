package com.tradeengine.ws;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages Binance WebSocket subscriptions for running bots.
 * Publishes price updates to frontend via STOMP.
 * Refreshes subscriptions every 10 seconds based on active bots.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricePublisher {

    private final BinanceStreamClient streamClient;
    private final TradeEventPublisher publisher;
    private final BotRepository botRepo;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    private final Set<String> activeSymbols = new HashSet<>();

    @PostConstruct
    public void init() {
        // Forward price updates to STOMP for frontend
        streamClient.setPriceUpdateListener((symbol, price) -> {
            publisher.publishPriceUpdate(symbol, BigDecimal.valueOf(price), 0);
        });

        // Always subscribe to major pairs
        streamClient.subscribe("BTCUSDT", true);
        streamClient.subscribe("ETHUSDT", true);
        activeSymbols.add("BTCUSDT");
        activeSymbols.add("ETHUSDT");
    }

    /**
     * Sync subscriptions with running bots every 10 seconds.
     */
    @Scheduled(fixedDelay = 10000)
    public void syncSubscriptions() {
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");

        Set<String> neededSymbols = new HashSet<>();
        neededSymbols.add("BTCUSDT");
        neededSymbols.add("ETHUSDT");

        for (TradingBot bot : runningBots) {
            neededSymbols.add(bot.getSymbol().toUpperCase());
        }

        // Subscribe to new symbols
        for (String symbol : neededSymbols) {
            if (!activeSymbols.contains(symbol)) {
                boolean testnet = !liveTradingEnabled;
                streamClient.subscribe(symbol, testnet);
                activeSymbols.add(symbol);
                log.info("[PricePublisher] Added stream for {}", symbol);
            }
        }

        // Unsubscribe from symbols no longer needed (except defaults)
        Set<String> toRemove = new HashSet<>();
        for (String symbol : activeSymbols) {
            if (!neededSymbols.contains(symbol)) {
                streamClient.unsubscribe(symbol);
                toRemove.add(symbol);
                log.info("[PricePublisher] Removed stream for {}", symbol);
            }
        }
        activeSymbols.removeAll(toRemove);
    }
}
