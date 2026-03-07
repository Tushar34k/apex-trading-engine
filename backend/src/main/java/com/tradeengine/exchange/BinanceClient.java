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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@Slf4j
public class BinanceClient implements ExchangeClient {

    private static final String LIVE_URL = "https://api.binance.com";
    private static final String TESTNET_URL = "https://testnet.binance.vision";

    @Value("${exchange.binance.base-url}")
    private String defaultBaseUrl;

    @Value("${exchange.binance.recv-window:5000}")
    private long recvWindow;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // --- ExchangeClient interface ---

    @Override
    public OrderResponse placeMarketOrder(String apiKey, String secret, String symbol,
                                           String side, BigDecimal quantity, String baseUrl) {
        OrderResult result = placeMarketOrderInternal(apiKey, secret, symbol, side, quantity, baseUrl);
        return OrderResponse.builder()
            .orderId(result.getOrderId())
            .symbol(result.getSymbol())
            .side(result.getSide())
            .status(result.getStatus())
            .executedQty(result.getExecutedQty())
            .avgPrice(result.getAvgPrice())
            .build();
    }

    @Override
    public OrderResponse placeLimitOrder(String apiKey, String secret, String symbol,
                                          String side, BigDecimal quantity, BigDecimal price, String baseUrl) {
        OrderResult result = placeLimitOrderInternal(apiKey, secret, symbol, side, quantity, price, baseUrl);
        return OrderResponse.builder()
            .orderId(result.getOrderId())
            .symbol(result.getSymbol())
            .side(result.getSide())
            .status(result.getStatus())
            .executedQty(result.getExecutedQty())
            .avgPrice(result.getAvgPrice())
            .build();
    }

    @Override
    public BigDecimal getPrice(String symbol, String baseUrl) {
        return getTickerPrice(symbol, baseUrl);
    }

    @Override
    public List<Balance> getBalances(String apiKey, String secret, String baseUrl) {
        return getBalancesInternal(apiKey, secret, baseUrl);
    }

    @Override
    public List<double[]> getCandles(String symbol, String interval, int limit, String baseUrl) {
        return getCandlesInternal(symbol, interval, limit, baseUrl);
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public String resolveBaseUrl(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return LIVE_URL;
        }
        return TESTNET_URL;
    }

    // --- Public endpoints ---

    public BigDecimal getTickerPrice(String symbol) {
        return getTickerPrice(symbol, null);
    }

    public BigDecimal getTickerPrice(String symbol, String baseUrl) {
        try {
            String url = resolveBase(baseUrl) + "/api/v3/ticker/price?symbol=" + symbol;
            String body = get(url, null);
            JsonNode node = mapper.readTree(body);
            return new BigDecimal(node.get("price").asText());
        } catch (Exception e) {
            log.error("Failed to get ticker price for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Binance ticker failed", e);
        }
    }

    public List<double[]> getCandlesInternal(String symbol, String interval, int limit, String baseUrl) {
        try {
            String url = resolveBase(baseUrl) + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval + "&limit=" + limit;
            String body = get(url, null);
            JsonNode arr = mapper.readTree(body);
            List<double[]> candles = new ArrayList<>();
            for (JsonNode c : arr) {
                candles.add(new double[]{
                    c.get(0).asLong() / 1000.0,
                    c.get(1).asDouble(),
                    c.get(2).asDouble(),
                    c.get(3).asDouble(),
                    c.get(4).asDouble(),
                    c.get(5).asDouble()
                });
            }
            return candles;
        } catch (Exception e) {
            log.error("Failed to get candles: {}", e.getMessage());
            throw new RuntimeException("Binance candles failed", e);
        }
    }

    // --- Signed endpoints ---

    private OrderResult placeMarketOrderInternal(String apiKey, String secret, String symbol,
                                                  String side, BigDecimal quantity, String baseUrl) {
        try {
            long timestamp = System.currentTimeMillis();
            String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=MARKET"
                + "&quantity=" + quantity.toPlainString()
                + "&recvWindow=" + recvWindow
                + "&timestamp=" + timestamp;

            String signature = sign(params, secret);
            params += "&signature=" + signature;

            String url = resolveBase(baseUrl) + "/api/v3/order?" + params;

            log.info("[BINANCE] Executing trade: symbol={} side={} qty={} timestamp={}", symbol, side, quantity, timestamp);

            String body = post(url, apiKey);
            JsonNode node = mapper.readTree(body);

            OrderResult result = new OrderResult();
            result.setOrderId(node.get("orderId").asText());
            result.setSymbol(node.get("symbol").asText());
            result.setSide(node.get("side").asText());
            result.setStatus(node.get("status").asText());
            result.setExecutedQty(new BigDecimal(node.get("executedQty").asText()));

            if (node.has("fills") && node.get("fills").size() > 0) {
                BigDecimal totalQty = BigDecimal.ZERO;
                BigDecimal totalCost = BigDecimal.ZERO;
                for (JsonNode fill : node.get("fills")) {
                    BigDecimal qty = new BigDecimal(fill.get("qty").asText());
                    BigDecimal price = new BigDecimal(fill.get("price").asText());
                    totalQty = totalQty.add(qty);
                    totalCost = totalCost.add(qty.multiply(price));
                }
                if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
                    result.setAvgPrice(totalCost.divide(totalQty, 8, java.math.RoundingMode.HALF_UP));
                }
            }

            log.info("[BINANCE] Order placed: {} {} {} @ avg {}", side, quantity, symbol, result.getAvgPrice());
            return result;
        } catch (Exception e) {
            log.error("[BINANCE] Failed to place order: {}", e.getMessage());
            throw new RuntimeException("Binance order failed: " + e.getMessage(), e);
        }
    }

    private OrderResult placeLimitOrderInternal(String apiKey, String secret, String symbol,
                                                 String side, BigDecimal quantity, BigDecimal price, String baseUrl) {
        try {
            long timestamp = System.currentTimeMillis();
            String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=LIMIT"
                + "&timeInForce=GTC"
                + "&quantity=" + quantity.toPlainString()
                + "&price=" + price.toPlainString()
                + "&recvWindow=" + recvWindow
                + "&timestamp=" + timestamp;

            String signature = sign(params, secret);
            params += "&signature=" + signature;

            String url = resolveBase(baseUrl) + "/api/v3/order?" + params;

            log.info("[BINANCE] Executing LIMIT order: symbol={} side={} qty={} price={} timestamp={}",
                symbol, side, quantity, price, timestamp);

            String body = post(url, apiKey);
            JsonNode node = mapper.readTree(body);

            OrderResult result = new OrderResult();
            result.setOrderId(node.get("orderId").asText());
            result.setSymbol(node.get("symbol").asText());
            result.setSide(node.get("side").asText());
            result.setStatus(node.get("status").asText());
            result.setExecutedQty(new BigDecimal(node.get("executedQty").asText()));
            result.setAvgPrice(price); // Limit orders fill at the specified price

            log.info("[BINANCE] LIMIT order placed: {} {} {} @ {}", side, quantity, symbol, price);
            return result;
        } catch (Exception e) {
            log.error("[BINANCE] Failed to place LIMIT order: {}", e.getMessage());
            throw new RuntimeException("Binance LIMIT order failed: " + e.getMessage(), e);
        }
    }

    private List<Balance> getBalancesInternal(String apiKey, String secret, String baseUrl) {
        try {
            long timestamp = System.currentTimeMillis();
            String params = "recvWindow=" + recvWindow + "&timestamp=" + timestamp;
            String signature = sign(params, secret);
            params += "&signature=" + signature;

            String url = resolveBase(baseUrl) + "/api/v3/account?" + params;
            String body = get(url, apiKey);
            JsonNode node = mapper.readTree(body);

            List<Balance> balances = new ArrayList<>();
            for (JsonNode b : node.get("balances")) {
                BigDecimal free = new BigDecimal(b.get("free").asText());
                BigDecimal locked = new BigDecimal(b.get("locked").asText());
                if (free.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0) {
                    balances.add(Balance.builder()
                        .asset(b.get("asset").asText())
                        .free(free)
                        .locked(locked)
                        .build());
                }
            }
            return balances;
        } catch (Exception e) {
            log.error("[BINANCE] Failed to get balances: {}", e.getMessage());
            throw new RuntimeException("Binance balance failed", e);
        }
    }

    public List<JsonNode> getOpenOrders(String apiKey, String secret, String symbol) {
        try {
            String params = "symbol=" + symbol + "&recvWindow=" + recvWindow
                + "&timestamp=" + System.currentTimeMillis();
            String signature = sign(params, secret);
            params += "&signature=" + signature;

            String url = defaultBaseUrl + "/api/v3/openOrders?" + params;
            String body = get(url, apiKey);
            return List.of(mapper.readTree(body));
        } catch (Exception e) {
            log.error("[BINANCE] Failed to get open orders: {}", e.getMessage());
            return List.of();
        }
    }

    // --- Helpers ---

    private String resolveBase(String baseUrl) {
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : defaultBaseUrl;
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    private String get(String url, String apiKey) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (apiKey != null) builder.header("X-MBX-APIKEY", apiKey);
        var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Binance error " + resp.statusCode() + ": " + resp.body());
        return resp.body();
    }

    private String post(String url, String apiKey) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-MBX-APIKEY", apiKey)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Binance error " + resp.statusCode() + ": " + resp.body());
        return resp.body();
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
