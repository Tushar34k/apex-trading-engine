import { Bot, Circle, TrendingUp, TrendingDown } from "lucide-react";
import { cn } from "@/lib/utils";

const bots = [
  { name: "BTC Trend Follower", strategy: "Trend Following v2.1", status: "running", pnl: "+$1,234.50", pnlType: "profit" as const, trades: 47, winRate: "68%", symbol: "BTC/USDT" },
  { name: "ETH Breakout Hunter", strategy: "Breakout v1.3", status: "running", pnl: "+$856.20", pnlType: "profit" as const, trades: 31, winRate: "58%", symbol: "ETH/USDT" },
  { name: "SOL Pullback", strategy: "Pullback v1.0", status: "running", pnl: "-$245.80", pnlType: "loss" as const, trades: 22, winRate: "45%", symbol: "SOL/USDT" },
  { name: "AVAX Trend", strategy: "Trend Following v2.1", status: "paused", pnl: "+$112.00", pnlType: "profit" as const, trades: 8, winRate: "62%", symbol: "AVAX/USDT" },
];

export function ActiveBots() {
  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-semibold text-foreground">Trading Bots</h3>
      </div>
      <div className="divide-y divide-border/50">
        {bots.map((bot) => (
          <div key={bot.name} className="flex items-center justify-between px-4 py-3 hover:bg-surface-2 transition-colors">
            <div className="flex items-center gap-3">
              <div className={cn("flex h-8 w-8 items-center justify-center rounded-md", bot.status === "running" ? "bg-profit/10" : "bg-muted")}>
                <Bot className={cn("h-4 w-4", bot.status === "running" ? "text-profit" : "text-muted-foreground")} />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">{bot.name}</span>
                  <Circle className={cn("h-1.5 w-1.5 fill-current", bot.status === "running" ? "text-profit" : "text-warning")} />
                </div>
                <div className="text-[11px] text-muted-foreground">{bot.strategy} · {bot.symbol}</div>
              </div>
            </div>
            <div className="flex items-center gap-4 text-right">
              <div>
                <div className={cn("font-mono text-sm font-semibold", bot.pnlType === "profit" ? "text-profit" : "text-loss")}>
                  {bot.pnl}
                </div>
                <div className="text-[10px] text-muted-foreground">{bot.trades} trades · {bot.winRate} win</div>
              </div>
              {bot.pnlType === "profit" ? (
                <TrendingUp className="h-4 w-4 text-profit" />
              ) : (
                <TrendingDown className="h-4 w-4 text-loss" />
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
