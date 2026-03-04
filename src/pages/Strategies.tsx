import { Bot, Play, Pause, Settings } from "lucide-react";
import { cn } from "@/lib/utils";
import { useStrategies } from "@/hooks/api/useStrategies";
import type { Strategy } from "@/types";

const strategyIcons: Record<string, typeof Bot> = {};

export default function Strategies() {
  const { data: strategiesList, isLoading } = useStrategies();

  if (isLoading) {
    return (
      <div className="space-y-6 animate-slide-up">
        <div>
          <h1 className="text-xl font-bold text-foreground">Strategy Framework</h1>
          <p className="text-sm text-muted-foreground">Loading strategies...</p>
        </div>
      </div>
    );
  }

  const strategies = strategiesList ?? [];

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">Strategy Framework</h1>
          <p className="text-sm text-muted-foreground">Manage and configure trading strategies</p>
        </div>
        <button className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors">
          <Bot className="h-4 w-4" /> New Strategy
        </button>
      </div>

      {strategies.length === 0 ? (
        <div className="rounded-lg border border-border bg-card p-8 text-center text-sm text-muted-foreground">
          No strategies configured
        </div>
      ) : (
        <div className="space-y-4">
          {strategies.map((s: Strategy) => (
            <div key={s.id} className="rounded-lg border border-border bg-card overflow-hidden">
              <div className="flex items-start justify-between p-5">
                <div className="flex items-start gap-4">
                  <div className={cn("flex h-10 w-10 items-center justify-center rounded-lg", s.isActive ? "bg-profit/10" : "bg-muted")}>
                    <Bot className={cn("h-5 w-5", s.isActive ? "text-profit" : "text-muted-foreground")} />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="text-base font-semibold text-foreground">{s.name}</h3>
                      <span className="rounded bg-surface-3 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">{s.version}</span>
                      <span className={cn("rounded px-1.5 py-0.5 text-[10px] font-semibold", s.isActive ? "bg-profit/10 text-profit" : "bg-warning/10 text-warning")}>
                        {s.isActive ? 'ACTIVE' : 'PAUSED'}
                      </span>
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground max-w-xl">{s.description}</p>
                    <div className="mt-2 text-xs text-muted-foreground">
                      Type: <span className="text-primary">{s.type.replace(/_/g, ' ')}</span>
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <button className="rounded-md p-2 text-muted-foreground hover:bg-surface-2 hover:text-foreground transition-colors">
                    <Settings className="h-4 w-4" />
                  </button>
                  <button className={cn("rounded-md p-2 transition-colors", s.isActive ? "text-warning hover:bg-warning/10" : "text-profit hover:bg-profit/10")}>
                    {s.isActive ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
