import { Shield, AlertTriangle, Lock, Activity } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { cn } from "@/lib/utils";

const riskRules = [
  { level: "Trade-Level", rules: [
    { name: "Risk Per Trade", value: "1.5%", max: "3.0%", status: "ok" },
    { name: "Stop-Loss Required", value: "Yes", max: "Always", status: "ok" },
    { name: "Min Risk/Reward", value: "1:2", max: "1:1.5", status: "ok" },
  ]},
  { level: "Daily Risk", rules: [
    { name: "Daily Loss", value: "-$142.30", max: "$500.00", status: "ok" },
    { name: "Daily Loss Limit", value: "28.5%", max: "100%", status: "ok" },
    { name: "Auto Stop", value: "Active", max: "—", status: "ok" },
  ]},
  { level: "Portfolio", rules: [
    { name: "Total Exposure", value: "34.2%", max: "60%", status: "ok" },
    { name: "BTC Exposure", value: "18.5%", max: "25%", status: "ok" },
    { name: "Correlation Score", value: "0.42", max: "0.70", status: "warning" },
    { name: "Current Drawdown", value: "-4.2%", max: "-15%", status: "ok" },
  ]},
];

export default function RiskControl() {
  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Risk Management Engine</h1>
        <p className="text-sm text-muted-foreground">Multi-level risk controls and position sizing</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Total Exposure" value="34.2%" change="Within limits" changeType="neutral" icon={Shield} />
        <StatCard label="Daily P&L" value="-$142.30" change="-1.1%" changeType="loss" icon={Activity} />
        <StatCard label="Max Drawdown" value="-4.2%" change="Far from limit" changeType="profit" icon={AlertTriangle} />
        <StatCard label="Risk Level" value="LOW" change="All checks pass" changeType="profit" icon={Lock} />
      </div>

      {/* Position Size Calculator */}
      <div className="rounded-lg border border-border bg-card p-5">
        <h3 className="text-sm font-semibold text-foreground mb-3">Position Size Formula</h3>
        <div className="rounded-md bg-surface-2 px-4 py-3 font-mono text-sm text-primary">
          PositionSize = (AccountBalance × Risk%) / StopLossDistance
        </div>
        <div className="mt-3 grid grid-cols-4 gap-4 text-center">
          <div className="rounded-md bg-surface-1 p-3">
            <div className="text-[10px] text-muted-foreground uppercase">Balance</div>
            <div className="font-mono text-sm text-foreground mt-1">$12,845.90</div>
          </div>
          <div className="rounded-md bg-surface-1 p-3">
            <div className="text-[10px] text-muted-foreground uppercase">Risk %</div>
            <div className="font-mono text-sm text-foreground mt-1">1.5%</div>
          </div>
          <div className="rounded-md bg-surface-1 p-3">
            <div className="text-[10px] text-muted-foreground uppercase">SL Distance</div>
            <div className="font-mono text-sm text-foreground mt-1">$1,650</div>
          </div>
          <div className="rounded-md bg-surface-1 p-3">
            <div className="text-[10px] text-muted-foreground uppercase">Position Size</div>
            <div className="font-mono text-sm text-primary font-bold mt-1">0.1168 BTC</div>
          </div>
        </div>
      </div>

      {/* Risk Rules */}
      <div className="space-y-4">
        {riskRules.map((level) => (
          <div key={level.level} className="rounded-lg border border-border bg-card overflow-hidden">
            <div className="border-b border-border px-5 py-3">
              <h3 className="text-sm font-semibold text-foreground">{level.level} Risk</h3>
            </div>
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-muted-foreground">
                  <th className="px-5 py-2.5 text-left font-medium">Rule</th>
                  <th className="px-5 py-2.5 text-right font-medium">Current</th>
                  <th className="px-5 py-2.5 text-right font-medium">Limit</th>
                  <th className="px-5 py-2.5 text-right font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {level.rules.map((r) => (
                  <tr key={r.name} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                    <td className="px-5 py-3 text-foreground">{r.name}</td>
                    <td className="px-5 py-3 text-right font-mono text-foreground">{r.value}</td>
                    <td className="px-5 py-3 text-right font-mono text-muted-foreground">{r.max}</td>
                    <td className="px-5 py-3 text-right">
                      <span className={cn("rounded px-2 py-0.5 text-[10px] font-bold", r.status === "ok" ? "bg-profit/10 text-profit" : "bg-warning/10 text-warning")}>
                        {r.status === "ok" ? "PASS" : "WARNING"}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </div>
  );
}
