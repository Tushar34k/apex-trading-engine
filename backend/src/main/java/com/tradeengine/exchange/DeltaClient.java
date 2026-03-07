package com.tradeengine.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Delta Exchange client.
 * TODO: Implement real Delta Exchange API integration with HMAC signing.
 *
 * Delta API docs: https://docs.delta.exchange
 * Base URL: https://api.delta.exchange
 *
 * Architecture note: This client is ready for Smart Order Routing —
 * getPrice() can be used to compare prices across exchanges before execution.
 */
@Component
@Slf4j
public class DeltaClient implements ExchangeClient {

    private static final String LIVE_URL = "https://api.delta.exchange";
    private static final String TESTNET_URL = "https://testnet-api.delta.exchange";

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        log.error("[DELTA] placeMarketOrder called but not yet implemented. symbol={} side={} qty={}", symbol, side, quantity);
        // TODO: Implement Delta Exchange order placement
        // Delta uses HMAC-SHA256 signing with timestamp, method, path, and body
        // POST /v2/orders { "product_id": ..., "size": ..., "side": ..., "order_type": "market_order" }
        throw new UnsupportedOperationException("Delta Exchange trading is not yet implemented");
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        log.error("[DELTA] getPrice called but not yet implemented. symbol={}", symbol);
        // TODO: Implement Delta Exchange price fetching
        // GET /v2/tickers/{symbol}
        throw new UnsupportedOperationException("Delta Exchange price fetching is not yet implemented");
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        log.error("[DELTA] getBalances called but not yet implemented");
        // TODO: Implement Delta Exchange balance fetching
        // GET /v2/wallet/balances (signed)
        throw new UnsupportedOperationException("Delta Exchange balance fetching is not yet implemented");
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        log.error("[DELTA] getCandles called but not yet implemented. symbol={}", symbol);
        // TODO: Implement Delta Exchange candle/kline data
        // GET /v2/history/candles?resolution=...&symbol=...
        throw new UnsupportedOperationException("Delta Exchange candle data is not yet implemented");
    }

    @Override
    public String getExchangeName() {
        return "DELTA";
    }

    @Override
    public String resolveBaseUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return LIVE_URL;
        }
        return TESTNET_URL;
    }
}
