import { Bar, BarChart, ResponsiveContainer, XAxis, YAxis, Tooltip, CartesianGrid } from "recharts";
import { StatCard } from "@/components/ui/stat-card";
import { LineChart as LineIcon, TrendingUp, Target, Award } from "lucide-react";
import { useAnalytics, useMonthlyReturns, useStrategyComparison } from "@/hooks/api/useAnalytics";
import type { MonthlyReturn, StrategyComparisonItem } from "@/types";

export default function Analytics() {
  const { data: perf } = useAnalytics();
  const { data: monthlyReturns } = useMonthlyReturns();
  const { data: strategyComparison } = useStrategyComparison();

  const monthlyData = (monthlyReturns ?? []).map((m: MonthlyReturn) => ({
    month: m.month,
    pnl: m.returnAmount,
  }));

  const strategies = strategyComparison ?? [];

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Analytics & Performance</h1>
        <p className="text-sm text-muted-foreground">Comprehensive trading performance analysis</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Total Return" value={perf ? `${perf.totalReturn >= 0 ? '+' : ''}$${perf.totalReturn.toLocaleString()}` : '—'} change={perf ? `${perf.totalReturnPercent >= 0 ? '+' : ''}${perf.totalReturnPercent.toFixed(1)}%` : '—'} changeType="profit" icon={TrendingUp} />
        <StatCard label="Win Rate" value={perf ? `${perf.winRate.toFixed(1)}%` : '—'} change={perf ? `${perf.totalTrades} trades` : '—'} changeType="neutral" icon={Target} />
        <StatCard label="Sharpe Ratio" value={perf ? perf.sharpeRatio.toFixed(2) : '—'} change={perf && perf.sharpeRatio > 1.5 ? 'Excellent' : 'Good'} changeType="profit" icon={LineIcon} />
        <StatCard label="Best Strategy" value={perf?.bestStrategy ?? '—'} change={perf ? `+${perf.bestStrategyReturn.toFixed(1)}%` : '—'} changeType="profit" icon={Award} />
      </div>

      {/* Monthly P&L */}
      <div className="rounded-lg border border-border bg-card p-5">
        <h3 className="text-sm font-semibold text-foreground mb-4">Monthly P&L</h3>
        {monthlyData.length === 0 ? (
          <div className="py-8 text-center text-sm text-muted-foreground">No monthly data</div>
        ) : (
          <ResponsiveContainer width="100%" height={250}>
            <BarChart data={monthlyData}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(220 14% 14%)" />
              <XAxis dataKey="month" tick={{ fill: "hsl(215 15% 55%)", fontSize: 11 }} axisLine={{ stroke: "hsl(220 14% 18%)" }} tickLine={false} />
              <YAxis tick={{ fill: "hsl(215 15% 55%)", fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={(v) => `$${v}`} width={50} />
              <Tooltip contentStyle={{ background: "hsl(220 18% 10%)", border: "1px solid hsl(220 14% 18%)", borderRadius: "6px", fontSize: "12px", color: "hsl(210 20% 92%)" }} />
              <Bar dataKey="pnl" radius={[4, 4, 0, 0]} fill="hsl(199 89% 48%)" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* Strategy comparison */}
      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-5 py-3">
          <h3 className="text-sm font-semibold text-foreground">Strategy Comparison</h3>
        </div>
        {strategies.length === 0 ? (
          <div className="p-8 text-center text-sm text-muted-foreground">No strategy data</div>
        ) : (
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-5 py-2.5 text-left font-medium">Strategy</th>
                <th className="px-5 py-2.5 text-right font-medium">Return</th>
                <th className="px-5 py-2.5 text-right font-medium">Win Rate</th>
                <th className="px-5 py-2.5 text-right font-medium">Sharpe</th>
              </tr>
            </thead>
            <tbody>
              {strategies.map((s: StrategyComparisonItem) => (
                <tr key={s.strategyName} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                  <td className="px-5 py-3 font-semibold text-foreground">{s.strategyName}</td>
                  <td className="px-5 py-3 text-right font-mono text-profit">+{s.totalReturn.toFixed(1)}%</td>
                  <td className="px-5 py-3 text-right font-mono text-foreground">{(s.winRate * 100).toFixed(1)}%</td>
                  <td className="px-5 py-3 text-right font-mono text-foreground">{s.sharpeRatio.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
