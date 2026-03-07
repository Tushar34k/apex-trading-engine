package com.tradeengine.controller;

import com.tradeengine.model.TradingBot;
import com.tradeengine.model.TradePosition;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.service.BotService;
import com.tradeengine.strategy.StrategyFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
@Slf4j
public class BotController {

    private final BotService botService;
    private final BotRepository botRepo;
    private final PositionRepository positionRepo;

    @Data
    public static class CreateBotRequest {
        @NotBlank private String name;
        @NotBlank private String symbol;
        private String timeframe = "1m";
        private String strategyType = "EMA_CROSS";
        private int fastEma = 9;
        private int slowEma = 21;
        private BigDecimal tradeSizePercent = new BigDecimal("10");
        @NotBlank private String apiKeyId;
        private String exchangeMode = "TESTNET";
        private String strategyParams;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var bots = botRepo.findByUserId(getUserId());
        var result = bots.stream().map(this::toMap).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateBotRequest req) {
        if (!StrategyFactory.exists(req.getStrategyType())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Unknown strategy: " + req.getStrategyType()));
        }

        String mode = req.getExchangeMode();
        if (mode == null || (!mode.equals("TESTNET") && !mode.equals("LIVE"))) {
            mode = "TESTNET";
        }

        TradingBot bot = new TradingBot();
        bot.setUserId(getUserId());
        bot.setName(req.getName());
        bot.setSymbol(req.getSymbol().toUpperCase());
        bot.setTimeframe(req.getTimeframe());
        bot.setStrategyType(req.getStrategyType());
        bot.setFastEma(req.getFastEma());
        bot.setSlowEma(req.getSlowEma());
        bot.setTradeSizePercent(req.getTradeSizePercent());
        bot.setApiKeyId(UUID.fromString(req.getApiKeyId()));
        bot.setExchangeMode(mode);
        bot.setStrategyParams(req.getStrategyParams());
        bot = botRepo.save(bot);
        return ResponseEntity.ok(toMap(bot));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        try {
            botService.startBot(UUID.fromString(id), getUserId());
            return ResponseEntity.ok(Map.of("status", "RUNNING"));
        } catch (RuntimeException e) {
            log.warn("Failed to start bot {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
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
        return ResponseEntity.ok(toMap(bot));
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<?> stats(@PathVariable String id) {
        UUID botId = UUID.fromString(id);
        var bot = botRepo.findById(botId).orElse(null);
        if (bot == null) return ResponseEntity.notFound().build();
        if (!bot.getUserId().equals(getUserId())) return ResponseEntity.status(403).build();

        List<TradePosition> positions = positionRepo.findByBotId(botId);
        BigDecimal totalPnl = BigDecimal.ZERO;
        int totalTrades = 0, wins = 0, losses = 0;
        BigDecimal totalProfit = BigDecimal.ZERO, totalLoss = BigDecimal.ZERO;

        for (TradePosition p : positions) {
            if ("CLOSED".equals(p.getStatus()) && p.getRealizedPnl() != null) {
                totalPnl = totalPnl.add(p.getRealizedPnl());
                totalTrades++;
                if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) {
                    wins++;
                    totalProfit = totalProfit.add(p.getRealizedPnl());
                } else {
                    losses++;
                    totalLoss = totalLoss.add(p.getRealizedPnl());
                }
            }
        }

        double winRate = totalTrades > 0 ? (double) wins / totalTrades * 100 : 0;
        double avgProfit = wins > 0 ? totalProfit.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP).doubleValue() : 0;
        double avgLoss = losses > 0 ? totalLoss.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP).doubleValue() : 0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pnl", totalPnl);
        stats.put("totalTrades", totalTrades);
        stats.put("wins", wins);
        stats.put("losses", losses);
        stats.put("winRate", Math.round(winRate * 10) / 10.0);
        stats.put("avgProfit", avgProfit);
        stats.put("avgLoss", avgLoss);
        return ResponseEntity.ok(stats);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        UUID botId = UUID.fromString(id);
        var bot = botRepo.findById(botId).orElse(null);
        if (bot == null) return ResponseEntity.notFound().build();
        if (!bot.getUserId().equals(getUserId())) return ResponseEntity.status(403).build();
        if ("RUNNING".equals(bot.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Stop bot before deleting"));
        }
        botRepo.delete(bot);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    private Map<String, Object> toMap(TradingBot b) {
        List<TradePosition> allPositions = positionRepo.findByBotId(b.getId());
        BigDecimal totalPnl = BigDecimal.ZERO;
        int totalTrades = 0;
        int wins = 0;
        for (TradePosition p : allPositions) {
            if ("CLOSED".equals(p.getStatus()) && p.getRealizedPnl() != null) {
                totalPnl = totalPnl.add(p.getRealizedPnl());
                totalTrades++;
                if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) wins++;
            }
        }
        double winRate = totalTrades > 0 ? (double) wins / totalTrades * 100 : 0;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", b.getId());
        map.put("userId", b.getUserId());
        map.put("name", b.getName());
        map.put("symbol", b.getSymbol());
        map.put("timeframe", b.getTimeframe());
        map.put("strategyType", b.getStrategyType());
        map.put("fastEma", b.getFastEma());
        map.put("slowEma", b.getSlowEma());
        map.put("tradeSizePercent", b.getTradeSizePercent());
        map.put("status", b.getStatus());
        map.put("exchangeMode", b.getExchangeMode());
        map.put("strategyParams", b.getStrategyParams());
        map.put("hasOpenPosition", b.isHasOpenPosition());
        map.put("entryPrice", b.getEntryPrice());
        map.put("quantity", b.getQuantity());
        map.put("createdAt", b.getCreatedAt() != null ? b.getCreatedAt().toString() : null);
        map.put("startedAt", b.getStartedAt() != null ? b.getStartedAt().toString() : null);
        map.put("stoppedAt", b.getStoppedAt() != null ? b.getStoppedAt().toString() : null);
        map.put("lastTradeTime", b.getLastTradeTime() != null ? b.getLastTradeTime().toString() : null);
        map.put("isProcessing", b.isProcessing());
        map.put("pnl", totalPnl);
        map.put("totalTrades", totalTrades);
        map.put("winRate", Math.round(winRate * 10) / 10.0);
        return map;
    }
}
