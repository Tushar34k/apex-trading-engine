package com.tradeengine.integration;

import com.tradeengine.strategy.EnhancedEmaCrossover;
import com.tradeengine.strategy.TradingStrategy;
import com.tradeengine.strategy.TradingStrategy.Signal;
import com.tradeengine.strategy.TradingStrategy.SignalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-timeframe trend confirmation in EnhancedEmaCrossover.
 */
class EnhancedEmaMultiTimeframeTest {

    private EnhancedEmaCrossover strategy;

    @BeforeEach
    void setUp() {
        strategy = new EnhancedEmaCrossover();
    }

    /**
     * Build candles with a consistent trend direction and volume.
     */
    private List<double[]> buildCandles(double startPrice, int count, double trend, double volume) {
        List<double[]> candles = new ArrayList<>();
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            double open = price;
            price += trend;
            double close = price;
            double high = Math.max(open, close) + Math.abs(trend) * 0.3;
            double low = Math.min(open, close) - Math.abs(trend) * 0.3;
            candles.add(new double[]{i * 60.0, open, high, low, close, volume});
        }
        return candles;
    }

    private List<Double> closes(List<double[]> candles) {
        return candles.stream().map(c -> c[4]).collect(Collectors.toList());
    }

    @Test
    @DisplayName("Trend aligned across all timeframes → allow signal")
    void trendAlignedAllowsSignal() {
        // All timeframes in uptrend — price well above EMA200
        List<double[]> entryCandles = buildCandles(40000, 300, 5, 1000);
        List<double[]> trendCandles = buildCandles(40000, 300, 8, 1500);
        List<double[]> macroCandles = buildCandles(40000, 300, 15, 2000);

        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");
        params.put("entryTf", "5m");
        params.put("trendTf", "15m");
        params.put("macroTf", "1h");

        SignalResult result = strategy.evaluate(closes(entryCandles), entryCandles,
            trendCandles, macroCandles, params, false);

        // Should NOT be rejected for trend misalignment
        assertFalse(result.reason().contains("MULTI_TF_REJECT"),
            "Should not reject when all trends align. Reason: " + result.reason());
    }

    @Test
    @DisplayName("Trend misaligned (15m downtrend) → reject signal")
    void trendMisalignedRejectsSignal() {
        // Entry (5m): uptrend
        List<double[]> entryCandles = buildCandles(40000, 300, 5, 1000);
        // Trend (15m): DOWNTREND — price below EMA200
        List<double[]> trendCandles = buildCandles(50000, 300, -5, 1500);
        // Macro (1h): uptrend
        List<double[]> macroCandles = buildCandles(40000, 300, 15, 2000);

        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");

        SignalResult result = strategy.evaluate(closes(entryCandles), entryCandles,
            trendCandles, macroCandles, params, false);

        assertEquals(Signal.HOLD, result.signal());
        assertTrue(result.reason().contains("MULTI_TF_REJECT") || result.reason().contains("trend"),
            "Should reject due to trend misalignment. Reason: " + result.reason());
    }

    @Test
    @DisplayName("All bearish trends → allow SELL direction")
    void allBearishAllowsSell() {
        // All timeframes in downtrend
        List<double[]> entryCandles = buildCandles(50000, 300, -5, 1000);
        List<double[]> trendCandles = buildCandles(50000, 300, -8, 1500);
        List<double[]> macroCandles = buildCandles(50000, 300, -15, 2000);

        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "ETHUSDT");

        SignalResult result = strategy.evaluate(closes(entryCandles), entryCandles,
            trendCandles, macroCandles, params, true);

        // Should NOT be rejected for trend misalignment when all bearish
        assertFalse(result.reason().contains("MULTI_TF_REJECT"),
            "Should not reject when all trends bearish. Reason: " + result.reason());
    }

    @Test
    @DisplayName("Missing trend/macro candles → fallback to entry-only")
    void missingDataFallsBackToEntryOnly() {
        List<double[]> entryCandles = buildCandles(40000, 300, 5, 1000);
        List<double[]> emptyCandles = Collections.emptyList();

        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");

        // Should not throw, should fall back gracefully
        SignalResult result = strategy.evaluate(closes(entryCandles), entryCandles,
            emptyCandles, emptyCandles, params, false);

        assertNotNull(result);
        assertNotNull(result.reason());
        // Should log warning about fallback but continue processing
    }

    @Test
    @DisplayName("Null trend/macro candles → fallback to entry-only")
    void nullDataFallsBackToEntryOnly() {
        List<double[]> entryCandles = buildCandles(40000, 300, 5, 1000);

        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");

        // Null candles should not crash
        SignalResult result = strategy.evaluate(closes(entryCandles), entryCandles,
            null, null, params, false);

        assertNotNull(result);
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("Backward-compatible 4-arg evaluate still works")
    void backwardCompatible4ArgEvaluate() {
        List<double[]> candles = buildCandles(40000, 300, 5, 1000);
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");

        // Old-style call without trend/macro candles
        SignalResult result = strategy.evaluate(closes(candles), candles, params, false);

        assertNotNull(result);
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("Macro (1h) downtrend blocks BUY even if 5m and 15m are up")
    void macroDowntrendBlocksBuy() {
        List<double[]> entryCandles = buildCandles(40000, 300, 5, 1000);
        List<double[]> trendCandles = buildCandles(40000, 300, 8, 1500);
        // Macro: clear downtrend
        List<double[]> macroCandles = buildCandles(50000, 300, -20, 2000);

        Map<String, Object> params = new HashMap<>();
        params.put("symbol", "BTCUSDT");

        SignalResult result = strategy.evaluate(closes(entryCandles), entryCandles,
            trendCandles, macroCandles, params, false);

        assertEquals(Signal.HOLD, result.signal());
    }
}
