package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Enhanced EMA Crossover Strategy with multi-layer signal confirmation
 * and multi-timeframe trend alignment.
 *
 * Entry conditions (ALL must be met):
 *   1. EMA9/EMA21 crossover
 *   2. EMA200 trend filter (only trade in trend direction)
 *   3. Volume > 1.5× VMA(20)
 *   4. Bullish/Bearish candle confirmation
 *
 * Rejection filters:
 *   - Fake signal: wick > 60% of candle size
 *   - Spread filter: spread > 0.2%
 *   - Volatility filter: ATR > 3× average ATR
 *
 * Multi-timeframe confirmation:
 *   - LONG: price > EMA200 on entry, trend, and macro timeframes
 *   - SHORT: price < EMA200 on entry, trend, and macro timeframes
 *
 * Output includes calculated SL (swing low/high) and TP (1:2 R:R).
 *
 * Allowed symbols: BTCUSDT, ETHUSDT, SOLUSDT, BNBUSDT
 */
public class EnhancedEmaCrossover implements TradingStrategy {

    private static final java.util.Set<String> ALLOWED_SYMBOLS = java.util.Set.of(
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"
    );

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnhancedEmaCrossover.class);

    // ─── Single-timeframe evaluate (backward-compatible) ───
    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        return evaluate(closingPrices, allCandles, null, null, params, hasOpenPosition);
    }

    // ─── Multi-timeframe evaluate ───
    @Override
    public SignalResult evaluate(List<Double> entryPrices, List<double[]> entryCandles,
                                 List<double[]> trendCandles, List<double[]> macroCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {

        int fastPeriod = getInt(params, "fastEma", 9);
        int slowPeriod = getInt(params, "slowEma", 21);
        int trendPeriod = getInt(params, "trendEma", 200);
        int volumeMaPeriod = getInt(params, "volumeMaPeriod", 20);
        double volumeMultiplier = getDouble(params, "volumeMultiplier", 1.5);
        double wickRatioMax = getDouble(params, "wickRatioMax", 0.60);
        double spreadMax = getDouble(params, "spreadMax", 0.002);
        double atrSpikeMultiplier = getDouble(params, "atrSpikeMultiplier", 3.0);
        int atrPeriod = getInt(params, "atrPeriod", 14);
        int swingLookback = getInt(params, "swingLookback", 10);
        double rrRatio = getDouble(params, "rrRatio", 2.0);

        // --- Symbol filter ---
        String symbol = params.containsKey("symbol") ? params.get("symbol").toString() : null;
        if (symbol != null && !ALLOWED_SYMBOLS.contains(symbol.toUpperCase())) {
            return new SignalResult(Signal.HOLD, lastPrice(entryPrices),
                "Symbol " + symbol + " not in allowed list");
        }

        // --- Minimum data check ---
        int minCandles = Math.max(trendPeriod + 2, Math.max(atrPeriod + 2, volumeMaPeriod + 2));
        if (entryPrices.size() < minCandles || entryCandles.size() < minCandles) {
            return new SignalResult(Signal.HOLD, lastPrice(entryPrices),
                "Insufficient data (need " + minCandles + ", got " + entryPrices.size() + ")");
        }

        double[] prices = entryPrices.stream().mapToDouble(d -> d).toArray();
        int last = prices.length - 1;
        double price = prices[last];

        // ─── Multi-Timeframe Trend Check ───
        MultiTfResult mtfResult = checkMultiTimeframeTrend(price, trendPeriod, entryCandles, trendCandles, macroCandles, symbol);
        if (mtfResult.rejected) {
            return new SignalResult(Signal.HOLD, price, mtfResult.reason);
        }

        // ─── 1. EMA Calculations ───
        double currentFast = EmaCrossover.calculateEMA(prices, fastPeriod, last);
        double currentSlow = EmaCrossover.calculateEMA(prices, slowPeriod, last);
        double prevFast = EmaCrossover.calculateEMA(prices, fastPeriod, last - 1);
        double prevSlow = EmaCrossover.calculateEMA(prices, slowPeriod, last - 1);
        double currentTrend = EmaCrossover.calculateEMA(prices, trendPeriod, last);
        double prevTrend = EmaCrossover.calculateEMA(prices, trendPeriod, last - 1);

        boolean crossedAbove = currentFast > currentSlow && prevFast <= prevSlow;
        boolean crossedBelow = currentFast < currentSlow && prevFast >= prevSlow;

        // ─── 2. Trend Filter (EMA200 on entry TF) ───
        boolean trendUp = price > currentTrend && currentTrend > prevTrend;
        boolean trendDown = price < currentTrend && currentTrend < prevTrend;

        // If multi-TF data is available, additionally require alignment
        if (mtfResult.hasTrendData) {
            trendUp = trendUp && mtfResult.allBullish;
            trendDown = trendDown && mtfResult.allBearish;
        }

        // ─── 3. Volume Confirmation ───
        double[] volumes = entryCandles.stream().mapToDouble(c -> c[5]).toArray();
        double currentVolume = volumes[volumes.length - 1];
        double volumeMA = calculateSMA(volumes, volumeMaPeriod, volumes.length - 2);
        boolean volumeConfirmed = currentVolume > volumeMultiplier * volumeMA;

        // ─── 4. Candle Confirmation ───
        double[] lastCandle = entryCandles.get(entryCandles.size() - 1);
        double open = lastCandle[1], high = lastCandle[2], low = lastCandle[3], close = lastCandle[4];
        boolean bullishCandle = close > open;
        boolean bearishCandle = close < open;

        // ─── 5. Fake Signal Filter ───
        double candleSize = high - low;
        if (candleSize <= 0) {
            return new SignalResult(Signal.HOLD, price, "Zero-size candle — skip");
        }
        double upperWick = bullishCandle ? (high - close) : (high - open);
        double lowerWick = bullishCandle ? (open - low) : (close - low);
        double maxWick = Math.max(upperWick, lowerWick);
        double wickRatio = maxWick / candleSize;

        if (wickRatio > wickRatioMax) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Fake signal filter: wick ratio %.2f > %.2f", wickRatio, wickRatioMax));
        }

        // Spread filter
        double spread = (high - low) / ((high + low) / 2);
        boolean spreadTooWide = spread > spreadMax;

        // Volume spike from single candle filter
        double prevVolume = volumes.length >= 2 ? volumes[volumes.length - 2] : currentVolume;
        boolean singleCandleSpike = currentVolume > 5.0 * prevVolume && currentVolume > 3.0 * volumeMA;

        // ─── 6. Volatility Filter (ATR) ───
        double currentATR = calculateATR(entryCandles, atrPeriod, entryCandles.size() - 1);
        double avgATR = calculateAvgATR(entryCandles, atrPeriod, 20, entryCandles.size() - 2);
        boolean atrSpikeRejected = currentATR > atrSpikeMultiplier * avgATR;

        if (atrSpikeRejected) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Volatility filter: ATR %.2f > %.1f× avg ATR %.2f — possible liquidation cascade",
                    currentATR, atrSpikeMultiplier, avgATR));
        }

        // ─── 7. Signal Decision ───
        String mtfTag = mtfResult.hasTrendData ? " | " + mtfResult.logTag : "";

        // LONG signal
        if (!hasOpenPosition && crossedAbove && trendUp && volumeConfirmed && bullishCandle
                && !spreadTooWide && !singleCandleSpike) {
            double swingLow = findSwingLow(entryCandles, swingLookback);
            double stopLoss = swingLow;
            double risk = price - stopLoss;
            if (risk <= 0) {
                return new SignalResult(Signal.HOLD, price, "Invalid SL: swing low above price");
            }
            double takeProfit = price + (risk * rrRatio);

            String reason = String.format(
                "LONG: EMA(%d)=%.2f crossed above EMA(%d)=%.2f | Trend EMA(%d)=%.2f ↑ | Vol=%.0f > %.1f×VMA=%.0f | ATR=%.2f | SL=%.2f TP=%.2f R:R=1:%.1f%s",
                fastPeriod, currentFast, slowPeriod, currentSlow, trendPeriod, currentTrend,
                currentVolume, volumeMultiplier, volumeMA, currentATR, stopLoss, takeProfit, rrRatio, mtfTag);

            return new SignalResult(Signal.BUY, price, reason, stopLoss, takeProfit, "HIGH");
        }

        // SHORT signal (for futures)
        if (hasOpenPosition && crossedBelow && trendDown && volumeConfirmed && bearishCandle
                && !spreadTooWide && !singleCandleSpike) {
            double swingHigh = findSwingHigh(entryCandles, swingLookback);
            double stopLoss = swingHigh;
            double risk = stopLoss - price;
            if (risk <= 0) {
                return new SignalResult(Signal.HOLD, price, "Invalid SL: swing high below price");
            }
            double takeProfit = price - (risk * rrRatio);

            String reason = String.format(
                "SHORT: EMA(%d)=%.2f crossed below EMA(%d)=%.2f | Trend EMA(%d)=%.2f ↓ | Vol=%.0f > %.1f×VMA=%.0f | ATR=%.2f | SL=%.2f TP=%.2f R:R=1:%.1f%s",
                fastPeriod, currentFast, slowPeriod, currentSlow, trendPeriod, currentTrend,
                currentVolume, volumeMultiplier, volumeMA, currentATR, stopLoss, takeProfit, rrRatio, mtfTag);

            return new SignalResult(Signal.SELL, price, reason, stopLoss, takeProfit, "HIGH");
        }

        // ─── 8. Rejection reasons for transparency ───
        StringBuilder noTradeReason = new StringBuilder("NO TRADE: ");
        if (!crossedAbove && !crossedBelow) noTradeReason.append("no crossover; ");
        if (crossedAbove && !trendUp) noTradeReason.append("crossover UP but trend DOWN; ");
        if (crossedBelow && !trendDown) noTradeReason.append("crossover DOWN but trend UP; ");
        if ((crossedAbove || crossedBelow) && !volumeConfirmed)
            noTradeReason.append(String.format("vol=%.0f < %.1f×VMA=%.0f; ", currentVolume, volumeMultiplier, volumeMA));
        if (crossedAbove && !bullishCandle) noTradeReason.append("bearish candle on buy signal; ");
        if (crossedBelow && !bearishCandle) noTradeReason.append("bullish candle on sell signal; ");
        if (spreadTooWide) noTradeReason.append(String.format("spread %.4f > %.4f; ", spread, spreadMax));
        if (singleCandleSpike) noTradeReason.append("single-candle volume spike; ");

        noTradeReason.append(String.format("EMA(%d)=%.2f EMA(%d)=%.2f EMA(%d)=%.2f",
            fastPeriod, currentFast, slowPeriod, currentSlow, trendPeriod, currentTrend));
        if (!mtfTag.isEmpty()) noTradeReason.append(mtfTag);

        return new SignalResult(Signal.HOLD, price, noTradeReason.toString());
    }

    // ─── Multi-Timeframe Trend Check ───

    private record MultiTfResult(boolean rejected, boolean hasTrendData,
                                  boolean allBullish, boolean allBearish,
                                  String reason, String logTag) {}

    private MultiTfResult checkMultiTimeframeTrend(double entryPrice, int trendPeriod,
                                                     List<double[]> entryCandles,
                                                     List<double[]> trendCandles,
                                                     List<double[]> macroCandles,
                                                     String symbol) {

        boolean hasTrend = trendCandles != null && trendCandles.size() >= trendPeriod + 2;
        boolean hasMacro = macroCandles != null && macroCandles.size() >= trendPeriod + 2;

        if (!hasTrend && !hasMacro) {
            if (trendCandles != null || macroCandles != null) {
                log.warn("[MULTI_TF_FALLBACK] symbol={} insufficient higher-TF data, using entry-only", symbol);
            }
            return new MultiTfResult(false, false, false, false, null, "MTF=ENTRY_ONLY");
        }

        // Compute EMA200 on each available timeframe
        String entryTrend = computeTrendDirection(entryPrice, entryCandles, trendPeriod);

        String trendTfTrend = "N/A";
        if (hasTrend) {
            double trendPrice = trendCandles.get(trendCandles.size() - 1)[4];
            trendTfTrend = computeTrendDirection(trendPrice, trendCandles, trendPeriod);
        }

        String macroTfTrend = "N/A";
        if (hasMacro) {
            double macroPrice = macroCandles.get(macroCandles.size() - 1)[4];
            macroTfTrend = computeTrendDirection(macroPrice, macroCandles, trendPeriod);
        }

        boolean allUp = "UP".equals(entryTrend)
            && (!hasTrend || "UP".equals(trendTfTrend))
            && (!hasMacro || "UP".equals(macroTfTrend));

        boolean allDown = "DOWN".equals(entryTrend)
            && (!hasTrend || "DOWN".equals(trendTfTrend))
            && (!hasMacro || "DOWN".equals(macroTfTrend));

        String logTag = String.format("[MULTI_TF_CHECK] 5m=%s 15m=%s 1h=%s", entryTrend, trendTfTrend, macroTfTrend);

        if (!allUp && !allDown) {
            String rejectReason = String.format(
                "[MULTI_TF_REJECT] symbol=%s 5mTrend=%s 15mTrend=%s 1hTrend=%s reason=trend_misalignment",
                symbol, entryTrend, trendTfTrend, macroTfTrend);
            log.info(rejectReason);
            return new MultiTfResult(true, true, false, false, rejectReason, logTag);
        }

        String checkLog = String.format(
            "[MULTI_TF_CHECK] symbol=%s 5mTrend=%s 15mTrend=%s 1hTrend=%s decision=%s",
            symbol, entryTrend, trendTfTrend, macroTfTrend, allUp ? "ALLOW_BUY" : "ALLOW_SELL");
        log.debug(checkLog);

        return new MultiTfResult(false, true, allUp, allDown, null, logTag);
    }

    private String computeTrendDirection(double currentPrice, List<double[]> candles, int emaPeriod) {
        if (candles == null || candles.size() < emaPeriod + 2) return "N/A";
        double[] closes = candles.stream().mapToDouble(c -> c[4]).toArray();
        double ema = EmaCrossover.calculateEMA(closes, emaPeriod, closes.length - 1);
        return currentPrice > ema ? "UP" : "DOWN";
    }

    // ─── Helper: Simple Moving Average ───
    private double calculateSMA(double[] data, int period, int endIndex) {
        int start = Math.max(0, endIndex - period + 1);
        double sum = 0;
        int count = 0;
        for (int i = start; i <= endIndex && i < data.length; i++) {
            sum += data[i];
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    // ─── Helper: Average True Range ───
    private double calculateATR(List<double[]> candles, int period, int endIndex) {
        double sum = 0;
        int count = 0;
        int start = Math.max(1, endIndex - period + 1);
        for (int i = start; i <= endIndex && i < candles.size(); i++) {
            double high = candles.get(i)[2];
            double low = candles.get(i)[3];
            double prevClose = candles.get(i - 1)[4];
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    // ─── Helper: Average ATR over a window (for spike detection) ───
    private double calculateAvgATR(List<double[]> candles, int atrPeriod, int windowSize, int endIndex) {
        double sum = 0;
        int count = 0;
        int start = Math.max(atrPeriod + 1, endIndex - windowSize + 1);
        for (int i = start; i <= endIndex && i < candles.size(); i++) {
            sum += calculateATR(candles, atrPeriod, i);
            count++;
        }
        return count > 0 ? sum / count : 1;
    }

    // ─── Helper: Find recent swing low ───
    private double findSwingLow(List<double[]> candles, int lookback) {
        double lowest = Double.MAX_VALUE;
        int start = Math.max(0, candles.size() - 1 - lookback);
        for (int i = start; i < candles.size() - 1; i++) {
            double low = candles.get(i)[3];
            if (low < lowest) lowest = low;
        }
        return lowest;
    }

    // ─── Helper: Find recent swing high ───
    private double findSwingHigh(List<double[]> candles, int lookback) {
        double highest = Double.MIN_VALUE;
        int start = Math.max(0, candles.size() - 1 - lookback);
        for (int i = start; i < candles.size() - 1; i++) {
            double high = candles.get(i)[2];
            if (high > highest) highest = high;
        }
        return highest;
    }

    private double lastPrice(List<Double> prices) {
        return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
    }

    private int getInt(Map<String, Object> params, String key, int def) {
        if (params == null || !params.containsKey(key)) return def;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    private double getDouble(Map<String, Object> params, String key, double def) {
        if (params == null || !params.containsKey(key)) return def;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }
}
