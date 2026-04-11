package com.tradeengine.strategy;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.OrderRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.service.RiskManagementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RiskSignalAdjusterTest {

    @Test
    void userStopLossFloorIsAppliedBeforeRiskValidation() {
        Map<String, Object> params = new HashMap<>();
        params.put("stopLossPercent", 1.0);

        RiskSignalAdjuster.AdjustedRiskSignal adjusted =
            RiskSignalAdjuster.forLongEntry(50000.0, 49950.0, 50100.0, params);

        assertTrue(adjusted.userFloorApplied());
        assertEquals(0.1, adjusted.atrSlPercent(), 1e-9);
        assertEquals(1.0, adjusted.userSlPercent(), 1e-9);
        assertEquals(1.0, adjusted.finalSlPercent(), 1e-9);
        assertEquals(49500.0, adjusted.stopLossPrice(), 1e-6);
        assertEquals(51000.0, adjusted.takeProfitPrice(), 1e-6);
        assertEquals(2.0, adjusted.takeProfitPercent(), 1e-9);

        RiskManagementService riskService = new RiskManagementService(
            mock(PositionRepository.class),
            mock(OrderRepository.class)
        );

        TradingBot bot = new TradingBot();
        bot.setId(UUID.randomUUID());
        bot.setTradeSizePercent(BigDecimal.ONE);

        var check = riskService.validateRiskReward(
            bot,
            50000.0,
            adjusted.stopLossPrice(),
            adjusted.takeProfitPrice(),
            params
        );

        assertTrue(check.allowed());
    }
}