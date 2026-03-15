package com.tradeengine.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Connects to Binance WebSocket streams for real-time market data.
 * Maintains timestamped price cache for staleness detection.
 */
@Component
@Slf4j
public class BinanceStreamClient {

    // Binance Futures WebSocket streams (not Spot)
    private static final String WS_BASE = "wss://fstream.binance.com/stream?streams=";
    private static final String TESTNET_WS_BASE = "wss://stream.binancefuture.com/stream?streams=";
    private static final long STALE_THRESHOLD_MS = 5000;
    private static final long DEAD_STREAM_THRESHOLD_MS = 30_000; // 30s = stream considered dead

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Price cache: symbol -> latest price
    private final ConcurrentHashMap<String, Double> priceCache = new ConcurrentHashMap<>();
    // Price timestamp cache: symbol -> last update epoch millis
    private final ConcurrentHashMap<String, Long> priceTimestamps = new ConcurrentHashMap<>();
    // Candle cache: "SYMBOL:timeframe" -> latest kline data [time, open, high, low, close, volume]
    private final ConcurrentHashMap<String, double[]> candleCache = new ConcurrentHashMap<>();
    // Order book depth cache: symbol -> [totalBidVolume, totalAskVolume]
    private final ConcurrentHashMap<String, double[]> depthCache = new ConcurrentHashMap<>();

    // Stream health tracking
    private final ConcurrentHashMap<String, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> messageCounters = new ConcurrentHashMap<>();

    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, WebSocket> activeConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    // Reuse for health monitoring
    private final ScheduledExecutorService healthMonitor = Executors.newSingleThreadScheduledExecutor();

    private BiConsumer<String, Double> priceUpdateListener;

    {
        // Start health monitor — checks every 10s for dead streams
        healthMonitor.scheduleAtFixedRate(this::checkStreamHealth, 15, 10, TimeUnit.SECONDS);
    }

    public void setPriceUpdateListener(BiConsumer<String, Double> listener) {
        this.priceUpdateListener = listener;
    }

    public void subscribe(String symbol, boolean testnet) {
        String sym = symbol.toLowerCase();
        if (subscribedSymbols.contains(sym)) return;
        subscribedSymbols.add(sym);

        String streams = sym + "@trade/"
            + sym + "@kline_1m/"
            + sym + "@kline_5m/"
            + sym + "@kline_15m/"
            + sym + "@kline_1h/"
            + sym + "@depth20@100ms";
        String wsUrl = (testnet ? TESTNET_WS_BASE : WS_BASE) + streams;

        connectWebSocket(sym, wsUrl);
        log.info("[Stream] Subscribed to {} (testnet={}) with multi-TF + depth", symbol, testnet);
    }

    public void unsubscribe(String symbol) {
        String sym = symbol.toLowerCase();
        subscribedSymbols.remove(sym);
        WebSocket ws = activeConnections.remove(sym);
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "unsubscribe");
        }
        String upper = symbol.toUpperCase();
        priceCache.remove(upper);
        priceTimestamps.remove(upper);
        depthCache.remove(upper);
        candleCache.entrySet().removeIf(e -> e.getKey().startsWith(upper + ":"));
        log.info("[Stream] Unsubscribed from {}", symbol);
    }

    /**
     * Get latest cached price. Returns null if no data available.
     */
    public Double getLatestPrice(String symbol) {
        return priceCache.get(symbol.toUpperCase());
    }

    /**
     * Check if the cached price is fresh (updated within STALE_THRESHOLD_MS).
     */
    public boolean isPriceFresh(String symbol) {
        Long ts = priceTimestamps.get(symbol.toUpperCase());
        if (ts == null) return false;
        return (System.currentTimeMillis() - ts) < STALE_THRESHOLD_MS;
    }

    /**
     * Get latest price only if fresh, otherwise return null (caller should fallback to REST).
     */
    public Double getFreshPrice(String symbol) {
        if (isPriceFresh(symbol)) {
            return priceCache.get(symbol.toUpperCase());
        }
        return null;
    }

    public double[] getCandle(String symbol, String timeframe) {
        return candleCache.get(symbol.toUpperCase() + ":" + timeframe);
    }

    public double[] getLatestCandle(String symbol) {
        return getCandle(symbol, "1m");
    }

    public double[] getDepth(String symbol) {
        return depthCache.get(symbol.toUpperCase());
    }

    public boolean hasPrice(String symbol) {
        return priceCache.containsKey(symbol.toUpperCase());
    }

    private void connectWebSocket(String sym, String wsUrl) {
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    log.info("[Stream] Connected: {}", sym);
                    activeConnections.put(sym, webSocket);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        processMessage(buffer.toString(), sym);
                        buffer.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                    webSocket.sendPong(message);
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    log.warn("[Stream] Closed {}: {} {}", sym, statusCode, reason);
                    activeConnections.remove(sym);
                    scheduleReconnect(sym, wsUrl);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    log.error("[Stream] Error {}: {}", sym, error.getMessage());
                    activeConnections.remove(sym);
                    scheduleReconnect(sym, wsUrl);
                }
            });
    }

    private void processMessage(String json, String sym) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            String eventType = data.has("e") ? data.get("e").asText() : "";
            String symbol = data.has("s") ? data.get("s").asText() : sym.toUpperCase();

            if ("trade".equals(eventType)) {
                double price = data.get("p").asDouble();
                priceCache.put(symbol, price);
                priceTimestamps.put(symbol, System.currentTimeMillis());
                lastMessageTime.put(sym, System.currentTimeMillis());
                messageCounters.merge(sym, 1L, Long::sum);
                if (priceUpdateListener != null) {
                    priceUpdateListener.accept(symbol, price);
                }
            } else if ("kline".equals(eventType)) {
                JsonNode k = data.get("k");
                String interval = k.get("i").asText();
                double[] candle = new double[]{
                    k.get("t").asLong() / 1000.0,
                    k.get("o").asDouble(),
                    k.get("h").asDouble(),
                    k.get("l").asDouble(),
                    k.get("c").asDouble(),
                    k.get("v").asDouble()
                };
                candleCache.put(symbol + ":" + interval, candle);
                priceCache.put(symbol, candle[4]);
                priceTimestamps.put(symbol, System.currentTimeMillis());
            } else if ("depthUpdate".equals(eventType) || data.has("bids")) {
                processDepth(data, symbol);
            }
        } catch (Exception e) {
            log.debug("[Stream] Parse error: {}", e.getMessage());
        }
    }

    private void processDepth(JsonNode data, String symbol) {
        try {
            double totalBid = 0, totalAsk = 0;
            JsonNode bids = data.get("bids");
            JsonNode asks = data.get("asks");

            if (bids != null && bids.isArray()) {
                for (JsonNode bid : bids) totalBid += bid.get(1).asDouble();
            }
            if (asks != null && asks.isArray()) {
                for (JsonNode ask : asks) totalAsk += ask.get(1).asDouble();
            }
            depthCache.put(symbol, new double[]{totalBid, totalAsk});
        } catch (Exception e) {
            log.debug("[Stream] Depth parse error for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Detect dead WebSocket streams and force reconnect.
     * A stream is considered dead if no messages received for 30+ seconds.
     */
    private void checkStreamHealth() {
        long now = System.currentTimeMillis();
        for (String sym : subscribedSymbols) {
            Long lastMsg = lastMessageTime.get(sym);
            if (lastMsg != null && (now - lastMsg) > DEAD_STREAM_THRESHOLD_MS) {
                log.error("[STREAM_HEALTH] DEAD stream detected for {} — no messages for {}ms. Forcing reconnect.",
                    sym, now - lastMsg);

                // Force close and reconnect
                WebSocket ws = activeConnections.remove(sym);
                if (ws != null) {
                    try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "health-check-reconnect"); }
                    catch (Exception ignored) {}
                }

                // Clear stale price data
                String upper = sym.toUpperCase();
                priceCache.remove(upper);
                priceTimestamps.remove(upper);

                // Trigger reconnect
                boolean testnet = activeConnections.containsKey(sym + "_testnet");
                String streams = sym + "@trade/" + sym + "@kline_1m/" + sym + "@kline_5m/"
                    + sym + "@kline_15m/" + sym + "@kline_1h/" + sym + "@depth20@100ms";
                String wsUrl = (testnet ? TESTNET_WS_BASE : WS_BASE) + streams;
                connectWebSocket(sym, wsUrl);
            } else if (lastMsg == null && activeConnections.containsKey(sym)) {
                // Stream connected but never received a message — may be stuck
                log.warn("[STREAM_HEALTH] Stream {} connected but no messages received yet", sym);
            }
        }
    }

    /**
     * Get stream health metrics for monitoring dashboard.
     */
    public Map<String, Object> getStreamHealth() {
        Map<String, Object> health = new HashMap<>();
        long now = System.currentTimeMillis();

        for (String sym : subscribedSymbols) {
            Map<String, Object> symHealth = new HashMap<>();
            symHealth.put("connected", activeConnections.containsKey(sym));
            Long lastMsg = lastMessageTime.get(sym);
            symHealth.put("lastMessageMs", lastMsg != null ? now - lastMsg : -1);
            symHealth.put("totalMessages", messageCounters.getOrDefault(sym, 0L));
            symHealth.put("priceFresh", isPriceFresh(sym.toUpperCase()));
            symHealth.put("latestPrice", priceCache.get(sym.toUpperCase()));
            health.put(sym, symHealth);
        }

        health.put("activeStreams", activeConnections.size());
        health.put("subscribedSymbols", subscribedSymbols.size());
        return health;
    }

    private void scheduleReconnect(String sym, String wsUrl) {
        if (!subscribedSymbols.contains(sym)) return;
        reconnectExecutor.schedule(() -> {
            log.info("[Stream] Reconnecting {}", sym);
            connectWebSocket(sym, wsUrl);
        }, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        subscribedSymbols.clear();
        activeConnections.values().forEach(ws -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"));
        activeConnections.clear();
        reconnectExecutor.shutdown();
        healthMonitor.shutdown();
        log.info("[Stream] All connections closed");
    }
}
