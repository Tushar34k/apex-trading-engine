import { useState, useEffect, useCallback } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  ShieldAlert, ShieldCheck, ShieldX, Ban, Octagon, Timer,
  TrendingDown, AlertTriangle, Activity, Zap,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { StatCard } from "@/components/ui/stat-card";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import client from "@/lib/api";
import { system } from "@/lib/api";
import { toast } from "sonner";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface SizingEntry {
  timestamp: string;
  botId?: string;
  symbol: string;
  balance?: number;
  riskPercent?: number;
  slDistancePercent?: number;
  rawRiskQty?: number;
  maxCapQty?: number;
  finalQty?: number;
  notionalValue?: number;
  impliedLeverage?: number;
  status: "PASSED" | "SIZE_CAPPED" | "REJECTED";
  reason?: string;
}

interface ApiHealth {
  status: string;
  exchanges: Record<string, { status: string; error: string; timestamp: number }>;
}

interface SystemMetrics {
  killSwitch: {
    active: boolean;
    reason: string | null;
    activatedAt: string | null;
  };
  exchangeHealth: {
    circuitBreakerOpen: boolean;
    recentErrors: number;
  };
  openPositions: number;
  runningBots: number;
  totalFailed: number;
  dailyDrawdownPercent?: number;
  consecutiveLosses?: number;
  cooldownEndsAt?: string | null;
}

/* ------------------------------------------------------------------ */
/*  Cooldown Timer Hook                                                */
/* ------------------------------------------------------------------ */

function useCooldownTimer(cooldownEndsAt: string | null | undefined) {
  const [remaining, setRemaining] = useState(0);

  const calculate = useCallback(() => {
    if (!cooldownEndsAt) return 0;
    const diff = new Date(cooldownEndsAt).getTime() - Date.now();
    return diff > 0 ? Math.ceil(diff / 1000) : 0;
  }, [cooldownEndsAt]);

  useEffect(() => {
    setRemaining(calculate());
    const id = setInterval(() => {
      const r = calculate();
      setRemaining(r);
      if (r <= 0) clearInterval(id);
    }, 1000);
    return () => clearInterval(id);
  }, [calculate]);

  const mins = Math.floor(remaining / 60);
  const secs = remaining % 60;
  return { remaining, display: `${mins}:${secs.toString().padStart(2, "0")}` };
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export default function RiskControl() {
  const queryClient = useQueryClient();

  /* — Data fetching — */
  const { data: sysMetrics } = useQuery<SystemMetrics>({
    queryKey: ["system-metrics"],
    queryFn: system.metrics,
    refetchInterval: 3000,
  });

  const { data: apiHealth } = useQuery<ApiHealth>({
    queryKey: ["risk-api-health"],
    queryFn: () => client.get("/risk-monitor/api-health").then((r) => r.data),
    refetchInterval: 5000,
  });

  const { data: sizingData } = useQuery<SizingEntry[]>({
    queryKey: ["risk-sizing-audit"],
    queryFn: () => client.get("/risk-monitor/sizing-audit?limit=50").then((r) => r.data),
    refetchInterval: 4000,
  });

  /* — Mutations — */
  const activateKillSwitch = useMutation({
    mutationFn: () => system.activateKillSwitch("EMERGENCY HALT — Manual activation from Risk Control Center"),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["system-metrics"] });
      toast.warning("🚨 EMERGENCY HALT — All trading stopped");
    },
  });

  const resetKillSwitch = useMutation({
    mutationFn: system.resetKillSwitch,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["system-metrics"] });
      toast.success("Kill switch reset — trading enabled");
    },
  });

  /* — Derived state — */
  const killSwitchActive = sysMetrics?.killSwitch?.active ?? false;
  const circuitBreakerOpen = sysMetrics?.exchangeHealth?.circuitBreakerOpen ?? false;
  const dailyDrawdown = sysMetrics?.dailyDrawdownPercent ?? 0;
  const consecutiveLosses = sysMetrics?.consecutiveLosses ?? 0;
  const cooldown = useCooldownTimer(sysMetrics?.cooldownEndsAt);

  const blockedExchanges = apiHealth?.exchanges
    ? Object.entries(apiHealth.exchanges).filter(([, v]) => v.status === "API_BLOCKED")
    : [];

  const isHealthy = !killSwitchActive && !circuitBreakerOpen && blockedExchanges.length === 0;

  /* — Sizing stats — */
  const totalEvals = sizingData?.length ?? 0;
  const cappedCount = sizingData?.filter((e) => e.status === "SIZE_CAPPED").length ?? 0;
  const rejectedCount = sizingData?.filter((e) => e.status === "REJECTED").length ?? 0;

  return (
    <div className="space-y-6 animate-slide-up">
      {/* ═══════════════════════════════════════════════════════════ */}
      {/*  HEADER                                                    */}
      {/* ═══════════════════════════════════════════════════════════ */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={cn(
            "flex h-10 w-10 items-center justify-center rounded-lg",
            isHealthy ? "bg-profit/10" : "bg-destructive/10"
          )}>
            {isHealthy ? (
              <ShieldCheck className="h-5 w-5 text-profit" />
            ) : (
              <ShieldAlert className="h-5 w-5 text-destructive animate-pulse" />
            )}
          </div>
          <div>
            <h1 className="text-xl font-bold text-foreground">Risk Control Center</h1>
            <p className="text-xs text-muted-foreground font-mono">
              CAPITAL PRESERVATION · INSTITUTIONAL GRADE
            </p>
          </div>
        </div>

        {/* Global status badge */}
        <Badge
          variant={isHealthy ? "outline" : "destructive"}
          className={cn(
            "text-xs font-mono px-3 py-1",
            isHealthy && "border-profit/50 text-profit"
          )}
        >
          {killSwitchActive
            ? "⛔ HALTED"
            : circuitBreakerOpen
            ? "⚠️ CIRCUIT OPEN"
            : blockedExchanges.length > 0
            ? "🔴 API BLOCKED"
            : "🟢 ALL SYSTEMS NOMINAL"}
        </Badge>
      </div>

      {/* ═══════════════════════════════════════════════════════════ */}
      {/*  TOP ROW: KILL SWITCH + API HEALTH                         */}
      {/* ═══════════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {/* Emergency Halt */}
        <div className={cn(
          "col-span-1 lg:col-span-2 rounded-lg border p-6 flex items-center justify-between",
          killSwitchActive
            ? "border-destructive/50 bg-destructive/5"
            : "border-border bg-card"
        )}>
          <div className="flex items-center gap-4">
            <Octagon className={cn(
              "h-10 w-10 shrink-0",
              killSwitchActive ? "text-destructive animate-pulse" : "text-muted-foreground"
            )} />
            <div>
              <h2 className="text-lg font-bold text-foreground">
                {killSwitchActive ? "TRADING HALTED" : "Global Kill Switch"}
              </h2>
              {killSwitchActive && sysMetrics?.killSwitch?.reason && (
                <p className="text-xs text-destructive font-mono mt-1">
                  Reason: {sysMetrics.killSwitch.reason}
                </p>
              )}
              <p className="text-xs text-muted-foreground mt-0.5">
                {killSwitchActive
                  ? "All bots stopped. Manual reset required to resume."
                  : "Immediately stops all bots and cancels pending orders."}
              </p>
            </div>
          </div>

          {killSwitchActive ? (
            <Button
              variant="outline"
              onClick={() => resetKillSwitch.mutate()}
              disabled={resetKillSwitch.isPending}
              className="border-profit/50 text-profit hover:bg-profit/10 font-mono text-xs px-6"
            >
              <ShieldCheck className="h-4 w-4 mr-1.5" />
              RESET & RESUME
            </Button>
          ) : (
            <Button
              variant="destructive"
              onClick={() => activateKillSwitch.mutate()}
              disabled={activateKillSwitch.isPending}
              className="bg-destructive hover:bg-destructive/90 text-destructive-foreground font-mono text-sm px-8 py-6 shadow-lg glow-loss"
            >
              <Octagon className="h-5 w-5 mr-2" />
              EMERGENCY HALT ALL TRADING
            </Button>
          )}
        </div>

        {/* API Health Panel */}
        <div className="rounded-lg border border-border bg-card p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
              Exchange API Health
            </h3>
            {apiHealth?.status === "HEALTHY" ? (
              <Badge variant="outline" className="border-profit/50 text-profit text-[10px] gap-1">
                <Zap className="h-2.5 w-2.5" /> CONNECTED
              </Badge>
            ) : (
              <Badge variant="destructive" className="text-[10px] gap-1">
                <Ban className="h-2.5 w-2.5" /> BLOCKED
              </Badge>
            )}
          </div>

          {blockedExchanges.length > 0 ? (
            <div className="space-y-2">
              {blockedExchanges.map(([exchange, info]) => (
                <div key={exchange} className="rounded-md border border-destructive/30 bg-destructive/5 p-3">
                  <div className="flex items-center gap-2">
                    <Ban className="h-3.5 w-3.5 text-destructive shrink-0" />
                    <span className="text-xs font-bold text-destructive font-mono">{exchange}</span>
                  </div>
                  <p className="text-[10px] text-muted-foreground mt-1 truncate font-mono">{info.error}</p>
                  <p className="text-[10px] text-muted-foreground mt-0.5">
                    Since {new Date(info.timestamp).toLocaleTimeString()}
                  </p>
                </div>
              ))}
            </div>
          ) : (
            <div className="flex items-center gap-2 rounded-md border border-profit/20 bg-profit/5 p-3">
              <ShieldCheck className="h-4 w-4 text-profit" />
              <span className="text-xs text-profit font-medium">All exchange APIs responding normally</span>
            </div>
          )}

          <div className="grid grid-cols-2 gap-2 pt-1">
            <div className="rounded-md bg-muted/30 p-2">
              <div className="text-[10px] text-muted-foreground uppercase">Open Positions</div>
              <div className="text-lg font-bold font-mono text-foreground">{sysMetrics?.openPositions ?? 0}</div>
            </div>
            <div className="rounded-md bg-muted/30 p-2">
              <div className="text-[10px] text-muted-foreground uppercase">Running Bots</div>
              <div className="text-lg font-bold font-mono text-foreground">{sysMetrics?.runningBots ?? 0}</div>
            </div>
          </div>
        </div>
      </div>

      {/* ═══════════════════════════════════════════════════════════ */}
      {/*  MIDDLE ROW: RISK METRICS                                  */}
      {/* ═══════════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {/* Daily Drawdown */}
        <div className={cn(
          "rounded-lg border p-4",
          dailyDrawdown >= 3
            ? "border-destructive/50 bg-destructive/5"
            : dailyDrawdown >= 2
            ? "border-warning/50 bg-warning/5"
            : "border-border bg-card"
        )}>
          <div className="flex items-center justify-between">
            <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
              Daily Drawdown
            </span>
            <TrendingDown className={cn(
              "h-4 w-4",
              dailyDrawdown >= 3 ? "text-destructive" : dailyDrawdown >= 2 ? "text-warning" : "text-muted-foreground"
            )} />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className={cn(
              "text-3xl font-bold font-mono",
              dailyDrawdown >= 3 ? "text-destructive" : dailyDrawdown >= 2 ? "text-warning" : "text-foreground"
            )}>
              {dailyDrawdown.toFixed(2)}%
            </span>
            <span className="text-xs text-muted-foreground">/ 3.00% max</span>
          </div>
          {/* Visual bar */}
          <div className="mt-2 h-1.5 rounded-full bg-muted/50 overflow-hidden">
            <div
              className={cn(
                "h-full rounded-full transition-all duration-500",
                dailyDrawdown >= 3 ? "bg-destructive" : dailyDrawdown >= 2 ? "bg-warning" : "bg-profit"
              )}
              style={{ width: `${Math.min((dailyDrawdown / 3) * 100, 100)}%` }}
            />
          </div>
        </div>

        {/* Consecutive Losses */}
        <div className={cn(
          "rounded-lg border p-4",
          consecutiveLosses >= 3
            ? "border-destructive/50 bg-destructive/5"
            : "border-border bg-card"
        )}>
          <div className="flex items-center justify-between">
            <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
              Consecutive Losses
            </span>
            <AlertTriangle className={cn(
              "h-4 w-4",
              consecutiveLosses >= 3 ? "text-destructive" : "text-muted-foreground"
            )} />
          </div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className={cn(
              "text-3xl font-bold font-mono",
              consecutiveLosses >= 3 ? "text-destructive" : consecutiveLosses >= 2 ? "text-warning" : "text-foreground"
            )}>
              {consecutiveLosses}
            </span>
            <span className="text-xs text-muted-foreground">/ 3 max</span>
          </div>
          <div className="mt-2 flex gap-1.5">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className={cn(
                  "h-2 flex-1 rounded-full",
                  i < consecutiveLosses ? "bg-destructive" : "bg-muted/50"
                )}
              />
            ))}
          </div>
        </div>

        {/* Post-Loss Cooldown */}
        <div className={cn(
          "rounded-lg border p-4",
          cooldown.remaining > 0
            ? "border-warning/50 bg-warning/5"
            : "border-border bg-card"
        )}>
          <div className="flex items-center justify-between">
            <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
              Post-Loss Cooldown
            </span>
            <Timer className={cn(
              "h-4 w-4",
              cooldown.remaining > 0 ? "text-warning animate-pulse" : "text-muted-foreground"
            )} />
          </div>
          <div className="mt-2">
            {cooldown.remaining > 0 ? (
              <>
                <span className="text-3xl font-bold font-mono text-warning">
                  {cooldown.display}
                </span>
                <p className="text-[10px] text-muted-foreground mt-1">Trading blocked until cooldown expires</p>
              </>
            ) : (
              <>
                <span className="text-3xl font-bold font-mono text-profit">CLEAR</span>
                <p className="text-[10px] text-muted-foreground mt-1">No active cooldown</p>
              </>
            )}
          </div>
        </div>

        {/* Sizing Stats */}
        <StatCard
          label="Trades Capped"
          value={cappedCount.toString()}
          change={`of ${totalEvals} evaluations`}
          changeType={cappedCount > 0 ? "loss" : "neutral"}
          icon={ShieldX}
        />
        <StatCard
          label="Trades Rejected"
          value={rejectedCount.toString()}
          change={`${totalEvals > 0 ? ((rejectedCount / totalEvals) * 100).toFixed(0) : 0}% rejection rate`}
          changeType={rejectedCount > 0 ? "loss" : "neutral"}
          icon={Ban}
        />
      </div>

      {/* ═══════════════════════════════════════════════════════════ */}
      {/*  BOTTOM ROW: FULL-WIDTH SIZING AUDIT LOG                   */}
      {/* ═══════════════════════════════════════════════════════════ */}
      <div className="rounded-lg border border-border bg-card">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-primary" />
            <h3 className="text-sm font-semibold text-foreground">Position Sizing Audit Trail</h3>
            <Badge variant="outline" className="text-[10px] font-mono ml-2">
              Last {totalEvals} evaluations
            </Badge>
          </div>
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5">
              <div className="h-2 w-2 rounded-full bg-profit" />
              <span className="text-[10px] text-muted-foreground">Passed</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="h-2 w-2 rounded-full bg-warning" />
              <span className="text-[10px] text-muted-foreground">Capped</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="h-2 w-2 rounded-full bg-destructive" />
              <span className="text-[10px] text-muted-foreground">Rejected</span>
            </div>
          </div>
        </div>

        <div className="overflow-auto max-h-[480px]">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="text-[10px] font-mono">TIME</TableHead>
                <TableHead className="text-[10px] font-mono">SYMBOL</TableHead>
                <TableHead className="text-[10px] font-mono text-right">BALANCE</TableHead>
                <TableHead className="text-[10px] font-mono text-right">SL DIST %</TableHead>
                <TableHead className="text-[10px] font-mono text-right">RISK QTY</TableHead>
                <TableHead className="text-[10px] font-mono text-right">MAX CAP QTY</TableHead>
                <TableHead className="text-[10px] font-mono text-right">FINAL QTY</TableHead>
                <TableHead className="text-[10px] font-mono text-right">NOTIONAL $</TableHead>
                <TableHead className="text-[10px] font-mono text-right">LEVERAGE</TableHead>
                <TableHead className="text-[10px] font-mono">STATUS</TableHead>
                <TableHead className="text-[10px] font-mono">REASON</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(!sizingData || sizingData.length === 0) && (
                <TableRow>
                  <TableCell colSpan={11} className="text-center text-muted-foreground text-xs py-12">
                    No sizing evaluations recorded yet — awaiting trade signals
                  </TableCell>
                </TableRow>
              )}
              {sizingData?.map((entry, i) => {
                const isCapped = entry.status === "SIZE_CAPPED";
                const isRejected = entry.status === "REJECTED";
                const time = entry.timestamp
                  ? new Date(entry.timestamp).toLocaleTimeString()
                  : "—";
                const leverageHigh = (entry.impliedLeverage ?? 0) > 3;

                return (
                  <TableRow key={i} className={cn(
                    isRejected && "bg-destructive/5",
                    isCapped && "bg-warning/5"
                  )}>
                    <TableCell className="text-xs font-mono text-muted-foreground">{time}</TableCell>
                    <TableCell className="text-xs font-mono font-semibold text-foreground">{entry.symbol}</TableCell>
                    <TableCell className="text-xs font-mono text-right text-muted-foreground">
                      {entry.balance != null ? `$${entry.balance.toLocaleString(undefined, { maximumFractionDigits: 0 })}` : "—"}
                    </TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right",
                      (entry.slDistancePercent ?? 0) < 0.3 ? "text-destructive font-bold" : "text-muted-foreground"
                    )}>
                      {entry.slDistancePercent != null ? `${entry.slDistancePercent.toFixed(3)}%` : "—"}
                    </TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right",
                      isCapped && "line-through text-destructive"
                    )}>
                      {entry.rawRiskQty?.toFixed(6) ?? "—"}
                    </TableCell>
                    <TableCell className="text-xs font-mono text-right text-muted-foreground">
                      {entry.maxCapQty?.toFixed(6) ?? "—"}
                    </TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right font-bold",
                      isCapped ? "text-warning" : isRejected ? "text-destructive" : "text-profit"
                    )}>
                      {isRejected ? "BLOCKED" : entry.finalQty?.toFixed(6) ?? "—"}
                    </TableCell>
                    <TableCell className="text-xs font-mono text-right text-foreground">
                      {entry.notionalValue != null ? `$${entry.notionalValue.toLocaleString(undefined, { maximumFractionDigits: 2 })}` : "—"}
                    </TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right font-semibold",
                      leverageHigh ? "text-warning" : "text-muted-foreground"
                    )}>
                      {entry.impliedLeverage != null ? `${entry.impliedLeverage.toFixed(2)}x` : "—"}
                    </TableCell>
                    <TableCell>
                      {entry.status === "PASSED" && (
                        <Badge variant="outline" className="border-profit/50 text-profit text-[10px] font-mono">
                          PASSED
                        </Badge>
                      )}
                      {entry.status === "SIZE_CAPPED" && (
                        <Badge className="bg-warning/15 text-warning border border-warning/30 text-[10px] font-mono gap-1">
                          <AlertTriangle className="h-2.5 w-2.5" /> CAPPED
                        </Badge>
                      )}
                      {entry.status === "REJECTED" && (
                        <Badge variant="destructive" className="text-[10px] font-mono">REJECTED</Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-[10px] font-mono text-muted-foreground max-w-[200px] truncate">
                      {entry.reason ?? "—"}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}
