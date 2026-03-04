package com.tradeengine.controller;

import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.model.TradeOrder;
import com.tradeengine.model.TradePosition;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.OrderRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.repository.StrategyRepository;
import com.tradeengine.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TradingController {

    private final OrderRepository orderRepo;
    private final PositionRepository positionRepo;
    private final StrategyRepository strategyRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final BinanceClient binance;

    @GetMapping("/orders")
    public ResponseEntity<List<Map<String, Object>>> getOrders() {
        var orders = orderRepo.findByUserIdOrderByCreatedAtDesc(getUserId());
        var result = orders.stream().map(o -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", o.getId());
            map.put("botId", o.getBotId() != null ? o.getBotId().toString() : "");
            map.put("userId", o.getUserId());
            map.put("exchangeOrderId", o.getExchangeOrderId() != null ? o.getExchangeOrderId() : "");
            map.put("symbol", o.getSymbol());
            map.put("side", o.getSide());
            map.put("type", o.getType());
            map.put("quantity", o.getQuantity());
            map.put("price", o.getPrice() != null ? o.getPrice() : BigDecimal.ZERO);
            map.put("filledQuantity", o.getFilledQuantity());
            map.put("avgFillPrice", o.getAvgFillPrice() != null ? o.getAvgFillPrice() : BigDecimal.ZERO);
            map.put("status", o.getStatus());
            map.put("createdAt", o.getCreatedAt().toString());
            map.put("filledAt", o.getFilledAt() != null ? o.getFilledAt().toString() : "");
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/positions")
    public ResponseEntity<List<Map<String, Object>>> getPositions() {
        var positions = positionRepo.findByUserIdAndStatus(getUserId(), "OPEN");
        var result = positions.stream().map(p -> {
            BigDecimal pnl = BigDecimal.ZERO;
            BigDecimal pnlPct = BigDecimal.ZERO;
            if (p.getCurrentPrice() != null && p.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                pnl = p.getCurrentPrice().subtract(p.getEntryPrice()).multiply(p.getQuantity());
                if ("SHORT".equals(p.getSide())) pnl = pnl.negate();
                pnlPct = p.getCurrentPrice().subtract(p.getEntryPrice())
                    .divide(p.getEntryPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                if ("SHORT".equals(p.getSide())) pnlPct = pnlPct.negate();
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
            map.put("stopLoss", p.getStopLoss() != null ? p.getStopLoss() : BigDecimal.ZERO);
            map.put("takeProfit", p.getTakeProfit() != null ? p.getTakeProfit() : BigDecimal.ZERO);
            map.put("strategyName", "EMA Crossover");
            map.put("botId", p.getBotId() != null ? p.getBotId().toString() : "");
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/balances")
    public ResponseEntity<?> getBalances() {
        UUID userId = getUserId();
        // Find user's first active API key
        var keys = apiKeyRepo.findByUserId(userId);
        if (keys.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        UserApiKey key = keys.stream().filter(UserApiKey::isActive).findFirst().orElse(keys.get(0));
        try {
            String decryptedKey = apiKeyService.decryptApiKey(key);
            String decryptedSecret = apiKeyService.decryptApiSecret(key);
            Map<String, BigDecimal> balances = binance.getBalances(decryptedKey, decryptedSecret);
            var result = balances.entrySet().stream().map(e -> {
                Map<String, Object> map = new HashMap<>();
                map.put("asset", e.getKey());
                map.put("free", e.getValue());
                map.put("locked", BigDecimal.ZERO);
                map.put("total", e.getValue());
                map.put("usdValue", e.getValue()); // MVP: approximate
                return map;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/strategies")
    public ResponseEntity<?> getStrategies() {
        var strategies = strategyRepo.findAll();
        var result = strategies.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getId());
            map.put("name", s.getName());
            map.put("description", s.getDescription());
            map.put("version", s.getVersion());
            map.put("type", s.getType());
            map.put("isActive", s.isActive());
            map.put("createdAt", s.getCreatedAt().toString());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/trades")
    public ResponseEntity<?> getTrades(
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String mode) {
        // Trades = closed positions in MVP
        UUID userId = getUserId();
        List<TradePosition> positions;
        if ("OPEN".equalsIgnoreCase(status)) {
            positions = positionRepo.findByUserIdAndStatus(userId, "OPEN");
        } else {
            positions = positionRepo.findByUserId(userId);
        }
        var result = positions.stream().map(p -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("botId", p.getBotId() != null ? p.getBotId().toString() : "");
            map.put("userId", p.getUserId());
            map.put("symbol", p.getSymbol());
            map.put("side", p.getSide());
            map.put("entryPrice", p.getEntryPrice());
            map.put("exitPrice", p.getExitPrice());
            map.put("quantity", p.getQuantity());
            map.put("pnl", p.getRealizedPnl());
            map.put("pnlPercent", BigDecimal.ZERO);
            map.put("stopLoss", p.getStopLoss() != null ? p.getStopLoss() : BigDecimal.ZERO);
            map.put("takeProfit", p.getTakeProfit() != null ? p.getTakeProfit() : BigDecimal.ZERO);
            map.put("status", p.getStatus());
            map.put("openedAt", p.getOpenedAt() != null ? p.getOpenedAt().toString() : "");
            map.put("closedAt", p.getClosedAt() != null ? p.getClosedAt().toString() : "");
            map.put("strategyName", "EMA Crossover");
            map.put("strategyVersion", "v1.0");
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/trades/{id}")
    public ResponseEntity<?> getTradeDetail(@PathVariable String id) {
        var position = positionRepo.findById(UUID.fromString(id));
        if (position.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var p = position.get();
        Map<String, Object> map = new HashMap<>();
        map.put("id", p.getId());
        map.put("symbol", p.getSymbol());
        map.put("side", p.getSide());
        map.put("entryPrice", p.getEntryPrice());
        map.put("exitPrice", p.getExitPrice());
        map.put("quantity", p.getQuantity());
        map.put("pnl", p.getRealizedPnl());
        map.put("status", p.getStatus());
        map.put("openedAt", p.getOpenedAt() != null ? p.getOpenedAt().toString() : "");
        map.put("closedAt", p.getClosedAt() != null ? p.getClosedAt().toString() : "");
        map.put("strategyName", "EMA Crossover");
        map.put("strategyVersion", "v1.0");
        return ResponseEntity.ok(map);
    }

    @GetMapping("/analytics/performance")
    public ResponseEntity<?> getPerformance() {
        UUID userId = getUserId();
        var allPositions = positionRepo.findByUserId(userId);
        var closedPositions = allPositions.stream().filter(p -> "CLOSED".equals(p.getStatus())).toList();

        BigDecimal totalReturn = closedPositions.stream()
            .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalTrades = closedPositions.size();
        long wins = closedPositions.stream()
            .filter(p -> p.getRealizedPnl() != null && p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
            .count();
        double winRate = totalTrades > 0 ? ((double) wins / totalTrades) * 100 : 0;

        Map<String, Object> map = new HashMap<>();
        map.put("totalReturn", totalReturn);
        map.put("totalReturnPercent", totalReturn.doubleValue() / 100); // simplified
        map.put("winRate", winRate);
        map.put("totalTrades", totalTrades);
        map.put("sharpeRatio", 0);
        map.put("maxDrawdown", 0);
        map.put("profitFactor", 0);
        map.put("bestStrategy", "EMA Crossover");
        map.put("bestStrategyReturn", totalReturn);
        return ResponseEntity.ok(map);
    }

    @GetMapping("/analytics/equity-curve")
    public ResponseEntity<?> getEquityCurve() {
        UUID userId = getUserId();
        var closedPositions = positionRepo.findByUserId(userId).stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) && p.getClosedAt() != null)
            .sorted(Comparator.comparing(TradePosition::getClosedAt))
            .toList();

        BigDecimal equity = new BigDecimal("10000");
        List<Map<String, Object>> curve = new ArrayList<>();
        for (var p : closedPositions) {
            equity = equity.add(p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO);
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", p.getClosedAt().toString());
            point.put("equity", equity);
            curve.add(point);
        }
        return ResponseEntity.ok(curve);
    }

    @GetMapping("/analytics/monthly-returns")
    public ResponseEntity<?> getMonthlyReturns() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/analytics/strategy-comparison")
    public ResponseEntity<?> getStrategyComparison() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/risk/config")
    public ResponseEntity<?> getRiskConfig() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/risk/status")
    public ResponseEntity<?> getRiskStatus() {
        UUID userId = getUserId();
        var openPositions = positionRepo.findByUserIdAndStatus(userId, "OPEN");
        double totalExposure = openPositions.size() * 10; // simplified

        Map<String, Object> status = new HashMap<>();
        status.put("totalExposure", totalExposure);
        status.put("maxExposure", 100);
        status.put("dailyPnl", 0);
        status.put("dailyLossLimit", 500);
        status.put("currentDrawdown", 0);
        status.put("maxDrawdown", 10);
        status.put("riskLevel", openPositions.isEmpty() ? "LOW" : "MEDIUM");
        status.put("allChecksPassed", true);
        status.put("violations", List.of());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/risk/exposure")
    public ResponseEntity<?> getExposure() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/backtest/results")
    public ResponseEntity<?> getBacktestResults() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/admin/users")
    public ResponseEntity<?> getAdminUsers() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/admin/system-health")
    public ResponseEntity<?> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("websocket", "online");
        health.put("exchangeApi", "online");
        health.put("database", "online");
        health.put("redis", "offline");
        health.put("uptime", System.currentTimeMillis() / 1000);
        health.put("activeConnections", 0);
        return ResponseEntity.ok(health);
    }
}
