package com.tradeengine.controller;

import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.service.CircuitBreakerService;
import com.tradeengine.service.KillSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemMetricsController {

    private final TradeExecutionQueue executionQueue;
    private final BotRepository botRepo;
    private final PositionRepository positionRepo;
    private final KillSwitchService killSwitch;
    private final CircuitBreakerService circuitBreaker;

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();

        // Execution queue
        m.put("queueSize", executionQueue.getQueueSize());
        m.put("totalSubmitted", executionQueue.getTotalSubmitted());
        m.put("totalExecuted", executionQueue.getTotalExecuted());
        m.put("totalFailed", executionQueue.getTotalFailed());

        // Bots
        m.put("totalBots", botRepo.count());
        m.put("runningBots", botRepo.findByStatus("RUNNING").size());

        // Positions
        long openPositions = positionRepo.findAll().stream()
            .filter(p -> "OPEN".equals(p.getStatus())).count();
        m.put("openPositions", openPositions);

        // Kill switch
        Map<String, Object> ks = new LinkedHashMap<>();
        ks.put("active", killSwitch.isActive());
        ks.put("reason", killSwitch.getActivationReason());
        ks.put("activatedAt", killSwitch.getActivatedAt());
        m.put("killSwitch", ks);

        // Exchange health
        Map<String, Object> exchange = new LinkedHashMap<>();
        exchange.put("circuitBreakerOpen", circuitBreaker.isOpen());
        exchange.put("recentErrors", circuitBreaker.getRecentFailureCount());
        exchange.put("killSwitchErrors", killSwitch.getRecentErrorCount());
        m.put("exchangeHealth", exchange);

        return m;
    }

    @PostMapping("/kill-switch/activate")
    public Map<String, Object> activateKillSwitch(@RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "Manual activation");
        killSwitch.activate(reason);
        return Map.of("status", "activated", "reason", reason);
    }

    @PostMapping("/kill-switch/reset")
    public Map<String, Object> resetKillSwitch() {
        killSwitch.reset();
        return Map.of("status", "reset");
    }
}
