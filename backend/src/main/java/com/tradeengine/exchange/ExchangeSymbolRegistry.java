package com.tradeengine.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exchange-agnostic symbol info registry.
 * Caches trading rules (LOT_SIZE, MIN_NOTIONAL, PRICE_FILTER) per exchange + symbol.
 * Replaces the old Binance-only SymbolInfoCache.
 *
 * Cache key format: "EXCHANGE:SYMBOL" (e.g. "BINANCE:BTCUSDT", "DELTA:BTCUSD")
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeSymbolRegistry {

    private final ExchangeFactory exchangeFactory;

    private final Map<String, SymbolInfo> cache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private String cacheKey(String exchange, String symbol) {
        return exchange.toUpperCase() + ":" + symbol.toUpperCase();
    }

    /**
     * Get cached symbol info for a specific exchange.
     */
    public SymbolInfo get(String exchange, String symbol) {
        return cache.get(cacheKey(exchange, symbol));
    }

    /**
     * Get or fetch symbol info for a specific exchange.
     * Delegates to the appropriate exchange-specific fetcher.
     */
    public SymbolInfo getOrFetch(String exchange, String symbol, String exchangeBaseUrl) {
        String key = cacheKey(exchange, symbol);
        SymbolInfo info = cache.get(key);
        if (info != null) return info;

        fetchForExchange(exchange, symbol, exchangeBaseUrl);
        return cache.get(key);
    }

    private void fetchForExchange(String exchange, String symbol, String baseUrl) {
        switch (exchange.toUpperCase()) {
            case "BINANCE" -> fetchBinanceSymbolInfo(symbol, baseUrl);
            case "DELTA" -> fetchDeltaSymbolInfo(symbol, baseUrl);
            case "BYBIT" -> fetchBybitSymbolInfo(symbol, baseUrl);
            default -> log.warn("[SYMBOL_REGISTRY] Unknown exchange for symbol fetch: {}", exchange);
        }
    }

    // ─── Binance ────────────────────────────────────────────────────────────────

    private void fetchBinanceSymbolInfo(String symbol, String baseUrl) {
        try {
            String url = baseUrl + "/api/v3/exchangeInfo?symbol=" + symbol.toUpperCase();
            var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[SYMBOL_REGISTRY] Binance exchangeInfo failed for {}: {}", symbol, resp.statusCode());
                return;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode symbols = root.get("symbols");
            if (symbols != null && symbols.isArray()) {
                for (JsonNode s : symbols) {
                    parseBinanceSymbol(s);
                }
            }
        } catch (Exception e) {
            log.error("[SYMBOL_REGISTRY] Error fetching Binance info for {}: {}", symbol, e.getMessage());
        }
    }

    private void parseBinanceSymbol(JsonNode symbolNode) {
        String sym = symbolNode.get("symbol").asText();
        SymbolInfo info = new SymbolInfo();
        info.setSymbol(sym);
        info.setExchange("BINANCE");

        JsonNode filters = symbolNode.get("filters");
        if (filters != null) {
            for (JsonNode f : filters) {
                String type = f.get("filterType").asText();
                switch (type) {
                    case "LOT_SIZE" -> {
                        info.setStepSize(new BigDecimal(f.get("stepSize").asText()));
                        info.setMinQty(new BigDecimal(f.get("minQty").asText()));
                        info.setMaxQty(new BigDecimal(f.get("maxQty").asText()));
                    }
                    case "NOTIONAL", "MIN_NOTIONAL" -> {
                        if (f.has("minNotional")) {
                            info.setMinNotional(new BigDecimal(f.get("minNotional").asText()));
                        }
                    }
                    case "PRICE_FILTER" -> {
                        info.setTickSize(new BigDecimal(f.get("tickSize").asText()));
                    }
                }
            }
        }
        cache.put(cacheKey("BINANCE", sym), info);
        log.debug("[SYMBOL_REGISTRY] Cached BINANCE:{} stepSize={} minQty={} minNotional={}",
            sym, info.getStepSize(), info.getMinQty(), info.getMinNotional());
    }

    // ─── Delta ──────────────────────────────────────────────────────────────────

    private void fetchDeltaSymbolInfo(String symbol, String baseUrl) {
        try {
            String url = baseUrl + "/v2/products/" + symbol.toUpperCase();
            var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[SYMBOL_REGISTRY] Delta product info failed for {}: {}", symbol, resp.statusCode());
                return;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode result = root.has("result") ? root.get("result") : root;

            SymbolInfo info = new SymbolInfo();
            info.setSymbol(symbol.toUpperCase());
            info.setExchange("DELTA");

            if (result.has("tick_size")) {
                info.setTickSize(new BigDecimal(result.get("tick_size").asText()));
            }
            if (result.has("contract_size")) {
                info.setStepSize(new BigDecimal(result.get("contract_size").asText()));
            }
            if (result.has("min_size")) {
                info.setMinQty(new BigDecimal(result.get("min_size").asText()));
            }

            cache.put(cacheKey("DELTA", symbol), info);
            log.debug("[SYMBOL_REGISTRY] Cached DELTA:{} tickSize={} stepSize={} minQty={}",
                symbol, info.getTickSize(), info.getStepSize(), info.getMinQty());
        } catch (Exception e) {
            log.error("[SYMBOL_REGISTRY] Error fetching Delta info for {}: {}", symbol, e.getMessage());
        }
    }

    // ─── Bybit ──────────────────────────────────────────────────────────────────

    private void fetchBybitSymbolInfo(String symbol, String baseUrl) {
        try {
            String url = baseUrl + "/v5/market/instruments-info?category=spot&symbol=" + symbol.toUpperCase();
            var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[SYMBOL_REGISTRY] Bybit instruments-info failed for {}: {}", symbol, resp.statusCode());
                return;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode list = root.path("result").path("list");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    SymbolInfo info = new SymbolInfo();
                    String sym = item.get("symbol").asText();
                    info.setSymbol(sym);
                    info.setExchange("BYBIT");

                    JsonNode lotFilter = item.path("lotSizeFilter");
                    if (!lotFilter.isMissingNode()) {
                        if (lotFilter.has("basePrecision"))
                            info.setStepSize(new BigDecimal(lotFilter.get("basePrecision").asText()));
                        if (lotFilter.has("minOrderQty"))
                            info.setMinQty(new BigDecimal(lotFilter.get("minOrderQty").asText()));
                        if (lotFilter.has("maxOrderQty"))
                            info.setMaxQty(new BigDecimal(lotFilter.get("maxOrderQty").asText()));
                    }

                    JsonNode priceFilter = item.path("priceFilter");
                    if (!priceFilter.isMissingNode() && priceFilter.has("tickSize")) {
                        info.setTickSize(new BigDecimal(priceFilter.get("tickSize").asText()));
                    }

                    cache.put(cacheKey("BYBIT", sym), info);
                    log.debug("[SYMBOL_REGISTRY] Cached BYBIT:{} stepSize={} minQty={}",
                        sym, info.getStepSize(), info.getMinQty());
                }
            }
        } catch (Exception e) {
            log.error("[SYMBOL_REGISTRY] Error fetching Bybit info for {}: {}", symbol, e.getMessage());
        }
    }

    // ─── Refresh ────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 1800000) // 30 min
    public void refreshAll() {
        if (cache.isEmpty()) return;

        int refreshed = 0;
        for (Map.Entry<String, SymbolInfo> entry : cache.entrySet()) {
            String[] parts = entry.getKey().split(":");
            if (parts.length != 2) continue;

            String exchange = parts[0];
            String symbol = parts[1];

            try {
                ExchangeClient client = exchangeFactory.getClient(exchange);
                String baseUrl = client.resolveBaseUrl("TESTNET"); // refresh from testnet by default
                fetchForExchange(exchange, symbol, baseUrl);
                refreshed++;
            } catch (Exception e) {
                log.warn("[SYMBOL_REGISTRY] Failed to refresh {}:{} — {}", exchange, symbol, e.getMessage());
            }
        }
        log.info("[SYMBOL_REGISTRY] Refreshed {} symbol entries", refreshed);
    }

    public int getCacheSize() {
        return cache.size();
    }
}
