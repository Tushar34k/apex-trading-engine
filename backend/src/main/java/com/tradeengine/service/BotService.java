package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.model.TradingBot;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.ws.MarketDataStreamService;
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
    private final ApiKeyRepository apiKeyRepo;
    private final ExchangeFactory exchangeFactory;
    private final MarketDataStreamService streamService;
    private final KillSwitchService killSwitch;

    @Value("${live-trading.enabled:false}")
    private boolean liveTradingEnabled;

    public void startBot(UUID botId, UUID userId) {
        TradingBot bot = botRepo.findById(botId)
            .orElseThrow(() -> new RuntimeException("Bot not found"));
        if (!bot.getUserId().equals(userId)) throw new RuntimeException("Not authorized");

        if (killSwitch.isActive()) {
            throw new RuntimeException("Cannot start bot — kill switch is active: " + killSwitch.getActivationReason());
        }

        if ("LIVE".equalsIgnoreCase(bot.getExchangeMode()) && !liveTradingEnabled) {
            throw new RuntimeException("Live trading is disabled. Set LIVE_TRADING_ENABLED=true to enable.");
        }

        // Resolve exchange from API key
        UserApiKey apiKey = apiKeyRepo.findById(bot.getApiKeyId())
            .orElseThrow(() -> new RuntimeException("API key not found for bot " + bot.getId()));
        String exchangeName = apiKey.getExchange().toUpperCase();

        bot.setStatus("RUNNING");
        bot.setStartedAt(Instant.now());
        bot.setStoppedAt(null);
        bot.setProcessing(false);
        botRepo.save(bot);

        // Subscribe to market data via exchange-agnostic stream service
        boolean testnet = !"LIVE".equalsIgnoreCase(bot.getExchangeMode()) || !liveTradingEnabled;
        streamService.subscribe(exchangeName, bot.getSymbol(), testnet);

        log.info("[BOT_START] botId={} name={} symbol={} exchange={} strategy={} EMA({}/{}) {}% mode={}",
            botId, bot.getName(), bot.getSymbol(), exchangeName, bot.getStrategyType(),
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
        log.info("[BOT_STOP] botId={} name={} symbol={}", botId, bot.getName(), bot.getSymbol());
    }
}
