package com.tradeengine.execution;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.OrderResponse;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized trade execution queue with per-bot duplicate protection,
 * request-level dedup, kill switch, circuit breaker, live trading safety,
 * and capacity monitoring. Exchange-agnostic.
 */
@Service
@Slf4j
public class TradeExecutionQueue {

    private static final int MAX_RETRIES = 3;
    private static final long RATE_LIMIT_DELAY_MS = 100;
    private static final int QUEUE_CAPACITY = 1000;
    private static final double QUEUE_WARNING_THRESHOLD = 0.9;

    private final ExchangeFactory exchangeFactory;
    private final KillSwitchService killSwitch;
    private final CircuitBreakerService circuitBreaker;
    private final BlockingQueue<TradeRequest> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Per-bot pending trade protection
    private final ConcurrentHashMap<UUID, Boolean> pendingTrades = new ConcurrentHashMap<>();

    // Request-level duplicate protection
    private final Set<UUID> processedRequests = ConcurrentHashMap.newKeySet();

    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalExecuted = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    private final Thread workerThread;

    public TradeExecutionQueue(ExchangeFactory exchangeFactory, KillSwitchService killSwitch, CircuitBreakerService circuitBreaker) {
        this.exchangeFactory = exchangeFactory;
        this.killSwitch = killSwitch;
        this.circuitBreaker = circuitBreaker;
        this.workerThread = new Thread(this::processLoop, "trade-execution-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        log.info("TradeExecutionQueue started — capacity={}", QUEUE_CAPACITY);
    }

    public void submitTrade(TradeRequest request) {
        // Validate all required fields
        try {
            request.validate();
        } catch (IllegalArgumentException e) {
            log.error("[TRADE_REJECTED] botId={} reason=validation_failed error={}", request.getBotId(), e.getMessage());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false).errorMessage(e.getMessage()).build());
            totalFailed.incrementAndGet();
            return;
        }

        // Duplicate request protection
        if (!processedRequests.add(request.getRequestId())) {
            log.warn("[TRADE_REJECTED] requestId={} botId={} reason=duplicate_request",
                request.getRequestId(), request.getBotId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false).errorMessage("Duplicate trade request ignored").build());
            totalFailed.incrementAndGet();
            return;
        }

        // Live trading safety gate
        if ("LIVE".equalsIgnoreCase(request.getExchangeMode()) && !liveTradingEnabled) {
            log.error("[TRADE_REJECTED] botId={} exchange={} reason=live_trading_disabled",
                request.getBotId(), request.getExchange());
            processedRequests.remove(request.getRequestId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false).errorMessage("Live trading is disabled").build());
            totalFailed.incrementAndGet();
            return;
        }

        if (killSwitch.isActive()) {
            log.warn("[TRADE_REJECTED] botId={} symbol={} side={} exchange={} reason=kill_switch_active",
                request.getBotId(), request.getSymbol(), request.getSide(), request.getExchange());
            processedRequests.remove(request.getRequestId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Kill switch active: " + killSwitch.getActivationReason())
                .build());
            totalFailed.incrementAndGet();
            return;
        }

        if (pendingTrades.putIfAbsent(request.getBotId(), true) != null) {
            log.warn("[TRADE_REJECTED] botId={} symbol={} side={} exchange={} reason=pending_trade_exists",
                request.getBotId(), request.getSymbol(), request.getSide(), request.getExchange());
            processedRequests.remove(request.getRequestId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Bot already has a pending trade in queue")
                .build());
            totalFailed.incrementAndGet();
            return;
        }

        totalSubmitted.incrementAndGet();

        int currentSize = queue.size();
        double usagePercent = currentSize * 100.0 / QUEUE_CAPACITY;
        if (usagePercent >= QUEUE_WARNING_THRESHOLD * 100) {
            log.warn("[QUEUE_WARNING] capacity={}% ({}/{}) — approaching limit",
                (int) usagePercent, currentSize, QUEUE_CAPACITY);
        }

        log.info("[TRADE_SUBMITTED] requestId={} botId={} symbol={} side={} qty={} type={} exchange={} mode={} queueSize={}",
            request.getRequestId(), request.getBotId(), request.getSymbol(), request.getSide(),
            request.getQuantity(), request.getOrderType(), request.getExchange(),
            request.getExchangeMode(), currentSize + 1);

        if (!queue.offer(request)) {
            log.error("[TRADE_REJECTED] botId={} symbol={} side={} exchange={} reason=queue_full",
                request.getBotId(), request.getSymbol(), request.getSide(), request.getExchange());
            pendingTrades.remove(request.getBotId());
            processedRequests.remove(request.getRequestId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Execution queue full")
                .build());
            totalFailed.incrementAndGet();
        }
    }

    public int cancelPendingTrades() {
        int cancelled = 0;
        TradeRequest req;
        while ((req = queue.poll()) != null) {
            pendingTrades.remove(req.getBotId());
            processedRequests.remove(req.getRequestId());
            req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Trade cancelled — kill switch activated")
                .build());
            cancelled++;
        }
        if (cancelled > 0) {
            log.warn("[KILL_SWITCH] Cancelled {} pending queue trades", cancelled);
        }
        return cancelled;
    }

    private void processLoop() {
        while (running.get()) {
            try {
                TradeRequest req = queue.poll(1, TimeUnit.SECONDS);
                if (req == null) continue;

                if (killSwitch.isActive()) {
                    log.warn("[TRADE_FAILED] botId={} symbol={} side={} exchange={} reason=kill_switch_active",
                        req.getBotId(), req.getSymbol(), req.getSide(), req.getExchange());
                    pendingTrades.remove(req.getBotId());
                    processedRequests.remove(req.getRequestId());
                    req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                        .success(false).errorMessage("Kill switch active").build());
                    totalFailed.incrementAndGet();
                    continue;
                }

                if (!circuitBreaker.isAllowed()) {
                    log.warn("[TRADE_DELAYED] botId={} symbol={} side={} exchange={} reason=circuit_breaker_open",
                        req.getBotId(), req.getSymbol(), req.getSide(), req.getExchange());
                    if (!queue.offer(req)) {
                        pendingTrades.remove(req.getBotId());
                        processedRequests.remove(req.getRequestId());
                        req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                            .success(false).errorMessage("Circuit breaker open and queue full").build());
                        totalFailed.incrementAndGet();
                    }
                    Thread.sleep(1000);
                    continue;
                }

                log.info("[TRADE] Exchange={} Symbol={} Side={} Qty={} BotId={} RequestId={}",
                    req.getExchange(), req.getSymbol(), req.getSide(),
                    req.getQuantity(), req.getBotId(), req.getRequestId());

                TradeRequest.TradeResult result = executeWithRetry(req);

                pendingTrades.remove(req.getBotId());
                processedRequests.remove(req.getRequestId());

                if (result.isSuccess()) {
                    totalExecuted.incrementAndGet();
                    log.info("[TRADE_EXECUTED] exchange={} symbol={} side={} qty={} orderId={} avgPrice={} botId={}",
                        req.getExchange(), req.getSymbol(), req.getSide(),
                        result.getExecutedQty(), result.getOrderId(), result.getAvgPrice(), req.getBotId());
                } else {
                    totalFailed.incrementAndGet();
                    log.error("[TRADE_FAILED] exchange={} symbol={} side={} qty={} botId={} error={}",
                        req.getExchange(), req.getSymbol(), req.getSide(),
                        req.getQuantity(), req.getBotId(), result.getErrorMessage());
                }

                req.getResultFuture().complete(result);
                Thread.sleep(RATE_LIMIT_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[QUEUE] Worker interrupted");
                break;
            } catch (Exception e) {
                log.error("[QUEUE] Unexpected error in worker loop: {}", e.getMessage(), e);
            }
        }
        log.info("[QUEUE] Worker thread stopped");
    }

    private TradeRequest.TradeResult executeWithRetry(TradeRequest req) {
        if (req.getExchange() == null || req.getExchange().isBlank()) {
            return TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Exchange field is required — cannot default to any exchange")
                .build();
        }

        ExchangeClient exchangeClient = exchangeFactory.getClient(req.getExchange());

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                OrderResponse orderResult;
                if ("LIMIT".equalsIgnoreCase(req.getOrderType())) {
                    log.info("[TRADE] Placing LIMIT order: exchange={} symbol={} side={} qty={} price={}",
                        req.getExchange(), req.getSymbol(), req.getSide(), req.getQuantity(), req.getPrice());
                    orderResult = exchangeClient.placeLimitOrder(
                        req.getApiKey(), req.getApiSecret(),
                        req.getSymbol(), req.getSide(),
                        req.getQuantity(), req.getPrice(), req.getExchangeBaseUrl());
                } else {
                    orderResult = exchangeClient.placeMarketOrder(
                        req.getApiKey(), req.getApiSecret(),
                        req.getSymbol(), req.getSide(),
                        req.getQuantity(), req.getExchangeBaseUrl());
                }

                return TradeRequest.TradeResult.builder()
                    .success(true)
                    .orderId(orderResult.getOrderId())
                    .executedQty(orderResult.getExecutedQty())
                    .avgPrice(orderResult.getAvgPrice())
                    .build();

            } catch (Exception e) {
                log.error("[TRADE_RETRY] exchange={} symbol={} side={} botId={} attempt={}/{} error={}",
                    req.getExchange(), req.getSymbol(), req.getSide(), req.getBotId(),
                    attempt, MAX_RETRIES, e.getMessage());
                circuitBreaker.recordFailure();
                killSwitch.recordExchangeError();
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(1000L * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }

        return TradeRequest.TradeResult.builder()
            .success(false)
            .errorMessage("All " + MAX_RETRIES + " attempts failed on " + req.getExchange())
            .build();
    }

    // --- Metrics ---
    public int getQueueSize() { return queue.size(); }
    public int getQueueCapacity() { return QUEUE_CAPACITY; }
    public double getQueueUsagePercent() { return queue.size() * 100.0 / QUEUE_CAPACITY; }
    public int getPendingBotsCount() { return pendingTrades.size(); }
    public long getTotalSubmitted() { return totalSubmitted.get(); }
    public long getTotalExecuted() { return totalExecuted.get(); }
    public long getTotalFailed() { return totalFailed.get(); }

    @PreDestroy
    public void shutdown() {
        log.info("[QUEUE] Shutting down — draining {} remaining trades", queue.size());
        running.set(false);
        try { workerThread.join(10_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        TradeRequest remaining;
        while ((remaining = queue.poll()) != null) {
            pendingTrades.remove(remaining.getBotId());
            processedRequests.remove(remaining.getRequestId());
            remaining.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false).errorMessage("System shutting down").build());
        }
        log.info("[QUEUE] Shutdown complete. Executed={}, Failed={}", totalExecuted.get(), totalFailed.get());
    }
}
