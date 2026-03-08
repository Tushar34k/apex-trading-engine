package com.tradeengine.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SymbolMapperServiceTest {

    private SymbolMapperService mapper;

    @BeforeEach
    void setUp() {
        mapper = new SymbolMapperService();

        Map<String, Map<String, String>> mappings = new HashMap<>();

        Map<String, String> btcMap = new HashMap<>();
        btcMap.put("binance", "BTCUSDT");
        btcMap.put("delta", "BTCUSD");
        btcMap.put("bybit", "BTCUSDT");
        mappings.put("BTC/USDT", btcMap);

        Map<String, String> ethMap = new HashMap<>();
        ethMap.put("binance", "ETHUSDT");
        ethMap.put("delta", "ETHUSD");
        ethMap.put("bybit", "ETHUSDT");
        mappings.put("ETH/USDT", ethMap);

        mapper.setMappings(mappings);
        mapper.init();
    }

    @Test
    void resolveSymbol_binance() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("BINANCE", "BTC/USDT"));
    }

    @Test
    void resolveSymbol_delta() {
        assertEquals("BTCUSD", mapper.resolveSymbol("DELTA", "BTC/USDT"));
    }

    @Test
    void resolveSymbol_bybit() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("BYBIT", "BTC/USDT"));
    }

    @Test
    void resolveSymbol_eth() {
        assertEquals("ETHUSDT", mapper.resolveSymbol("BINANCE", "ETH/USDT"));
        assertEquals("ETHUSD", mapper.resolveSymbol("DELTA", "ETH/USDT"));
    }

    @Test
    void resolveSymbol_caseInsensitive() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("binance", "btc/usdt"));
    }

    @Test
    void resolveSymbol_nativePassthrough() {
        // No "/" → treated as already exchange-native
        assertEquals("BTCUSDT", mapper.resolveSymbol("BINANCE", "BTCUSDT"));
    }

    @Test
    void resolveSymbol_unknownUniversalThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.resolveSymbol("BINANCE", "UNKNOWN/PAIR"));
    }

    @Test
    void resolveSymbol_unknownExchangeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.resolveSymbol("KRAKEN", "BTC/USDT"));
    }

    @Test
    void resolveSymbol_nullSymbolThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.resolveSymbol("BINANCE", null));
    }

    @Test
    void resolveSymbol_nullExchangeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.resolveSymbol(null, "BTC/USDT"));
    }

    @Test
    void toUniversal_knownMapping() {
        assertEquals("BTC/USDT", mapper.toUniversal("DELTA", "BTCUSD"));
    }

    @Test
    void toUniversal_unknownFallback() {
        assertEquals("XYZABC", mapper.toUniversal("BINANCE", "XYZABC"));
    }

    @Test
    void hasMapping_true() {
        assertTrue(mapper.hasMapping("BINANCE", "BTC/USDT"));
        assertTrue(mapper.hasMapping("DELTA", "ETH/USDT"));
    }

    @Test
    void hasMapping_false() {
        assertFalse(mapper.hasMapping("BINANCE", "UNKNOWN/PAIR"));
        assertFalse(mapper.hasMapping("BINANCE", "BTCUSDT")); // no "/" → false
    }
}
