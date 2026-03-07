package com.tradeengine.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Caches Binance exchangeInfo to validate LOT_SIZE, MIN_NOTIONAL, PRICE_FILTER.
 * Refreshes every 30 minutes.
 */
@Component
@Slf4j
public class SymbolInfoCache {

    @Value("${exchange.binance.base-url}")
    private String baseUrl;

    private final Map<String, SymbolInfo> cache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public SymbolInfo get(String symbol) {
        return cache.get(symbol.toUpperCase());
    }

    public SymbolInfo getOrFetch(String symbol, String exchangeBaseUrl) {
        SymbolInfo info = cache.get(symbol.toUpperCase());
        if (info != null) return info;
        fetchSingle(symbol, exchangeBaseUrl);
        return cache.get(symbol.toUpperCase());
    }

    private void fetchSingle(String symbol, String exchangeBaseUrl) {
        try {
            String url = (exchangeBaseUrl != null ? exchangeBaseUrl : baseUrl)
                + "/api/v3/exchangeInfo?symbol=" + symbol.toUpperCase();
            var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Failed to fetch exchangeInfo for {}: {}", symbol, resp.statusCode());
                return;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode symbols = root.get("symbols");
            if (symbols != null && symbols.isArray()) {
                for (JsonNode s : symbols) {
                    parseAndCache(s);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching exchangeInfo for {}: {}", symbol, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1800000) // 30 min
    public void refreshAll() {
        if (cache.isEmpty()) return;
        try {
            String url = baseUrl + "/api/v3/exchangeInfo";
            var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonNode root = mapper.readTree(resp.body());
            JsonNode symbols = root.get("symbols");
            if (symbols != null && symbols.isArray()) {
                for (JsonNode s : symbols) {
                    String sym = s.get("symbol").asText();
                    if (cache.containsKey(sym)) {
                        parseAndCache(s);
                    }
                }
            }
            log.info("SymbolInfoCache refreshed: {} symbols", cache.size());
        } catch (Exception e) {
            log.warn("Failed to refresh symbol info cache: {}", e.getMessage());
        }
    }

    private void parseAndCache(JsonNode symbolNode) {
        String sym = symbolNode.get("symbol").asText();
        SymbolInfo info = new SymbolInfo();
        info.setSymbol(sym);

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
        cache.put(sym, info);
        log.debug("Cached symbol info: {} stepSize={} minQty={} minNotional={}",
            sym, info.getStepSize(), info.getMinQty(), info.getMinNotional());
    }
}
