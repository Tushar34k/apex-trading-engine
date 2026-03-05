package com.tradeengine.controller;

import com.tradeengine.exchange.BinanceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final BinanceClient binance;

    @GetMapping("/candles")
    public ResponseEntity<?> getCandles(
        @RequestParam String symbol,
        @RequestParam(defaultValue = "1h") String timeframe,
        @RequestParam(defaultValue = "200") int limit
    ) {
        List<double[]> candles = binance.getCandles(symbol, mapTimeframe(timeframe), limit);
        var result = candles.stream().map(c -> Map.of(
            "time", (long) c[0],
            "open", c[1],
            "high", c[2],
            "low", c[3],
            "close", c[4],
            "volume", c[5]
        )).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/support-resistance")
    public ResponseEntity<?> getSupportResistance(
        @RequestParam String symbol,
        @RequestParam(defaultValue = "1h") String timeframe,
        @RequestParam(defaultValue = "200") int limit
    ) {
        List<double[]> candles = binance.getCandles(symbol, mapTimeframe(timeframe), limit);
        List<Double> highs = candles.stream().map(c -> c[2]).toList();
        List<Double> lows = candles.stream().map(c -> c[3]).toList();

        // Detect swing highs/lows (local extremes over 5-candle window)
        List<Map<String, Object>> levels = new java.util.ArrayList<>();
        int window = 5;
        for (int i = window; i < candles.size() - window; i++) {
            boolean isSwingHigh = true;
            boolean isSwingLow = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j == i) continue;
                if (highs.get(j) >= highs.get(i)) isSwingHigh = false;
                if (lows.get(j) <= lows.get(i)) isSwingLow = false;
            }
            if (isSwingHigh) {
                levels.add(Map.of("price", highs.get(i), "type", "resistance", "strength", 1));
            }
            if (isSwingLow) {
                levels.add(Map.of("price", lows.get(i), "type", "support", "strength", 1));
            }
        }

        // Cluster nearby levels (within 0.5% of each other)
        List<Map<String, Object>> clustered = new java.util.ArrayList<>();
        List<Boolean> used = new java.util.ArrayList<>(java.util.Collections.nCopies(levels.size(), false));
        for (int i = 0; i < levels.size(); i++) {
            if (used.get(i)) continue;
            double price = ((Number) levels.get(i).get("price")).doubleValue();
            String type = (String) levels.get(i).get("type");
            int strength = 1;
            for (int j = i + 1; j < levels.size(); j++) {
                if (used.get(j)) continue;
                double otherPrice = ((Number) levels.get(j).get("price")).doubleValue();
                if (Math.abs(price - otherPrice) / price < 0.005) {
                    price = (price + otherPrice) / 2;
                    strength++;
                    used.set(j, true);
                }
            }
            Map<String, Object> level = new java.util.HashMap<>();
            level.put("price", Math.round(price * 100.0) / 100.0);
            level.put("type", type);
            level.put("strength", strength);
            clustered.add(level);
        }

        // Sort by strength desc, take top 10
        clustered.sort((a, b) -> Integer.compare((int) b.get("strength"), (int) a.get("strength")));
        var result = clustered.stream().limit(10).toList();
        return ResponseEntity.ok(result);
    }

    private String mapTimeframe(String tf) {
        return switch (tf.toLowerCase()) {
            case "1m" -> "1m";
            case "5m" -> "5m";
            case "15m" -> "15m";
            case "1h" -> "1h";
            case "4h" -> "4h";
            case "1d" -> "1d";
            default -> "1h";
        };
    }
}
