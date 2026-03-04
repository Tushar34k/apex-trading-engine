import { Bar, BarChart, ResponsiveContainer, XAxis, YAxis, Tooltip, CartesianGrid } from "recharts";
import { StatCard } from "@/components/ui/stat-card";
import { LineChart as LineIcon, TrendingUp, Target, Award } from "lucide-react";

const strategyComparison = [
  { name: "Trend Following", return: 34.5, winRate: 68, sharpe: 1.82 },
  { name: "Breakout", return: 21.8, winRate: 58, sharpe: 1.45 },
  { name: "Pullback", return: 12.3, winRate: 52, sharpe: 1.12 },
];

const monthlyPnl = [
  { month: "Oct", pnl: 420 }, { month: "Nov", pnl: 780 },
  { month: "Dec", pnl: -210 }, { month: "Jan", pnl: 560 },
  { month: "Feb", pnl: 310 }, { month: "Mar", pnl: 120 },
];

export default function Analytics() {
  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Analytics & Performance</h1>
        <p className="text-sm text-muted-foreground">Comprehensive trading performance analysis</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Total Return" value="+$1,845.90" change="+16.8%" changeType="profit" icon={TrendingUp} />
        <StatCard label="Win Rate" value="62.4%" change="280 trades" changeType="neutral" icon={Target} />
        <StatCard label="Sharpe Ratio" value="1.82" change="Excellent" changeType="profit" icon={LineIcon} />
        <StatCard label="Best Strategy" value="Trend Follow" change="+34.5%" changeType="profit" icon={Award} />
      </div>

      {/* Monthly P&L */}
      <div className="rounded-lg border border-border bg-card p-5">
        <h3 className="text-sm font-semibold text-foreground mb-4">Monthly P&L</h3>
        <ResponsiveContainer width="100%" height={250}>
          <BarChart data={monthlyPnl}>
            <CartesianGrid strokeDasharray="3 3" stroke="hsl(220 14% 14%)" />
            <XAxis dataKey="month" tick={{ fill: "hsl(215 15% 55%)", fontSize: 11 }} axisLine={{ stroke: "hsl(220 14% 18%)" }} tickLine={false} />
            <YAxis tick={{ fill: "hsl(215 15% 55%)", fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={(v) => `$${v}`} width={50} />
            <Tooltip contentStyle={{ background: "hsl(220 18% 10%)", border: "1px solid hsl(220 14% 18%)", borderRadius: "6px", fontSize: "12px", color: "hsl(210 20% 92%)" }} />
            <Bar dataKey="pnl" radius={[4, 4, 0, 0]} fill="hsl(199 89% 48%)" />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Strategy comparison */}
      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-5 py-3">
          <h3 className="text-sm font-semibold text-foreground">Strategy Comparison</h3>
        </div>
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
            {strategyComparison.map((s) => (
              <tr key={s.name} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                <td className="px-5 py-3 font-semibold text-foreground">{s.name}</td>
                <td className="px-5 py-3 text-right font-mono text-profit">+{s.return}%</td>
                <td className="px-5 py-3 text-right font-mono text-foreground">{s.winRate}%</td>
                <td className="px-5 py-3 text-right font-mono text-foreground">{s.sharpe}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
