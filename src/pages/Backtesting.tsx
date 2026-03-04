import { FlaskConical, Play } from "lucide-react";
import { Area, AreaChart, ResponsiveContainer, XAxis, YAxis, Tooltip } from "recharts";
import { cn } from "@/lib/utils";

const backtestResults = [
  {
    id: 1, strategy: "Trend Following", version: "v2.1", symbol: "BTC/USDT", period: "Jan 2024 – Mar 2025",
    metrics: { totalReturn: "+34.5%", winRate: "68%", sharpe: "1.82", maxDD: "-8.3%", profitFactor: "2.14", totalTrades: 127 },
  },
  {
    id: 2, strategy: "Breakout", version: "v1.3", symbol: "ETH/USDT", period: "Jan 2024 – Mar 2025",
    metrics: { totalReturn: "+21.8%", winRate: "58%", sharpe: "1.45", maxDD: "-11.2%", profitFactor: "1.76", totalTrades: 89 },
  },
  {
    id: 3, strategy: "Pullback", version: "v1.0", symbol: "SOL/USDT", period: "Jun 2024 – Mar 2025",
    metrics: { totalReturn: "+12.3%", winRate: "52%", sharpe: "1.12", maxDD: "-14.5%", profitFactor: "1.32", totalTrades: 64 },
  },
];

const equityData = Array.from({ length: 60 }, (_, i) => ({
  day: i,
  equity: 10000 + Math.sin(i / 8) * 500 + i * 50 + (Math.random() - 0.3) * 200,
}));

export default function Backtesting() {
  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">Backtesting Engine</h1>
          <p className="text-sm text-muted-foreground">Test strategies against historical data</p>
        </div>
        <button className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors">
          <Play className="h-4 w-4" /> Run Backtest
        </button>
      </div>

      {/* Equity Chart */}
      <div className="rounded-lg border border-border bg-card p-5">
        <h3 className="text-sm font-semibold text-foreground mb-4">Backtest Equity – Trend Following v2.1</h3>
        <ResponsiveContainer width="100%" height={250}>
          <AreaChart data={equityData}>
            <defs>
              <linearGradient id="btGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="hsl(142 71% 45%)" stopOpacity={0.3} />
                <stop offset="100%" stopColor="hsl(142 71% 45%)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <XAxis dataKey="day" tick={{ fill: "hsl(215 15% 55%)", fontSize: 10 }} axisLine={{ stroke: "hsl(220 14% 18%)" }} tickLine={false} />
            <YAxis tick={{ fill: "hsl(215 15% 55%)", fontSize: 10 }} axisLine={false} tickLine={false} tickFormatter={(v) => `$${(v/1000).toFixed(1)}k`} width={50} />
            <Tooltip contentStyle={{ background: "hsl(220 18% 10%)", border: "1px solid hsl(220 14% 18%)", borderRadius: "6px", fontSize: "12px", color: "hsl(210 20% 92%)" }} />
            <Area type="monotone" dataKey="equity" stroke="hsl(142 71% 45%)" strokeWidth={2} fill="url(#btGrad)" />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* Results */}
      <div className="space-y-4">
        {backtestResults.map((r) => (
          <div key={r.id} className="rounded-lg border border-border bg-card overflow-hidden">
            <div className="flex items-center justify-between px-5 py-3 border-b border-border">
              <div className="flex items-center gap-3">
                <FlaskConical className="h-4 w-4 text-primary" />
                <span className="text-sm font-semibold text-foreground">{r.strategy} {r.version}</span>
                <span className="text-xs text-muted-foreground">· {r.symbol} · {r.period}</span>
              </div>
            </div>
            <div className="grid grid-cols-6 divide-x divide-border/50 bg-surface-1">
              {Object.entries(r.metrics).map(([key, val]) => (
                <div key={key} className="px-4 py-3 text-center">
                  <div className="text-[10px] text-muted-foreground uppercase tracking-wider">{key.replace(/([A-Z])/g, ' $1').trim()}</div>
                  <div className={cn("mt-1 font-mono text-sm font-semibold", 
                    typeof val === 'string' && val.startsWith("+") && "text-profit",
                    typeof val === 'string' && val.startsWith("-") && "text-loss",
                    !(typeof val === 'string' && (val.startsWith("+") || val.startsWith("-"))) && "text-foreground"
                  )}>{val}</div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
