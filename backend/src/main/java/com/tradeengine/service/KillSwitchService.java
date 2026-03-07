package com.tradeengine.service;

import com.tradeengine.model.TradingBot;
import com.tradeengine.repository.BotRepository;
import com.tradeengine.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global kill switch — halts all trading when critical thresholds are breached.
 *
 * Triggers:
 * - Daily loss exceeds configured percent
 * - Total exposure exceeds configured limit
 * - Exchange error rate exceeds threshold
 *
 * When activated: all bots stop, queue is blocked, manual reset required.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KillSwitchService {

    private final BotRepository botRepo;
    private final PositionRepository positionRepo;
    private final NotificationService notificationService;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile String activationReason = null;
    private volatile Instant activatedAt = null;

    // Configurable thresholds (defaults)
    private volatile double maxDailyLossPercent = 5.0;
    private volatile double maxTotalExposureUsdt = 100_000.0;
    private volatile int maxExchangeErrorsPerMinute = 5;

    // Error tracking
    private final java.util.concurrent.ConcurrentLinkedQueue<Instant> recentErrors = new java.util.concurrent.ConcurrentLinkedQueue<>();

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
     * Activate the kill switch — stops all bots and blocks trading.
     */
    public void activate(String reason) {
        if (active.compareAndSet(false, true)) {
            activationReason = reason;
            activatedAt = Instant.now();
            log.error("[KILL SWITCH] ACTIVATED: {}", reason);

            // Stop all running bots
            List<TradingBot> runningBots = botRepo.findByStatus("RUNNING");
            for (TradingBot bot : runningBots) {
                bot.setStatus("STOPPED");
                bot.setStoppedAt(Instant.now());
                bot.setProcessing(false);
                botRepo.save(bot);
                log.warn("[KILL SWITCH] Stopped bot {} [{}]", bot.getId(), bot.getName());
            }

            // Notify all affected users
            runningBots.stream()
                .map(b -> b.getUserId().toString())
                .distinct()
                .forEach(userId -> notificationService.notifyKillSwitch(userId, reason));

            log.error("[KILL SWITCH] {} bots stopped. Manual reset required.", runningBots.size());
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
            log.info("[KILL SWITCH] RESET. Previous reason: {}", prevReason);
        }
    }

    /**
     * Record an exchange error. If threshold exceeded, activate kill switch.
     */
    public void recordExchangeError() {
        Instant now = Instant.now();
        recentErrors.add(now);

        // Purge errors older than 1 minute
        Instant cutoff = now.minus(1, ChronoUnit.MINUTES);
        recentErrors.removeIf(t -> t.isBefore(cutoff));

        if (recentErrors.size() >= maxExchangeErrorsPerMinute) {
            activate("Exchange error rate exceeded: " + recentErrors.size() + " errors in 1 minute");
        }
    }

    /**
     * Check daily loss against threshold. Call periodically.
     */
    public void checkDailyLoss(BigDecimal accountBalance, BigDecimal dailyPnl) {
        if (active.get() || accountBalance.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal lossPercent = dailyPnl.negate()
            .divide(accountBalance, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));

        if (dailyPnl.signum() < 0 && lossPercent.doubleValue() >= maxDailyLossPercent) {
            activate("Daily loss " + lossPercent.setScale(2, RoundingMode.HALF_UP) + "% exceeds limit of " + maxDailyLossPercent + "%");
        }
    }

    /**
     * Check total exposure against threshold.
     */
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

    public void setMaxDailyLossPercent(double pct) { this.maxDailyLossPercent = pct; }
    public void setMaxTotalExposureUsdt(double usdt) { this.maxTotalExposureUsdt = usdt; }
    public void setMaxExchangeErrorsPerMinute(int count) { this.maxExchangeErrorsPerMinute = count; }

    public double getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public double getMaxTotalExposureUsdt() { return maxTotalExposureUsdt; }
    public int getMaxExchangeErrorsPerMinute() { return maxExchangeErrorsPerMinute; }
}
