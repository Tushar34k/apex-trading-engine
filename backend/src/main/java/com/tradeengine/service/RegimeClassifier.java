package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lightweight market regime classifier.
 *
 * Inputs: close prices + OHLCV candles (oldest → newest).
 * Outputs: TREND | RANGE | CHOP | HIGH_VOL.
 *
 * Heuristics (cheap, no extra dependencies):
 *   - EMA50/EMA200 alignment + slope → TREND
 *   - ATR% above 2x its 50-bar average → HIGH_VOL
 *   - Price oscillating tightly around EMA50 with low ATR → RANGE
 *   - Default fallback → CHOP
 */
@Service
@Slf4j
public class RegimeClassifier {

    public enum Regime { TREND, RANGE, CHOP, HIGH_VOL }

    public Regime classify(List<Double> closes, List<double[]> candles) {
        if (closes == null || closes.size() < 200 || candles == null || candles.size() < 50) {
            return Regime.CHOP;
        }
        double[] p = closes.stream().mapToDouble(Double::doubleValue).toArray();
        int last = p.length - 1;
        double price = p[last];

        double ema50 = ema(p, 50, last);
        double ema200 = ema(p, 200, last);
        double ema50Prev = ema(p, 50, last - 10);
        double slope = (ema50 - ema50Prev) / Math.max(1e-9, ema50Prev);

        double atr = atr(candles, 14, last);
        double atrPct = price > 0 ? atr / price * 100.0 : 0;

        // Average ATR% across last 50 bars
        double avgAtrPct = 0;
        int n = 0;
        for (int i = Math.max(15, last - 50); i < last; i++) {
            double a = atr(candles, 14, i);
            avgAtrPct += a / Math.max(1e-9, p[i]) * 100.0;
            n++;
        }
        avgAtrPct = n > 0 ? avgAtrPct / n : atrPct;

        // HIGH_VOL: current ATR% materially above its own baseline
        if (avgAtrPct > 0 && atrPct > 2.0 * avgAtrPct) return Regime.HIGH_VOL;

        boolean aligned = (price > ema50 && ema50 > ema200) || (price < ema50 && ema50 < ema200);
        boolean strongSlope = Math.abs(slope) > 0.0015; // 0.15% over 10 bars

        if (aligned && strongSlope) return Regime.TREND;

        // RANGE: low ATR% AND price hugging EMA50
        double distFromEma = Math.abs(price - ema50) / Math.max(1e-9, atr);
        if (atrPct < 0.6 * avgAtrPct && distFromEma < 1.0) return Regime.RANGE;

        return Regime.CHOP;
    }

    private double ema(double[] data, int period, int endIndex) {
        if (endIndex < period) return data[Math.max(0, endIndex)];
        double k = 2.0 / (period + 1);
        double e = 0;
        for (int i = 0; i < period; i++) e += data[i];
        e /= period;
        for (int i = period; i <= endIndex; i++) e = (data[i] - e) * k + e;
        return e;
    }

    private double atr(List<double[]> candles, int period, int endIndex) {
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
}
