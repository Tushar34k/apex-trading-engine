package com.tradeengine.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Delta Exchange API client.
 *
 * API docs: https://docs.delta.exchange
 * Authentication: HMAC-SHA256 with headers: api-key, timestamp, signature
 * Signature payload: method + timestamp + requestPath + body
 */
@Component
@Slf4j
public class DeltaClient implements ExchangeClient {

    private static final String LIVE_URL = "https://api.delta.exchange";
    private static final String TESTNET_URL = "https://cdn-ind.testnet.deltaex.org";

    @Value("${exchange.delta.base-url:" + TESTNET_URL + "}")
    private String defaultBaseUrl;

    @Value("${exchange.delta.live-url:" + LIVE_URL + "}")
    private String liveBaseUrl;

    @Value("${exchange.delta.testnet-url:" + TESTNET_URL + "}")
    private String testnetBaseUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // --- ExchangeClient interface ---

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String path = "/v2/orders";
            long timestamp = System.currentTimeMillis() / 1000; // Delta uses seconds

            int contractSize = quantity.intValue();
            if (contractSize <= 0) {
                throw new IllegalArgumentException("Delta requires integer contract size > 0, got: " + quantity);
            }
            if (quantity.stripTrailingZeros().scale() > 0) {
                log.warn("[DELTA] Quantity {} truncated to {} for integer contract size", quantity, contractSize);
            }

            String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("product_symbol", symbol);
                put("size", contractSize);
                put("side", side.toLowerCase());
                put("order_type", "market_order");
            }});

            String signature = sign("POST" + timestamp + path + body, secret);

            log.info("[DELTA] Executing MARKET order: symbol={} side={} qty={}", symbol, side, quantity);

            String responseBody = post(base + path, apiKey, String.valueOf(timestamp), signature, body);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode result = root.get("result");

            return OrderResponse.builder()
                .orderId(String.valueOf(result.get("id").asLong()))
                .symbol(result.path("product_symbol").asText(symbol))
                .side(result.path("side").asText(side).toUpperCase())
                .status(result.path("state").asText("open"))
                .executedQty(new BigDecimal(result.path("size").asText(quantity.toPlainString())))
                .avgPrice(result.has("avg_fill_price") && !result.get("avg_fill_price").isNull()
                    ? new BigDecimal(result.get("avg_fill_price").asText())
                    : null)
                .build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DELTA] Failed to place MARKET order: {}", e.getMessage());
            throw new RuntimeException("Delta MARKET order failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResponse placeLimitOrder(String apiKey, String secret, String symbol,
                                          String side, BigDecimal quantity, BigDecimal price, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String path = "/v2/orders";
            long timestamp = System.currentTimeMillis() / 1000;

            String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("product_symbol", symbol);
                put("size", quantity.intValue());
                put("side", side.toLowerCase());
                put("order_type", "limit_order");
                put("limit_price", price.toPlainString());
            }});

            String signature = sign("POST" + timestamp + path + body, secret);

            log.info("[DELTA] Executing LIMIT order: symbol={} side={} qty={} price={}", symbol, side, quantity, price);

            String responseBody = post(base + path, apiKey, String.valueOf(timestamp), signature, body);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode result = root.get("result");

            return OrderResponse.builder()
                .orderId(String.valueOf(result.get("id").asLong()))
                .symbol(result.path("product_symbol").asText(symbol))
                .side(result.path("side").asText(side).toUpperCase())
                .status(result.path("state").asText("open"))
                .executedQty(new BigDecimal(result.path("size").asText(quantity.toPlainString())))
                .avgPrice(price)
                .build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DELTA] Failed to place LIMIT order: {}", e.getMessage());
            throw new RuntimeException("Delta LIMIT order failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String url = base + "/v2/tickers/" + symbol;
            String responseBody = getUnsigned(url);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode result = root.get("result");

            String markPrice = result.path("mark_price").asText(null);
            if (markPrice != null) {
                return new BigDecimal(markPrice);
            }
            String spotPrice = result.path("spot_price").asText(null);
            if (spotPrice != null) {
                return new BigDecimal(spotPrice);
            }
            throw new RuntimeException("No price found in Delta ticker response for " + symbol);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DELTA] Failed to get price for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Delta price fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String path = "/v2/wallet/balances";
            long timestamp = System.currentTimeMillis() / 1000;

            String signature = sign("GET" + timestamp + path, secret);

            String responseBody = getSigned(base + path, apiKey, String.valueOf(timestamp), signature);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode resultArray = root.get("result");

            List<Balance> balances = new ArrayList<>();
            if (resultArray != null && resultArray.isArray()) {
                for (JsonNode b : resultArray) {
                    BigDecimal available = new BigDecimal(b.path("available_balance").asText("0"));
                    BigDecimal locked = new BigDecimal(b.path("order_margin").asText("0"))
                        .add(new BigDecimal(b.path("position_margin").asText("0")));

                    if (available.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0) {
                        balances.add(Balance.builder()
                            .asset(b.path("asset_symbol").asText(b.path("asset_id").asText()))
                            .free(available)
                            .locked(locked)
                            .build());
                    }
                }
            }
            return balances;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DELTA] Failed to get balances: {}", e.getMessage());
            throw new RuntimeException("Delta balance fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String resolution = mapInterval(interval);
            long endTime = System.currentTimeMillis() / 1000;
            long startTime = endTime - (intervalToSeconds(resolution) * limit);

            String url = base + "/v2/history/candles?resolution=" + resolution
                + "&symbol=" + symbol
                + "&start=" + startTime
                + "&end=" + endTime;

            String responseBody = getUnsigned(url);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode resultArray = root.get("result");

            List<double[]> candles = new ArrayList<>();
            if (resultArray != null && resultArray.isArray()) {
                for (JsonNode c : resultArray) {
                    candles.add(new double[]{
                        c.path("time").asLong(),
                        c.path("open").asDouble(),
                        c.path("high").asDouble(),
                        c.path("low").asDouble(),
                        c.path("close").asDouble(),
                        c.path("volume").asDouble()
                    });
                }
            }
            return candles;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DELTA] Failed to get candles for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Delta candle fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExchangeName() {
        return "DELTA";
    }

    @Override
    public String resolveBaseUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return liveBaseUrl;
        }
        return testnetBaseUrl;
    }

    // --- Helpers ---

    private String resolveBase(String baseUrl) {
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : defaultBaseUrl;
    }

    private void validateResponse(JsonNode root) {
        if (root.has("error") && !root.get("error").isNull()) {
            String error = root.get("error").toString();
            log.error("[DELTA] API error: {}", error);
            throw new RuntimeException("Delta API error: " + error);
        }
        if (root.has("success") && !root.get("success").asBoolean(true)) {
            String msg = root.has("message") ? root.get("message").asText() : root.toString();
            log.error("[DELTA] API failure: {}", msg);
            throw new RuntimeException("Delta API failure: " + msg);
        }
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Delta HMAC signing failed", e);
        }
    }

    private String getUnsigned(String url) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Delta GET error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String getSigned(String url, String apiKey, String timestamp, String signature) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("api-key", apiKey)
            .header("timestamp", timestamp)
            .header("signature", signature)
            .header("Content-Type", "application/json")
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Delta GET error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String post(String url, String apiKey, String timestamp, String signature, String body) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("api-key", apiKey)
            .header("timestamp", timestamp)
            .header("signature", signature)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Delta POST error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /**
     * Map common interval strings (e.g. "1m", "1h", "1d") to Delta resolution format.
     */
    private String mapInterval(String interval) {
        if (interval == null) return "1m";
        return switch (interval.toLowerCase()) {
            case "1m" -> "1m";
            case "3m" -> "3m";
            case "5m" -> "5m";
            case "15m" -> "15m";
            case "30m" -> "30m";
            case "1h" -> "1h";
            case "2h" -> "2h";
            case "4h" -> "4h";
            case "6h" -> "6h";
            case "1d", "1D" -> "1d";
            case "1w", "1W" -> "1w";
            default -> interval;
        };
    }

    private long intervalToSeconds(String resolution) {
        return switch (resolution) {
            case "1m" -> 60;
            case "3m" -> 180;
            case "5m" -> 300;
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h" -> 3600;
            case "2h" -> 7200;
            case "4h" -> 14400;
            case "6h" -> 21600;
            case "1d" -> 86400;
            case "1w" -> 604800;
            default -> 60;
        };
    }
}
