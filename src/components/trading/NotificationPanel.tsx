import { Bell, TrendingUp, TrendingDown, ShieldAlert, Target, Activity } from "lucide-react";
import { cn } from "@/lib/utils";
import { useNotifications } from "@/hooks/useNotifications";
import { Badge } from "@/components/ui/badge";

const ICON_MAP: Record<string, typeof Bell> = {
  BOT_BUY: TrendingUp,
  BOT_SELL: TrendingDown,
  BOT_SL: ShieldAlert,
  BOT_TP: Target,
  BOT_TRAILING_SL: Activity,
  RISK_BLOCKED: ShieldAlert,
};

const COLOR_MAP: Record<string, string> = {
  BOT_BUY: "text-profit",
  BOT_SELL: "text-loss",
  BOT_SL: "text-destructive",
  BOT_TP: "text-profit",
  BOT_TRAILING_SL: "text-warning",
  RISK_BLOCKED: "text-destructive",
};

export function NotificationPanel() {
  const { notifications, clear } = useNotifications();

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div className="flex items-center gap-2">
          <Bell className="h-4 w-4 text-primary" />
          <h3 className="text-sm font-semibold text-foreground">Notifications</h3>
          {notifications.length > 0 && (
            <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
              {notifications.length}
            </Badge>
          )}
        </div>
        {notifications.length > 0 && (
          <button onClick={clear} className="text-[10px] text-muted-foreground hover:text-foreground transition-colors">
            Clear
          </button>
        )}
      </div>
      <div className="max-h-64 overflow-y-auto">
        {notifications.length === 0 ? (
          <div className="px-4 py-6 text-center text-xs text-muted-foreground">
            No notifications yet
          </div>
        ) : (
          <div className="divide-y divide-border/50">
            {notifications.map((n, i) => {
              const Icon = ICON_MAP[n.type] ?? Bell;
              const color = COLOR_MAP[n.type] ?? "text-muted-foreground";
              return (
                <div key={i} className="flex items-start gap-3 px-4 py-2.5 hover:bg-muted/30 transition-colors">
                  <Icon className={cn("h-4 w-4 mt-0.5 shrink-0", color)} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-medium text-foreground">{n.botName}</span>
                      <span className="text-[10px] text-muted-foreground">{n.symbol}</span>
                    </div>
                    <p className="text-[11px] text-muted-foreground">{n.message}</p>
                    {n.pnl !== undefined && (
                      <span className={cn("text-[10px] font-mono", n.pnl >= 0 ? "text-profit" : "text-loss")}>
                        PnL: {n.pnl >= 0 ? '+' : ''}${n.pnl.toFixed(2)}
                      </span>
                    )}
                  </div>
                  <span className="text-[9px] text-muted-foreground whitespace-nowrap">
                    {n.timestamp ? new Date(n.timestamp).toLocaleTimeString() : ''}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
