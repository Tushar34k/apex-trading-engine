//package com.tradeengine.service;
//
//import com.tradeengine.exchange.ExchangeClient;
//import com.tradeengine.exchange.ExchangeFactory;
//import com.tradeengine.ws.MarketDataStreamService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.math.BigDecimal;
//
///**
// * Provides current market prices using WebSocket stream data with REST fallback.
// * Exchange-agnostic: delegates to MarketDataStreamService, falls back to REST via ExchangeFactory.
// */
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class MarketPriceMonitor {
//
//    private final MarketDataStreamService streamService;
//    private final ExchangeFactory exchangeFactory;
//
//    /**
//     * Get current price for a symbol on a given exchange.
//     * Prefers WebSocket stream data; falls back to REST API.
//     */
//    public BigDecimal getCurrentPrice(String symbol, String exchange, String baseUrl) {
//        // Try WebSocket stream first (exchange-agnostic)
//        Double streamPrice = streamService.getFreshPrice(exchange, symbol);
//        if (streamPrice != null) {
//            return BigDecimal.valueOf(streamPrice);
//        }
//
//        // REST fallback via the correct exchange client
//        try {
//            ExchangeClient client = exchangeFactory.getClient(exchange);
//            return client.getPrice(symbol, baseUrl);
//        } catch (Exception e) {
//            log.error("[PRICE_MONITOR] Failed to get price for {} on {}: {}", symbol, exchange, e.getMessage());
//            return null;
//        }
//    }
//}

package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.ws.MarketDataStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Provides current market prices using WebSocket stream data with REST fallback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketPriceMonitor {

    private final MarketDataStreamService streamService;
    private final ExchangeFactory exchangeFactory;

    /**
     * Primary method: get current price
     */
    public BigDecimal getCurrentPrice(String symbol, String exchange, String baseUrl) {

        // 1. Try WebSocket first
        Double streamPrice = streamService.getFreshPrice(exchange, symbol);
        if (streamPrice != null && streamPrice > 0) {
            return BigDecimal.valueOf(streamPrice);
        }

        // 2. Fallback to REST
        try {
            ExchangeClient client = exchangeFactory.getClient(exchange);
            BigDecimal restPrice = client.getPrice(symbol, baseUrl);

            if (restPrice != null && restPrice.compareTo(BigDecimal.ZERO) > 0) {
                return restPrice;
            }

        } catch (Exception e) {
            log.error("[PRICE_MONITOR] REST fallback failed for {} on {}: {}",
                    symbol, exchange, e.getMessage());
        }

        return null;
    }

    /**
     * SAFE wrapper — prevents nulls and invalid values
     * Use this everywhere in risk systems
     */
    public BigDecimal getSafePrice(String symbol, String exchange, String baseUrl) {
        try {
            BigDecimal price = getCurrentPrice(symbol, exchange, baseUrl);

            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[SAFE_PRICE] Invalid price for {} on {}", symbol, exchange);
                return null;
            }

            return price;

        } catch (Exception e) {
            log.error("[SAFE_PRICE_ERROR] {} {} error={}", symbol, exchange, e.getMessage());
            return null;
        }
    }
}
