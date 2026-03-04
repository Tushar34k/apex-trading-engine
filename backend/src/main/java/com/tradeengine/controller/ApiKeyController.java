package com.tradeengine.controller;

import com.tradeengine.model.UserApiKey;
import com.tradeengine.service.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService service;

    @Data
    public static class AddKeyRequest {
        @NotBlank private String exchange;
        @NotBlank private String label;
        @NotBlank private String apiKey;
        @NotBlank private String apiSecret;
        private String permissions = "TRADE_ONLY";
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        var keys = service.listForUser(getUserId());
        var result = keys.stream().map(k -> Map.<String, Object>of(
            "id", k.getId(),
            "exchange", k.getExchange(),
            "label", k.getLabel(),
            "permissions", k.getPermissions(),
            "isActive", k.isActive(),
            "createdAt", k.getCreatedAt().toString(),
            "lastUsedAt", k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : "",
            "maskedKey", "••••" + k.getApiKeyEncrypted().substring(Math.max(0, k.getApiKeyEncrypted().length() - 4))
        )).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody AddKeyRequest req) {
        var key = service.addKey(getUserId(), req.getExchange(), req.getLabel(),
            req.getApiKey(), req.getApiSecret(), req.getPermissions());
        return ResponseEntity.ok(Map.of("id", key.getId(), "label", key.getLabel()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        service.deleteKey(java.util.UUID.fromString(id), getUserId());
        return ResponseEntity.ok().build();
    }
}
