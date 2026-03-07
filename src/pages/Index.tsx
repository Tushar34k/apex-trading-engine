import { Plus, Wallet, Activity } from "lucide-react";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { TradingChart } from "@/components/trading/TradingChart";
import { PositionsTable } from "@/components/trading/PositionsTable";
import { ActiveBots } from "@/components/trading/ActiveBots";
import { TradeHistory } from "@/components/trading/TradeHistory";
import { CreateBotDialog } from "@/components/trading/CreateBotDialog";
import { NotificationPanel } from "@/components/trading/NotificationPanel";
import { useBots } from "@/hooks/api/useBots";
import { useAccountBalance } from "@/hooks/api/useAccountBalance";
import { execution } from "@/lib/api";
import { cn } from "@/lib/utils";

const Dashboard = () => {
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const { data: botsList } = useBots();
  const { data: balance } = useAccountBalance();
  const { data: execMetrics } = useQuery({
    queryKey: ['execution-metrics'],
    queryFn: execution.metrics,
    refetchInterval: 5000,
  });

  const runningBots = botsList?.filter((b) => b.status === 'RUNNING') ?? [];
  const totalPnl = botsList?.reduce((sum, b) => sum + (b.pnl ?? 0), 0) ?? 0;
  const totalTrades = botsList?.reduce((sum, b) => sum + (b.totalTrades ?? 0), 0) ?? 0;

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">Dashboard</h1>
          <p className="text-sm text-muted-foreground">Trading Overview</p>
        </div>
        <button
          onClick={() => setCreateBotOpen(true)}
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          <Plus className="h-4 w-4" /> Create Bot
        </button>
      </div>

      {/* Stats row */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
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
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <TradingChart />
        </div>
        <div className="space-y-6">
          <ActiveBots />
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
