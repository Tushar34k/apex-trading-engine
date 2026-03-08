package com.tradeengine.integration;

import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import com.tradeengine.service.PositionTracker;
import com.tradeengine.exchange.SymbolInfo;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests risk management components: kill switch, circuit breaker,
 * and symbol validation.
 */
class RiskManagementTest {

    // ─── Circuit Breaker ────────────────────────────────────────────────────────

    @Test
    @DisplayName("CircuitBreaker: opens after threshold failures")
    void circuitBreakerOpens() {
        CircuitBreakerService cb = new CircuitBreakerService();
        assertTrue(cb.isAllowed());

        for (int i = 0; i < 5; i++) cb.recordFailure();

        assertTrue(cb.isOpen());
        assertFalse(cb.isAllowed());
    }

    @Test
    @DisplayName("CircuitBreaker: stays closed below threshold")
    void circuitBreakerStaysClosed() {
        CircuitBreakerService cb = new CircuitBreakerService();
        for (int i = 0; i < 4; i++) cb.recordFailure();
        assertTrue(cb.isAllowed());
        assertFalse(cb.isOpen());
    }

    // ─── Kill Switch ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KillSwitch: activates and resets")
    void killSwitchActivateReset() {
        var botRepo = mock(com.tradeengine.repository.BotRepository.class);
        var notifService = mock(com.tradeengine.service.NotificationService.class);
        var execQueue = mock(com.tradeengine.execution.TradeExecutionQueue.class);

        when(botRepo.findByStatus("RUNNING")).thenReturn(java.util.List.of());

        KillSwitchService ks = new KillSwitchService(botRepo, notifService, execQueue);

        assertFalse(ks.isActive());

        ks.activate("Test reason");
        assertTrue(ks.isActive());
        assertEquals("Test reason", ks.getActivationReason());

        ks.reset();
        assertFalse(ks.isActive());
        assertNull(ks.getActivationReason());
    }

    @Test
    @DisplayName("KillSwitch: exchange error rate triggers activation")
    void killSwitchErrorRate() {
        var botRepo = mock(com.tradeengine.repository.BotRepository.class);
        var notifService = mock(com.tradeengine.service.NotificationService.class);
        var execQueue = mock(com.tradeengine.execution.TradeExecutionQueue.class);

        when(botRepo.findByStatus("RUNNING")).thenReturn(java.util.List.of());

        KillSwitchService ks = new KillSwitchService(botRepo, notifService, execQueue);
        ks.setMaxExchangeErrorsPerMinute(3);

        for (int i = 0; i < 3; i++) ks.recordExchangeError();

        assertTrue(ks.isActive());
    }

    @Test
    @DisplayName("KillSwitch: daily loss check triggers activation")
    void killSwitchDailyLoss() {
        var botRepo = mock(com.tradeengine.repository.BotRepository.class);
        var notifService = mock(com.tradeengine.service.NotificationService.class);
        var execQueue = mock(com.tradeengine.execution.TradeExecutionQueue.class);

        when(botRepo.findByStatus("RUNNING")).thenReturn(java.util.List.of());

        KillSwitchService ks = new KillSwitchService(botRepo, notifService, execQueue);
        ks.setMaxDailyLossPercent(5.0);

        ks.checkDailyLoss(new BigDecimal("10000"), new BigDecimal("-600")); // 6% loss

        assertTrue(ks.isActive());
    }

    // ─── Symbol Validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SymbolInfo: validates quantity against LOT_SIZE")
    void symbolInfoValidation() {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setMinQty(new BigDecimal("0.00001"));
        info.setMaxQty(new BigDecimal("100"));
        info.setStepSize(new BigDecimal("0.00001"));
        info.setMinNotional(new BigDecimal("10"));

        assertNull(info.validate(new BigDecimal("0.001"), new BigDecimal("42000")));
        assertNotNull(info.validate(new BigDecimal("0.000001"), new BigDecimal("42000"))); // below minQty
        assertNotNull(info.validate(new BigDecimal("0.0001"), new BigDecimal("1"))); // below minNotional
    }

    @Test
    @DisplayName("SymbolInfo: rounds quantity to stepSize")
    void symbolInfoRounding() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.001"));

        assertEquals(new BigDecimal("0.123"), info.roundQuantity(new BigDecimal("0.1239")));
        assertEquals(new BigDecimal("1.000"), info.roundQuantity(new BigDecimal("1.0009")));
    }
}
