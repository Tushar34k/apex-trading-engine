package com.tradeengine.execution;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Immutable trade request submitted to the execution queue.
 */
@Data
@Builder
public class TradeRequest {
    @Builder.Default
    private final UUID requestId = UUID.randomUUID(); // unique per request for dedup

    private final UUID botId;
    private final UUID userId;
    private final String symbol;
    private final String side;          // BUY / SELL
    private final BigDecimal quantity;
    private final BigDecimal price;     // optional, null for MARKET
    private final String orderType;     // MARKET / LIMIT
    private final String apiKey;
    private final String apiSecret;
    private final String exchangeBaseUrl;
    private final String exchange;      // BINANCE / DELTA / BYBIT
    private final String exchangeMode;  // LIVE / TESTNET
    private final String notificationType; // BOT_SELL, BOT_SL, BOT_TP, BOT_TRAILING_SL
    private final Instant timestamp;

    // Callback for the submitter to await result
    @Builder.Default
    private final CompletableFuture<TradeResult> resultFuture = new CompletableFuture<>();

    /**
     * Validates all required fields before submission.
     * @throws IllegalArgumentException if any required field is missing or invalid
     */
    public void validate() {
        if (exchange == null || exchange.isBlank())
            throw new IllegalArgumentException("Exchange is required");
        if (symbol == null || symbol.isBlank())
            throw new IllegalArgumentException("Symbol is required");
        if (side == null || side.isBlank())
            throw new IllegalArgumentException("Side is required");
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Quantity must be greater than 0");
        if (botId == null)
            throw new IllegalArgumentException("Bot ID is required");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("API key is required");
        if (apiSecret == null || apiSecret.isBlank())
            throw new IllegalArgumentException("API secret is required");
        if (exchangeMode == null || exchangeMode.isBlank())
            throw new IllegalArgumentException("Exchange mode is required");
        if (orderType == null || orderType.isBlank())
            throw new IllegalArgumentException("Order type is required");
        if (!"MARKET".equalsIgnoreCase(orderType) && !"LIMIT".equalsIgnoreCase(orderType))
            throw new IllegalArgumentException("Unsupported order type: " + orderType);
        if ("LIMIT".equalsIgnoreCase(orderType) && (price == null || price.compareTo(BigDecimal.ZERO) <= 0))
            throw new IllegalArgumentException("Price is required and must be > 0 for LIMIT orders");
    }

    @Data
    @Builder
    public static class TradeResult {
        private final boolean success;
        private final String orderId;
        private final BigDecimal executedQty;
        private final BigDecimal avgPrice;
        private final String errorMessage;
    }
}
