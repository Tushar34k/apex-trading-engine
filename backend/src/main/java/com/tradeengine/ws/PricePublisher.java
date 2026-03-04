package com.tradeengine.ws;

import com.tradeengine.exchange.BinanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Publishes live price updates for tracked symbols via WebSocket.
 * Runs every 5 seconds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricePublisher {

    private final BinanceClient binance;
    private final TradeEventPublisher publisher;

    private static final String[] TRACKED_SYMBOLS = {"BTCUSDT", "ETHUSDT"};

    @Scheduled(fixedDelay = 5000)
    public void publishPrices() {
        for (String symbol : TRACKED_SYMBOLS) {
            try {
                BigDecimal price = binance.getTickerPrice(symbol);
                publisher.publishPriceUpdate(symbol, price, 0); // MVP: change24h=0
            } catch (Exception e) {
                log.debug("Failed to publish price for {}: {}", symbol, e.getMessage());
            }
        }
    }
}
