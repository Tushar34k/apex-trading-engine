package com.tradeengine.execution;

import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized trade execution queue with per-bot duplicate protection,
 * kill switch integration, circuit breaker, and capacity monitoring.
 */
@Service
@Slf4j
public class TradeExecutionQueue {

    private static final int MAX_RETRIES = 3;
    private static final long RATE_LIMIT_DELAY_MS = 100;
    private static final int QUEUE_CAPACITY = 1000;
    private static final double QUEUE_WARNING_THRESHOLD = 0.9;

    private final BinanceClient binance;
    private final KillSwitchService killSwitch;
    private final CircuitBreakerService circuitBreaker;
    private final BlockingQueue<TradeRequest> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Per-bot pending trade protection
    private final ConcurrentHashMap<UUID, Boolean> pendingTrades = new ConcurrentHashMap<>();

    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalExecuted = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    private final Thread workerThread;

    public TradeExecutionQueue(BinanceClient binance, KillSwitchService killSwitch, CircuitBreakerService circuitBreaker) {
        this.binance = binance;
        this.killSwitch = killSwitch;
        this.circuitBreaker = circuitBreaker;
        this.workerThread = new Thread(this::processLoop, "trade-execution-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        log.info("TradeExecutionQueue started — capacity={}", QUEUE_CAPACITY);
    }

    public void submitTrade(TradeRequest request) {
        // Kill switch gate
        if (killSwitch.isActive()) {
            log.warn("[TRADE_REJECTED] botId={} symbol={} side={} qty={} reason=kill_switch_active",
                request.getBotId(), request.getSymbol(), request.getSide(), request.getQuantity());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Kill switch active: " + killSwitch.getActivationReason())
                .build());
            totalFailed.incrementAndGet();
            return;
        }

        // Per-bot duplicate protection
        if (pendingTrades.putIfAbsent(request.getBotId(), true) != null) {
            log.warn("[TRADE_REJECTED] botId={} symbol={} side={} reason=pending_trade_exists",
                request.getBotId(), request.getSymbol(), request.getSide());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Bot already has a pending trade in queue")
                .build());
            totalFailed.incrementAndGet();
            return;
        }

        totalSubmitted.incrementAndGet();

        // Queue capacity monitoring
        int currentSize = queue.size();
        double usagePercent = currentSize * 100.0 / QUEUE_CAPACITY;
        if (usagePercent >= QUEUE_WARNING_THRESHOLD * 100) {
            log.warn("[QUEUE_WARNING] capacity={}% ({}/{}) — approaching limit",
                (int) usagePercent, currentSize, QUEUE_CAPACITY);
        }

        log.info("[TRADE_SUBMITTED] botId={} symbol={} side={} qty={} type={} queueSize={}",
            request.getBotId(), request.getSymbol(), request.getSide(),
            request.getQuantity(), request.getOrderType(), currentSize + 1);

        if (!queue.offer(request)) {
            log.error("[TRADE_REJECTED] botId={} symbol={} side={} reason=queue_full",
                request.getBotId(), request.getSymbol(), request.getSide());
            pendingTrades.remove(request.getBotId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Execution queue full")
                .build());
            totalFailed.incrementAndGet();
        }
    }

    /**
     * Cancel all pending trades in the queue (called by kill switch).
     */
    public int cancelPendingTrades() {
        int cancelled = 0;
        TradeRequest req;
        while ((req = queue.poll()) != null) {
            pendingTrades.remove(req.getBotId());
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

                // Re-check kill switch
                if (killSwitch.isActive()) {
                    log.warn("[TRADE_FAILED] botId={} symbol={} side={} reason=kill_switch_active",
                        req.getBotId(), req.getSymbol(), req.getSide());
                    pendingTrades.remove(req.getBotId());
                    req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                        .success(false).errorMessage("Kill switch active").build());
                    totalFailed.incrementAndGet();
                    continue;
                }

                // Circuit breaker check
                if (!circuitBreaker.isAllowed()) {
                    log.warn("[TRADE_DELAYED] botId={} symbol={} side={} reason=circuit_breaker_open",
                        req.getBotId(), req.getSymbol(), req.getSide());
                    if (!queue.offer(req)) {
                        pendingTrades.remove(req.getBotId());
                        req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                            .success(false).errorMessage("Circuit breaker open and queue full").build());
                        totalFailed.incrementAndGet();
                    }
                    Thread.sleep(1000);
                    continue;
                }

                log.info("[TRADE_EXECUTING] botId={} symbol={} side={} qty={} type={}",
                    req.getBotId(), req.getSymbol(), req.getSide(),
                    req.getQuantity(), req.getOrderType());

                TradeRequest.TradeResult result = executeWithRetry(req);

                // Clear per-bot pending lock
                pendingTrades.remove(req.getBotId());

                if (result.isSuccess()) {
                    totalExecuted.incrementAndGet();
                    log.info("[TRADE_EXECUTED] botId={} symbol={} side={} qty={} orderId={} avgPrice={}",
                        req.getBotId(), req.getSymbol(), req.getSide(),
                        result.getExecutedQty(), result.getOrderId(), result.getAvgPrice());
                } else {
                    totalFailed.incrementAndGet();
                    log.error("[TRADE_FAILED] botId={} symbol={} side={} qty={} error={}",
                        req.getBotId(), req.getSymbol(), req.getSide(),
                        req.getQuantity(), result.getErrorMessage());
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
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                BinanceClient.OrderResult orderResult = binance.placeMarketOrder(
                    req.getApiKey(), req.getApiSecret(),
                    req.getSymbol(), req.getSide(),
                    req.getQuantity(), req.getExchangeBaseUrl());

                return TradeRequest.TradeResult.builder()
                    .success(true)
                    .orderId(orderResult.getOrderId())
                    .executedQty(orderResult.getExecutedQty())
                    .avgPrice(orderResult.getAvgPrice())
                    .build();

            } catch (Exception e) {
                log.error("[TRADE_RETRY] botId={} symbol={} side={} attempt={}/{} error={}",
                    req.getBotId(), req.getSymbol(), req.getSide(),
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
            .errorMessage("All " + MAX_RETRIES + " attempts failed")
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
            remaining.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false).errorMessage("System shutting down").build());
        }
        log.info("[QUEUE] Shutdown complete. Executed={}, Failed={}", totalExecuted.get(), totalFailed.get());
    }
}
