package com.tradeengine.integration;

import com.tradeengine.exchange.SymbolMapperService;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SymbolMapperService: universal→native resolution, reverse lookup,
 * backward compatibility, and error handling.
 */
class SymbolMappingTest {

    private SymbolMapperService mapper;

    @BeforeEach
    void setUp() {
        mapper = new SymbolMapperService();
        Map<String, Map<String, String>> mappings = new HashMap<>();

        mappings.put("BTC/USDT", Map.of("binance", "BTCUSDT", "delta", "BTCUSD", "bybit", "BTCUSDT"));
        mappings.put("ETH/USDT", Map.of("binance", "ETHUSDT", "delta", "ETHUSD", "bybit", "ETHUSDT"));
        mappings.put("SOL/USDT", Map.of("binance", "SOLUSDT", "delta", "SOLUSD", "bybit", "SOLUSDT"));

        mapper.setMappings(mappings);
        mapper.init();
    }

    @Test
    @DisplayName("Resolves universal symbol to Binance native")
    void resolvesBinance() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("BINANCE", "BTC/USDT"));
        assertEquals("ETHUSDT", mapper.resolveSymbol("BINANCE", "ETH/USDT"));
    }

    @Test
    @DisplayName("Resolves universal symbol to Delta native")
    void resolvesDelta() {
        assertEquals("BTCUSD", mapper.resolveSymbol("DELTA", "BTC/USDT"));
        assertEquals("ETHUSD", mapper.resolveSymbol("DELTA", "ETH/USDT"));
    }

    @Test
    @DisplayName("Resolves universal symbol to Bybit native")
    void resolvesBybit() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("BYBIT", "BTC/USDT"));
        assertEquals("SOLUSDT", mapper.resolveSymbol("BYBIT", "SOL/USDT"));
    }

    @Test
    @DisplayName("Case-insensitive resolution")
    void caseInsensitive() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("binance", "btc/usdt"));
        assertEquals("BTCUSD", mapper.resolveSymbol("delta", "Btc/Usdt"));
    }

    @Test
    @DisplayName("Native symbol passthrough (no slash)")
    void nativePassthrough() {
        assertEquals("BTCUSDT", mapper.resolveSymbol("BINANCE", "BTCUSDT"));
        assertEquals("BTCUSD", mapper.resolveSymbol("DELTA", "BTCUSD"));
    }

    @Test
    @DisplayName("Reverse lookup: native to universal")
    void reverseLookup() {
        assertEquals("BTC/USDT", mapper.toUniversal("BINANCE", "BTCUSDT"));
        assertEquals("BTC/USDT", mapper.toUniversal("DELTA", "BTCUSD"));
    }

    @Test
    @DisplayName("Reverse lookup returns native for unmapped symbol")
    void reverseLookupFallback() {
        assertEquals("XYZUSDT", mapper.toUniversal("BINANCE", "XYZUSDT"));
    }

    @Test
    @DisplayName("hasMapping returns correct results")
    void hasMapping() {
        assertTrue(mapper.hasMapping("BINANCE", "BTC/USDT"));
        assertFalse(mapper.hasMapping("BINANCE", "XYZ/USDT"));
        assertFalse(mapper.hasMapping("BINANCE", "BTCUSDT")); // no slash = false
    }

    @Test
    @DisplayName("Throws on unknown universal symbol")
    void throwsOnUnknownSymbol() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.resolveSymbol("BINANCE", "DOGE/USDT"));
    }

    @Test
    @DisplayName("Throws on unsupported exchange for known symbol")
    void throwsOnUnsupportedExchange() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.resolveSymbol("KRAKEN", "BTC/USDT"));
    }

    @Test
    @DisplayName("Throws on null/blank inputs")
    void throwsOnNullInputs() {
        assertThrows(IllegalArgumentException.class, () -> mapper.resolveSymbol("BINANCE", null));
        assertThrows(IllegalArgumentException.class, () -> mapper.resolveSymbol(null, "BTC/USDT"));
        assertThrows(IllegalArgumentException.class, () -> mapper.resolveSymbol("BINANCE", ""));
    }
}
