package com.tradeengine.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "positions")
@Data
public class TradePosition {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bot_id")
    private UUID botId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side; // LONG, SHORT

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "entry_price", nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "current_price")
    private BigDecimal currentPrice;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Column(name = "take_profit")
    private BigDecimal takeProfit;

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN, CLOSED

    @Column
    private String exchange; // BINANCE, DELTA, BYBIT

    @Column(name = "opened_at")
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "exit_price")
    private BigDecimal exitPrice;

    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;
}
