import { useBots } from "@/hooks/api/useBots";
import { useBotStats } from "@/hooks/api/useBotStats";
import { useState } from "react";
import { BarChart3, TrendingUp, TrendingDown, Target, Activity } from "lucide-react";
import { cn } from "@/lib/utils";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

export default function Analytics() {
  const { data: botsList } = useBots();
  const bots = botsList ?? [];
  const [selectedBotId, setSelectedBotId] = useState<string | null>(null);
  const { data: stats } = useBotStats(selectedBotId);

  // Global stats
  const totalPnl = bots.reduce((sum, b) => sum + (b.pnl ?? 0), 0);
  const totalTrades = bots.reduce((sum, b) => sum + (b.totalTrades ?? 0), 0);
  const runningBots = bots.filter((b) => b.status === "RUNNING").length;

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center gap-3">
        <BarChart3 className="h-5 w-5 text-primary" />
        <h1 className="text-xl font-bold text-foreground">Analytics</h1>
      </div>

      {/* Global stats */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Total P&L</div>
          <div className={cn("text-2xl font-bold font-mono", totalPnl >= 0 ? "text-profit" : "text-loss")}>
            {totalPnl >= 0 ? '+' : ''}${totalPnl.toFixed(2)}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Total Trades</div>
          <div className="text-2xl font-bold font-mono text-foreground">{totalTrades}</div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Active Bots</div>
          <div className="text-2xl font-bold font-mono text-foreground">{runningBots}</div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Total Bots</div>
          <div className="text-2xl font-bold font-mono text-foreground">{bots.length}</div>
        </div>
      </div>

      {/* Bot selector */}
      <div className="rounded-lg border border-border bg-card p-5 space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-foreground">Bot Performance</h3>
          <Select value={selectedBotId ?? ""} onValueChange={(v) => setSelectedBotId(v || null)}>
            <SelectTrigger className="w-[200px]">
              <SelectValue placeholder="Select a bot" />
            </SelectTrigger>
            <SelectContent>
              {bots.map((b) => (
                <SelectItem key={b.id} value={b.id}>{b.name} ({b.symbol})</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {!stats ? (
          <div className="py-8 text-center text-sm text-muted-foreground">
            Select a bot to view detailed statistics
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <div className="rounded-md border border-border p-3">
              <div className="flex items-center gap-2 text-[10px] uppercase text-muted-foreground">
                <Target className="h-3 w-3" /> P&L
              </div>
              <div className={cn("text-lg font-bold font-mono", stats.pnl >= 0 ? "text-profit" : "text-loss")}>
                {stats.pnl >= 0 ? '+' : ''}${stats.pnl.toFixed(2)}
              </div>
            </div>
            <div className="rounded-md border border-border p-3">
              <div className="flex items-center gap-2 text-[10px] uppercase text-muted-foreground">
                <Activity className="h-3 w-3" /> Win Rate
              </div>
              <div className="text-lg font-bold font-mono text-foreground">{stats.winRate}%</div>
              <div className="text-[10px] text-muted-foreground">{stats.wins}W / {stats.losses}L</div>
            </div>
            <div className="rounded-md border border-border p-3">
              <div className="flex items-center gap-2 text-[10px] uppercase text-muted-foreground">
                <TrendingUp className="h-3 w-3 text-profit" /> Avg Profit
              </div>
              <div className="text-lg font-bold font-mono text-profit">${stats.avgProfit.toFixed(2)}</div>
            </div>
            <div className="rounded-md border border-border p-3">
              <div className="flex items-center gap-2 text-[10px] uppercase text-muted-foreground">
                <TrendingDown className="h-3 w-3 text-loss" /> Avg Loss
              </div>
              <div className="text-lg font-bold font-mono text-loss">${stats.avgLoss.toFixed(2)}</div>
            </div>
          </div>
        )}
      </div>

      {/* Per-bot breakdown */}
      <div className="rounded-lg border border-border bg-card">
        <div className="border-b border-border px-4 py-3">
          <h3 className="text-sm font-semibold text-foreground">All Bots Overview</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-4 py-2 text-left">Bot</th>
                <th className="px-4 py-2 text-left">Symbol</th>
                <th className="px-4 py-2 text-left">Strategy</th>
                <th className="px-4 py-2 text-left">Status</th>
                <th className="px-4 py-2 text-right">Trades</th>
                <th className="px-4 py-2 text-right">Win %</th>
                <th className="px-4 py-2 text-right">P&L</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/50">
              {bots.map((b) => (
                <tr key={b.id} className="hover:bg-muted/30">
                  <td className="px-4 py-2 font-medium text-foreground">{b.name}</td>
                  <td className="px-4 py-2 font-mono text-muted-foreground">{b.symbol}</td>
                  <td className="px-4 py-2 text-muted-foreground">{b.strategyType}</td>
                  <td className="px-4 py-2">
                    <span className={cn("text-[10px] font-bold",
                      b.status === "RUNNING" ? "text-profit" : "text-muted-foreground")}>
                      {b.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-right font-mono">{b.totalTrades ?? 0}</td>
                  <td className="px-4 py-2 text-right font-mono">{b.winRate ?? 0}%</td>
                  <td className={cn("px-4 py-2 text-right font-mono font-semibold",
                    (b.pnl ?? 0) >= 0 ? "text-profit" : "text-loss")}>
                    {(b.pnl ?? 0) >= 0 ? '+' : ''}${(b.pnl ?? 0).toFixed(2)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
