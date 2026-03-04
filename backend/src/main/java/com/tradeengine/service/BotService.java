package com.tradeengine.service;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotService {

    private final BotRepository botRepo;

    public void startBot(UUID botId, UUID userId) {
        TradingBot bot = botRepo.findById(botId)
            .orElseThrow(() -> new RuntimeException("Bot not found"));
        if (!bot.getUserId().equals(userId)) throw new RuntimeException("Not authorized");

        bot.setStatus("RUNNING");
        bot.setStartedAt(Instant.now());
        botRepo.save(bot);
        log.info("Bot {} started for symbol {}", botId, bot.getSymbol());
    }

    public void stopBot(UUID botId, UUID userId) {
        TradingBot bot = botRepo.findById(botId)
            .orElseThrow(() -> new RuntimeException("Bot not found"));
        if (!bot.getUserId().equals(userId)) throw new RuntimeException("Not authorized");

        bot.setStatus("STOPPED");
        bot.setStoppedAt(Instant.now());
        botRepo.save(bot);
        log.info("Bot {} stopped", botId);
    }
}
