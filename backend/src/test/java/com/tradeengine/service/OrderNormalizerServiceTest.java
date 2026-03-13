package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeSymbolRegistry;
import com.tradeengine.exchange.SymbolInfo;
import com.tradeengine.execution.NormalizedOrder;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for OrderNormalizerService: quantity/price rounding, minQty, maxQty,
 * minNotional validation across all exchanges.
 */
class OrderNormalizerServiceTest {

    private ExchangeSymbolRegistry registry;
    private OrderNormalizerService normalizer;

    @BeforeEach
    void setUp() {
        registry = mock(ExchangeSymbolRegistry.class);
        normalizer = new OrderNormalizerService(registry);
    }

    private SymbolInfo binanceBtc() {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BINANCE");
        info.setStepSize(new BigDecimal("0.001"));
        info.setTickSize(new BigDecimal("0.10"));
        info.setMinQty(new BigDecimal("0.001"));
        info.setMaxQty(new BigDecimal("1000"));
        info.setMinNotional(new BigDecimal("5"));
        return info;
    }

    private SymbolInfo bybitBtc() {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BYBIT");
        info.setStepSize(new BigDecimal("0.000001"));
        info.setTickSize(new BigDecimal("0.01"));
        info.setMinQty(new BigDecimal("0.000048"));
        info.setMaxQty(new BigDecimal("71.73"));
        return info;
    }

    private SymbolInfo deltaBtc() {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSD");
        info.setExchange("DELTA");
        info.setStepSize(new BigDecimal("1"));
        info.setTickSize(new BigDecimal("0.5"));
        info.setMinQty(new BigDecimal("1"));
        return info;
    }

    // ── Quantity rounding ──

    @Test
    @DisplayName("Binance: quantity rounded down to stepSize")
    void binanceQuantityRounding() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.12345"), new BigDecimal("65000"), "BUY", "http://test");

        assertTrue(result.isValid());
        assertEquals(0, new BigDecimal("0.123").compareTo(result.getQuantity()));
    }

    @Test
    @DisplayName("Bybit: quantity rounded down to stepSize")
    void bybitQuantityRounding() {
        when(registry.getOrFetch("BYBIT", "BTCUSDT", "http://test")).thenReturn(bybitBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BYBIT", "BTCUSDT", new BigDecimal("0.1234567"), new BigDecimal("65000"), "BUY", "http://test");

        assertTrue(result.isValid());
        assertEquals(0, new BigDecimal("0.123456").compareTo(result.getQuantity()));
    }

    @Test
    @DisplayName("Delta: quantity rounded down to contract stepSize")
    void deltaQuantityRounding() {
        when(registry.getOrFetch("DELTA", "BTCUSD", "http://test")).thenReturn(deltaBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "DELTA", "BTCUSD", new BigDecimal("3.7"), new BigDecimal("65000"), "BUY", "http://test");

        assertTrue(result.isValid());
        assertEquals(0, new BigDecimal("3").compareTo(result.getQuantity()));
    }

    // ── Price rounding ──

    @Test
    @DisplayName("Price rounded down to tickSize")
    void priceRounding() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.001"), new BigDecimal("65000.1234"), "BUY", "http://test");

        assertTrue(result.isValid());
        assertEquals(0, new BigDecimal("65000.1").compareTo(result.getPrice()));
    }

    // ── minQty validation ──

    @Test
    @DisplayName("Rejected when quantity below minQty")
    void belowMinQty() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.0001"), new BigDecimal("65000"), "BUY", "http://test");

        assertFalse(result.isValid());
        assertTrue(result.getValidationMessage().contains("below minQty"));
    }

    // ── maxQty validation ──

    @Test
    @DisplayName("Rejected when quantity above maxQty")
    void aboveMaxQty() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("2000"), new BigDecimal("65000"), "BUY", "http://test");

        assertFalse(result.isValid());
        assertTrue(result.getValidationMessage().contains("above maxQty"));
    }

    // ── minNotional validation ──

    @Test
    @DisplayName("Rejected when notional below minNotional")
    void belowMinNotional() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        // 0.001 * 1.0 = 0.001 which is < 5
        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.001"), new BigDecimal("1.0"), "BUY", "http://test");

        assertFalse(result.isValid());
        assertTrue(result.getValidationMessage().contains("below minNotional"));
    }

    @Test
    @DisplayName("Market order skips minNotional check (no price)")
    void marketOrderNoNotionalCheck() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.001"), null, "BUY", "http://test");

        assertTrue(result.isValid());
    }

    // ── Missing symbol info ──

    @Test
    @DisplayName("Pass-through when no symbol info available")
    void noSymbolInfo() {
        when(registry.getOrFetch("UNKNOWN", "XYZUSDT", "http://test")).thenReturn(null);

        NormalizedOrder result = normalizer.normalizeOrder(
                "UNKNOWN", "XYZUSDT", new BigDecimal("1.23456"), new BigDecimal("99.99"), "BUY", "http://test");

        assertTrue(result.isValid());
        assertEquals(new BigDecimal("1.23456"), result.getQuantity());
        assertEquals(new BigDecimal("99.99"), result.getPrice());
    }

    // ── Zero quantity after rounding ──

    @Test
    @DisplayName("Rejected when quantity rounds to zero")
    void quantityRoundsToZero() {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BINANCE");
        info.setStepSize(new BigDecimal("1"));
        info.setMinQty(new BigDecimal("1"));

        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(info);

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.5"), new BigDecimal("65000"), "BUY", "http://test");

        assertFalse(result.isValid());
    }

    // ── Exact values pass through ──

    @Test
    @DisplayName("Exact step-aligned values pass through unchanged")
    void exactValuesPassThrough() {
        when(registry.getOrFetch("BINANCE", "BTCUSDT", "http://test")).thenReturn(binanceBtc());

        NormalizedOrder result = normalizer.normalizeOrder(
                "BINANCE", "BTCUSDT", new BigDecimal("0.001"), new BigDecimal("65000.0"), "BUY", "http://test");

        assertTrue(result.isValid());
        assertEquals(0, new BigDecimal("0.001").compareTo(result.getQuantity()));
        assertEquals(0, new BigDecimal("65000").compareTo(result.getPrice()));
    }
}
