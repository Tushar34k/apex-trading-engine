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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Connects to Binance WebSocket streams for real-time market data.
 * Subscribes to trade and kline streams per symbol.
 * Updates an in-memory price cache that the StrategyRunner reads.
 */
@Component
@Slf4j
public class BinanceStreamClient {

    private static final String WS_BASE = "wss://stream.binance.com:9443/stream?streams=";
    private static final String TESTNET_WS_BASE = "wss://testnet.binance.vision/stream?streams=";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Price cache: symbol -> latest price
    private final ConcurrentHashMap<String, Double> priceCache = new ConcurrentHashMap<>();
    // Candle cache: symbol -> latest kline data [time, open, high, low, close, volume]
    private final ConcurrentHashMap<String, double[]> candleCache = new ConcurrentHashMap<>();

    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, WebSocket> activeConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();

    private BiConsumer<String, Double> priceUpdateListener;

    public void setPriceUpdateListener(BiConsumer<String, Double> listener) {
        this.priceUpdateListener = listener;
    }

    /**
     * Subscribe to real-time price updates for a symbol.
     */
    public void subscribe(String symbol, boolean testnet) {
        String sym = symbol.toLowerCase();
        if (subscribedSymbols.contains(sym)) return;
        subscribedSymbols.add(sym);

        String streams = sym + "@trade/" + sym + "@kline_1m";
        String wsUrl = (testnet ? TESTNET_WS_BASE : WS_BASE) + streams;

        connectWebSocket(sym, wsUrl);
        log.info("[Stream] Subscribed to {} (testnet={})", symbol, testnet);
    }

    public void unsubscribe(String symbol) {
        String sym = symbol.toLowerCase();
        subscribedSymbols.remove(sym);
        WebSocket ws = activeConnections.remove(sym);
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "unsubscribe");
        }
        priceCache.remove(symbol.toUpperCase());
        candleCache.remove(symbol.toUpperCase());
        log.info("[Stream] Unsubscribed from {}", symbol);
    }

    public Double getLatestPrice(String symbol) {
        return priceCache.get(symbol.toUpperCase());
    }

    public double[] getLatestCandle(String symbol) {
        return candleCache.get(symbol.toUpperCase());
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
                if (priceUpdateListener != null) {
                    priceUpdateListener.accept(symbol, price);
                }
            } else if ("kline".equals(eventType)) {
                JsonNode k = data.get("k");
                double[] candle = new double[]{
                    k.get("t").asLong() / 1000.0,
                    k.get("o").asDouble(),
                    k.get("h").asDouble(),
                    k.get("l").asDouble(),
                    k.get("c").asDouble(),
                    k.get("v").asDouble()
                };
                candleCache.put(symbol, candle);
                // Also update price from kline close
                priceCache.put(symbol, candle[4]);
            }
        } catch (Exception e) {
            log.debug("[Stream] Parse error: {}", e.getMessage());
        }
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
        log.info("[Stream] All connections closed");
    }
}
