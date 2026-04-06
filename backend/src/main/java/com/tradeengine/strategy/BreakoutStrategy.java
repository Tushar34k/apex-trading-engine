package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Breakout Strategy with structure-based SL/TP.
 * BUY when price breaks above the highest high of lookback period.
 * SL placed below the breakout candle's low. TP at 2.5x risk.
 */
public class BreakoutStrategy implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        int lookback = getInt(params, "breakoutLookback", 20);
        double confirmFactor = getDouble(params, "breakoutConfirm", 0.2);

        if (allCandles.size() < lookback + 2) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data for Breakout (need " + (lookback + 2) + ", got " + allCandles.size() + ")");
        }

        int end = allCandles.size() - 1;
        int start = end - lookback;

        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;

        for (int i = start; i < end; i++) {
            double high = allCandles.get(i)[2];
            double low = allCandles.get(i)[3];
            if (high > highestHigh) highestHigh = high;
            if (low < lowestLow) lowestLow = low;
        }

        double currentClose = allCandles.get(end)[4];
        double currentLow = allCandles.get(end)[3];
        double breakoutThreshold = highestHigh * (1 + confirmFactor / 100);
        double breakdownThreshold = lowestLow * (1 - confirmFactor / 100);

        if (!hasOpenPosition && currentClose > breakoutThreshold) {
            // SL below the breakout candle's low or ATR-based, whichever is wider
            double atr = StrategyUtils.calculateATR(allCandles, getInt(params, "atrPeriod", 14));
            double slMultiplier = getDouble(params, "slAtrMultiplier", 1.5);
            double rrRatio = getDouble(params, "rrRatio", 2.5);

            double atrSl = currentClose - (atr * slMultiplier);
            double structureSl = currentLow - (atr * 0.2); // just below breakout candle low
            double stopLoss = Math.min(atrSl, structureSl); // more conservative (wider) SL
            double risk = currentClose - stopLoss;
            double takeProfit = currentClose + (risk * rrRatio);

            return new SignalResult(Signal.BUY, currentClose,
                String.format("Breakout UP: price %.2f > resistance %.2f (+%.1f%%) | SL=%.2f TP=%.2f ATR=%.2f",
                    currentClose, highestHigh, confirmFactor, stopLoss, takeProfit, atr),
                stopLoss, takeProfit, null);
        }

        if (hasOpenPosition && currentClose < breakdownThreshold) {
            return new SignalResult(Signal.SELL, currentClose,
                String.format("Breakdown: price %.2f < support %.2f (-%.1f%%) → SELL",
                    currentClose, lowestLow, confirmFactor));
        }

        return new SignalResult(Signal.HOLD, currentClose,
            String.format("Breakout: No signal. Price=%.2f, Range=[%.2f - %.2f], Lookback=%d",
                currentClose, lowestLow, highestHigh, lookback));
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

    private double getDouble(Map<String, Object> params, String key, double def) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v != null) try { return Double.parseDouble(v.toString()); } catch (Exception ignored) {}
        return def;
    }
}
