package com.tradeengine.controller;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.service.AITradeValidationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for AI trade validation — preview scores, view decision history, and analytics.
 */
@RestController
@RequestMapping("/api/ai-validation")
@RequiredArgsConstructor
public class AIValidationController {

    private final AITradeValidationService aiService;
    private final ExchangeFactory exchangeFactory;

    @Data
    public static class PreviewRequest {
        private String symbol = "BTCUSDT";
        private String side = "BUY";
        private String timeframe = "5m";
        private String exchange = "BINANCE";
        private Map<String, Object> params;
    }

    /**
     * Preview AI validation for a symbol without placing a trade.
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody PreviewRequest req) {
        try {
            ExchangeClient client = exchangeFactory.getClient(req.getExchange());
            String baseUrl = client.resolveBaseUrl("TESTNET");

            List<double[]> candles = client.getCandles(req.getSymbol(), req.getTimeframe(), 300, baseUrl);
            if (candles.size() < 200) {
                return ResponseEntity.badRequest().body(Map.of("message", "Need 200+ candles, got " + candles.size()));
            }

            List<Double> closingPrices = candles.stream().map(c -> c[4]).collect(Collectors.toList());
            Map<String, Object> params = req.getParams() != null ? req.getParams() : new HashMap<>();

            BigDecimal fundingRate = null;
            try {
                fundingRate = client.getFundingRate(req.getSymbol(), baseUrl);
            } catch (Exception ignored) {}

            AITradeValidationService.AIValidationResult result = aiService.scoreForBacktest(
                req.getSide(), closingPrices, candles, null, fundingRate, params);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("decision", result.decision().name());
            response.put("confidence", Math.round(result.confidence() * 1000.0) / 1000.0);
            response.put("reason", result.reason());
            response.put("factors", result.factorScores());
            response.put("latencyMs", result.latencyMs());
            response.put("minConfidence", params.getOrDefault("aiMinConfidence", 0.65));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get recent AI decisions and aggregate stats.
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(aiService.getStats());
    }

    /**
     * Get recent AI decision log.
     */
    @GetMapping("/decisions")
    public ResponseEntity<?> getDecisions(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(aiService.getRecentDecisions(Math.min(limit, 200)));
    }
}
