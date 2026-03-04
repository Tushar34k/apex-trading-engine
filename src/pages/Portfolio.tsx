import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from "recharts";
import { StatCard } from "@/components/ui/stat-card";
import { DollarSign, PieChart as PieIcon, TrendingUp, BarChart3 } from "lucide-react";
import { useBalances } from "@/hooks/api/useBalances";
import { usePositions } from "@/hooks/api/useTrades";
import { useMonthlyReturns, useAnalytics } from "@/hooks/api/useAnalytics";
import type { Balance, MonthlyReturn } from "@/types";

const COLORS = ["hsl(25, 95%, 53%)", "hsl(199, 89%, 48%)", "hsl(262, 83%, 58%)", "hsl(142, 71%, 45%)", "hsl(38, 92%, 50%)"];

export default function Portfolio() {
  const { data: balancesList } = useBalances();
  const { data: positionsList } = usePositions();
  const { data: monthlyReturns } = useMonthlyReturns();
  const { data: perf } = useAnalytics();

  const balances = balancesList ?? [];
  const totalValue = balances.reduce((sum, b: Balance) => sum + b.usdValue, 0);

  const allocations = balances
    .filter((b: Balance) => b.usdValue > 0)
    .map((b: Balance, i: number) => ({
      name: b.asset,
      value: totalValue > 0 ? (b.usdValue / totalValue) * 100 : 0,
      color: COLORS[i % COLORS.length],
    }));

  const returns = monthlyReturns ?? [];

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Portfolio Overview</h1>
        <p className="text-sm text-muted-foreground">Asset allocation and performance tracking</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Total Value" value={`$${totalValue.toLocaleString()}`} change={perf ? `${perf.totalReturnPercent >= 0 ? '+' : ''}${perf.totalReturnPercent.toFixed(1)}% all time` : '—'} changeType="profit" icon={DollarSign} />
        <StatCard label="Positions" value={String(positionsList?.length ?? 0)} change={`Across ${new Set(positionsList?.map(p => p.symbol)).size} assets`} changeType="neutral" icon={PieIcon} />
        <StatCard label="Best Strategy" value={perf?.bestStrategy ?? '—'} change={perf ? `+${perf.bestStrategyReturn.toFixed(1)}%` : '—'} changeType="profit" icon={TrendingUp} />
        <StatCard label="Sharpe Ratio" value={perf ? perf.sharpeRatio.toFixed(2) : '—'} change={perf && perf.sharpeRatio > 1.5 ? 'Excellent' : perf && perf.sharpeRatio > 1 ? 'Good' : '—'} changeType="profit" icon={BarChart3} />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Allocation chart */}
        <div className="rounded-lg border border-border bg-card p-5">
          <h3 className="text-sm font-semibold text-foreground mb-4">Asset Allocation</h3>
          {allocations.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">No allocations</div>
          ) : (
            <div className="flex items-center gap-8">
              <ResponsiveContainer width={180} height={180}>
                <PieChart>
                  <Pie data={allocations} cx="50%" cy="50%" innerRadius={50} outerRadius={80} dataKey="value" stroke="hsl(220 18% 10%)" strokeWidth={2}>
                    {allocations.map((entry) => (
                      <Cell key={entry.name} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip contentStyle={{ background: "hsl(220 18% 10%)", border: "1px solid hsl(220 14% 18%)", borderRadius: "6px", fontSize: "12px", color: "hsl(210 20% 92%)" }} />
                </PieChart>
              </ResponsiveContainer>
              <div className="space-y-3">
                {allocations.map((a) => (
                  <div key={a.name} className="flex items-center gap-3">
                    <div className="h-3 w-3 rounded-sm" style={{ backgroundColor: a.color }} />
                    <span className="text-sm text-foreground">{a.name}</span>
                    <span className="font-mono text-sm text-muted-foreground">{a.value.toFixed(1)}%</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Monthly returns */}
        <div className="rounded-lg border border-border bg-card p-5">
          <h3 className="text-sm font-semibold text-foreground mb-4">Monthly Returns</h3>
          {returns.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">No monthly data</div>
          ) : (
            <div className="space-y-2">
              {returns.map((m: MonthlyReturn) => (
                <div key={m.month} className="flex items-center justify-between rounded-md bg-surface-1 px-4 py-2.5">
                  <span className="text-sm text-muted-foreground">{m.month}</span>
                  <span className={`font-mono text-sm font-semibold ${m.returnPercent >= 0 ? "text-profit" : "text-loss"}`}>
                    {m.returnPercent >= 0 ? '+' : ''}{m.returnPercent.toFixed(1)}%
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
