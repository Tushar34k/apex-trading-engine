import { ShieldCheck, AlertTriangle, Activity, Settings2, Gauge } from "lucide-react";
import { useState } from "react";
import { useBots } from "@/hooks/api/useBots";
import { usePositions } from "@/hooks/api/useTrades";
import { useNotifications } from "@/hooks/useNotifications";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { RiskSettingsPanel } from "@/components/trading/RiskSettingsPanel";
import { TradeQualityPanel } from "@/components/trading/TradeQualityPanel";
import type { TradingBot, Position } from "@/types";

export default function RiskControl() {
  const { data: botsList } = useBots();
  const { data: positionsList } = usePositions();
  const { notifications: allNotifications } = useNotifications();
  const [selectedBot, setSelectedBot] = useState<TradingBot | undefined>();

  const riskAlerts = allNotifications.filter(
    (n) => n.type === "RISK_BLOCKED" || n.type === "BOT_SL" || n.type === "BOT_TRAILING_SL"
  );

  const bots = botsList ?? [];
  const positions = positionsList ?? [];

  const liveBots = bots.filter((b) => b.exchangeMode === "LIVE" && b.status === "RUNNING");
  const openPositionCount = positions.length;
  const totalExposure = positions.reduce((sum, p) => sum + p.entryPrice * p.size, 0);

  const parseParams = (bot: TradingBot): Record<string, unknown> => {
    try {
      return bot.strategyParams ? JSON.parse(bot.strategyParams) : {};
    } catch {
      return {};
    }
  };

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center gap-3">
        <ShieldCheck className="h-5 w-5 text-primary" />
        <h1 className="text-xl font-bold text-foreground">Risk Control</h1>
      </div>

      {/* Risk overview stats */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Live Bots</div>
          <div className={cn("text-2xl font-bold font-mono", liveBots.length > 0 ? "text-destructive" : "text-foreground")}>
            {liveBots.length}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Open Positions</div>
          <div className="text-2xl font-bold font-mono text-foreground">{openPositionCount}</div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Total Exposure</div>
          <div className="text-2xl font-bold font-mono text-foreground">
            ${totalExposure.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-[10px] uppercase text-muted-foreground">Risk Alerts</div>
          <div className={cn("text-2xl font-bold font-mono", riskAlerts.length > 0 ? "text-destructive" : "text-foreground")}>
            {riskAlerts.length}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        {/* Left column: Risk settings + Quality */}
        <div className="space-y-4">
          {/* Bot selector for risk settings */}
          <div className="rounded-lg border border-border bg-card p-4 space-y-3">
            <h3 className="text-xs font-semibold text-foreground flex items-center gap-2">
              <Settings2 className="h-3.5 w-3.5 text-primary" />
              Configure Bot Risk
            </h3>
            <div className="space-y-1">
              {bots.map((bot) => (
                <button
                  key={bot.id}
                  onClick={() => setSelectedBot(bot)}
                  className={cn(
                    "w-full flex items-center justify-between rounded-md px-3 py-2 text-xs transition-colors",
                    selectedBot?.id === bot.id
                      ? "bg-primary/10 text-primary"
                      : "text-muted-foreground hover:bg-muted/30 hover:text-foreground"
                  )}
                >
                  <span className="font-medium">{bot.name}</span>
                  <div className="flex items-center gap-2">
                    <Badge variant="outline" className={cn("text-[9px]",
                      bot.exchangeMode === "LIVE" ? "border-destructive/50 text-destructive" : ""
                    )}>
                      {bot.exchangeMode}
                    </Badge>
                    <span className={cn("text-[10px] font-bold",
                      bot.status === "RUNNING" ? "text-profit" : "text-muted-foreground"
                    )}>
                      {bot.status}
                    </span>
                  </div>
                </button>
              ))}
              {bots.length === 0 && (
                <p className="text-xs text-muted-foreground py-4 text-center">No bots created yet</p>
              )}
            </div>
          </div>

          <RiskSettingsPanel bot={selectedBot} />
          <TradeQualityPanel />
        </div>

        {/* Right column: Config table + Alerts + Positions */}
        <div className="xl:col-span-2 space-y-4">
          {/* Per-bot risk config */}
          <div className="rounded-lg border border-border bg-card">
            <div className="border-b border-border px-4 py-3">
              <h3 className="text-sm font-semibold text-foreground">Bot Risk Overview</h3>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-border text-muted-foreground">
                    <th className="px-4 py-2 text-left font-medium">Bot</th>
                    <th className="px-4 py-2 text-left font-medium">Mode</th>
                    <th className="px-4 py-2 text-right font-medium">Trade Size</th>
                    <th className="px-4 py-2 text-right font-medium">SL</th>
                    <th className="px-4 py-2 text-right font-medium">TP</th>
                    <th className="px-4 py-2 text-right font-medium">Trailing</th>
                    <th className="px-4 py-2 text-right font-medium">Daily Loss</th>
                    <th className="px-4 py-2 text-right font-medium">Min Score</th>
                    <th className="px-4 py-2 text-left font-medium">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/50">
                  {bots.map((bot) => {
                    const params = parseParams(bot);
                    return (
                      <tr key={bot.id} className="hover:bg-muted/30 transition-colors">
                        <td className="px-4 py-2.5 font-medium text-foreground">{bot.name}</td>
                        <td className="px-4 py-2.5">
                          <Badge variant="outline" className={cn("text-[10px]",
                            bot.exchangeMode === "LIVE" ? "border-destructive/50 text-destructive" : ""
                          )}>
                            {bot.exchangeMode}
                          </Badge>
                        </td>
                        <td className="px-4 py-2.5 text-right font-mono text-foreground">{bot.tradeSizePercent}%</td>
                        <td className="px-4 py-2.5 text-right font-mono">
                          {params.stopLossPercent ? (
                            <span className="text-destructive">{String(params.stopLossPercent)}%</span>
                          ) : (
                            <span className="text-muted-foreground">ATR</span>
                          )}
                        </td>
                        <td className="px-4 py-2.5 text-right font-mono">
                          {params.takeProfitPercent ? (
                            <span className="text-profit">{String(params.takeProfitPercent)}%</span>
                          ) : (
                            <span className="text-muted-foreground">R:R</span>
                          )}
                        </td>
                        <td className="px-4 py-2.5 text-right font-mono">
                          {params.trailingStopPercent ? (
                            <span className="text-warning">{String(params.trailingStopPercent)}%</span>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="px-4 py-2.5 text-right font-mono">
                          <span className="text-destructive">{String(params.maxDailyLossPercent ?? 3)}%</span>
                        </td>
                        <td className="px-4 py-2.5 text-right font-mono">
                          <span className="text-primary">{String(params.minTradeScore ?? 70)}</span>
                        </td>
                        <td className="px-4 py-2.5">
                          <span className={cn("text-[10px] font-bold",
                            bot.status === "RUNNING" ? "text-profit" : "text-muted-foreground")}>
                            {bot.status}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* Risk alerts */}
          <div className="rounded-lg border border-border bg-card">
            <div className="border-b border-border px-4 py-3 flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-warning" />
              <h3 className="text-sm font-semibold text-foreground">Recent Risk Events</h3>
            </div>
            {riskAlerts.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-muted-foreground">No risk events</div>
            ) : (
              <div className="divide-y divide-border/50 max-h-60 overflow-y-auto">
                {riskAlerts.slice(0, 20).map((alert, i) => (
                  <div key={i} className="px-4 py-2.5 flex items-start gap-3">
                    <div className={cn("mt-0.5 h-2 w-2 rounded-full shrink-0",
                      alert.type === "RISK_BLOCKED" ? "bg-destructive" : "bg-warning"
                    )} />
                    <div className="flex-1 min-w-0">
                      <div className="text-xs text-foreground">{alert.message}</div>
                      <div className="text-[10px] text-muted-foreground">
                        {alert.botName} · {alert.symbol} · {new Date(alert.timestamp).toLocaleTimeString()}
                      </div>
                    </div>
                    <Badge variant="outline" className="text-[9px] shrink-0">
                      {alert.type.replace("BOT_", "")}
                    </Badge>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Open positions */}
          <div className="rounded-lg border border-border bg-card">
            <div className="border-b border-border px-4 py-3 flex items-center gap-2">
              <Activity className="h-4 w-4 text-primary" />
              <h3 className="text-sm font-semibold text-foreground">Active Position Exposure</h3>
            </div>
            {positions.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-muted-foreground">No open positions</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-border text-muted-foreground">
                      <th className="px-4 py-2 text-left font-medium">Symbol</th>
                      <th className="px-4 py-2 text-left font-medium">Side</th>
                      <th className="px-4 py-2 text-right font-medium">Size</th>
                      <th className="px-4 py-2 text-right font-medium">Entry</th>
                      <th className="px-4 py-2 text-right font-medium">Current</th>
                      <th className="px-4 py-2 text-right font-medium">P&L</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border/50">
                    {positions.map((p) => (
                      <tr key={p.id} className="hover:bg-muted/30">
                        <td className="px-4 py-2.5 font-mono font-semibold text-foreground">{p.symbol}</td>
                        <td className="px-4 py-2.5">
                          <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold",
                            p.side === "LONG" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>
                            {p.side}
                          </span>
                        </td>
                        <td className="px-4 py-2.5 text-right font-mono text-foreground">{p.size.toFixed(6)}</td>
                        <td className="px-4 py-2.5 text-right font-mono text-muted-foreground">${p.entryPrice.toLocaleString()}</td>
                        <td className="px-4 py-2.5 text-right font-mono text-foreground">${p.currentPrice.toLocaleString()}</td>
                        <td className={cn("px-4 py-2.5 text-right font-mono font-semibold",
                          p.pnl >= 0 ? "text-profit" : "text-loss")}>
                          {p.pnl >= 0 ? '+' : ''}${p.pnl.toFixed(2)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
