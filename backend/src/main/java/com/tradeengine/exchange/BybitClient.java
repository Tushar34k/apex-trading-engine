package com.tradeengine.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bybit Exchange client.
 * TODO: Implement real Bybit API integration with HMAC signing.
 *
 * Bybit API docs: https://bybit-exchange.github.io/docs/v5/intro
 * Base URL: https://api.bybit.com
 * Testnet: https://api-testnet.bybit.com
 *
 * Architecture note: This client is ready for Smart Order Routing —
 * getPrice() can be used to compare prices across exchanges before execution.
 */
@Component
@Slf4j
public class BybitClient implements ExchangeClient {

    private static final String LIVE_URL = "https://api.bybit.com";
    private static final String TESTNET_URL = "https://api-testnet.bybit.com";

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        log.error("[BYBIT] placeMarketOrder called but not yet implemented. symbol={} side={} qty={}", symbol, side, quantity);
        // TODO: Implement Bybit order placement
        // Bybit V5 uses HMAC-SHA256 with timestamp + api_key + recv_window + body
        // POST /v5/order/create { "category": "spot", "symbol": ..., "side": ..., "orderType": "Market", "qty": ... }
        throw new UnsupportedOperationException("Bybit trading is not yet implemented");
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        log.error("[BYBIT] getPrice called but not yet implemented. symbol={}", symbol);
        // TODO: Implement Bybit price fetching
        // GET /v5/market/tickers?category=spot&symbol=...
        throw new UnsupportedOperationException("Bybit price fetching is not yet implemented");
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        log.error("[BYBIT] getBalances called but not yet implemented");
        // TODO: Implement Bybit balance fetching
        // GET /v5/account/wallet-balance?accountType=UNIFIED (signed)
        throw new UnsupportedOperationException("Bybit balance fetching is not yet implemented");
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        log.error("[BYBIT] getCandles called but not yet implemented. symbol={}", symbol);
        // TODO: Implement Bybit candle/kline data
        // GET /v5/market/kline?category=spot&symbol=...&interval=...&limit=...
        throw new UnsupportedOperationException("Bybit candle data is not yet implemented");
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public String resolveBaseUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return LIVE_URL;
        }
        return TESTNET_URL;
    }
}
