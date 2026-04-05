import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ShieldCheck, ShieldX, Ban } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import client from "@/lib/api";

interface SizingEntry {
  timestamp: string;
  botId?: string;
  symbol: string;
  balance?: number;
  riskPercent?: number;
  slDistancePercent?: number;
  rawRiskQty?: number;
  maxCapQty?: number;
  finalQty?: number;
  notionalValue?: number;
  impliedLeverage?: number;
  status: "PASSED" | "SIZE_CAPPED" | "REJECTED";
  reason?: string;
}

interface ApiHealth {
  status: string;
  exchanges: Record<string, { status: string; error: string; timestamp: number }>;
}

export function RiskSizingMonitor() {
  const { data: sizingData } = useQuery<SizingEntry[]>({
    queryKey: ["risk-sizing-audit"],
    queryFn: () => client.get("/risk-monitor/sizing-audit?limit=20").then((r) => r.data),
    refetchInterval: 5000,
  });

  const { data: apiHealth } = useQuery<ApiHealth>({
    queryKey: ["risk-api-health"],
    queryFn: () => client.get("/risk-monitor/api-health").then((r) => r.data),
    refetchInterval: 5000,
  });

  const blockedExchanges = apiHealth?.exchanges
    ? Object.entries(apiHealth.exchanges).filter(([, v]) => v.status === "API_BLOCKED")
    : [];

  return (
    <div className="space-y-4">
      {/* API Health Indicator */}
      {blockedExchanges.length > 0 && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-3 space-y-2">
          {blockedExchanges.map(([exchange, info]) => (
            <div key={exchange} className="flex items-center gap-2">
              <Ban className="h-4 w-4 text-destructive shrink-0" />
              <div className="min-w-0">
                <p className="text-sm font-semibold text-destructive">
                  {exchange} — API IP BLOCKED
                </p>
                <p className="text-xs text-muted-foreground truncate">{info.error}</p>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Sizing Audit Table */}
      <div className="rounded-lg border border-border bg-card">
        <div className="flex items-center justify-between p-4 pb-2">
          <h3 className="text-sm font-semibold text-foreground">Risk & Sizing Monitor</h3>
          <div className="flex items-center gap-1.5">
            {apiHealth?.status === "HEALTHY" ? (
              <Badge variant="outline" className="border-profit/50 text-profit text-xs gap-1">
                <ShieldCheck className="h-3 w-3" /> API Healthy
              </Badge>
            ) : (
              <Badge variant="destructive" className="text-xs gap-1">
                <ShieldX className="h-3 w-3" /> API Blocked
              </Badge>
            )}
          </div>
        </div>

        <div className="overflow-auto max-h-80">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="text-xs">Time</TableHead>
                <TableHead className="text-xs">Symbol</TableHead>
                <TableHead className="text-xs text-right">Risk Qty</TableHead>
                <TableHead className="text-xs text-right">Final Qty</TableHead>
                <TableHead className="text-xs text-right">Leverage</TableHead>
                <TableHead className="text-xs">Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(!sizingData || sizingData.length === 0) && (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-muted-foreground text-xs py-6">
                    No sizing evaluations yet
                  </TableCell>
                </TableRow>
              )}
              {sizingData?.map((entry, i) => {
                const isCapped = entry.status === "SIZE_CAPPED";
                const isRejected = entry.status === "REJECTED";
                const time = entry.timestamp
                  ? new Date(entry.timestamp).toLocaleTimeString()
                  : "—";

                return (
                  <TableRow key={i}>
                    <TableCell className="text-xs font-mono text-muted-foreground">{time}</TableCell>
                    <TableCell className="text-xs font-mono">{entry.symbol}</TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right",
                      isCapped && "line-through text-destructive"
                    )}>
                      {entry.rawRiskQty?.toFixed(6) ?? "—"}
                    </TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right font-semibold",
                      isCapped ? "text-warning" : isRejected ? "text-destructive" : "text-profit"
                    )}>
                      {isRejected ? "—" : entry.finalQty?.toFixed(6) ?? "—"}
                    </TableCell>
                    <TableCell className={cn(
                      "text-xs font-mono text-right",
                      (entry.impliedLeverage ?? 0) > 3 ? "text-warning" : "text-muted-foreground"
                    )}>
                      {entry.impliedLeverage != null ? `${entry.impliedLeverage.toFixed(2)}x` : "—"}
                    </TableCell>
                    <TableCell>
                      {entry.status === "PASSED" && (
                        <Badge variant="outline" className="border-profit/50 text-profit text-[10px]">PASSED</Badge>
                      )}
                      {entry.status === "SIZE_CAPPED" && (
                        <Badge className="bg-warning/20 text-warning border-warning/30 text-[10px] gap-1">
                          <AlertTriangle className="h-2.5 w-2.5" /> CAPPED
                        </Badge>
                      )}
                      {entry.status === "REJECTED" && (
                        <Badge variant="destructive" className="text-[10px]">REJECTED</Badge>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}
