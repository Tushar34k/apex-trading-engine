package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Scalping EMA Strategy.
 * Uses fast EMAs (default EMA5/EMA13) for quick entries on small timeframes.
 * BUY when fastEMA crosses above slowEMA.
 * SELL when fastEMA crosses below slowEMA.
 */
public class ScalpingEma implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        int fastPeriod = getInt(params, "fastEma", 5);
        int slowPeriod = getInt(params, "slowEma", 13);

        int minCandles = slowPeriod + 2;
        if (closingPrices.size() < minCandles) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data for ScalpingEMA");
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();

        double currentFast = EmaCrossover.calculateEMA(prices, fastPeriod, prices.length - 1);
        double currentSlow = EmaCrossover.calculateEMA(prices, slowPeriod, prices.length - 1);
        double prevFast = EmaCrossover.calculateEMA(prices, fastPeriod, prices.length - 2);
        double prevSlow = EmaCrossover.calculateEMA(prices, slowPeriod, prices.length - 2);

        double price = prices[prices.length - 1];
        boolean currentAbove = currentFast > currentSlow;
        boolean previousAbove = prevFast > prevSlow;

        // Additional momentum filter: price must be above fast EMA for buy
        if (!hasOpenPosition && currentAbove && !previousAbove && price > currentFast) {
            return new SignalResult(Signal.BUY, price,
                String.format("ScalpEMA(%d)=%.2f crossed above EMA(%d)=%.2f, price above fast",
                    fastPeriod, currentFast, slowPeriod, currentSlow));
        } else if (hasOpenPosition && !currentAbove && previousAbove) {
            return new SignalResult(Signal.SELL, price,
                String.format("ScalpEMA(%d)=%.2f crossed below EMA(%d)=%.2f",
                    fastPeriod, currentFast, slowPeriod, currentSlow));
        }

        return new SignalResult(Signal.HOLD, price,
            String.format("ScalpEMA: No signal. EMA(%d)=%.2f, EMA(%d)=%.2f",
                fastPeriod, currentFast, slowPeriod, currentSlow));
    }

    private double lastPrice(List<Double> prices) {
        return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        if (params == null || !params.containsKey(key)) return defaultVal;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return defaultVal; }
    }
}
