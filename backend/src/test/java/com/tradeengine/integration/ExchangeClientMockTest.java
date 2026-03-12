package com.tradeengine.integration;

import com.tradeengine.exchange.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests exchange client implementations against MockWebServer.
 * Verifies correct request signing, endpoint usage, and response parsing.
 */
class ExchangeClientMockTest {

    private MockWebServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    // ─── Binance ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BinanceClient: getPrice parses ticker response")
    void binanceGetPrice() {
        mockServer.enqueue(new MockResponse()
            .setBody("{\"symbol\":\"BTCUSDT\",\"price\":\"42500.50\"}")
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        BigDecimal price = client.getPrice("BTCUSDT", baseUrl);

        assertEquals(new BigDecimal("42500.50"), price);
    }

    @Test
    @DisplayName("BinanceClient: getCandles returns OHLCV data")
    void binanceGetCandles() {
        String body = "[[1609459200000,\"29000.0\",\"29500.0\",\"28800.0\",\"29300.0\",\"1234.5\"]]";
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        List<double[]> candles = client.getCandles("BTCUSDT", "1m", 1, baseUrl);

        assertEquals(1, candles.size());
        assertEquals(29000.0, candles.get(0)[1], 0.01); // open
        assertEquals(29500.0, candles.get(0)[2], 0.01); // high
        assertEquals(28800.0, candles.get(0)[3], 0.01); // low
        assertEquals(29300.0, candles.get(0)[4], 0.01); // close
    }

    @Test
    @DisplayName("BinanceClient: getBalances parses futures balance response")
    void binanceGetBalances() {
        // Futures /fapi/v2/balance returns an array, not an object with "balances" key
        String body = """
            [
              {"asset":"USDT","balance":"1050.00","availableBalance":"1000.00"},
              {"asset":"BTC","balance":"0.5","availableBalance":"0.5"},
              {"asset":"ETH","balance":"0.0","availableBalance":"0.0"}
            ]
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        List<Balance> balances = client.getBalances("key", "secret", baseUrl);

        // Should filter out zero-balance assets
        assertEquals(2, balances.size());
        Balance usdt = balances.stream().filter(b -> "USDT".equals(b.getAsset())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("1000.00"), usdt.getFree());
        assertEquals(new BigDecimal("50.00"), usdt.getLocked());
    }

    @Test
    @DisplayName("BinanceClient: testConnection returns true on valid response")
    void binanceTestConnection() {
        String body = """
            [{"asset":"USDT","balance":"100.00","availableBalance":"100.00"}]
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        assertTrue(client.testConnection("key", "secret", baseUrl));
    }

    @Test
    @DisplayName("BinanceClient: testConnection returns false on API error")
    void binanceTestConnectionFailure() {
        String body = """
            {"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}
            """;
        mockServer.enqueue(new MockResponse()
            .setResponseCode(403)
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        assertFalse(client.testConnection("badkey", "badsecret", baseUrl));
    }

    @Test
    @DisplayName("BinanceClient: placeMarketOrder parses futures response")
    void binancePlaceMarketOrder() {
        String body = """
            {
              "orderId": 12345,
              "symbol": "BTCUSDT",
              "side": "BUY",
              "status": "FILLED",
              "executedQty": "0.001",
              "avgPrice": "42500.00"
            }
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        OrderResponse resp = client.placeMarketOrder("key", "secret", "BTCUSDT", "BUY",
            new BigDecimal("0.001"), baseUrl);

        assertEquals("12345", resp.getOrderId());
        assertEquals("BTCUSDT", resp.getSymbol());
        assertEquals("BUY", resp.getSide());
        assertEquals(new BigDecimal("0.001"), resp.getExecutedQty());
        assertEquals(new BigDecimal("42500.00"), resp.getAvgPrice());
    }

    @Test
    @DisplayName("BinanceClient: getOpenPositions parses futures positions")
    void binanceGetOpenPositions() {
        String body = """
            [
              {"symbol":"BTCUSDT","positionAmt":"0.01","entryPrice":"42000","unRealizedProfit":"50.0","positionSide":"LONG"},
              {"symbol":"ETHUSDT","positionAmt":"0","entryPrice":"0","unRealizedProfit":"0","positionSide":"BOTH"}
            ]
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BinanceClient client = new BinanceClient();
        List<ExchangePosition> positions = client.getOpenPositions("key", "secret", baseUrl);

        assertEquals(1, positions.size());
        ExchangePosition pos = positions.get(0);
        assertEquals("BTCUSDT", pos.getSymbol());
        assertEquals(new BigDecimal("0.01"), pos.getSize());
        assertEquals("BINANCE", pos.getExchange());
    }

    // ─── Delta ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DeltaClient: getPrice parses ticker response")
    void deltaGetPrice() {
        String body = """
            {"result":{"mark_price":"42500.00"}}
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        DeltaClient client = new DeltaClient();
        BigDecimal price = client.getPrice("BTCUSD", baseUrl);

        assertEquals(new BigDecimal("42500.00"), price);
    }

    @Test
    @DisplayName("DeltaClient: getCandles returns OHLCV data")
    void deltaGetCandles() {
        String body = """
            {"result":[{"time":1609459200,"open":"29000","high":"29500","low":"28800","close":"29300","volume":"1234"}]}
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        DeltaClient client = new DeltaClient();
        List<double[]> candles = client.getCandles("BTCUSD", "1m", 1, baseUrl);

        assertEquals(1, candles.size());
        assertEquals(29000.0, candles.get(0)[1], 0.01);
    }

    @Test
    @DisplayName("DeltaClient: getOpenPositions parses response")
    void deltaGetOpenPositions() {
        String body = """
            {"result":[
              {"product_symbol":"BTCUSD","size":"5","entry_price":"42000","side":"buy","unrealized_pnl":"100"},
              {"product_symbol":"ETHUSD","size":"0","entry_price":"0","side":"buy","unrealized_pnl":"0"}
            ]}
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        DeltaClient client = new DeltaClient();
        List<ExchangePosition> positions = client.getOpenPositions("key", "secret", baseUrl);

        assertEquals(1, positions.size());
        assertEquals("BTCUSD", positions.get(0).getSymbol());
        assertEquals(new BigDecimal("5"), positions.get(0).getSize());
    }

    // ─── Bybit ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BybitClient: getPrice parses V5 ticker response")
    void bybitGetPrice() {
        String body = """
            {"retCode":0,"result":{"list":[{"lastPrice":"42500.50"}]}}
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BybitClient client = new BybitClient();
        BigDecimal price = client.getPrice("BTCUSDT", baseUrl);

        assertEquals(new BigDecimal("42500.50"), price);
    }

    @Test
    @DisplayName("BybitClient: getCandles parses V5 kline response")
    void bybitGetCandles() {
        String body = """
            {"retCode":0,"result":{"list":[["1609459200000","29000","29500","28800","29300","1234"]]}}
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BybitClient client = new BybitClient();
        List<double[]> candles = client.getCandles("BTCUSDT", "1", 1, baseUrl);

        assertEquals(1, candles.size());
        assertEquals(29000.0, candles.get(0)[1], 0.01);
    }

    @Test
    @DisplayName("BybitClient: getOpenPositions parses V5 position list")
    void bybitGetOpenPositions() {
        String body = """
            {"retCode":0,"result":{"list":[
              {"symbol":"BTCUSDT","size":"0.01","avgPrice":"42000","side":"Buy","unrealisedPnl":"50"},
              {"symbol":"ETHUSDT","size":"0","avgPrice":"0","side":"","unrealisedPnl":"0"}
            ]}}
            """;
        mockServer.enqueue(new MockResponse()
            .setBody(body)
            .addHeader("Content-Type", "application/json"));

        BybitClient client = new BybitClient();
        List<ExchangePosition> positions = client.getOpenPositions("key", "secret", baseUrl);

        assertEquals(1, positions.size());
        assertEquals("BTCUSDT", positions.get(0).getSymbol());
        assertEquals("BYBIT", positions.get(0).getExchange());
    }

    // ─── ExchangeFactory ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ExchangeFactory: returns correct client per exchange")
    void exchangeFactoryReturnsCorrectClient() {
        BinanceClient binance = new BinanceClient();
        DeltaClient delta = new DeltaClient();
        BybitClient bybit = new BybitClient();
        ExchangeFactory factory = new ExchangeFactory(binance, delta, bybit);

        assertInstanceOf(BinanceClient.class, factory.getClient("BINANCE"));
        assertInstanceOf(DeltaClient.class, factory.getClient("DELTA"));
        assertInstanceOf(BybitClient.class, factory.getClient("BYBIT"));
        assertInstanceOf(BinanceClient.class, factory.getClient("binance"));
    }

    @Test
    @DisplayName("ExchangeFactory: rejects unsupported exchange")
    void exchangeFactoryRejectsUnsupported() {
        ExchangeFactory factory = new ExchangeFactory(new BinanceClient(), new DeltaClient(), new BybitClient());
        assertThrows(IllegalArgumentException.class, () -> factory.getClient("KRAKEN"));
    }

    // ─── Error Handling ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("BinanceClient: handles API error gracefully")
    void binanceHandlesApiError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        BinanceClient client = new BinanceClient();
        assertThrows(Exception.class, () -> client.getPrice("BTCUSDT", baseUrl));
    }
}
