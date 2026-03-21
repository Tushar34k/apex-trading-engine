package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.ws.MarketDataStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI-based Trade Validation Layer — institutional-grade signal gatekeeper.
 *
 * Evaluates every BUY/SELL signal through a multi-factor confidence model before execution.
 * Returns APPROVE/REJECT with confidence (0–1) and detailed reasoning.
 *
 * Factors scored:
 *   1. Trend alignment (EMA200 + multi-timeframe)    — 25% weight
 *   2. Volume confirmation (vs 20-period VMA)         — 20% weight
 *   3. RSI condition (neutral zone preferred)          — 15% weight
 *   4. Volatility regime (ATR-based)                   — 15% weight
 *   5. Funding rate safety                              — 10% weight
 *   6. Multi-timeframe alignment (1m vs 5m)            — 15% weight
 *
 * Integration points:
 *   - KillSwitchService: blocked if active
 *   - CircuitBreakerService: blocked if open
 *   - RiskManagementService: feeds risk context
 *
 * Fail-safe: if AI evaluation throws, trade is SKIPPED (not approved).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AITradeValidationService {

    private final KillSwitchService killSwitch;
    private final CircuitBreakerService circuitBreaker;
    private final MarketDataStreamService streamService;

    private static final double MIN_CONFIDENCE = 0.65;
    private static final int MAX_DECISION_LOG_SIZE = 500;

    // Decision log for analytics
    private final ConcurrentLinkedDeque<AIDecisionLog> decisionLog = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalApproved = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    // ─── Public Records ───

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

    /**
     * Validate a trade signal through the AI multi-factor model.
     * Thread-safe and exchange-agnostic.
     *
     * @param side            "BUY" or "SELL"
     * @param symbol          exchange-native symbol (e.g. BTCUSDT)
     * @param exchange        exchange name (BINANCE, BYBIT, DELTA)
     * @param closingPrices   entry-timeframe closing prices (oldest → newest)
     * @param candles         entry-timeframe OHLCV (oldest → newest)
     * @param higherTfPrices  higher-TF closing prices for MTF check (nullable)
     * @param fundingRate     current funding rate (nullable)
     * @param params          strategy params (may contain confidence threshold override)
     * @param botId           bot UUID string for logging
     * @return AIValidationResult with decision, confidence, reason
     */
    public AIValidationResult validate(
            String side, String symbol, String exchange,
            List<Double> closingPrices, List<double[]> candles,
            List<Double> higherTfPrices, BigDecimal fundingRate,
            Map<String, Object> params, String botId) {

        long startNanos = System.nanoTime();

        try {
            // ─── Pre-checks: system safety ───
            if (killSwitch.isActive()) {
                return fail(botId, symbol, side, exchange, "Kill switch active: " + killSwitch.getActivationReason(), startNanos);
            }
            if (!circuitBreaker.isAllowed()) {
                return fail(botId, symbol, side, exchange, "Circuit breaker open", startNanos);
            }

            // ─── Data validation ───
            if (closingPrices == null || closingPrices.size() < 200) {
                return fail(botId, symbol, side, exchange, "Insufficient price data (" +
                    (closingPrices == null ? 0 : closingPrices.size()) + " < 200)", startNanos);
            }
            if (candles == null || candles.size() < 200) {
                return fail(botId, symbol, side, exchange, "Insufficient candle data", startNanos);
            }

            double[] prices = closingPrices.stream().mapToDouble(d -> d).toArray();
            int last = prices.length - 1;
            double price = prices[last];
            boolean isBuy = "BUY".equalsIgnoreCase(side);
            double minConfidence = getDouble(params, "aiMinConfidence", MIN_CONFIDENCE);

            Map<String, Double> factors = new LinkedHashMap<>();

            // ─── Factor 1: Trend Alignment (weight 0.25) ───
            double trendScore = scoreTrendAlignment(prices, last, price, isBuy);
            factors.put("trend", trendScore);

            // ─── Factor 2: Volume Confirmation (weight 0.20) ───
            double volumeScore = scoreVolumeConfirmation(candles, last);
            factors.put("volume", volumeScore);

            // ─── Factor 3: RSI Condition (weight 0.15) ───
            double rsiScore = scoreRSICondition(prices, last, isBuy);
            factors.put("rsi", rsiScore);

            // ─── Factor 4: Volatility Regime (weight 0.15) ───
            double volatilityScore = scoreVolatilityRegime(candles, last);
            factors.put("volatility", volatilityScore);

            // ─── Factor 5: Funding Rate Safety (weight 0.10) ───
            double fundingScore = scoreFundingRate(fundingRate, isBuy, params);
            factors.put("funding", fundingScore);

            // ─── Factor 6: Multi-Timeframe Alignment (weight 0.15) ───
            double mtfScore = scoreMTFAlignment(prices, last, higherTfPrices, isBuy);
            factors.put("mtf", mtfScore);

            // ─── Weighted Confidence ───
            double confidence =
                trendScore * 0.25 +
                volumeScore * 0.20 +
                rsiScore * 0.15 +
                volatilityScore * 0.15 +
                fundingScore * 0.10 +
                mtfScore * 0.15;

            // Clamp to [0, 1]
            confidence = Math.max(0, Math.min(1, confidence));

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            // ─── Hard rejection rules (override confidence) ───
            List<String> hardRejects = new ArrayList<>();
            if (trendScore < 0.3) hardRejects.add("trend_misaligned");
            if (volumeScore < 0.2) hardRejects.add("weak_volume");
            if (volatilityScore < 0.2) hardRejects.add("low_volatility");

            if (!hardRejects.isEmpty()) {
                String reason = String.format("HARD_REJECT: %s | confidence=%.3f | factors=%s",
                    String.join("+", hardRejects), confidence, formatFactors(factors));
                return logAndReturn(botId, symbol, side, exchange, Decision.REJECT, confidence, reason, factors, latencyMs);
            }

            // ─── Confidence threshold check ───
            if (confidence < minConfidence) {
                String reason = String.format("LOW_CONFIDENCE: %.3f < %.3f | factors=%s",
                    confidence, minConfidence, formatFactors(factors));
                return logAndReturn(botId, symbol, side, exchange, Decision.REJECT, confidence, reason, factors, latencyMs);
            }

            // ─── APPROVED ───
            String reason = String.format("APPROVED: confidence=%.3f (min=%.3f) | factors=%s",
                confidence, minConfidence, formatFactors(factors));
            return logAndReturn(botId, symbol, side, exchange, Decision.APPROVE, confidence, reason, factors, latencyMs);

        } catch (Exception e) {
            // ─── FAIL-SAFE: AI error → skip trade ───
            totalErrors.incrementAndGet();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("[AI_VALIDATION_ERROR] botId={} symbol={} side={} error={} latency={}ms — SKIPPING TRADE",
                botId, symbol, side, e.getMessage(), latencyMs, e);
            return new AIValidationResult(Decision.REJECT, 0, "AI_ERROR: " + e.getMessage(),
                Map.of(), latencyMs);
        }
    }

    // ─── Factor Scoring Methods ───

    /**
     * Factor 1: Trend alignment using EMA50/EMA200.
     * Returns 0.0–1.0
     */
    private double scoreTrendAlignment(double[] prices, int last, double price, boolean isBuy) {
        double ema50 = calculateEMA(prices, 50, last);
        double ema200 = calculateEMA(prices, 200, last);
        double ema200Prev = last > 1 ? calculateEMA(prices, 200, last - 1) : ema200;

        double score = 0;
        if (isBuy) {
            if (price > ema200) score += 0.35;
            if (price > ema50) score += 0.25;
            if (ema50 > ema200) score += 0.25;  // Golden cross structure
            if (ema200 > ema200Prev) score += 0.15; // EMA200 rising
        } else {
            if (price < ema200) score += 0.35;
            if (price < ema50) score += 0.25;
            if (ema50 < ema200) score += 0.25;
            if (ema200 < ema200Prev) score += 0.15;
        }
        return Math.min(1.0, score);
    }

    /**
     * Factor 2: Volume confirmation — current volume vs 20-period VMA.
     */
    private double scoreVolumeConfirmation(List<double[]> candles, int last) {
        if (last < 20) return 0.5;
        double[] volumes = candles.stream().mapToDouble(c -> c[5]).toArray();
        double vma = 0;
        for (int i = last - 20; i < last; i++) vma += volumes[i];
        vma /= 20;

        double ratio = vma > 0 ? volumes[last] / vma : 1;
        if (ratio >= 2.5) return 1.0;
        if (ratio >= 2.0) return 0.9;
        if (ratio >= 1.5) return 0.75;
        if (ratio >= 1.0) return 0.5;
        if (ratio >= 0.7) return 0.25;
        return 0.1;
    }

    /**
     * Factor 3: RSI condition — neutral zone (40–60) ideal.
     */
    private double scoreRSICondition(double[] prices, int last, boolean isBuy) {
        double rsi = calculateRSI(prices, 14, last);
        if (isBuy) {
            if (rsi > 75) return 0.0;
            if (rsi > 70) return 0.15;
            if (rsi > 60) return 0.5;
            if (rsi >= 40) return 1.0;  // Ideal zone
            if (rsi >= 30) return 0.7;
            return 0.3;
        } else {
            if (rsi < 25) return 0.0;
            if (rsi < 30) return 0.15;
            if (rsi < 40) return 0.5;
            if (rsi <= 60) return 1.0;
            if (rsi <= 70) return 0.7;
            return 0.3;
        }
    }

    /**
     * Factor 4: Volatility regime — moderate ATR is ideal, low = reject.
     */
    private double scoreVolatilityRegime(List<double[]> candles, int last) {
        if (last < 34) return 0.5;
        double currentATR = calculateATR(candles, 14, last);
        double avgATR = 0;
        int count = 0;
        for (int i = Math.max(15, last - 20); i < last; i++) {
            avgATR += calculateATR(candles, 14, i);
            count++;
        }
        avgATR = count > 0 ? avgATR / count : currentATR;
        double ratio = avgATR > 0 ? currentATR / avgATR : 1;

        if (ratio < 0.4) return 0.1;    // Dead market
        if (ratio < 0.6) return 0.3;
        if (ratio < 0.8) return 0.6;
        if (ratio <= 1.5) return 1.0;   // Ideal
        if (ratio <= 2.5) return 0.5;
        return 0.15;                     // Too volatile
    }

    /**
     * Factor 5: Funding rate — extreme rates penalize trade direction.
     */
    private double scoreFundingRate(BigDecimal fundingRate, boolean isBuy, Map<String, Object> params) {
        if (fundingRate == null) return 0.7; // Unknown = slight penalty
        double rate = fundingRate.doubleValue();
        double maxRate = getDouble(params, "maxFundingRate", 0.001);

        if (Math.abs(rate) > maxRate * 2) return 0.0; // Extreme
        if (Math.abs(rate) > maxRate) return 0.3;

        // Positive funding = longs pay shorts (bearish pressure)
        if (isBuy && rate > 0.0005) return 0.5;
        if (!isBuy && rate < -0.0005) return 0.5;

        return 1.0; // Safe range
    }

    /**
     * Factor 6: Multi-timeframe alignment — entry TF trend must align with higher TF.
     * Compares 1m vs 5m (or entry vs trend timeframe).
     */
    private double scoreMTFAlignment(double[] entryPrices, int last, List<Double> higherTfPrices, boolean isBuy) {
        if (higherTfPrices == null || higherTfPrices.size() < 50) return 0.5; // No data = neutral

        double[] htfPrices = higherTfPrices.stream().mapToDouble(d -> d).toArray();
        int htfLast = htfPrices.length - 1;

        double entryEma50 = calculateEMA(entryPrices, 50, last);
        double htfEma50 = calculateEMA(htfPrices, 50, htfLast);
        double htfEma200 = htfLast >= 200 ? calculateEMA(htfPrices, 200, htfLast) : htfEma50;
        double htfPrice = htfPrices[htfLast];

        double score = 0;
        if (isBuy) {
            if (entryPrices[last] > entryEma50) score += 0.35;
            if (htfPrice > htfEma50) score += 0.35;
            if (htfPrice > htfEma200) score += 0.30;
        } else {
            if (entryPrices[last] < entryEma50) score += 0.35;
            if (htfPrice < htfEma50) score += 0.35;
            if (htfPrice < htfEma200) score += 0.30;
        }
        return Math.min(1.0, score);
    }

    // ─── Decision Logging ───

    private AIValidationResult logAndReturn(String botId, String symbol, String side, String exchange,
                                             Decision decision, double confidence, String reason,
                                             Map<String, Double> factors, long latencyMs) {
        if (decision == Decision.APPROVE) {
            totalApproved.incrementAndGet();
            log.info("[AI_APPROVED] botId={} symbol={} side={} exchange={} confidence={} latency={}ms | {}",
                botId, symbol, side, exchange, String.format("%.3f", confidence), latencyMs, reason);
        } else {
            totalRejected.incrementAndGet();
            log.info("[AI_REJECTED] botId={} symbol={} side={} exchange={} confidence={} latency={}ms | {}",
                botId, symbol, side, exchange, String.format("%.3f", confidence), latencyMs, reason);
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

    // ─── Analytics API ───

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

            Map<String, Double> factors = new LinkedHashMap<>();
            factors.put("trend", scoreTrendAlignment(prices, last, prices[last], isBuy));
            factors.put("volume", scoreVolumeConfirmation(candles, last));
            factors.put("rsi", scoreRSICondition(prices, last, isBuy));
            factors.put("volatility", scoreVolatilityRegime(candles, last));
            factors.put("funding", scoreFundingRate(fundingRate, isBuy, params));
            factors.put("mtf", scoreMTFAlignment(prices, last, higherTfPrices, isBuy));

            double confidence = factors.get("trend") * 0.25 + factors.get("volume") * 0.20 +
                factors.get("rsi") * 0.15 + factors.get("volatility") * 0.15 +
                factors.get("funding") * 0.10 + factors.get("mtf") * 0.15;
            confidence = Math.max(0, Math.min(1, confidence));

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            Decision decision = confidence >= minConfidence ? Decision.APPROVE : Decision.REJECT;
            String reason = String.format("%s: confidence=%.3f (min=%.3f)", decision, confidence, minConfidence);
            return new AIValidationResult(decision, confidence, reason, factors, latencyMs);
        } catch (Exception e) {
            return new AIValidationResult(Decision.REJECT, 0, "AI_ERROR: " + e.getMessage(), Map.of(), 0);
        }
    }

    // ─── Technical Indicators ───

    private double calculateEMA(double[] data, int period, int endIndex) {
        if (endIndex < period) return data[Math.min(endIndex, data.length - 1)];
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
            if (change > 0) gainSum += change; else lossSum -= change;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100;
        return 100 - (100 / (1 + avgGain / avgLoss));
    }

    private double calculateATR(List<double[]> candles, int period, int endIndex) {
        double sum = 0; int count = 0;
        int start = Math.max(1, endIndex - period + 1);
        for (int i = start; i <= endIndex && i < candles.size(); i++) {
            double h = candles.get(i)[2], l = candles.get(i)[3], pc = candles.get(i - 1)[4];
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private String formatFactors(Map<String, Double> factors) {
        StringBuilder sb = new StringBuilder();
        factors.forEach((k, v) -> sb.append(k).append("=").append(String.format("%.2f", v)).append(" "));
        return sb.toString().trim();
    }

    private double getDouble(Map<String, Object> params, String key, double def) {
        if (params == null || !params.containsKey(key)) return def;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }
}
