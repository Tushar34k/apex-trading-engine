package com.tradeengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends typed trade notifications to frontend via WebSocket.
 * Event types: BOT_BUY, BOT_SELL, BOT_SL, BOT_TP, BOT_TRAILING_SL
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SimpMessagingTemplate messaging;

    public void notifyBuy(String userId, String botName, String symbol, BigDecimal price, BigDecimal qty) {
        send(userId, "BOT_BUY", botName, symbol, "Buy order executed",
            Map.of("price", price, "quantity", qty));
    }

    public void notifySell(String userId, String botName, String symbol, BigDecimal price, BigDecimal pnl) {
        send(userId, "BOT_SELL", botName, symbol, "Sell order executed",
            Map.of("price", price, "pnl", pnl));
    }

    public void notifyStopLoss(String userId, String botName, String symbol, BigDecimal price, BigDecimal pnl) {
        send(userId, "BOT_SL", botName, symbol, "Stop-loss triggered",
            Map.of("price", price, "pnl", pnl));
    }

    public void notifyTakeProfit(String userId, String botName, String symbol, BigDecimal price, BigDecimal pnl) {
        send(userId, "BOT_TP", botName, symbol, "Take-profit triggered",
            Map.of("price", price, "pnl", pnl));
    }

    public void notifyTrailingStop(String userId, String botName, String symbol, BigDecimal price, BigDecimal pnl) {
        send(userId, "BOT_TRAILING_SL", botName, symbol, "Trailing stop triggered",
            Map.of("price", price, "pnl", pnl));
    }

    public void notifyRiskBlocked(String userId, String botName, String symbol, String reason) {
        send(userId, "RISK_BLOCKED", botName, symbol, reason, Map.of());
    }

    private void send(String userId, String type, String botName, String symbol,
                      String message, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("botName", botName);
        event.put("symbol", symbol);
        event.put("message", message);
        event.put("timestamp", Instant.now().toString());
        event.putAll(data);

        messaging.convertAndSendToUser(userId, "/topic/notifications", event);
        messaging.convertAndSend("/topic/notifications", event);
        log.info("[NOTIFICATION] {} - {} {} - {}", type, botName, symbol, message);
    }
}
