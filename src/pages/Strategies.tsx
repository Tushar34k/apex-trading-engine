import { Bot, Play, Pause, Settings, TrendingUp, Zap, ArrowDownUp } from "lucide-react";
import { cn } from "@/lib/utils";

const strategies = [
  {
    name: "Trend Following",
    version: "v2.1",
    description: "EMA crossover with ATR-based stops. Adapts position size based on volatility regime.",
    status: "active",
    performance: { totalReturn: "+34.5%", winRate: "68%", sharpe: "1.82", maxDD: "-8.3%", trades: 127 },
    params: { emaFast: 12, emaSlow: 26, atrPeriod: 14, atrMultiplier: 2.5, riskPerTrade: "1.5%" },
    regime: "Trending",
    icon: TrendingUp,
  },
  {
    name: "Breakout Strategy",
    version: "v1.3",
    description: "Detects consolidation breakouts with volume confirmation. Works best in ranging-to-trending transitions.",
    status: "active",
    performance: { totalReturn: "+21.8%", winRate: "58%", sharpe: "1.45", maxDD: "-11.2%", trades: 89 },
    params: { lookback: 20, volMultiplier: 1.5, breakoutThreshold: "2%", riskPerTrade: "1.0%" },
    regime: "Ranging → Trending",
    icon: Zap,
  },
  {
    name: "Pullback Strategy",
    version: "v1.0",
    description: "Enters on pullbacks within established trends. Uses RSI and support levels.",
    status: "paused",
    performance: { totalReturn: "+12.3%", winRate: "52%", sharpe: "1.12", maxDD: "-14.5%", trades: 64 },
    params: { rsiPeriod: 14, rsiOversold: 35, trendEma: 50, riskPerTrade: "1.0%" },
    regime: "Trending",
    icon: ArrowDownUp,
  },
];

export default function Strategies() {
  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">Strategy Framework</h1>
          <p className="text-sm text-muted-foreground">Manage and configure trading strategies</p>
        </div>
        <button className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors">
          <Bot className="h-4 w-4" /> New Strategy
        </button>
      </div>

      <div className="space-y-4">
        {strategies.map((s) => (
          <div key={s.name} className="rounded-lg border border-border bg-card overflow-hidden">
            <div className="flex items-start justify-between p-5">
              <div className="flex items-start gap-4">
                <div className={cn("flex h-10 w-10 items-center justify-center rounded-lg", s.status === "active" ? "bg-profit/10" : "bg-muted")}>
                  <s.icon className={cn("h-5 w-5", s.status === "active" ? "text-profit" : "text-muted-foreground")} />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-base font-semibold text-foreground">{s.name}</h3>
                    <span className="rounded bg-surface-3 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">{s.version}</span>
                    <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-semibold", s.status === "active" ? "bg-profit/10 text-profit" : "bg-warning/10 text-warning")}>
                      {s.status.toUpperCase()}
                    </span>
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground max-w-xl">{s.description}</p>
                  <div className="mt-2 text-xs text-muted-foreground">
                    Regime: <span className="text-primary">{s.regime}</span>
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button className="rounded-md p-2 text-muted-foreground hover:bg-surface-2 hover:text-foreground transition-colors">
                  <Settings className="h-4 w-4" />
                </button>
                <button className={cn("rounded-md p-2 transition-colors", s.status === "active" ? "text-warning hover:bg-warning/10" : "text-profit hover:bg-profit/10")}>
                  {s.status === "active" ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                </button>
              </div>
            </div>

            {/* Performance row */}
            <div className="grid grid-cols-5 border-t border-border bg-surface-1">
              {[
                { label: "Total Return", value: s.performance.totalReturn, type: s.performance.totalReturn.startsWith("+") ? "profit" : "loss" },
                { label: "Win Rate", value: s.performance.winRate, type: "neutral" },
                { label: "Sharpe Ratio", value: s.performance.sharpe, type: "neutral" },
                { label: "Max Drawdown", value: s.performance.maxDD, type: "loss" },
                { label: "Total Trades", value: String(s.performance.trades), type: "neutral" },
              ].map((m) => (
                <div key={m.label} className="px-5 py-3 text-center border-r border-border/50 last:border-r-0">
                  <div className="text-[10px] text-muted-foreground uppercase tracking-wider">{m.label}</div>
                  <div className={cn("mt-1 font-mono text-sm font-semibold", m.type === "profit" && "text-profit", m.type === "loss" && "text-loss", m.type === "neutral" && "text-foreground")}>
                    {m.value}
                  </div>
                </div>
              ))}
            </div>

            {/* Parameters */}
            <div className="border-t border-border px-5 py-3">
              <div className="text-[10px] text-muted-foreground uppercase tracking-wider mb-2">Parameters</div>
              <div className="flex flex-wrap gap-2">
                {Object.entries(s.params).map(([k, v]) => (
                  <span key={k} className="rounded bg-surface-2 px-2 py-1 font-mono text-[11px] text-muted-foreground">
                    {k}: <span className="text-foreground">{v}</span>
                  </span>
                ))}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
