import { BarChart3, Circle } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { cn } from "@/lib/utils";

const paperTrades = [
  { id: 1, symbol: "BTC/USDT", side: "LONG", entry: "66,200", exit: "67,100", pnl: "+$135.00", status: "closed", strategy: "Trend Following v2.1" },
  { id: 2, symbol: "ETH/USDT", side: "SHORT", entry: "3,550", exit: "—", pnl: "+$42.00", status: "open", strategy: "Breakout v1.3" },
  { id: 3, symbol: "SOL/USDT", side: "LONG", entry: "145.50", exit: "143.20", pnl: "-$103.50", status: "closed", strategy: "Pullback v1.0" },
  { id: 4, symbol: "BTC/USDT", side: "LONG", entry: "65,800", exit: "66,950", pnl: "+$172.50", status: "closed", strategy: "Trend Following v2.1" },
];

export default function PaperTrading() {
  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Paper Trading</h1>
        <p className="text-sm text-muted-foreground">Simulate trades with live data, no real money at risk</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Virtual Balance" value="$10,246.00" change="+2.46%" changeType="profit" icon={BarChart3} />
        <StatCard label="Open Positions" value="1" change="ETH/USDT" changeType="neutral" />
        <StatCard label="Total Trades" value="4" change="This session" changeType="neutral" />
        <StatCard label="Session P&L" value="+$246.00" change="+2.46%" changeType="profit" />
      </div>

      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-4 py-3 flex items-center gap-2">
          <h3 className="text-sm font-semibold text-foreground">Paper Trades</h3>
          <span className="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-bold text-primary">SIMULATED</span>
        </div>
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-border text-muted-foreground">
              <th className="px-4 py-2.5 text-left font-medium">Symbol</th>
              <th className="px-4 py-2.5 text-left font-medium">Side</th>
              <th className="px-4 py-2.5 text-right font-medium">Entry</th>
              <th className="px-4 py-2.5 text-right font-medium">Exit</th>
              <th className="px-4 py-2.5 text-right font-medium">P&L</th>
              <th className="px-4 py-2.5 text-left font-medium">Strategy</th>
              <th className="px-4 py-2.5 text-right font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {paperTrades.map((t) => (
              <tr key={t.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                <td className="px-4 py-3 font-mono font-semibold text-foreground">{t.symbol}</td>
                <td className="px-4 py-3">
                  <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold", t.side === "LONG" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>{t.side}</span>
                </td>
                <td className="px-4 py-3 text-right font-mono text-muted-foreground">${t.entry}</td>
                <td className="px-4 py-3 text-right font-mono text-muted-foreground">{t.exit}</td>
                <td className={cn("px-4 py-3 text-right font-mono font-semibold", t.pnl.startsWith("+") ? "text-profit" : "text-loss")}>{t.pnl}</td>
                <td className="px-4 py-3 text-muted-foreground">{t.strategy}</td>
                <td className="px-4 py-3 text-right">
                  <span className="flex items-center justify-end gap-1">
                    <Circle className={cn("h-1.5 w-1.5 fill-current", t.status === "open" ? "text-profit" : "text-muted-foreground")} />
                    <span className="text-muted-foreground">{t.status}</span>
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
