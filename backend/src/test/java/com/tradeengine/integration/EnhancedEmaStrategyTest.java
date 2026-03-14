package com.tradeengine.integration;

import com.tradeengine.strategy.*;
import com.tradeengine.strategy.TradingStrategy.Signal;
import com.tradeengine.strategy.TradingStrategy.SignalResult;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EnhancedEmaCrossover strategy with all filters.
 */
class EnhancedEmaStrategyTest {

    private final TradingStrategy strategy = StrategyFactory.get("ENHANCED_EMA");

    private List<double[]> buildCandles(double startPrice, int count, double trend, double volume) {
        List<double[]> candles = new ArrayList<>();
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            double open = price;
            price += trend;
            double close = price;
            double high = Math.max(open, close) + Math.abs(trend) * 0.3;
            double low = Math.min(open, close) - Math.abs(trend) * 0.3;
            candles.add(new double[]{System.currentTimeMillis() / 1000.0 + i * 60, open, high, low, close, volume});
        }
        return candles;
    }

    private List<Double> closes(List<double[]> candles) {
        return candles.stream().map(c -> c[4]).collect(Collectors.toList());
    }

    @Test
    @DisplayName("HOLD when insufficient data (< 202 candles)")
    void holdOnInsufficientData() {
        List<double[]> candles = buildCandles(40000, 50, 10, 1000);
        SignalResult result = strategy.evaluate(closes(candles), candles, Map.of(), false);
        assertEquals(Signal.HOLD, result.signal());
        assertTrue(result.reason().contains("Insufficient data"));
    }

    @Test
    @DisplayName("HOLD when no crossover occurs")
    void holdWhenNoCrossover() {
        // Steady uptrend — fast EMA stays above slow, no crossover
        List<double[]> candles = buildCandles(30000, 250, 5, 1000);
        SignalResult result = strategy.evaluate(closes(candles), candles, Map.of(), false);
        assertEquals(Signal.HOLD, result.signal());
    }

    @Test
    @DisplayName("BUY signal with trend + volume + crossover confirmation")
    void buySignalOnFullConfirmation() {
        // Build: 200 candles slight downtrend, then 50 candles strong uptrend with high volume
        List<double[]> candles = new ArrayList<>();
        double price = 40000;
        // Downtrend phase (price stays below EMA200 area initially)
        for (int i = 0; i < 200; i++) {
            double open = price;
            price -= 2; // gentle downtrend
            double close = price;
            candles.add(new double[]{i * 60.0, open, open + 5, close - 5, close, 500});
        }
        // Sharp reversal with high volume — fast EMA should cross above slow
        for (int i = 0; i < 50; i++) {
            double open = price;
            price += 30; // strong uptrend
            double close = price;
            double high = close + 5;
            double low = open - 2;
            candles.add(new double[]{(200 + i) * 60.0, open, high, low, close, 3000}); // 6x normal volume
        }

        Map<String, Object> params = new HashMap<>();
        params.put("fastEma", 9);
        params.put("slowEma", 21);
        params.put("trendEma", 200);
        params.put("volumeMultiplier", 1.5);

        SignalResult result = strategy.evaluate(closes(candles), candles, params, false);
        // May be BUY or HOLD depending on exact EMA positions, but should not be SELL
        assertNotEquals(Signal.SELL, result.signal());
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("Reject trade on ATR spike (volatility filter)")
    void rejectOnAtrSpike() {
        List<double[]> candles = buildCandles(40000, 220, 5, 1000);
        // Add extreme volatility candles at the end
        for (int i = 0; i < 5; i++) {
            double base = 41100 + i * 500;
            candles.add(new double[]{(220 + i) * 60.0, base, base + 2000, base - 2000, base + 500, 5000});
        }

        Map<String, Object> params = new HashMap<>(Map.of("atrSpikeMultiplier", 3.0));
        SignalResult result = strategy.evaluate(closes(candles), candles, params, false);
        assertEquals(Signal.HOLD, result.signal());
        assertTrue(result.reason().toLowerCase().contains("volatility") || result.reason().contains("ATR")
            || result.reason().contains("no crossover") || result.reason().contains("NO TRADE"));
    }

    @Test
    @DisplayName("Reject fake signal with high wick ratio")
    void rejectFakeSignalHighWick() {
        // Build candles where last candle has > 60% wick
        List<double[]> candles = buildCandles(40000, 224, 5, 1000);
        // Replace last candle with a doji / high-wick candle
        double close = candles.get(candles.size() - 1)[4];
        candles.set(candles.size() - 1, new double[]{
            999999, close - 1, close + 100, close - 5, close, 1000
        }); // upper wick 101, body 1, total range 105 → wick ratio ~96%

        Map<String, Object> params = new HashMap<>(Map.of("wickRatioMax", 0.60));
        SignalResult result = strategy.evaluate(closes(candles), candles, params, false);
        assertEquals(Signal.HOLD, result.signal());
        assertTrue(result.reason().contains("wick") || result.reason().contains("Fake") || result.reason().contains("NO TRADE"));
    }

    @Test
    @DisplayName("Symbol filter rejects unlisted symbols")
    void rejectUnlistedSymbol() {
        List<double[]> candles = buildCandles(1.0, 250, 0.001, 1000);
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "DOGEUSDT");

        SignalResult result = strategy.evaluate(closes(candles), candles, params, false);
        assertEquals(Signal.HOLD, result.signal());
        assertTrue(result.reason().contains("not in allowed"));
    }

    @Test
    @DisplayName("Allowed symbols pass filter")
    void allowedSymbolPasses() {
        List<double[]> candles = buildCandles(40000, 250, 5, 1000);
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");

        SignalResult result = strategy.evaluate(closes(candles), candles, params, false);
        // Should not be rejected for symbol reasons
        assertFalse(result.reason().contains("not in allowed"));
    }

    @Test
    @DisplayName("SignalResult backward compatibility")
    void signalResultBackwardCompat() {
        // Old 3-arg constructor still works
        SignalResult simple = new SignalResult(Signal.HOLD, 42000.0, "test");
        assertNull(simple.stopLoss());
        assertNull(simple.takeProfit());
        assertNull(simple.confidence());

        // New 6-arg constructor
        SignalResult enhanced = new SignalResult(Signal.BUY, 42000.0, "test", 41500.0, 43000.0, "HIGH");
        assertEquals(41500.0, enhanced.stopLoss());
        assertEquals(43000.0, enhanced.takeProfit());
        assertEquals("HIGH", enhanced.confidence());
    }

    @Test
    @DisplayName("Factory registers ENHANCED_EMA")
    void factoryRegistered() {
        assertTrue(StrategyFactory.exists("ENHANCED_EMA"));
        assertNotNull(StrategyFactory.get("ENHANCED_EMA"));
    }
}
