package com.tradeengine.controller;

import com.tradeengine.model.TradeOrder;
import com.tradeengine.model.TradePosition;
import com.tradeengine.repository.OrderRepository;
import com.tradeengine.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static com.tradeengine.controller.UserController.getUserId;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TradingController {

    private final OrderRepository orderRepo;
    private final PositionRepository positionRepo;

    @GetMapping("/orders")
    public ResponseEntity<List<Map<String, Object>>> getOrders() {
        var orders = orderRepo.findByUserIdOrderByCreatedAtDesc(getUserId());
        var result = orders.stream().map(o -> Map.<String, Object>ofEntries(
            Map.entry("id", o.getId()),
            Map.entry("botId", o.getBotId() != null ? o.getBotId().toString() : ""),
            Map.entry("userId", o.getUserId()),
            Map.entry("exchangeOrderId", o.getExchangeOrderId() != null ? o.getExchangeOrderId() : ""),
            Map.entry("symbol", o.getSymbol()),
            Map.entry("side", o.getSide()),
            Map.entry("type", o.getType()),
            Map.entry("quantity", o.getQuantity()),
            Map.entry("price", o.getPrice() != null ? o.getPrice() : BigDecimal.ZERO),
            Map.entry("filledQuantity", o.getFilledQuantity()),
            Map.entry("avgFillPrice", o.getAvgFillPrice() != null ? o.getAvgFillPrice() : BigDecimal.ZERO),
            Map.entry("status", o.getStatus()),
            Map.entry("createdAt", o.getCreatedAt().toString()),
            Map.entry("filledAt", o.getFilledAt() != null ? o.getFilledAt().toString() : "")
        )).toList();
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
            return Map.<String, Object>ofEntries(
                Map.entry("id", p.getId()),
                Map.entry("symbol", p.getSymbol()),
                Map.entry("side", p.getSide()),
                Map.entry("size", p.getQuantity()),
                Map.entry("entryPrice", p.getEntryPrice()),
                Map.entry("currentPrice", p.getCurrentPrice() != null ? p.getCurrentPrice() : p.getEntryPrice()),
                Map.entry("pnl", pnl),
                Map.entry("pnlPercent", pnlPct),
                Map.entry("stopLoss", p.getStopLoss() != null ? p.getStopLoss() : BigDecimal.ZERO),
                Map.entry("takeProfit", p.getTakeProfit() != null ? p.getTakeProfit() : BigDecimal.ZERO),
                Map.entry("strategyName", "EMA Crossover"),
                Map.entry("botId", p.getBotId() != null ? p.getBotId().toString() : "")
            );
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/balances")
    public ResponseEntity<?> getBalances() {
        // Delegate to exchange client in production; MVP returns from exchange
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/strategies")
    public ResponseEntity<?> getStrategies() {
        // Return from DB
        return ResponseEntity.ok(List.of());
    }
}
