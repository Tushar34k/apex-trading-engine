package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeSymbolRegistry;
import com.tradeengine.exchange.SymbolInfo;
import com.tradeengine.execution.NormalizedOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Normalizes order quantity and price to comply with exchange trading rules
 * (stepSize, tickSize, minQty, maxQty, minNotional) before submission.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderNormalizerService {

    private final ExchangeSymbolRegistry symbolRegistry;

    /**
     * Normalize an order's quantity and price to exchange-compliant values.
     *
     * @param exchange  BINANCE / BYBIT / DELTA
     * @param symbol    exchange-specific symbol (e.g. BTCUSDT)
     * @param quantity  raw quantity from strategy
     * @param price     raw price (null for MARKET orders — minNotional check skipped)
     * @param side      BUY / SELL
     * @param baseUrl   exchange base URL for fetching symbol info if not cached
     * @return NormalizedOrder with adjusted values or validation error
     */
    public NormalizedOrder normalizeOrder(String exchange, String symbol, BigDecimal quantity,
                                          BigDecimal price, String side, String baseUrl) {

        SymbolInfo info = symbolRegistry.getOrFetch(exchange, symbol, baseUrl);

        if (info == null) {
            log.warn("[ORDER_NORMALIZE] No symbol info for {}:{} — passing through raw values", exchange, symbol);
            return NormalizedOrder.builder()
                    .exchange(exchange).symbol(symbol).side(side)
                    .rawQuantity(quantity).rawPrice(price)
                    .quantity(quantity).price(price)
                    .valid(true)
                    .validationMessage("No symbol info available — raw values used")
                    .build();
        }

        // ── Normalize quantity: floor(qty / stepSize) * stepSize ──
        BigDecimal normalizedQty = quantity;
        if (info.getStepSize() != null && info.getStepSize().compareTo(BigDecimal.ZERO) > 0) {
            normalizedQty = quantity.divideToIntegralValue(info.getStepSize()).multiply(info.getStepSize());
            normalizedQty = normalizedQty.stripTrailingZeros();
        }

        // ── Normalize price: floor(price / tickSize) * tickSize ──
        BigDecimal normalizedPrice = price;
        if (price != null && info.getTickSize() != null && info.getTickSize().compareTo(BigDecimal.ZERO) > 0) {
            normalizedPrice = price.divideToIntegralValue(info.getTickSize()).multiply(info.getTickSize());
            normalizedPrice = normalizedPrice.stripTrailingZeros();
        }

        // ── Validate minQty ──
        if (info.getMinQty() != null && normalizedQty.compareTo(info.getMinQty()) < 0) {
            String msg = String.format("Quantity %s below minQty %s for %s:%s",
                    normalizedQty.toPlainString(), info.getMinQty().toPlainString(), exchange, symbol);
            log.warn("[ORDER_REJECTED] reason=MIN_QTY {}", msg);
            return rejected(exchange, symbol, side, quantity, price, normalizedQty, normalizedPrice, msg);
        }

        // ── Validate maxQty ──
        if (info.getMaxQty() != null && normalizedQty.compareTo(info.getMaxQty()) > 0) {
            String msg = String.format("Quantity %s above maxQty %s for %s:%s",
                    normalizedQty.toPlainString(), info.getMaxQty().toPlainString(), exchange, symbol);
            log.warn("[ORDER_REJECTED] reason=MAX_QTY {}", msg);
            return rejected(exchange, symbol, side, quantity, price, normalizedQty, normalizedPrice, msg);
        }

        // ── Validate minNotional (only when price is available) ──
        if (normalizedPrice != null && info.getMinNotional() != null) {
            BigDecimal notional = normalizedQty.multiply(normalizedPrice);
            if (notional.compareTo(info.getMinNotional()) < 0) {
                String msg = String.format("Notional %s below minNotional %s for %s:%s",
                        notional.toPlainString(), info.getMinNotional().toPlainString(), exchange, symbol);
                log.warn("[ORDER_REJECTED] reason=MIN_NOTIONAL {}", msg);
                return rejected(exchange, symbol, side, quantity, price, normalizedQty, normalizedPrice, msg);
            }
        }

        // ── Validate normalized quantity is positive ──
        if (normalizedQty.compareTo(BigDecimal.ZERO) <= 0) {
            String msg = String.format("Normalized quantity is zero/negative for %s:%s (raw=%s stepSize=%s)",
                    exchange, symbol, quantity.toPlainString(),
                    info.getStepSize() != null ? info.getStepSize().toPlainString() : "null");
            log.warn("[ORDER_REJECTED] reason=ZERO_QTY {}", msg);
            return rejected(exchange, symbol, side, quantity, price, normalizedQty, normalizedPrice, msg);
        }

        log.info("[ORDER_NORMALIZED] exchange={} symbol={} rawQty={} normalizedQty={} rawPrice={} normalizedPrice={}",
                exchange, symbol,
                quantity.toPlainString(), normalizedQty.toPlainString(),
                price != null ? price.toPlainString() : "MARKET",
                normalizedPrice != null ? normalizedPrice.toPlainString() : "MARKET");

        return NormalizedOrder.builder()
                .exchange(exchange).symbol(symbol).side(side)
                .rawQuantity(quantity).rawPrice(price)
                .quantity(normalizedQty).price(normalizedPrice)
                .valid(true)
                .build();
    }

    private NormalizedOrder rejected(String exchange, String symbol, String side,
                                     BigDecimal rawQty, BigDecimal rawPrice,
                                     BigDecimal normQty, BigDecimal normPrice, String reason) {
        return NormalizedOrder.builder()
                .exchange(exchange).symbol(symbol).side(side)
                .rawQuantity(rawQty).rawPrice(rawPrice)
                .quantity(normQty).price(normPrice)
                .valid(false)
                .validationMessage(reason)
                .build();
    }
}
