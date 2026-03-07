package com.tradeengine.execution;

import com.tradeengine.exchange.BinanceClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized trade execution queue.
 * Prevents concurrent Binance API calls and handles retries with rate-limit protection.
 */
@Service
@Slf4j
public class TradeExecutionQueue {

    private static final int MAX_RETRIES = 3;
    private static final long RATE_LIMIT_DELAY_MS = 100;

    private final BinanceClient binance;
    private final BlockingQueue<TradeRequest> queue = new LinkedBlockingQueue<>(1000);
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Metrics
    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalExecuted = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    private final Thread workerThread;

    public TradeExecutionQueue(BinanceClient binance) {
        this.binance = binance;
        this.workerThread = new Thread(this::processLoop, "trade-execution-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        log.info("TradeExecutionQueue started — worker thread active");
    }

    /**
     * Submit a trade request to the queue. Returns immediately.
     * Use request.getResultFuture() to await the outcome.
     */
    public void submitTrade(TradeRequest request) {
        totalSubmitted.incrementAndGet();
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

    private void processLoop() {
        while (running.get()) {
            try {
                TradeRequest req = queue.poll(1, TimeUnit.SECONDS);
                if (req == null) continue;

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

                // Rate limit protection
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
                    // For limit orders, fall back to market for now
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

    // --- Metrics ---

    public int getQueueSize() { return queue.size(); }
    public long getTotalSubmitted() { return totalSubmitted.get(); }
    public long getTotalExecuted() { return totalExecuted.get(); }
    public long getTotalFailed() { return totalFailed.get(); }

    @PreDestroy
    public void shutdown() {
        log.info("[QUEUE] Shutting down — draining {} remaining trades", queue.size());
        running.set(false);
        try {
            workerThread.join(10_000); // Wait up to 10s for current trade to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Complete any remaining futures with failure
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
