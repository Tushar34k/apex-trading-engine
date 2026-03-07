package com.tradeengine.exchange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Unified balance model returned by all exchange clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Balance {
    private String asset;
    private BigDecimal free;
    private BigDecimal locked;

    public BigDecimal getTotal() {
        BigDecimal f = free != null ? free : BigDecimal.ZERO;
        BigDecimal l = locked != null ? locked : BigDecimal.ZERO;
        return f.add(l);
    }
}
