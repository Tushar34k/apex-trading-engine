import { Shield, AlertTriangle, Lock, Activity } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { cn } from "@/lib/utils";
import { useRiskConfig, useRiskStatus, useExposure } from "@/hooks/api/useRisk";
import type { RiskConfigItem, ExposureBreakdown } from "@/types";

export default function RiskControl() {
  const { data: configItems, isLoading: configLoading } = useRiskConfig();
  const { data: riskStatus, isLoading: statusLoading } = useRiskStatus();
  const { data: exposure } = useExposure();

  const isLoading = configLoading || statusLoading;

  // Group config by level
  const groupedConfig = (configItems ?? []).reduce<Record<string, RiskConfigItem[]>>((acc, item) => {
    const key = item.level;
    if (!acc[key]) acc[key] = [];
    acc[key].push(item);
    return acc;
  }, {});

  const levelLabels: Record<string, string> = {
    TRADE: 'Trade-Level',
    DAILY: 'Daily Risk',
    PORTFOLIO: 'Portfolio',
  };

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Risk Management Engine</h1>
        <p className="text-sm text-muted-foreground">Multi-level risk controls and position sizing</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="Total Exposure"
          value={riskStatus ? `${riskStatus.totalExposure.toFixed(1)}%` : '—'}
          change={riskStatus?.allChecksPassed ? 'Within limits' : 'Violations detected'}
          changeType={riskStatus?.allChecksPassed ? "neutral" : "loss"}
          icon={Shield}
        />
        <StatCard
          label="Daily P&L"
          value={riskStatus ? `$${riskStatus.dailyPnl.toFixed(2)}` : '—'}
          change={riskStatus ? `${((riskStatus.dailyPnl / riskStatus.dailyLossLimit) * 100).toFixed(0)}% of limit` : '—'}
          changeType={riskStatus && riskStatus.dailyPnl >= 0 ? "profit" : "loss"}
          icon={Activity}
        />
        <StatCard
          label="Max Drawdown"
          value={riskStatus ? `${riskStatus.currentDrawdown.toFixed(1)}%` : '—'}
          change={riskStatus ? `Limit: ${riskStatus.maxDrawdown.toFixed(1)}%` : '—'}
          changeType="profit"
          icon={AlertTriangle}
        />
        <StatCard
          label="Risk Level"
          value={riskStatus?.riskLevel ?? '—'}
          change={riskStatus?.allChecksPassed ? 'All checks pass' : `${riskStatus?.violations.length ?? 0} violations`}
          changeType={riskStatus?.riskLevel === 'LOW' ? "profit" : riskStatus?.riskLevel === 'HIGH' || riskStatus?.riskLevel === 'CRITICAL' ? "loss" : "neutral"}
          icon={Lock}
        />
      </div>

      {/* Position Size Calculator */}
      <div className="rounded-lg border border-border bg-card p-5">
        <h3 className="text-sm font-semibold text-foreground mb-3">Position Size Formula</h3>
        <div className="rounded-md bg-surface-2 px-4 py-3 font-mono text-sm text-primary">
          PositionSize = (AccountBalance × Risk%) / StopLossDistance
        </div>
      </div>

      {/* Exposure Breakdown */}
      {exposure && exposure.length > 0 && (
        <div className="rounded-lg border border-border bg-card overflow-hidden">
          <div className="border-b border-border px-5 py-3">
            <h3 className="text-sm font-semibold text-foreground">Exposure Breakdown</h3>
          </div>
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-5 py-2.5 text-left font-medium">Symbol</th>
                <th className="px-5 py-2.5 text-right font-medium">Exposure</th>
                <th className="px-5 py-2.5 text-right font-medium">% of Portfolio</th>
                <th className="px-5 py-2.5 text-right font-medium">Limit</th>
              </tr>
            </thead>
            <tbody>
              {exposure.map((e: ExposureBreakdown) => (
                <tr key={e.symbol} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                  <td className="px-5 py-3 font-mono font-semibold text-foreground">{e.symbol}</td>
                  <td className="px-5 py-3 text-right font-mono text-foreground">${e.exposure.toLocaleString()}</td>
                  <td className="px-5 py-3 text-right font-mono text-foreground">{e.exposurePercent.toFixed(1)}%</td>
                  <td className="px-5 py-3 text-right font-mono text-muted-foreground">{e.limit.toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Risk Rules by Level */}
      {isLoading ? (
        <div className="rounded-lg border border-border bg-card p-8 text-center text-sm text-muted-foreground">Loading risk configuration...</div>
      ) : (
        <div className="space-y-4">
          {Object.entries(groupedConfig).map(([level, items]) => (
            <div key={level} className="rounded-lg border border-border bg-card overflow-hidden">
              <div className="border-b border-border px-5 py-3">
                <h3 className="text-sm font-semibold text-foreground">{levelLabels[level] ?? level} Risk</h3>
              </div>
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-border text-muted-foreground">
                    <th className="px-5 py-2.5 text-left font-medium">Rule</th>
                    <th className="px-5 py-2.5 text-right font-medium">Value</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((r: RiskConfigItem) => (
                    <tr key={r.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                      <td className="px-5 py-3 text-foreground">{r.configKey.replace(/_/g, ' ')}</td>
                      <td className="px-5 py-3 text-right font-mono text-foreground">{r.configValue}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
