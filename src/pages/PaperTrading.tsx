import { BarChart3, Circle } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { cn } from "@/lib/utils";
import { useTrades } from "@/hooks/api/useTrades";
import type { Trade } from "@/types";

export default function PaperTrading() {
  const { data: tradesList, isLoading } = useTrades({ mode: 'PAPER' });

  const trades = tradesList ?? [];
  const openTrades = trades.filter((t: Trade) => t.status === 'OPEN');
  const totalPnl = trades.reduce((sum, t: Trade) => sum + (t.pnl ?? 0), 0);

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Paper Trading</h1>
        <p className="text-sm text-muted-foreground">Simulate trades with live data, no real money at risk</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Virtual Balance" value={`$${(10000 + totalPnl).toLocaleString()}`} change={`${totalPnl >= 0 ? '+' : ''}${((totalPnl / 10000) * 100).toFixed(2)}%`} changeType={totalPnl >= 0 ? "profit" : "loss"} icon={BarChart3} />
        <StatCard label="Open Positions" value={String(openTrades.length)} change={openTrades.length > 0 ? openTrades[0].symbol : 'None'} changeType="neutral" />
        <StatCard label="Total Trades" value={String(trades.length)} change="This session" changeType="neutral" />
        <StatCard label="Session P&L" value={`${totalPnl >= 0 ? '+' : ''}$${totalPnl.toFixed(2)}`} change={`${totalPnl >= 0 ? '+' : ''}${((totalPnl / 10000) * 100).toFixed(2)}%`} changeType={totalPnl >= 0 ? "profit" : "loss"} />
      </div>

      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-4 py-3 flex items-center gap-2">
          <h3 className="text-sm font-semibold text-foreground">Paper Trades</h3>
          <span className="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-bold text-primary">SIMULATED</span>
        </div>
        {isLoading ? (
          <div className="p-8 text-center text-sm text-muted-foreground">Loading...</div>
        ) : trades.length === 0 ? (
          <div className="p-8 text-center text-sm text-muted-foreground">No paper trades yet</div>
        ) : (
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-4 py-2.5 text-left font-medium">Symbol</th>
                <th className="px-4 py-2.5 text-left font-medium">Side</th>
                <th className="px-4 py-2.5 text-right font-medium">Entry</th>
                <th className="px-4 py-2.5 text-right font-medium">Exit</th>
                <th className="px-4 py-2.5 text-right font-medium">P&L</th>
                <th className="px-4 py-2.5 text-left font-medium">Strategy</th>
                <th className="px-4 py-2.5 text-right font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {trades.map((t: Trade) => (
                <tr key={t.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                  <td className="px-4 py-3 font-mono font-semibold text-foreground">{t.symbol}</td>
                  <td className="px-4 py-3">
                    <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold", t.side === "LONG" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>{t.side}</span>
                  </td>
                  <td className="px-4 py-3 text-right font-mono text-muted-foreground">${t.entryPrice.toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-muted-foreground">{t.exitPrice ? `$${t.exitPrice.toLocaleString()}` : '—'}</td>
                  <td className={cn("px-4 py-3 text-right font-mono font-semibold", (t.pnl ?? 0) >= 0 ? "text-profit" : "text-loss")}>
                    {t.pnl != null ? `${t.pnl >= 0 ? '+' : ''}$${t.pnl.toFixed(2)}` : '—'}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{t.strategyName} {t.strategyVersion}</td>
                  <td className="px-4 py-3 text-right">
                    <span className="flex items-center justify-end gap-1">
                      <Circle className={cn("h-1.5 w-1.5 fill-current", t.status === "OPEN" ? "text-profit" : "text-muted-foreground")} />
                      <span className="text-muted-foreground">{t.status.toLowerCase()}</span>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
