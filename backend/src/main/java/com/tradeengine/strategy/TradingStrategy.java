package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface. All strategies implement this.
 * evaluate() receives closing prices and dynamic params from bot config.
 */
public interface TradingStrategy {

    enum Signal { BUY, SELL, HOLD }

    record SignalResult(Signal signal, double price, String reason) {}

    /**
     * @param closingPrices closing prices oldest→newest
     * @param allCandles    full OHLCV data oldest→newest (each: [time, open, high, low, close, volume])
     * @param params        dynamic parameters from bot.strategyParams JSON
     * @param hasOpenPosition whether bot currently has an open position
     */
    SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                          Map<String, Object> params, boolean hasOpenPosition);
}
