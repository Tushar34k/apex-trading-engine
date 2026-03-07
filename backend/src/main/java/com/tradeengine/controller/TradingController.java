package com.tradeengine.controller;

import com.tradeengine.exchange.Balance;
import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.model.TradePosition;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.OrderRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class TradingController {

    private final OrderRepository orderRepo;
    private final PositionRepository positionRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ExchangeFactory exchangeFactory;

    @GetMapping("/orders")
    public ResponseEntity<?> getOrders(@RequestParam(required = false) String botId) {
        var orders = orderRepo.findByUserIdOrderByCreatedAtDesc(getUserId());
        var result = orders.stream().map(o -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", o.getId());
            map.put("botId", o.getBotId() != null ? o.getBotId().toString() : "");
            map.put("symbol", o.getSymbol());
            map.put("side", o.getSide());
            map.put("type", o.getType());
            map.put("quantity", o.getQuantity());
            map.put("price", o.getPrice());
            map.put("filledQuantity", o.getFilledQuantity());
            map.put("avgFillPrice", o.getAvgFillPrice());
            map.put("status", o.getStatus());
            map.put("exchangeOrderId", o.getExchangeOrderId());
            map.put("createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : null);
            map.put("filledAt", o.getFilledAt() != null ? o.getFilledAt().toString() : null);
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/positions")
    public ResponseEntity<?> getPositions() {
        var positions = positionRepo.findByUserIdAndStatus(getUserId(), "OPEN");
        var result = positions.stream().map(p -> {
            BigDecimal pnl = BigDecimal.ZERO;
            BigDecimal pnlPct = BigDecimal.ZERO;
            if (p.getCurrentPrice() != null && p.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                pnl = p.getCurrentPrice().subtract(p.getEntryPrice()).multiply(p.getQuantity());
                pnlPct = p.getCurrentPrice().subtract(p.getEntryPrice())
                    .divide(p.getEntryPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            }
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("symbol", p.getSymbol());
            map.put("side", p.getSide());
            map.put("size", p.getQuantity());
            map.put("entryPrice", p.getEntryPrice());
            map.put("currentPrice", p.getCurrentPrice() != null ? p.getCurrentPrice() : p.getEntryPrice());
            map.put("pnl", pnl);
            map.put("pnlPercent", pnlPct);
            map.put("botId", p.getBotId() != null ? p.getBotId().toString() : "");
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/trades")
    public ResponseEntity<?> getTrades(@RequestParam(required = false) String botId) {
        UUID userId = getUserId();
        List<TradePosition> positions = positionRepo.findByUserId(userId);
        var result = positions.stream().map(p -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("botId", p.getBotId() != null ? p.getBotId().toString() : "");
            map.put("symbol", p.getSymbol());
            map.put("side", p.getSide().equals("LONG") ? "BUY" : "SELL");
            map.put("entryPrice", p.getEntryPrice());
            map.put("exitPrice", p.getExitPrice());
            map.put("quantity", p.getQuantity());
            map.put("pnl", p.getRealizedPnl());
            map.put("openedAt", p.getOpenedAt() != null ? p.getOpenedAt().toString() : "");
            map.put("closedAt", p.getClosedAt() != null ? p.getClosedAt().toString() : null);
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/balances")
    public ResponseEntity<?> getBalances(@RequestParam(defaultValue = "TESTNET") String mode) {
        UUID userId = getUserId();
        var keys = apiKeyRepo.findByUserId(userId);
        if (keys.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        UserApiKey key = keys.stream().filter(UserApiKey::isActive).findFirst().orElse(keys.get(0));
        try {
            String decryptedKey = apiKeyService.decryptApiKey(key);
            String decryptedSecret = apiKeyService.decryptApiSecret(key);

            ExchangeClient client = exchangeFactory.getClient(key.getExchange());
            String baseUrl = client.resolveBaseUrl(mode);
            List<Balance> balances = client.getBalances(decryptedKey, decryptedSecret, baseUrl);

            log.debug("Fetched {} balances from {} for user {}", balances.size(), key.getExchange(), userId);

            var result = balances.stream().map(b -> {
                Map<String, Object> map = new HashMap<>();
                map.put("asset", b.getAsset());
                map.put("free", b.getFree());
                map.put("locked", b.getLocked());
                map.put("total", b.getTotal());
                map.put("usdValue", b.getTotal());
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to fetch balances via {}: {}", key.getExchange(), e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }
}
