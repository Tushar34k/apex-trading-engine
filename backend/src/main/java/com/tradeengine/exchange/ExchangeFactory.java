package com.tradeengine.exchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Factory that resolves the correct ExchangeClient based on exchange name.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeFactory {

    private final BinanceClient binanceClient;
    private final DeltaClient deltaClient;
    private final BybitClient bybitClient;

    /**
     * Returns the ExchangeClient for the given exchange name.
     *
     * @param exchange one of BINANCE, DELTA, BYBIT (case-insensitive)
     * @throws IllegalArgumentException if exchange is not supported
     */
    public ExchangeClient getClient(String exchange) {
        SupportedExchange.validate(exchange);

        return switch (exchange.toUpperCase()) {
            case "BINANCE" -> binanceClient;
            case "DELTA" -> deltaClient;
            case "BYBIT" -> bybitClient;
            default -> throw new IllegalArgumentException("No client for exchange: " + exchange);
        };
    }
}
