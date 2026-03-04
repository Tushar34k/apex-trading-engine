import { cn } from "@/lib/utils";
import { usePositions } from "@/hooks/api/useTrades";
import type { Position } from "@/types";

export function PositionsTable() {
  const { data: positionsList, isLoading } = usePositions();

  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-card p-8 flex items-center justify-center">
        <span className="text-sm text-muted-foreground">Loading positions...</span>
      </div>
    );
  }

  const positions = positionsList ?? [];

  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-semibold text-foreground">Open Positions</h3>
      </div>
      {positions.length === 0 ? (
        <div className="px-4 py-8 text-center text-sm text-muted-foreground">No open positions</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-4 py-2.5 text-left font-medium">Symbol</th>
                <th className="px-4 py-2.5 text-left font-medium">Side</th>
                <th className="px-4 py-2.5 text-right font-medium">Size</th>
                <th className="px-4 py-2.5 text-right font-medium">Entry</th>
                <th className="px-4 py-2.5 text-right font-medium">Current</th>
                <th className="px-4 py-2.5 text-right font-medium">P&L</th>
                <th className="px-4 py-2.5 text-left font-medium">Strategy</th>
                <th className="px-4 py-2.5 text-right font-medium">SL</th>
                <th className="px-4 py-2.5 text-right font-medium">TP</th>
              </tr>
            </thead>
            <tbody>
              {positions.map((p: Position) => {
                const isProfitable = p.pnl >= 0;
                return (
                  <tr key={p.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                    <td className="px-4 py-3 font-mono font-semibold text-foreground">{p.symbol}</td>
                    <td className="px-4 py-3">
                      <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold", p.side === "LONG" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>
                        {p.side}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-foreground">{p.size.toFixed(4)}</td>
                    <td className="px-4 py-3 text-right font-mono text-muted-foreground">${p.entryPrice.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-mono text-foreground">${p.currentPrice.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right">
                      <div className={cn("font-mono", isProfitable ? "text-profit" : "text-loss")}>
                        <div>{isProfitable ? '+' : ''}${p.pnl.toFixed(2)}</div>
                        <div className="text-[10px] opacity-70">{isProfitable ? '+' : ''}{p.pnlPercent.toFixed(2)}%</div>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{p.strategyName}</td>
                    <td className="px-4 py-3 text-right font-mono text-loss/70">${p.stopLoss.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-mono text-profit/70">${p.takeProfit.toLocaleString()}</td>
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
