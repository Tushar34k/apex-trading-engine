import { Circle, Wifi, WifiOff, LogOut, FlaskConical, Wallet } from "lucide-react";
import { useAuth } from "@/contexts/AuthContext";
import { useWebSocket, useMarketPrice } from "@/hooks/useWebSocket";
import { useBots } from "@/hooks/api/useBots";
import { useAccountBalance } from "@/hooks/api/useAccountBalance";

export function TopBar() {
  const { user, logout } = useAuth();
  const { connected } = useWebSocket();
  const btcPrice = useMarketPrice('BTCUSDT');
  const { data: botsList } = useBots();
  const { data: balance } = useAccountBalance();

  const activeBotCount = botsList?.filter((b) => b.status === 'RUNNING').length ?? 0;
  const hasLiveBot = botsList?.some((b) => b.status === 'RUNNING' && b.exchangeMode === 'LIVE');
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
            {connected ? 'Connected' : 'Disconnected'}
          </span>
        </div>
        <div className="h-4 w-px bg-border" />
        <span className="font-mono text-xs text-muted-foreground">
          BTC{' '}
          <span className={btcPrice.change24h >= 0 ? 'text-profit' : 'text-loss'}>
            {btcPrice.price ? `$${btcPrice.price.toLocaleString(undefined, { minimumFractionDigits: 2 })}` : '—'}
          </span>
        </span>
        {balance && (
          <>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Wallet className="h-3 w-3" />
              <span className="font-mono">${balance.available?.toLocaleString(undefined, { minimumFractionDigits: 2 }) ?? '0.00'}</span>
            </div>
          </>
        )}
        <div className={`flex items-center gap-1.5 rounded-md border px-2.5 py-1 ${
          hasLiveBot
            ? 'bg-destructive/10 border-destructive/20'
            : 'bg-warning/10 border-warning/20'
        }`}>
          <FlaskConical className={`h-3.5 w-3.5 ${hasLiveBot ? 'text-destructive' : 'text-warning'}`} />
          <span className={`text-[10px] font-bold uppercase tracking-wider ${hasLiveBot ? 'text-destructive' : 'text-warning'}`}>
            {hasLiveBot ? 'LIVE' : 'Testnet'}
          </span>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2 rounded-md bg-surface-2 px-3 py-1.5">
          <Circle className={`h-2 w-2 fill-current ${activeBotCount > 0 ? 'text-profit animate-pulse' : 'text-muted-foreground'}`} />
          <span className="text-xs font-medium text-foreground">{activeBotCount} Bot{activeBotCount !== 1 ? 's' : ''} Active</span>
        </div>
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
