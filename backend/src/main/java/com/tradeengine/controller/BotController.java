package com.tradeengine.controller;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.OrderRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.repository.StrategyRepository;
import com.tradeengine.service.BotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotService botService;
    private final BotRepository botRepo;
    private final StrategyRepository strategyRepo;
    private final OrderRepository orderRepo;
    private final PositionRepository positionRepo;

    @Data
    public static class CreateBotRequest {
        @NotBlank private String strategyId;
        @NotBlank private String apiKeyId;
        @NotBlank private String symbol;
        private String timeframe = "1h";
        private String mode = "LIVE";
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var bots = botRepo.findByUserId(getUserId());
        var result = bots.stream().map(b -> {
            var strategy = strategyRepo.findById(b.getStrategyId()).orElse(null);
            // Count trades and calculate PnL
            var orders = orderRepo.findByBotId(b.getId());
            var closedPositions = positionRepo.findByBotIdAndStatus(b.getId(), "CLOSED");
            int totalTrades = closedPositions.size();
            BigDecimal totalPnl = closedPositions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            long wins = closedPositions.stream()
                .filter(p -> p.getRealizedPnl() != null && p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();
            double winRate = totalTrades > 0 ? (double) wins / totalTrades : 0;

            Map<String, Object> map = new HashMap<>();
            map.put("id", b.getId());
            map.put("userId", b.getUserId());
            map.put("strategyId", b.getStrategyId());
            map.put("strategyName", strategy != null ? strategy.getName() : "Unknown");
            map.put("strategyVersion", strategy != null ? strategy.getVersion() : "");
            map.put("apiKeyId", b.getApiKeyId());
            map.put("symbol", b.getSymbol());
            map.put("timeframe", b.getTimeframe());
            map.put("mode", b.getMode());
            map.put("status", b.getStatus());
            map.put("createdAt", b.getCreatedAt().toString());
            map.put("startedAt", b.getStartedAt() != null ? b.getStartedAt().toString() : null);
            map.put("stoppedAt", b.getStoppedAt() != null ? b.getStoppedAt().toString() : null);
            map.put("pnl", totalPnl);
            map.put("totalTrades", totalTrades);
            map.put("winRate", winRate);
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateBotRequest req) {
        TradingBot bot = new TradingBot();
        bot.setUserId(getUserId());
        bot.setStrategyId(UUID.fromString(req.getStrategyId()));
        bot.setApiKeyId(UUID.fromString(req.getApiKeyId()));
        bot.setSymbol(req.getSymbol());
        bot.setTimeframe(req.getTimeframe());
        bot.setMode(req.getMode());
        bot = botRepo.save(bot);
        return ResponseEntity.ok(Map.of("id", bot.getId()));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        botService.startBot(UUID.fromString(id), getUserId());
        return ResponseEntity.ok(Map.of("status", "RUNNING"));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable String id) {
        botService.stopBot(UUID.fromString(id), getUserId());
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        var bot = botRepo.findById(UUID.fromString(id)).orElse(null);
        if (bot == null) return ResponseEntity.notFound().build();
        if (!bot.getUserId().equals(getUserId())) return ResponseEntity.status(403).build();
        Map<String, Object> map = new HashMap<>();
        map.put("id", bot.getId());
        map.put("status", bot.getStatus());
        map.put("symbol", bot.getSymbol());
        map.put("startedAt", bot.getStartedAt() != null ? bot.getStartedAt().toString() : null);
        map.put("stoppedAt", bot.getStoppedAt() != null ? bot.getStoppedAt().toString() : null);
        return ResponseEntity.ok(map);
    }
}
