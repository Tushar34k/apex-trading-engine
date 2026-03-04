import { cn } from "@/lib/utils";

const positions = [
  { symbol: "BTC/USDT", side: "LONG", size: "0.15", entry: "66,850.00", current: "67,432.50", pnl: "+$87.38", pnlPct: "+1.30%", strategy: "Trend Following v2.1", sl: "65,200.00", tp: "69,500.00" },
  { symbol: "ETH/USDT", side: "LONG", size: "2.40", entry: "3,480.00", current: "3,521.20", pnl: "+$98.88", pnlPct: "+1.18%", strategy: "Breakout v1.3", sl: "3,350.00", tp: "3,750.00" },
  { symbol: "SOL/USDT", side: "SHORT", size: "45.0", entry: "148.50", current: "151.20", pnl: "-$121.50", pnlPct: "-1.82%", strategy: "Pullback v1.0", sl: "155.00", tp: "140.00" },
];

export function PositionsTable() {
  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-semibold text-foreground">Open Positions</h3>
      </div>
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
            {positions.map((p) => (
              <tr key={p.symbol} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                <td className="px-4 py-3 font-mono font-semibold text-foreground">{p.symbol}</td>
                <td className="px-4 py-3">
                  <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold", p.side === "LONG" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>
                    {p.side}
                  </span>
                </td>
                <td className="px-4 py-3 text-right font-mono text-foreground">{p.size}</td>
                <td className="px-4 py-3 text-right font-mono text-muted-foreground">${p.entry}</td>
                <td className="px-4 py-3 text-right font-mono text-foreground">${p.current}</td>
                <td className="px-4 py-3 text-right">
                  <div className={cn("font-mono", p.pnl.startsWith("+") ? "text-profit" : "text-loss")}>
                    <div>{p.pnl}</div>
                    <div className="text-[10px] opacity-70">{p.pnlPct}</div>
                  </div>
                </td>
                <td className="px-4 py-3 text-muted-foreground">{p.strategy}</td>
                <td className="px-4 py-3 text-right font-mono text-loss/70">${p.sl}</td>
                <td className="px-4 py-3 text-right font-mono text-profit/70">${p.tp}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
