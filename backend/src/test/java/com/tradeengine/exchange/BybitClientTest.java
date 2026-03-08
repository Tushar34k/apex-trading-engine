package com.tradeengine.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BybitClient utility methods.
 * Tests interval mapping, side capitalization, status mapping.
 */
class BybitClientTest {

    private BybitClient client;

    @BeforeEach
    void setUp() {
        client = new BybitClient();
    }

    // --- Interval mapping ---

    @Test
    void mapInterval_1m() throws Exception {
        assertEquals("1", invokeMapInterval("1m"));
    }

    @Test
    void mapInterval_5m() throws Exception {
        assertEquals("5", invokeMapInterval("5m"));
    }

    @Test
    void mapInterval_15m() throws Exception {
        assertEquals("15", invokeMapInterval("15m"));
    }

    @Test
    void mapInterval_1h() throws Exception {
        assertEquals("60", invokeMapInterval("1h"));
    }

    @Test
    void mapInterval_4h() throws Exception {
        assertEquals("240", invokeMapInterval("4h"));
    }

    @Test
    void mapInterval_1d() throws Exception {
        assertEquals("D", invokeMapInterval("1d"));
    }

    @Test
    void mapInterval_1D_uppercase() throws Exception {
        assertEquals("D", invokeMapInterval("1D"));
    }

    @Test
    void mapInterval_1w() throws Exception {
        assertEquals("W", invokeMapInterval("1w"));
    }

    @Test
    void mapInterval_null() throws Exception {
        assertEquals("1", invokeMapInterval(null));
    }

    @Test
    void mapInterval_unknown_passthrough() throws Exception {
        assertEquals("custom", invokeMapInterval("custom"));
    }

    // --- Side capitalization ---

    @Test
    void capitalizeBybitSide_buy() throws Exception {
        assertEquals("Buy", invokeCapitalizeSide("BUY"));
    }

    @Test
    void capitalizeBybitSide_sell() throws Exception {
        assertEquals("Sell", invokeCapitalizeSide("SELL"));
    }

    @Test
    void capitalizeBybitSide_lowercase() throws Exception {
        assertEquals("Buy", invokeCapitalizeSide("buy"));
    }

    @Test
    void capitalizeBybitSide_null() throws Exception {
        assertEquals("Buy", invokeCapitalizeSide(null));
    }

    // --- Status mapping ---

    @Test
    void mapBybitStatus_filled() throws Exception {
        assertEquals("FILLED", invokeMapStatus("Filled"));
    }

    @Test
    void mapBybitStatus_partiallyFilled() throws Exception {
        assertEquals("PARTIALLY_FILLED", invokeMapStatus("PartiallyFilled"));
    }

    @Test
    void mapBybitStatus_cancelled() throws Exception {
        assertEquals("CANCELLED", invokeMapStatus("Cancelled"));
    }

    @Test
    void mapBybitStatus_rejected() throws Exception {
        assertEquals("REJECTED", invokeMapStatus("Rejected"));
    }

    @Test
    void mapBybitStatus_new() throws Exception {
        assertEquals("NEW", invokeMapStatus("New"));
    }

    @Test
    void mapBybitStatus_unknown() throws Exception {
        assertEquals("UNKNOWN", invokeMapStatus("Unknown"));
    }

    // --- Exchange name ---

    @Test
    void getExchangeName() {
        assertEquals("BYBIT", client.getExchangeName());
    }

    // --- Reflection helpers for private methods ---

    private String invokeMapInterval(String interval) throws Exception {
        Method m = BybitClient.class.getDeclaredMethod("mapInterval", String.class);
        m.setAccessible(true);
        return (String) m.invoke(client, interval);
    }

    private String invokeCapitalizeSide(String side) throws Exception {
        Method m = BybitClient.class.getDeclaredMethod("capitalizeBybitSide", String.class);
        m.setAccessible(true);
        return (String) m.invoke(client, side);
    }

    private String invokeMapStatus(String status) throws Exception {
        Method m = BybitClient.class.getDeclaredMethod("mapBybitStatus", String.class);
        m.setAccessible(true);
        return (String) m.invoke(client, status);
    }
}
