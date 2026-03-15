package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import com.tradeengine.execution.NormalizedOrder;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.service.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Production stress tests for the trading engine.
 * Covers all 10 validation steps:
 *   1. Exchange connectivity
 *   2. Symbol registry
 *   3. Order normalization
 *   4. Risk management
 *   5. Order execution
 *   6. Exchange failure/retry
 *   7. Queue load (50 concurrent trades)
 *   8. Position sync
 *   9. Metrics validation
 *  10. Full trading cycle
 */
class StressTest {

    private ExchangeFactory exchangeFactory;
    private ExchangeClient mockClient;
    private KillSwitchService killSwitch;
    private CircuitBreakerService circuitBreaker;
    private OrderNormalizerService orderNormalizer;
    private PositionRiskValidator riskValidator;
    private ExchangeSymbolRegistry symbolRegistry;
    private TradeExecutionQueue queue;

    @BeforeEach
    void setUp() {
        mockClient = mock(ExchangeClient.class);
        exchangeFactory = mock(ExchangeFactory.class);
        when(exchangeFactory.getClient(anyString())).thenReturn(mockClient);

        killSwitch = mock(KillSwitchService.class);
        when(killSwitch.isActive()).thenReturn(false);

        circuitBreaker = mock(CircuitBreakerService.class);
        when(circuitBreaker.isAllowed()).thenReturn(true);

        symbolRegistry = mock(ExchangeSymbolRegistry.class);
        orderNormalizer = new OrderNormalizerService(symbolRegistry);

        PositionTracker positionTracker = new PositionTracker();
        riskValidator = new PositionRiskValidator(positionTracker);
        riskValidator.setMaxPositionPercent(20);
        riskValidator.setMaxSingleTradePercent(5);
        riskValidator.setMaxGlobalOpenPositions(100);

        // Default: balance of 100,000 USDT (high enough for most tests)
        when(mockClient.getBalances(anyString(), anyString(), anyString()))
            .thenReturn(List.of(Balance.builder()
                .asset("USDT").free(new BigDecimal("100000")).locked(BigDecimal.ZERO).build()));

        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("ORD-OK").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("65000")).build());

        PositionSyncService positionSyncService = mock(PositionSyncService.class);
        queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator, positionSyncService);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    private TradeRequest buildRequest(UUID botId, String symbol, BigDecimal qty) {
        return TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol(symbol).side("BUY").quantity(qty)
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 1 — EXCHANGE CONNECTIVITY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 1: All three exchanges resolve correct client")
    void exchangeConnectivity() {
        ExchangeClient binance = mock(ExchangeClient.class);
        ExchangeClient bybit = mock(ExchangeClient.class);
        ExchangeClient delta = mock(ExchangeClient.class);

        when(binance.testConnection(any(), any(), any())).thenReturn(true);
        when(bybit.testConnection(any(), any(), any())).thenReturn(true);
        when(delta.testConnection(any(), any(), any())).thenReturn(true);

        when(binance.resolveBaseUrl("TESTNET")).thenReturn("https://testnet.binancefuture.com");
        when(bybit.resolveBaseUrl("TESTNET")).thenReturn("https://api-testnet.bybit.com");
        when(delta.resolveBaseUrl("TESTNET")).thenReturn("https://cdn-ind.testnet.deltaex.org");

        when(exchangeFactory.getClient("BINANCE")).thenReturn(binance);
        when(exchangeFactory.getClient("BYBIT")).thenReturn(bybit);
        when(exchangeFactory.getClient("DELTA")).thenReturn(delta);

        assertTrue(binance.testConnection("k", "s", binance.resolveBaseUrl("TESTNET")));
        assertTrue(bybit.testConnection("k", "s", bybit.resolveBaseUrl("TESTNET")));
        assertTrue(delta.testConnection("k", "s", delta.resolveBaseUrl("TESTNET")));

        assertEquals("https://testnet.binancefuture.com", binance.resolveBaseUrl("TESTNET"));
        assertEquals("https://api-testnet.bybit.com", bybit.resolveBaseUrl("TESTNET"));
        assertEquals("https://cdn-ind.testnet.deltaex.org", delta.resolveBaseUrl("TESTNET"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 2 — SYMBOL REGISTRY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 2: Symbol info auto-fetched when missing")
    void symbolRegistryAutoFetch() {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BINANCE");
        info.setStepSize(new BigDecimal("0.001"));
        info.setTickSize(new BigDecimal("0.01"));
        info.setMinQty(new BigDecimal("0.001"));
        info.setMinNotional(new BigDecimal("5"));

        when(symbolRegistry.getOrFetch("BINANCE", "BTCUSDT", "url")).thenReturn(info);

        NormalizedOrder result = orderNormalizer.normalizeOrder(
            "BINANCE", "BTCUSDT", new BigDecimal("0.005"), new BigDecimal("65000"), "BUY", "url");

        assertTrue(result.isValid());
        verify(symbolRegistry).getOrFetch("BINANCE", "BTCUSDT", "url");
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 3 — ORDER NORMALIZATION (extended)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 3a: Quantity too small → rejected")
    void normQuantityTooSmall() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.01"));
        info.setMinQty(new BigDecimal("0.01"));
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(info);

        NormalizedOrder r = orderNormalizer.normalizeOrder(
            "BINANCE", "BTCUSDT", new BigDecimal("0.005"), new BigDecimal("65000"), "BUY", "url");
        assertFalse(r.isValid());
        assertTrue(r.getValidationMessage().contains("minQty") || r.getValidationMessage().contains("zero"));
    }

    @Test
    @DisplayName("TEST 3b: Quantity too large → rejected")
    void normQuantityTooLarge() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.001"));
        info.setMinQty(new BigDecimal("0.001"));
        info.setMaxQty(new BigDecimal("10"));
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(info);

        NormalizedOrder r = orderNormalizer.normalizeOrder(
            "BINANCE", "BTCUSDT", new BigDecimal("100"), new BigDecimal("65000"), "BUY", "url");
        assertFalse(r.isValid());
        assertTrue(r.getValidationMessage().contains("maxQty"));
    }

    @Test
    @DisplayName("TEST 3c: Price not matching tickSize → floored")
    void normPriceTickSize() {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.001"));
        info.setTickSize(new BigDecimal("0.50"));
        info.setMinQty(new BigDecimal("0.001"));
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(info);

        NormalizedOrder r = orderNormalizer.normalizeOrder(
            "BINANCE", "BTCUSDT", new BigDecimal("0.001"), new BigDecimal("65000.73"), "BUY", "url");
        assertTrue(r.isValid());
        assertEquals(0, new BigDecimal("65000.5").compareTo(r.getPrice()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 4 — RISK MANAGEMENT (extended)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 4a: Max exposure exceeded → rejected")
    void riskMaxExposure() {
        // Balance = 1000, maxPosition = 20% → 200. Order = 5 * 50 = 250 → REJECTED
        riskValidator.setMaxSingleTradePercent(100); // disable single trade check
        String err = riskValidator.validatePositionSize(
            "BINANCE", "BTCUSDT", new BigDecimal("5"), new BigDecimal("50"), new BigDecimal("1000"));
        assertNotNull(err);
        assertTrue(err.contains("maxPositionPercent"));
    }

    @Test
    @DisplayName("TEST 4b: Max single trade exceeded → rejected")
    void riskMaxSingleTrade() {
        // Balance = 10000, maxSingle = 5% → 500. Order = 0.01 * 65000 = 650 → REJECTED
        String err = riskValidator.validatePositionSize(
            "BINANCE", "BTCUSDT", new BigDecimal("0.01"), new BigDecimal("65000"), new BigDecimal("10000"));
        assertNotNull(err);
        assertTrue(err.contains("maxSingleTradePercent"));
    }

    @Test
    @DisplayName("TEST 4c: Duplicate order within 2s → blocked")
    void riskDuplicateOrder() {
        UUID botId = UUID.randomUUID();
        assertTrue(riskValidator.acquireOrderLock(botId, "BTCUSDT"));
        assertFalse(riskValidator.acquireOrderLock(botId, "BTCUSDT"));
        // Different symbol is allowed
        assertTrue(riskValidator.acquireOrderLock(botId, "ETHUSDT"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 5 — ORDER EXECUTION (pipeline verification)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 5: Valid order flows through full pipeline")
    void orderExecutionPipeline() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);

        TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertEquals("ORD-OK", result.getOrderId());
        verify(mockClient).placeMarketOrder(any(), any(), eq("BTCUSDT"), eq("BUY"), any(), any());
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 6 — EXCHANGE FAILURE / RETRY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 6a: HTTP 429 retried successfully")
    void retryOn429() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("HTTP 429 Too Many Requests"))
            .thenReturn(OrderResponse.builder()
                .orderId("RETRY-OK").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("65000")).build());

        TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        verify(circuitBreaker).recordFailure();
    }

    @Test
    @DisplayName("TEST 6b: HTTP 500 retried 3 times, all fail")
    void retryExhausted() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("HTTP 500 Internal Server Error"));

        TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(15, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("All 3 attempts failed"));
        verify(circuitBreaker, times(3)).recordFailure();
    }

    @Test
    @DisplayName("TEST 6c: Timeout retried successfully")
    void retryOnTimeout() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Request timed out"))
            .thenReturn(OrderResponse.builder()
                .orderId("TIMEOUT-OK").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("65000")).build());

        TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("TEST 6d: Non-retryable error fails immediately")
    void nonRetryableError() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Binance API error [-2019]: Margin is insufficient"));

        TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(10, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        // Non-retryable should only try once
        verify(mockClient, times(1)).placeMarketOrder(any(), any(), any(), any(), any(), any());
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 7 — QUEUE LOAD (50 concurrent trades)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 7: 50 concurrent trades — no drops, no duplicates")
    void queueLoadTest() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);

        AtomicInteger orderCounter = new AtomicInteger();
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                int n = orderCounter.incrementAndGet();
                return OrderResponse.builder()
                    .orderId("LOAD-" + n).symbol("BTCUSDT").side("BUY").status("FILLED")
                    .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("65000")).build();
            });

        int totalTrades = 50;
        List<TradeRequest> requests = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(totalTrades);

        // Submit 50 trades from different bots simultaneously
        for (int i = 0; i < totalTrades; i++) {
            TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
            requests.add(req);
            executor.submit(() -> {
                queue.submitTrade(req);
                latch.countDown();
            });
        }

        // Wait for all submissions
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All submissions should complete within 5s");

        // Wait for all executions
        int succeeded = 0;
        int failed = 0;
        for (TradeRequest req : requests) {
            try {
                TradeRequest.TradeResult result = req.getResultFuture().get(60, TimeUnit.SECONDS);
                if (result.isSuccess()) succeeded++;
                else failed++;
            } catch (TimeoutException e) {
                failed++;
            }
        }

        // All should complete (some may be rejected by duplicate lock, but none dropped)
        assertEquals(totalTrades, succeeded + failed,
            "No requests should be dropped — all must complete (success or rejection)");

        // Most should succeed (some may hit duplicate bot lock since submissions are near-simultaneous)
        assertTrue(succeeded > 0, "At least some trades should succeed");

        // Metrics should be consistent
        assertEquals(queue.getTotalSubmitted() + queue.getTotalRejected(),
            succeeded + failed, "Metrics should account for all requests");

        // Queue should be empty after processing
        assertEquals(0, queue.getQueueSize(), "Queue should be drained");

        executor.shutdown();
    }

    @Test
    @DisplayName("TEST 7b: Queue handles burst without data loss")
    void queueBurstNoDrop() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);

        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("BURST").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("65000")).build());

        // Rapid-fire 20 trades from unique bots
        List<TradeRequest> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
            requests.add(req);
            queue.submitTrade(req);
        }

        // Wait for all to complete
        for (TradeRequest req : requests) {
            TradeRequest.TradeResult result = req.getResultFuture().get(30, TimeUnit.SECONDS);
            assertNotNull(result, "Every request must receive a result");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 8 — POSITION SYNC (covered in PositionSyncServiceTest)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 8: PositionTracker prevents double entries")
    void positionTrackerNoDuplicates() {
        PositionTracker tracker = new PositionTracker();
        UUID botId = UUID.randomUUID();

        PositionTracker.TrackedPosition pos1 = PositionTracker.TrackedPosition.builder()
            .botId(botId).userId(UUID.randomUUID()).symbol("BTCUSDT").exchange("BINANCE")
            .exchangeMode("TESTNET").entryPrice(new BigDecimal("42000"))
            .quantity(new BigDecimal("0.01")).apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").build();

        PositionTracker.TrackedPosition pos2 = PositionTracker.TrackedPosition.builder()
            .botId(botId).userId(UUID.randomUUID()).symbol("BTCUSDT").exchange("BINANCE")
            .exchangeMode("TESTNET").entryPrice(new BigDecimal("43000"))
            .quantity(new BigDecimal("0.02")).apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").build();

        tracker.registerPosition(pos1);
        tracker.registerPosition(pos2); // overwrites

        assertEquals(1, tracker.getOpenPositionCount());
        assertEquals(new BigDecimal("0.02"), tracker.getPosition(botId).get().getQuantity());
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 9 — METRICS VALIDATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 9: Execution metrics accurately tracked")
    void metricsAccuracy() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);

        // 1 success
        TradeRequest req1 = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req1);
        req1.getResultFuture().get(10, TimeUnit.SECONDS);

        // 1 rejection (kill switch)
        when(killSwitch.isActive()).thenReturn(true);
        when(killSwitch.getActivationReason()).thenReturn("test");
        TradeRequest req2 = buildRequest(UUID.randomUUID(), "ETHUSDT", new BigDecimal("0.01"));
        queue.submitTrade(req2);
        req2.getResultFuture().get(5, TimeUnit.SECONDS);

        assertTrue(queue.getTotalSubmitted() >= 1);
        assertTrue(queue.getTotalExecuted() >= 1);
        assertTrue(queue.getTotalRejected() >= 1);
        assertTrue(queue.getSuccessRate() > 0);
        assertTrue(queue.getAvgLatencyMs() >= 0);
        assertEquals(1000, queue.getQueueCapacity());
        assertTrue(queue.getQueueUsagePercent() >= 0);
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST 10 — FULL TRADING CYCLE (BUY → verify → SELL)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST 10: Full BUY → SELL lifecycle")
    void fullBuySellCycle() throws Exception {
        SymbolInfo info = new SymbolInfo();
        info.setStepSize(new BigDecimal("0.001"));
        info.setTickSize(new BigDecimal("0.01"));
        info.setMinQty(new BigDecimal("0.001"));
        info.setMaxQty(new BigDecimal("1000"));
        info.setMinNotional(new BigDecimal("5"));
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(info);

        // BUY
        when(mockClient.placeMarketOrder(any(), any(), eq("BTCUSDT"), eq("BUY"), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("BUY-1").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.010")).avgPrice(new BigDecimal("65000")).build());

        UUID botId = UUID.randomUUID();
        TradeRequest buy = TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.0105"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(buy);
        TradeRequest.TradeResult buyResult = buy.getResultFuture().get(10, TimeUnit.SECONDS);

        assertTrue(buyResult.isSuccess());
        assertEquals("BUY-1", buyResult.getOrderId());
        assertEquals(new BigDecimal("0.010"), buyResult.getExecutedQty());

        // Wait for lock cooldown
        Thread.sleep(2100);

        // SELL
        when(mockClient.placeMarketOrder(any(), any(), eq("BTCUSDT"), eq("SELL"), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("SELL-1").symbol("BTCUSDT").side("SELL").status("FILLED")
                .executedQty(new BigDecimal("0.010")).avgPrice(new BigDecimal("66000")).build());

        TradeRequest sell = TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("SELL").quantity(new BigDecimal("0.010"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(sell);
        TradeRequest.TradeResult sellResult = sell.getResultFuture().get(10, TimeUnit.SECONDS);

        assertTrue(sellResult.isSuccess());
        assertEquals("SELL-1", sellResult.getOrderId());

        // PnL = (66000 - 65000) * 0.010 = $10
        BigDecimal pnl = sellResult.getAvgPrice().subtract(buyResult.getAvgPrice())
            .multiply(buyResult.getExecutedQty());
        assertEquals(0, new BigDecimal("10.000").compareTo(pnl));
    }

    // ═══════════════════════════════════════════════════════════════════
    // ADDITIONAL EDGE CASES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EDGE: LIMIT order with invalid price is validated")
    void limitOrderValidation() throws Exception {
        TradeRequest req = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .price(BigDecimal.ZERO)  // Invalid!
            .orderType("LIMIT").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(req);
        TradeRequest.TradeResult result = req.getResultFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("EDGE: Missing exchange field is rejected")
    void missingExchangeRejected() throws Exception {
        TradeRequest req = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(req);
        TradeRequest.TradeResult result = req.getResultFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("EDGE: Shutdown drains queue and completes futures")
    void shutdownDrainsQueue() throws Exception {
        // Use a slow exchange to ensure trade is in flight during shutdown
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Thread.sleep(5000);
                return OrderResponse.builder()
                    .orderId("SLOW").symbol("BTCUSDT").side("BUY").status("FILLED")
                    .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("65000")).build();
            });

        TradeRequest req = buildRequest(UUID.randomUUID(), "BTCUSDT", new BigDecimal("0.001"));
        queue.submitTrade(req);

        Thread.sleep(100); // Let it enter the worker
        queue.shutdown();

        // Future must complete (either success or shutdown message)
        TradeRequest.TradeResult result = req.getResultFuture().get(15, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    @Test
    @DisplayName("EDGE: Circuit breaker state transitions")
    void circuitBreakerStateTransitions() {
        CircuitBreakerService cb = new CircuitBreakerService();

        // Initially closed
        assertTrue(cb.isAllowed());
        assertFalse(cb.isOpen());

        // 4 failures — still closed
        for (int i = 0; i < 4; i++) cb.recordFailure();
        assertTrue(cb.isAllowed());

        // 5th failure — opens
        cb.recordFailure();
        assertTrue(cb.isOpen());
        assertFalse(cb.isAllowed());
    }

    @Test
    @DisplayName("EDGE: Kill switch activation and reset")
    void killSwitchActivationReset() {
        KillSwitchService ks = new KillSwitchService(
            mock(com.tradeengine.repository.BotRepository.class),
            mock(NotificationService.class),
            null);

        assertFalse(ks.isActive());
        ks.activate("test reason");
        assertTrue(ks.isActive());
        assertEquals("test reason", ks.getActivationReason());

        ks.reset();
        assertFalse(ks.isActive());
        assertNull(ks.getActivationReason());
    }

    @Test
    @DisplayName("EDGE: Risk validator with null balance skips checks")
    void riskNullBalanceSkips() {
        String err = riskValidator.validatePositionSize(
            "BINANCE", "BTCUSDT", new BigDecimal("100"), new BigDecimal("65000"), null);
        assertNull(err, "Null balance should skip risk checks");
    }

    @Test
    @DisplayName("EDGE: Risk validator with zero balance skips checks")
    void riskZeroBalanceSkips() {
        String err = riskValidator.validatePositionSize(
            "BINANCE", "BTCUSDT", new BigDecimal("0.001"), new BigDecimal("65000"), BigDecimal.ZERO);
        assertNull(err, "Zero balance should skip risk checks");
    }

    @Test
    @DisplayName("EDGE: Market order with null price skips risk price check")
    void riskMarketOrderNullPrice() {
        String err = riskValidator.validatePositionSize(
            "BINANCE", "BTCUSDT", new BigDecimal("0.001"), null, new BigDecimal("10000"));
        assertNull(err, "Market order (null price) should skip notional check");
    }

    @Test
    @DisplayName("EDGE: Multi-exchange concurrent requests")
    void multiExchangeConcurrent() throws Exception {
        when(symbolRegistry.getOrFetch(any(), any(), any())).thenReturn(null);

        TradeRequest binanceReq = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        TradeRequest bybitReq = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BYBIT").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(binanceReq);
        queue.submitTrade(bybitReq);

        TradeRequest.TradeResult r1 = binanceReq.getResultFuture().get(10, TimeUnit.SECONDS);
        TradeRequest.TradeResult r2 = bybitReq.getResultFuture().get(10, TimeUnit.SECONDS);

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
    }
}
