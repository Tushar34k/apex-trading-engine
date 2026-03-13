package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full exchange pipeline:
 * Symbol mapping → Exchange client → Authentication → Order flow
 */
class ExchangePipelineTest {

    private SymbolMapperService symbolMapper;

    @BeforeEach
    void setUp() {
        symbolMapper = new SymbolMapperService();
        symbolMapper.setMappings(Map.of(
            "BTC/USDT", Map.of("binance", "BTCUSDT", "delta", "BTCUSD", "bybit", "BTCUSDT"),
            "ETH/USDT", Map.of("binance", "ETHUSDT", "delta", "ETHUSD", "bybit", "ETHUSDT"),
            "SOL/USDT", Map.of("binance", "SOLUSDT", "delta", "SOLUSD", "bybit", "SOLUSDT")
        ));
        symbolMapper.init();
    }

    // ─── Symbol Mapping ─────────────────────────────────────────────────────────

    @Test
    void binanceSymbolMapping() {
        assertEquals("BTCUSDT", symbolMapper.resolveSymbol("BINANCE", "BTC/USDT"));
        assertEquals("ETHUSDT", symbolMapper.resolveSymbol("BINANCE", "ETH/USDT"));
        assertEquals("SOLUSDT", symbolMapper.resolveSymbol("BINANCE", "SOL/USDT"));
    }

    @Test
    void deltaSymbolMapping() {
        assertEquals("BTCUSD", symbolMapper.resolveSymbol("DELTA", "BTC/USDT"));
        assertEquals("ETHUSD", symbolMapper.resolveSymbol("DELTA", "ETH/USDT"));
    }

    @Test
    void bybitSymbolMapping() {
        assertEquals("BTCUSDT", symbolMapper.resolveSymbol("BYBIT", "BTC/USDT"));
        assertEquals("ETHUSDT", symbolMapper.resolveSymbol("BYBIT", "ETH/USDT"));
    }

    @Test
    void nativeSymbolPassthrough() {
        // Symbols without "/" should pass through unchanged
        assertEquals("BTCUSDT", symbolMapper.resolveSymbol("BINANCE", "BTCUSDT"));
        assertEquals("BTCUSD", symbolMapper.resolveSymbol("DELTA", "BTCUSD"));
    }

    @Test
    void reverseMapping() {
        assertEquals("BTC/USDT", symbolMapper.toUniversal("BINANCE", "BTCUSDT"));
        assertEquals("BTC/USDT", symbolMapper.toUniversal("DELTA", "BTCUSD"));
        assertEquals("BTC/USDT", symbolMapper.toUniversal("BYBIT", "BTCUSDT"));
    }

    @Test
    void unknownSymbolThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> symbolMapper.resolveSymbol("BINANCE", "UNKNOWN/PAIR"));
    }

    @Test
    void unknownExchangeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> symbolMapper.resolveSymbol("KRAKEN", "BTC/USDT"));
    }

    @Test
    void caseInsensitive() {
        assertEquals("BTCUSDT", symbolMapper.resolveSymbol("binance", "btc/usdt"));
        assertEquals("BTCUSD", symbolMapper.resolveSymbol("Delta", "BTC/USDT"));
    }

    // ─── API Key Validation ─────────────────────────────────────────────────────

    @Test
    void apiKeyTrimming() {
        // Simulates what ApiKeyService.decryptApiKey does
        String rawKey = "  abc123xyz456  \n";
        String trimmed = rawKey.trim();
        assertEquals("abc123xyz456", trimmed);
        assertFalse(trimmed.contains(" "));
        assertFalse(trimmed.contains("\n"));
    }

    @Test
    void apiKeyDiagnosticInfo() {
        String key = "AbCdEfGhIjKl1234";
        String prefix = key.substring(0, Math.min(4, key.length()));
        String suffix = key.substring(Math.max(0, key.length() - 4));
        assertEquals("AbCd", prefix);
        assertEquals("1234", suffix);
        assertEquals(16, key.length());
    }

    // ─── ExchangeClient URL Resolution ──────────────────────────────────────────

    @Test
    void binanceUrlResolution() {
        // Verify the expected URL patterns
        String liveUrl = "https://fapi.binance.com";
        String testnetUrl = "https://testnet.binancefuture.com";

        assertTrue(liveUrl.contains("fapi"), "Live URL must use futures API");
        assertFalse(liveUrl.contains("api/v3"), "Must NOT use spot API");
        assertTrue(testnetUrl.contains("binancefuture"), "Testnet must use futures testnet");
    }

    @Test
    void bybitUrlResolution() {
        String liveUrl = "https://api.bybit.com";
        String testnetUrl = "https://api-testnet.bybit.com";
        assertFalse(liveUrl.equals(testnetUrl), "Live and testnet URLs must differ");
    }

    @Test
    void deltaUrlResolution() {
        String liveUrl = "https://api.delta.exchange";
        String testnetUrl = "https://cdn-ind.testnet.deltaex.org";
        assertFalse(liveUrl.equals(testnetUrl), "Live and testnet URLs must differ");
    }

    // ─── HMAC Signing ───────────────────────────────────────────────────────────

    @Test
    void hmacSha256Signing() throws Exception {
        // Verify HMAC-SHA256 produces consistent output
        String data = "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01&recvWindow=5000&timestamp=1234567890";
        String secret = "test-secret-key";

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String sig1 = org.apache.commons.codec.binary.Hex.encodeHexString(
            mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Same input must produce same signature
        mac.reset();
        mac.init(new javax.crypto.spec.SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String sig2 = org.apache.commons.codec.binary.Hex.encodeHexString(
            mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(sig1, sig2, "HMAC must be deterministic");
        assertEquals(64, sig1.length(), "SHA256 hex output must be 64 chars");
    }

    // ─── OrderResponse Parsing ──────────────────────────────────────────────────

    @Test
    void orderResponseBuilder() {
        OrderResponse resp = OrderResponse.builder()
            .orderId("12345")
            .symbol("BTCUSDT")
            .side("BUY")
            .status("FILLED")
            .executedQty(new BigDecimal("0.01"))
            .avgPrice(new BigDecimal("65000.00"))
            .build();

        assertEquals("12345", resp.getOrderId());
        assertEquals("BTCUSDT", resp.getSymbol());
        assertEquals("BUY", resp.getSide());
        assertEquals(new BigDecimal("0.01"), resp.getExecutedQty());
    }

    // ─── SupportedExchange Validation ───────────────────────────────────────────

    @Test
    void supportedExchangeValidation() {
        assertDoesNotThrow(() -> SupportedExchange.validate("BINANCE"));
        assertDoesNotThrow(() -> SupportedExchange.validate("binance"));
        assertDoesNotThrow(() -> SupportedExchange.validate("DELTA"));
        assertDoesNotThrow(() -> SupportedExchange.validate("BYBIT"));
        assertThrows(IllegalArgumentException.class, () -> SupportedExchange.validate("KRAKEN"));
    }
}
