import { FlaskConical, Play } from "lucide-react";
import { Area, AreaChart, ResponsiveContainer, XAxis, YAxis, Tooltip } from "recharts";
import { cn } from "@/lib/utils";
import { useBacktestResults, useRunBacktest } from "@/hooks/api/useBacktest";
import type { BacktestRun } from "@/types";

export default function Backtesting() {
  const { data: results, isLoading } = useBacktestResults();
  const runBacktest = useRunBacktest();

  const backtestResults = results ?? [];

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">Backtesting Engine</h1>
          <p className="text-sm text-muted-foreground">Test strategies against historical data</p>
        </div>
        <button
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          disabled={runBacktest.isPending}
        >
          <Play className="h-4 w-4" /> {runBacktest.isPending ? 'Running...' : 'Run Backtest'}
        </button>
      </div>

      {isLoading ? (
        <div className="rounded-lg border border-border bg-card p-8 text-center text-sm text-muted-foreground">Loading backtest results...</div>
      ) : backtestResults.length === 0 ? (
        <div className="rounded-lg border border-border bg-card p-8 text-center text-sm text-muted-foreground">No backtest results. Run a backtest to see results.</div>
      ) : (
        <div className="space-y-4">
          {backtestResults.map((r: BacktestRun) => (
            <div key={r.id} className="rounded-lg border border-border bg-card overflow-hidden">
              <div className="flex items-center justify-between px-5 py-3 border-b border-border">
                <div className="flex items-center gap-3">
                  <FlaskConical className="h-4 w-4 text-primary" />
                  <span className="text-sm font-semibold text-foreground">{r.strategyName} {r.strategyVersion}</span>
                  <span className="text-xs text-muted-foreground">· {r.symbol} · {r.timeframe}</span>
                  <span className={cn(
                    "rounded px-1.5 py-0.5 text-[10px] font-bold",
                    r.status === 'COMPLETED' ? "bg-profit/10 text-profit" : r.status === 'RUNNING' ? "bg-primary/10 text-primary" : "bg-loss/10 text-loss"
                  )}>
                    {r.status}
                  </span>
                </div>
              </div>
              {r.status === 'COMPLETED' && (
                <div className="grid grid-cols-6 divide-x divide-border/50 bg-surface-1">
                  {[
                    { label: 'Total Return', value: r.totalReturn != null ? `${r.totalReturn >= 0 ? '+' : ''}${r.totalReturn.toFixed(2)}%` : '—', type: r.totalReturn != null && r.totalReturn >= 0 ? 'profit' : 'loss' },
                    { label: 'Win Rate', value: r.winRate != null ? `${(r.winRate * 100).toFixed(1)}%` : '—', type: 'neutral' },
                    { label: 'Sharpe Ratio', value: r.sharpeRatio?.toFixed(2) ?? '—', type: 'neutral' },
                    { label: 'Max Drawdown', value: r.maxDrawdown != null ? `${r.maxDrawdown.toFixed(2)}%` : '—', type: 'loss' },
                    { label: 'Profit Factor', value: r.profitFactor?.toFixed(2) ?? '—', type: 'neutral' },
                    { label: 'Total Trades', value: r.totalTrades != null ? String(r.totalTrades) : '—', type: 'neutral' },
                  ].map((m) => (
                    <div key={m.label} className="px-4 py-3 text-center">
                      <div className="text-[10px] text-muted-foreground uppercase tracking-wider">{m.label}</div>
                      <div className={cn("mt-1 font-mono text-sm font-semibold",
                        m.type === "profit" && "text-profit",
                        m.type === "loss" && "text-loss",
                        m.type === "neutral" && "text-foreground"
                      )}>{m.value}</div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
