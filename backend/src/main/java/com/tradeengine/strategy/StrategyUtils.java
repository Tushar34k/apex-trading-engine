package com.tradeengine.strategy;

import java.util.List;

/**
 * Shared utility methods for all trading strategies.
 * Provides ATR calculation, swing high/low detection, and other common indicators.
 */
public final class StrategyUtils {

    private StrategyUtils() {}

    /**
     * Calculate Average True Range (ATR) from OHLCV candles.
     * Each candle: [time, open, high, low, close, volume]
     *
     * @param candles OHLCV data oldest→newest
     * @param period  ATR period (default 14)
     * @return ATR value, or 0 if insufficient data
     */
    public static double calculateATR(List<double[]> candles, int period) {
        if (candles == null || candles.size() < period + 1) return 0;

        int start = candles.size() - period - 1;
        double atrSum = 0;
        int count = 0;

        for (int i = start + 1; i < candles.size(); i++) {
            double high = candles.get(i)[2];
            double low = candles.get(i)[3];
            double prevClose = candles.get(i - 1)[4];

            double tr = Math.max(high - low,
                        Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            atrSum += tr;
            count++;
        }

        return count > 0 ? atrSum / count : 0;
    }

    /**
     * Find the lowest low in the last N candles (for support-based SL).
     */
    public static double recentSwingLow(List<double[]> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0;
        int start = Math.max(0, candles.size() - lookback);
        double lowest = Double.MAX_VALUE;
        for (int i = start; i < candles.size(); i++) {
            double low = candles.get(i)[3];
            if (low < lowest) lowest = low;
        }
        return lowest;
    }

    /**
     * Find the highest high in the last N candles (for resistance-based TP).
     */
    public static double recentSwingHigh(List<double[]> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0;
        int start = Math.max(0, candles.size() - lookback);
        double highest = Double.MIN_VALUE;
        for (int i = start; i < candles.size(); i++) {
            double high = candles.get(i)[2];
            if (high > highest) highest = high;
        }
        return highest;
    }
}
