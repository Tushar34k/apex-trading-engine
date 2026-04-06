package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * RSI Strategy with ATR-based SL/TP.
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

        // BUY conditions
        boolean buySignal = false;
        String buyReason = null;

        if (!hasOpenPosition && prevRsi < buyThreshold && rsi >= buyThreshold) {
            buySignal = true;
            buyReason = String.format("RSI(%d)=%.1f crossed above oversold %.0f (prev=%.1f)", period, rsi, buyThreshold, prevRsi);
        } else if (!hasOpenPosition && rsi < buyThreshold - 5) {
            buySignal = true;
            buyReason = String.format("RSI(%d)=%.1f deeply oversold (threshold=%.0f)", period, rsi, buyThreshold);
        }

        if (buySignal) {
            double atr = StrategyUtils.calculateATR(allCandles, getInt(params, "atrPeriod", 14));
            double slMultiplier = getDouble(params, "slAtrMultiplier", 1.5);
            double rrRatio = getDouble(params, "rrRatio", 2.5);

            double stopLoss = price - (atr * slMultiplier);
            double takeProfit = price + (atr * slMultiplier * rrRatio);

            return new SignalResult(Signal.BUY, price,
                buyReason + String.format(" | SL=%.2f TP=%.2f ATR=%.2f", stopLoss, takeProfit, atr),
                stopLoss, takeProfit, null);
        }

        // SELL conditions
        if (hasOpenPosition && prevRsi > sellThreshold && rsi <= sellThreshold) {
            return new SignalResult(Signal.SELL, price,
                String.format("RSI(%d)=%.1f crossed below overbought %.0f (prev=%.1f)", period, rsi, sellThreshold, prevRsi));
        }
        if (hasOpenPosition && rsi > sellThreshold + 5) {
            return new SignalResult(Signal.SELL, price,
                String.format("RSI(%d)=%.1f extremely overbought (threshold=%.0f)", period, rsi, sellThreshold));
        }

        return new SignalResult(Signal.HOLD, price,
            String.format("RSI(%d)=%.1f — no signal (buy<%.0f, sell>%.0f)", period, rsi, buyThreshold, sellThreshold));
    }

    public static double calculateRSI(List<Double> prices, int period) {
        if (prices.size() < period + 1) return 50;

        double gainSum = 0, lossSum = 0;
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
