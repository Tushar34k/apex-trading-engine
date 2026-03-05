import { cn } from "@/lib/utils";
import { useTrades } from "@/hooks/api/useTrades";
import type { Trade } from "@/types";

export function TradeHistory() {
  const { data: tradesList, isLoading } = useTrades();

  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-card p-8 flex items-center justify-center">
        <span className="text-sm text-muted-foreground">Loading trades...</span>
      </div>
    );
  }

  const trades = tradesList ?? [];

  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-semibold text-foreground">Trade History</h3>
      </div>
      {trades.length === 0 ? (
        <div className="px-4 py-8 text-center text-sm text-muted-foreground">No trades yet. Start a bot to begin trading.</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-4 py-2.5 text-left font-medium">Symbol</th>
                <th className="px-4 py-2.5 text-left font-medium">Side</th>
                <th className="px-4 py-2.5 text-right font-medium">Entry</th>
                <th className="px-4 py-2.5 text-right font-medium">Exit</th>
                <th className="px-4 py-2.5 text-right font-medium">Qty</th>
                <th className="px-4 py-2.5 text-right font-medium">P&L</th>
                <th className="px-4 py-2.5 text-left font-medium">Opened</th>
                <th className="px-4 py-2.5 text-left font-medium">Closed</th>
              </tr>
            </thead>
            <tbody>
              {trades.map((t: Trade) => {
                const pnl = t.pnl ?? 0;
                const isProfitable = pnl >= 0;
                return (
                  <tr key={t.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                    <td className="px-4 py-3 font-mono font-semibold text-foreground">{t.symbol}</td>
                    <td className="px-4 py-3">
                      <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold", t.side === "BUY" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>
                        {t.side}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-muted-foreground">${t.entryPrice.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-mono text-foreground">{t.exitPrice ? `$${t.exitPrice.toLocaleString()}` : '—'}</td>
                    <td className="px-4 py-3 text-right font-mono text-foreground">{t.quantity.toFixed(6)}</td>
                    <td className="px-4 py-3 text-right">
                      <span className={cn("font-mono", isProfitable ? "text-profit" : "text-loss")}>
                        {isProfitable ? '+' : ''}${pnl.toFixed(2)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{new Date(t.openedAt).toLocaleString()}</td>
                    <td className="px-4 py-3 text-muted-foreground">{t.closedAt ? new Date(t.closedAt).toLocaleString() : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
