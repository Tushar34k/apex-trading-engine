import { useState } from "react";
import { Settings2 } from "lucide-react";
import { useBots, useUpdateBotParams } from "@/hooks/api/useBots";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { TradingBot } from "@/types";

interface RiskParam {
  key: string;
  label: string;
  description: string;
  default: number;
  min: number;
  max: number;
  step: number;
  suffix: string;
}

const RISK_PARAMS: RiskParam[] = [
  { key: "riskPercentPerTrade", label: "Risk per Trade", description: "Max % of balance risked per trade", default: 1, min: 0.1, max: 5, step: 0.1, suffix: "%" },
  { key: "maxPositions", label: "Max Positions", description: "Max simultaneous open positions", default: 3, min: 1, max: 10, step: 1, suffix: "" },
  { key: "maxDailyLossPercent", label: "Daily Loss Limit", description: "Auto-stop if daily loss exceeds", default: 3, min: 0.5, max: 10, step: 0.5, suffix: "%" },
  { key: "maxConsecutiveLosses", label: "Consecutive Loss Limit", description: "Auto-pause after N consecutive losses", default: 3, min: 1, max: 10, step: 1, suffix: "" },
  { key: "minTradeScore", label: "Min Quality Score", description: "Reject trades scoring below", default: 70, min: 40, max: 90, step: 5, suffix: "/100" },
  { key: "stopLossPercent", label: "Stop Loss", description: "Default SL % (ATR-based overrides this)", default: 1.5, min: 0.3, max: 5, step: 0.1, suffix: "%" },
  { key: "takeProfitPercent", label: "Take Profit", description: "Default TP % (R:R-based overrides this)", default: 3, min: 0.5, max: 10, step: 0.5, suffix: "%" },
  { key: "trailingStopPercent", label: "Trailing Stop", description: "Lock profits with trailing stop", default: 1.5, min: 0.3, max: 5, step: 0.1, suffix: "%" },
  { key: "maxSpreadPercent", label: "Max Spread", description: "Reject entry if spread exceeds", default: 0.2, min: 0.05, max: 1, step: 0.05, suffix: "%" },
  { key: "atrSlMultiplier", label: "ATR SL Multiplier", description: "SL distance in ATR multiples", default: 1.5, min: 0.5, max: 3, step: 0.1, suffix: "×" },
  { key: "rrRatio", label: "Risk:Reward Ratio", description: "Minimum R:R for take profit", default: 2, min: 1, max: 5, step: 0.5, suffix: ":1" },
];

export function RiskSettingsPanel({ bot }: { bot?: TradingBot }) {
  const currentParams = bot?.strategyParams ? JSON.parse(bot.strategyParams) : {};
  const [params, setParams] = useState<Record<string, number>>(() => {
    const initial: Record<string, number> = {};
    RISK_PARAMS.forEach((p) => {
      initial[p.key] = currentParams[p.key] ?? p.default;
    });
    return initial;
  });

  const updateParams = useUpdateBotParams();

  const handleSave = () => {
    if (!bot) return;
    const merged = { ...currentParams, ...params };
    updateParams.mutate(
      { botId: bot.id, params: JSON.stringify(merged) },
      {
        onSuccess: () => toast.success("Risk settings saved"),
        onError: () => toast.error("Failed to save settings"),
      }
    );
  };

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="border-b border-border px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Settings2 className="h-4 w-4 text-primary" />
          <h3 className="text-sm font-semibold text-foreground">Risk Settings</h3>
        </div>
        {bot && (
          <button
            onClick={handleSave}
            disabled={updateParams.isPending}
            className="rounded-md bg-primary px-3 py-1 text-[10px] font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            {updateParams.isPending ? "Saving..." : "Save"}
          </button>
        )}
      </div>

      <div className="p-4 space-y-3 max-h-[500px] overflow-y-auto">
        {RISK_PARAMS.map((param) => (
          <div key={param.key} className="space-y-1">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium text-foreground">{param.label}</label>
              <span className="text-xs font-mono text-primary">
                {params[param.key]}{param.suffix}
              </span>
            </div>
            <input
              type="range"
              min={param.min}
              max={param.max}
              step={param.step}
              value={params[param.key]}
              onChange={(e) =>
                setParams((prev) => ({ ...prev, [param.key]: Number(e.target.value) }))
              }
              className="w-full h-1.5 rounded-full appearance-none bg-muted/30 accent-primary cursor-pointer"
            />
            <p className="text-[10px] text-muted-foreground">{param.description}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
