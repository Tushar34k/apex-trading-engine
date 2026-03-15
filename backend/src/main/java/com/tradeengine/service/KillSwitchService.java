package com.tradeengine.service;

import com.tradeengine.execution.TradeExecutionQueue;
import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.PositionRepository;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global kill switch — halts all trading when critical thresholds are breached.
 *
 * When activated:
 * - Stops all running bots
 * - Cancels pending queue trades
 * - Blocks new trade submissions
 * - Notifies all affected users
 * - Requires manual reset
 */
@Service
@Slf4j
public class KillSwitchService {

    private final BotRepository botRepo;
    private final NotificationService notificationService;

    // Lazily injected to break circular dependency
    @Setter
    private TradeExecutionQueue executionQueue;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile String activationReason = null;
    private volatile Instant activatedAt = null;

    private volatile double maxDailyLossPercent = 5.0;
    private volatile double maxTotalExposureUsdt = 100_000.0;
    private volatile int maxExchangeErrorsPerMinute = 5;
    private volatile int maxConsecutiveFailures = 5;

    private final ConcurrentLinkedQueue<Instant> recentErrors = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures = new java.util.concurrent.atomic.AtomicInteger(0);

    public KillSwitchService(BotRepository botRepo, NotificationService notificationService,
                             @Lazy TradeExecutionQueue executionQueue) {
        this.botRepo = botRepo;
        this.notificationService = notificationService;
        this.executionQueue = executionQueue;
    }

    public boolean isActive() {
        return active.get();
    }

    public String getActivationReason() {
        return activationReason;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    /**
     * Activate the kill switch — stops all bots, cancels queued trades, blocks new trades.
     */
    public void activate(String reason) {
        if (active.compareAndSet(false, true)) {
            activationReason = reason;
            activatedAt = Instant.now();
            log.error("[KILL_SWITCH_ACTIVATED] reason={}", reason);

            // 1. Cancel all pending trades in the execution queue
            if (executionQueue != null) {
                int cancelled = executionQueue.cancelPendingTrades();
                log.warn("[KILL_SWITCH_ACTIVATED] Cancelled {} pending queue trades", cancelled);
            }

            // 2. Stop all running bots
            List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");
            for (TradingBot bot : runningBots) {
                bot.setStatus("STOPPED");
                bot.setStoppedAt(Instant.now());
                bot.setProcessing(false);
                botRepo.save(bot);
                log.warn("[KILL_SWITCH_ACTIVATED] botId={} name={} symbol={} — stopped",
                    bot.getId(), bot.getName(), bot.getSymbol());
            }

            // 3. Notify all affected users
            runningBots.stream()
                .map(b -> b.getUserId().toString())
                .distinct()
                .forEach(userId -> notificationService.notifyKillSwitch(userId, reason));

            log.error("[KILL_SWITCH_ACTIVATED] {} bots stopped. Manual reset required.", runningBots.size());
        }
    }

    /**
     * Manual reset — only way to re-enable trading after kill switch.
     */
    public void reset() {
        if (active.compareAndSet(true, false)) {
            String prevReason = activationReason;
            activationReason = null;
            activatedAt = null;
            recentErrors.clear();
            consecutiveFailures.set(0);
            log.info("[KILL_SWITCH_RESET] Previous reason: {} — consecutive failures counter reset", prevReason);
        }
    }

    public void recordExchangeError() {
        Instant now = Instant.now();
        recentErrors.add(now);
        Instant cutoff = now.minus(1, ChronoUnit.MINUTES);
        recentErrors.removeIf(t -> t.isBefore(cutoff));

        int count = recentErrors.size();
        if (count >= maxExchangeErrorsPerMinute) {
            activate("Exchange error rate exceeded: " + count + " errors in 1 minute");
        }
    }

    public void checkDailyLoss(BigDecimal accountBalance, BigDecimal dailyPnl) {
        if (active.get() || accountBalance.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal lossPercent = dailyPnl.negate()
            .divide(accountBalance, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));

        if (dailyPnl.signum() < 0 && lossPercent.doubleValue() >= maxDailyLossPercent) {
            activate("Daily loss " + lossPercent.setScale(2, RoundingMode.HALF_UP) + "% exceeds limit of " + maxDailyLossPercent + "%");
        }
    }

    public void checkExposure(BigDecimal totalExposure) {
        if (active.get()) return;
        if (totalExposure.doubleValue() > maxTotalExposureUsdt) {
            activate("Total exposure $" + totalExposure.setScale(2, RoundingMode.HALF_UP) + " exceeds limit $" + maxTotalExposureUsdt);
        }
    }

    public int getRecentErrorCount() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);
        recentErrors.removeIf(t -> t.isBefore(cutoff));
        return recentErrors.size();
    }

    /**
     * Record a successful trade — resets consecutive failure counter.
     */
    public void recordTradeSuccess() {
        consecutiveFailures.set(0);
    }

    /**
     * Record a failed trade — increments counter and triggers kill switch if threshold exceeded.
     */
    public void recordTradeFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("[KILL_SWITCH] Consecutive trade failures: {}/{}", failures, maxConsecutiveFailures);
        if (failures >= maxConsecutiveFailures) {
            activate(failures + " consecutive trade failures exceeded limit of " + maxConsecutiveFailures);
        }
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public void setMaxDailyLossPercent(double pct) { this.maxDailyLossPercent = pct; }
    public void setMaxTotalExposureUsdt(double usdt) { this.maxTotalExposureUsdt = usdt; }
    public void setMaxExchangeErrorsPerMinute(int count) { this.maxExchangeErrorsPerMinute = count; }
    public void setMaxConsecutiveFailures(int count) { this.maxConsecutiveFailures = count; }

    public double getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public double getMaxTotalExposureUsdt() { return maxTotalExposureUsdt; }
    public int getMaxExchangeErrorsPerMinute() { return maxExchangeErrorsPerMinute; }
    public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
}
