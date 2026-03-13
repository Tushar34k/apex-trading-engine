package com.tradeengine.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

/**
 * Exchange-agnostic market data stream service.
 *
 * Currently delegates to BinanceStreamClient for real-time WebSocket data.
 * For non-Binance exchanges, callers should use REST fallback (via ExchangeClient.getPrice()).
 *
 * This abstraction layer ensures StrategyRunner/BotService never reference Binance directly.
 * When Bybit/Delta WebSocket clients are added, they plug in here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataStreamService {

    private final BinanceStreamClient binanceStreamClient;

    /**
     * Subscribe to real-time market data for a symbol on a given exchange.
     */
    public void subscribe(String exchange, String symbol, boolean testnet) {
        switch (exchange.toUpperCase()) {
            case "BINANCE" -> {
                binanceStreamClient.subscribe(symbol.toLowerCase(), testnet);
                log.info("[STREAM] Subscribed via Binance WS: symbol={} testnet={}", symbol, testnet);
            }
            case "BYBIT", "DELTA" -> {
                // WebSocket streams not yet implemented for these exchanges.
                // StrategyRunner will fall back to REST polling via ExchangeClient.getPrice()
                log.info("[STREAM] No WS stream for {} — REST fallback will be used for {}", exchange, symbol);
            }
            default -> log.warn("[STREAM] Unknown exchange for streaming: {}", exchange);
        }
    }

    /**
     * Unsubscribe from real-time market data.
     */
    public void unsubscribe(String exchange, String symbol) {
        if ("BINANCE".equalsIgnoreCase(exchange)) {
            binanceStreamClient.unsubscribe(symbol.toLowerCase());
        }
    }

    /**
     * Get latest cached price if fresh, otherwise null (caller should use REST fallback).
     * Only returns data for exchanges with active WebSocket connections.
     */
    public Double getFreshPrice(String exchange, String symbol) {
        if ("BINANCE".equalsIgnoreCase(exchange)) {
            return binanceStreamClient.getFreshPrice(symbol);
        }
        // No stream data for other exchanges — return null to trigger REST fallback
        return null;
    }

    /**
     * Get latest cached price regardless of freshness.
     */
    public Double getLatestPrice(String exchange, String symbol) {
        if ("BINANCE".equalsIgnoreCase(exchange)) {
            return binanceStreamClient.getLatestPrice(symbol);
        }
        return null;
    }

    /**
     * Get order book depth data [totalBidVolume, totalAskVolume].
     */
    public double[] getDepth(String exchange, String symbol) {
        if ("BINANCE".equalsIgnoreCase(exchange)) {
            return binanceStreamClient.getDepth(symbol);
        }
        return null;
    }

    /**
     * Check if real-time price data is available for this exchange.
     */
    public boolean hasStreamSupport(String exchange) {
        return "BINANCE".equalsIgnoreCase(exchange);
    }

    /**
     * Set a listener for price updates (currently Binance-only).
     */
    public void setPriceUpdateListener(BiConsumer<String, Double> listener) {
        binanceStreamClient.setPriceUpdateListener(listener);
    }
}
