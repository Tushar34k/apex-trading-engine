package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * MACD Strategy.
 * BUY when MACD line crosses above signal line.
 * SELL when MACD line crosses below signal line.
 * 
 * Params: macdFast (12), macdSlow (26), macdSignal (9)
 */
public class MacdStrategy implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        int fastPeriod = getInt(params, "macdFast", 12);
        int slowPeriod = getInt(params, "macdSlow", 26);
        int signalPeriod = getInt(params, "macdSignal", 9);

        int minCandles = slowPeriod + signalPeriod + 2;
        if (closingPrices.size() < minCandles) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data for MACD (need " + minCandles + ", got " + closingPrices.size() + ")");
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();

        // Current MACD
        double[] macdCurrent = calculateMACD(prices, fastPeriod, slowPeriod, signalPeriod, prices.length - 1);
        // Previous MACD
        double[] macdPrev = calculateMACD(prices, fastPeriod, slowPeriod, signalPeriod, prices.length - 2);

        double macdLine = macdCurrent[0];
        double signalLine = macdCurrent[1];
        double histogram = macdCurrent[2];
        double prevMacdLine = macdPrev[0];
        double prevSignalLine = macdPrev[1];

        double price = prices[prices.length - 1];
        boolean currentAbove = macdLine > signalLine;
        boolean prevAbove = prevMacdLine > prevSignalLine;

        // BUY: MACD crosses above signal line
        if (!hasOpenPosition && currentAbove && !prevAbove) {
            return new SignalResult(Signal.BUY, price,
                String.format("MACD(%.4f) crossed above Signal(%.4f), histogram=%.4f → BUY",
                    macdLine, signalLine, histogram));
        }

        // SELL: MACD crosses below signal line
        if (hasOpenPosition && !currentAbove && prevAbove) {
            return new SignalResult(Signal.SELL, price,
                String.format("MACD(%.4f) crossed below Signal(%.4f), histogram=%.4f → SELL",
                    macdLine, signalLine, histogram));
        }

        return new SignalResult(Signal.HOLD, price,
            String.format("MACD: no crossover. MACD=%.4f, Signal=%.4f, Hist=%.4f",
                macdLine, signalLine, histogram));
    }

    /**
     * Returns [macdLine, signalLine, histogram]
     */
    private double[] calculateMACD(double[] prices, int fast, int slow, int signal, int endIndex) {
        double fastEma = EmaCrossover.calculateEMA(prices, fast, endIndex);
        double slowEma = EmaCrossover.calculateEMA(prices, slow, endIndex);
        double macdLine = fastEma - slowEma;

        // Calculate signal line (EMA of MACD values)
        // We need a series of MACD values to compute the signal EMA
        int seriesLen = signal + 5; // extra buffer
        double[] macdSeries = new double[seriesLen];
        for (int i = 0; i < seriesLen; i++) {
            int idx = endIndex - (seriesLen - 1 - i);
            if (idx < slow) idx = slow;
            double f = EmaCrossover.calculateEMA(prices, fast, idx);
            double s = EmaCrossover.calculateEMA(prices, slow, idx);
            macdSeries[i] = f - s;
        }

        // Signal = EMA of macdSeries
        double multiplier = 2.0 / (signal + 1);
        double signalEma = macdSeries[0];
        for (int i = 1; i < macdSeries.length; i++) {
            signalEma = (macdSeries[i] - signalEma) * multiplier + signalEma;
        }

        return new double[]{macdLine, signalEma, macdLine - signalEma};
    }

    private double lastPrice(List<Double> prices) {
        return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
    }

    private int getInt(Map<String, Object> params, String key, int def) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) try { return Integer.parseInt(v.toString()); } catch (Exception ignored) {}
        return def;
    }
}
