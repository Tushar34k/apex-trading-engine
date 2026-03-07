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
    private final String notificationType; // BOT_SELL, BOT_SL, BOT_TP, BOT_TRAILING_SL
    private final Instant timestamp;

    // Callback for the submitter to await result
    @Builder.Default
    private final CompletableFuture<TradeResult> resultFuture = new CompletableFuture<>();

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
