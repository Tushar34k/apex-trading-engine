package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Order Book Imbalance Strategy.
 * Generates BUY signals when bid pressure significantly exceeds ask pressure,
 * and SELL signals when ask pressure dominates.
 *
 * Parameters (from strategyParams):
 * - imbalanceThreshold (default 1.5): ratio threshold for signal generation
 * - volumeMinimum (default 0): minimum total volume to consider signal valid
 *
 * Note: This strategy uses closing prices as a proxy when order book data
 * is not available. In production, it reads bid/ask volumes from
 * the MarketDataStreamService's depth cache (exchange-agnostic).
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

        // Analyze recent volume profile from candle data as proxy for order flow
        double buyVolume = 0;
        double sellVolume = 0;
        int lookback = Math.min(10, allCandles.size());

        for (int i = allCandles.size() - lookback; i < allCandles.size(); i++) {
            double[] candle = allCandles.get(i);
            double open = candle[1];
            double close = candle[4];
            double volume = candle[5];

            if (close > open) {
                buyVolume += volume;
            } else {
                sellVolume += volume;
            }
        }

        // Read order book imbalance from params if injected by BinanceStreamClient
        double bidPressure = getParam(params, "bidVolume", buyVolume);
        double askPressure = getParam(params, "askVolume", sellVolume);

        if (askPressure <= 0) askPressure = 1;
        double imbalanceRatio = bidPressure / askPressure;

        if (!hasOpenPosition && imbalanceRatio >= imbalanceThreshold) {
            return new SignalResult(Signal.BUY, currentPrice,
                String.format("Order book imbalance BUY: bid/ask ratio=%.2f (threshold=%.2f)", imbalanceRatio, imbalanceThreshold));
        }

        if (hasOpenPosition && (1.0 / imbalanceRatio) >= imbalanceThreshold) {
            return new SignalResult(Signal.SELL, currentPrice,
                String.format("Order book imbalance SELL: ask/bid ratio=%.2f (threshold=%.2f)", 1.0 / imbalanceRatio, imbalanceThreshold));
        }

        return new SignalResult(Signal.HOLD, currentPrice,
            String.format("Order book neutral: bid/ask ratio=%.2f", imbalanceRatio));
    }

    private double last(List<Double> prices) {
        return prices.get(prices.size() - 1);
    }

    private double getParam(Map<String, Object> params, String key, double defaultVal) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultVal;
    }
}
