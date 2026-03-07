package com.tradeengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate signals within a short interval.
 * Ignores identical BUY/SELL signals for the same bot within the debounce window.
 */
@Service
@Slf4j
public class SignalDebounceService {

    private static final long DEBOUNCE_SECONDS = 5;

    // Key: "botId:signal" → last signal timestamp
    private final Map<String, Instant> signalCache = new ConcurrentHashMap<>();

    /**
     * Returns true if the signal should be processed (not a duplicate).
     */
    public boolean shouldProcess(UUID botId, String signal) {
        String key = botId + ":" + signal;
        Instant now = Instant.now();
        Instant lastSignal = signalCache.get(key);

        if (lastSignal != null && now.minusSeconds(DEBOUNCE_SECONDS).isBefore(lastSignal)) {
            log.debug("[DEBOUNCE] Ignoring duplicate {} signal for bot {} (within {}s window)",
                signal, botId, DEBOUNCE_SECONDS);
            return false;
        }

        signalCache.put(key, now);
        return true;
    }

    /**
     * Clear debounce state for a bot (e.g., when bot is stopped).
     */
    public void clearBot(UUID botId) {
        signalCache.entrySet().removeIf(e -> e.getKey().startsWith(botId.toString()));
    }
}
