package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * RSI Strategy.
 * BUY when RSI drops below buyThreshold (oversold).
 * SELL when RSI rises above sellThreshold (overbought).
 */
public class RsiStrategy implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        int period = getInt(params, "rsiPeriod", 14);
        double buyThreshold = getDouble(params, "rsiBuyThreshold", 30);
        double sellThreshold = getDouble(params, "rsiSellThreshold", 70);

        if (closingPrices.size() < period + 2) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data for RSI (need " + (period + 2) + ", got " + closingPrices.size() + ")");
        }

        double rsi = calculateRSI(closingPrices, period);
        double prevRsi = calculateRSI(closingPrices.subList(0, closingPrices.size() - 1), period);
        double price = lastPrice(closingPrices);

        // BUY: RSI crosses up through buyThreshold (was below, now above or at)
        if (!hasOpenPosition && prevRsi < buyThreshold && rsi >= buyThreshold) {
            return new SignalResult(Signal.BUY, price,
                String.format("RSI(%d)=%.1f crossed above oversold level %.0f (prev=%.1f) → BUY signal",
                    period, rsi, buyThreshold, prevRsi));
        }

        // Also BUY if RSI is deeply oversold
        if (!hasOpenPosition && rsi < buyThreshold - 5) {
            return new SignalResult(Signal.BUY, price,
                String.format("RSI(%d)=%.1f deeply oversold (threshold=%.0f) → BUY signal",
                    period, rsi, buyThreshold));
        }

        // SELL: RSI crosses down through sellThreshold
        if (hasOpenPosition && prevRsi > sellThreshold && rsi <= sellThreshold) {
            return new SignalResult(Signal.SELL, price,
                String.format("RSI(%d)=%.1f crossed below overbought level %.0f (prev=%.1f) → SELL signal",
                    period, rsi, sellThreshold, prevRsi));
        }

        // Also SELL if RSI is extremely overbought
        if (hasOpenPosition && rsi > sellThreshold + 5) {
            return new SignalResult(Signal.SELL, price,
                String.format("RSI(%d)=%.1f extremely overbought (threshold=%.0f) → SELL signal",
                    period, rsi, sellThreshold));
        }

        return new SignalResult(Signal.HOLD, price,
            String.format("RSI(%d)=%.1f — no signal (buy<%.0f, sell>%.0f)",
                period, rsi, buyThreshold, sellThreshold));
    }

    public static double calculateRSI(List<Double> prices, int period) {
        if (prices.size() < period + 1) return 50; // neutral default

        double gainSum = 0, lossSum = 0;

        // Initial average gain/loss
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
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
