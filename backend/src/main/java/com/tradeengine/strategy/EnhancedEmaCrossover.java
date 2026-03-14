package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Enhanced EMA Crossover Strategy with multi-layer signal confirmation.
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
 * Output includes calculated SL (swing low/high) and TP (1:2 R:R).
 *
 * Allowed symbols: BTCUSDT, ETHUSDT, SOLUSDT, BNBUSDT
 */
public class EnhancedEmaCrossover implements TradingStrategy {

    private static final java.util.Set<String> ALLOWED_SYMBOLS = java.util.Set.of(
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"
    );

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
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
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Symbol " + symbol + " not in allowed list");
        }

        // --- Minimum data check ---
        int minCandles = Math.max(trendPeriod + 2, Math.max(atrPeriod + 2, volumeMaPeriod + 2));
        if (closingPrices.size() < minCandles || allCandles.size() < minCandles) {
            return new SignalResult(Signal.HOLD, lastPrice(closingPrices),
                "Insufficient data (need " + minCandles + ", got " + closingPrices.size() + ")");
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();
        int last = prices.length - 1;
        double price = prices[last];

        // ─── 1. EMA Calculations ───
        double currentFast = EmaCrossover.calculateEMA(prices, fastPeriod, last);
        double currentSlow = EmaCrossover.calculateEMA(prices, slowPeriod, last);
        double prevFast = EmaCrossover.calculateEMA(prices, fastPeriod, last - 1);
        double prevSlow = EmaCrossover.calculateEMA(prices, slowPeriod, last - 1);
        double currentTrend = EmaCrossover.calculateEMA(prices, trendPeriod, last);
        double prevTrend = EmaCrossover.calculateEMA(prices, trendPeriod, last - 1);

        boolean crossedAbove = currentFast > currentSlow && prevFast <= prevSlow;
        boolean crossedBelow = currentFast < currentSlow && prevFast >= prevSlow;

        // ─── 2. Trend Filter (EMA200) ───
        boolean trendUp = price > currentTrend && currentTrend > prevTrend;
        boolean trendDown = price < currentTrend && currentTrend < prevTrend;

        // ─── 3. Volume Confirmation ───
        double[] volumes = allCandles.stream().mapToDouble(c -> c[5]).toArray();
        double currentVolume = volumes[volumes.length - 1];
        double volumeMA = calculateSMA(volumes, volumeMaPeriod, volumes.length - 2); // exclude current
        boolean volumeConfirmed = currentVolume > volumeMultiplier * volumeMA;

        // ─── 4. Candle Confirmation ───
        double[] lastCandle = allCandles.get(allCandles.size() - 1);
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
        // Only reject on entry signals, not holds
        boolean spreadTooWide = spread > spreadMax;

        // Volume spike from single candle filter
        double prevVolume = volumes.length >= 2 ? volumes[volumes.length - 2] : currentVolume;
        boolean singleCandleSpike = currentVolume > 5.0 * prevVolume && currentVolume > 3.0 * volumeMA;

        // ─── 6. Volatility Filter (ATR) ───
        double currentATR = calculateATR(allCandles, atrPeriod, allCandles.size() - 1);
        double avgATR = calculateAvgATR(allCandles, atrPeriod, 20, allCandles.size() - 2);
        boolean atrSpikeRejected = currentATR > atrSpikeMultiplier * avgATR;

        if (atrSpikeRejected) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Volatility filter: ATR %.2f > %.1f× avg ATR %.2f — possible liquidation cascade",
                    currentATR, atrSpikeMultiplier, avgATR));
        }

        // ─── 7. Signal Decision ───

        // LONG signal
        if (!hasOpenPosition && crossedAbove && trendUp && volumeConfirmed && bullishCandle
                && !spreadTooWide && !singleCandleSpike) {
            double swingLow = findSwingLow(allCandles, swingLookback);
            double stopLoss = swingLow;
            double risk = price - stopLoss;
            if (risk <= 0) {
                return new SignalResult(Signal.HOLD, price, "Invalid SL: swing low above price");
            }
            double takeProfit = price + (risk * rrRatio);

            String reason = String.format(
                "LONG: EMA(%d)=%.2f crossed above EMA(%d)=%.2f | Trend EMA(%d)=%.2f ↑ | Vol=%.0f > %.1f×VMA=%.0f | ATR=%.2f | SL=%.2f TP=%.2f R:R=1:%.1f",
                fastPeriod, currentFast, slowPeriod, currentSlow, trendPeriod, currentTrend,
                currentVolume, volumeMultiplier, volumeMA, currentATR, stopLoss, takeProfit, rrRatio);

            return new SignalResult(Signal.BUY, price, reason, stopLoss, takeProfit, "HIGH");
        }

        // SHORT signal (for futures)
        if (hasOpenPosition && crossedBelow && trendDown && volumeConfirmed && bearishCandle
                && !spreadTooWide && !singleCandleSpike) {
            double swingHigh = findSwingHigh(allCandles, swingLookback);
            double stopLoss = swingHigh;
            double risk = stopLoss - price;
            if (risk <= 0) {
                return new SignalResult(Signal.HOLD, price, "Invalid SL: swing high below price");
            }
            double takeProfit = price - (risk * rrRatio);

            String reason = String.format(
                "SHORT: EMA(%d)=%.2f crossed below EMA(%d)=%.2f | Trend EMA(%d)=%.2f ↓ | Vol=%.0f > %.1f×VMA=%.0f | ATR=%.2f | SL=%.2f TP=%.2f R:R=1:%.1f",
                fastPeriod, currentFast, slowPeriod, currentSlow, trendPeriod, currentTrend,
                currentVolume, volumeMultiplier, volumeMA, currentATR, stopLoss, takeProfit, rrRatio);

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

        return new SignalResult(Signal.HOLD, price, noTradeReason.toString());
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
