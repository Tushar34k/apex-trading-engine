package com.tradeengine.exchange;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Exchange symbol trading rules (LOT_SIZE, MIN_NOTIONAL, PRICE_FILTER).
 * Exchange-agnostic — the exchange field identifies the source.
 */
@Data
public class SymbolInfo {
    private String symbol;
    private String exchange;    // BINANCE, DELTA, BYBIT
    private BigDecimal stepSize;      // LOT_SIZE stepSize
    private BigDecimal minQty;        // LOT_SIZE minQty
    private BigDecimal maxQty;        // LOT_SIZE maxQty
    private BigDecimal minNotional;   // NOTIONAL or MIN_NOTIONAL minNotional
    private BigDecimal tickSize;      // PRICE_FILTER tickSize

    /**
     * Round quantity DOWN to comply with stepSize.
     */
    public BigDecimal roundQuantity(BigDecimal qty) {
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) == 0) return qty;
        // floor to nearest stepSize
        BigDecimal remainder = qty.remainder(stepSize);
        return qty.subtract(remainder);
    }

    /**
     * Validate quantity against Binance LOT_SIZE and MIN_NOTIONAL rules.
     * Returns null if valid, or an error message if invalid.
     */
    public String validate(BigDecimal quantity, BigDecimal price) {
        if (minQty != null && quantity.compareTo(minQty) < 0) {
            return "Quantity " + quantity + " below minQty " + minQty;
        }
        if (maxQty != null && quantity.compareTo(maxQty) > 0) {
            return "Quantity " + quantity + " above maxQty " + maxQty;
        }
        if (minNotional != null && price != null) {
            BigDecimal notional = quantity.multiply(price);
            if (notional.compareTo(minNotional) < 0) {
                return "Notional " + notional + " below minNotional " + minNotional;
            }
        }
        return null;
    }
}
