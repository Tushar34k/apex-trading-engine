package com.tradeengine.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_bots")
@Data
public class TradingBot {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "strategy_id", nullable = false)
    private UUID strategyId;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe = "1h";

    @Column(nullable = false)
    private String mode = "LIVE";

    @Column(nullable = false)
    private String status = "STOPPED";

    @Column(name = "risk_percent", nullable = false)
    private BigDecimal riskPercent = new BigDecimal("1.5");

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;
}
