package com.tradeengine.service;

import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.execution.TradeRequest;
import com.tradeengine.service.PositionTracker.TrackedPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Scheduled risk manager that monitors all tracked positions every 3 seconds.
 * Automatically triggers SELL orders when SL/TP/trailing stop conditions are met.
 * Uses the existing TradeExecutionQueue for safe, serialized order submission.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionRiskManager {

    private final PositionTracker positionTracker;
    private final MarketPriceMonitor priceMonitor;
    private final TradeExecutionQueue executionQueue;
    private final KillSwitchService killSwitch;
    private final TrailingStopService trailingStopService;

    @Scheduled(fixedDelay = 3000)
    public void checkPositions() {
        if (killSwitch.isActive()) return;

        for (TrackedPosition pos : positionTracker.getAllPositions()) {
            try {
                checkPosition(pos);
            } catch (Exception e) {
                log.error("[RISK_MANAGER] Error checking position botId={}: {}",
                    pos.getBotId(), e.getMessage(), e);
            }
        }
    }

    private void checkPosition(TrackedPosition pos) {
        BigDecimal currentPrice = priceMonitor.getCurrentPrice(
            pos.getSymbol(), pos.getExchange(), pos.getExchangeBaseUrl());

        if (currentPrice == null) {
            log.debug("[RISK_MANAGER] No price available for {} on {}", pos.getSymbol(), pos.getExchange());
            return;
        }

        // Update price extremes
        positionTracker.updatePriceExtremes(pos.getBotId(), currentPrice);

        // --- Stop Loss ---
        if (pos.getStopLossPrice() != null) {
            if (currentPrice.compareTo(pos.getStopLossPrice()) <= 0) {
                log.warn("[POSITION_CLOSED] reason=STOP_LOSS botId={} symbol={} price={} stopLoss={}",
                    pos.getBotId(), pos.getSymbol(), currentPrice, pos.getStopLossPrice());
                submitExitOrder(pos, "BOT_SL");
                return;
            }
        }

        // --- Take Profit ---
        if (pos.getTakeProfitPrice() != null) {
            if (currentPrice.compareTo(pos.getTakeProfitPrice()) >= 0) {
                log.info("[POSITION_CLOSED] reason=TAKE_PROFIT botId={} symbol={} price={} takeProfit={}",
                    pos.getBotId(), pos.getSymbol(), currentPrice, pos.getTakeProfitPrice());
                submitExitOrder(pos, "BOT_TP");
                return;
            }
        }

        // --- Trailing Stop ---
        if (pos.getTrailingStopPercent() != null) {
            boolean triggered = trailingStopService.checkTrailingStop(
                pos.getBotId(), currentPrice, pos.getEntryPrice(),
                pos.getTrailingStopPercent().doubleValue());

            if (triggered) {
                BigDecimal hwm = trailingStopService.getHighWaterMark(pos.getBotId());
                log.warn("[POSITION_CLOSED] reason=TRAILING_STOP botId={} symbol={} price={} hwm={} trailingPct={}%",
                    pos.getBotId(), pos.getSymbol(), currentPrice, hwm, pos.getTrailingStopPercent());
                submitExitOrder(pos, "BOT_TRAILING_SL");
                return;
            }
        }
    }

    private void submitExitOrder(TrackedPosition pos, String notificationType) {
        // Mark position as exiting to prevent duplicate exit attempts
        // Do NOT remove from tracker yet — only remove after successful execution
        // to preserve tracking if the trade fails
        positionTracker.removePosition(pos.getBotId());

        TradeRequest request = TradeRequest.builder()
            .botId(pos.getBotId())
            .userId(pos.getUserId())
            .symbol(pos.getSymbol())
            .side("SELL")
            .quantity(pos.getQuantity())
            .orderType("MARKET")
            .apiKey(pos.getApiKey())
            .apiSecret(pos.getApiSecret())
            .exchangeBaseUrl(pos.getExchangeBaseUrl())
            .exchange(pos.getExchange())
            .exchangeMode(pos.getExchangeMode())
            .notificationType(notificationType)
            .timestamp(Instant.now())
            .build();

        executionQueue.submitTrade(request);

        // Re-register position if trade submission fails so it can be retried
        request.getResultFuture()
            .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .thenAccept(result -> {
                if (!result.isSuccess()) {
                    log.error("[RISK_EXIT_FAILED] Re-registering position for botId={} symbol={} — trade failed: {}",
                        pos.getBotId(), pos.getSymbol(), result.getErrorMessage());
                    positionTracker.registerPosition(pos);
                }
            })
            .exceptionally(ex -> {
                log.error("[RISK_EXIT_TIMEOUT] Re-registering position for botId={} symbol={} — {}",
                    pos.getBotId(), pos.getSymbol(), ex.getMessage());
                positionTracker.registerPosition(pos);
                return null;
            });

        log.info("[RISK_EXIT] Submitted SELL for botId={} symbol={} qty={} reason={}",
            pos.getBotId(), pos.getSymbol(), pos.getQuantity(), notificationType);
    }
}
