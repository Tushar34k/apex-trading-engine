import { useState } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useCreateBot } from "@/hooks/api/useBots";
import { useApiKeys } from "@/hooks/api/useApiKeys";
import { toast } from "@/hooks/use-toast";

interface CreateBotDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateBotDialog({ open, onOpenChange }: CreateBotDialogProps) {
  const [name, setName] = useState("");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [timeframe, setTimeframe] = useState("1m");
  const [fastEma, setFastEma] = useState(9);
  const [slowEma, setSlowEma] = useState(21);
  const [tradeSizePercent, setTradeSizePercent] = useState(10);
  const [apiKeyId, setApiKeyId] = useState("");

  const createBot = useCreateBot();
  const { data: keysList } = useApiKeys();
  const apiKeys = (keysList ?? []).filter((k) => k.isActive);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !apiKeyId || !symbol.trim()) {
      toast({ title: "Validation Error", description: "Name, API Key, and Symbol are required", variant: "destructive" });
      return;
    }
    if (fastEma >= slowEma) {
      toast({ title: "Validation Error", description: "Fast EMA must be less than Slow EMA", variant: "destructive" });
      return;
    }
    try {
      await createBot.mutateAsync({
        name: name.trim(),
        symbol: symbol.trim().toUpperCase(),
        timeframe,
        strategyType: "EMA_CROSS",
        fastEma,
        slowEma,
        tradeSizePercent,
        apiKeyId,
      });
      toast({ title: "Bot Created", description: `Bot "${name}" for ${symbol} created.` });
      onOpenChange(false);
      resetForm();
    } catch (err: any) {
      toast({ title: "Error", description: err?.response?.data?.message || "Failed to create bot", variant: "destructive" });
    }
  };

  const resetForm = () => {
    setName(""); setSymbol("BTCUSDT"); setTimeframe("1m"); setFastEma(9); setSlowEma(21); setTradeSizePercent(10); setApiKeyId("");
  };

  return (
    <Dialog open={open} onOpenChange={(v) => { onOpenChange(v); if (!v) resetForm(); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Create Trading Bot</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="botName">Bot Name</Label>
            <Input id="botName" placeholder="e.g. BTC Scalper" value={name} onChange={(e) => setName(e.target.value)} />
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
          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label htmlFor="fastEma">Fast EMA</Label>
              <Input id="fastEma" type="number" min={2} max={50} value={fastEma} onChange={(e) => setFastEma(Number(e.target.value))} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="slowEma">Slow EMA</Label>
              <Input id="slowEma" type="number" min={5} max={200} value={slowEma} onChange={(e) => setSlowEma(Number(e.target.value))} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="tradeSize">Trade Size %</Label>
              <Input id="tradeSize" type="number" min={1} max={100} value={tradeSizePercent} onChange={(e) => setTradeSizePercent(Number(e.target.value))} />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">Strategy: EMA Crossover · Binance TESTNET · Spot only · No short selling</p>
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
