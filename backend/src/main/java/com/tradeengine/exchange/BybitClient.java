package com.tradeengine.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Bybit Exchange client — placeholder implementation.
 * TODO: Implement real Bybit API integration.
 */
@Component
@Slf4j
public class BybitClient implements ExchangeClient {

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        // TODO: Implement Bybit order placement
        throw new UnsupportedOperationException("Bybit trading is not yet implemented");
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        // TODO: Implement Bybit price fetching
        throw new UnsupportedOperationException("Bybit price fetching is not yet implemented");
    }

    @Override
    public Map<String, BigDecimal> getBalances(String apiKey, String secret, String baseUrl) {
        // TODO: Implement Bybit balance fetching
        throw new UnsupportedOperationException("Bybit balance fetching is not yet implemented");
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        // TODO: Implement Bybit candle/kline data
        throw new UnsupportedOperationException("Bybit candle data is not yet implemented");
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }
}
