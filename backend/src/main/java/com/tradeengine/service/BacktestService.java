package com.tradeengine.service;

import com.tradeengine.exchange.BinanceClient;
import com.tradeengine.strategy.StrategyFactory;
import com.tradeengine.strategy.TradingStrategy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final BinanceClient binance;

    @Data
    public static class BacktestRequest {
        private String symbol;
        private String strategyType;
        private String timeframe;
        private double initialBalance;
        private Map<String, Object> strategyParams;
        private int candleLimit = 500;
    }

    @Data
    public static class BacktestResult {
        private double profitPercent;
        private double winRate;
        private int totalTrades;
        private double maxDrawdown;
        private double finalBalance;
        private double totalProfit;
        private int wins;
        private int losses;
        private List<Map<String, Object>> trades = new ArrayList<>();
        private List<double[]> equityCurve = new ArrayList<>();
    }

    public BacktestResult runBacktest(BacktestRequest req) {
        log.info("Running backtest: {} {} {} candles={}", req.symbol, req.strategyType, req.timeframe, req.candleLimit);

        // Fetch historical candles
        List<double[]> candles = binance.getCandles(req.symbol, req.timeframe, req.candleLimit);
        if (candles.size() < 50) {
            throw new RuntimeException("Not enough candle data for backtest (need 50+, got " + candles.size() + ")");
        }

        TradingStrategy strategy = StrategyFactory.get(req.strategyType);
        Map<String, Object> params = req.strategyParams != null ? req.strategyParams : new HashMap<>();
        params.putIfAbsent("fastEma", 9);
        params.putIfAbsent("slowEma", 21);

        BacktestResult result = new BacktestResult();
        double balance = req.initialBalance;
        double peakBalance = balance;
        double maxDrawdown = 0;
        boolean inPosition = false;
        double entryPrice = 0;
        double positionQty = 0;
        int wins = 0, losses = 0;

        // Walk through candles starting at index 50
        for (int i = 50; i < candles.size(); i++) {
            List<Double> closingPrices = candles.subList(0, i + 1).stream()
                .map(c -> c[4]).collect(Collectors.toList());
            List<double[]> candleSlice = candles.subList(0, i + 1);

            TradingStrategy.SignalResult signal = strategy.evaluate(closingPrices, candleSlice, params, inPosition);
            double currentPrice = candles.get(i)[4];

            if (signal.signal() == TradingStrategy.Signal.BUY && !inPosition) {
                double tradeAmount = balance * 0.10; // 10% per trade
                positionQty = tradeAmount / currentPrice;
                entryPrice = currentPrice;
                balance -= tradeAmount;
                inPosition = true;
            } else if (signal.signal() == TradingStrategy.Signal.SELL && inPosition) {
                double sellValue = positionQty * currentPrice;
                double pnl = sellValue - (positionQty * entryPrice);
                balance += sellValue;
                inPosition = false;

                if (pnl > 0) wins++;
                else losses++;

                Map<String, Object> trade = new LinkedHashMap<>();
                trade.put("entryPrice", entryPrice);
                trade.put("exitPrice", currentPrice);
                trade.put("quantity", positionQty);
                trade.put("pnl", Math.round(pnl * 100.0) / 100.0);
                trade.put("side", pnl > 0 ? "WIN" : "LOSS");
                result.getTrades().add(trade);

                positionQty = 0;
                entryPrice = 0;
            }

            // Track equity
            double equity = balance + (inPosition ? positionQty * currentPrice : 0);
            peakBalance = Math.max(peakBalance, equity);
            double drawdown = peakBalance > 0 ? (peakBalance - equity) / peakBalance * 100 : 0;
            maxDrawdown = Math.max(maxDrawdown, drawdown);

            result.getEquityCurve().add(new double[]{candles.get(i)[0], equity});
        }

        // Close open position at last price
        if (inPosition) {
            double lastPrice = candles.get(candles.size() - 1)[4];
            balance += positionQty * lastPrice;
        }

        int totalTrades = wins + losses;
        result.setFinalBalance(Math.round(balance * 100.0) / 100.0);
        result.setTotalProfit(Math.round((balance - req.initialBalance) * 100.0) / 100.0);
        result.setProfitPercent(Math.round((balance - req.initialBalance) / req.initialBalance * 10000.0) / 100.0);
        result.setWinRate(totalTrades > 0 ? Math.round((double) wins / totalTrades * 10000.0) / 100.0 : 0);
        result.setTotalTrades(totalTrades);
        result.setMaxDrawdown(Math.round(maxDrawdown * 100.0) / 100.0);
        result.setWins(wins);
        result.setLosses(losses);

        log.info("Backtest complete: {} trades, {:.2f}% profit, {:.1f}% win rate",
            totalTrades, result.getProfitPercent(), result.getWinRate());

        return result;
    }
}
