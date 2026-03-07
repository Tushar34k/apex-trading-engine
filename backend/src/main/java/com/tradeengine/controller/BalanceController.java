package com.tradeengine.controller;

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
            String baseUrl = resolveUrl(mode);

            ExchangeClient client = exchangeFactory.getClient(key.getExchange());
            Map<String, BigDecimal> balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);
            BigDecimal available = balances.getOrDefault("USDT", BigDecimal.ZERO);

            return ResponseEntity.ok(Map.of(
                "asset", "USDT",
                "available", available,
                "locked", BigDecimal.ZERO,
                "total", available
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
            String baseUrl = resolveUrl(mode);

            ExchangeClient client = exchangeFactory.getClient(key.getExchange());
            Map<String, BigDecimal> balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);
            var result = balances.entrySet().stream().map(e -> Map.of(
                "asset", e.getKey(),
                "available", e.getValue(),
                "locked", BigDecimal.ZERO,
                "total", e.getValue()
            )).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch balances via {}: {}", key.getExchange(), e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    private String resolveUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode) && liveTradingEnabled) {
            return "https://api.binance.com";
        }
        return "https://testnet.binance.vision";
    }
}
