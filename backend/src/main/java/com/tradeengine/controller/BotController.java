package com.tradeengine.controller;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.StrategyRepository;
import com.tradeengine.service.BotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotService botService;
    private final BotRepository botRepo;
    private final StrategyRepository strategyRepo;

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
            return Map.<String, Object>of(
                "id", b.getId(),
                "userId", b.getUserId(),
                "strategyId", b.getStrategyId(),
                "strategyName", strategy != null ? strategy.getName() : "Unknown",
                "strategyVersion", strategy != null ? strategy.getVersion() : "",
                "apiKeyId", b.getApiKeyId(),
                "symbol", b.getSymbol(),
                "timeframe", b.getTimeframe(),
                "mode", b.getMode(),
                "status", b.getStatus(),
                "createdAt", b.getCreatedAt().toString(),
                "pnl", 0,
                "totalTrades", 0,
                "winRate", 0
            );
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
}
