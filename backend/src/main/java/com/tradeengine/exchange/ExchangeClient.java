package com.tradeengine.exchange;

import java.math.BigDecimal;
import java.util.List;

/**
 * Generic exchange client interface.
 * All exchange implementations must implement this.
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
     * Get account balances as structured Balance objects.
     */
    List<Balance> getBalances(String apiKey, String secret, String baseUrl);

    /**
     * Get historical candle/kline data.
     */
    List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl);

    /**
     * Returns the exchange identifier (e.g. "BINANCE", "DELTA", "BYBIT").
     */
    String getExchangeName();

    /**
     * Resolve the base URL for the given trading mode.
     * @param mode "LIVE" or "TESTNET"
     * @return the appropriate API base URL for this exchange
     */
    String resolveBaseUrl(String mode);
}
