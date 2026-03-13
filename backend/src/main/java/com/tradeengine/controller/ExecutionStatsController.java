package com.tradeengine.controller;

import com.tradeengine.execution.TradeExecutionQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class ExecutionStatsController {

    private final TradeExecutionQueue executionQueue;

    @GetMapping("/execution-stats")
    public Map<String, Object> getExecutionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("queueSize", executionQueue.getQueueSize());
        stats.put("queueCapacity", executionQueue.getQueueCapacity());
        stats.put("queueUsagePercent", Math.round(executionQueue.getQueueUsagePercent() * 10) / 10.0);
        stats.put("pendingBots", executionQueue.getPendingBotsCount());
        stats.put("totalSubmitted", executionQueue.getTotalSubmitted());
        stats.put("totalExecuted", executionQueue.getTotalExecuted());
        stats.put("totalRejected", executionQueue.getTotalRejected());
        stats.put("totalFailed", executionQueue.getTotalFailed());
        stats.put("successRate", Math.round(executionQueue.getSuccessRate() * 10) / 10.0);
        stats.put("avgLatencyMs", Math.round(executionQueue.getAvgLatencyMs() * 10) / 10.0);
        stats.put("recentRejections", executionQueue.getRecentRejections().stream()
            .map(r -> Map.of(
                "timestamp", r.timestamp(),
                "botId", r.botId().toString(),
                "symbol", r.symbol(),
                "reason", r.reason()
            )).toList());
        return stats;
    }
}
