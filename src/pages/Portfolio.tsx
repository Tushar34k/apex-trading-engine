import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from "recharts";
import { StatCard } from "@/components/ui/stat-card";
import { DollarSign, PieChart as PieIcon, TrendingUp, BarChart3 } from "lucide-react";

const allocations = [
  { name: "BTC/USDT", value: 45, color: "hsl(25, 95%, 53%)" },
  { name: "ETH/USDT", value: 30, color: "hsl(199, 89%, 48%)" },
  { name: "SOL/USDT", value: 15, color: "hsl(262, 83%, 58%)" },
  { name: "Cash", value: 10, color: "hsl(215, 15%, 55%)" },
];

const monthlyReturns = [
  { month: "Oct '24", ret: "+4.2%" }, { month: "Nov '24", ret: "+7.8%" },
  { month: "Dec '24", ret: "-2.1%" }, { month: "Jan '25", ret: "+5.6%" },
  { month: "Feb '25", ret: "+3.1%" }, { month: "Mar '25", ret: "+1.2%" },
];

export default function Portfolio() {
  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Portfolio Overview</h1>
        <p className="text-sm text-muted-foreground">Asset allocation and performance tracking</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Total Value" value="$12,845.90" change="+16.8% all time" changeType="profit" icon={DollarSign} />
        <StatCard label="Positions" value="3" change="Across 3 assets" changeType="neutral" icon={PieIcon} />
        <StatCard label="Best Performer" value="BTC" change="+34.5%" changeType="profit" icon={TrendingUp} />
        <StatCard label="Sharpe Ratio" value="1.82" change="Excellent" changeType="profit" icon={BarChart3} />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Allocation chart */}
        <div className="rounded-lg border border-border bg-card p-5">
          <h3 className="text-sm font-semibold text-foreground mb-4">Asset Allocation</h3>
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
                  <span className="font-mono text-sm text-muted-foreground">{a.value}%</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Monthly returns */}
        <div className="rounded-lg border border-border bg-card p-5">
          <h3 className="text-sm font-semibold text-foreground mb-4">Monthly Returns</h3>
          <div className="space-y-2">
            {monthlyReturns.map((m) => (
              <div key={m.month} className="flex items-center justify-between rounded-md bg-surface-1 px-4 py-2.5">
                <span className="text-sm text-muted-foreground">{m.month}</span>
                <span className={`font-mono text-sm font-semibold ${m.ret.startsWith("+") ? "text-profit" : "text-loss"}`}>
                  {m.ret}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
