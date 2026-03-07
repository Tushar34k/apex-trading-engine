package com.tradeengine.strategy;

import java.util.*;

/**
 * Support/Resistance Strategy.
 * Detects S/R levels from recent swing highs/lows.
 * BUY when price bounces off support.
 * SELL when price rejects resistance.
 */
public class SupportResistance implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        int lookback = getInt(params, "lookback", 50);
        double tolerance = getDouble(params, "tolerance", 0.003); // 0.3%

        if (allCandles.size() < lookback) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data for S/R (" + allCandles.size() + "/" + lookback + ")");
        }

        // Use last 'lookback' candles
        List<double[]> recent = allCandles.subList(allCandles.size() - lookback, allCandles.size());
        double currentPrice = recent.get(recent.size() - 1)[4]; // close

        // Find swing lows (support) and swing highs (resistance)
        List<Double> supports = new ArrayList<>();
        List<Double> resistances = new ArrayList<>();

        for (int i = 2; i < recent.size() - 2; i++) {
            double high = recent.get(i)[2];
            double low = recent.get(i)[3];

            // Swing high
            if (high > recent.get(i - 1)[2] && high > recent.get(i - 2)[2]
                && high > recent.get(i + 1)[2] && high > recent.get(i + 2)[2]) {
                resistances.add(high);
            }
            // Swing low
            if (low < recent.get(i - 1)[3] && low < recent.get(i - 2)[3]
                && low < recent.get(i + 1)[3] && low < recent.get(i + 2)[3]) {
                supports.add(low);
            }
        }

        if (supports.isEmpty() && resistances.isEmpty()) {
            return new SignalResult(Signal.HOLD, currentPrice, "No S/R levels detected");
        }

        // Find nearest support and resistance
        Double nearestSupport = supports.stream()
            .filter(s -> s < currentPrice)
            .max(Comparator.naturalOrder()).orElse(null);

        Double nearestResistance = resistances.stream()
            .filter(r -> r > currentPrice)
            .min(Comparator.naturalOrder()).orElse(null);

        // BUY: price near support (within tolerance)
        if (!hasOpenPosition && nearestSupport != null) {
            double distPct = (currentPrice - nearestSupport) / nearestSupport;
            if (distPct >= 0 && distPct <= tolerance) {
                return new SignalResult(Signal.BUY, currentPrice,
                    String.format("Price %.2f bouncing from support %.2f (%.2f%%)",
                        currentPrice, nearestSupport, distPct * 100));
            }
        }

        // SELL: price near resistance (within tolerance)
        if (hasOpenPosition && nearestResistance != null) {
            double distPct = (nearestResistance - currentPrice) / nearestResistance;
            if (distPct >= 0 && distPct <= tolerance) {
                return new SignalResult(Signal.SELL, currentPrice,
                    String.format("Price %.2f rejecting resistance %.2f (%.2f%%)",
                        currentPrice, nearestResistance, distPct * 100));
            }
        }

        return new SignalResult(Signal.HOLD, currentPrice,
            String.format("S/R: No signal. Price=%.2f, Support=%s, Resistance=%s",
                currentPrice,
                nearestSupport != null ? String.format("%.2f", nearestSupport) : "none",
                nearestResistance != null ? String.format("%.2f", nearestResistance) : "none"));
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
