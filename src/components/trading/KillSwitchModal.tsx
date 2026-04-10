import { useState, useEffect } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Octagon, CheckCircle2, Loader2, CircleDot,
  AlertTriangle, RefreshCw, ShieldCheck, Settings2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Tooltip, TooltipContent, TooltipTrigger,
} from "@/components/ui/tooltip";
import { system } from "@/lib/api";
import client from "@/lib/api";
import { toast } from "sonner";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface SystemMetrics {
  killSwitch: {
    active: boolean;
    reason: string | null;
    activatedAt: string | null;
  };
  exchangeHealth: {
    circuitBreakerOpen: boolean;
    recentErrors: number;
  };
}

type StepStatus = "pending" | "loading" | "done" | "error";

/* ------------------------------------------------------------------ */
/*  Recovery Step Component                                            */
/* ------------------------------------------------------------------ */

function RecoveryStep({
  stepNum,
  title,
  description,
  status,
  onExecute,
  disabled,
}: {
  stepNum: number;
  title: string;
  description: string;
  status: StepStatus;
  onExecute: () => void;
  disabled: boolean;
}) {
  return (
    <div
      className={cn(
        "flex items-center gap-4 rounded-lg border p-4 transition-all",
        status === "done"
          ? "border-profit/40 bg-profit/5"
          : status === "error"
          ? "border-destructive/40 bg-destructive/5"
          : status === "loading"
          ? "border-primary/40 bg-primary/5"
          : "border-border bg-card"
      )}
    >
      {/* Step indicator */}
      <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-border bg-muted/50">
        {status === "loading" ? (
          <Loader2 className="h-4 w-4 text-primary animate-spin" />
        ) : status === "done" ? (
          <CheckCircle2 className="h-4 w-4 text-profit" />
        ) : status === "error" ? (
          <AlertTriangle className="h-4 w-4 text-destructive" />
        ) : (
          <span className="text-xs font-bold font-mono text-muted-foreground">
            {stepNum}
          </span>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <h4 className="text-sm font-semibold text-foreground">{title}</h4>
        <p className="text-[11px] text-muted-foreground mt-0.5">{description}</p>
      </div>

      {/* Action */}
      <Button
        size="sm"
        variant={status === "done" ? "outline" : "default"}
        onClick={onExecute}
        disabled={disabled || status === "loading" || status === "done"}
        className={cn(
          "font-mono text-xs shrink-0",
          status === "done" && "border-profit/50 text-profit pointer-events-none"
        )}
      >
        {status === "loading" ? (
          <>
            <Loader2 className="h-3 w-3 mr-1.5 animate-spin" />
            RUNNING
          </>
        ) : status === "done" ? (
          <>
            <CheckCircle2 className="h-3 w-3 mr-1.5" />
            COMPLETE
          </>
        ) : (
          <>
            <CircleDot className="h-3 w-3 mr-1.5" />
            EXECUTE
          </>
        )}
      </Button>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Main Modal                                                         */
/* ------------------------------------------------------------------ */

export function KillSwitchModal() {
  const queryClient = useQueryClient();

  const { data: sysMetrics } = useQuery<SystemMetrics>({
    queryKey: ["system-metrics"],
    queryFn: system.metrics,
    refetchInterval: 3000,
  });

  const killSwitchActive = sysMetrics?.killSwitch?.active ?? false;
  const killReason = sysMetrics?.killSwitch?.reason ?? "Unknown trigger";
  const activatedAt = sysMetrics?.killSwitch?.activatedAt;

  /* Step states */
  const [step1, setStep1] = useState<StepStatus>("pending");
  const [step2, setStep2] = useState<StepStatus>("pending");
  const [step3, setStep3] = useState<StepStatus>("pending");

  /* Bot sizing adjustment */
  const [botId, setBotId] = useState("941b3384-5589-4f71-8a07-10b424504413");
  const [newSizePercent, setNewSizePercent] = useState("2");
  const [sizeUpdated, setSizeUpdated] = useState(false);

  /* Reset steps when kill switch deactivates */
  useEffect(() => {
    if (!killSwitchActive) {
      setStep1("pending");
      setStep2("pending");
      setStep3("pending");
      setSizeUpdated(false);
    }
  }, [killSwitchActive]);

  /* Mutations */
  const syncBalances = useMutation({
    mutationFn: system.syncBalances,
    onMutate: () => setStep1("loading"),
    onSuccess: () => {
      setStep1("done");
      toast.success("Exchange balances synced successfully");
    },
    onError: () => {
      setStep1("error");
      toast.error("Balance sync failed — check exchange connectivity");
    },
  });

  const reconcile = useMutation({
    mutationFn: system.reconcilePositions,
    onMutate: () => setStep2("loading"),
    onSuccess: () => {
      setStep2("done");
      toast.success("Positions reconciled — orphans resolved");
    },
    onError: () => {
      setStep2("error");
      toast.error("Reconciliation failed — manual review needed");
    },
  });

  const resetKS = useMutation({
    mutationFn: system.resetKillSwitch,
    onMutate: () => setStep3("loading"),
    onSuccess: () => {
      setStep3("done");
      queryClient.invalidateQueries({ queryKey: ["system-metrics"] });
      toast.success("Kill switch reset — system unlocked");
    },
    onError: () => {
      setStep3("error");
      toast.error("Reset failed");
    },
  });

  const updateBotSize = useMutation({
    mutationFn: () =>
      client.patch(`/bots/${botId}/sizing`, { tradeSizePercent: parseFloat(newSizePercent) }).then((r) => r.data),
    onSuccess: () => {
      setSizeUpdated(true);
      queryClient.invalidateQueries({ queryKey: ["bots"] });
      toast.success(`Bot sizing updated to ${newSizePercent}%`);
    },
    onError: () => toast.error("Failed to update bot sizing"),
  });

  const canResetKS = step1 === "done" && step2 === "done";

  return (
    <Dialog open={killSwitchActive}>
      <DialogContent
        className="sm:max-w-[620px] border-destructive/50 bg-background shadow-2xl shadow-destructive/10 [&>button]:hidden"
        onPointerDownOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
      >
        {/* Header */}
        <DialogHeader className="space-y-3">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-destructive/15">
              <Octagon className="h-5 w-5 text-destructive animate-pulse" />
            </div>
            <div>
              <DialogTitle className="text-destructive text-lg font-bold tracking-tight">
                CRITICAL: GLOBAL KILL SWITCH ACTIVATED
              </DialogTitle>
              <DialogDescription className="text-xs text-muted-foreground font-mono mt-0.5">
                All trading halted · Manual recovery required
              </DialogDescription>
            </div>
          </div>

          {/* Error reason */}
          <div className="rounded-md border border-destructive/30 bg-destructive/5 p-3">
            <div className="flex items-start gap-2">
              <AlertTriangle className="h-4 w-4 text-destructive shrink-0 mt-0.5" />
              <div>
                <p className="text-xs font-semibold text-destructive">Trigger Reason</p>
                <p className="text-xs text-foreground font-mono mt-1 leading-relaxed">
                  {killReason}
                </p>
                {activatedAt && (
                  <p className="text-[10px] text-muted-foreground font-mono mt-1">
                    Activated: {new Date(activatedAt).toLocaleString()}
                  </p>
                )}
              </div>
            </div>
          </div>
        </DialogHeader>

        {/* ──────── Recovery Pipeline ──────── */}
        <div className="space-y-3 mt-2">
          <div className="flex items-center gap-2">
            <RefreshCw className="h-3.5 w-3.5 text-primary" />
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
              Recovery Checklist
            </h3>
          </div>

          <RecoveryStep
            stepNum={1}
            title="Force Sync Exchange Balances"
            description="Re-fetch actual balance from all connected exchanges to reconcile internal state."
            status={step1}
            onExecute={() => syncBalances.mutate()}
            disabled={false}
          />

          <RecoveryStep
            stepNum={2}
            title="Reconcile Orphaned Positions"
            description="Compare DB positions vs. exchange positions and close orphans that caused margin errors."
            status={step2}
            onExecute={() => reconcile.mutate()}
            disabled={step1 !== "done"}
          />

          <RecoveryStep
            stepNum={3}
            title="Acknowledge Risk & Reset Kill Switch"
            description="Confirm you've reviewed the issue, then unlock the system for trading."
            status={step3}
            onExecute={() => resetKS.mutate()}
            disabled={!canResetKS}
          />

          {!canResetKS && step3 === "pending" && (
            <p className="text-[10px] text-muted-foreground text-center font-mono">
              Complete steps 1 & 2 before the kill switch can be reset
            </p>
          )}
        </div>

        {/* ──────── Bot Sizing Adjustment ──────── */}
        <div className="mt-4 rounded-lg border border-border bg-card p-4 space-y-3">
          <div className="flex items-center gap-2">
            <Settings2 className="h-3.5 w-3.5 text-warning" />
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
              Adjust Bot Sizing
            </h3>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-[10px] text-muted-foreground uppercase font-mono">Bot ID</label>
              <Input
                value={botId}
                onChange={(e) => setBotId(e.target.value)}
                className="font-mono text-xs h-8 bg-muted/30"
                placeholder="Bot UUID"
              />
            </div>
            <div className="space-y-1.5">
              <div className="flex items-center gap-1">
                <label className="text-[10px] text-muted-foreground uppercase font-mono">
                  Trade Size %
                </label>
                <Tooltip>
                  <TooltipTrigger>
                    <AlertTriangle className="h-3 w-3 text-warning" />
                  </TooltipTrigger>
                  <TooltipContent side="top" className="max-w-[220px]">
                    <p className="text-xs">
                      Ensure this matches actual exchange balance. Values above 5% are blocked by the risk engine.
                    </p>
                  </TooltipContent>
                </Tooltip>
              </div>
              <Input
                type="number"
                min="0.5"
                max="5"
                step="0.5"
                value={newSizePercent}
                onChange={(e) => setNewSizePercent(e.target.value)}
                className="font-mono text-xs h-8 bg-muted/30"
              />
            </div>
          </div>

          <Button
            size="sm"
            variant="outline"
            onClick={() => updateBotSize.mutate()}
            disabled={updateBotSize.isPending || sizeUpdated}
            className={cn(
              "w-full font-mono text-xs",
              sizeUpdated && "border-profit/50 text-profit"
            )}
          >
            {updateBotSize.isPending ? (
              <><Loader2 className="h-3 w-3 mr-1.5 animate-spin" /> UPDATING...</>
            ) : sizeUpdated ? (
              <><CheckCircle2 className="h-3 w-3 mr-1.5" /> SIZE UPDATED</>
            ) : (
              <><Settings2 className="h-3 w-3 mr-1.5" /> APPLY NEW SIZING</>
            )}
          </Button>

          {sizeUpdated && (
            <Badge variant="outline" className="border-profit/50 text-profit text-[10px] font-mono w-full justify-center">
              <ShieldCheck className="h-3 w-3 mr-1" />
              Bot will use {newSizePercent}% sizing on next trade
            </Badge>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
