import { useState } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useAddApiKey } from "@/hooks/api/useApiKeys";
import { toast } from "@/hooks/use-toast";
import { Eye, EyeOff } from "lucide-react";

const SUPPORTED_EXCHANGES = [
  { value: "BINANCE", label: "Binance" },
  { value: "DELTA", label: "Delta Exchange" },
  { value: "BYBIT", label: "Bybit" },
];

interface AddApiKeyDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function AddApiKeyDialog({ open, onOpenChange }: AddApiKeyDialogProps) {
  const [exchange, setExchange] = useState("BINANCE");
  const [label, setLabel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [apiSecret, setApiSecret] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [showSecret, setShowSecret] = useState(false);
  const addKey = useAddApiKey();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!label.trim() || !apiKey.trim() || !apiSecret.trim() || !exchange) {
      toast({ title: "Validation Error", description: "All fields are required", variant: "destructive" });
      return;
    }
    try {
      await addKey.mutateAsync({
        exchange,
        label: label.trim(),
        apiKey: apiKey.trim(),
        apiSecret: apiSecret.trim(),
        permissions: "TRADE_ONLY",
      });
      toast({ title: "API Key Added", description: `Your ${exchange} API key has been securely stored.` });
      setExchange("BINANCE"); setLabel(""); setApiKey(""); setApiSecret("");
      onOpenChange(false);
    } catch (err: any) {
      const msg = err?.response?.data?.message || "Failed to add API key";
      toast({ title: "Error", description: msg, variant: "destructive" });
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Add Exchange API Key</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label>Exchange</Label>
            <Select value={exchange} onValueChange={setExchange}>
              <SelectTrigger><SelectValue placeholder="Select exchange" /></SelectTrigger>
              <SelectContent>
                {SUPPORTED_EXCHANGES.map((ex) => (
                  <SelectItem key={ex.value} value={ex.value}>{ex.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            {exchange !== "BINANCE" && (
              <p className="text-xs text-muted-foreground">
                {exchange} integration is coming soon. You can save your key now.
              </p>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="label">Label</Label>
            <Input id="label" placeholder="e.g. Main Trading Key" value={label} onChange={(e) => setLabel(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="apiKey">API Key</Label>
            <div className="relative">
              <Input id="apiKey" type={showKey ? "text" : "password"} placeholder="Paste your API key" value={apiKey} onChange={(e) => setApiKey(e.target.value)} className="pr-10" />
              <button type="button" onClick={() => setShowKey(!showKey)} className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="apiSecret">Secret Key</Label>
            <div className="relative">
              <Input id="apiSecret" type={showSecret ? "text" : "password"} placeholder="Paste your secret key" value={apiSecret} onChange={(e) => setApiSecret(e.target.value)} className="pr-10" />
              <button type="button" onClick={() => setShowSecret(!showSecret)} className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
          <p className="text-xs text-muted-foreground">Keys are encrypted with AES-256 before storage. Only trading permissions are used.</p>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={addKey.isPending}>
              {addKey.isPending ? "Adding..." : "Add Key"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
