package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * EMA Crossover Strategy.
 * BUY when fastEMA crosses above slowEMA (no open position).
 * SELL when fastEMA crosses below slowEMA (has open position).
 * Spot only. No short selling.
 *
 * Now includes ATR-based dynamic SL/TP for every BUY signal.
 */
public class EmaCrossover implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        int fastPeriod = getInt(params, "fastEma", 9);
        int slowPeriod = getInt(params, "slowEma", 21);

        int minCandles = slowPeriod + 2;
        if (closingPrices.size() < minCandles) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data (need " + minCandles + ", got " + closingPrices.size() + ")");
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();

        double currentFast = calculateEMA(prices, fastPeriod, prices.length - 1);
        double currentSlow = calculateEMA(prices, slowPeriod, prices.length - 1);
        double prevFast = calculateEMA(prices, fastPeriod, prices.length - 2);
        double prevSlow = calculateEMA(prices, slowPeriod, prices.length - 2);

        double price = prices[prices.length - 1];
        boolean currentAbove = currentFast > currentSlow;
        boolean previousAbove = prevFast > prevSlow;

        if (!hasOpenPosition && currentAbove && !previousAbove) {
            // Compute ATR-based SL/TP
            double atr = StrategyUtils.calculateATR(allCandles, getInt(params, "atrPeriod", 14));
            double slMultiplier = getDouble(params, "slAtrMultiplier", 1.5);
            double rrRatio = getDouble(params, "rrRatio", 2.5);

            double stopLoss = price - (atr * slMultiplier);
            double takeProfit = price + (atr * slMultiplier * rrRatio);

            return new SignalResult(Signal.BUY, price,
                String.format("EMA(%d)=%.2f crossed above EMA(%d)=%.2f | SL=%.2f TP=%.2f (ATR=%.2f)",
                    fastPeriod, currentFast, slowPeriod, currentSlow, stopLoss, takeProfit, atr),
                stopLoss, takeProfit, null);
        } else if (hasOpenPosition && !currentAbove && previousAbove) {
            return new SignalResult(Signal.SELL, price,
                String.format("EMA(%d)=%.2f crossed below EMA(%d)=%.2f", fastPeriod, currentFast, slowPeriod, currentSlow));
        }

        return new SignalResult(Signal.HOLD, price,
            String.format("No crossover. EMA(%d)=%.2f, EMA(%d)=%.2f", fastPeriod, currentFast, slowPeriod, currentSlow));
    }

    public static double calculateEMA(double[] prices, int period, int endIndex) {
        double multiplier = 2.0 / (period + 1);
        int startIndex = Math.max(0, endIndex - period * 3);
        double sum = 0;
        int count = 0;
        for (int i = startIndex; i < startIndex + period && i <= endIndex; i++) {
            sum += prices[i];
            count++;
        }
        double ema = sum / count;
        for (int i = startIndex + period; i <= endIndex; i++) {
            ema = (prices[i] - ema) * multiplier + ema;
        }
        return ema;
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

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        if (params == null || !params.containsKey(key)) return defaultVal;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return defaultVal; }
    }
}
