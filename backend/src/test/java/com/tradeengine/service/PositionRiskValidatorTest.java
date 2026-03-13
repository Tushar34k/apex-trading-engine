package com.tradeengine.service;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PositionRiskValidator: position limits, single trade limits,
 * and duplicate order lock.
 */
class PositionRiskValidatorTest {

    private PositionRiskValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PositionRiskValidator();
        validator.setMaxPositionPercent(20);
        validator.setMaxSingleTradePercent(5);
    }

    // ── Position size checks ──

    @Test
    @DisplayName("Order within single trade limit passes")
    void withinSingleTradeLimit() {
        // Balance=10000, maxSingleTrade=5% → 500. Order=0.005*65000=325 → OK
        String result = validator.validatePositionSize("BINANCE", "BTCUSDT",
            new BigDecimal("0.005"), new BigDecimal("65000"), new BigDecimal("10000"));
        assertNull(result);
    }

    @Test
    @DisplayName("Order exceeding single trade limit is rejected")
    void exceedsSingleTradeLimit() {
        // Balance=10000, maxSingleTrade=5% → 500. Order=0.01*65000=650 → REJECTED
        String result = validator.validatePositionSize("BINANCE", "BTCUSDT",
            new BigDecimal("0.01"), new BigDecimal("65000"), new BigDecimal("10000"));
        assertNotNull(result);
        assertTrue(result.contains("maxSingleTradePercent"));
    }

    @Test
    @DisplayName("Order exceeding max position limit is rejected")
    void exceedsMaxPositionLimit() {
        // Balance=1000, maxPosition=20% → 200. Order=1*300=300 → REJECTED
        validator.setMaxSingleTradePercent(50); // raise single trade to test position limit
        String result = validator.validatePositionSize("BINANCE", "BTCUSDT",
            new BigDecimal("1"), new BigDecimal("300"), new BigDecimal("1000"));
        assertNotNull(result);
        assertTrue(result.contains("maxPositionPercent"));
    }

    @Test
    @DisplayName("Null balance skips validation")
    void nullBalanceSkips() {
        String result = validator.validatePositionSize("BINANCE", "BTCUSDT",
            new BigDecimal("100"), new BigDecimal("65000"), null);
        assertNull(result);
    }

    @Test
    @DisplayName("Null price skips validation (market order)")
    void nullPriceSkips() {
        String result = validator.validatePositionSize("BINANCE", "BTCUSDT",
            new BigDecimal("0.01"), null, new BigDecimal("10000"));
        assertNull(result);
    }

    // ── Duplicate order lock ──

    @Test
    @DisplayName("First order acquires lock successfully")
    void firstOrderAcquiresLock() {
        UUID botId = UUID.randomUUID();
        assertTrue(validator.acquireOrderLock(botId, "BTCUSDT"));
    }

    @Test
    @DisplayName("Second order within 2s is blocked")
    void secondOrderBlocked() {
        UUID botId = UUID.randomUUID();
        assertTrue(validator.acquireOrderLock(botId, "BTCUSDT"));
        assertFalse(validator.acquireOrderLock(botId, "BTCUSDT"));
    }

    @Test
    @DisplayName("Different symbol is not blocked")
    void differentSymbolNotBlocked() {
        UUID botId = UUID.randomUUID();
        assertTrue(validator.acquireOrderLock(botId, "BTCUSDT"));
        assertTrue(validator.acquireOrderLock(botId, "ETHUSDT"));
    }

    @Test
    @DisplayName("Different bot is not blocked")
    void differentBotNotBlocked() {
        assertTrue(validator.acquireOrderLock(UUID.randomUUID(), "BTCUSDT"));
        assertTrue(validator.acquireOrderLock(UUID.randomUUID(), "BTCUSDT"));
    }

    @Test
    @DisplayName("Lock expires after cooldown")
    void lockExpires() throws Exception {
        UUID botId = UUID.randomUUID();
        assertTrue(validator.acquireOrderLock(botId, "BTCUSDT"));
        Thread.sleep(2100); // Wait for 2s cooldown
        assertTrue(validator.acquireOrderLock(botId, "BTCUSDT"));
    }

    @Test
    @DisplayName("Cleanup removes expired locks")
    void cleanupWorks() throws Exception {
        UUID botId = UUID.randomUUID();
        validator.acquireOrderLock(botId, "BTCUSDT");
        Thread.sleep(4100); // Wait for expiry (2x lock time)
        validator.cleanupLocks();
        assertTrue(validator.acquireOrderLock(botId, "BTCUSDT"));
    }
}
