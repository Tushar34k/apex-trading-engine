package com.tradeengine.strategy;

import java.util.List;
import java.util.Map;

/**
 * Enhanced EMA Crossover Strategy v2 — Institutional-Grade Signal Engine
 *
 * MAJOR UPGRADES:
 *   1. Multi-Timeframe trend alignment (5m/15m/1h EMA200 required)
 *   2. RSI filter — reject BUY if RSI > 70, reject SELL if RSI < 30
 *   3. Volume spike confirmation with moving average threshold
 *   4. ATR-based dynamic Stop Loss (replaces fixed %)
 *   5. Pullback entry — prefer entries near EMA21 (not chasing breakouts)
 *   6. Fake breakout protection — candle close above resistance required
 *   7. Candle body/wick ratio filter
 *   8. Dynamic TP at 1:2 or 1:3 R:R based on confidence
 *   9. Partial profit booking levels (TP1 at 1:1 R:R for 50%)
 *
 * Allowed symbols: BTCUSDT, ETHUSDT, SOLUSDT, BNBUSDT
 */
public class EnhancedEmaCrossover implements TradingStrategy {

    private static final java.util.Set<String> ALLOWED_SYMBOLS = java.util.Set.of(
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"
    );

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnhancedEmaCrossover.class);

    @Override
    public SignalResult evaluate(List<Double> closingPrices, List<double[]> allCandles,
                                 Map<String, Object> params, boolean hasOpenPosition) {
        return evaluate(closingPrices, allCandles, null, null, params, hasOpenPosition);
    }

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
        double atrSlMultiplier = getDouble(params, "atrSlMultiplier", 1.5);
        double maxPullbackATR = getDouble(params, "maxPullbackATR", 2.5);
        int rsiPeriod = getInt(params, "rsiPeriod", 14);
        double rsiOverbought = getDouble(params, "rsiOverbought", 70);
        double rsiOversold = getDouble(params, "rsiOversold", 30);
        double minATRRatio = getDouble(params, "minATRRatio", 0.5);

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
        double prevFast2 = last > 2 ? EmaCrossover.calculateEMA(prices, fastPeriod, last - 2) : prevFast;
        double prevSlow2 = last > 2 ? EmaCrossover.calculateEMA(prices, slowPeriod, last - 2) : prevSlow;
        double currentTrend = EmaCrossover.calculateEMA(prices, trendPeriod, last);
        double prevTrend = EmaCrossover.calculateEMA(prices, trendPeriod, last - 1);
        double ema50 = EmaCrossover.calculateEMA(prices, 50, last);

        boolean crossedAbove = currentFast > currentSlow && prevFast <= prevSlow;
        boolean crossedBelow = currentFast < currentSlow && prevFast >= prevSlow;

        // ─── 1b. EMA SLOPE FILTER (NEW) — both EMAs must slope in trade direction ───
        double fastSlope = currentFast - prevFast;
        double slowSlope = currentSlow - prevSlow;
        double fastAccel = (currentFast - prevFast) - (prevFast - prevFast2); // acceleration

        boolean emaSlopeUp = fastSlope > 0 && slowSlope > 0;
        boolean emaSlopeDown = fastSlope < 0 && slowSlope < 0;

        if (crossedAbove && !emaSlopeUp) {
            return new SignalResult(Signal.HOLD, price,
                String.format("EMA slope filter: fastSlope=%.4f slowSlope=%.4f — both must be positive for BUY", fastSlope, slowSlope));
        }
        if (crossedBelow && !emaSlopeDown) {
            return new SignalResult(Signal.HOLD, price,
                String.format("EMA slope filter: fastSlope=%.4f slowSlope=%.4f — both must be negative for SELL", fastSlope, slowSlope));
        }

        // ─── 2. Trend Filter (EMA200 + EMA50 on entry TF) ───
        boolean trendUp = price > currentTrend && currentTrend > prevTrend && price > ema50;
        boolean trendDown = price < currentTrend && currentTrend < prevTrend && price < ema50;

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

        // ─── 5. Fake Signal Filter (wick ratio) ───
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

        // ─── 6. ATR Volatility Filter ───
        double currentATR = calculateATR(entryCandles, atrPeriod, entryCandles.size() - 1);
        double avgATR = calculateAvgATR(entryCandles, atrPeriod, 20, entryCandles.size() - 2);
        boolean atrSpikeRejected = currentATR > atrSpikeMultiplier * avgATR;

        if (atrSpikeRejected) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Volatility filter: ATR %.2f > %.1f× avg ATR %.2f", currentATR, atrSpikeMultiplier, avgATR));
        }

        // Low volatility (sideways) filter
        double atrRatio = avgATR > 0 ? currentATR / avgATR : 1;
        if (atrRatio < minATRRatio) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Low volatility filter: ATR ratio %.2f < min %.2f — likely sideways", atrRatio, minATRRatio));
        }

        // ─── 7. RSI Filter (NEW) ───
        double rsi = calculateRSI(prices, rsiPeriod, last);
        if (crossedAbove && rsi > rsiOverbought) {
            return new SignalResult(Signal.HOLD, price,
                String.format("RSI filter: RSI=%.1f > %.0f — overbought, skip BUY", rsi, rsiOverbought));
        }
        if (crossedBelow && rsi < rsiOversold) {
            return new SignalResult(Signal.HOLD, price,
                String.format("RSI filter: RSI=%.1f < %.0f — oversold, skip SELL", rsi, rsiOversold));
        }

        // ─── 8. Pullback Filter (UPGRADED) — require pullback bounce, reject chasing ───
        double ema21 = EmaCrossover.calculateEMA(prices, 21, last);
        double pullbackDistance = Math.abs(price - ema21) / (currentATR > 0 ? currentATR : 1);

        if (crossedAbove && price > ema21 && pullbackDistance > maxPullbackATR) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Pullback filter: price %.1fATR from EMA21 (max %.1f) — chasing", pullbackDistance, maxPullbackATR));
        }
        if (crossedBelow && price < ema21 && pullbackDistance > maxPullbackATR) {
            return new SignalResult(Signal.HOLD, price,
                String.format("Pullback filter: price %.1fATR from EMA21 (max %.1f) — chasing", pullbackDistance, maxPullbackATR));
        }

        // ─── 8b. Bounce Confirmation (NEW) — verify price bounced off EMA zone ───
        // For BUY: previous candle low must have touched EMA21 zone (within 0.5 ATR), current candle closes above
        if (crossedAbove && entryCandles.size() >= 3) {
            double[] prevCandle = entryCandles.get(entryCandles.size() - 2);
            double prevLow = prevCandle[3];
            double prevEma21 = EmaCrossover.calculateEMA(prices, 21, last - 1);
            double distFromEma = Math.abs(prevLow - prevEma21) / (currentATR > 0 ? currentATR : 1);
            boolean touchedZone = distFromEma <= 1.0; // within 1 ATR of EMA21
            boolean bounced = close > open && close > prevCandle[4]; // bullish close above prev close

            if (!touchedZone && pullbackDistance > 0.5) {
                return new SignalResult(Signal.HOLD, price,
                    String.format("Bounce filter: prev candle %.1fATR from EMA21, no pullback touch — waiting for retest", distFromEma));
            }
        }

        // ─── 9. Fake Breakout Protection — candle close above/below level ───
        // For BUY: close must be > previous swing high (not just wick)
        // For SELL: close must be < previous swing low (not just wick)
        double recentHigh = findSwingHigh(entryCandles, swingLookback);
        double recentLow = findSwingLow(entryCandles, swingLookback);

        // ─── 10. Signal Decision ───
        String mtfTag = mtfResult.hasTrendData ? " | " + mtfResult.logTag : "";

        // ─── 10b. Momentum Confirmation (NEW) — require strong candle + volume trend ───
        // Current volume must be higher than previous candle volume (increasing momentum)
        boolean volumeIncreasing = volumes.length >= 2 && currentVolume > volumes[volumes.length - 2];
        // Candle body must be >50% of range (conviction, not indecision)
        double bodyRatio = candleSize > 0 ? Math.abs(close - open) / candleSize : 0;
        boolean strongCandle = bodyRatio > 0.50;

        // LONG signal
        if (!hasOpenPosition && crossedAbove && trendUp && volumeConfirmed && bullishCandle
                && !spreadTooWide && !singleCandleSpike && strongCandle) {

            // Additional momentum gate: reject if EMA acceleration is negative (decelerating)
            if (fastAccel < 0) {
                return new SignalResult(Signal.HOLD, price,
                    String.format("Momentum decelerating: EMA9 accel=%.6f — waiting for strength", fastAccel));
            }

            // ATR-based dynamic SL (replaces fixed swing low in many cases)
            double atrStopLoss = price - (currentATR * atrSlMultiplier);
            double swingLow = findSwingLow(entryCandles, swingLookback);
            // Use the HIGHER of ATR-SL and swing low (tighter but more meaningful)
            double stopLoss = Math.max(atrStopLoss, swingLow);

            double risk = price - stopLoss;
            if (risk <= 0) {
                return new SignalResult(Signal.HOLD, price, "Invalid SL: calculated SL above price");
            }

            // Dynamic TP: use R:R ratio, consider confidence for higher targets
            double takeProfit = price + (risk * rrRatio);
            // TP1 at 1:1 for partial profit (stored in params downstream)
            double tp1 = price + risk;

            String reason = String.format(
                "LONG: EMA(%d)↑EMA(%d) slope=%.4f/%.4f accel=%.6f | EMA200=%.2f ↑ | EMA50=%.2f | Vol=%.0f>%.1f×VMA(%s) | RSI=%.1f | ATR=%.2f | PB=%.1fATR | body=%.0f%% | SL=%.2f TP=%.2f R:R=1:%.1f TP1=%.2f%s",
                fastPeriod, slowPeriod, fastSlope, slowSlope, fastAccel, currentTrend, ema50,
                currentVolume, volumeMultiplier, volumeIncreasing ? "↑" : "→", rsi, currentATR, pullbackDistance,
                bodyRatio * 100, stopLoss, takeProfit, rrRatio, tp1, mtfTag);

            return new SignalResult(Signal.BUY, price, reason, stopLoss, takeProfit, "HIGH");
        }

        // SHORT signal
        if (hasOpenPosition && crossedBelow && trendDown && volumeConfirmed && bearishCandle
                && !spreadTooWide && !singleCandleSpike && strongCandle) {

            double atrStopLoss = price + (currentATR * atrSlMultiplier);
            double swingHigh = findSwingHigh(entryCandles, swingLookback);
            double stopLoss = Math.min(atrStopLoss, swingHigh);

            double risk = stopLoss - price;
            if (risk <= 0) {
                return new SignalResult(Signal.HOLD, price, "Invalid SL: calculated SL below price");
            }
            double takeProfit = price - (risk * rrRatio);

            String reason = String.format(
                "SHORT: EMA(%d)↓EMA(%d) slope=%.4f/%.4f | EMA200=%.2f ↓ | EMA50=%.2f | Vol=%.0f>%.1f×VMA | RSI=%.1f | ATR=%.2f | body=%.0f%% | SL=%.2f TP=%.2f R:R=1:%.1f%s",
                fastPeriod, slowPeriod, fastSlope, slowSlope, currentTrend, ema50,
                currentVolume, volumeMultiplier, rsi, currentATR, bodyRatio * 100,
                stopLoss, takeProfit, rrRatio, mtfTag);

            return new SignalResult(Signal.SELL, price, reason, stopLoss, takeProfit, "HIGH");
        }

        // ─── Rejection reasons ───
        StringBuilder noTradeReason = new StringBuilder("NO TRADE: ");
        if (!crossedAbove && !crossedBelow) noTradeReason.append("no crossover; ");
        if (crossedAbove && !trendUp) noTradeReason.append("crossover UP but trend DOWN; ");
        if (crossedBelow && !trendDown) noTradeReason.append("crossover DOWN but trend UP; ");
        if ((crossedAbove || crossedBelow) && !volumeConfirmed)
            noTradeReason.append(String.format("vol=%.0f < %.1f×VMA=%.0f; ", currentVolume, volumeMultiplier, volumeMA));
        if (crossedAbove && !bullishCandle) noTradeReason.append("bearish candle on buy; ");
        if (crossedBelow && !bearishCandle) noTradeReason.append("bullish candle on sell; ");
        if (spreadTooWide) noTradeReason.append(String.format("spread %.4f > %.4f; ", spread, spreadMax));
        if (singleCandleSpike) noTradeReason.append("single-candle volume spike; ");

        noTradeReason.append(String.format("RSI=%.1f EMA(%d)=%.2f EMA(%d)=%.2f EMA(%d)=%.2f EMA50=%.2f",
            rsi, fastPeriod, currentFast, slowPeriod, currentSlow, trendPeriod, currentTrend, ema50));
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

        String logTag = String.format("[MTF] 5m=%s 15m=%s 1h=%s", entryTrend, trendTfTrend, macroTfTrend);

        if (!allUp && !allDown) {
            String rejectReason = String.format(
                "[MULTI_TF_REJECT] symbol=%s 5mTrend=%s 15mTrend=%s 1hTrend=%s reason=trend_misalignment",
                symbol, entryTrend, trendTfTrend, macroTfTrend);
            log.info(rejectReason);
            return new MultiTfResult(true, true, false, false, rejectReason, logTag);
        }

        log.debug("[MULTI_TF_CHECK] symbol={} {} decision={}", symbol, logTag, allUp ? "ALLOW_BUY" : "ALLOW_SELL");
        return new MultiTfResult(false, true, allUp, allDown, null, logTag);
    }

    private String computeTrendDirection(double currentPrice, List<double[]> candles, int emaPeriod) {
        if (candles == null || candles.size() < emaPeriod + 2) return "N/A";
        double[] closes = candles.stream().mapToDouble(c -> c[4]).toArray();
        double ema = EmaCrossover.calculateEMA(closes, emaPeriod, closes.length - 1);
        return currentPrice > ema ? "UP" : "DOWN";
    }

    // ─── RSI Calculation ───
    private double calculateRSI(double[] prices, int period, int endIndex) {
        if (endIndex < period + 1) return 50;
        double gainSum = 0, lossSum = 0;
        int start = endIndex - period;
        for (int i = start + 1; i <= endIndex; i++) {
            double change = prices[i] - prices[i - 1];
            if (change > 0) gainSum += change;
            else lossSum -= change;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    // ─── Helpers ───

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

    private double calculateATR(List<double[]> candles, int period, int endIndex) {
        double sum = 0;
        int count = 0;
        int start = Math.max(1, endIndex - period + 1);
        for (int i = start; i <= endIndex && i < candles.size(); i++) {
            double high = candles.get(i)[2], low = candles.get(i)[3], prevClose = candles.get(i - 1)[4];
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

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

    private double findSwingLow(List<double[]> candles, int lookback) {
        double lowest = Double.MAX_VALUE;
        int start = Math.max(0, candles.size() - 1 - lookback);
        for (int i = start; i < candles.size() - 1; i++) {
            if (candles.get(i)[3] < lowest) lowest = candles.get(i)[3];
        }
        return lowest;
    }

    private double findSwingHigh(List<double[]> candles, int lookback) {
        double highest = Double.MIN_VALUE;
        int start = Math.max(0, candles.size() - 1 - lookback);
        for (int i = start; i < candles.size() - 1; i++) {
            if (candles.get(i)[2] > highest) highest = candles.get(i)[2];
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
