import { Key, Shield, Eye, EyeOff, Plus, Trash2 } from "lucide-react";
import { useState } from "react";

const apiKeys = [
  { id: 1, exchange: "Binance", label: "Main Trading", created: "2024-10-15", permissions: "Spot & Futures Trading", status: "active", lastUsed: "2 min ago" },
  { id: 2, exchange: "Binance", label: "Read Only", created: "2024-11-01", permissions: "Read Only", status: "active", lastUsed: "1 hour ago" },
];

export default function ApiKeys() {
  const [showKeys, setShowKeys] = useState(false);

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-foreground">API Key Management</h1>
          <p className="text-sm text-muted-foreground">Securely manage exchange connections</p>
        </div>
        <button className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors">
          <Plus className="h-4 w-4" /> Add API Key
        </button>
      </div>

      {/* Security notice */}
      <div className="flex items-start gap-3 rounded-lg border border-primary/20 bg-primary/5 p-4">
        <Shield className="h-5 w-5 text-primary mt-0.5" />
        <div>
          <p className="text-sm font-medium text-foreground">AES-256 Encrypted Storage</p>
          <p className="text-xs text-muted-foreground mt-1">All API keys are encrypted at rest. Withdrawal permissions are automatically disabled. Only trading and read permissions are supported.</p>
        </div>
      </div>

      <div className="space-y-4">
        {apiKeys.map((k) => (
          <div key={k.id} className="rounded-lg border border-border bg-card p-5">
            <div className="flex items-start justify-between">
              <div className="flex items-start gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-warning/10">
                  <Key className="h-5 w-5 text-warning" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-foreground">{k.label}</span>
                    <span className="rounded bg-profit/10 px-1.5 py-0.5 text-[10px] font-bold text-profit">{k.status.toUpperCase()}</span>
                  </div>
                  <div className="text-xs text-muted-foreground mt-1">{k.exchange} · Created {k.created}</div>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button onClick={() => setShowKeys(!showKeys)} className="rounded-md p-2 text-muted-foreground hover:bg-surface-2 hover:text-foreground transition-colors">
                  {showKeys ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
                <button className="rounded-md p-2 text-muted-foreground hover:bg-loss/10 hover:text-loss transition-colors">
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </div>
            <div className="mt-4 grid grid-cols-3 gap-4">
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">API Key</div>
                <div className="font-mono text-xs text-foreground mt-1">{showKeys ? "xK7m...q9Rp" : "••••••••"}</div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Permissions</div>
                <div className="text-xs text-foreground mt-1">{k.permissions}</div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Last Used</div>
                <div className="text-xs text-foreground mt-1">{k.lastUsed}</div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
