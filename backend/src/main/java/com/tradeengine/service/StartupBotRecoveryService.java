package com.tradeengine.service;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * On server restart:
 * 1. Clears stale isProcessing flags for RUNNING bots
 * 2. Recovers positions from exchange state via PositionSyncService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StartupBotRecoveryService {

    private final BotRepository botRepo;
    private final PositionSyncService positionSyncService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverBots() {
        // Step 1: Clear stale processing locks
        List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");
        int recovered = 0;
        for (TradingBot bot : runningBots) {
            if (bot.isProcessing()) {
                bot.setProcessing(false);
                botRepo.save(bot);
                recovered++;
            }
        }
        log.info("Bot recovery: {} RUNNING bots found, {} had stale processing locks cleared",
            runningBots.size(), recovered);

        // Step 2: Recover positions from exchange
        try {
            positionSyncService.recoverPositionsOnStartup();
        } catch (Exception e) {
            log.error("Startup position recovery failed: {}", e.getMessage(), e);
        }
    }
}
