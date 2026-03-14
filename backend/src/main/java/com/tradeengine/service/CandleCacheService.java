package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache layer for multi-timeframe candle data.
 * Prevents excessive API calls by caching candles with configurable TTLs per interval.
 *
 * TTLs:
 *   5m  → 60 seconds
 *   15m → 180 seconds (3 minutes)
 *   1h  → 600 seconds (10 minutes)
 *   default → 30 seconds
 */
@Service
@Slf4j
public class CandleCacheService {

    private static final ConcurrentHashMap<String, CachedCandles> cache = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> TTL_MAP = new ConcurrentHashMap<>();

    static {
        TTL_MAP.put("5m", 60L);
        TTL_MAP.put("15m", 180L);
        TTL_MAP.put("1h", 600L);
        TTL_MAP.put("1m", 30L);
    }

    /**
     * Get candles from cache or fetch from exchange if stale/missing.
     *
     * @return candle list, or empty list on fetch failure (never null)
     */
    public List<double[]> getCandles(ExchangeClient client, String apiKey, String secret,
                                      String symbol, String interval, int limit, String baseUrl) {
        String cacheKey = buildKey(client.getExchangeName(), symbol, interval);
        long ttlSeconds = TTL_MAP.getOrDefault(interval, 30L);

        CachedCandles cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(ttlSeconds)) {
            log.trace("[CANDLE_CACHE] HIT {}:{} (age={}s)", symbol, interval,
                Instant.now().getEpochSecond() - cached.fetchedAt);
            return cached.candles;
        }

        try {
            List<double[]> candles = client.getCandles(symbol, interval, limit, baseUrl);
            if (candles != null && !candles.isEmpty()) {
                cache.put(cacheKey, new CachedCandles(candles, Instant.now().getEpochSecond()));
                log.debug("[CANDLE_CACHE] REFRESH {}:{} count={}", symbol, interval, candles.size());
                return candles;
            }
        } catch (Exception e) {
            log.warn("[CANDLE_CACHE] FETCH_FAILED {}:{} error={}", symbol, interval, e.getMessage());
        }

        // Return stale data if available, otherwise empty
        if (cached != null) {
            log.warn("[CANDLE_CACHE] STALE_FALLBACK {}:{} (age={}s)", symbol, interval,
                Instant.now().getEpochSecond() - cached.fetchedAt);
            return cached.candles;
        }
        return Collections.emptyList();
    }

    /**
     * Invalidate cache for a specific symbol/interval.
     */
    public void invalidate(String exchange, String symbol, String interval) {
        cache.remove(buildKey(exchange, symbol, interval));
    }

    /**
     * Clear all cached data.
     */
    public void clearAll() {
        cache.clear();
    }

    private String buildKey(String exchange, String symbol, String interval) {
        return exchange + ":" + symbol + ":" + interval;
    }

    private record CachedCandles(List<double[]> candles, long fetchedAt) {
        boolean isExpired(long ttlSeconds) {
            return Instant.now().getEpochSecond() - fetchedAt > ttlSeconds;
        }
    }
}
