import { useState } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { useCreateBot } from "@/hooks/api/useBots";
import { useApiKeys } from "@/hooks/api/useApiKeys";
import { toast } from "@/hooks/use-toast";
import type { StrategyType, ExchangeMode } from "@/types";
import { AlertTriangle } from "lucide-react";

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
        strategyParams: JSON.stringify(strategyParams),
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
  };

  return (
    <Dialog open={open} onOpenChange={(v) => { onOpenChange(v); if (!v) resetForm(); }}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Trading Bot</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Bot Name */}
          <div className="space-y-2">
            <Label htmlFor="botName">Bot Name</Label>
            <Input id="botName" placeholder="e.g. BTC Scalper" value={name} onChange={(e) => setName(e.target.value)} />
          </div>

          {/* API Key + Exchange Mode */}
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

          {/* Live trading warning */}
          {exchangeMode === "LIVE" && (
            <div className="flex items-center gap-2 rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              <AlertTriangle className="h-4 w-4 shrink-0" />
              <span>Live trading uses REAL funds. Ensure you understand the risks.</span>
            </div>
          )}

          {/* Symbol + Timeframe */}
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

          {/* Strategy Selection */}
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

          {/* Dynamic Strategy Parameters */}
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

          {/* Trade Size */}
          <div className="space-y-2">
            <Label htmlFor="tradeSize">Trade Size % (of USDT balance)</Label>
            <Input id="tradeSize" type="number" min={1} max={100} value={tradeSizePercent}
              onChange={(e) => setTradeSizePercent(Number(e.target.value))} />
          </div>

          {/* Summary */}
          <div className="flex flex-wrap gap-1.5">
            <Badge variant="outline">{STRATEGY_CONFIGS[strategyType].label}</Badge>
            <Badge variant="outline">{exchangeMode}</Badge>
            <Badge variant="outline">{symbol || "—"}</Badge>
            <Badge variant="outline">{timeframe}</Badge>
            <Badge variant="outline">{tradeSizePercent}% size</Badge>
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
