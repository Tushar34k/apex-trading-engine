import { Bell, Circle, Wifi } from "lucide-react";

export function TopBar() {
  return (
    <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-border bg-background/80 backdrop-blur-md px-6">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <Wifi className="h-3.5 w-3.5 text-profit" />
          <span className="text-xs text-muted-foreground">Binance Connected</span>
        </div>
        <div className="h-4 w-px bg-border" />
        <span className="font-mono text-xs text-muted-foreground">
          BTC <span className="text-profit">$67,432.50</span>
        </span>
        <span className="font-mono text-xs text-muted-foreground">
          ETH <span className="text-profit">$3,521.20</span>
        </span>
      </div>
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2 rounded-md bg-surface-2 px-3 py-1.5">
          <Circle className="h-2 w-2 fill-profit text-profit animate-pulse-glow" />
          <span className="text-xs font-medium text-foreground">3 Bots Active</span>
        </div>
        <button className="relative text-muted-foreground hover:text-foreground transition-colors">
          <Bell className="h-4 w-4" />
          <span className="absolute -top-1 -right-1 h-2 w-2 rounded-full bg-loss" />
        </button>
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-primary/20 flex items-center justify-center text-xs font-bold text-primary">
            JD
          </div>
        </div>
      </div>
    </header>
  );
}
