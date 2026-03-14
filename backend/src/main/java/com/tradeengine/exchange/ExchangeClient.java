package com.tradeengine.exchange;

import java.math.BigDecimal;
import java.util.List;

/**
 * Generic exchange client interface.
 * All exchange implementations must implement this.
 * The interface is exchange-agnostic — no Binance/Bybit/Delta-specific logic.
 */
public interface ExchangeClient {

    /**
     * Place a market order on the exchange.
     */
    OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                    String side, BigDecimal quantity, String baseUrl);

    /**
     * Place a limit order on the exchange.
     */
    OrderResponse placeLimitOrder(String apiKey, String secret, String symbol,
                                   String side, BigDecimal quantity, BigDecimal price, String baseUrl);

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

    /**
     * Fetch all open positions from the exchange.
     * Only returns positions with size > 0.
     *
     * @return list of open positions, never null
     */
    List<ExchangePosition> getOpenPositions(String apiKey, String secret, String baseUrl);

    /**
     * Test connectivity and API key validity.
     * Calls a simple authenticated endpoint and returns true if successful.
     */
    boolean testConnection(String apiKey, String secret, String baseUrl);

    /**
     * Cancel an open order by orderId and symbol.
     *
     * @return the cancelled order response, or throws on failure
     */
    OrderResponse cancelOrder(String apiKey, String secret, String symbol, String orderId, String baseUrl);

    /**
     * Fetch open orders for a symbol.
     *
     * @return list of open order details as JSON nodes (exchange-specific format)
     */
    List<com.fasterxml.jackson.databind.JsonNode> getOpenOrders(String apiKey, String secret, String symbol, String baseUrl);

    /**
     * Query the status of a specific order by orderId.
     * Used for partial fill handling — returns updated executedQty and status.
     *
     * @return updated OrderResponse with current fill status
     */
    OrderResponse queryOrderStatus(String apiKey, String secret, String symbol, String orderId, String baseUrl);
}
