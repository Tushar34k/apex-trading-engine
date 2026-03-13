package com.tradeengine.controller;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.SupportedExchange;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.service.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
@Slf4j
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepo;
    private final ExchangeFactory exchangeFactory;

    @Data
    public static class AddKeyRequest {
        @NotBlank private String exchange;
        @NotBlank private String label;
        @NotBlank private String apiKey;
        @NotBlank private String apiSecret;
        private String permissions = "TRADE_ONLY";
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var keys = apiKeyService.listForUser(getUserId());
        var result = keys.stream().map(k -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", k.getId());
            map.put("exchange", k.getExchange());
            map.put("label", k.getLabel());
            map.put("permissions", k.getPermissions());
            map.put("isActive", k.isActive());
            map.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : null);
            map.put("lastUsedAt", k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : null);
            map.put("maskedKey", "****");
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody AddKeyRequest req) {
        // Validate exchange is supported
        try {
            SupportedExchange.validate(req.getExchange());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }

        var key = apiKeyService.addKey(getUserId(), req.getExchange().toUpperCase(), req.getLabel(),
            req.getApiKey(), req.getApiSecret(), req.getPermissions());
        log.info("API key added: exchange={} label={} userId={}", req.getExchange(), req.getLabel(), getUserId());
        return ResponseEntity.ok(Map.of("id", key.getId(), "label", key.getLabel(), "exchange", key.getExchange()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        apiKeyService.deleteKey(UUID.fromString(id), getUserId());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable String id) {
        var key = apiKeyRepo.findById(UUID.fromString(id)).orElse(null);
        if (key == null || !key.getUserId().equals(getUserId())) {
            return ResponseEntity.status(404).body(Map.of("valid", false, "message", "Key not found"));
        }
        try {
            String decryptedKey = apiKeyService.decryptApiKey(key);
            String decryptedSecret = apiKeyService.decryptApiSecret(key);

            ExchangeClient client = exchangeFactory.getClient(key.getExchange());
            String baseUrl = client.resolveBaseUrl("TESTNET");
            log.info("[API_KEY_TEST] Testing {} key={} against {}", key.getExchange(), id, baseUrl);
            boolean valid = client.testConnection(decryptedKey, decryptedSecret, baseUrl);
            if (valid) {
                return ResponseEntity.ok(Map.of("valid", true,
                    "message", "Connected to " + key.getExchange() + " successfully."));
            } else {
                return ResponseEntity.ok(Map.of("valid", false,
                    "message", "Connection test failed for " + key.getExchange()));
            }
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.ok(Map.of("valid", false,
                "message", key.getExchange() + " integration is not yet implemented"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("valid", false, "message", "Failed: " + e.getMessage()));
        }
    }

    @GetMapping("/supported-exchanges")
    public ResponseEntity<?> supportedExchanges() {
        return ResponseEntity.ok(List.of("BINANCE", "DELTA", "BYBIT"));
    }
}
