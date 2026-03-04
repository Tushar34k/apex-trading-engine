import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useTradeDetail } from "@/hooks/api/useTrades";
import { cn } from "@/lib/utils";

interface TradeDetailDialogProps {
  tradeId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function TradeDetailDialog({ tradeId, open, onOpenChange }: TradeDetailDialogProps) {
  const { data: detail, isLoading } = useTradeDetail(open ? tradeId : null);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg bg-card border-border text-foreground">
        <DialogHeader>
          <DialogTitle className="text-foreground">Trade Details</DialogTitle>
        </DialogHeader>

        {isLoading ? (
          <div className="py-8 text-center text-sm text-muted-foreground">Loading trade details...</div>
        ) : !detail ? (
          <div className="py-8 text-center text-sm text-muted-foreground">Trade not found</div>
        ) : (
          <div className="space-y-4">
            {/* Trade Summary */}
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Symbol</div>
                <div className="font-mono text-sm font-semibold text-foreground mt-1">{detail.symbol}</div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Side</div>
                <div className={cn("text-sm font-bold mt-1", detail.side === 'LONG' ? 'text-profit' : 'text-loss')}>{detail.side}</div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Entry</div>
                <div className="font-mono text-sm text-foreground mt-1">${detail.entryPrice.toLocaleString()}</div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Exit</div>
                <div className="font-mono text-sm text-foreground mt-1">{detail.exitPrice ? `$${detail.exitPrice.toLocaleString()}` : '—'}</div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">P&L</div>
                <div className={cn("font-mono text-sm font-bold mt-1", (detail.pnl ?? 0) >= 0 ? 'text-profit' : 'text-loss')}>
                  {detail.pnl != null ? `${detail.pnl >= 0 ? '+' : ''}$${detail.pnl.toFixed(2)}` : '—'}
                </div>
              </div>
              <div className="rounded-md bg-surface-1 px-3 py-2">
                <div className="text-[10px] text-muted-foreground uppercase">Status</div>
                <div className="text-sm text-foreground mt-1">{detail.status}</div>
              </div>
            </div>

            {/* Strategy Info */}
            {detail.explanation && (
              <>
                <div className="border-t border-border pt-4">
                  <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">Strategy & Regime</h4>
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="rounded bg-primary/10 px-2 py-1 text-xs font-medium text-primary">
                      {detail.explanation.strategyName} {detail.explanation.strategyVersion}
                    </span>
                    <span className="rounded bg-surface-3 px-2 py-1 text-xs text-muted-foreground">
                      {detail.explanation.marketRegime}
                    </span>
                  </div>
                </div>

                {/* Entry/Exit Reasons */}
                <div className="space-y-2">
                  <div className="rounded-md bg-surface-1 px-3 py-2">
                    <div className="text-[10px] text-muted-foreground uppercase">Entry Reason</div>
                    <div className="text-xs text-foreground mt-1">{detail.explanation.entryReason}</div>
                  </div>
                  {detail.explanation.exitReason && (
                    <div className="rounded-md bg-surface-1 px-3 py-2">
                      <div className="text-[10px] text-muted-foreground uppercase">Exit Reason</div>
                      <div className="text-xs text-foreground mt-1">{detail.explanation.exitReason}</div>
                    </div>
                  )}
                </div>

                {/* Indicator Snapshot */}
                <div>
                  <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">Indicator Snapshot</h4>
                  <div className="flex flex-wrap gap-2">
                    {Object.entries(detail.explanation.indicatorSnapshot).map(([key, value]) => (
                      <span key={key} className="rounded bg-surface-2 px-2 py-1 font-mono text-[11px] text-muted-foreground">
                        {key}: <span className="text-foreground">{typeof value === 'number' ? value.toFixed(2) : value}</span>
                      </span>
                    ))}
                  </div>
                </div>

                {/* Risk Settings */}
                <div>
                  <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">Risk Settings</h4>
                  <div className="flex flex-wrap gap-2">
                    {Object.entries(detail.explanation.riskSettings).map(([key, value]) => (
                      <span key={key} className="rounded bg-surface-2 px-2 py-1 font-mono text-[11px] text-muted-foreground">
                        {key}: <span className="text-foreground">{value}</span>
                      </span>
                    ))}
                  </div>
                </div>
              </>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
