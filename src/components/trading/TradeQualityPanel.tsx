import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Gauge, TrendingUp, Volume2, Activity, BarChart3, Zap } from "lucide-react";
import { cn } from "@/lib/utils";
import client from "@/lib/api";

interface QualityScore {
  total: number;
  minRequired: number;
  passed: boolean;
  trend: number;
  volume: number;
  rsi: number;
  volatility: number;
  pullback: number;
  candle: number;
  breakdown: string;
}

export function TradeQualityPanel() {
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [score, setScore] = useState<QualityScore | null>(null);

  const checkQuality = useMutation({
    mutationFn: async () => {
      const { data } = await client.post<QualityScore>("/trade-quality/score", {
        symbol,
        side,
        timeframe: "5m",
        exchange: "BINANCE",
      });
      return data;
    },
    onSuccess: (data) => setScore(data),
  });

  const scoreColor = (val: number, max: number) => {
    const pct = val / max;
    if (pct >= 0.7) return "text-profit";
    if (pct >= 0.4) return "text-warning";
    return "text-destructive";
  };

  const totalColor = (val: number) => {
    if (val >= 70) return "text-profit";
    if (val >= 50) return "text-warning";
    return "text-destructive";
  };

  const barWidth = (val: number, max: number) => `${Math.min(100, (val / max) * 100)}%`;

  const categories = score
    ? [
        { label: "Trend", value: score.trend, max: 25, icon: TrendingUp },
        { label: "Volume", value: score.volume, max: 20, icon: Volume2 },
        { label: "RSI", value: score.rsi, max: 15, icon: Activity },
        { label: "Volatility", value: score.volatility, max: 15, icon: BarChart3 },
        { label: "Pullback", value: score.pullback, max: 15, icon: Zap },
        { label: "Candle", value: score.candle, max: 10, icon: Gauge },
      ]
    : [];

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="border-b border-border px-4 py-3 flex items-center gap-2">
        <Gauge className="h-4 w-4 text-primary" />
        <h3 className="text-sm font-semibold text-foreground">Trade Quality Score</h3>
      </div>

      <div className="p-4 space-y-4">
        {/* Controls */}
        <div className="flex items-center gap-2">
          <input
            value={symbol}
            onChange={(e) => setSymbol(e.target.value.toUpperCase())}
            className="flex-1 rounded-md border border-border bg-background px-3 py-1.5 text-xs font-mono text-foreground"
            placeholder="BTCUSDT"
          />
          <div className="flex rounded-md border border-border overflow-hidden">
            <button
              onClick={() => setSide("BUY")}
              className={cn(
                "px-3 py-1.5 text-xs font-medium transition-colors",
                side === "BUY" ? "bg-profit/20 text-profit" : "text-muted-foreground hover:bg-muted/30"
              )}
            >
              BUY
            </button>
            <button
              onClick={() => setSide("SELL")}
              className={cn(
                "px-3 py-1.5 text-xs font-medium transition-colors",
                side === "SELL" ? "bg-loss/20 text-loss" : "text-muted-foreground hover:bg-muted/30"
              )}
            >
              SELL
            </button>
          </div>
          <button
            onClick={() => checkQuality.mutate()}
            disabled={checkQuality.isPending}
            className="rounded-md bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {checkQuality.isPending ? "..." : "Score"}
          </button>
        </div>

        {/* Score Display */}
        {score && (
          <>
            {/* Total score gauge */}
            <div className="flex items-center justify-between">
              <div>
                <span className={cn("text-3xl font-bold font-mono", totalColor(score.total))}>
                  {score.total}
                </span>
                <span className="text-sm text-muted-foreground">/100</span>
              </div>
              <div className={cn(
                "rounded-full px-3 py-1 text-xs font-bold",
                score.passed
                  ? "bg-profit/20 text-profit"
                  : "bg-destructive/20 text-destructive"
              )}>
                {score.passed ? "PASS" : "REJECT"}
              </div>
            </div>

            {/* Progress bar */}
            <div className="h-2 rounded-full bg-muted/30 overflow-hidden">
              <div
                className={cn(
                  "h-full rounded-full transition-all duration-500",
                  score.total >= 70 ? "bg-profit" : score.total >= 50 ? "bg-warning" : "bg-destructive"
                )}
                style={{ width: `${score.total}%` }}
              />
            </div>

            {/* Category breakdown */}
            <div className="space-y-2">
              {categories.map((cat) => (
                <div key={cat.label} className="flex items-center gap-2">
                  <cat.icon className="h-3 w-3 text-muted-foreground shrink-0" />
                  <span className="text-[10px] text-muted-foreground w-16">{cat.label}</span>
                  <div className="flex-1 h-1.5 rounded-full bg-muted/30 overflow-hidden">
                    <div
                      className={cn("h-full rounded-full transition-all duration-300",
                        cat.value / cat.max >= 0.7 ? "bg-profit" :
                        cat.value / cat.max >= 0.4 ? "bg-warning" : "bg-destructive"
                      )}
                      style={{ width: barWidth(cat.value, cat.max) }}
                    />
                  </div>
                  <span className={cn("text-[10px] font-mono w-8 text-right", scoreColor(cat.value, cat.max))}>
                    {cat.value}/{cat.max}
                  </span>
                </div>
              ))}
            </div>
          </>
        )}

        {!score && !checkQuality.isPending && (
          <div className="py-6 text-center text-xs text-muted-foreground">
            Check trade quality before entering a position
          </div>
        )}
      </div>
    </div>
  );
}
