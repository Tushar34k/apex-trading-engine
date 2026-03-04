import { Users, Power, AlertTriangle, Activity, Wifi, Server, Shield } from "lucide-react";
import { StatCard } from "@/components/ui/stat-card";
import { cn } from "@/lib/utils";
import { useAdminUsers, useSystemHealth, useKillSwitch, useResumeTrading } from "@/hooks/api/useAdmin";
import type { AdminUser, SystemHealth } from "@/types";

const healthIcons: Record<string, typeof Wifi> = {
  websocket: Wifi,
  exchangeApi: Server,
  database: Activity,
  redis: Activity,
};

export default function Admin() {
  const { data: usersList } = useAdminUsers();
  const { data: health } = useSystemHealth();
  const killSwitch = useKillSwitch();
  const resumeTrading = useResumeTrading();

  const users = usersList ?? [];
  const activeUsers = users.filter((u: AdminUser) => u.isActive).length;
  const totalBots = users.reduce((sum, u: AdminUser) => sum + u.activeBots, 0);

  const isKilled = killSwitch.isSuccess && !resumeTrading.isSuccess;

  return (
    <div className="space-y-6 animate-slide-up">
      <div>
        <h1 className="text-xl font-bold text-foreground">Admin & Monitoring</h1>
        <p className="text-sm text-muted-foreground">System-wide monitoring and user management</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Active Users" value={String(activeUsers)} change={`${users.length} total`} changeType="neutral" icon={Users} />
        <StatCard label="Active Bots" value={String(totalBots)} change={`Across ${activeUsers} users`} changeType="neutral" icon={Activity} />
        <StatCard label="System Status" value={health ? 'Online' : '—'} change={health ? `${health.activeConnections} connections` : '—'} changeType="neutral" icon={Shield} />
        <StatCard label="Uptime" value={health ? `${Math.floor(health.uptime / 3600)}h` : '—'} change="Since last restart" changeType="profit" icon={AlertTriangle} />
      </div>

      {/* Kill Switch */}
      <div className={cn("rounded-lg border p-5 transition-colors", isKilled ? "border-loss bg-loss/5" : "border-border bg-card")}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Power className={cn("h-6 w-6", isKilled ? "text-loss" : "text-muted-foreground")} />
            <div>
              <h3 className="text-sm font-semibold text-foreground">Emergency Kill Switch</h3>
              <p className="text-xs text-muted-foreground">Immediately stops all bots and cancels pending orders</p>
            </div>
          </div>
          <button
            onClick={() => isKilled ? resumeTrading.mutate() : killSwitch.mutate()}
            disabled={killSwitch.isPending || resumeTrading.isPending}
            className={cn("rounded-md px-4 py-2 text-sm font-bold transition-colors disabled:opacity-50",
              isKilled ? "bg-profit text-primary-foreground hover:bg-profit/90" : "bg-loss text-destructive-foreground hover:bg-loss/90"
            )}
          >
            {isKilled ? "RESUME TRADING" : "KILL ALL TRADING"}
          </button>
        </div>
      </div>

      {/* System Health */}
      {health && (
        <div className="rounded-lg border border-border bg-card overflow-hidden">
          <div className="border-b border-border px-5 py-3">
            <h3 className="text-sm font-semibold text-foreground">System Health</h3>
          </div>
          <div className="grid grid-cols-4 divide-x divide-border/50">
            {(['websocket', 'exchangeApi', 'database', 'redis'] as const).map((key) => {
              const status = health[key];
              const Icon = healthIcons[key];
              return (
                <div key={key} className="flex items-center gap-3 px-5 py-4">
                  <Icon className={cn("h-4 w-4", status === 'online' ? 'text-profit' : status === 'degraded' ? 'text-warning' : 'text-loss')} />
                  <div>
                    <div className="text-sm text-foreground capitalize">{key.replace(/([A-Z])/g, ' $1').trim()}</div>
                    <div className={cn("text-[10px] font-semibold uppercase", status === 'online' ? 'text-profit' : status === 'degraded' ? 'text-warning' : 'text-loss')}>{status}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Users */}
      <div className="rounded-lg border border-border bg-card overflow-hidden">
        <div className="border-b border-border px-5 py-3">
          <h3 className="text-sm font-semibold text-foreground">User Management</h3>
        </div>
        {users.length === 0 ? (
          <div className="p-8 text-center text-sm text-muted-foreground">No users found</div>
        ) : (
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="px-5 py-2.5 text-left font-medium">Email</th>
                <th className="px-5 py-2.5 text-left font-medium">Roles</th>
                <th className="px-5 py-2.5 text-center font-medium">Active Bots</th>
                <th className="px-5 py-2.5 text-left font-medium">Last Login</th>
                <th className="px-5 py-2.5 text-right font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u: AdminUser) => (
                <tr key={u.id} className="border-b border-border/50 hover:bg-surface-2 transition-colors">
                  <td className="px-5 py-3 text-foreground">{u.email}</td>
                  <td className="px-5 py-3">
                    {u.roles.map((role) => (
                      <span key={role} className={cn("rounded px-1.5 py-0.5 text-[10px] font-bold mr-1",
                        role === "ADMIN" ? "bg-primary/10 text-primary" : "bg-surface-3 text-muted-foreground"
                      )}>{role}</span>
                    ))}
                  </td>
                  <td className="px-5 py-3 text-center font-mono text-foreground">{u.activeBots}</td>
                  <td className="px-5 py-3 text-muted-foreground">{u.lastLogin ? new Date(u.lastLogin).toLocaleString() : 'Never'}</td>
                  <td className="px-5 py-3 text-right">
                    <span className={cn("rounded px-2 py-0.5 text-[10px] font-bold", u.isActive ? "bg-profit/10 text-profit" : "bg-loss/10 text-loss")}>
                      {u.isActive ? 'ACTIVE' : 'DISABLED'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
