package com.tradeengine.integration;

import com.tradeengine.service.PositionTracker;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PositionTracker: CRUD operations, price watermarks, and thread safety.
 */
class PositionTrackerTest {

    private PositionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PositionTracker();
    }

    private PositionTracker.TrackedPosition buildPosition(UUID botId) {
        return PositionTracker.TrackedPosition.builder()
            .botId(botId).userId(UUID.randomUUID())
            .symbol("BTCUSDT").exchange("BINANCE").exchangeMode("TESTNET")
            .entryPrice(new BigDecimal("42000")).quantity(new BigDecimal("0.01"))
            .apiKey("key").apiSecret("secret").exchangeBaseUrl("http://localhost")
            .highestPriceSeen(new BigDecimal("42000"))
            .lowestPriceSeen(new BigDecimal("42000"))
            .openedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("Register and retrieve position")
    void registerAndGet() {
        UUID botId = UUID.randomUUID();
        tracker.registerPosition(buildPosition(botId));

        assertTrue(tracker.hasPosition(botId));
        assertEquals(1, tracker.getOpenPositionCount());
        assertEquals("BTCUSDT", tracker.getPosition(botId).get().getSymbol());
    }

    @Test
    @DisplayName("Remove position")
    void removePosition() {
        UUID botId = UUID.randomUUID();
        tracker.registerPosition(buildPosition(botId));
        tracker.removePosition(botId);

        assertFalse(tracker.hasPosition(botId));
        assertEquals(0, tracker.getOpenPositionCount());
    }

    @Test
    @DisplayName("Update price watermarks")
    void updatePriceExtremes() {
        UUID botId = UUID.randomUUID();
        tracker.registerPosition(buildPosition(botId));

        tracker.updatePriceExtremes(botId, new BigDecimal("43000"));
        assertEquals(new BigDecimal("43000"), tracker.getPosition(botId).get().getHighestPriceSeen());

        tracker.updatePriceExtremes(botId, new BigDecimal("41000"));
        assertEquals(new BigDecimal("41000"), tracker.getPosition(botId).get().getLowestPriceSeen());
    }

    @Test
    @DisplayName("Multiple positions tracked independently")
    void multiplePositions() {
        UUID bot1 = UUID.randomUUID();
        UUID bot2 = UUID.randomUUID();
        tracker.registerPosition(buildPosition(bot1));
        tracker.registerPosition(buildPosition(bot2));

        assertEquals(2, tracker.getOpenPositionCount());
        tracker.removePosition(bot1);
        assertEquals(1, tracker.getOpenPositionCount());
        assertTrue(tracker.hasPosition(bot2));
    }

    @Test
    @DisplayName("Thread safety: concurrent register/remove")
    void threadSafety() throws Exception {
        int threads = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        UUID botId = UUID.randomUUID();
                        tracker.registerPosition(buildPosition(botId));
                        tracker.updatePriceExtremes(botId, new BigDecimal("43000"));
                        tracker.removePosition(botId);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, errors.get(), "No thread safety errors should occur");
        assertEquals(0, tracker.getOpenPositionCount());
    }
}
