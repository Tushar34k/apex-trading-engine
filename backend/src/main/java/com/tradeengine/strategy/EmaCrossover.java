package com.tradeengine.strategy;

import lombok.Data;
import java.util.List;

/**
 * EMA Crossover Strategy with dynamic parameters.
 * BUY when fastEMA crosses above slowEMA (no open position).
 * SELL when fastEMA crosses below slowEMA (has open position).
 * Spot only. No short selling.
 */
public class EmaCrossover {

    public enum Signal { BUY, SELL, HOLD }

    @Data
    public static class SignalResult {
        private Signal signal;
        private double fastEmaValue;
        private double slowEmaValue;
        private double price;
        private String reason;
    }

    /**
     * Evaluate strategy with dynamic EMA periods.
     * @param closingPrices list of closing prices (oldest first)
     * @param fastPeriod fast EMA period (e.g. 9)
     * @param slowPeriod slow EMA period (e.g. 21)
     */
    public static SignalResult evaluate(List<Double> closingPrices, int fastPeriod, int slowPeriod) {
        SignalResult result = new SignalResult();
        result.setSignal(Signal.HOLD);

        int minCandles = slowPeriod + 2;
        if (closingPrices.size() < minCandles) {
            result.setReason("Insufficient data (need " + minCandles + "+ candles, got " + closingPrices.size() + ")");
            return result;
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();

        double currentFast = calculateEMA(prices, fastPeriod, prices.length - 1);
        double currentSlow = calculateEMA(prices, slowPeriod, prices.length - 1);
        double prevFast = calculateEMA(prices, fastPeriod, prices.length - 2);
        double prevSlow = calculateEMA(prices, slowPeriod, prices.length - 2);

        result.setFastEmaValue(currentFast);
        result.setSlowEmaValue(currentSlow);
        result.setPrice(prices[prices.length - 1]);

        boolean currentAbove = currentFast > currentSlow;
        boolean previousAbove = prevFast > prevSlow;

        if (currentAbove && !previousAbove) {
            result.setSignal(Signal.BUY);
            result.setReason(String.format("EMA(%d)=%.2f crossed above EMA(%d)=%.2f", fastPeriod, currentFast, slowPeriod, currentSlow));
        } else if (!currentAbove && previousAbove) {
            result.setSignal(Signal.SELL);
            result.setReason(String.format("EMA(%d)=%.2f crossed below EMA(%d)=%.2f", fastPeriod, currentFast, slowPeriod, currentSlow));
        } else {
            result.setReason(String.format("No crossover. EMA(%d)=%.2f, EMA(%d)=%.2f", fastPeriod, currentFast, slowPeriod, currentSlow));
        }

        return result;
    }

    private static double calculateEMA(double[] prices, int period, int endIndex) {
        double multiplier = 2.0 / (period + 1);
        int startIndex = Math.max(0, endIndex - period * 3);
        double sum = 0;
        for (int i = startIndex; i < startIndex + period && i <= endIndex; i++) {
            sum += prices[i];
        }
        double ema = sum / period;
        for (int i = startIndex + period; i <= endIndex; i++) {
            ema = (prices[i] - ema) * multiplier + ema;
        }
        return ema;
    }
}
