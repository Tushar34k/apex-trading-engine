import { Settings, Users, Power, AlertTriangle, Activity, Wifi, Server, Shield } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { cn } from "@/lib/utils";
import { useState } from "react";

const users = [
  { id: 1, email: "john@example.com", role: "ADMIN", status: "active", bots: 3, lastLogin: "2 min ago" },
  { id: 2, email: "jane@example.com", role: "USER", status: "active", bots: 1, lastLogin: "1 hour ago" },
  { id: 3, email: "bob@example.com", role: "USER", status: "disabled", bots: 0, lastLogin: "3 days ago" },
];

const systemHealth = [
  { name: "WebSocket", status: "online", icon: Wifi },
  { name: "Binance API", status: "online", icon: Server },
  { name: "Database", status: "online", icon: Activity },
  { name: "Redis Cache", status: "online", icon: Activity },
];

export default function Admin() {
  const [killSwitch, setKillSwitch] = useState(false);

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Admin & Monitoring</h1>
        <p className="text-sm text-muted-foreground">System-wide monitoring and user management</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Active Users" value="2" change="1 admin, 1 user" changeType="neutral" icon={Users} />
        <StatCard label="Active Bots" value="4" change="Across 2 users" changeType="neutral" icon={Activity} />
        <StatCard label="System Exposure" value="$4,320" change="34.2% of AUM" changeType="neutral" icon={Shield} />
        <StatCard label="Order Failures" value="0" change="Last 24h" changeType="profit" icon={AlertTriangle} />
      </div>

      {/* Kill Switch */}
      <div className={cn("rounded-lg border p-5 transition-colors", killSwitch ? "border-loss bg-loss/5" : "border-border bg-card")}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Power className={cn("h-6 w-6", killSwitch ? "text-loss" : "text-muted-foreground")} />
            <div>
              <h3 className="text-sm font-semibold text-foreground">Emergency Kill Switch</h3>
              <p className="text-xs text-muted-foreground">Immediately stops all bots and cancels pending orders</p>
            </div>
          </div>
          <button
            onClick={() => setKillSwitch(!killSwitch)}
            className={cn("rounded-md px-4 py-2 text-sm font-bold transition-colors", killSwitch ? "bg-profit text-primary-foreground hover:bg-profit/90" : "bg-loss text-destructive-foreground hover:bg-loss/90")}
          >
            {killSwitch ? "RESUME TRADING" : "KILL ALL TRADING"}
          </button>
        </div>
      </div>

      {/* System Health */}
      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-5 py-3">
          <h3 className="text-sm font-semibold text-foreground">System Health</h3>
        </div>
        <div className="grid grid-cols-4 divide-x divide-border/50">
          {systemHealth.map((s) => (
            <div key={s.name} className="flex items-center gap-3 px-5 py-4">
              <s.icon className="h-4 w-4 text-profit" />
              <div>
                <div className="text-sm text-foreground">{s.name}</div>
                <div className="text-[10px] text-profit font-semibold uppercase">{s.status}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Users */}
      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-5 py-3">
          <h3 className="text-sm font-semibold text-foreground">User Management</h3>
        </div>
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-border text-muted-foreground">
              <th className="px-5 py-2.5 text-left font-medium">Email</th>
              <th className="px-5 py-2.5 text-left font-medium">Role</th>
              <th className="px-5 py-2.5 text-center font-medium">Active Bots</th>
              <th className="px-5 py-2.5 text-left font-medium">Last Login</th>
              <th className="px-5 py-2.5 text-right font-medium">Status</th>
              <th className="px-5 py-2.5 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                <td className="px-5 py-3 text-foreground">{u.email}</td>
                <td className="px-5 py-3">
                  <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold", u.role === "ADMIN" ? "bg-primary/10 text-primary" : "bg-surface-3 text-muted-foreground")}>{u.role}</span>
                </td>
                <td className="px-5 py-3 text-center font-mono text-foreground">{u.bots}</td>
                <td className="px-5 py-3 text-muted-foreground">{u.lastLogin}</td>
                <td className="px-5 py-3 text-right">
                  <span className={cn("rounded px-2 py-0.5 text-[10px] font-bold", u.status === "active" ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>{u.status.toUpperCase()}</span>
                </td>
                <td className="px-5 py-3 text-right">
                  <button className="text-muted-foreground hover:text-foreground transition-colors text-xs">Manage</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
