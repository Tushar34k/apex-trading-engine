package com.tradeengine.controller;

import com.tradeengine.execution.TradeExecutionQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/execution")
@RequiredArgsConstructor
public class ExecutionMetricsController {

    private final TradeExecutionQueue executionQueue;

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return Map.of(
            "queueSize", executionQueue.getQueueSize(),
            "totalSubmitted", executionQueue.getTotalSubmitted(),
            "totalExecuted", executionQueue.getTotalExecuted(),
            "totalFailed", executionQueue.getTotalFailed()
        );
    }
}
