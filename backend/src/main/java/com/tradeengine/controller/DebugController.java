package com.tradeengine.controller;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.ExchangeSymbolRegistry;
import com.tradeengine.exchange.SymbolMapperService;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

/**
 * Diagnostic endpoint for exchange connectivity testing.
 * Tests API key validity, base URL resolution, symbol mapping, and market data.
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ExchangeFactory exchangeFactory;
    private final ExchangeSymbolRegistry symbolRegistry;
    private final SymbolMapperService symbolMapper;
    private final TradeExecutionQueue executionQueue;

    /**
     * Full exchange connectivity test.
     *
     * Tests:
     * 1. API key decryption
     * 2. Base URL resolution
     * 3. Authenticated connectivity (testConnection)
     * 4. Symbol mapping
     * 5. Market data fetch (price + candles)
     * 6. Balance fetch
     * 7. Position fetch
     *
     * @param keyId  API key UUID
     * @param mode   TESTNET or LIVE (default: TESTNET)
     * @param symbol Universal symbol to test (default: BTC/USDT)
     */
    @PostMapping("/exchange-test")
    public ResponseEntity<?> testExchange(
            @RequestParam String keyId,
            @RequestParam(defaultValue = "TESTNET") String mode,
            @RequestParam(defaultValue = "BTC/USDT") String symbol) {

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", System.currentTimeMillis());
        report.put("mode", mode);
        report.put("universalSymbol", symbol);

        // Step 1: Load and decrypt API key
        UserApiKey apiKey;
        try {
            apiKey = apiKeyRepo.findById(UUID.fromString(keyId)).orElse(null);
            if (apiKey == null || !apiKey.getUserId().equals(getUserId())) {
                return ResponseEntity.status(404).body(Map.of("error", "API key not found"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid key ID: " + e.getMessage()));
        }

        String exchange = apiKey.getExchange().toUpperCase();
        report.put("exchange", exchange);

        String decryptedKey, decryptedSecret;
        try {
            decryptedKey = apiKeyService.decryptApiKey(apiKey);
            decryptedSecret = apiKeyService.decryptApiSecret(apiKey);
            report.put("apiKeyDecryption", Map.of(
                "status", "OK",
                "keyLength", decryptedKey.length(),
                "keyPrefix", decryptedKey.substring(0, Math.min(4, decryptedKey.length())),
                "keySuffix", decryptedKey.substring(Math.max(0, decryptedKey.length() - 4)),
                "secretLength", decryptedSecret.length()
            ));
        } catch (Exception e) {
            report.put("apiKeyDecryption", Map.of("status", "FAILED", "error", e.getMessage()));
            return ResponseEntity.ok(report);
        }

        // Step 2: Resolve exchange client and base URL
        ExchangeClient client;
        String baseUrl;
        try {
            client = exchangeFactory.getClient(exchange);
            baseUrl = client.resolveBaseUrl(mode);
            report.put("baseUrl", Map.of("status", "OK", "url", baseUrl));
        } catch (Exception e) {
            report.put("baseUrl", Map.of("status", "FAILED", "error", e.getMessage()));
            return ResponseEntity.ok(report);
        }

        // Step 3: Test connectivity
        try {
            boolean connected = client.testConnection(decryptedKey, decryptedSecret, baseUrl);
            report.put("connectivity", Map.of(
                "status", connected ? "OK" : "FAILED",
                "authenticated", connected
            ));
        } catch (Exception e) {
            report.put("connectivity", Map.of("status", "FAILED", "error", e.getMessage()));
        }

        // Step 4: Symbol mapping
        String exchangeSymbol;
        try {
            exchangeSymbol = symbolMapper.resolveSymbol(exchange, symbol);
            report.put("symbolMapping", Map.of(
                "status", "OK",
                "universal", symbol,
                "native", exchangeSymbol
            ));
        } catch (Exception e) {
            report.put("symbolMapping", Map.of("status", "FAILED", "error", e.getMessage()));
            exchangeSymbol = symbol.replace("/", ""); // fallback
        }

        // Step 5: Market data
        try {
            BigDecimal price = client.getPrice(exchangeSymbol, baseUrl);
            report.put("priceCheck", Map.of(
                "status", "OK",
                "symbol", exchangeSymbol,
                "price", price.toPlainString()
            ));
        } catch (Exception e) {
            report.put("priceCheck", Map.of("status", "FAILED", "error", e.getMessage()));
        }

        try {
            var candles = client.getCandles(exchangeSymbol, "1m", 5, baseUrl);
            report.put("candleCheck", Map.of(
                "status", "OK",
                "count", candles.size(),
                "latestClose", candles.isEmpty() ? "N/A" : String.valueOf(candles.get(candles.size() - 1)[4])
            ));
        } catch (Exception e) {
            report.put("candleCheck", Map.of("status", "FAILED", "error", e.getMessage()));
        }

        // Step 6: Balance
        try {
            var balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);
            report.put("balanceCheck", Map.of(
                "status", "OK",
                "assetCount", balances.size(),
                "assets", balances.stream()
                    .map(b -> b.getAsset() + "=" + b.getFree().toPlainString())
                    .limit(5).toList()
            ));
        } catch (Exception e) {
            report.put("balanceCheck", Map.of("status", "FAILED", "error", e.getMessage()));
        }

        // Step 7: Positions
        try {
            var positions = client.getOpenPositions(decryptedKey, decryptedSecret, exchangeSymbol, baseUrl);
            report.put("positionCheck", Map.of(
                "status", "OK",
                "openPositions", positions.size()
            ));
        } catch (Exception e) {
            report.put("positionCheck", Map.of("status", "FAILED", "error", e.getMessage()));
        }

        // Step 8: Symbol registry
        try {
            var info = symbolRegistry.getOrFetch(exchange, exchangeSymbol, baseUrl);
            if (info != null) {
                report.put("symbolRegistry", Map.of(
                    "status", "OK",
                    "stepSize", String.valueOf(info.getStepSize()),
                    "minQty", String.valueOf(info.getMinQty()),
                    "tickSize", String.valueOf(info.getTickSize())
                ));
            } else {
                report.put("symbolRegistry", Map.of("status", "WARN", "message", "No info cached"));
            }
        } catch (Exception e) {
            report.put("symbolRegistry", Map.of("status", "FAILED", "error", e.getMessage()));
        }

        // Step 9: Queue health
        report.put("executionQueue", Map.of(
            "queueSize", executionQueue.getQueueSize(),
            "capacity", executionQueue.getQueueCapacity(),
            "usagePercent", executionQueue.getQueueUsagePercent(),
            "totalSubmitted", executionQueue.getTotalSubmitted(),
            "totalExecuted", executionQueue.getTotalExecuted(),
            "totalFailed", executionQueue.getTotalFailed()
        ));

        log.info("[DEBUG] Exchange test complete: exchange={} mode={} symbol={}", exchange, mode, symbol);
        return ResponseEntity.ok(report);
    }

    /**
     * Quick connectivity check — no symbol or market data tests.
     */
    @PostMapping("/exchange-test/quick")
    public ResponseEntity<?> quickTest(@RequestParam String keyId,
                                        @RequestParam(defaultValue = "TESTNET") String mode) {
        UserApiKey apiKey = apiKeyRepo.findById(UUID.fromString(keyId)).orElse(null);
        if (apiKey == null || !apiKey.getUserId().equals(getUserId())) {
            return ResponseEntity.status(404).body(Map.of("valid", false, "message", "Key not found"));
        }

        try {
            String decryptedKey = apiKeyService.decryptApiKey(apiKey);
            String decryptedSecret = apiKeyService.decryptApiSecret(apiKey);
            ExchangeClient client = exchangeFactory.getClient(apiKey.getExchange());
            String baseUrl = client.resolveBaseUrl(mode);

            boolean valid = client.testConnection(decryptedKey, decryptedSecret, baseUrl);
            return ResponseEntity.ok(Map.of(
                "valid", valid,
                "exchange", apiKey.getExchange(),
                "baseUrl", baseUrl,
                "mode", mode
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("valid", false, "error", e.getMessage()));
        }
    }
}
