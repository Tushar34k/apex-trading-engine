package com.tradeengine.exchange;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Unified order response returned by all ExchangeClient implementations.
 */
@Data
@Builder
public class OrderResponse {
    private String orderId;
    private String symbol;
    private String side;
    private String status;
    private BigDecimal executedQty;
    private BigDecimal avgPrice;
}
