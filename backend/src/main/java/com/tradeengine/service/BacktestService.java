package com.tradeengine.service;

import com.tradeengine.exchange.ExchangeClient;
import com.tradeengine.exchange.ExchangeFactory;
import com.tradeengine.strategy.StrategyFactory;
import com.tradeengine.strategy.TradingStrategy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final ExchangeFactory exchangeFactory;

    @Data
    public static class BacktestRequest {
        private String symbol;
        private String strategyType;
        private String timeframe;
        private double initialBalance;
        private Map<String, Object> strategyParams;
        private int candleLimit = 500;
        private String exchange = "BINANCE";
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
        if (req.getExchange() == null || req.getExchange().isBlank()) {
            throw new IllegalArgumentException("Exchange field is required for backtesting");
        }

        ExchangeClient client = exchangeFactory.getClient(req.getExchange());
        String baseUrl = client.resolveBaseUrl("TESTNET");

        log.info("Running backtest on {}: {} {} {} candles={}",
            req.getExchange(), req.symbol, req.strategyType, req.timeframe, req.candleLimit);

        List<double[]> candles = client.getCandles(req.symbol, req.timeframe, req.candleLimit, baseUrl);
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

        for (int i = 50; i < candles.size(); i++) {
            List<Double> closingPrices = candles.subList(0, i + 1).stream()
                .map(c -> c[4]).collect(Collectors.toList());
            List<double[]> candleSlice = candles.subList(0, i + 1);

            TradingStrategy.SignalResult signal = strategy.evaluate(closingPrices, candleSlice, params, inPosition);
            double currentPrice = candles.get(i)[4];

            if (signal.signal() == TradingStrategy.Signal.BUY && !inPosition) {
                double tradeAmount = balance * 0.10;
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

            double equity = balance + (inPosition ? positionQty * currentPrice : 0);
            peakBalance = Math.max(peakBalance, equity);
            double drawdown = peakBalance > 0 ? (peakBalance - equity) / peakBalance * 100 : 0;
            maxDrawdown = Math.max(maxDrawdown, drawdown);

            result.getEquityCurve().add(new double[]{candles.get(i)[0], equity});
        }

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

        log.info("Backtest complete on {}: {} trades, {}% profit, {}% win rate",
            req.getExchange(), totalTrades, result.getProfitPercent(), result.getWinRate());

        return result;
    }
}
