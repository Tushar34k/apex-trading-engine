package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import com.tradeengine.execution.NormalizedOrder;
import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import com.tradeengine.service.OrderNormalizerService;
import com.tradeengine.service.PositionRiskValidator;
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
 * retry logic, order normalization, duplicate order lock, and queue capacity.
 */
class TradeExecutionQueueTest {

    private ExchangeFactory exchangeFactory;
    private KillSwitchService killSwitch;
    private CircuitBreakerService circuitBreaker;
    private OrderNormalizerService orderNormalizer;
    private PositionRiskValidator riskValidator;
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

        killSwitch = mock(KillSwitchService.class);
        when(killSwitch.isActive()).thenReturn(false);

        circuitBreaker = mock(CircuitBreakerService.class);
        when(circuitBreaker.isAllowed()).thenReturn(true);

        exchangeFactory = mock(ExchangeFactory.class);
        when(exchangeFactory.getClient(anyString())).thenReturn(mockClient);

        orderNormalizer = mock(OrderNormalizerService.class);
        when(orderNormalizer.normalizeOrder(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> NormalizedOrder.builder()
                .exchange(inv.getArgument(0)).symbol(inv.getArgument(1)).side(inv.getArgument(4))
                .rawQuantity(inv.getArgument(2)).rawPrice(inv.getArgument(3))
                .quantity(inv.getArgument(2)).price(inv.getArgument(3))
                .valid(true).build());

        riskValidator = new PositionRiskValidator();
    }

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

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);
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

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);
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

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);

        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);

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
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Thread.sleep(3000);
                return OrderResponse.builder()
                    .orderId("ORD-1").symbol("BTCUSDT").side("BUY").status("FILLED")
                    .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("42000"))
                    .build();
            });

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);

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
        Thread.sleep(100);
        queue.submitTrade(req2);

        TradeRequest.TradeResult result2 = req2.getResultFuture().get(2, TimeUnit.SECONDS);
        assertFalse(result2.isSuccess());
        assertTrue(result2.getErrorMessage().contains("pending trade") ||
                   result2.getErrorMessage().contains("cooldown"));

        queue.shutdown();
    }

    @Test
    @DisplayName("Validation rejects invalid request")
    void validationRejectsInvalid() throws Exception {
        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);

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
        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);

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

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);
        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);
        req.getResultFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, queue.getTotalSubmitted());
        assertEquals(1, queue.getTotalExecuted());
        assertEquals(0, queue.getTotalFailed());

        queue.shutdown();
    }

    @Test
    @DisplayName("Normalization rejection prevents exchange call")
    void normalizationRejection() throws Exception {
        when(orderNormalizer.normalizeOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(NormalizedOrder.builder()
                .exchange("BINANCE").symbol("BTCUSDT").side("BUY")
                .rawQuantity(new BigDecimal("0.0001")).rawPrice(new BigDecimal("65000"))
                .quantity(new BigDecimal("0")).price(new BigDecimal("65000"))
                .valid(false).validationMessage("Quantity below minQty").build());

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);
        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("normalization failed"));
        verify(mockClient, never()).placeMarketOrder(any(), any(), any(), any(), any(), any());

        assertTrue(queue.getTotalRejected() > 0);

        queue.shutdown();
    }

    @Test
    @DisplayName("Invalid order response is treated as failure")
    void invalidOrderResponse() throws Exception {
        when(mockClient.placeMarketOrder(any(), any(), any(), any(), any(), any()))
            .thenReturn(OrderResponse.builder()
                .orderId(null).symbol("BTCUSDT").side("BUY").status("FILLED")
                .executedQty(new BigDecimal("0.001")).avgPrice(new BigDecimal("42000"))
                .build());

        TradeExecutionQueue queue = new TradeExecutionQueue(exchangeFactory, killSwitch, circuitBreaker, orderNormalizer, riskValidator);
        TradeRequest req = buildRequest("BUY");
        queue.submitTrade(req);

        TradeRequest.TradeResult result = req.getResultFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("missing orderId"));

        queue.shutdown();
    }
}
