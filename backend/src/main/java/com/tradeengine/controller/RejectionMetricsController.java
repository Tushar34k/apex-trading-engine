package com.tradeengine.controller;

import com.tradeengine.service.RejectionMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * GET /api/debug/rejection-breakdown/{botId}?window=1h
 *
 * Returns a map of "STAGE:REASON" → count over the requested window.
 * Window format: e.g. 15m, 1h, 24h. Defaults to 1h.
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class RejectionMetricsController {

    private final RejectionMetricsService metrics;

    @GetMapping("/rejection-breakdown/{botId}")
    public ResponseEntity<Map<String, Long>> breakdown(
            @PathVariable String botId,
            @RequestParam(defaultValue = "1h") String window) {
        Duration d = parseWindow(window);
        return ResponseEntity.ok(metrics.breakdown(botId, d));
    }

    @GetMapping("/rejection-recent/{botId}")
    public ResponseEntity<?> recent(@PathVariable String botId,
                                    @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(metrics.recent(botId, Math.min(limit, 1000)));
    }

    private Duration parseWindow(String w) {
        try {
            if (w.endsWith("h")) return Duration.ofHours(Long.parseLong(w.substring(0, w.length() - 1)));
            if (w.endsWith("m")) return Duration.ofMinutes(Long.parseLong(w.substring(0, w.length() - 1)));
            if (w.endsWith("s")) return Duration.ofSeconds(Long.parseLong(w.substring(0, w.length() - 1)));
        } catch (Exception ignored) {}
        return Duration.ofHours(1);
    }
}
