package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface. All strategies implement this.
 * evaluate() receives closing prices and dynamic params from bot config.
 */
public interface TradingStrategy {

    enum Signal { BUY, SELL, HOLD }

    record SignalResult(Signal signal, double price, String reason,
                        Double stopLoss, Double takeProfit, String confidence) {
        /** Backward-compatible constructor for existing strategies */
        SignalResult(Signal signal, double price, String reason) {
            this(signal, price, reason, null, null, null);
        }
    }

    /**
     * Original single-timeframe evaluate (backward-compatible).
     *
     * @param closingPrices closing prices oldest→newest
     * @param allCandles    full OHLCV data oldest→newest (each: [time, open, high, low, close, volume])
     * @param params        dynamic parameters from bot.strategyParams JSON
     * @param hasOpenPosition whether bot currently has an open position
     */
    SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                          Map<String, Object> params, boolean hasOpenPosition);

    /**
     * Multi-timeframe evaluate with trend and macro confirmation candles.
     * Default implementation delegates to single-timeframe evaluate (backward-compatible).
     *
     * @param entryPrices   closing prices from entry timeframe oldest→newest
     * @param entryCandles  full OHLCV from entry timeframe
     * @param trendCandles  full OHLCV from trend confirmation timeframe (e.g. 15m), may be null/empty
     * @param macroCandles  full OHLCV from macro trend timeframe (e.g. 1h), may be null/empty
     * @param params        dynamic parameters from bot.strategyParams JSON
     * @param hasOpenPosition whether bot currently has an open position
     */
    default SignalResult evaluate(List<Double> entryPrices, List<double[]> entryCandles,
                                  List<double[]> trendCandles, List<double[]> macroCandles,
                                  Map<String, Object> params, boolean hasOpenPosition) {
        // Default: ignore higher timeframes, use entry only
        return evaluate(entryPrices, entryCandles, params, hasOpenPosition);
    }
}
