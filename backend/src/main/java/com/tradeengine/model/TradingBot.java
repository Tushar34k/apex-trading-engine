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

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe = "1m";

    @Column(name = "strategy_type", nullable = false)
    private String strategyType = "EMA_CROSS";

    @Column(name = "fast_ema", nullable = false)
    private int fastEma = 9;

    @Column(name = "slow_ema", nullable = false)
    private int slowEma = 21;

    @Column(name = "trade_size_percent", nullable = false)
    private BigDecimal tradeSizePercent = new BigDecimal("10");

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(nullable = false)
    private String status = "STOPPED";

    @Column(name = "has_open_position")
    private boolean hasOpenPosition = false;

    @Column(name = "entry_price")
    private BigDecimal entryPrice;

    @Column
    private BigDecimal quantity;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "last_trade_time")
    private Instant lastTradeTime;

    @Column(name = "is_processing")
    private boolean isProcessing = false;
}
