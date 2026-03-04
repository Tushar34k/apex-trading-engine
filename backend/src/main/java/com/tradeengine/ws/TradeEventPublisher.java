package com.tradeengine.ws;

import com.tradeengine.model.TradeOrder;
import com.tradeengine.model.TradePosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeEventPublisher {

    private final SimpMessagingTemplate messaging;

    public void publishOrderFilled(String userId, TradeOrder order) {
        var event = Map.of(
            "type", "ORDER_UPDATE",
            "order", Map.of(
                "id", order.getId(),
                "symbol", order.getSymbol(),
                "side", order.getSide(),
                "status", order.getStatus(),
                "filledQuantity", order.getFilledQuantity(),
                "avgFillPrice", order.getAvgFillPrice(),
                "exchangeOrderId", order.getExchangeOrderId() != null ? order.getExchangeOrderId() : ""
            )
        );
        messaging.convertAndSendToUser(userId, "/topic/orders", event);
        log.info("Published ORDER_UPDATE for user {} - {} {}", userId, order.getSide(), order.getSymbol());
    }

    public void publishPositionOpened(String userId, TradePosition position) {
        var event = Map.of(
            "type", "TRADE_OPENED",
            "trade", Map.of(
                "id", position.getId(),
                "symbol", position.getSymbol(),
                "side", position.getSide(),
                "entryPrice", position.getEntryPrice(),
                "quantity", position.getQuantity(),
                "status", "OPEN",
                "openedAt", position.getOpenedAt().toString(),
                "strategyName", "EMA Crossover",
                "strategyVersion", "v1.0"
            )
        );
        messaging.convertAndSendToUser(userId, "/topic/trades", event);
        messaging.convertAndSendToUser(userId, "/topic/positions", Map.of(
            "type", "POSITION_UPDATE",
            "position", Map.of(
                "id", position.getId(),
                "symbol", position.getSymbol(),
                "side", position.getSide(),
                "size", position.getQuantity(),
                "entryPrice", position.getEntryPrice(),
                "currentPrice", position.getCurrentPrice()
            )
        ));
        log.info("Published TRADE_OPENED for user {}", userId);
    }

    public void publishPositionClosed(String userId, TradePosition position, BigDecimal pnl) {
        var event = Map.of(
            "type", "TRADE_CLOSED",
            "trade", Map.of(
                "id", position.getId(),
                "symbol", position.getSymbol(),
                "side", position.getSide(),
                "entryPrice", position.getEntryPrice(),
                "exitPrice", position.getExitPrice(),
                "quantity", position.getQuantity(),
                "pnl", pnl,
                "status", "CLOSED",
                "openedAt", position.getOpenedAt().toString(),
                "closedAt", position.getClosedAt().toString(),
                "strategyName", "EMA Crossover",
                "strategyVersion", "v1.0"
            )
        );
        messaging.convertAndSendToUser(userId, "/topic/trades", event);
        log.info("Published TRADE_CLOSED for user {} with PnL {}", userId, pnl);
    }

    public void publishPriceUpdate(String symbol, BigDecimal price, double change24h) {
        var event = Map.of(
            "type", "PRICE_UPDATE",
            "symbol", symbol,
            "price", price,
            "change24h", change24h,
            "timestamp", System.currentTimeMillis()
        );
        messaging.convertAndSend("/topic/market/" + symbol, event);
    }
}
