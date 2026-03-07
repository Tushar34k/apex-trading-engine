package com.tradeengine.service;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.ws.BinanceStreamClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotService {

    private final BotRepository botRepo;
    private final BinanceStreamClient streamClient;
    private final KillSwitchService killSwitch;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    public void startBot(UUID botId, UUID userId) {
        TradingBot bot = botRepo.findById(botId)
            .orElseThrow(() -> new RuntimeException("Bot not found"));
        if (!bot.getUserId().equals(userId)) throw new RuntimeException("Not authorized");

        // Kill switch guard
        if (killSwitch.isActive()) {
            throw new RuntimeException("Cannot start bot — kill switch is active: " + killSwitch.getActivationReason());
        }

        // Live trading guard
        if ("LIVE".equalsIgnoreCase(bot.getExchangeMode()) && !liveTradingEnabled) {
            throw new RuntimeException("Live trading is disabled. Set LIVE_TRADING_ENABLED=true to enable.");
        }

        bot.setStatus("RUNNING");
        bot.setStartedAt(Instant.now());
        bot.setStoppedAt(null);
        bot.setProcessing(false);
        botRepo.save(bot);

        // Subscribe to market data for this bot's symbol
        boolean testnet = !"LIVE".equalsIgnoreCase(bot.getExchangeMode()) || !liveTradingEnabled;
        streamClient.subscribe(bot.getSymbol().toLowerCase(), testnet);

        log.info("Bot {} started: {} {} strategy={} EMA({}/{}) {}% mode={}",
            botId, bot.getName(), bot.getSymbol(), bot.getStrategyType(),
            bot.getFastEma(), bot.getSlowEma(), bot.getTradeSizePercent(), bot.getExchangeMode());
    }

    public void stopBot(UUID botId, UUID userId) {
        TradingBot bot = botRepo.findById(botId)
            .orElseThrow(() -> new RuntimeException("Bot not found"));
        if (!bot.getUserId().equals(userId)) throw new RuntimeException("Not authorized");

        bot.setStatus("STOPPED");
        bot.setStoppedAt(Instant.now());
        bot.setProcessing(false);
        botRepo.save(bot);
        log.info("Bot {} stopped: {} {}", botId, bot.getName(), bot.getSymbol());
    }
}
