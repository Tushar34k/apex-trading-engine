import { Bot, Circle, Play, Square } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { useBots, useStartBot, useStopBot } from "@/hooks/api/useBots";
import type { TradingBot } from "@/types";

const STRATEGY_LABELS: Record<string, string> = {
  EMA_CROSS: "EMA Cross",
  SCALPING_EMA: "Scalp EMA",
  SUPPORT_RESISTANCE: "S/R",
};

export function ActiveBots() {
  const { data: botsList, isLoading } = useBots();
  const startBot = useStartBot();
  const stopBot = useStopBot();

  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-card p-8 flex items-center justify-center">
        <span className="text-sm text-muted-foreground">Loading bots...</span>
      </div>
    );
  }

  const bots = botsList ?? [];

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-semibold text-foreground">Trading Bots</h3>
      </div>
      {bots.length === 0 ? (
        <div className="px-4 py-8 text-center text-sm text-muted-foreground">No bots configured</div>
      ) : (
        <div className="divide-y divide-border/50">
          {bots.map((bot: TradingBot) => {
            const pnl = bot.pnl ?? 0;
            const isProfitable = pnl >= 0;
            return (
              <div key={bot.id} className="flex items-center justify-between px-4 py-3 hover:bg-muted/30 transition-colors">
                <div className="flex items-center gap-3">
                  <div className={cn("flex h-8 w-8 items-center justify-center rounded-md",
                    bot.status === "RUNNING" ? "bg-primary/10" : "bg-muted")}>
                    <Bot className={cn("h-4 w-4",
                      bot.status === "RUNNING" ? "text-primary" : "text-muted-foreground")} />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-foreground">{bot.name}</span>
                      <Circle className={cn("h-1.5 w-1.5 fill-current",
                        bot.status === "RUNNING" ? "text-primary" : "text-muted-foreground")} />
                      <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                        {bot.exchangeMode === "LIVE" ? "LIVE" : "TEST"}
                      </Badge>
                    </div>
                    <div className="text-[11px] text-muted-foreground">
                      {bot.symbol} · {bot.timeframe} · {STRATEGY_LABELS[bot.strategyType] ?? bot.strategyType} · {bot.tradeSizePercent}%
                    </div>
                    {bot.hasOpenPosition && (
                      <div className="text-[10px] text-amber-500 font-mono">
                        OPEN @ ${bot.entryPrice?.toLocaleString()} · Qty: {bot.quantity}
                      </div>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <div className={cn("font-mono text-sm font-semibold",
                      isProfitable ? "text-primary" : "text-destructive")}>
                      {isProfitable ? '+' : ''}${pnl.toFixed(2)}
                    </div>
                    <div className="text-[10px] text-muted-foreground">
                      {bot.totalTrades ?? 0} trades
                    </div>
                  </div>
                  <button
                    onClick={() => bot.status === 'RUNNING' ? stopBot.mutate(bot.id) : startBot.mutate(bot.id)}
                    disabled={startBot.isPending || stopBot.isPending}
                    className={cn("rounded-md p-1.5 transition-colors",
                      bot.status === 'RUNNING'
                        ? "text-destructive hover:bg-destructive/10"
                        : "text-primary hover:bg-primary/10")}
                  >
                    {bot.status === 'RUNNING' ? <Square className="h-3.5 w-3.5" /> : <Play className="h-3.5 w-3.5" />}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
