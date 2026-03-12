package com.tradeengine.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
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
import java.util.*;

/**
 * Binance Futures (USD-M) client.
 *
 * All endpoints target the /fapi/... Futures API, NOT the Spot /api/v3 endpoints.
 *
 * Mainnet:  https://fapi.binance.com
 * Testnet:  https://testnet.binancefuture.com
 */
@Component
@Slf4j
public class BinanceClient implements ExchangeClient {

    private static final String FUTURES_LIVE_URL = "https://fapi.binance.com";
    private static final String FUTURES_TESTNET_URL = "https://testnet.binancefuture.com";

    @Value("${exchange.binance.base-url:" + FUTURES_TESTNET_URL + "}")
    private String defaultBaseUrl;

    @Value("${exchange.binance.live-url:" + FUTURES_LIVE_URL + "}")
    private String liveBaseUrl;

    @Value("${exchange.binance.testnet-url:" + FUTURES_TESTNET_URL + "}")
    private String testnetBaseUrl;

    @Value("${exchange.binance.recv-window:5000}")
    private long recvWindow;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── ExchangeClient interface ───────────────────────────────────────────────

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v1/order";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "symbol", symbol,
            "side", side.toUpperCase(),
            "type", "MARKET",
            "quantity", quantity.toPlainString(),
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] POST {} params={}", endpoint, maskSecret(params));

        try {
            String body = post(url, apiKey);
            log.info("[BINANCE] POST {} response={}", endpoint, body);

            JsonNode node = parseAndValidate(body);

            OrderResponse resp = OrderResponse.builder()
                .orderId(node.get("orderId").asText())
                .symbol(node.get("symbol").asText())
                .side(node.get("side").asText())
                .status(node.get("status").asText())
                .executedQty(new BigDecimal(node.path("executedQty").asText("0")))
                .avgPrice(new BigDecimal(node.path("avgPrice").asText("0")))
                .build();

            log.info("[BINANCE] MARKET order filled: {} {} {} @ avg {}",
                resp.getSide(), resp.getExecutedQty(), resp.getSymbol(), resp.getAvgPrice());
            return resp;
        } catch (Exception e) {
            log.error("[BINANCE] MARKET order failed: endpoint={} symbol={} side={} qty={} error={}",
                endpoint, symbol, side, quantity, e.getMessage());
            throw new RuntimeException("Binance MARKET order failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResponse placeLimitOrder(String apiKey, String secret, String symbol,
                                          String side, BigDecimal quantity, BigDecimal price, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v1/order";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "symbol", symbol,
            "side", side.toUpperCase(),
            "type", "LIMIT",
            "timeInForce", "GTC",
            "quantity", quantity.toPlainString(),
            "price", price.toPlainString(),
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] POST {} params={}", endpoint, maskSecret(params));

        try {
            String body = post(url, apiKey);
            log.info("[BINANCE] POST {} response={}", endpoint, body);

            JsonNode node = parseAndValidate(body);

            OrderResponse resp = OrderResponse.builder()
                .orderId(node.get("orderId").asText())
                .symbol(node.get("symbol").asText())
                .side(node.get("side").asText())
                .status(node.get("status").asText())
                .executedQty(new BigDecimal(node.path("executedQty").asText("0")))
                .avgPrice(price)
                .build();

            log.info("[BINANCE] LIMIT order placed: {} {} {} @ {}",
                resp.getSide(), resp.getExecutedQty(), resp.getSymbol(), price);
            return resp;
        } catch (Exception e) {
            log.error("[BINANCE] LIMIT order failed: endpoint={} symbol={} error={}",
                endpoint, symbol, e.getMessage());
            throw new RuntimeException("Binance LIMIT order failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v1/ticker/price";
        String url = base + endpoint + "?symbol=" + symbol;

        log.debug("[BINANCE] GET {} symbol={}", endpoint, symbol);

        try {
            String body = get(url, null);
            log.debug("[BINANCE] GET {} response={}", endpoint, body);

            JsonNode node = parseAndValidate(body);
            BigDecimal price = new BigDecimal(node.get("price").asText());
            log.debug("[BINANCE] Price {}={}", symbol, price);
            return price;
        } catch (Exception e) {
            log.error("[BINANCE] getPrice failed: symbol={} error={}", symbol, e.getMessage());
            throw new RuntimeException("Binance ticker failed for " + symbol, e);
        }
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v2/balance";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] GET {} params={}", endpoint, maskSecret(params));

        try {
            String body = get(url, apiKey);
            log.debug("[BINANCE] GET {} response={}", endpoint, body);

            JsonNode arr = parseAndValidate(body);
            List<Balance> balances = new ArrayList<>();

            if (arr.isArray()) {
                for (JsonNode b : arr) {
                    BigDecimal balance = new BigDecimal(b.path("balance").asText("0"));
                    BigDecimal availableBalance = new BigDecimal(b.path("availableBalance").asText("0"));
                    BigDecimal locked = balance.subtract(availableBalance);

                    if (balance.compareTo(BigDecimal.ZERO) > 0) {
                        balances.add(Balance.builder()
                            .asset(b.path("asset").asText())
                            .free(availableBalance)
                            .locked(locked.max(BigDecimal.ZERO))
                            .build());
                    }
                }
            }

            log.info("[BINANCE] Fetched {} balances", balances.size());
            return balances;
        } catch (Exception e) {
            log.error("[BINANCE] getBalances failed: {}", e.getMessage());
            throw new RuntimeException("Binance balance fetch failed", e);
        }
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v1/klines";
        String url = base + endpoint + "?symbol=" + symbol
            + "&interval=" + interval + "&limit=" + limit;

        log.debug("[BINANCE] GET {} symbol={} interval={} limit={}", endpoint, symbol, interval, limit);

        try {
            String body = get(url, null);
            JsonNode arr = mapper.readTree(body);

            List<double[]> candles = new ArrayList<>();
            for (JsonNode c : arr) {
                candles.add(new double[]{
                    c.get(0).asLong() / 1000.0,  // timestamp
                    c.get(1).asDouble(),           // open
                    c.get(2).asDouble(),           // high
                    c.get(3).asDouble(),           // low
                    c.get(4).asDouble(),           // close
                    c.get(5).asDouble()            // volume
                });
            }

            log.debug("[BINANCE] Fetched {} candles for {}", candles.size(), symbol);
            return candles;
        } catch (Exception e) {
            log.error("[BINANCE] getCandles failed: symbol={} error={}", symbol, e.getMessage());
            throw new RuntimeException("Binance candles failed for " + symbol, e);
        }
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public String resolveBaseUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return liveBaseUrl;
        }
        return testnetBaseUrl;
    }

    @Override
    public List<ExchangePosition> getOpenPositions(String apiKey, String secret, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v2/positionRisk";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] GET {} params={}", endpoint, maskSecret(params));

        try {
            String body = get(url, apiKey);
            log.debug("[BINANCE] GET {} response={}", endpoint, body);

            JsonNode arr = parseAndValidate(body);
            List<ExchangePosition> positions = new ArrayList<>();

            if (arr.isArray()) {
                for (JsonNode p : arr) {
                    BigDecimal posAmt = new BigDecimal(p.path("positionAmt").asText("0"));
                    if (posAmt.abs().compareTo(BigDecimal.ZERO) == 0) continue;

                    positions.add(ExchangePosition.builder()
                        .exchange("BINANCE")
                        .symbol(p.path("symbol").asText())
                        .side(posAmt.compareTo(BigDecimal.ZERO) > 0 ? "LONG" : "SHORT")
                        .size(posAmt.abs())
                        .entryPrice(new BigDecimal(p.path("entryPrice").asText("0")))
                        .unrealizedPnl(new BigDecimal(p.path("unRealizedProfit").asText("0")))
                        .build());
                }
            }

            log.info("[BINANCE] Fetched {} open positions", positions.size());
            return positions;
        } catch (Exception e) {
            log.error("[BINANCE] getOpenPositions failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── Additional Futures endpoints ───────────────────────────────────────────

    /**
     * Fetch open orders for a symbol.
     * Endpoint: GET /fapi/v1/openOrders
     */
    public List<JsonNode> getOpenOrders(String apiKey, String secret, String symbol, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v1/openOrders";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "symbol", symbol,
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] GET {} symbol={}", endpoint, symbol);

        try {
            String body = get(url, apiKey);
            log.debug("[BINANCE] GET {} response={}", endpoint, body);

            JsonNode arr = parseAndValidate(body);
            List<JsonNode> orders = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode order : arr) {
                    orders.add(order);
                }
            }
            log.info("[BINANCE] Fetched {} open orders for {}", orders.size(), symbol);
            return orders;
        } catch (Exception e) {
            log.error("[BINANCE] getOpenOrders failed: symbol={} error={}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetch all orders (including filled/cancelled) for a symbol.
     * Endpoint: GET /fapi/v1/allOrders
     */
    public List<JsonNode> getAllOrders(String apiKey, String secret, String symbol, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v1/allOrders";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "symbol", symbol,
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] GET {} symbol={}", endpoint, symbol);

        try {
            String body = get(url, apiKey);
            log.debug("[BINANCE] GET {} response={}", endpoint, body);

            JsonNode arr = parseAndValidate(body);
            List<JsonNode> orders = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode order : arr) {
                    orders.add(order);
                }
            }
            log.info("[BINANCE] Fetched {} total orders for {}", orders.size(), symbol);
            return orders;
        } catch (Exception e) {
            log.error("[BINANCE] getAllOrders failed: symbol={} error={}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * Test connectivity by calling GET /fapi/v2/balance.
     * Returns true if the API key is valid and can authenticate.
     */
    public boolean testConnection(String apiKey, String secret, String baseUrl) {
        String base = resolveBase(baseUrl);
        String endpoint = "/fapi/v2/balance";

        long timestamp = System.currentTimeMillis();
        String params = buildQueryString(
            "recvWindow", String.valueOf(recvWindow),
            "timestamp", String.valueOf(timestamp)
        );

        String signed = appendSignature(params, secret);
        String url = base + endpoint + "?" + signed;

        log.info("[BINANCE] Testing connection: GET {}", endpoint);

        try {
            String body = get(url, apiKey);
            JsonNode node = mapper.readTree(body);

            // If the response is an error object with a code, connection failed
            if (node.has("code") && node.get("code").asInt() < 0) {
                log.error("[BINANCE] Connection test failed: code={} msg={}",
                    node.get("code").asInt(), node.path("msg").asText());
                return false;
            }

            log.info("[BINANCE] Connection test successful");
            return true;
        } catch (Exception e) {
            log.error("[BINANCE] Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Signing & HTTP helpers ─────────────────────────────────────────────────

    private String resolveBase(String baseUrl) {
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : defaultBaseUrl;
    }

    /**
     * Build a deterministic query string from key-value pairs.
     * Parameters are kept in the order provided (Binance doesn't require sorted params,
     * but the signature must match the exact query string).
     */
    private String buildQueryString(String... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Query params must be key-value pairs");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
        }
        return sb.toString();
    }

    /**
     * Signs the query string with HMAC-SHA256 and appends &signature=...
     */
    private String appendSignature(String queryString, String secret) {
        String signature = sign(queryString, secret);
        return queryString + "&signature=" + signature;
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    /**
     * Mask the signature and timestamp from logs to avoid leaking signing material.
     */
    private String maskSecret(String params) {
        return params.replaceAll("signature=[^&]+", "signature=***")
                     .replaceAll("timestamp=\\d+", "timestamp=***");
    }

    private String get(String url, String apiKey) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).GET()
            .timeout(Duration.ofSeconds(10));
        if (apiKey != null) {
            builder.header("X-MBX-APIKEY", apiKey);
        }

        var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            log.error("[BINANCE] HTTP {} from GET {}: {}", resp.statusCode(), url.split("\\?")[0], resp.body());
            // Parse Binance error codes for clear messaging
            parseBinanceError(resp.body(), resp.statusCode());
        }

        return resp.body();
    }

    private String post(String url, String apiKey) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-MBX-APIKEY", apiKey)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            log.error("[BINANCE] HTTP {} from POST {}: {}", resp.statusCode(), url.split("\\?")[0], resp.body());
            parseBinanceError(resp.body(), resp.statusCode());
        }

        return resp.body();
    }

    /**
     * Parse Binance API error responses and throw descriptive exceptions.
     * Common error codes:
     *   -1021: Timestamp outside recvWindow
     *   -1022: Invalid signature
     *   -2014: API key format invalid
     *   -2015: Invalid API key, IP, or permissions
     */
    private void parseBinanceError(String responseBody, int httpStatus) {
        try {
            JsonNode node = mapper.readTree(responseBody);
            if (node.has("code")) {
                int code = node.get("code").asInt();
                String msg = node.path("msg").asText("Unknown error");

                String detail = switch (code) {
                    case -1021 -> "Timestamp outside recvWindow. Check server clock sync. recvWindow=" + recvWindow;
                    case -1022 -> "Invalid signature. Verify API secret is correct and query string order matches.";
                    case -2014 -> "API key format invalid. Verify the API key was copied correctly.";
                    case -2015 -> "Invalid API key, IP restriction, or insufficient permissions.";
                    case -1102 -> "Mandatory parameter missing or malformed.";
                    case -4001 -> "Price/quantity precision exceeds allowed decimals.";
                    default -> msg;
                };

                throw new RuntimeException(String.format(
                    "Binance API error [%d]: %s (HTTP %d)", code, detail, httpStatus));
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ignored) {
            // Not JSON or no code field
        }

        throw new RuntimeException("Binance HTTP " + httpStatus + ": " + responseBody);
    }

    /**
     * Parse JSON and check for Binance error codes in the response body.
     */
    private JsonNode parseAndValidate(String body) throws Exception {
        JsonNode node = mapper.readTree(body);

        // Binance error responses are objects with "code" < 0
        if (node.isObject() && node.has("code") && node.get("code").asInt() < 0) {
            int code = node.get("code").asInt();
            String msg = node.path("msg").asText("Unknown error");
            throw new RuntimeException(String.format("Binance API error [%d]: %s", code, msg));
        }

        return node;
    }

    @Data
    public static class OrderResult {
        private String orderId;
        private String symbol;
        private String side;
        private String status;
        private BigDecimal executedQty;
        private BigDecimal avgPrice;
    }
}
