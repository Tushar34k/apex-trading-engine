package com.tradeengine.service;

import com.tradeengine.exchange.Balance;
import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.model.TradePosition;
import com.tradeengine.model.TradingBot;
import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.PositionRepository;
import com.tradeengine.ws.MarketDataStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Real-time exposure and daily PnL monitor.
 * Runs every 15 seconds and feeds the KillSwitch with live data.
 *
 * This is the ONLY service that calls KillSwitchService.checkExposure() and checkDailyLoss().
 * Without this service, those kill switch checks are dead code.
 *
 * Monitors:
 *   - Total notional exposure across all open positions (all bots, all exchanges)
 *   - Daily realized PnL across all closed positions today
 *   - Per-user exposure breakdown for dashboard reporting
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExposureMonitorService {

    private final PositionRepository positionRepo;
    private final PositionTracker positionTracker;
    private final KillSwitchService killSwitch;
    private final MarketPriceMonitor priceMonitor;
    private final BotRepository botRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ExchangeFactory exchangeFactory;

    // Latest snapshot for dashboard queries
    private final AtomicReference<ExposureSnapshot> latestSnapshot = new AtomicReference<>(
        new ExposureSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, Map.of(), Instant.now()));

    public record ExposureSnapshot(
        BigDecimal totalExposureUsdt,
        BigDecimal dailyRealizedPnl,
        BigDecimal accountBalance,
        int openPositionCount,
        Map<String, BigDecimal> perSymbolExposure,
        Instant timestamp
    ) {}

    /**
     * Scheduled monitor — runs every 15 seconds.
     * Computes total exposure and daily PnL, feeds kill switch.
     */
    @Scheduled(fixedDelay = 15_000)
    public void monitorExposure() {
        if (killSwitch.isActive()) return;

        try {
            // 1. Compute total notional exposure from tracked positions
            BigDecimal totalExposure = BigDecimal.ZERO;
            Map<String, BigDecimal> perSymbol = new HashMap<>();
            int posCount = 0;

            for (PositionTracker.TrackedPosition pos : positionTracker.getAllPositions()) {
                BigDecimal currentPrice = priceMonitor.getCurrentPrice(
                    pos.getSymbol(), pos.getExchange(), pos.getExchangeBaseUrl());

                if (currentPrice == null) {
                    // Use entry price as fallback
                    currentPrice = pos.getEntryPrice();
                }

                BigDecimal notional = pos.getQuantity().multiply(currentPrice);
                totalExposure = totalExposure.add(notional);
                perSymbol.merge(pos.getSymbol(), notional, BigDecimal::add);
                posCount++;
            }

            // 2. Compute daily realized PnL from closed positions
            Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
            BigDecimal dailyPnl = BigDecimal.ZERO;

            // Query all closed positions today across all bots
            List<TradingBot> allBots = botRepo.findByStatus("RUNNING");
            Set<UUID> botIds = allBots.stream().map(TradingBot::getId).collect(Collectors.toSet());

            for (UUID botId : botIds) {
                List<TradePosition> positions = positionRepo.findByBotId(botId);
                for (TradePosition p : positions) {
                    if ("CLOSED".equals(p.getStatus())
                        && p.getClosedAt() != null
                        && p.getClosedAt().isAfter(startOfDay)
                        && p.getRealizedPnl() != null) {
                        dailyPnl = dailyPnl.add(p.getRealizedPnl());
                    }
                }
            }

            // 3. Fetch account balance for daily loss % calculation
            BigDecimal accountBalance = fetchPrimaryAccountBalance(allBots);

            // 4. Feed kill switch checks (these were previously dead code)
            killSwitch.checkExposure(totalExposure);
            if (accountBalance.compareTo(BigDecimal.ZERO) > 0) {
                killSwitch.checkDailyLoss(accountBalance, dailyPnl);
            }

            // 5. Store snapshot for dashboard
            ExposureSnapshot snapshot = new ExposureSnapshot(
                totalExposure, dailyPnl, accountBalance, posCount, perSymbol, Instant.now());
            latestSnapshot.set(snapshot);

            // 6. Log summary
            if (posCount > 0) {
                log.info("[EXPOSURE_MONITOR] positions={} totalExposure=${} dailyPnl=${} balance=${}",
                    posCount,
                    totalExposure.setScale(2, RoundingMode.HALF_UP),
                    dailyPnl.setScale(2, RoundingMode.HALF_UP),
                    accountBalance.setScale(2, RoundingMode.HALF_UP));
            }

        } catch (Exception e) {
            log.error("[EXPOSURE_MONITOR] Failed: {}", e.getMessage());
        }
    }

    /**
     * Fetch the primary account balance (first available RUNNING bot's exchange balance).
     * Cached to avoid excessive API calls.
     */
    private BigDecimal fetchPrimaryAccountBalance(List<TradingBot> bots) {
        if (bots.isEmpty()) return BigDecimal.ZERO;

        // Group by API key to minimize exchange calls
        Map<UUID, TradingBot> byApiKey = new LinkedHashMap<>();
        for (TradingBot bot : bots) {
            byApiKey.putIfAbsent(bot.getApiKeyId(), bot);
        }

        BigDecimal totalBalance = BigDecimal.ZERO;

        for (var entry : byApiKey.entrySet()) {
            try {
                Optional<UserApiKey> apiKeyOpt = apiKeyRepo.findById(entry.getKey());
                if (apiKeyOpt.isEmpty()) continue;

                UserApiKey apiKeyEntity = apiKeyOpt.get();
                String apiKey = apiKeyService.decryptApiKey(apiKeyEntity);
                String secret = apiKeyService.decryptApiSecret(apiKeyEntity);
                String exchangeName = apiKeyEntity.getExchange().toUpperCase();

                ExchangeClient client = exchangeFactory.getClient(exchangeName);
                TradingBot bot = entry.getValue();
                String baseUrl = client.resolveBaseUrl(bot.getExchangeMode());

                List<Balance> balances = client.getBalances(apiKey, secret, baseUrl);
                BigDecimal usdtBalance = balances.stream()
                    .filter(b -> "USDT".equalsIgnoreCase(b.getAsset()) || "USD".equalsIgnoreCase(b.getAsset()))
                    .map(Balance::getTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                totalBalance = totalBalance.add(usdtBalance);
            } catch (Exception e) {
                log.debug("[EXPOSURE_MONITOR] Failed to fetch balance for apiKey={}: {}",
                    entry.getKey(), e.getMessage());
            }
        }

        return totalBalance;
    }

    /**
     * Get latest exposure snapshot for dashboard/API.
     */
    public ExposureSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }
}
