package com.tradeengine.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Data
public class TradeOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bot_id")
    private UUID botId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "exchange_order_id")
    private String exchangeOrderId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side; // BUY, SELL

    @Column(nullable = false)
    private String type = "MARKET";

    @Column(nullable = false)
    private BigDecimal quantity;

    private BigDecimal price;

    @Column(name = "filled_quantity")
    private BigDecimal filledQuantity = BigDecimal.ZERO;

    @Column(name = "avg_fill_price")
    private BigDecimal avgFillPrice;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, FILLED, CANCELLED, FAILED

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "filled_at")
    private Instant filledAt;
}
