package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trade Quality Scorer — computes a 0–100 score before every trade.
 * Only trades scoring above the threshold (default 70) are allowed.
 *
 * Scoring breakdown (100 total):
 *   - Trend alignment:   25 pts (price vs EMA50/EMA200 + higher TF alignment)
 *   - Volume strength:   20 pts (current vol vs VMA ratio)
 *   - RSI condition:     15 pts (neutral zone = high score, extremes = 0)
 *   - ATR volatility:    15 pts (moderate vol = high, too low/high = low)
 *   - Pullback quality:  15 pts (entry near support/EMA, not chasing)
 *   - Candle structure:  10 pts (body-to-wick ratio, engulfing patterns)
 */
@Service
@Slf4j
public class TradeQualityScorer {

    public enum Tier { FULL, HALF, QUARTER, REJECT }

    public record QualityScore(
        int total,
        int trendScore,
        int volumeScore,
        int rsiScore,
        int volatilityScore,
        int pullbackScore,
        int candleScore,
        String breakdown,
        boolean passed,
        Tier tier,
        double sizeMultiplier
    ) {}

    /**
     * Score a potential trade from 0-100.
     *
     * @param closingPrices price data (oldest → newest)
     * @param candles       OHLCV data (oldest → newest)
     * @param side          "BUY" or "SELL"
     * @param params        strategy params (may contain minTradeScore)
     * @return QualityScore with breakdown
     */
    public QualityScore score(List<Double> closingPrices, List<double[]> candles,
                              String side, Map<String, Object> params) {

        if (closingPrices.size() < 200 || candles.size() < 200) {
            return new QualityScore(0, 0, 0, 0, 0, 0, 0, "Insufficient data for scoring", false);
        }

        double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();
        int last = prices.length - 1;
        double price = prices[last];
        boolean isBuy = "BUY".equalsIgnoreCase(side);

        int minScore = getInt(params, "minTradeScore", 75);

        // --- 1. Trend Alignment (25 pts) ---
        int trendScore = scoreTrend(prices, last, price, isBuy);

        // --- 2. Volume Strength (20 pts) ---
        double[] volumes = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) volumes[i] = candles.get(i)[5];
        int volumeScore = scoreVolume(volumes, last);

        // --- 3. RSI Condition (15 pts) ---
        double rsi = calculateRSI(prices, 14, last);
        int rsiScore = scoreRSI(rsi, isBuy);

        // --- 4. ATR Volatility (15 pts) ---
        int volatilityScore = scoreVolatility(candles, last);

        // --- 5. Pullback Quality (15 pts) ---
        int pullbackScore = scorePullback(prices, candles, last, isBuy);

        // --- 6. Candle Structure (10 pts) ---
        int candleScore = scoreCandleStructure(candles, last, isBuy);

        int total = trendScore + volumeScore + rsiScore + volatilityScore + pullbackScore + candleScore;
        boolean passed = total >= minScore;

        String breakdown = String.format(
            "SCORE=%d/%d [trend=%d vol=%d rsi=%d(%.1f) atr=%d pullback=%d candle=%d] %s",
            total, minScore, trendScore, volumeScore, rsiScore, rsi,
            volatilityScore, pullbackScore, candleScore, passed ? "PASS" : "REJECT");

        log.info("[TRADE_QUALITY] side={} price={} {}", side, price, breakdown);

        return new QualityScore(total, trendScore, volumeScore, rsiScore,
            volatilityScore, pullbackScore, candleScore, breakdown, passed);
    }

    // --- Trend (25 pts): EMA50/EMA200 alignment ---
    private int scoreTrend(double[] prices, int last, double price, boolean isBuy) {
        double ema50 = calculateEMA(prices, 50, last);
        double ema200 = calculateEMA(prices, 200, last);

        int score = 0;

        if (isBuy) {
            if (price > ema200) score += 10; // Price above EMA200
            if (price > ema50) score += 5;   // Price above EMA50
            if (ema50 > ema200) score += 10; // Golden cross structure
        } else {
            if (price < ema200) score += 10;
            if (price < ema50) score += 5;
            if (ema50 < ema200) score += 10;
        }
        return score;
    }

    // --- Volume (20 pts): current volume vs 20-period VMA ---
    private int scoreVolume(double[] volumes, int last) {
        if (last < 20) return 0;
        double vma = 0;
        for (int i = last - 20; i < last; i++) vma += volumes[i];
        vma /= 20;

        double ratio = vma > 0 ? volumes[last] / vma : 0;

        if (ratio >= 2.0) return 20;      // Strong volume confirmation
        if (ratio >= 1.5) return 15;
        if (ratio >= 1.0) return 10;
        if (ratio >= 0.7) return 5;
        return 0;                           // Very weak volume
    }

    // --- RSI (15 pts): soft penalties only — never hard zero ---
    // Trend trades MUST be allowed even at extreme RSI; gate is the score, not this filter.
    private int scoreRSI(double rsi, boolean isBuy) {
        if (isBuy) {
            if (rsi >= 80) return 4;         // Very overbought — small score (was 0)
            if (rsi >= 70) return 8;         // Overbought but trending — partial credit
            if (rsi > 60) return 11;
            if (rsi >= 40) return 15;        // Ideal zone
            if (rsi >= 30) return 10;
            return 6;
        } else {
            if (rsi <= 20) return 4;         // Very oversold — small score (was 0)
            if (rsi <= 30) return 8;
            if (rsi < 40) return 11;
            if (rsi <= 60) return 15;
            if (rsi <= 70) return 10;
            return 6;
        }
    }

    // --- Volatility (15 pts): moderate ATR is ideal ---
    private int scoreVolatility(List<double[]> candles, int last) {
        if (last < 34) return 0;

        double currentATR = calculateATR(candles, 14, last);
        double avgATR = 0;
        int count = 0;
        for (int i = Math.max(15, last - 20); i < last; i++) {
            avgATR += calculateATR(candles, 14, i);
            count++;
        }
        avgATR = count > 0 ? avgATR / count : currentATR;

        double ratio = avgATR > 0 ? currentATR / avgATR : 1;

        if (ratio < 0.5) return 3;          // Too calm — likely sideways
        if (ratio < 0.8) return 8;
        if (ratio <= 1.5) return 15;        // Moderate — ideal
        if (ratio <= 2.5) return 8;         // Getting volatile
        return 3;                            // Extremely volatile — danger
    }

    // --- Pullback (15 pts): entry near EMA, not chasing ---
    private int scorePullback(double[] prices, List<double[]> candles, int last, boolean isBuy) {
        double ema21 = calculateEMA(prices, 21, last);
        double price = prices[last];
        double atr = calculateATR(candles, 14, last);
        if (atr <= 0) return 0;

        double distanceFromEma = Math.abs(price - ema21) / atr;

        // Soft pullback ladder — distant entries get reduced credit, never zero.
        if (isBuy) {
            if (price < ema21) return 6;             // Below EMA — partial (was 5)
            if (distanceFromEma <= 0.5) return 15;   // Tight pullback — excellent
            if (distanceFromEma <= 1.0) return 13;
            if (distanceFromEma <= 2.0) return 10;
            if (distanceFromEma <= 3.5) return 7;
            return 4;                                // Chasing — small score (was 0)
        } else {
            if (price > ema21) return 6;
            if (distanceFromEma <= 0.5) return 15;
            if (distanceFromEma <= 1.0) return 13;
            if (distanceFromEma <= 2.0) return 10;
            if (distanceFromEma <= 3.5) return 7;
            return 4;
        }
    }

    // --- Candle Structure (10 pts): body-to-wick ratio, engulfing ---
    private int scoreCandleStructure(List<double[]> candles, int last, boolean isBuy) {
        if (last < 2) return 0;

        double[] cur = candles.get(last);
        double[] prev = candles.get(last - 1);
        double open = cur[1], high = cur[2], low = cur[3], close = cur[4];
        double body = Math.abs(close - open);
        double range = high - low;
        if (range <= 0) return 0;

        double bodyRatio = body / range;
        int score = 0;

        // Strong body (>60% of range)
        if (bodyRatio > 0.6) score += 5;
        else if (bodyRatio > 0.4) score += 3;

        // Engulfing pattern
        double prevBody = Math.abs(prev[4] - prev[1]);
        if (isBuy && close > open && body > prevBody && close > prev[2]) {
            score += 5; // Bullish engulfing
        } else if (!isBuy && close < open && body > prevBody && close < prev[3]) {
            score += 5; // Bearish engulfing
        } else {
            // Directional candle check
            if (isBuy && close > open) score += 2;
            if (!isBuy && close < open) score += 2;
        }

        return Math.min(score, 10);
    }

    // --- Technical Indicators ---

    private double calculateEMA(double[] data, int period, int endIndex) {
        if (endIndex < period) return data[endIndex];
        double multiplier = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period && i <= endIndex; i++) ema += data[i];
        ema /= period;
        for (int i = period; i <= endIndex; i++) {
            ema = (data[i] - ema) * multiplier + ema;
        }
        return ema;
    }

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

    private double calculateATR(List<double[]> candles, int period, int endIndex) {
        double sum = 0;
        int count = 0;
        int start = Math.max(1, endIndex - period + 1);
        for (int i = start; i <= endIndex && i < candles.size(); i++) {
            double h = candles.get(i)[2], l = candles.get(i)[3], pc = candles.get(i - 1)[4];
            double tr = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private int getInt(Map<String, Object> params, String key, int def) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }
}
