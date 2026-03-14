package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import com.tradeengine.execution.NormalizedOrder;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.service.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Full trading cycle integration test:
 *   Strategy signal → OrderNormalizerService → PositionRiskValidator
 *   → TradeExecutionQueue → ExchangeClient → Exchange API
 *
 * Validates the complete pipeline end-to-end with mocked exchange responses.
 */
class FullTradingCycleTest {

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

        riskValidator = new PositionRiskValidator();
        riskValidator.setMaxPositionPercent(20);
        riskValidator.setMaxSingleTradePercent(5);

        // Mock balance for risk validation
        when(mockClient.getBalances(anyString(), anyString(), anyString()))
            .thenReturn(List.of(Balance.builder().asset("USDT").free(new BigDecimal("10000")).locked(BigDecimal.ZERO).build()));

        queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    // ─── STEP 10: Full Bot Cycle ───

    @Test
    @DisplayName("Full cycle: signal → normalize → risk → execute → success")
    void fullTradingCycleSuccess() throws Exception {
        // Setup symbol info for normalization
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BINANCE");
        info.setStepSize(new BigDecimal("0.001"));
        info.setTickSize(new BigDecimal("0.01"));
        info.setMinQty(new BigDecimal("0.001"));
        info.setMaxQty(new BigDecimal("1000"));
        info.setMinNotional(new BigDecimal("5"));
        when(symbolRegistry.getOrFetch("BINANCE", "BTCUSDT", "https://testnet.binancefuture.com"))
            .thenReturn(info);

        // Mock exchange order response
        when(mockClient.placeMarketOrder(anyString(), anyString(), eq("BTCUSDT"), eq("BUY"), any(), anyString()))
            .thenReturn(OrderResponse.builder()
                .orderId("12345")
                .symbol("BTCUSDT")
                .side("BUY")
                .status("FILLED")
                .executedQty(new BigDecimal("0.005"))
                .avgPrice(new BigDecimal("65000.00"))
                .build());

        // Submit trade (simulates strategy signal)
        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .symbol("BTCUSDT")
            .side("BUY")
            .quantity(new BigDecimal("0.0057"))  // Will be normalized to 0.005
            .orderType("MARKET")
            .apiKey("test-key")
            .apiSecret("test-secret")
            .exchangeBaseUrl("https://testnet.binancefuture.com")
            .exchange("BINANCE")
            .exchangeMode("TESTNET")
            .timestamp(Instant.now())
            .build();

        queue.submitTrade(request);

        // Wait for execution
        TradeRequest.TradeResult result = request.getResultFuture().get(10, TimeUnit.SECONDS);

        // Verify full pipeline
        assertTrue(result.isSuccess(), "Trade should succeed");
        assertEquals("12345", result.getOrderId());
        assertEquals(new BigDecimal("0.005"), result.getExecutedQty());
        assertEquals(new BigDecimal("65000.00"), result.getAvgPrice());

        // Verify exchange was called with normalized quantity
        verify(mockClient).placeMarketOrder(eq("test-key"), eq("test-secret"),
            eq("BTCUSDT"), eq("BUY"), eq(new BigDecimal("0.005")), anyString());

        // Verify metrics
        assertEquals(1, queue.getTotalSubmitted());
        assertEquals(1, queue.getTotalExecuted());
        assertEquals(0, queue.getTotalRejected());
    }

    @Test
    @DisplayName("Order rejected by risk validator — exceeds single trade limit")
    void riskValidatorRejectsOrder() throws Exception {
        // No symbol info → raw values pass through normalizer
        when(symbolRegistry.getOrFetch(anyString(), anyString(), anyString())).thenReturn(null);

        // Balance = 10000, maxSingleTrade = 5% = 500 USDT
        // Order = 0.01 * 65000 = 650 USDT → REJECTED
        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .symbol("BTCUSDT")
            .side("BUY")
            .quantity(new BigDecimal("0.01"))
            .price(new BigDecimal("65000"))
            .orderType("LIMIT")
            .apiKey("test-key")
            .apiSecret("test-secret")
            .exchangeBaseUrl("https://testnet.binancefuture.com")
            .exchange("BINANCE")
            .exchangeMode("TESTNET")
            .timestamp(Instant.now())
            .build();

        queue.submitTrade(request);
        TradeRequest.TradeResult result = request.getResultFuture().get(10, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("maxSingleTradePercent"));
        assertTrue(queue.getTotalRejected() > 0);

        // Exchange should never be called
        verify(mockClient, never()).placeLimitOrder(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Order rejected by normalizer — below minNotional")
    void normalizerRejectsMinNotional() throws Exception {
        SymbolInfo info = new SymbolInfo();
        info.setSymbol("BTCUSDT");
        info.setExchange("BINANCE");
        info.setStepSize(new BigDecimal("0.001"));
        info.setMinQty(new BigDecimal("0.001"));
        info.setMinNotional(new BigDecimal("100"));
        when(symbolRegistry.getOrFetch(anyString(), anyString(), anyString())).thenReturn(info);

        // Order: 0.001 * 50 = 0.05 USDT, minNotional = 100 → REJECTED
        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .symbol("BTCUSDT")
            .side("BUY")
            .quantity(new BigDecimal("0.001"))
            .price(new BigDecimal("50"))
            .orderType("LIMIT")
            .apiKey("test-key")
            .apiSecret("test-secret")
            .exchangeBaseUrl("https://testnet.binancefuture.com")
            .exchange("BINANCE")
            .exchangeMode("TESTNET")
            .timestamp(Instant.now())
            .build();

        queue.submitTrade(request);
        TradeRequest.TradeResult result = request.getResultFuture().get(10, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("minNotional"));
    }

    @Test
    @DisplayName("Duplicate order blocked by 2s cooldown lock")
    void duplicateOrderBlocked() throws Exception {
        when(symbolRegistry.getOrFetch(anyString(), anyString(), anyString())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("111").symbol("BTCUSDT").side("BUY")
                .status("FILLED").executedQty(new BigDecimal("0.001"))
                .avgPrice(new BigDecimal("65000")).build());

        UUID botId = UUID.randomUUID();

        // First order
        TradeRequest req1 = TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        // Second order — same bot+symbol within 2s
        TradeRequest req2 = TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(req1);
        queue.submitTrade(req2);

        TradeRequest.TradeResult result2 = req2.getResultFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result2.isSuccess());
        assertTrue(result2.getErrorMessage().contains("cooldown"));
    }

    @Test
    @DisplayName("Kill switch blocks all orders")
    void killSwitchBlocksOrders() throws Exception {
        when(killSwitch.isActive()).thenReturn(true);
        when(killSwitch.getActivationReason()).thenReturn("Test kill");

        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(request);
        TradeRequest.TradeResult result = request.getResultFuture().get(5, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Kill switch"));
    }

    @Test
    @DisplayName("Live trading blocked when disabled")
    void liveTradingBlocked() throws Exception {
        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("LIVE")
            .timestamp(Instant.now()).build();

        queue.submitTrade(request);
        TradeRequest.TradeResult result = request.getResultFuture().get(5, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Live trading is disabled"));
    }

    @Test
    @DisplayName("Exchange error triggers retry and circuit breaker")
    void retryOnTransientError() throws Exception {
        when(symbolRegistry.getOrFetch(anyString(), anyString(), anyString())).thenReturn(null);

        // First 2 calls fail with 500, third succeeds
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("HTTP 500 Internal Server Error"))
            .thenThrow(new RuntimeException("HTTP 500 Internal Server Error"))
            .thenReturn(OrderResponse.builder()
                .orderId("999").symbol("BTCUSDT").side("BUY")
                .status("FILLED").executedQty(new BigDecimal("0.001"))
                .avgPrice(new BigDecimal("65000")).build());

        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(request);
        TradeRequest.TradeResult result = request.getResultFuture().get(15, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals("999", result.getOrderId());

        // Circuit breaker should have recorded 2 failures
        verify(circuitBreaker, times(2)).recordFailure();
    }

    @Test
    @DisplayName("Invalid exchange response fails validation")
    void invalidExchangeResponseFails() throws Exception {
        when(symbolRegistry.getOrFetch(anyString(), anyString(), anyString())).thenReturn(null);

        // Response missing orderId
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId(null)  // Missing!
                .symbol("BTCUSDT").side("BUY")
                .status("NEW").executedQty(BigDecimal.ZERO).build());

        TradeRequest request = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(request);
        TradeRequest.TradeResult result = request.getResultFuture().get(10, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("missing orderId"));
    }

    @Test
    @DisplayName("Metrics track all outcomes correctly")
    void metricsTracking() throws Exception {
        when(symbolRegistry.getOrFetch(anyString(), anyString(), anyString())).thenReturn(null);
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("OK1").symbol("BTCUSDT").side("BUY")
                .status("FILLED").executedQty(new BigDecimal("0.001"))
                .avgPrice(new BigDecimal("65000")).build());

        // Submit a successful order
        TradeRequest req = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY").quantity(new BigDecimal("0.001"))
            .orderType("MARKET").apiKey("k").apiSecret("s")
            .exchangeBaseUrl("url").exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(req);
        req.getResultFuture().get(10, TimeUnit.SECONDS);

        assertTrue(queue.getTotalSubmitted() >= 1);
        assertTrue(queue.getTotalExecuted() >= 1);
        assertTrue(queue.getSuccessRate() > 0);
        assertTrue(queue.getAvgLatencyMs() >= 0);
        assertEquals(1000, queue.getQueueCapacity());
    }
}
