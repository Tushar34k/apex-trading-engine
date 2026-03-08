package com.tradeengine.exchange;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Normalized position snapshot fetched from an exchange.
 * Used by PositionSyncService to reconcile internal state.
 */
@Data
@Builder
public class ExchangePosition {
    private final String exchange;
    private final String symbol;
    private final String side;       // "LONG" or "SHORT"
    private final BigDecimal size;
    private final BigDecimal entryPrice;
    private final BigDecimal unrealizedPnl;
}
