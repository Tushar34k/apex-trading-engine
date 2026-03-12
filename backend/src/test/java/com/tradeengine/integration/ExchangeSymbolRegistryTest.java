package com.tradeengine.integration;

import com.tradeengine.exchange.ExchangeSymbolRegistry;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.exchange.SymbolInfo;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests ExchangeSymbolRegistry caching and parsing for all exchanges.
 */
class ExchangeSymbolRegistryTest {

    private MockWebServer mockServer;
    private String baseUrl;
    private ExchangeSymbolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/").toString().replaceAll("/$", "");

        ExchangeFactory factory = mock(ExchangeFactory.class);
        registry = new ExchangeSymbolRegistry(factory);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Binance Futures: parses /fapi/v1/exchangeInfo with LOT_SIZE and MIN_NOTIONAL")
    void binanceSymbolInfo() {
        String body = """
            {"symbols":[{
              "symbol":"BTCUSDT",
              "filters":[
                {"filterType":"LOT_SIZE","stepSize":"0.001","minQty":"0.001","maxQty":"1000"},
                {"filterType":"MIN_NOTIONAL","notional":"5"},
                {"filterType":"PRICE_FILTER","tickSize":"0.10"}
              ]
            }]}
            """;
        mockServer.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/json"));

        SymbolInfo info = registry.getOrFetch("BINANCE", "BTCUSDT", baseUrl);

        assertNotNull(info);
        assertEquals("BTCUSDT", info.getSymbol());
        assertEquals("BINANCE", info.getExchange());
        assertNotNull(info.getStepSize());
        // Verify the request hit the Futures endpoint
        try {
            var request = mockServer.takeRequest();
            assertTrue(request.getPath().startsWith("/fapi/v1/exchangeInfo"),
                "Should use Futures endpoint /fapi/v1/exchangeInfo but got: " + request.getPath());
        } catch (Exception e) {
            fail("Failed to verify request path: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Bybit: parses instruments-info")
    void bybitSymbolInfo() {
        String body = """
            {"retCode":0,"result":{"list":[{
              "symbol":"BTCUSDT",
              "lotSizeFilter":{"basePrecision":"0.000001","minOrderQty":"0.000048","maxOrderQty":"71.73"},
              "priceFilter":{"tickSize":"0.01"}
            }]}}
            """;
        mockServer.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/json"));

        SymbolInfo info = registry.getOrFetch("BYBIT", "BTCUSDT", baseUrl);

        assertNotNull(info);
        assertEquals("BYBIT", info.getExchange());
        assertNotNull(info.getMinQty());
    }

    @Test
    @DisplayName("Cache returns previously fetched info")
    void cacheHit() {
        String body = """
            {"symbols":[{"symbol":"ETHUSDT","filters":[
              {"filterType":"LOT_SIZE","stepSize":"0.0001","minQty":"0.0001","maxQty":"100000"}
            ]}]}
            """;
        mockServer.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/json"));

        registry.getOrFetch("BINANCE", "ETHUSDT", baseUrl);
        SymbolInfo cached = registry.get("BINANCE", "ETHUSDT");

        assertNotNull(cached);
        assertEquals(1, registry.getCacheSize());
        // No second request should be made
        assertEquals(1, mockServer.getRequestCount());
    }
}
