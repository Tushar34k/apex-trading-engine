package com.tradeengine.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Exposes the sizing audit trail and API health status to the frontend dashboard.
 */
@RestController
@RequestMapping("/api/risk-monitor")
@RequiredArgsConstructor
@Slf4j
public class RiskMonitorController {

    // Shared singleton — populated by StrategyRunner via static access
    private static final ConcurrentLinkedDeque<Map<String, Object>> sizingAuditLog = new ConcurrentLinkedDeque<>();
    private static final int MAX_AUDIT_ENTRIES = 100;

    private static volatile Map<String, Object> apiHealthStatus = Map.of(
        "status", "HEALTHY",
        "exchanges", Map.of()
    );

    /**
     * Called by StrategyRunner/TradeExecutionQueue to record a sizing evaluation.
     */
    public static void recordSizingEvaluation(Map<String, Object> entry) {
        sizingAuditLog.addFirst(entry);
        while (sizingAuditLog.size() > MAX_AUDIT_ENTRIES) {
            sizingAuditLog.pollLast();
        }
    }

    /**
     * Called by execution queue or exchange clients when a 401/auth error occurs.
     */
    public static void recordApiAuthError(String exchange, String message) {
        Map<String, Object> exchanges = new LinkedHashMap<>();
        // Preserve existing exchange statuses
        Object existing = apiHealthStatus.get("exchanges");
        if (existing instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existingMap = (Map<String, Object>) existing;
            exchanges.putAll(existingMap);
        }
        exchanges.put(exchange, Map.of(
            "status", "API_BLOCKED",
            "error", message,
            "timestamp", System.currentTimeMillis()
        ));

        Map<String, Object> newStatus = new LinkedHashMap<>();
        newStatus.put("status", "API_BLOCKED");
        newStatus.put("exchanges", exchanges);
        apiHealthStatus = newStatus;
    }

    /**
     * Clear API health error for an exchange (e.g., after successful reconnect).
     */
    public static void clearApiAuthError(String exchange) {
        Map<String, Object> exchanges = new LinkedHashMap<>();
        Object existing = apiHealthStatus.get("exchanges");
        if (existing instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existingMap = (Map<String, Object>) existing;
            exchanges.putAll(existingMap);
        }
        exchanges.remove(exchange);

        Map<String, Object> newStatus = new LinkedHashMap<>();
        newStatus.put("status", exchanges.isEmpty() ? "HEALTHY" : "API_BLOCKED");
        newStatus.put("exchanges", exchanges);
        apiHealthStatus = newStatus;
    }

    @GetMapping("/sizing-audit")
    public List<Map<String, Object>> getSizingAudit(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> entry : sizingAuditLog) {
            if (count++ >= limit) break;
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/api-health")
    public Map<String, Object> getApiHealth() {
        return apiHealthStatus;
    }
}
