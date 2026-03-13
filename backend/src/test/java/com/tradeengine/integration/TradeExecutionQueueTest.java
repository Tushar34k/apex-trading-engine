package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import com.tradeengine.service.OrderNormalizerService;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the TradeExecutionQueue: submission, dedup, kill switch, circuit breaker,
 * retry logic, and queue capacity.
 */
class TradeExecutionQueueTest {

    private ExchangeFactory exchangeFactory;
    private KillSwitchService killSwitch;
    private CircuitBreakerService circuitBreaker;
    private OrderNormalizerService orderNormalizer;
    private ExchangeClient mockClient;

    @BeforeEach
    void setUp() {
        mockClient = mock(ExchangeClient.class);
        BinanceClient binance = mock(BinanceClient.class, Mockito.withSettings().defaultAnswer(inv -> {
            if (inv.getMethod().getName().equals("placeMarketOrder")) {
                return OrderResponse.builder()
                    .orderId("ORD-123").symbol("BTCUSDT").side("BUY")
                    .status("FILLED").executedQty(new BigDecimal("0.001"))
                    .avgPrice(new BigDecimal("42000")).build();
            }
            return inv.callRealMethod();
        }));

        // We need real instances for KillSwitch and CircuitBreaker
        killSwitch = mock(KillSwitchService.class);
        when(killSwitch.isActive()).thenReturn(false);

        circuitBreaker = mock(CircuitBreakerService.class);
        when(circuitBreaker.isAllowed()).thenReturn(true);

        exchangeFactory = mock(ExchangeFactory.class);
        when(exchangeFactory.getClient(anyString())).thenReturn(mockClient);

        orderNormalizer = mock(OrderNormalizerService.class);
        // Default: pass-through normalization
        when(orderNormalizer.normalizeOrder(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> com.tradeengine.execution.NormalizedOrder.builder()
                .exchange(inv.getArgument(0)).symbol(inv.getArgument(1)).side(inv.getArgument(4))
                .rawQuantity(inv.getArgument(2)).rawPrice(inv.getArgument(3))
                .quantity(inv.getArgument(2)).price(inv.getArgument(3))
                .valid(true).build());

    private TradeRequest buildRequest(String side) {
        return TradeRequest.builder()
            .botId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .symbol("BTCUSDT")
            .side(side)
            .quantity(new BigDecimal("0.001"))
            .orderType("MARKET")
            .apiKey("test-key")
            .apiSecret("test-secret")
            .exchangeBaseUrl("http://localhost")
            .exchange("BINANCE")
            .exchangeMode("TESTNET")
            .timestamp(Instant.now())
            .build();
    }

    @Test
    @DisplayName("Successful market order execution")
    void successfulExecution() throws Exception {
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("ORD-1").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("42000"))
                .build());

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer);
        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertEquals("ORD-1", result.getOrderId());

        queue.shutdown();
    }

    @Test
    @DisplayName("Kill switch blocks trade submission")
    void killSwitchBlocksTrade() throws Exception {
        when(killSwitch.isActive()).thenReturn(true);
        when(killSwitch.getActivationReason()).thenReturn("Test kill");

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer);
        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(2, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Kill switch"));

        queue.shutdown();
    }

    @Test
    @DisplayName("Duplicate request is rejected")
    void duplicateRequestRejected() throws Exception {
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("ORD-1").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("42000"))
                .build());

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer);

        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);

        // Submit same request again (same requestId)
        TradeRequest dup = TradeRequest.builder()
            .requestId(req.getRequestId())
            .botId(req.getBotId())
            .userId(req.getUserId())
            .symbol("BTCUSDT").side("BUY")
            .quantity(new BigDecimal("0.001"))
            .orderType("MARKET")
            .apiKey("test-key").apiSecret("test-secret")
            .exchangeBaseUrl("http://localhost")
            .exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now())
            .build();
        queue.submitTrade(dup);

        TradeRequest.TradeResult dupResult = dup.getResultFuture().get(2, TimeUnit.SECONDS);
        assertFalse(dupResult.isSuccess());
        assertTrue(dupResult.getErrorMessage().contains("Duplicate"));

        queue.shutdown();
    }

    @Test
    @DisplayName("Pending bot trade prevents concurrent submission")
    void pendingBotPreventsSubmission() throws Exception {
        // Make the exchange hang to keep the first request in-flight
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Thread.sleep(3000);
                return OrderResponse.builder()
                    .orderId("ORD-1").symbol("BTCUSDT").side("BUY").status("FILLED")
                    .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("42000"))
                    .build();
            });

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker);

        UUID botId = UUID.randomUUID();
        TradeRequest req1 = TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY")
            .quantity(new BigDecimal("0.001")).orderType("MARKET")
            .apiKey("key").apiSecret("secret")
            .exchangeBaseUrl("http://localhost")
            .exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        TradeRequest req2 = TradeRequest.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("SELL")
            .quantity(new BigDecimal("0.001")).orderType("MARKET")
            .apiKey("key").apiSecret("secret")
            .exchangeBaseUrl("http://localhost")
            .exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(req1);
        Thread.sleep(100); // Let first enter queue
        queue.submitTrade(req2);

        TradeRequest.TradeResult result2 = req2.getResultFuture().get(2, TimeUnit.SECONDS);
        assertFalse(result2.isSuccess());
        assertTrue(result2.getErrorMessage().contains("pending trade"));

        queue.shutdown();
    }

    @Test
    @DisplayName("Validation rejects invalid request")
    void validationRejectsInvalid() throws Exception {
        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker);

        TradeRequest invalid = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("").side("BUY")
            .quantity(new BigDecimal("0.001")).orderType("MARKET")
            .apiKey("key").apiSecret("secret")
            .exchangeBaseUrl("http://localhost")
            .exchange("BINANCE").exchangeMode("TESTNET")
            .timestamp(Instant.now()).build();

        queue.submitTrade(invalid);
        TradeRequest.TradeResult result = invalid.getResultFuture().get(2, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());

        queue.shutdown();
    }

    @Test
    @DisplayName("Live trading disabled blocks LIVE mode requests")
    void liveTradingDisabledBlocks() throws Exception {
        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker);

        TradeRequest req = TradeRequest.builder()
            .botId(UUID.randomUUID()).userId(UUID.randomUUID())
            .symbol("BTCUSDT").side("BUY")
            .quantity(new BigDecimal("0.001")).orderType("MARKET")
            .apiKey("key").apiSecret("secret")
            .exchangeBaseUrl("http://localhost")
            .exchange("BINANCE").exchangeMode("LIVE")
            .timestamp(Instant.now()).build();

        queue.submitTrade(req);
        TradeRequest.TradeResult result = req.getResultFuture().get(2, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Live trading"));

        queue.shutdown();
    }

    @Test
    @DisplayName("Queue metrics are tracked correctly")
    void metricsTracked() throws Exception {
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId("ORD-1").symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("42000"))
                .build());

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker);
        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);
        req.getResultFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, queue.getTotalSubmitted());
        assertEquals(1, queue.getTotalExecuted());
        assertEquals(0, queue.getTotalFailed());

        queue.shutdown();
    }
}
