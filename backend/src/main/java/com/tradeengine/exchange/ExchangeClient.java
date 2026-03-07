package com.tradeengine.exchange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Generic exchange client interface.
 * All exchange implementations (Binance, Delta, Bybit) must implement this.
 */
public interface ExchangeClient {

    /**
     * Place a market order on the exchange.
     */
    OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                    String side, BigDecimal quantity, String baseUrl);

    /**
     * Get current ticker price for a symbol.
     */
    BigDecimal getPrice(String symbol, String baseUrl);

    /**
     * Get account balances.
     */
    Map<String, BigDecimal> getBalances(String apiKey, String secret, String baseUrl);

    /**
     * Get historical candle/kline data.
     */
    List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl);

    /**
     * Returns the exchange identifier (e.g. "BINANCE", "DELTA", "BYBIT").
     */
    String getExchangeName();
}
