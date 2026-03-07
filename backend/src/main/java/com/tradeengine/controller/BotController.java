package com.tradeengine.controller;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.service.BotService;
import com.tradeengine.strategy.StrategyFactory;
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
        private String strategyParams; // JSON string
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var bots = botRepo.findByUserId(getUserId());
        var result = bots.stream().map(this::toMap).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateBotRequest req) {
        // Validate strategy type
        if (!StrategyFactory.exists(req.getStrategyType())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                "Unknown strategy: " + req.getStrategyType()));
        }

        // Validate exchange mode
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
        return ResponseEntity.ok(toMap(bot));
    }

    private Map<String, Object> toMap(TradingBot b) {
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
        map.put("pnl", 0);
        map.put("totalTrades", 0);
        map.put("winRate", 0);
        return map;
    }
}
