import { DollarSign, TrendingUp, BarChart3, Shield, Plus } from "lucide-react";
import { useState } from "react";
import { StatCard } from "@/components/ui/stat-card";
import { TradingChart } from "@/components/trading/TradingChart";
import { PositionsTable } from "@/components/trading/PositionsTable";
import { ActiveBots } from "@/components/trading/ActiveBots";
import { EquityCurve } from "@/components/trading/EquityCurve";
import { CreateBotDialog } from "@/components/trading/CreateBotDialog";
import { useAnalytics } from "@/hooks/api/useAnalytics";
import { useRiskStatus } from "@/hooks/api/useRisk";

const Dashboard = () => {
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const { data: perf } = useAnalytics();
  const { data: riskStatus } = useRiskStatus();

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Dashboard</h1>
        <p className="text-sm text-muted-foreground">Real-time trading overview</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="Portfolio Value"
          value={perf ? `$${perf.totalReturn.toLocaleString()}` : '—'}
          change={perf ? `${perf.totalReturnPercent >= 0 ? '+' : ''}${perf.totalReturnPercent.toFixed(1)}%` : '—'}
          changeType={perf && perf.totalReturnPercent >= 0 ? "profit" : "loss"}
          icon={DollarSign}
        />
        <StatCard
          label="Total Trades"
          value={perf ? String(perf.totalTrades) : '—'}
          change={perf ? `${perf.winRate.toFixed(1)}% win rate` : '—'}
          changeType="neutral"
          icon={TrendingUp}
        />
        <StatCard
          label="Win Rate"
          value={perf ? `${perf.winRate.toFixed(1)}%` : '—'}
          change={perf ? `Sharpe: ${perf.sharpeRatio.toFixed(2)}` : '—'}
          changeType="profit"
          icon={BarChart3}
        />
        <StatCard
          label="Risk Exposure"
          value={riskStatus ? `${riskStatus.totalExposure.toFixed(1)}%` : '—'}
          change={riskStatus ? (riskStatus.allChecksPassed ? 'Within limits' : `${riskStatus.violations.length} violations`) : '—'}
          changeType={riskStatus?.allChecksPassed ? "neutral" : "loss"}
          icon={Shield}
        />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <TradingChart />
        </div>
        <div className="space-y-4">
          <button
            onClick={() => setCreateBotOpen(true)}
            className="flex w-full items-center justify-center gap-2 rounded-md border border-dashed border-border bg-card px-4 py-3 text-sm font-medium text-muted-foreground hover:text-foreground hover:border-primary/40 transition-colors"
          >
            <Plus className="h-4 w-4" /> Create Bot
          </button>
          <ActiveBots />
        </div>
      </div>

      <EquityCurve />
      <PositionsTable />

      <CreateBotDialog open={createBotOpen} onOpenChange={setCreateBotOpen} />
    </div>
  );
};

export default Dashboard;
