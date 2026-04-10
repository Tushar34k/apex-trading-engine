package com.tradeengine.controller;

import com.tradeengine.exchange.Balance;
import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.model.TradingBot;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.service.ApiKeyService;
import com.tradeengine.service.PositionSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

/**
 * Recovery endpoints used by the Kill Switch Modal to restore system health.
 * POST /api/exchange/sync-balances — re-fetch balances from all connected exchanges
 * POST /api/trading/reconcile     — reconcile internal DB positions with exchange state
 * PATCH /api/bots/{id}/sizing     — adjust tradeSizePercent for a specific bot
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class RecoveryController {

    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ExchangeFactory exchangeFactory;
    private final BotRepository botRepo;
    private final PositionSyncService positionSyncService;

    /**
     * Step 1: Force sync balances from all connected exchanges.
     */
    @PostMapping("/exchange/sync-balances")
    public ResponseEntity<?> syncBalances() {
        UUID userId = getUserId();
        var keys = apiKeyRepo.findByUserId(userId);
        if (keys.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_keys", "message", "No API keys configured"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (UserApiKey key : keys) {
            if (!key.isActive()) continue;
            try {
                String decryptedKey = apiKeyService.decryptApiKey(key);
                String decryptedSecret = apiKeyService.decryptApiSecret(key);
                ExchangeClient client = exchangeFactory.getClient(key.getExchange());
                String baseUrl = client.resolveBaseUrl("TESTNET"); // safe default
                List<Balance> balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);

                BigDecimal usdtBalance = balances.stream()
                    .filter(b -> "USDT".equalsIgnoreCase(b.getAsset()))
                    .map(Balance::getTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                results.add(Map.of(
                    "exchange", key.getExchange(),
                    "status", "synced",
                    "usdtBalance", usdtBalance
                ));
                // Clear any auth error for this exchange on successful sync
                RiskMonitorController.clearApiAuthError(key.getExchange().toUpperCase());
                log.info("[RECOVERY] Balance synced for {} — USDT={}", key.getExchange(), usdtBalance);
            } catch (Exception e) {
                results.add(Map.of(
                    "exchange", key.getExchange(),
                    "status", "failed",
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                ));
                log.error("[RECOVERY] Balance sync failed for {}: {}", key.getExchange(), e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of("status", "completed", "exchanges", results));
    }

    /**
     * Step 2: Reconcile orphaned positions — sync DB state with exchange reality.
     */
    @PostMapping("/trading/reconcile")
    public ResponseEntity<?> reconcilePositions() {
        try {
            positionSyncService.syncPositions();
            log.info("[RECOVERY] Position reconciliation completed");
            return ResponseEntity.ok(Map.of("status", "reconciled", "message", "Positions synchronized with exchange"));
        } catch (Exception e) {
            log.error("[RECOVERY] Position reconciliation failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "status", "failed",
                "error", e.getMessage() != null ? e.getMessage() : "Reconciliation error"
            ));
        }
    }

    /**
     * Adjust tradeSizePercent for a specific bot. Capped to 0.5–5.0% range.
     */
    @PatchMapping("/bots/{id}/sizing")
    public ResponseEntity<?> updateBotSizing(@PathVariable String id, @RequestBody Map<String, Object> body) {
        UUID botId = UUID.fromString(id);
        var bot = botRepo.findById(botId).orElse(null);
        if (bot == null) return ResponseEntity.notFound().build();
        if (!bot.getUserId().equals(getUserId())) return ResponseEntity.status(403).build();

        Object rawSize = body.get("tradeSizePercent");
        if (rawSize == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "tradeSizePercent is required"));
        }

        double newSize = ((Number) rawSize).doubleValue();
        // Enforce safety bounds
        if (newSize < 0.5) newSize = 0.5;
        if (newSize > 5.0) newSize = 5.0;

        bot.setTradeSizePercent(BigDecimal.valueOf(newSize));
        botRepo.save(bot);

        log.info("[RECOVERY] Bot {} tradeSizePercent updated to {}%", botId, newSize);
        return ResponseEntity.ok(Map.of(
            "botId", botId,
            "tradeSizePercent", newSize,
            "status", "updated"
        ));
    }
}
