import { DollarSign, TrendingUp, BarChart3, Shield } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { TradingChart } from "@/components/trading/TradingChart";
import { PositionsTable } from "@/components/trading/PositionsTable";
import { ActiveBots } from "@/components/trading/ActiveBots";
import { EquityCurve } from "@/components/trading/EquityCurve";

const Dashboard = () => {
  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Dashboard</h1>
        <p className="text-sm text-muted-foreground">Real-time trading overview</p>
      </div>

      {/* KPIs */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Portfolio Value" value="$12,845.90" change="+$1,845.90 (16.8%)" changeType="profit" icon={DollarSign} />
        <StatCard label="Today's P&L" value="+$64.76" change="+0.51%" changeType="profit" icon={TrendingUp} />
        <StatCard label="Win Rate" value="62.4%" change="+2.1% vs last month" changeType="profit" icon={BarChart3} />
        <StatCard label="Risk Exposure" value="34.2%" change="Within limits" changeType="neutral" icon={Shield} />
      </div>

      {/* Chart + Bots */}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <TradingChart />
        </div>
        <div>
          <ActiveBots />
        </div>
      </div>

      {/* Equity + Positions */}
      <EquityCurve />
      <PositionsTable />
    </div>
  );
};

export default Dashboard;
