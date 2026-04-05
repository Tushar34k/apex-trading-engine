package com.tradeengine.execution;

import com.tradeengine.exchange.Balance;
import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.OrderResponse;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import com.tradeengine.service.OrderNormalizerService;
import com.tradeengine.service.PositionRiskValidator;
import com.tradeengine.service.PositionSyncService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production-hardened trade execution queue with:
 * - Order normalization (stepSize/tickSize/minQty/minNotional)
 * - Position risk validation (maxPositionPercent/maxSingleTradePercent)
 * - Per-bot+symbol duplicate order lock (2s cooldown)
 * - Exponential backoff retry for transient errors (429/500/timeout)
 * - Order result validation (orderId/status/executedQty)
 * - Full execution metrics
 */
@Service
@Slf4j
public class TradeExecutionQueue {

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {500, 1000, 2000};
    private static final long RATE_LIMIT_DELAY_MS = 100;
    private static final int QUEUE_CAPACITY = 1000;
    private static final double QUEUE_WARNING_THRESHOLD = 0.9;
    private static final long PARTIAL_FILL_WAIT_MS = 1000;

    private final ExchangeFactory exchangeFactory;
    private final KillSwitchService killSwitch;
    private final CircuitBreakerService circuitBreaker;
    private final OrderNormalizerService orderNormalizer;
    private final PositionRiskValidator riskValidator;
    private final PositionSyncService positionSyncService;
    private final BlockingQueue<TradeRequest> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Per-bot pending trade protection
    private final ConcurrentHashMap<UUID, Boolean> pendingTrades = new ConcurrentHashMap<>();

    // Request-level duplicate protection
    private final Set<UUID> processedRequests = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> requestTimestamps = new ConcurrentHashMap<>();
    private static final long REQUEST_EXPIRY_MS = 300_000;

    // Execution metrics
    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalExecuted = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicLong latencySampleCount = new AtomicLong();

    // Recent rejection reasons for UI
    private final ConcurrentLinkedDeque<RejectionRecord> recentRejections = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_REJECTIONS = 50;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    private final Thread workerThread;

    public TradeExecutionQueue(ExchangeFactory exchangeFactory, KillSwitchService killSwitch,
                               CircuitBreakerService circuitBreaker, OrderNormalizerService orderNormalizer,
                               PositionRiskValidator riskValidator,
                               @org.springframework.context.annotation.Lazy PositionSyncService positionSyncService) {
        this.exchangeFactory = exchangeFactory;
        this.killSwitch = killSwitch;
        this.circuitBreaker = circuitBreaker;
        this.orderNormalizer = orderNormalizer;
        this.riskValidator = riskValidator;
        this.positionSyncService = positionSyncService;
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
            rejectOrder(request, "VALIDATION_FAILED", e.getMessage());
            return;
        }

        // Duplicate request protection
        if (!processedRequests.add(request.getRequestId())) {
            rejectOrder(request, "DUPLICATE_REQUEST", "Duplicate trade request ignored");
            return;
        }
        requestTimestamps.put(request.getRequestId(), System.currentTimeMillis());
        cleanupExpiredRequests();

        // Live trading safety gate
        if ("LIVE".equalsIgnoreCase(request.getExchangeMode()) && !liveTradingEnabled) {
            processedRequests.remove(request.getRequestId());
            rejectOrder(request, "LIVE_TRADING_DISABLED", "Live trading is disabled");
            return;
        }

        if (killSwitch.isActive()) {
            processedRequests.remove(request.getRequestId());
            rejectOrder(request, "KILL_SWITCH", "Kill switch active: " + killSwitch.getActivationReason());
            return;
        }

        // Per-bot+symbol duplicate order lock (2s cooldown)
        if (riskValidator != null && !riskValidator.acquireOrderLock(request.getBotId(), request.getSymbol())) {
            processedRequests.remove(request.getRequestId());
            rejectOrder(request, "DUPLICATE_ORDER", "Order blocked — cooldown active for bot+symbol");
            return;
        }

        if (pendingTrades.putIfAbsent(request.getBotId(), true) != null) {
            processedRequests.remove(request.getRequestId());
            rejectOrder(request, "PENDING_TRADE", "Bot already has a pending trade in queue");
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
            pendingTrades.remove(request.getBotId());
            processedRequests.remove(request.getRequestId());
            rejectOrder(request, "QUEUE_FULL", "Execution queue full");
        }
    }

    public int cancelPendingTrades() {
        int cancelled = 0;
        TradeRequest req;
        while ((req = queue.poll()) != null) {
            pendingTrades.remove(req.getBotId());
            processedRequests.remove(req.getRequestId());
            req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                .success(false).errorMessage("Trade cancelled — kill switch activated").build());
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

                long startMs = System.currentTimeMillis();

                if (killSwitch.isActive()) {
                    pendingTrades.remove(req.getBotId());
                    processedRequests.remove(req.getRequestId());
                    req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                        .success(false).errorMessage("Kill switch active").build());
                    totalFailed.incrementAndGet();
                    continue;
                }

                if (!circuitBreaker.isAllowed()) {
                    log.warn("[TRADE_DELAYED] botId={} symbol={} exchange={} reason=circuit_breaker_open",
                        req.getBotId(), req.getSymbol(), req.getExchange());
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

                // ── Order Normalization ──
                NormalizedOrder normalized = null;
                if (orderNormalizer != null) {
                    normalized = orderNormalizer.normalizeOrder(
                        req.getExchange(), req.getSymbol(), req.getQuantity(),
                        req.getPrice(), req.getSide(), req.getExchangeBaseUrl());

                    if (!normalized.isValid()) {
                        pendingTrades.remove(req.getBotId());
                        processedRequests.remove(req.getRequestId());
                        recordRejection(req.getBotId(), req.getSymbol(), normalized.getValidationMessage());
                        req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                            .success(false).errorMessage("Order normalization failed: " + normalized.getValidationMessage()).build());
                        totalRejected.incrementAndGet();
                        totalFailed.incrementAndGet();
                        continue;
                    }
                }

                // ── Position Risk Validation ──
                if (riskValidator != null) {
                    BigDecimal checkPrice = normalized != null ? normalized.getPrice() : req.getPrice();
                    BigDecimal checkQty = normalized != null ? normalized.getQuantity() : req.getQuantity();

                    // Fetch account balance from exchange for real risk validation
                    BigDecimal accountBalance = null;
                    try {
                        ExchangeClient balanceClient = exchangeFactory.getClient(req.getExchange());
                        List<Balance> balances = balanceClient.getBalances(req.getApiKey(), req.getApiSecret(), req.getExchangeBaseUrl());
                        accountBalance = balances.stream()
                            .filter(b -> "USDT".equalsIgnoreCase(b.getAsset()) || "USD".equalsIgnoreCase(b.getAsset()))
                            .map(Balance::getTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                        log.debug("[RISK_CHECK] exchange={} accountBalance={}", req.getExchange(), accountBalance);
                    } catch (Exception e) {
                        log.warn("[RISK_CHECK] Failed to fetch balance for risk validation, skipping: {}", e.getMessage());
                    }

                    String riskError = riskValidator.validatePositionSize(
                        req.getExchange(), req.getSymbol(), checkQty, checkPrice, accountBalance);
                    if (riskError != null) {
                        pendingTrades.remove(req.getBotId());
                        processedRequests.remove(req.getRequestId());
                        recordRejection(req.getBotId(), req.getSymbol(), riskError);
                        req.getResultFuture().complete(TradeRequest.TradeResult.builder()
                            .success(false).errorMessage("Position risk check failed: " + riskError).build());
                        totalRejected.incrementAndGet();
                        totalFailed.incrementAndGet();
                        log.warn("[ORDER_REJECTED] reason=RISK_LIMIT botId={} symbol={} {}", req.getBotId(), req.getSymbol(), riskError);
                        continue;
                    }
                }

                TradeRequest normalizedReq = normalized != null
                    ? TradeRequest.builder()
                        .requestId(req.getRequestId()).botId(req.getBotId()).userId(req.getUserId())
                        .symbol(req.getSymbol()).side(req.getSide())
                        .quantity(normalized.getQuantity()).price(normalized.getPrice())
                        .orderType(req.getOrderType()).apiKey(req.getApiKey()).apiSecret(req.getApiSecret())
                        .exchangeBaseUrl(req.getExchangeBaseUrl()).exchange(req.getExchange())
                        .exchangeMode(req.getExchangeMode()).timestamp(req.getTimestamp())
                        .stopLossPrice(req.getStopLossPrice()).takeProfitPrice(req.getTakeProfitPrice())
                        .trailingStopPercent(req.getTrailingStopPercent())
                        .build()
                    : req;

                TradeRequest.TradeResult result = executeWithRetry(normalizedReq);

                long latencyMs = System.currentTimeMillis() - startMs;
                totalLatencyMs.addAndGet(latencyMs);
                latencySampleCount.incrementAndGet();

                pendingTrades.remove(req.getBotId());
                processedRequests.remove(req.getRequestId());

                if (result.isSuccess()) {
                    totalExecuted.incrementAndGet();
                    log.info("[TRADE_EXECUTED] exchange={} symbol={} side={} qty={} orderId={} avgPrice={} botId={} latency={}ms",
                        req.getExchange(), req.getSymbol(), req.getSide(),
                        result.getExecutedQty(), result.getOrderId(), result.getAvgPrice(),
                        req.getBotId(), latencyMs);

                    // Trigger post-trade position sync
                    triggerPostTradeSync();
                } else {
                    totalFailed.incrementAndGet();
                    log.error("[TRADE_FAILED] exchange={} symbol={} side={} qty={} botId={} error={} latency={}ms",
                        req.getExchange(), req.getSymbol(), req.getSide(),
                        req.getQuantity(), req.getBotId(), result.getErrorMessage(), latencyMs);
                }

                req.getResultFuture().complete(result);
                log.info("[POSITION_UPDATED] botId={} symbol={} side={} exchange={}",
                    req.getBotId(), req.getSymbol(), req.getSide(), req.getExchange());
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

                // ── Order result validation ──
                String validationError = validateOrderResponse(orderResult);
                if (validationError != null) {
                    log.error("[ORDER_INVALID_RESPONSE] exchange={} symbol={} error={}",
                        req.getExchange(), req.getSymbol(), validationError);
                    return TradeRequest.TradeResult.builder()
                        .success(false).errorMessage(validationError).build();
                }

                // ── Partial fill handling ──
                if (orderResult.isPartiallyFilled()) {
                    log.warn("[PARTIAL_FILL] exchange={} symbol={} orderId={} executedQty={} — waiting for fill update",
                        req.getExchange(), req.getSymbol(), orderResult.getOrderId(), orderResult.getExecutedQty());
                    try {
                        Thread.sleep(PARTIAL_FILL_WAIT_MS);
                        OrderResponse updated = exchangeClient.queryOrderStatus(
                            req.getApiKey(), req.getApiSecret(), req.getSymbol(),
                            orderResult.getOrderId(), req.getExchangeBaseUrl());
                        log.info("[PARTIAL_FILL_UPDATE] exchange={} symbol={} orderId={} status={} executedQty={}",
                            req.getExchange(), req.getSymbol(), updated.getOrderId(),
                            updated.getStatus(), updated.getExecutedQty());
                        orderResult = updated;
                    } catch (Exception pfe) {
                        log.warn("[PARTIAL_FILL] Failed to query updated status, using original: {}", pfe.getMessage());
                    }
                }

                // Always use executedQty from exchange — never assume full fill
                BigDecimal finalExecutedQty = orderResult.getExecutedQty();
                if (finalExecutedQty == null || finalExecutedQty.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    finalExecutedQty = req.getQuantity(); // fallback only if exchange didn't report
                    log.warn("[PARTIAL_FILL] executedQty missing from response, using requested qty={}", finalExecutedQty);
                }

                return TradeRequest.TradeResult.builder()
                    .success(true)
                    .orderId(orderResult.getOrderId())
                    .executedQty(finalExecutedQty)
                    .avgPrice(orderResult.getAvgPrice())
                    .orderType(orderResult.getOrderType())
                    .build();

            } catch (Exception e) {
                long delay = attempt <= RETRY_DELAYS_MS.length ? RETRY_DELAYS_MS[attempt - 1] : 2000;
                boolean isRetryable = isRetryableError(e);

                log.error("[ORDER_RETRY] attempt={}/{} exchange={} symbol={} side={} botId={} retryable={} delay={}ms error={}",
                    attempt, MAX_RETRIES, req.getExchange(), req.getSymbol(), req.getSide(),
                    req.getBotId(), isRetryable, delay, e.getMessage());

                circuitBreaker.recordFailure();
                killSwitch.recordExchangeError();

                if (!isRetryable || attempt >= MAX_RETRIES) {
                    break;
                }

                try { Thread.sleep(delay); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        return TradeRequest.TradeResult.builder()
            .success(false)
            .errorMessage("All " + MAX_RETRIES + " attempts failed on " + req.getExchange())
            .build();
    }

    /**
     * Validate that the exchange response contains required fields.
     */
    private String validateOrderResponse(OrderResponse response) {
        if (response == null) return "Exchange returned null response";
        if (response.getOrderId() == null || response.getOrderId().isBlank())
            return "Exchange response missing orderId";
        if (response.getStatus() == null || response.getStatus().isBlank())
            return "Exchange response missing status";
        if (response.getExecutedQty() == null)
            return "Exchange response missing executedQty";
        return null;
    }

    /**
     * Check if an error is transient and retryable (429, 500, timeout).
     */
    private boolean isRetryableError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // ═══ GRACEFUL 401 HANDLING — don't crash, alert the frontend ═══
        if (msg.contains("401") || msg.contains("invalid api-key") || msg.contains("ip") || msg.contains("permission")) {
            log.error("[API_AUTH_ERROR] Exchange returned 401/auth error: {}", e.getMessage());
            // Extract exchange name from context if possible
            String exchange = "UNKNOWN";
            if (msg.contains("binance")) exchange = "BINANCE";
            else if (msg.contains("delta")) exchange = "DELTA";
            else if (msg.contains("bybit")) exchange = "BYBIT";
            com.tradeengine.controller.RiskMonitorController.recordApiAuthError(exchange, e.getMessage());

            // Record as sizing audit rejection too
            Map<String, Object> authAudit = new java.util.LinkedHashMap<>();
            authAudit.put("timestamp", java.time.Instant.now().toString());
            authAudit.put("symbol", "N/A");
            authAudit.put("status", "REJECTED");
            authAudit.put("reason", "API_AUTH_BLOCKED: " + e.getMessage());
            com.tradeengine.controller.RiskMonitorController.recordSizingEvaluation(authAudit);

            return false; // NOT retryable — auth errors won't resolve on retry
        }

        return msg.contains("429") || msg.contains("500") || msg.contains("502")
            || msg.contains("503") || msg.contains("timeout") || msg.contains("timed out")
            || e instanceof java.net.http.HttpTimeoutException;
    }

    private void rejectOrder(TradeRequest request, String reason, String message) {
        log.warn("[ORDER_REJECTED] botId={} symbol={} exchange={} reason={} message={}",
            request.getBotId(), request.getSymbol(), request.getExchange(), reason, message);
        request.getResultFuture().complete(TradeRequest.TradeResult.builder()
            .success(false).errorMessage(message).build());
        totalRejected.incrementAndGet();
        totalFailed.incrementAndGet();
        recordRejection(request.getBotId(), request.getSymbol(), reason + ": " + message);
    }

    private void recordRejection(UUID botId, String symbol, String reason) {
        recentRejections.addFirst(new RejectionRecord(
            System.currentTimeMillis(), botId, symbol, reason));
        while (recentRejections.size() > MAX_RECENT_REJECTIONS) {
            recentRejections.pollLast();
        }
    }

    // --- Cleanup ---
    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        requestTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > REQUEST_EXPIRY_MS) {
                processedRequests.remove(entry.getKey());
                return true;
            }
            return false;
        });
        if (riskValidator != null) riskValidator.cleanupLocks();
    }

    // --- Metrics ---
    public int getQueueSize() { return queue.size(); }
    public int getQueueCapacity() { return QUEUE_CAPACITY; }
    public double getQueueUsagePercent() { return queue.size() * 100.0 / QUEUE_CAPACITY; }
    public int getPendingBotsCount() { return pendingTrades.size(); }
    public long getTotalSubmitted() { return totalSubmitted.get(); }
    public long getTotalExecuted() { return totalExecuted.get(); }
    public long getTotalFailed() { return totalFailed.get(); }
    public long getTotalRejected() { return totalRejected.get(); }
    public double getSuccessRate() {
        long total = totalSubmitted.get();
        return total > 0 ? (double) totalExecuted.get() / total * 100 : 0;
    }
    public double getAvgLatencyMs() {
        long samples = latencySampleCount.get();
        return samples > 0 ? (double) totalLatencyMs.get() / samples : 0;
    }
    public java.util.List<RejectionRecord> getRecentRejections() {
        return new java.util.ArrayList<>(recentRejections);
    }

    /**
     * Trigger position sync after trade execution to ensure internal state matches exchange.
     */
    private void triggerPostTradeSync() {
        if (positionSyncService != null) {
            try {
                positionSyncService.syncPositions();
                log.debug("[POST_TRADE_SYNC] Position sync triggered after trade execution");
            } catch (Exception e) {
                log.warn("[POST_TRADE_SYNC] Failed to sync positions: {}", e.getMessage());
            }
        }
    }

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
        log.info("[QUEUE] Shutdown complete. Executed={}, Failed={}, Rejected={}",
            totalExecuted.get(), totalFailed.get(), totalRejected.get());
    }

    /**
     * Record of a rejected order, exposed to the UI.
     */
    public record RejectionRecord(long timestamp, UUID botId, String symbol, String reason) {}
}
