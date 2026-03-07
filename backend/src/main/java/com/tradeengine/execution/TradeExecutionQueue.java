package com.tradeengine.execution;

import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized trade execution queue with kill switch, circuit breaker, and capacity monitoring.
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
        log.info("TradeExecutionQueue started — worker thread active");
    }

    public void submitTrade(TradeRequest request) {
        // Kill switch check
        if (killSwitch.isActive()) {
            log.warn("[QUEUE] Trade rejected — kill switch active: {} {} {}",
                request.getSide(), request.getSymbol(), request.getBotId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Kill switch active: " + killSwitch.getActivationReason())
                .build());
            totalFailed.incrementAndGet();
            return;
        }

        totalSubmitted.incrementAndGet();

        // Queue capacity monitoring
        int currentSize = queue.size();
        if (currentSize >= QUEUE_CAPACITY * QUEUE_WARNING_THRESHOLD) {
            log.warn("[QUEUE] WARNING: Queue at {}% capacity ({}/{})",
                (int)(currentSize * 100.0 / QUEUE_CAPACITY), currentSize, QUEUE_CAPACITY);
        }

        log.info("[QUEUE] Trade received: {} {} {} qty={} bot={}",
                request.getSide(), request.getSymbol(), request.getOrderType(),
                request.getQuantity(), request.getBotId());

        if (!queue.offer(request)) {
            log.error("[QUEUE] Queue full! Rejecting trade for bot {}", request.getBotId());
            request.getResultFuture().complete(TradeRequest.TradeResult.builder()
                    .success(false)
                    .errorMessage("Execution queue full")
                    .build());
            totalFailed.incrementAndGet();
        }
    }

    /**
     * Cancel all pending trades in the queue (used by kill switch).
     */
    public int cancelPendingTrades() {
        int cancelled = 0;
        TradeRequest req;
        while ((req = queue.poll()) != null) {
            req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("Trade cancelled — kill switch activated")
                .build());
            cancelled++;
        }
        if (cancelled > 0) {
            log.warn("[QUEUE] Cancelled {} pending trades due to kill switch", cancelled);
        }
        return cancelled;
    }

    private void processLoop() {
        while (running.get()) {
            try {
                TradeRequest req = queue.poll(1, TimeUnit.SECONDS);
                if (req == null) continue;

                // Re-check kill switch before executing
                if (killSwitch.isActive()) {
                    log.warn("[QUEUE] Trade skipped — kill switch active: {} {} {}",
                        req.getSide(), req.getSymbol(), req.getBotId());
                    req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                        .success(false)
                        .errorMessage("Kill switch active")
                        .build());
                    totalFailed.incrementAndGet();
                    continue;
                }

                // Circuit breaker check
                if (!circuitBreaker.isAllowed()) {
                    log.warn("[QUEUE] Trade delayed — circuit breaker open: {} {} {}",
                        req.getSide(), req.getSymbol(), req.getBotId());
                    // Re-queue the trade
                    if (!queue.offer(req)) {
                        req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                            .success(false)
                            .errorMessage("Circuit breaker open and queue full")
                            .build());
                        totalFailed.incrementAndGet();
                    }
                    Thread.sleep(1000); // Wait before retrying
                    continue;
                }

                log.info("[QUEUE] Trade executing: {} {} {} qty={} bot={}",
                        req.getSide(), req.getSymbol(), req.getOrderType(),
                        req.getQuantity(), req.getBotId());

                TradeRequest.TradeResult result = executeWithRetry(req);

                if (result.isSuccess()) {
                    totalExecuted.incrementAndGet();
                    log.info("[QUEUE] Trade success: {} {} {} orderId={} avgPrice={}",
                            req.getSide(), req.getSymbol(), result.getExecutedQty(),
                            result.getOrderId(), result.getAvgPrice());
                } else {
                    totalFailed.incrementAndGet();
                    log.error("[QUEUE] Trade failed: {} {} {} error={}",
                            req.getSide(), req.getSymbol(), req.getQuantity(),
                            result.getErrorMessage());
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
                BinanceClient.OrderResult orderResult;
                if ("MARKET".equals(req.getOrderType())) {
                    orderResult = binance.placeMarketOrder(
                            req.getApiKey(), req.getApiSecret(),
                            req.getSymbol(), req.getSide(),
                            req.getQuantity(), req.getExchangeBaseUrl());
                } else {
                    orderResult = binance.placeMarketOrder(
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
                log.error("[QUEUE] Attempt {}/{} failed for {} {} {}: {}",
                        attempt, MAX_RETRIES, req.getSide(), req.getQuantity(),
                        req.getSymbol(), e.getMessage());
                circuitBreaker.recordFailure();
                killSwitch.recordExchangeError();
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        return TradeRequest.TradeResult.builder()
                .success(false)
                .errorMessage("All " + MAX_RETRIES + " attempts failed")
                .build();
    }

    public int getQueueSize() { return queue.size(); }
    public long getTotalSubmitted() { return totalSubmitted.get(); }
    public long getTotalExecuted() { return totalExecuted.get(); }
    public long getTotalFailed() { return totalFailed.get(); }

    @PreDestroy
    public void shutdown() {
        log.info("[QUEUE] Shutting down — draining {} remaining trades", queue.size());
        running.set(false);
        try {
            workerThread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        TradeRequest remaining;
        while ((remaining = queue.poll()) != null) {
            remaining.getResultFuture().complete(TradeRequest.TradeResult.builder()
                    .success(false)
                    .errorMessage("System shutting down")
                    .build());
        }
        log.info("[QUEUE] Shutdown complete. Executed={}, Failed={}", totalExecuted.get(), totalFailed.get());
    }
}
