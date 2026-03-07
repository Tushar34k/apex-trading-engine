import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { useRunBacktest } from "@/hooks/api/useBacktest";
import type { BacktestResult, StrategyType } from "@/types";
import { FlaskConical, TrendingUp, TrendingDown, BarChart3, AlertTriangle } from "lucide-react";
import { toast } from "@/hooks/use-toast";

const STRATEGY_LABELS: Record<string, string> = {
  EMA_CROSS: "EMA Crossover",
  SCALPING_EMA: "Scalping EMA",
  SUPPORT_RESISTANCE: "Support & Resistance",
};

export default function Backtesting() {
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [strategyType, setStrategyType] = useState<StrategyType>("EMA_CROSS");
  const [timeframe, setTimeframe] = useState("1h");
  const [initialBalance, setInitialBalance] = useState(10000);
  const [candleLimit, setCandleLimit] = useState(500);
  const [fastEma, setFastEma] = useState(9);
  const [slowEma, setSlowEma] = useState(21);
  const [result, setResult] = useState<BacktestResult | null>(null);

  const runBacktest = useRunBacktest();

  const handleRun = async () => {
    try {
      const res = await runBacktest.mutateAsync({
        symbol: symbol.toUpperCase(),
        strategyType,
        timeframe,
        initialBalance,
        strategyParams: { fastEma, slowEma },
        candleLimit,
      });
      setResult(res);
      toast({ title: "Backtest Complete", description: `${res.totalTrades} trades, ${res.profitPercent}% return` });
    } catch (err: any) {
      toast({ title: "Backtest Failed", description: err?.response?.data?.message || "Error running backtest", variant: "destructive" });
    }
  };

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center gap-3">
        <FlaskConical className="h-5 w-5 text-primary" />
        <h1 className="text-xl font-bold text-foreground">Backtesting</h1>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Config */}
        <div className="rounded-lg border border-border bg-card p-5 space-y-4">
          <h3 className="text-sm font-semibold text-foreground">Configuration</h3>

          <div className="space-y-2">
            <Label>Symbol</Label>
            <Input value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="BTCUSDT" />
          </div>

          <div className="space-y-2">
            <Label>Strategy</Label>
            <Select value={strategyType} onValueChange={(v) => setStrategyType(v as StrategyType)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {Object.entries(STRATEGY_LABELS).map(([k, v]) => (
                  <SelectItem key={k} value={k}>{v}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-2">
              <Label>Timeframe</Label>
              <Select value={timeframe} onValueChange={setTimeframe}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="1m">1m</SelectItem>
                  <SelectItem value="5m">5m</SelectItem>
                  <SelectItem value="15m">15m</SelectItem>
                  <SelectItem value="1h">1h</SelectItem>
                  <SelectItem value="4h">4h</SelectItem>
                  <SelectItem value="1d">1d</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Candles</Label>
              <Input type="number" value={candleLimit} onChange={(e) => setCandleLimit(Number(e.target.value))} min={100} max={1000} />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-2">
              <Label>Fast EMA</Label>
              <Input type="number" value={fastEma} onChange={(e) => setFastEma(Number(e.target.value))} />
            </div>
            <div className="space-y-2">
              <Label>Slow EMA</Label>
              <Input type="number" value={slowEma} onChange={(e) => setSlowEma(Number(e.target.value))} />
            </div>
          </div>

          <div className="space-y-2">
            <Label>Initial Balance ($)</Label>
            <Input type="number" value={initialBalance} onChange={(e) => setInitialBalance(Number(e.target.value))} />
          </div>

          <Button onClick={handleRun} disabled={runBacktest.isPending} className="w-full">
            {runBacktest.isPending ? "Running..." : "Run Backtest"}
          </Button>
        </div>

        {/* Results */}
        <div className="lg:col-span-2 space-y-4">
          {!result ? (
            <div className="rounded-lg border border-border bg-card p-12 text-center">
              <BarChart3 className="h-10 w-10 mx-auto text-muted-foreground mb-3" />
              <p className="text-sm text-muted-foreground">Configure and run a backtest to see results</p>
            </div>
          ) : (
            <>
              {/* Stats cards */}
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <div className="rounded-lg border border-border bg-card p-4">
                  <div className="text-[10px] uppercase text-muted-foreground">Profit</div>
                  <div className={`text-lg font-bold font-mono ${result.profitPercent >= 0 ? 'text-profit' : 'text-loss'}`}>
                    {result.profitPercent >= 0 ? '+' : ''}{result.profitPercent}%
                  </div>
                  <div className="text-[10px] text-muted-foreground">${result.totalProfit.toFixed(2)}</div>
                </div>
                <div className="rounded-lg border border-border bg-card p-4">
                  <div className="text-[10px] uppercase text-muted-foreground">Win Rate</div>
                  <div className="text-lg font-bold font-mono text-foreground">{result.winRate}%</div>
                  <div className="text-[10px] text-muted-foreground">{result.wins}W / {result.losses}L</div>
                </div>
                <div className="rounded-lg border border-border bg-card p-4">
                  <div className="text-[10px] uppercase text-muted-foreground">Total Trades</div>
                  <div className="text-lg font-bold font-mono text-foreground">{result.totalTrades}</div>
                </div>
                <div className="rounded-lg border border-border bg-card p-4">
                  <div className="text-[10px] uppercase text-muted-foreground">Max Drawdown</div>
                  <div className="text-lg font-bold font-mono text-loss">{result.maxDrawdown}%</div>
                </div>
              </div>

              {/* Final balance */}
              <div className="rounded-lg border border-border bg-card p-4">
                <div className="flex items-center justify-between">
                  <div>
                    <div className="text-xs text-muted-foreground">Final Balance</div>
                    <div className="text-2xl font-bold font-mono text-foreground">${result.finalBalance.toLocaleString()}</div>
                  </div>
                  <div className="flex gap-2">
                    <Badge variant="outline">{symbol}</Badge>
                    <Badge variant="outline">{STRATEGY_LABELS[strategyType]}</Badge>
                    <Badge variant="outline">{timeframe}</Badge>
                  </div>
                </div>
              </div>

              {/* Trade list */}
              <div className="rounded-lg border border-border bg-card">
                <div className="border-b border-border px-4 py-3">
                  <h3 className="text-sm font-semibold text-foreground">Trade History</h3>
                </div>
                <div className="max-h-64 overflow-y-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-border text-muted-foreground">
                        <th className="px-4 py-2 text-left">#</th>
                        <th className="px-4 py-2 text-right">Entry</th>
                        <th className="px-4 py-2 text-right">Exit</th>
                        <th className="px-4 py-2 text-right">PnL</th>
                        <th className="px-4 py-2 text-right">Result</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border/50">
                      {result.trades.map((t, i) => (
                        <tr key={i} className="hover:bg-muted/30">
                          <td className="px-4 py-2 text-muted-foreground">{i + 1}</td>
                          <td className="px-4 py-2 text-right font-mono">${t.entryPrice.toFixed(2)}</td>
                          <td className="px-4 py-2 text-right font-mono">${t.exitPrice.toFixed(2)}</td>
                          <td className={`px-4 py-2 text-right font-mono ${t.pnl >= 0 ? 'text-profit' : 'text-loss'}`}>
                            {t.pnl >= 0 ? '+' : ''}${t.pnl.toFixed(2)}
                          </td>
                          <td className="px-4 py-2 text-right">
                            {t.side === 'WIN' ? (
                              <TrendingUp className="h-3.5 w-3.5 inline text-profit" />
                            ) : (
                              <TrendingDown className="h-3.5 w-3.5 inline text-loss" />
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
