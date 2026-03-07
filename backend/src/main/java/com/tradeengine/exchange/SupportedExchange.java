package com.tradeengine.exchange;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enum of supported exchanges for validation.
 */
public enum SupportedExchange {
    BINANCE,
    DELTA,
    BYBIT;

    private static final Set<String> NAMES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toSet());

    public static boolean isSupported(String exchange) {
        return exchange != null && NAMES.contains(exchange.toUpperCase());
    }

    public static void validate(String exchange) {
        if (!isSupported(exchange)) {
            throw new IllegalArgumentException(
                "Unsupported exchange: '" + exchange + "'. Supported: " + NAMES);
        }
    }
}
