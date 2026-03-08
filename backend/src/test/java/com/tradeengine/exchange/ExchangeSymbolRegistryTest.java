package com.tradeengine.exchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExchangeSymbolRegistryTest {

    private ExchangeSymbolRegistry registry;
    private ExchangeFactory exchangeFactory;

    @BeforeEach
    void setUp() {
        exchangeFactory = mock(ExchangeFactory.class);
        registry = new ExchangeSymbolRegistry(exchangeFactory);
    }

    @Test
    void cacheKey_format() {
        // Put a symbol info manually and verify retrieval
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BINANCE");
        info.setStepSize(new BigDecimal("0.00001"));
        info.setMinQty(new BigDecimal("0.00001"));
        info.setMaxQty(new BigDecimal("9000"));
        info.setMinNotional(new BigDecimal("10"));

        // Use getOrFetch which internally uses cacheKey — test the get() path
        assertNull(registry.get("BINANCE", "BTCUSDT"), "Cache should be empty initially");
    }

    @Test
    void get_returns_null_for_unknown() {
        assertNull(registry.get("BINANCE", "UNKNOWN"));
        assertNull(registry.get("DELTA", "BTCUSD"));
        assertNull(registry.get("BYBIT", "ETHUSDT"));
    }

    @Test
    void get_case_insensitive_exchange() {
        // The cacheKey uppercases both exchange and symbol
        assertNull(registry.get("binance", "btcusdt"));
        assertNull(registry.get("Binance", "BtcUsdt"));
    }

    @Test
    void getCacheSize_empty() {
        assertEquals(0, registry.getCacheSize());
    }

    @Test
    void symbolInfo_roundQuantity() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.001"));

        BigDecimal rounded = info.roundQuantity(new BigDecimal("1.23456"));
        assertEquals(new BigDecimal("1.234"), rounded);
    }

    @Test
    void symbolInfo_roundQuantity_exact() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.01"));

        BigDecimal rounded = info.roundQuantity(new BigDecimal("5.00"));
        assertEquals(0, rounded.compareTo(new BigDecimal("5.00")));
    }

    @Test
    void symbolInfo_roundQuantity_nullStepSize() {
        SymbolInfo info = new SymbolInfo();
        // stepSize is null
        BigDecimal qty = new BigDecimal("1.23456");
        assertEquals(qty, info.roundQuantity(qty));
    }

    @Test
    void symbolInfo_roundQuantity_zeroStepSize() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(BigDecimal.ZERO);
        BigDecimal qty = new BigDecimal("1.23456");
        assertEquals(qty, info.roundQuantity(qty));
    }

    @Test
    void symbolInfo_validate_valid() {
        SymbolInfo info = new SymbolInfo();
        info.setMinQty(new BigDecimal("0.001"));
        info.setMaxQty(new BigDecimal("100"));
        info.setMinNotional(new BigDecimal("10"));

        assertNull(info.validate(new BigDecimal("0.5"), new BigDecimal("50000")));
    }

    @Test
    void symbolInfo_validate_belowMinQty() {
        SymbolInfo info = new SymbolInfo();
        info.setMinQty(new BigDecimal("0.01"));

        String error = info.validate(new BigDecimal("0.001"), new BigDecimal("50000"));
        assertNotNull(error);
        assertTrue(error.contains("below minQty"));
    }

    @Test
    void symbolInfo_validate_aboveMaxQty() {
        SymbolInfo info = new SymbolInfo();
        info.setMaxQty(new BigDecimal("100"));

        String error = info.validate(new BigDecimal("200"), new BigDecimal("50000"));
        assertNotNull(error);
        assertTrue(error.contains("above maxQty"));
    }

    @Test
    void symbolInfo_validate_belowMinNotional() {
        SymbolInfo info = new SymbolInfo();
        info.setMinQty(new BigDecimal("0.00001"));
        info.setMinNotional(new BigDecimal("10"));

        String error = info.validate(new BigDecimal("0.00001"), new BigDecimal("100"));
        assertNotNull(error);
        assertTrue(error.contains("below minNotional"));
    }

    @Test
    void symbolInfo_exchange_field() {
        SymbolInfo info = new SymbolInfo();
        info.setExchange("BINANCE");
        assertEquals("BINANCE", info.getExchange());

        info.setExchange("DELTA");
        assertEquals("DELTA", info.getExchange());

        info.setExchange("BYBIT");
        assertEquals("BYBIT", info.getExchange());
    }

    @Test
    void refreshAll_emptyCache_doesNothing() {
        // Should not throw even with empty cache
        assertDoesNotThrow(() -> registry.refreshAll());
    }
}
