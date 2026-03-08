package com.tradeengine.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TradeRequest validation logic.
 */
class TradeRequestTest {

    private TradeRequest.TradeRequestBuilder baseRequest() {
        return TradeRequest.builder()
            .botId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .symbol("BTCUSDT")
            .side("BUY")
            .quantity(new BigDecimal("0.001"))
            .orderType("MARKET")
            .apiKey("test-key")
            .apiSecret("test-secret")
            .exchange("BINANCE")
            .exchangeMode("TESTNET")
            .exchangeBaseUrl("https://testnet.binance.vision");
    }

    @Test
    void validate_valid_market_order() {
        assertDoesNotThrow(() -> baseRequest().build().validate());
    }

    @Test
    void validate_valid_limit_order() {
        assertDoesNotThrow(() -> baseRequest()
            .orderType("LIMIT")
            .price(new BigDecimal("50000"))
            .build().validate());
    }

    @Test
    void validate_missing_exchange() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().exchange(null).build().validate());
        assertTrue(ex.getMessage().contains("Exchange"));
    }

    @Test
    void validate_missing_symbol() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().symbol(null).build().validate());
        assertTrue(ex.getMessage().contains("Symbol"));
    }

    @Test
    void validate_missing_side() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().side(null).build().validate());
        assertTrue(ex.getMessage().contains("Side"));
    }

    @Test
    void validate_zero_quantity() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().quantity(BigDecimal.ZERO).build().validate());
        assertTrue(ex.getMessage().contains("Quantity"));
    }

    @Test
    void validate_negative_quantity() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().quantity(new BigDecimal("-1")).build().validate());
        assertTrue(ex.getMessage().contains("Quantity"));
    }

    @Test
    void validate_missing_apiKey() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().apiKey(null).build().validate());
        assertTrue(ex.getMessage().contains("API key"));
    }

    @Test
    void validate_missing_exchangeMode() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().exchangeMode(null).build().validate());
        assertTrue(ex.getMessage().contains("Exchange mode"));
    }

    @Test
    void validate_unsupported_orderType() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().orderType("STOP").build().validate());
        assertTrue(ex.getMessage().contains("Unsupported order type"));
    }

    @Test
    void validate_limit_without_price() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().orderType("LIMIT").build().validate());
        assertTrue(ex.getMessage().contains("Price"));
    }

    @Test
    void validate_limit_zero_price() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().orderType("LIMIT").price(BigDecimal.ZERO).build().validate());
        assertTrue(ex.getMessage().contains("Price"));
    }

    @Test
    void validate_trailing_stop_too_low() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().trailingStopPercent(new BigDecimal("0.01")).build().validate());
        assertTrue(ex.getMessage().contains("Trailing stop"));
    }

    @Test
    void validate_trailing_stop_too_high() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest().trailingStopPercent(new BigDecimal("25")).build().validate());
        assertTrue(ex.getMessage().contains("Trailing stop"));
    }

    @Test
    void validate_buy_sl_above_entry() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest()
                .orderType("LIMIT")
                .price(new BigDecimal("50000"))
                .stopLossPrice(new BigDecimal("55000"))
                .build().validate());
        assertTrue(ex.getMessage().contains("Stop loss"));
    }

    @Test
    void validate_buy_tp_below_entry() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> baseRequest()
                .orderType("LIMIT")
                .price(new BigDecimal("50000"))
                .takeProfitPrice(new BigDecimal("45000"))
                .build().validate());
        assertTrue(ex.getMessage().contains("Take profit"));
    }

    @Test
    void requestId_unique() {
        TradeRequest r1 = baseRequest().build();
        TradeRequest r2 = baseRequest().build();
        assertNotEquals(r1.getRequestId(), r2.getRequestId());
    }

    @Test
    void resultFuture_exists() {
        TradeRequest r = baseRequest().build();
        assertNotNull(r.getResultFuture());
        assertFalse(r.getResultFuture().isDone());
    }
}
