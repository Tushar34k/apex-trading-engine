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
