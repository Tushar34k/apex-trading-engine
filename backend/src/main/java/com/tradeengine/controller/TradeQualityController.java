package com.tradeengine.controller;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.service.CandleCacheService;
import com.tradeengine.service.TradeQualityScorer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Endpoint to preview trade quality scores without placing a trade.
 * Useful for the dashboard trade quality analytics panel.
 */
@RestController
@RequestMapping("/api/trade-quality")
@RequiredArgsConstructor
public class TradeQualityController {

    private final TradeQualityScorer scorer;
    private final ExchangeFactory exchangeFactory;
    private final CandleCacheService candleCacheService;

    @Data
    public static class ScoreRequest {
        private String symbol = "BTCUSDT";
        private String side = "BUY";
        private String timeframe = "5m";
        private String exchange = "BINANCE";
        private Map<String, Object> params;
    }

    @PostMapping("/score")
    public ResponseEntity<?> getScore(@RequestBody ScoreRequest req) {
        try {
            ExchangeClient client = exchangeFactory.getClient(req.getExchange());
            String baseUrl = client.resolveBaseUrl("TESTNET");

            List<double[]> candles = client.getCandles(req.getSymbol(), req.getTimeframe(), 300, baseUrl);
            if (candles.size() < 200) {
                return ResponseEntity.badRequest().body(Map.of("message", "Need 200+ candles, got " + candles.size()));
            }

            List<Double> closingPrices = candles.stream().map(c -> c[4]).collect(Collectors.toList());
            Map<String, Object> params = req.getParams() != null ? req.getParams() : new HashMap<>();

            TradeQualityScorer.QualityScore score = scorer.score(closingPrices, candles, req.getSide(), params);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", score.total());
            result.put("minRequired", params.getOrDefault("minTradeScore", 70));
            result.put("passed", score.passed());
            result.put("trend", score.trendScore());
            result.put("volume", score.volumeScore());
            result.put("rsi", score.rsiScore());
            result.put("volatility", score.volatilityScore());
            result.put("pullback", score.pullbackScore());
            result.put("candle", score.candleScore());
            result.put("breakdown", score.breakdown());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
