package com.tradeengine.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Component
@Slf4j
public class BybitClient implements ExchangeClient {

    private static final String LIVE_URL = "https://api.bybit.com";
    private static final String TESTNET_URL = "https://api-testnet.bybit.com";

    @Value("${exchange.bybit.base-url:" + TESTNET_URL + "}")
    private String defaultBaseUrl;

    @Value("${exchange.bybit.live-url:" + LIVE_URL + "}")
    private String liveBaseUrl;

    @Value("${exchange.bybit.testnet-url:" + TESTNET_URL + "}")
    private String testnetBaseUrl;

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        log.error("[BYBIT] placeMarketOrder called but not yet implemented. symbol={} side={} qty={}", symbol, side, quantity);
        throw new UnsupportedOperationException("Bybit trading is not yet implemented");
    }

    @Override
    public OrderResponse placeLimitOrder(String apiKey, String secret, String symbol,
                                          String side, BigDecimal quantity, BigDecimal price, String baseUrl) {
        log.error("[BYBIT] placeLimitOrder called but not yet implemented. symbol={} side={} qty={} price={}", symbol, side, quantity, price);
        throw new UnsupportedOperationException("Bybit limit order trading is not yet implemented");
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        log.error("[BYBIT] getPrice called but not yet implemented. symbol={}", symbol);
        throw new UnsupportedOperationException("Bybit price fetching is not yet implemented");
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        log.error("[BYBIT] getBalances called but not yet implemented");
        throw new UnsupportedOperationException("Bybit balance fetching is not yet implemented");
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        log.error("[BYBIT] getCandles called but not yet implemented. symbol={}", symbol);
        throw new UnsupportedOperationException("Bybit candle data is not yet implemented");
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public String resolveBaseUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return liveBaseUrl;
        }
        return testnetBaseUrl;
    }
}
