import { Plus, Wallet, Activity, Shield, ShieldAlert, ShieldCheck, AlertTriangle } from "lucide-react";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { TradingChart } from "@/components/trading/TradingChart";
import { PositionsTable } from "@/components/trading/PositionsTable";
import { ActiveBots } from "@/components/trading/ActiveBots";
import { TradeHistory } from "@/components/trading/TradeHistory";
import { CreateBotDialog } from "@/components/trading/CreateBotDialog";
import { NotificationPanel } from "@/components/trading/NotificationPanel";
import { TradeQualityPanel } from "@/components/trading/TradeQualityPanel";
import { AIValidationPanel } from "@/components/trading/AIValidationPanel";
import { RiskSizingMonitor } from "@/components/trading/RiskSizingMonitor";
import { useBots } from "@/hooks/api/useBots";
import { useAccountBalance } from "@/hooks/api/useAccountBalance";
import { system } from "@/lib/api";
import { cn } from "@/lib/utils";
import { toast } from "sonner";

const Dashboard = () => {
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const queryClient = useQueryClient();
  const { data: botsList } = useBots();
  const { data: balance } = useAccountBalance();
  const { data: sysMetrics } = useQuery({
    queryKey: ['system-metrics'],
    queryFn: system.metrics,
    refetchInterval: 5000,
  });

  const resetKillSwitch = useMutation({
    mutationFn: system.resetKillSwitch,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system-metrics'] });
      toast.success("Kill switch reset — trading enabled");
    },
  });

  const activateKillSwitch = useMutation({
    mutationFn: () => system.activateKillSwitch("Manual activation"),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system-metrics'] });
      toast.warning("Kill switch activated — all bots stopped");
    },
  });

  const runningBots = botsList?.filter((b) => b.status === 'RUNNING') ?? [];
  const totalPnl = botsList?.reduce((sum, b) => sum + (b.pnl ?? 0), 0) ?? 0;
  const totalTrades = botsList?.reduce((sum, b) => sum + (b.totalTrades ?? 0), 0) ?? 0;

  const killSwitchActive = sysMetrics?.killSwitch?.active ?? false;
  const circuitBreakerOpen = sysMetrics?.exchangeHealth?.circuitBreakerOpen ?? false;
  const queueUsage = sysMetrics?.queue?.usagePercent ?? 0;

  return (
    <div className="space-y-6 animate-slide-up">
      {/* Kill Switch Alert Banner */}
      {killSwitchActive && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <ShieldAlert className="h-5 w-5 text-destructive" />
            <div>
              <p className="text-sm font-semibold text-destructive">Kill Switch Active — All Trading Halted</p>
              <p className="text-xs text-muted-foreground">{sysMetrics?.killSwitch?.reason ?? 'Unknown reason'}</p>
            </div>
          </div>
          <button
            onClick={() => resetKillSwitch.mutate()}
            disabled={resetKillSwitch.isPending}
            className="rounded-md bg-destructive px-4 py-1.5 text-xs font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors"
          >
            Reset Kill Switch
          </button>
        </div>
      )}

      {/* Circuit Breaker Warning */}
      {circuitBreakerOpen && !killSwitchActive && (
        <div className="rounded-lg border border-warning/50 bg-warning/10 p-3 flex items-center gap-3">
          <AlertTriangle className="h-4 w-4 text-warning" />
          <p className="text-sm text-warning">Circuit breaker open — trading paused for 60s due to exchange errors</p>
        </div>
      )}

      {/* Queue Capacity Warning */}
      {queueUsage >= 80 && (
        <div className="rounded-lg border border-warning/50 bg-warning/10 p-3 flex items-center gap-3">
          <AlertTriangle className="h-4 w-4 text-warning" />
          <p className="text-sm text-warning">Execution queue at {queueUsage.toFixed(0)}% capacity</p>
        </div>
      )}

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">Dashboard</h1>
          <p className="text-sm text-muted-foreground">Trading Overview</p>
        </div>
        <div className="flex items-center gap-2">
          {!killSwitchActive && (
            <button
              onClick={() => activateKillSwitch.mutate()}
              className="flex items-center gap-2 rounded-md border border-destructive/30 px-3 py-2 text-xs font-medium text-destructive hover:bg-destructive/10 transition-colors"
            >
              <Shield className="h-3.5 w-3.5" /> Emergency Stop
            </button>
          )}
          <button
            onClick={() => setCreateBotOpen(true)}
            className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="h-4 w-4" /> Create Bot
          </button>
        </div>
      </div>

      {/* Stats row */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-6">
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-xs text-muted-foreground uppercase">Running Bots</div>
          <div className="mt-1 text-2xl font-bold text-foreground">{runningBots.length}</div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-xs text-muted-foreground uppercase">Total Trades</div>
          <div className="mt-1 text-2xl font-bold text-foreground">{totalTrades}</div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="text-xs text-muted-foreground uppercase">Total P&L</div>
          <div className={cn("mt-1 text-2xl font-bold font-mono", totalPnl >= 0 ? 'text-profit' : 'text-loss')}>
            {totalPnl >= 0 ? '+' : ''}${totalPnl.toFixed(2)}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground uppercase">
            <Wallet className="h-3 w-3" /> USDT Balance
          </div>
          <div className="mt-1 text-2xl font-bold font-mono text-foreground">
            ${balance?.available?.toLocaleString(undefined, { minimumFractionDigits: 2 }) ?? '—'}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground uppercase">
            <Activity className="h-3 w-3" /> Exec Queue
          </div>
          <div className="mt-1 text-lg font-bold font-mono text-foreground">
            {sysMetrics?.queue?.size ?? 0} / {sysMetrics?.queue?.capacity ?? 1000}
          </div>
          <div className="mt-0.5 text-xs text-muted-foreground">
            {sysMetrics?.totalExecuted ?? 0} ok · {sysMetrics?.totalFailed ?? 0} fail · {sysMetrics?.queue?.pendingBots ?? 0} bots pending
          </div>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground uppercase">
            {killSwitchActive ? (
              <ShieldAlert className="h-3 w-3 text-destructive" />
            ) : circuitBreakerOpen ? (
              <AlertTriangle className="h-3 w-3 text-warning" />
            ) : (
              <ShieldCheck className="h-3 w-3 text-profit" />
            )}
            System Health
          </div>
          <div className={cn("mt-1 text-lg font-bold",
            killSwitchActive ? 'text-destructive' :
            circuitBreakerOpen ? 'text-warning' : 'text-profit'
          )}>
            {killSwitchActive ? 'HALTED' : circuitBreakerOpen ? 'PAUSED' : 'HEALTHY'}
          </div>
          <div className="mt-0.5 text-xs text-muted-foreground">
            {sysMetrics?.exchangeHealth?.recentErrors ?? 0} errors/min · {sysMetrics?.openPositions ?? 0} positions
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <TradingChart />
        </div>
        <div className="space-y-6">
          <ActiveBots />
          <TradeQualityPanel />
          <AIValidationPanel />
          <NotificationPanel />
        </div>
      </div>

      <PositionsTable />
      <TradeHistory />

      <CreateBotDialog open={createBotOpen} onOpenChange={setCreateBotOpen} />
    </div>
  );
};

export default Dashboard;
