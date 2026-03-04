import { useState } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useCreateBot } from "@/hooks/api/useBots";
import { useStrategies } from "@/hooks/api/useStrategies";
import { useApiKeys } from "@/hooks/api/useApiKeys";
import { toast } from "@/hooks/use-toast";
import { AlertTriangle } from "lucide-react";

interface CreateBotDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateBotDialog({ open, onOpenChange }: CreateBotDialogProps) {
  const [strategyId, setStrategyId] = useState("");
  const [apiKeyId, setApiKeyId] = useState("");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [timeframe, setTimeframe] = useState("1h");
  const [mode, setMode] = useState<"LIVE" | "PAPER">("PAPER");
  const [showLiveConfirm, setShowLiveConfirm] = useState(false);

  const createBot = useCreateBot();
  const { data: strategiesList } = useStrategies();
  const { data: keysList } = useApiKeys();

  const strategies = strategiesList ?? [];
  const apiKeys = (keysList ?? []).filter((k) => k.isActive);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!strategyId || !apiKeyId || !symbol.trim()) {
      toast({ title: "Validation Error", description: "Strategy, API Key, and Symbol are required", variant: "destructive" });
      return;
    }
    if (mode === "LIVE" && !showLiveConfirm) {
      setShowLiveConfirm(true);
      return;
    }
    try {
      await createBot.mutateAsync({ strategyId, apiKeyId, symbol: symbol.trim().toUpperCase(), timeframe, mode });
      toast({ title: "Bot Created", description: `Trading bot for ${symbol} created successfully.` });
      onOpenChange(false);
      resetForm();
    } catch (err: any) {
      toast({ title: "Error", description: err?.response?.data?.message || "Failed to create bot", variant: "destructive" });
    }
  };

  const resetForm = () => {
    setStrategyId(""); setApiKeyId(""); setSymbol("BTCUSDT"); setTimeframe("1h"); setMode("PAPER"); setShowLiveConfirm(false);
  };

  return (
    <Dialog open={open} onOpenChange={(v) => { onOpenChange(v); if (!v) resetForm(); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Create Trading Bot</DialogTitle>
        </DialogHeader>

        {showLiveConfirm ? (
          <div className="space-y-4">
            <div className="flex items-start gap-3 rounded-lg border border-warning/30 bg-warning/5 p-4">
              <AlertTriangle className="h-5 w-5 text-warning mt-0.5" />
              <div>
                <p className="text-sm font-medium text-foreground">Live Trading Warning</p>
                <p className="text-xs text-muted-foreground mt-1">You are about to trade with real funds on Binance. This bot will place real market orders. Are you sure?</p>
              </div>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setShowLiveConfirm(false)}>Go Back</Button>
              <Button variant="destructive" onClick={handleSubmit} disabled={createBot.isPending}>
                {createBot.isPending ? "Creating..." : "Confirm Live Trading"}
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label>Strategy</Label>
              <Select value={strategyId} onValueChange={setStrategyId}>
                <SelectTrigger><SelectValue placeholder="Select strategy" /></SelectTrigger>
                <SelectContent>
                  {strategies.map((s) => (
                    <SelectItem key={s.id} value={s.id}>{s.name} ({s.version})</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
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
              <Label htmlFor="symbol">Symbol</Label>
              <Input id="symbol" placeholder="BTCUSDT" value={symbol} onChange={(e) => setSymbol(e.target.value)} />
            </div>
            <div className="grid grid-cols-2 gap-4">
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
              <div className="space-y-2">
                <Label>Mode</Label>
                <Select value={mode} onValueChange={(v) => setMode(v as "LIVE" | "PAPER")}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="PAPER">Paper Trading</SelectItem>
                    <SelectItem value="LIVE">Live Trading</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={createBot.isPending || apiKeys.length === 0}>
                {createBot.isPending ? "Creating..." : "Create Bot"}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
