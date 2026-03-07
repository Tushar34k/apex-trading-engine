package com.tradeengine.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Delta Exchange client — placeholder implementation.
 * TODO: Implement real Delta Exchange API integration.
 */
@Component
@Slf4j
public class DeltaClient implements ExchangeClient {

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        // TODO: Implement Delta Exchange order placement
        throw new UnsupportedOperationException("Delta Exchange trading is not yet implemented");
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        // TODO: Implement Delta Exchange price fetching
        throw new UnsupportedOperationException("Delta Exchange price fetching is not yet implemented");
    }

    @Override
    public Map<String, BigDecimal> getBalances(String apiKey, String secret, String baseUrl) {
        // TODO: Implement Delta Exchange balance fetching
        throw new UnsupportedOperationException("Delta Exchange balance fetching is not yet implemented");
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        // TODO: Implement Delta Exchange candle/kline data
        throw new UnsupportedOperationException("Delta Exchange candle data is not yet implemented");
    }

    @Override
    public String getExchangeName() {
        return "DELTA";
    }
}
