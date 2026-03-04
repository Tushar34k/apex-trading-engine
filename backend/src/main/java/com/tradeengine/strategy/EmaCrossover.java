package com.tradeengine.strategy;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * EMA 9/21 Crossover Strategy
 * BUY when EMA9 crosses above EMA21
 * SELL when EMA9 crosses below EMA21
 */
public class EmaCrossover {

    public enum Signal { BUY, SELL, HOLD }

    @Data
    public static class SignalResult {
        private Signal signal;
        private double ema9;
        private double ema21;
        private double price;
        private String reason;
    }

    /**
     * Evaluate the strategy given a list of closing prices (oldest first).
     * Needs at least 22 candles (21 for EMA + 1 for previous crossover check).
     */
    public static SignalResult evaluate(List<Double> closingPrices) {
        SignalResult result = new SignalResult();
        result.setSignal(Signal.HOLD);

        if (closingPrices.size() < 22) {
            result.setReason("Insufficient data (need 22+ candles)");
            return result;
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();

        // Calculate current and previous EMA values
        double currentEma9 = calculateEMA(prices, 9, prices.length - 1);
        double currentEma21 = calculateEMA(prices, 21, prices.length - 1);
        double prevEma9 = calculateEMA(prices, 9, prices.length - 2);
        double prevEma21 = calculateEMA(prices, 21, prices.length - 2);

        result.setEma9(currentEma9);
        result.setEma21(currentEma21);
        result.setPrice(prices[prices.length - 1]);

        // Crossover detection
        boolean currentAbove = currentEma9 > currentEma21;
        boolean previousAbove = prevEma9 > prevEma21;

        if (currentAbove && !previousAbove) {
            result.setSignal(Signal.BUY);
            result.setReason(String.format("EMA9 (%.2f) crossed above EMA21 (%.2f)", currentEma9, currentEma21));
        } else if (!currentAbove && previousAbove) {
            result.setSignal(Signal.SELL);
            result.setReason(String.format("EMA9 (%.2f) crossed below EMA21 (%.2f)", currentEma9, currentEma21));
        } else {
            result.setReason(String.format("No crossover. EMA9=%.2f, EMA21=%.2f", currentEma9, currentEma21));
        }

        return result;
    }

    private static double calculateEMA(double[] prices, int period, int endIndex) {
        double multiplier = 2.0 / (period + 1);

        // Start with SMA for the first 'period' values
        double sum = 0;
        int startIndex = Math.max(0, endIndex - period * 3); // Use enough history
        for (int i = startIndex; i < startIndex + period && i <= endIndex; i++) {
            sum += prices[i];
        }
        double ema = sum / period;

        // Apply EMA formula
        for (int i = startIndex + period; i <= endIndex; i++) {
            ema = (prices[i] - ema) * multiplier + ema;
        }

        return ema;
    }
}
