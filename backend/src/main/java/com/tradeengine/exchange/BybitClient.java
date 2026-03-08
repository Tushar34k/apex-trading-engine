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
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Bybit V5 API client.
 *
 * API docs: https://bybit-exchange.github.io/docs/v5/intro
 * Authentication: HMAC-SHA256
 * Signature payload: timestamp + apiKey + recvWindow + queryString/body
 * Headers: X-BAPI-API-KEY, X-BAPI-TIMESTAMP, X-BAPI-SIGN, X-BAPI-RECV-WINDOW
 */
@Component
@Slf4j
public class BybitClient implements ExchangeClient {

    private static final long RECV_WINDOW = 5000;

    @Value("${exchange.bybit.base-url:https://api-testnet.bybit.com}")
    private String defaultBaseUrl;

    @Value("${exchange.bybit.live-url:https://api.bybit.com}")
    private String liveBaseUrl;

    @Value("${exchange.bybit.testnet-url:https://api-testnet.bybit.com}")
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
            String path = "/v5/order/create";
            long timestamp = System.currentTimeMillis();

            var payload = new LinkedHashMap<String, Object>();
            payload.put("category", "spot");
            payload.put("symbol", symbol);
            payload.put("side", capitalizeBybitSide(side));
            payload.put("orderType", "Market");
            payload.put("qty", quantity.toPlainString());

            String body = mapper.writeValueAsString(payload);
            String signature = sign(timestamp, apiKey, body, secret);

            log.info("[BYBIT] Executing MARKET order: symbol={} side={} qty={}", symbol, side, quantity);

            String responseBody = post(base + path, apiKey, timestamp, signature, body);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode result = root.get("result");

            return OrderResponse.builder()
                .orderId(result.path("orderId").asText())
                .symbol(symbol)
                .side(side.toUpperCase())
                .status(mapBybitStatus(result.path("orderStatus").asText("New")))
                .executedQty(quantity)
                .avgPrice(result.has("avgPrice") && !result.get("avgPrice").asText().isEmpty()
                    ? new BigDecimal(result.get("avgPrice").asText())
                    : null)
                .build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BYBIT] Failed to place MARKET order: {}", e.getMessage());
            throw new RuntimeException("Bybit MARKET order failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResponse placeLimitOrder(String apiKey, String secret, String symbol,
                                          String side, BigDecimal quantity, BigDecimal price, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String path = "/v5/order/create";
            long timestamp = System.currentTimeMillis();

            var payload = new LinkedHashMap<String, Object>();
            payload.put("category", "spot");
            payload.put("symbol", symbol);
            payload.put("side", capitalizeBybitSide(side));
            payload.put("orderType", "Limit");
            payload.put("qty", quantity.toPlainString());
            payload.put("price", price.toPlainString());
            payload.put("timeInForce", "GTC");

            String body = mapper.writeValueAsString(payload);
            String signature = sign(timestamp, apiKey, body, secret);

            log.info("[BYBIT] Executing LIMIT order: symbol={} side={} qty={} price={}", symbol, side, quantity, price);

            String responseBody = post(base + path, apiKey, timestamp, signature, body);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode result = root.get("result");

            return OrderResponse.builder()
                .orderId(result.path("orderId").asText())
                .symbol(symbol)
                .side(side.toUpperCase())
                .status(mapBybitStatus(result.path("orderStatus").asText("New")))
                .executedQty(quantity)
                .avgPrice(price)
                .build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BYBIT] Failed to place LIMIT order: {}", e.getMessage());
            throw new RuntimeException("Bybit LIMIT order failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String url = base + "/v5/market/tickers?category=spot&symbol=" + symbol;

            String responseBody = getUnsigned(url);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode list = root.path("result").path("list");

            if (list.isArray() && list.size() > 0) {
                String lastPrice = list.get(0).path("lastPrice").asText(null);
                if (lastPrice != null) {
                    return new BigDecimal(lastPrice);
                }
            }
            throw new RuntimeException("No price found in Bybit ticker for " + symbol);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BYBIT] Failed to get price for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Bybit price fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String path = "/v5/account/wallet-balance";
            String queryString = "accountType=UNIFIED";
            long timestamp = System.currentTimeMillis();

            String signature = sign(timestamp, apiKey, queryString, secret);

            String responseBody = getSigned(base + path + "?" + queryString,
                apiKey, timestamp, signature);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode resultList = root.path("result").path("list");

            List<Balance> balances = new ArrayList<>();
            if (resultList.isArray()) {
                for (JsonNode account : resultList) {
                    JsonNode coins = account.path("coin");
                    if (coins.isArray()) {
                        for (JsonNode coin : coins) {
                            BigDecimal free = new BigDecimal(coin.path("availableToWithdraw").asText("0"));
                            BigDecimal locked = new BigDecimal(coin.path("locked").asText("0"));

                            if (free.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0) {
                                balances.add(Balance.builder()
                                    .asset(coin.path("coin").asText())
                                    .free(free)
                                    .locked(locked)
                                    .build());
                            }
                        }
                    }
                }
            }
            return balances;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BYBIT] Failed to get balances: {}", e.getMessage());
            throw new RuntimeException("Bybit balance fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        try {
            String base = resolveBase(baseUrl);
            String bybitInterval = mapInterval(interval);
            String url = base + "/v5/market/kline?category=spot&symbol=" + symbol
                + "&interval=" + bybitInterval + "&limit=" + limit;

            String responseBody = getUnsigned(url);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode list = root.path("result").path("list");

            List<double[]> candles = new ArrayList<>();
            if (list.isArray()) {
                // Bybit returns newest first, reverse for chronological order
                for (int i = list.size() - 1; i >= 0; i--) {
                    JsonNode c = list.get(i);
                    candles.add(new double[]{
                        c.get(0).asLong() / 1000.0, // startTime ms → seconds
                        c.get(1).asDouble(),          // open
                        c.get(2).asDouble(),          // high
                        c.get(3).asDouble(),          // low
                        c.get(4).asDouble(),          // close
                        c.get(5).asDouble()            // volume
                    });
                }
            }
            return candles;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BYBIT] Failed to get candles for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Bybit candle fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
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
        try {
            String base = resolveBase(baseUrl);
            String path = "/v5/position/list";
            String queryString = "category=linear&settleCoin=USDT";
            long timestamp = System.currentTimeMillis();

            String signature = sign(timestamp, apiKey, queryString, secret);
            String responseBody = getSigned(base + path + "?" + queryString,
                apiKey, timestamp, signature);
            JsonNode root = mapper.readTree(responseBody);

            validateResponse(root);
            JsonNode list = root.path("result").path("list");

            List<ExchangePosition> positions = new ArrayList<>();
            if (list.isArray()) {
                for (JsonNode p : list) {
                    BigDecimal size = new BigDecimal(p.path("size").asText("0"));
                    if (size.compareTo(BigDecimal.ZERO) == 0) continue;

                    positions.add(ExchangePosition.builder()
                        .exchange("BYBIT")
                        .symbol(p.path("symbol").asText())
                        .side(p.path("side").asText("Buy").equalsIgnoreCase("Buy") ? "LONG" : "SHORT")
                        .size(size)
                        .entryPrice(new BigDecimal(p.path("avgPrice").asText("0")))
                        .unrealizedPnl(new BigDecimal(p.path("unrealisedPnl").asText("0")))
                        .build());
                }
            }
            log.info("[BYBIT] Fetched {} open positions", positions.size());
            return positions;
        } catch (Exception e) {
            log.error("[BYBIT] Failed to fetch positions: {}", e.getMessage());
            return List.of();
        }
    }

    // --- HMAC-SHA256 Signing (Bybit V5) ---

    /**
     * Bybit V5 signature: HMAC-SHA256(timestamp + apiKey + recvWindow + payload)
     * For GET: payload = queryString
     * For POST: payload = requestBody
     */
    private String sign(long timestamp, String apiKey, String payload, String secret) {
        try {
            String preSign = timestamp + apiKey + RECV_WINDOW + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(preSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Bybit HMAC signing failed", e);
        }
    }

    // --- HTTP helpers ---

    private String resolveBase(String baseUrl) {
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : defaultBaseUrl;
    }

    private void validateResponse(JsonNode root) {
        int retCode = root.path("retCode").asInt(-1);
        if (retCode != 0) {
            String retMsg = root.path("retMsg").asText("Unknown error");
            log.error("[BYBIT] API error: retCode={} retMsg={}", retCode, retMsg);
            throw new RuntimeException("Bybit API error [" + retCode + "]: " + retMsg);
        }
    }

    private String getUnsigned(String url) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Bybit GET error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String getSigned(String url, String apiKey, long timestamp, String signature) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("X-BAPI-API-KEY", apiKey)
            .header("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
            .header("X-BAPI-SIGN", signature)
            .header("X-BAPI-RECV-WINDOW", String.valueOf(RECV_WINDOW))
            .header("Content-Type", "application/json")
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Bybit GET error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String post(String url, String apiKey, long timestamp, String signature, String body) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-BAPI-API-KEY", apiKey)
            .header("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
            .header("X-BAPI-SIGN", signature)
            .header("X-BAPI-RECV-WINDOW", String.valueOf(RECV_WINDOW))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Bybit POST error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /**
     * Map common interval strings to Bybit V5 kline intervals.
     * Bybit uses: 1,3,5,15,30,60,120,240,360,720,D,W,M
     */
    private String mapInterval(String interval) {
        if (interval == null) return "1";
        return switch (interval.toLowerCase()) {
            case "1m" -> "1";
            case "3m" -> "3";
            case "5m" -> "5";
            case "15m" -> "15";
            case "30m" -> "30";
            case "1h" -> "60";
            case "2h" -> "120";
            case "4h" -> "240";
            case "6h" -> "360";
            case "12h" -> "720";
            case "1d", "1D" -> "D";
            case "1w", "1W" -> "W";
            case "1M" -> "M";
            default -> interval;
        };
    }

    /**
     * Bybit side must be "Buy" or "Sell" (capitalized).
     */
    private String capitalizeBybitSide(String side) {
        if (side == null) return "Buy";
        return switch (side.toUpperCase()) {
            case "BUY" -> "Buy";
            case "SELL" -> "Sell";
            default -> side;
        };
    }

    /**
     * Map Bybit order status to internal status.
     */
    private String mapBybitStatus(String bybitStatus) {
        return switch (bybitStatus) {
            case "Filled" -> "FILLED";
            case "PartiallyFilled" -> "PARTIALLY_FILLED";
            case "Cancelled" -> "CANCELLED";
            case "Rejected" -> "REJECTED";
            case "New" -> "NEW";
            default -> bybitStatus.toUpperCase();
        };
    }
}
