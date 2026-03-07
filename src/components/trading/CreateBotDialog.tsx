import { useState } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { useCreateBot } from "@/hooks/api/useBots";
import { useApiKeys } from "@/hooks/api/useApiKeys";
import { toast } from "@/hooks/use-toast";
import type { StrategyType, ExchangeMode } from "@/types";
import { AlertTriangle, ShieldCheck } from "lucide-react";

interface CreateBotDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const STRATEGY_CONFIGS: Record<StrategyType, { label: string; description: string; defaultParams: Record<string, number> }> = {
  EMA_CROSS: {
    label: "EMA Crossover",
    description: "Buy when fast EMA crosses above slow EMA, sell on cross below",
    defaultParams: { fastEma: 9, slowEma: 21 },
  },
  SCALPING_EMA: {
    label: "Scalping EMA",
    description: "Fast EMA crossover with momentum filter for quick trades",
    defaultParams: { fastEma: 5, slowEma: 13 },
  },
  SUPPORT_RESISTANCE: {
    label: "Support & Resistance",
    description: "Buy at support bounces, sell at resistance rejections",
    defaultParams: { lookback: 50, tolerance: 0.3 },
  },
  RSI: {
    label: "RSI Strategy",
    description: "Buy when RSI is oversold, sell when overbought",
    defaultParams: { rsiPeriod: 14, rsiBuyThreshold: 30, rsiSellThreshold: 70 },
  },
  MACD: {
    label: "MACD Strategy",
    description: "Buy on MACD line crossing above signal line, sell on cross below",
    defaultParams: { macdFast: 12, macdSlow: 26, macdSignal: 9 },
  },
  BREAKOUT: {
    label: "Breakout Strategy",
    description: "Buy when price breaks above recent high, sell on breakdown below support",
    defaultParams: { breakoutLookback: 20, breakoutConfirm: 0.2 },
  },
  ORDER_BOOK: {
    label: "Order Book Imbalance",
    description: "Buy/sell based on bid/ask volume imbalance from live depth stream",
    defaultParams: { imbalanceThreshold: 1.5 },
  },
};

export function CreateBotDialog({ open, onOpenChange }: CreateBotDialogProps) {
  const [name, setName] = useState("");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [timeframe, setTimeframe] = useState("1m");
  const [strategyType, setStrategyType] = useState<StrategyType>("EMA_CROSS");
  const [exchangeMode, setExchangeMode] = useState<ExchangeMode>("TESTNET");
  const [tradeSizePercent, setTradeSizePercent] = useState(10);
  const [apiKeyId, setApiKeyId] = useState("");
  const [strategyParams, setStrategyParams] = useState<Record<string, number>>(
    STRATEGY_CONFIGS.EMA_CROSS.defaultParams
  );
  const [enableSL, setEnableSL] = useState(false);
  const [stopLossPercent, setStopLossPercent] = useState(5);
  const [enableTP, setEnableTP] = useState(false);
  const [takeProfitPercent, setTakeProfitPercent] = useState(10);
  const [enableTrailing, setEnableTrailing] = useState(false);
  const [trailingStopPercent, setTrailingStopPercent] = useState(2);
  const [maxDailyLossPercent, setMaxDailyLossPercent] = useState(0);

  const createBot = useCreateBot();
  const { data: keysList } = useApiKeys();
  const apiKeys = (keysList ?? []).filter((k) => k.isActive);

  const handleStrategyChange = (value: string) => {
    const st = value as StrategyType;
    setStrategyType(st);
    setStrategyParams({ ...STRATEGY_CONFIGS[st].defaultParams });
  };

  const handleParamChange = (key: string, value: number) => {
    setStrategyParams((prev) => ({ ...prev, [key]: value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !apiKeyId || !symbol.trim()) {
      toast({ title: "Validation Error", description: "Name, API Key, and Symbol are required", variant: "destructive" });
      return;
    }

    try {
      const fastEma = strategyParams.fastEma ?? 9;
      const slowEma = strategyParams.slowEma ?? 21;

      const finalParams: Record<string, number> = { ...strategyParams };
      if (enableSL) finalParams.stopLossPercent = stopLossPercent;
      if (enableTP) finalParams.takeProfitPercent = takeProfitPercent;
      if (enableTrailing) finalParams.trailingStopPercent = trailingStopPercent;
      if (maxDailyLossPercent > 0) finalParams.maxDailyLossPercent = maxDailyLossPercent;

      await createBot.mutateAsync({
        name: name.trim(),
        symbol: symbol.trim().toUpperCase(),
        timeframe,
        strategyType,
        fastEma,
        slowEma,
        tradeSizePercent,
        apiKeyId,
        exchangeMode,
        strategyParams: JSON.stringify(finalParams),
      });
      toast({ title: "Bot Created", description: `Bot "${name}" created with ${STRATEGY_CONFIGS[strategyType].label} strategy.` });
      onOpenChange(false);
      resetForm();
    } catch (err: any) {
      toast({ title: "Error", description: err?.response?.data?.message || "Failed to create bot", variant: "destructive" });
    }
  };

  const resetForm = () => {
    setName("");
    setSymbol("BTCUSDT");
    setTimeframe("1m");
    setStrategyType("EMA_CROSS");
    setExchangeMode("TESTNET");
    setTradeSizePercent(10);
    setApiKeyId("");
    setStrategyParams({ ...STRATEGY_CONFIGS.EMA_CROSS.defaultParams });
    setEnableSL(false);
    setStopLossPercent(5);
    setEnableTP(false);
    setTakeProfitPercent(10);
    setEnableTrailing(false);
    setTrailingStopPercent(2);
    setMaxDailyLossPercent(0);
  };

  return (
    <Dialog open={open} onOpenChange={(v) => { onOpenChange(v); if (!v) resetForm(); }}>
      <DialogContent className="sm:max-w-lg max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Create Trading Bot</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="botName">Bot Name</Label>
            <Input id="botName" placeholder="e.g. BTC Scalper" value={name} onChange={(e) => setName(e.target.value)} />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>API Key</Label>
              <Select value={apiKeyId} onValueChange={setApiKeyId}>
                <SelectTrigger><SelectValue placeholder="Select API key" /></SelectTrigger>
                <SelectContent>
                  {apiKeys.length === 0 ? (
                    <SelectItem value="none" disabled>No active API keys</SelectItem>
                  ) : apiKeys.map((k) => (
                    <SelectItem key={k.id} value={k.id}>{k.label} ({k.exchange})</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Exchange Mode</Label>
              <Select value={exchangeMode} onValueChange={(v) => setExchangeMode(v as ExchangeMode)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="TESTNET">Testnet (Paper)</SelectItem>
                  <SelectItem value="LIVE">Live Trading</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          {exchangeMode === "LIVE" && (
            <div className="flex items-center gap-2 rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              <AlertTriangle className="h-4 w-4 shrink-0" />
              <span>Live trading uses REAL funds. Ensure you understand the risks.</span>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="symbol">Symbol</Label>
              <Input id="symbol" placeholder="BTCUSDT" value={symbol} onChange={(e) => setSymbol(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label>Timeframe</Label>
              <Select value={timeframe} onValueChange={setTimeframe}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="1m">1 Minute</SelectItem>
                  <SelectItem value="5m">5 Minutes</SelectItem>
                  <SelectItem value="15m">15 Minutes</SelectItem>
                  <SelectItem value="1h">1 Hour</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-2">
            <Label>Strategy</Label>
            <Select value={strategyType} onValueChange={handleStrategyChange}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {Object.entries(STRATEGY_CONFIGS).map(([key, cfg]) => (
                  <SelectItem key={key} value={key}>{cfg.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">{STRATEGY_CONFIGS[strategyType].description}</p>
          </div>

          <div className="space-y-2">
            <Label>Strategy Parameters</Label>
            <div className="grid grid-cols-2 gap-3">
              {Object.entries(strategyParams).map(([key, value]) => (
                <div key={key} className="space-y-1">
                  <Label htmlFor={`param-${key}`} className="text-xs text-muted-foreground capitalize">
                    {key.replace(/([A-Z])/g, ' $1').trim()}
                  </Label>
                  <Input
                    id={`param-${key}`}
                    type="number"
                    step={key === "tolerance" ? 0.1 : 1}
                    value={value}
                    onChange={(e) => handleParamChange(key, Number(e.target.value))}
                  />
                </div>
              ))}
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="tradeSize">Trade Size % (of USDT balance)</Label>
            <Input id="tradeSize" type="number" min={1} max={100} value={tradeSizePercent}
              onChange={(e) => setTradeSizePercent(Number(e.target.value))} />
          </div>

          {/* Risk Management */}
          <div className="space-y-3 rounded-md border border-border p-3">
            <div className="flex items-center gap-2 text-sm font-medium text-foreground">
              <ShieldCheck className="h-4 w-4 text-primary" />
              Risk Management
            </div>

            <div className="flex items-center justify-between">
              <Label htmlFor="sl-toggle" className="text-sm">Stop-Loss</Label>
              <Switch id="sl-toggle" checked={enableSL} onCheckedChange={setEnableSL} />
            </div>
            {enableSL && (
              <div className="space-y-1">
                <Label className="text-xs text-muted-foreground">Stop-Loss %</Label>
                <Input type="number" min={0.5} max={50} step={0.5} value={stopLossPercent}
                  onChange={(e) => setStopLossPercent(Number(e.target.value))} />
                <p className="text-[10px] text-muted-foreground">Auto-sell if price drops this % below entry</p>
              </div>
            )}

            <div className="flex items-center justify-between">
              <Label htmlFor="tp-toggle" className="text-sm">Take-Profit</Label>
              <Switch id="tp-toggle" checked={enableTP} onCheckedChange={setEnableTP} />
            </div>
            {enableTP && (
              <div className="space-y-1">
                <Label className="text-xs text-muted-foreground">Take-Profit %</Label>
                <Input type="number" min={0.5} max={100} step={0.5} value={takeProfitPercent}
                  onChange={(e) => setTakeProfitPercent(Number(e.target.value))} />
                <p className="text-[10px] text-muted-foreground">Auto-sell if price rises this % above entry</p>
              </div>
            )}

            <div className="flex items-center justify-between">
              <Label htmlFor="ts-toggle" className="text-sm">Trailing Stop</Label>
              <Switch id="ts-toggle" checked={enableTrailing} onCheckedChange={setEnableTrailing} />
            </div>
            {enableTrailing && (
              <div className="space-y-1">
                <Label className="text-xs text-muted-foreground">Trailing Stop %</Label>
                <Input type="number" min={0.5} max={20} step={0.5} value={trailingStopPercent}
                  onChange={(e) => setTrailingStopPercent(Number(e.target.value))} />
                <p className="text-[10px] text-muted-foreground">Follows price up, sells if price drops this % from peak</p>
              </div>
            )}

            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Max Daily Loss % (0 = disabled)</Label>
              <Input type="number" min={0} max={50} step={0.5} value={maxDailyLossPercent}
                onChange={(e) => setMaxDailyLossPercent(Number(e.target.value))} />
            </div>
          </div>

          {/* Summary */}
          <div className="flex flex-wrap gap-1.5">
            <Badge variant="outline">{STRATEGY_CONFIGS[strategyType].label}</Badge>
            <Badge variant="outline">{exchangeMode}</Badge>
            <Badge variant="outline">{symbol || "—"}</Badge>
            <Badge variant="outline">{timeframe}</Badge>
            <Badge variant="outline">{tradeSizePercent}% size</Badge>
            {enableSL && <Badge variant="outline" className="text-destructive border-destructive/30">SL {stopLossPercent}%</Badge>}
            {enableTP && <Badge variant="outline" className="text-profit border-profit/30">TP {takeProfitPercent}%</Badge>}
            {enableTrailing && <Badge variant="outline" className="text-warning border-warning/30">Trail {trailingStopPercent}%</Badge>}
            {maxDailyLossPercent > 0 && <Badge variant="outline">Max Loss {maxDailyLossPercent}%/day</Badge>}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={createBot.isPending || apiKeys.length === 0}>
              {createBot.isPending ? "Creating..." : "Create Bot"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
