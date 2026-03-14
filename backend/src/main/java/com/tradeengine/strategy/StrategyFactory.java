package com.tradeengine.strategy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory to resolve strategy by type name.
 * Strategies are stateless singletons.
 */
public class StrategyFactory {

    private static final Map<String, TradingStrategy> STRATEGIES = new ConcurrentHashMap<>();

    static {
        STRATEGIES.put("EMA_CROSS", new EmaCrossover());
        STRATEGIES.put("SCALPING_EMA", new ScalpingEma());
        STRATEGIES.put("SUPPORT_RESISTANCE", new SupportResistance());
        STRATEGIES.put("RSI", new RsiStrategy());
        STRATEGIES.put("MACD", new MacdStrategy());
        STRATEGIES.put("BREAKOUT", new BreakoutStrategy());
        STRATEGIES.put("ORDER_BOOK", new OrderBookStrategy());
        STRATEGIES.put("ENHANCED_EMA", new EnhancedEmaCrossover());
    }

    public static TradingStrategy get(String strategyType) {
        TradingStrategy strategy = STRATEGIES.get(strategyType);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyType
                + ". Available: " + STRATEGIES.keySet());
        }
        return strategy;
    }

    public static boolean exists(String strategyType) {
        return STRATEGIES.containsKey(strategyType);
    }

    public static Set<String> availableStrategies() {
        return STRATEGIES.keySet();
    }
}
