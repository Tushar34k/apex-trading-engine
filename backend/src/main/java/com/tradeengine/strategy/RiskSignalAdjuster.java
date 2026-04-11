package com.tradeengine.strategy;

import java.util.Map;

/**
 * Applies user-configured SL floors to raw strategy signals before risk validation.
 */
public final class RiskSignalAdjuster {

    private RiskSignalAdjuster() {}

    public record AdjustedRiskSignal(
        double stopLossPrice,
        Double takeProfitPrice,
        Double takeProfitPercent,
        double atrSlPercent,
        double userSlPercent,
        double finalSlPercent
    ) {
        public boolean userFloorApplied() {
            return finalSlPercent > atrSlPercent;
        }
    }

    public static AdjustedRiskSignal forLongEntry(
        double entryPrice,
        Double signalStopLoss,
        Double signalTakeProfit,
        Map<String, Object> params
    ) {
        if (entryPrice <= 0) {
            throw new IllegalArgumentException("Entry price must be positive");
        }
        if (signalStopLoss == null || signalStopLoss <= 0) {
            throw new IllegalArgumentException("Signal stop loss must be positive");
        }

        double atrSlPercent = Math.abs(entryPrice - signalStopLoss) / entryPrice * 100;
        double userSlPercent = 0.0;
        Object configuredStopLoss = params.get("stopLossPercent");
        if (configuredStopLoss instanceof Number number) {
            userSlPercent = Math.max(0.0, number.doubleValue());
        }

        double finalSlPercent = Math.max(atrSlPercent, userSlPercent);
        double adjustedStopLoss = entryPrice * (1 - (finalSlPercent / 100.0));

        Double adjustedTakeProfitPrice = null;
        Double adjustedTakeProfitPercent = null;
        if (signalTakeProfit != null && signalTakeProfit > 0) {
            double tpPercent = Math.abs(signalTakeProfit - entryPrice) / entryPrice * 100;
            if (finalSlPercent > atrSlPercent && atrSlPercent > 0) {
                tpPercent = tpPercent * (finalSlPercent / atrSlPercent);
            }
            adjustedTakeProfitPercent = tpPercent;
            adjustedTakeProfitPrice = entryPrice * (1 + (tpPercent / 100.0));
        }

        return new AdjustedRiskSignal(
            adjustedStopLoss,
            adjustedTakeProfitPrice,
            adjustedTakeProfitPercent,
            atrSlPercent,
            userSlPercent,
            finalSlPercent
        );
    }
}