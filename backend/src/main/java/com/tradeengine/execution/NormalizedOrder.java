package com.tradeengine.execution;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Result of order normalization. Contains exchange-compliant quantity/price
 * or a validation error explaining why the order cannot be placed.
 */
@Data
@Builder
public class NormalizedOrder {
    private final String exchange;
    private final String symbol;
    private final String side;
    private final BigDecimal rawQuantity;
    private final BigDecimal rawPrice;
    private final BigDecimal quantity;   // normalized (step-size aligned)
    private final BigDecimal price;      // normalized (tick-size aligned)
    private final boolean valid;
    private final String validationMessage;
}
