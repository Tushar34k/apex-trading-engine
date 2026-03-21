import { useState } from "react";
import { FlaskConical, Play, Square, TrendingUp, TrendingDown, AlertTriangle, Brain, Shield } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { TradingChart } from "@/components/trading/TradingChart";
import { TradeQualityPanel } from "@/components/trading/TradeQualityPanel";
import { useRunBacktest } from "@/hooks/api/useBacktest";
import type { BacktestResult, StrategyType } from "@/types";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

const STRATEGY_LABELS: Record<string, string> = {
  EMA_CROSS: "EMA Crossover",
  ENHANCED_EMA: "Enhanced EMA (Recommended)",
  SCALPING_EMA: "Scalping EMA",
  RSI: "RSI Strategy",
  MACD: "MACD Strategy",
  BREAKOUT: "Breakout Strategy",
};

const EXCHANGES = ["BINANCE", "BYBIT", "DELTA"];

export default function PaperTrading() {
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [strategy, setStrategy] = useState<StrategyType>("ENHANCED_EMA");
  const [timeframe, setTimeframe] = useState("5m");
  const [balance, setBalance] = useState(10000);
  const [exchange, setExchange] = useState("BINANCE");
  const [compareAI, setCompareAI] = useState(true);
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [isRunning, setIsRunning] = useState(false);

  const runBacktest = useRunBacktest();

  const handleStart = async () => {
    setIsRunning(true);
    try {
      const res = await runBacktest.mutateAsync({
        symbol: symbol.toUpperCase(),
        strategyType: strategy,
        timeframe,
        initialBalance: balance,
        strategyParams: {
          fastEma: 9,
          slowEma: 21,
          trendEma: 200,
          atrSlMultiplier: 1.5,
          rrRatio: 2,
          minTradeScore: 70,
        },
        candleLimit: 500,
        exchange,
        compareAI,
      });
      setResult(res);
      toast.success(`Paper trade simulation complete: ${res.totalTrades} trades`);
    } catch (err: any) {
      toast.error(err?.response?.data?.message || "Simulation failed");
    } finally {
      setIsRunning(false);
    }
  };

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <FlaskConical className="h-5 w-5 text-primary" />
          <div>
            <h1 className="text-xl font-bold text-foreground">Paper Trading</h1>
            <p className="text-xs text-muted-foreground">Simulate trades with zero risk using historical data</p>
          </div>
        </div>
        <Badge variant="outline" className="border-warning/50 text-warning text-[10px]">
          SIMULATION MODE
        </Badge>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        {/* Left: Config + Quality */}
        <div className="space-y-4">
          <div className="rounded-lg border border-border bg-card p-5 space-y-4">
            <h3 className="text-sm font-semibold text-foreground">Simulation Config</h3>

            <div className="space-y-2">
              <Label className="text-xs">Symbol</Label>
              <Input value={symbol} onChange={(e) => setSymbol(e.target.value)} className="font-mono text-xs" />
            </div>

            <div className="space-y-2">
              <Label className="text-xs">Strategy</Label>
              <Select value={strategy} onValueChange={(v) => setStrategy(v as StrategyType)}>
                <SelectTrigger className="text-xs"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {Object.entries(STRATEGY_LABELS).map(([k, v]) => (
                    <SelectItem key={k} value={k} className="text-xs">{v}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label className="text-xs">Exchange</Label>
              <Select value={exchange} onValueChange={setExchange}>
                <SelectTrigger className="text-xs"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {EXCHANGES.map((ex) => (
                    <SelectItem key={ex} value={ex} className="text-xs">{ex}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label className="text-xs">Timeframe</Label>
                <Select value={timeframe} onValueChange={setTimeframe}>
                  <SelectTrigger className="text-xs"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {["1m", "5m", "15m", "1h", "4h"].map((tf) => (
                      <SelectItem key={tf} value={tf} className="text-xs">{tf}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label className="text-xs">Balance ($)</Label>
                <Input type="number" value={balance} onChange={(e) => setBalance(Number(e.target.value))} className="text-xs" />
              </div>
            </div>

            <div className="flex items-center justify-between rounded-md border border-border p-3">
              <div className="flex items-center gap-2">
                <Brain className="h-3.5 w-3.5 text-primary" />
                <span className="text-xs font-medium text-foreground">Compare AI Filter</span>
              </div>
              <Switch checked={compareAI} onCheckedChange={setCompareAI} />
            </div>

            <Button onClick={handleStart} disabled={isRunning} className="w-full gap-2">
              {isRunning ? (
                <><Square className="h-3.5 w-3.5" /> Simulating...</>
              ) : (
                <><Play className="h-3.5 w-3.5" /> Run Simulation</>
              )}
            </Button>
          </div>

          <TradeQualityPanel />
        </div>

        {/* Right: Chart + Results */}
        <div className="xl:col-span-2 space-y-4">
          <TradingChart symbol={symbol} timeframe={timeframe} />

          {result && (
            <>
              {/* Stats */}
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
                <div className="rounded-lg border border-border bg-card p-3">
                  <div className="text-[10px] uppercase text-muted-foreground">Return</div>
                  <div className={cn("text-lg font-bold font-mono", result.profitPercent >= 0 ? "text-profit" : "text-loss")}>
                    {result.profitPercent >= 0 ? "+" : ""}{result.profitPercent}%
                  </div>
                </div>
                <div className="rounded-lg border border-border bg-card p-3">
                  <div className="text-[10px] uppercase text-muted-foreground">Win Rate</div>
                  <div className="text-lg font-bold font-mono text-foreground">{result.winRate}%</div>
                </div>
                <div className="rounded-lg border border-border bg-card p-3">
                  <div className="text-[10px] uppercase text-muted-foreground">Trades</div>
                  <div className="text-lg font-bold font-mono text-foreground">{result.totalTrades}</div>
                </div>
                <div className="rounded-lg border border-border bg-card p-3">
                  <div className="text-[10px] uppercase text-muted-foreground">Max Drawdown</div>
                  <div className="text-lg font-bold font-mono text-loss">{result.maxDrawdown}%</div>
                </div>
                <div className="rounded-lg border border-border bg-card p-3">
                  <div className="text-[10px] uppercase text-muted-foreground">Final Balance</div>
                  <div className="text-lg font-bold font-mono text-foreground">${result.finalBalance.toLocaleString()}</div>
                </div>
              </div>

              {/* AI Filter Stats */}
              {(result.aiApproved != null || result.aiRejected != null) && (
                <div className="rounded-lg border border-primary/30 bg-primary/5 p-4 space-y-3">
                  <div className="flex items-center gap-2">
                    <Brain className="h-4 w-4 text-primary" />
                    <h4 className="text-sm font-semibold text-foreground">AI Filter Impact</h4>
                  </div>
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <div>
                      <div className="text-[10px] uppercase text-muted-foreground">AI Approved</div>
                      <div className="text-lg font-bold font-mono text-profit">{result.aiApproved ?? 0}</div>
                    </div>
                    <div>
                      <div className="text-[10px] uppercase text-muted-foreground">AI Rejected</div>
                      <div className="text-lg font-bold font-mono text-loss">{result.aiRejected ?? 0}</div>
                    </div>
                    {result.withoutAI && (
                      <>
                        <div>
                          <div className="text-[10px] uppercase text-muted-foreground">Without AI Return</div>
                          <div className={cn("text-lg font-bold font-mono", result.withoutAI.profitPercent >= 0 ? "text-profit" : "text-loss")}>
                            {result.withoutAI.profitPercent >= 0 ? "+" : ""}{result.withoutAI.profitPercent}%
                          </div>
                        </div>
                        <div>
                          <div className="text-[10px] uppercase text-muted-foreground">Without AI Drawdown</div>
                          <div className="text-lg font-bold font-mono text-loss">{result.withoutAI.maxDrawdown}%</div>
                        </div>
                      </>
                    )}
                  </div>
                  {result.withoutAI && (
                    <div className="flex items-center gap-2 text-xs text-muted-foreground border-t border-border/50 pt-2">
                      <Shield className="h-3 w-3" />
                      <span>
                        AI filter {result.profitPercent > result.withoutAI.profitPercent ? "improved" : "changed"} return by{' '}
                        <span className={cn("font-mono font-semibold",
                          result.profitPercent - result.withoutAI.profitPercent >= 0 ? "text-profit" : "text-loss")}>
                          {(result.profitPercent - result.withoutAI.profitPercent) >= 0 ? "+" : ""}
                          {(result.profitPercent - result.withoutAI.profitPercent).toFixed(2)}%
                        </span>
                        {' '}and reduced drawdown by{' '}
                        <span className="font-mono font-semibold text-profit">
                          {(result.withoutAI.maxDrawdown - result.maxDrawdown).toFixed(2)}%
                        </span>
                      </span>
                    </div>
                  )}
                </div>
              )}

              {/* Trade list */}
              <div className="rounded-lg border border-border bg-card">
                <div className="border-b border-border px-4 py-3 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-foreground">Simulated Trades</h3>
                  <div className="flex gap-2 text-[10px]">
                    <span className="text-profit">{result.wins}W</span>
                    <span className="text-loss">{result.losses}L</span>
                  </div>
                </div>
                <div className="max-h-64 overflow-y-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-border text-muted-foreground">
                        <th className="px-4 py-2 text-left">#</th>
                        <th className="px-4 py-2 text-right">Entry</th>
                        <th className="px-4 py-2 text-right">Exit</th>
                        <th className="px-4 py-2 text-right">P&L</th>
                        <th className="px-4 py-2 text-center">Result</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border/50">
                      {result.trades.map((t, i) => (
                        <tr key={i} className="hover:bg-muted/30">
                          <td className="px-4 py-2 text-muted-foreground">{i + 1}</td>
                          <td className="px-4 py-2 text-right font-mono">${t.entryPrice.toFixed(2)}</td>
                          <td className="px-4 py-2 text-right font-mono">${t.exitPrice.toFixed(2)}</td>
                          <td className={cn("px-4 py-2 text-right font-mono", t.pnl >= 0 ? "text-profit" : "text-loss")}>
                            {t.pnl >= 0 ? "+" : ""}${t.pnl.toFixed(2)}
                          </td>
                          <td className="px-4 py-2 text-center">
                            {t.side === "WIN" ? (
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

          {!result && (
            <div className="rounded-lg border border-border bg-card p-12 text-center">
              <AlertTriangle className="h-8 w-8 mx-auto text-muted-foreground mb-3" />
              <p className="text-sm text-muted-foreground">Configure settings and run a simulation</p>
              <p className="text-xs text-muted-foreground mt-1">Uses historical candle data — no real funds at risk</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
