package com.tradeengine.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exchange-agnostic symbol resolution service.
 *
 * Strategies use universal format (e.g. "BTC/USDT").
 * This service maps to exchange-native symbols (e.g. "BTCUSDT" for Binance, "BTCUSD" for Delta).
 *
 * If the incoming symbol is already exchange-native (no "/" separator), it passes through unchanged.
 *
 * Thread-safe: uses immutable snapshot after initialization.
 */
@Service
@ConfigurationProperties(prefix = "symbols")
@Slf4j
public class SymbolMapperService {

    /**
     * Populated from application.yml:
     * symbols:
     *   mappings:
     *     BTC/USDT:
     *       binance: BTCUSDT
     *       delta: BTCUSD
     *       bybit: BTCUSDT
     */
    private Map<String, Map<String, String>> mappings = new ConcurrentHashMap<>();

    // Immutable snapshot built at startup
    private volatile Map<String, Map<String, String>> resolvedMappings = Map.of();

    @PostConstruct
    public void init() {
        if (mappings == null || mappings.isEmpty()) {
            log.warn("[SYMBOL_MAPPER] No symbol mappings configured in application.yml");
            resolvedMappings = Map.of();
            return;
        }

        // Build immutable copy with uppercased keys
        var builder = new ConcurrentHashMap<String, Map<String, String>>();
        mappings.forEach((universal, exchangeMap) -> {
            var inner = new ConcurrentHashMap<String, String>();
            exchangeMap.forEach((exchange, nativeSymbol) ->
                inner.put(exchange.toUpperCase(), nativeSymbol.toUpperCase()));
            builder.put(universal.toUpperCase(), Collections.unmodifiableMap(inner));
        });
        resolvedMappings = Collections.unmodifiableMap(builder);

        log.info("[SYMBOL_MAPPER] Loaded {} universal symbol mappings", resolvedMappings.size());
        resolvedMappings.forEach((uni, map) ->
            log.debug("[SYMBOL_MAPPER] {} → {}", uni, map));
    }

    /**
     * Resolves a universal symbol to the exchange-native format.
     *
     * If the symbol contains "/" it's treated as universal and mapped.
     * If no "/" is present, it's assumed to already be exchange-native and returned as-is (backward compat).
     *
     * @param exchange     exchange name (BINANCE, DELTA, BYBIT)
     * @param symbol       universal (BTC/USDT) or native (BTCUSDT) symbol
     * @return exchange-native symbol
     * @throws IllegalArgumentException if no mapping exists for the universal symbol + exchange combo
     */
    public String resolveSymbol(String exchange, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalArgumentException("Exchange is required");
        }

        // Backward compatibility: if no "/" present, treat as already exchange-native
        if (!symbol.contains("/")) {
            return symbol.toUpperCase();
        }

        String key = symbol.toUpperCase();
        String ex = exchange.toUpperCase();

        Map<String, String> exchangeMap = resolvedMappings.get(key);
        if (exchangeMap == null) {
            throw new IllegalArgumentException(
                "No symbol mappings configured for universal symbol: " + key);
        }

        String nativeSymbol = exchangeMap.get(ex);
        if (nativeSymbol == null) {
            throw new IllegalArgumentException(
                "Unsupported symbol " + key + " for exchange " + ex +
                ". Available exchanges: " + exchangeMap.keySet());
        }

        return nativeSymbol;
    }

    /**
     * Reverse-lookup: find the universal symbol for a given exchange-native symbol.
     * Returns the native symbol itself if no mapping is found.
     */
    public String toUniversal(String exchange, String nativeSymbol) {
        if (nativeSymbol == null) return null;
        String ex = exchange != null ? exchange.toUpperCase() : "";
        String sym = nativeSymbol.toUpperCase();

        for (var entry : resolvedMappings.entrySet()) {
            String mapped = entry.getValue().get(ex);
            if (sym.equals(mapped)) {
                return entry.getKey();
            }
        }
        return sym; // fallback to native
    }

    /**
     * Check if a universal symbol has a mapping for the given exchange.
     */
    public boolean hasMapping(String exchange, String universalSymbol) {
        if (universalSymbol == null || !universalSymbol.contains("/")) return false;
        Map<String, String> map = resolvedMappings.get(universalSymbol.toUpperCase());
        return map != null && map.containsKey(exchange.toUpperCase());
    }

    // Setter for Spring @ConfigurationProperties binding
    public void setMappings(Map<String, Map<String, String>> mappings) {
        this.mappings = mappings;
    }

    public Map<String, Map<String, String>> getMappings() {
        return mappings;
    }
}
