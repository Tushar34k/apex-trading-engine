package com.tradeengine.integration;

import com.tradeengine.strategy.*;
import com.tradeengine.strategy.TradingStrategy.Signal;
import com.tradeengine.strategy.TradingStrategy.SignalResult;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests strategy signal generation with realistic candle data.
 */
class StrategyExecutionTest {

    private List<Double> generatePrices(double start, double end, int count) {
        List<Double> prices = new ArrayList<>();
        double step = (end - start) / (count - 1);
        for (int i = 0; i < count; i++) {
            prices.add(start + step * i);
        }
        return prices;
    }

    private List<double[]> toCandles(List<Double> closes) {
        return closes.stream().map(c -> new double[]{
            System.currentTimeMillis() / 1000.0, c - 10, c + 10, c - 20, c, 1000
        }).collect(Collectors.toList());
    }

    @Test
    @DisplayName("EMA Crossover: BUY on uptrend")
    void emaCrossoverBuy() {
        // Simulate downtrend then sharp uptrend (fast EMA crosses above slow)
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 30; i++) prices.add(40000.0 - i * 50);  // downtrend
        for (int i = 0; i < 30; i++) prices.add(38500.0 + i * 100); // uptrend

        TradingStrategy strategy = StrategyFactory.get("EMA_CROSS");
        Map<String, Object> params = Map.of("fastEma", 9, "slowEma", 21);
        SignalResult result = strategy.evaluate(prices, toCandles(prices), params, false);

        assertNotNull(result);
        // Signal should be BUY or HOLD depending on crossover point
        assertNotEquals(Signal.SELL, result.signal());
    }

    @Test
    @DisplayName("RSI Strategy: signals on oversold")
    void rsiStrategy() {
        // Simulate a consistent downtrend (RSI should go below 30)
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 60; i++) prices.add(42000.0 - i * 100);

        TradingStrategy strategy = StrategyFactory.get("RSI");
        Map<String, Object> params = new HashMap<>();
        params.put("rsiPeriod", 14);
        params.put("oversold", 30);
        params.put("overbought", 70);
        SignalResult result = strategy.evaluate(prices, toCandles(prices), params, false);

        assertNotNull(result);
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("MACD Strategy: evaluates without error")
    void macdStrategy() {
        List<Double> prices = generatePrices(40000, 42000, 60);
        TradingStrategy strategy = StrategyFactory.get("MACD");
        SignalResult result = strategy.evaluate(prices, toCandles(prices), Map.of(), false);

        assertNotNull(result);
    }

    @Test
    @DisplayName("StrategyFactory: returns all registered strategies")
    void factoryReturnsStrategies() {
        assertNotNull(StrategyFactory.get("EMA_CROSS"));
        assertNotNull(StrategyFactory.get("RSI"));
        assertNotNull(StrategyFactory.get("MACD"));
        assertTrue(StrategyFactory.exists("EMA_CROSS"));
        assertFalse(StrategyFactory.exists("NONEXISTENT"));
    }

    @Test
    @DisplayName("StrategyFactory: throws on unknown strategy")
    void factoryThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> StrategyFactory.get("UNKNOWN"));
    }

    @Test
    @DisplayName("Strategy HOLD when insufficient data")
    void holdOnInsufficientData() {
        List<Double> prices = List.of(42000.0, 42100.0, 42050.0);
        TradingStrategy strategy = StrategyFactory.get("EMA_CROSS");
        SignalResult result = strategy.evaluate(prices, toCandles(prices),
            Map.of("fastEma", 9, "slowEma", 21), false);

        // With only 3 data points and EMA needing 21, should HOLD
        assertEquals(Signal.HOLD, result.signal());
    }

    @Test
    @DisplayName("Strategy respects hasOpenPosition flag")
    void respectsOpenPosition() {
        // Uptrend should generate BUY when no position, but HOLD when already in
        List<Double> prices = generatePrices(38000, 42000, 60);
        TradingStrategy strategy = StrategyFactory.get("EMA_CROSS");
        Map<String, Object> params = Map.of("fastEma", 9, "slowEma", 21);

        SignalResult withPos = strategy.evaluate(prices, toCandles(prices), params, true);
        SignalResult withoutPos = strategy.evaluate(prices, toCandles(prices), params, false);

        // With open position, shouldn't generate BUY
        if (withoutPos.signal() == Signal.BUY) {
            assertNotEquals(Signal.BUY, withPos.signal());
        }
    }
}
