import { Bell, Circle, Wifi, WifiOff, LogOut } from "lucide-react";
import { useAuth } from "@/contexts/AuthContext";
import { useWebSocket, useMarketPrice } from "@/hooks/useWebSocket";
import { useBots } from "@/hooks/api/useBots";

export function TopBar() {
  const { user, logout } = useAuth();
  const { connected } = useWebSocket();
  const btcPrice = useMarketPrice('BTCUSDT');
  const ethPrice = useMarketPrice('ETHUSDT');
  const { data: botsList } = useBots();

  const activeBotCount = botsList?.filter((b) => b.status === 'RUNNING').length ?? 0;
  const initials = user?.email?.slice(0, 2).toUpperCase() ?? '??';

  return (
    <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-border bg-background/80 backdrop-blur-md px-6">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          {connected ? (
            <Wifi className="h-3.5 w-3.5 text-profit" />
          ) : (
            <WifiOff className="h-3.5 w-3.5 text-loss" />
          )}
          <span className="text-xs text-muted-foreground">
            {connected ? 'Exchange Connected' : 'Disconnected'}
          </span>
        </div>
        <div className="h-4 w-px bg-border" />
        <span className="font-mono text-xs text-muted-foreground">
          BTC{' '}
          <span className={btcPrice.change24h >= 0 ? 'text-profit' : 'text-loss'}>
            {btcPrice.price ? `$${btcPrice.price.toLocaleString(undefined, { minimumFractionDigits: 2 })}` : '—'}
          </span>
        </span>
        <span className="font-mono text-xs text-muted-foreground">
          ETH{' '}
          <span className={ethPrice.change24h >= 0 ? 'text-profit' : 'text-loss'}>
            {ethPrice.price ? `$${ethPrice.price.toLocaleString(undefined, { minimumFractionDigits: 2 })}` : '—'}
          </span>
        </span>
      </div>
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2 rounded-md bg-surface-2 px-3 py-1.5">
          <Circle className={`h-2 w-2 fill-current ${activeBotCount > 0 ? 'text-profit animate-pulse' : 'text-muted-foreground'}`} />
          <span className="text-xs font-medium text-foreground">{activeBotCount} Bot{activeBotCount !== 1 ? 's' : ''} Active</span>
        </div>
        <button className="relative text-muted-foreground hover:text-foreground transition-colors">
          <Bell className="h-4 w-4" />
        </button>
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-primary/20 flex items-center justify-center text-xs font-bold text-primary">
            {initials}
          </div>
          <button onClick={logout} className="text-muted-foreground hover:text-foreground transition-colors">
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
    </header>
  );
}
