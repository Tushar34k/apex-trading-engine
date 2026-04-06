package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Order Book Imbalance Strategy with ATR-based SL/TP.
 * Generates BUY signals when bid pressure significantly exceeds ask pressure.
 */
public class OrderBookStrategy implements TradingStrategy {

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                  Map<String, Object> params, boolean hasOpenPosition) {
        if (closingPrices.size() < 10) {
            return new SignalResult(Signal.HOLD, last(closingPrices), "Insufficient data for order book analysis");
        }

        double imbalanceThreshold = getParam(params, "imbalanceThreshold", 1.5);
        double currentPrice = last(closingPrices);

        double buyVolume = 0;
        double sellVolume = 0;
        int lookback = Math.min(10, allCandles.size());

        for (int i = allCandles.size() - lookback; i < allCandles.size(); i++) {
            double[] candle = allCandles.get(i);
            double open = candle[1];
            double close = candle[4];
            double volume = candle[5];
            if (close > open) buyVolume += volume;
            else sellVolume += volume;
        }

        double bidPressure = getParam(params, "bidVolume", buyVolume);
        double askPressure = getParam(params, "askVolume", sellVolume);
        if (askPressure <= 0) askPressure = 1;
        double imbalanceRatio = bidPressure / askPressure;

        if (!hasOpenPosition && imbalanceRatio >= imbalanceThreshold) {
            double atr = StrategyUtils.calculateATR(allCandles, getIntParam(params, "atrPeriod", 14));
            double slMultiplier = getParam(params, "slAtrMultiplier", 1.5);
            double rrRatio = getParam(params, "rrRatio", 2.5);

            double stopLoss = currentPrice - (atr * slMultiplier);
            double takeProfit = currentPrice + (atr * slMultiplier * rrRatio);

            return new SignalResult(Signal.BUY, currentPrice,
                String.format("OrderBook BUY: bid/ask=%.2f (threshold=%.2f) | SL=%.2f TP=%.2f ATR=%.2f",
                    imbalanceRatio, imbalanceThreshold, stopLoss, takeProfit, atr),
                stopLoss, takeProfit, null);
        }

        if (hasOpenPosition && (1.0 / imbalanceRatio) >= imbalanceThreshold) {
            return new SignalResult(Signal.SELL, currentPrice,
                String.format("OrderBook SELL: ask/bid=%.2f (threshold=%.2f)", 1.0 / imbalanceRatio, imbalanceThreshold));
        }

        return new SignalResult(Signal.HOLD, currentPrice,
            String.format("Order book neutral: bid/ask=%.2f", imbalanceRatio));
    }

    private double last(List<Double> prices) {
        return prices.get(prices.size() - 1);
    }

    private double getParam(Map<String, Object> params, String key, double defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultVal;
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultVal;
    }
}
