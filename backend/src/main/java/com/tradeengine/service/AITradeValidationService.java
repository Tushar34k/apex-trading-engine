package com.tradeengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI Trade Validation Service v2 — Enhanced Multi-Factor Confidence Engine
 *
 * 9-factor weighted scoring model:
 *   1. Trend alignment (EMA50/200)          — 15% weight
 *   2. EMA slope/momentum (EMA9/21 angle)   — 15% weight  [NEW]
 *   3. Volume confirmation                   — 12% weight
 *   4. RSI condition                         — 10% weight
 *   5. Volatility regime (ATR)               — 10% weight
 *   6. Funding rate safety                   — 8% weight
 *   7. Multi-timeframe alignment             — 12% weight
 *   8. Candle structure/patterns             — 10% weight  [NEW]
 *   9. Price-EMA proximity (pullback)        — 8% weight   [NEW]
 *
 * Fail-safe: AI errors → trade SKIPPED
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AITradeValidationService {

    private final KillSwitchService killSwitch;
    private final CircuitBreakerService circuitBreaker;

    private static final double MIN_CONFIDENCE = 0.65;
    private static final int MAX_DECISION_LOG_SIZE = 500;

    private final ConcurrentLinkedDeque<AIDecisionLog> decisionLog = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalApproved = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    public enum Decision { APPROVE, REJECT }

    public record AIValidationResult(
        Decision decision,
        double confidence,
        String reason,
        Map<String, Double> factorScores,
        long latencyMs
    ) {
        public boolean isApproved() { return decision == Decision.APPROVE; }
    }

    public record AIDecisionLog(
        Instant timestamp,
        String botId,
        String symbol,
        String side,
        String exchange,
        Decision decision,
        double confidence,
        String reason,
        long latencyMs
    ) {}

    // ─── Core Validation ───

    public AIValidationResult validate(
            String side, String symbol, String exchange,
            List<Double> closingPrices, List<double[]> candles,
            List<Double> higherTfPrices, BigDecimal fundingRate,
            Map<String, Object> params, String botId) {

        long startNanos = System.nanoTime();
        try {
            if (killSwitch.isActive()) {
                return fail(botId, symbol, side, exchange, "Kill switch active: " + killSwitch.getActivationReason(), startNanos);
            }
            if (!circuitBreaker.isAllowed()) {
                return fail(botId, symbol, side, exchange, "Circuit breaker open", startNanos);
            }
            if (closingPrices == null || closingPrices.size() < 200 || candles == null || candles.size() < 200) {
                return fail(botId, symbol, side, exchange, "Insufficient data", startNanos);
            }

            double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();
            int last = prices.length - 1;
            double price = prices[last];
            boolean isBuy = "BUY".equalsIgnoreCase(side);
            double minConfidence = getDouble(params, "aiMinConfidence", MIN_CONFIDENCE);

            Map<String, Double> factors = computeFactors(prices, last, price, isBuy, candles, higherTfPrices, fundingRate, params);

            double confidence = computeWeightedConfidence(factors);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            // Hard rejection rules
            List<String> hardRejects = new ArrayList<>();
            if (factors.getOrDefault("trend", 0.0) < 0.25) hardRejects.add("trend_misaligned");
            if (factors.getOrDefault("momentum", 0.0) < 0.2) hardRejects.add("no_momentum");
            if (factors.getOrDefault("volume", 0.0) < 0.2) hardRejects.add("weak_volume");
            if (factors.getOrDefault("volatility", 0.0) < 0.15) hardRejects.add("dead_market");

            if (!hardRejects.isEmpty()) {
                String reason = String.format("HARD_REJECT: %s | conf=%.3f | %s",
                    String.join("+", hardRejects), confidence, formatFactors(factors));
                return logAndReturn(botId, symbol, side, exchange, Decision.REJECT, confidence, reason, factors, latencyMs);
            }

            if (confidence < minConfidence) {
                String reason = String.format("LOW_CONF: %.3f < %.3f | %s", confidence, minConfidence, formatFactors(factors));
                return logAndReturn(botId, symbol, side, exchange, Decision.REJECT, confidence, reason, factors, latencyMs);
            }

            String reason = String.format("APPROVED: conf=%.3f (min=%.3f) | %s", confidence, minConfidence, formatFactors(factors));
            return logAndReturn(botId, symbol, side, exchange, Decision.APPROVE, confidence, reason, factors, latencyMs);

        } catch (Exception e) {
            totalErrors.incrementAndGet();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("[AI_ERROR] botId={} symbol={} error={} — SKIPPING TRADE", botId, symbol, e.getMessage(), e);
            return new AIValidationResult(Decision.REJECT, 0, "AI_ERROR: " + e.getMessage(), Map.of(), latencyMs);
        }
    }

    // ─── Factor Computation ───

    private Map<String, Double> computeFactors(double[] prices, int last, double price, boolean isBuy,
                                                 List<double[]> candles, List<Double> higherTfPrices,
                                                 BigDecimal fundingRate, Map<String, Object> params) {
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("trend", scoreTrend(prices, last, price, isBuy));
        f.put("momentum", scoreMomentum(prices, last, isBuy));
        f.put("volume", scoreVolume(candles, last));
        f.put("rsi", scoreRSI(prices, last, isBuy));
        f.put("volatility", scoreVolatility(candles, last));
        f.put("funding", scoreFunding(fundingRate, isBuy, params));
        f.put("mtf", scoreMTF(prices, last, higherTfPrices, isBuy));
        f.put("candle", scoreCandlePattern(candles, last, isBuy));
        f.put("pullback", scorePullback(prices, candles, last, isBuy));
        return f;
    }

    private double computeWeightedConfidence(Map<String, Double> f) {
        double c = f.getOrDefault("trend", 0.0) * 0.15
            + f.getOrDefault("momentum", 0.0) * 0.15
            + f.getOrDefault("volume", 0.0) * 0.12
            + f.getOrDefault("rsi", 0.0) * 0.10
            + f.getOrDefault("volatility", 0.0) * 0.10
            + f.getOrDefault("funding", 0.0) * 0.08
            + f.getOrDefault("mtf", 0.0) * 0.12
            + f.getOrDefault("candle", 0.0) * 0.10
            + f.getOrDefault("pullback", 0.0) * 0.08;
        return Math.max(0, Math.min(1, c));
    }

    // ─── Factor 1: Trend (EMA50/200) — 15% ───

    private double scoreTrend(double[] prices, int last, double price, boolean isBuy) {
        double ema50 = ema(prices, 50, last);
        double ema200 = ema(prices, 200, last);
        double ema200Prev = last > 1 ? ema(prices, 200, last - 1) : ema200;

        double s = 0;
        if (isBuy) {
            if (price > ema200) s += 0.35;
            if (price > ema50) s += 0.25;
            if (ema50 > ema200) s += 0.25;
            if (ema200 > ema200Prev) s += 0.15;
        } else {
            if (price < ema200) s += 0.35;
            if (price < ema50) s += 0.25;
            if (ema50 < ema200) s += 0.25;
            if (ema200 < ema200Prev) s += 0.15;
        }
        return Math.min(1.0, s);
    }

    // ─── Factor 2: Momentum (EMA9/21 slope) — 15% [NEW] ───

    private double scoreMomentum(double[] prices, int last, boolean isBuy) {
        if (last < 22) return 0.5;

        double ema9 = ema(prices, 9, last);
        double ema9Prev = ema(prices, 9, last - 1);
        double ema9Prev2 = ema(prices, 9, last - 2);
        double ema21 = ema(prices, 21, last);
        double ema21Prev = ema(prices, 21, last - 1);

        double s = 0;

        // EMA9 slope (angle) — measures short-term momentum
        double ema9Slope = (ema9 - ema9Prev) / ema9Prev * 10000; // basis points
        // EMA9 acceleration (slope of slope) — momentum strengthening
        double ema9Accel = ((ema9 - ema9Prev) - (ema9Prev - ema9Prev2));

        if (isBuy) {
            if (ema9 > ema21) s += 0.30;          // Fast above slow
            if (ema9Slope > 5) s += 0.25;          // Positive slope > 5bps
            else if (ema9Slope > 0) s += 0.10;
            if (ema9Accel > 0) s += 0.20;          // Momentum accelerating
            if (ema21 > ema21Prev) s += 0.25;      // Slow EMA rising
        } else {
            if (ema9 < ema21) s += 0.30;
            if (ema9Slope < -5) s += 0.25;
            else if (ema9Slope < 0) s += 0.10;
            if (ema9Accel < 0) s += 0.20;
            if (ema21 < ema21Prev) s += 0.25;
        }
        return Math.min(1.0, s);
    }

    // ─── Factor 3: Volume — 12% ───

    private double scoreVolume(List<double[]> candles, int last) {
        if (last < 20) return 0.5;
        double[] vols = candles.stream().mapToDouble(c -> c[5]).toArray();
        double vma = 0;
        for (int i = last - 20; i < last; i++) vma += vols[i];
        vma /= 20;
        double ratio = vma > 0 ? vols[last] / vma : 1;

        if (ratio >= 2.5) return 1.0;
        if (ratio >= 2.0) return 0.85;
        if (ratio >= 1.5) return 0.70;
        if (ratio >= 1.0) return 0.50;
        if (ratio >= 0.7) return 0.25;
        return 0.1;
    }

    // ─── Factor 4: RSI — 10% ───

    private double scoreRSI(double[] prices, int last, boolean isBuy) {
        double rsi = rsi(prices, 14, last);
        if (isBuy) {
            if (rsi > 75) return 0.0;
            if (rsi > 70) return 0.15;
            if (rsi > 60) return 0.50;
            if (rsi >= 40) return 1.0;
            if (rsi >= 30) return 0.70;
            return 0.30;
        } else {
            if (rsi < 25) return 0.0;
            if (rsi < 30) return 0.15;
            if (rsi < 40) return 0.50;
            if (rsi <= 60) return 1.0;
            if (rsi <= 70) return 0.70;
            return 0.30;
        }
    }

    // ─── Factor 5: Volatility (ATR) — 10% ───

    private double scoreVolatility(List<double[]> candles, int last) {
        if (last < 34) return 0.5;
        double curATR = atr(candles, 14, last);
        double avgATR = 0;
        int cnt = 0;
        for (int i = Math.max(15, last - 20); i < last; i++) {
            avgATR += atr(candles, 14, i);
            cnt++;
        }
        avgATR = cnt > 0 ? avgATR / cnt : curATR;
        double ratio = avgATR > 0 ? curATR / avgATR : 1;

        if (ratio < 0.4) return 0.05;
        if (ratio < 0.6) return 0.25;
        if (ratio < 0.8) return 0.55;
        if (ratio <= 1.5) return 1.0;
        if (ratio <= 2.5) return 0.45;
        return 0.10;
    }

    // ─── Factor 6: Funding Rate — 8% ───

    private double scoreFunding(BigDecimal fundingRate, boolean isBuy, Map<String, Object> params) {
        if (fundingRate == null) return 0.7;
        double rate = fundingRate.doubleValue();
        double maxRate = getDouble(params, "maxFundingRate", 0.001);

        if (Math.abs(rate) > maxRate * 2) return 0.0;
        if (Math.abs(rate) > maxRate) return 0.3;
        if (isBuy && rate > 0.0005) return 0.5;
        if (!isBuy && rate < -0.0005) return 0.5;
        return 1.0;
    }

    // ─── Factor 7: Multi-Timeframe — 12% ───

    private double scoreMTF(double[] entryPrices, int last, List<Double> htfPrices, boolean isBuy) {
        if (htfPrices == null || htfPrices.size() < 50) return 0.5;
        double[] htf = htfPrices.stream().mapToDouble(d -> d).toArray();
        int htfLast = htf.length - 1;

        double entryEma50 = ema(entryPrices, 50, last);
        double htfEma50 = ema(htf, 50, htfLast);
        double htfEma200 = htfLast >= 200 ? ema(htf, 200, htfLast) : htfEma50;
        double htfPrice = htf[htfLast];

        // Also check HTF EMA9/21 alignment
        double htfEma9 = ema(htf, 9, htfLast);
        double htfEma21 = ema(htf, 21, htfLast);

        double s = 0;
        if (isBuy) {
            if (entryPrices[last] > entryEma50) s += 0.20;
            if (htfPrice > htfEma50) s += 0.20;
            if (htfPrice > htfEma200) s += 0.20;
            if (htfEma9 > htfEma21) s += 0.20;  // HTF short-term momentum aligns
            if (htfEma50 > htfEma200) s += 0.20; // HTF golden cross
        } else {
            if (entryPrices[last] < entryEma50) s += 0.20;
            if (htfPrice < htfEma50) s += 0.20;
            if (htfPrice < htfEma200) s += 0.20;
            if (htfEma9 < htfEma21) s += 0.20;
            if (htfEma50 < htfEma200) s += 0.20;
        }
        return Math.min(1.0, s);
    }

    // ─── Factor 8: Candle Pattern — 10% [NEW] ───

    private double scoreCandlePattern(List<double[]> candles, int last, boolean isBuy) {
        if (last < 3) return 0.5;

        double[] cur = candles.get(last);
        double[] prev = candles.get(last - 1);
        double[] prev2 = candles.get(last - 2);

        double open = cur[1], high = cur[2], low = cur[3], close = cur[4];
        double body = Math.abs(close - open);
        double range = high - low;
        if (range <= 0) return 0.1;

        double bodyRatio = body / range;
        double s = 0;

        // Strong body (>60% of range = conviction)
        if (bodyRatio > 0.65) s += 0.25;
        else if (bodyRatio > 0.45) s += 0.15;

        // Directional confirmation
        boolean bullish = close > open;
        boolean bearish = close < open;
        if (isBuy && bullish) s += 0.15;
        if (!isBuy && bearish) s += 0.15;

        // Engulfing pattern
        double prevBody = Math.abs(prev[4] - prev[1]);
        if (isBuy && bullish && body > prevBody && close > prev[2]) {
            s += 0.25; // Bullish engulfing
        } else if (!isBuy && bearish && body > prevBody && close < prev[3]) {
            s += 0.25; // Bearish engulfing
        }

        // Three-candle pattern: two opposing then strong confirmation
        boolean prev2Bearish = prev2[4] < prev2[1];
        boolean prevBearish = prev[4] < prev[1];
        if (isBuy && prev2Bearish && prevBearish && bullish && body > prevBody) {
            s += 0.20; // Morning star variant
        }
        boolean prev2Bullish = prev2[4] > prev2[1];
        boolean prevBullish = prev[4] > prev[1];
        if (!isBuy && prev2Bullish && prevBullish && bearish && body > prevBody) {
            s += 0.20; // Evening star variant
        }

        // Wick rejection (hammer/shooting star)
        double upperWick = bullish ? (high - close) : (high - open);
        double lowerWick = bullish ? (open - low) : (close - low);
        if (isBuy && lowerWick > body * 2 && upperWick < body * 0.5) {
            s += 0.15; // Hammer pattern
        }
        if (!isBuy && upperWick > body * 2 && lowerWick < body * 0.5) {
            s += 0.15; // Shooting star
        }

        return Math.min(1.0, s);
    }

    // ─── Factor 9: Pullback Quality — 8% [NEW] ───

    private double scorePullback(double[] prices, List<double[]> candles, int last, boolean isBuy) {
        double ema21 = ema(prices, 21, last);
        double price = prices[last];
        double curATR = atr(candles, 14, last);
        if (curATR <= 0) return 0.5;

        double dist = Math.abs(price - ema21) / curATR;

        if (isBuy) {
            if (price < ema21) return 0.3;          // Below EMA — risky
            if (dist <= 0.5) return 1.0;             // Tight pullback — ideal
            if (dist <= 1.0) return 0.80;
            if (dist <= 1.5) return 0.55;
            if (dist <= 2.5) return 0.25;
            return 0.05;                              // Chasing
        } else {
            if (price > ema21) return 0.3;
            if (dist <= 0.5) return 1.0;
            if (dist <= 1.0) return 0.80;
            if (dist <= 1.5) return 0.55;
            if (dist <= 2.5) return 0.25;
            return 0.05;
        }
    }

    // ─── Logging ───

    private AIValidationResult logAndReturn(String botId, String symbol, String side, String exchange,
                                             Decision decision, double confidence, String reason,
                                             Map<String, Double> factors, long latencyMs) {
        if (decision == Decision.APPROVE) {
            totalApproved.incrementAndGet();
            log.info("[AI_APPROVED] bot={} {}:{} {} conf={} {}ms | {}", botId, exchange, symbol, side,
                String.format("%.3f", confidence), latencyMs, reason);
        } else {
            totalRejected.incrementAndGet();
            log.info("[AI_REJECTED] bot={} {}:{} {} conf={} {}ms | {}", botId, exchange, symbol, side,
                String.format("%.3f", confidence), latencyMs, reason);
        }

        AIDecisionLog entry = new AIDecisionLog(Instant.now(), botId, symbol, side, exchange,
            decision, confidence, reason, latencyMs);
        decisionLog.addFirst(entry);
        while (decisionLog.size() > MAX_DECISION_LOG_SIZE) decisionLog.removeLast();

        return new AIValidationResult(decision, confidence, reason, factors, latencyMs);
    }

    private AIValidationResult fail(String botId, String symbol, String side, String exchange,
                                     String reason, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return logAndReturn(botId, symbol, side, exchange, Decision.REJECT, 0, reason, Map.of(), latencyMs);
    }

    // ─── Analytics ───

    public List<AIDecisionLog> getRecentDecisions(int limit) {
        return decisionLog.stream().limit(limit).toList();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalApproved", totalApproved.get());
        stats.put("totalRejected", totalRejected.get());
        stats.put("totalErrors", totalErrors.get());
        long total = totalApproved.get() + totalRejected.get();
        stats.put("approvalRate", total > 0 ? Math.round(totalApproved.get() * 10000.0 / total) / 100.0 : 0);
        stats.put("recentDecisions", getRecentDecisions(20));
        return stats;
    }

    /**
     * Standalone scoring for backtesting — no system safety checks, pure factor analysis.
     */
    public AIValidationResult scoreForBacktest(
            String side, List<Double> closingPrices, List<double[]> candles,
            List<Double> higherTfPrices, BigDecimal fundingRate, Map<String, Object> params) {
        long startNanos = System.nanoTime();
        try {
            if (closingPrices == null || closingPrices.size() < 200 || candles == null || candles.size() < 200) {
                return new AIValidationResult(Decision.REJECT, 0, "Insufficient data", Map.of(), 0);
            }
            double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();
            int last = prices.length - 1;
            boolean isBuy = "BUY".equalsIgnoreCase(side);
            double minConfidence = getDouble(params, "aiMinConfidence", MIN_CONFIDENCE);

            Map<String, Double> factors = computeFactors(prices, last, prices[last], isBuy, candles, higherTfPrices, fundingRate, params);
            double confidence = computeWeightedConfidence(factors);

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            // Apply same hard rejection rules as live trading
            List<String> hardRejects = new ArrayList<>();
            if (factors.getOrDefault("trend", 0.0) < 0.25) hardRejects.add("trend_misaligned");
            if (factors.getOrDefault("momentum", 0.0) < 0.2) hardRejects.add("no_momentum");
            if (factors.getOrDefault("volume", 0.0) < 0.2) hardRejects.add("weak_volume");
            if (factors.getOrDefault("volatility", 0.0) < 0.15) hardRejects.add("dead_market");

            if (!hardRejects.isEmpty()) {
                String reason = String.format("HARD_REJECT: %s | conf=%.3f", String.join("+", hardRejects), confidence);
                return new AIValidationResult(Decision.REJECT, confidence, reason, factors, latencyMs);
            }

            Decision decision = confidence >= minConfidence ? Decision.APPROVE : Decision.REJECT;
            String reason = String.format("%s: conf=%.3f (min=%.3f)", decision, confidence, minConfidence);
            return new AIValidationResult(decision, confidence, reason, factors, latencyMs);
        } catch (Exception e) {
            return new AIValidationResult(Decision.REJECT, 0, "AI_ERROR: " + e.getMessage(), Map.of(), 0);
        }
    }

    // ─── Technical Indicators ───

    private double ema(double[] data, int period, int endIndex) {
        if (endIndex < period) return data[Math.min(endIndex, data.length - 1)];
        double m = 2.0 / (period + 1);
        double e = 0;
        for (int i = 0; i < period && i <= endIndex; i++) e += data[i];
        e /= period;
        for (int i = period; i <= endIndex; i++) e = (data[i] - e) * m + e;
        return e;
    }

    private double rsi(double[] prices, int period, int endIndex) {
        if (endIndex < period + 1) return 50;
        double g = 0, l = 0;
        int start = endIndex - period;
        for (int i = start + 1; i <= endIndex; i++) {
            double d = prices[i] - prices[i - 1];
            if (d > 0) g += d; else l -= d;
        }
        double ag = g / period, al = l / period;
        if (al == 0) return 100;
        return 100 - (100 / (1 + ag / al));
    }

    private double atr(List<double[]> candles, int period, int endIndex) {
        double sum = 0; int cnt = 0;
        int start = Math.max(1, endIndex - period + 1);
        for (int i = start; i <= endIndex && i < candles.size(); i++) {
            double h = candles.get(i)[2], lo = candles.get(i)[3], pc = candles.get(i - 1)[4];
            sum += Math.max(h - lo, Math.max(Math.abs(h - pc), Math.abs(lo - pc)));
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private String formatFactors(Map<String, Double> factors) {
        StringBuilder sb = new StringBuilder();
        factors.forEach((k, v) -> sb.append(k).append("=").append(String.format("%.0f%%", v * 100)).append(" "));
        return sb.toString().trim();
    }

    private double getDouble(Map<String, Object> params, String key, double def) {
        if (params == null || !params.containsKey(key)) return def;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }
}
