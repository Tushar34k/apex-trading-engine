package com.tradeengine.controller;

import com.tradeengine.exchange.Balance;
import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
@Slf4j
public class BalanceController {

    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ExchangeFactory exchangeFactory;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    @GetMapping("/balance")
    public ResponseEntity<?> getBalance(@RequestParam(defaultValue = "TESTNET") String mode) {
        UUID userId = getUserId();
        var keys = apiKeyRepo.findByUserId(userId);
        if (keys.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "asset", "USDT", "available", 0, "locked", 0, "total", 0
            ));
        }

        UserApiKey key = keys.stream().filter(UserApiKey::isActive).findFirst().orElse(keys.get(0));
        try {
            String decryptedKey = apiKeyService.decryptApiKey(key);
            String decryptedSecret = apiKeyService.decryptApiSecret(key);

            ExchangeClient client = exchangeFactory.getClient(key.getExchange());
            String baseUrl = resolveUrl(client, mode);
            List<Balance> balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);

            BigDecimal available = BigDecimal.ZERO;
            BigDecimal locked = BigDecimal.ZERO;
            for (Balance b : balances) {
                if ("USDT".equals(b.getAsset())) {
                    available = b.getFree();
                    locked = b.getLocked() != null ? b.getLocked() : BigDecimal.ZERO;
                    break;
                }
            }

            return ResponseEntity.ok(Map.of(
                "asset", "USDT",
                "available", available,
                "locked", locked,
                "total", available.add(locked)
            ));
        } catch (Exception e) {
            log.error("Failed to fetch balance via {}: {}", key.getExchange(), e.getMessage());
            return ResponseEntity.ok(Map.of(
                "asset", "USDT", "available", 0, "locked", 0, "total", 0
            ));
        }
    }

    @GetMapping("/balances")
    public ResponseEntity<?> getAllBalances(@RequestParam(defaultValue = "TESTNET") String mode) {
        UUID userId = getUserId();
        var keys = apiKeyRepo.findByUserId(userId);
        if (keys.isEmpty()) return ResponseEntity.ok(List.of());

        UserApiKey key = keys.stream().filter(UserApiKey::isActive).findFirst().orElse(keys.get(0));
        try {
            String decryptedKey = apiKeyService.decryptApiKey(key);
            String decryptedSecret = apiKeyService.decryptApiSecret(key);

            ExchangeClient client = exchangeFactory.getClient(key.getExchange());
            String baseUrl = resolveUrl(client, mode);
            List<Balance> balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);

            var result = balances.stream().map(b -> Map.of(
                "asset", (Object) b.getAsset(),
                "available", b.getFree(),
                "locked", b.getLocked() != null ? b.getLocked() : BigDecimal.ZERO,
                "total", b.getTotal()
            )).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch balances via {}: {}", key.getExchange(), e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    private String resolveUrl(ExchangeClient client, String mode) {
        if ("LIVE".equalsIgnoreCase(mode) && !liveTradingEnabled) {
            log.warn("Live trading disabled. Forcing TESTNET for {}", client.getExchangeName());
            return client.resolveBaseUrl("TESTNET");
        }
        return client.resolveBaseUrl(mode);
    }
}
