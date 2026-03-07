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
 * On server restart, clears isProcessing flags for all RUNNING bots
 * so the scheduler can pick them up again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StartupBotRecoveryService {

    private final BotRepository botRepo;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverBots() {
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
    }
}
