import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAIValidationStats, useAIDecisions, useAIPreview } from '@/hooks/api/useAIValidation';
import { Brain, CheckCircle, XCircle, AlertTriangle, Zap, TrendingUp, BarChart3 } from 'lucide-react';
import { useState } from 'react';

interface FactorScore {
  [key: string]: number;
}

interface AIDecision {
  timestamp: string;
  botId: string;
  symbol: string;
  side: string;
  exchange: string;
  decision: 'APPROVE' | 'REJECT';
  confidence: number;
  reason: string;
  latencyMs: number;
}

export function AIValidationPanel() {
  const { data: stats } = useAIValidationStats();
  const { data: decisions } = useAIDecisions(20);
  const previewMutation = useAIPreview();

  const [previewSymbol, setPreviewSymbol] = useState('BTCUSDT');
  const [previewSide, setPreviewSide] = useState('BUY');
  const [previewExchange, setPreviewExchange] = useState('BINANCE');

  const handlePreview = () => {
    previewMutation.mutate({
      symbol: previewSymbol,
      side: previewSide,
      timeframe: '5m',
      exchange: previewExchange,
    });
  };

  return (
    <div className="space-y-4">
      {/* Stats Overview */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Card className="bg-card border-border">
          <CardContent className="p-4 text-center">
            <div className="text-2xl font-bold text-primary">{stats?.totalApproved ?? 0}</div>
            <div className="text-xs text-muted-foreground flex items-center justify-center gap-1 mt-1">
              <CheckCircle className="h-3 w-3 text-green-500" /> Approved
            </div>
          </CardContent>
        </Card>
        <Card className="bg-card border-border">
          <CardContent className="p-4 text-center">
            <div className="text-2xl font-bold text-destructive">{stats?.totalRejected ?? 0}</div>
            <div className="text-xs text-muted-foreground flex items-center justify-center gap-1 mt-1">
              <XCircle className="h-3 w-3 text-red-500" /> Rejected
            </div>
          </CardContent>
        </Card>
        <Card className="bg-card border-border">
          <CardContent className="p-4 text-center">
            <div className="text-2xl font-bold text-accent-foreground">{stats?.approvalRate ?? 0}%</div>
            <div className="text-xs text-muted-foreground flex items-center justify-center gap-1 mt-1">
              <TrendingUp className="h-3 w-3" /> Approval Rate
            </div>
          </CardContent>
        </Card>
        <Card className="bg-card border-border">
          <CardContent className="p-4 text-center">
            <div className="text-2xl font-bold text-muted-foreground">{stats?.totalErrors ?? 0}</div>
            <div className="text-xs text-muted-foreground flex items-center justify-center gap-1 mt-1">
              <AlertTriangle className="h-3 w-3 text-yellow-500" /> Errors
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Preview Tool */}
      <Card className="bg-card border-border">
        <CardHeader className="pb-3">
          <CardTitle className="text-sm flex items-center gap-2">
            <Brain className="h-4 w-4 text-primary" />
            AI Signal Preview
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2 items-end">
            <Select value={previewSymbol} onValueChange={setPreviewSymbol}>
              <SelectTrigger className="w-[130px]"><SelectValue /></SelectTrigger>
              <SelectContent>
                {['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT'].map(s => (
                  <SelectItem key={s} value={s}>{s}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select value={previewSide} onValueChange={setPreviewSide}>
              <SelectTrigger className="w-[90px]"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="BUY">BUY</SelectItem>
                <SelectItem value="SELL">SELL</SelectItem>
              </SelectContent>
            </Select>
            <Select value={previewExchange} onValueChange={setPreviewExchange}>
              <SelectTrigger className="w-[120px]"><SelectValue /></SelectTrigger>
              <SelectContent>
                {['BINANCE', 'BYBIT', 'DELTA'].map(e => (
                  <SelectItem key={e} value={e}>{e}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button size="sm" onClick={handlePreview} disabled={previewMutation.isPending}>
              <Zap className="h-3 w-3 mr-1" />
              {previewMutation.isPending ? 'Analyzing...' : 'Analyze'}
            </Button>
          </div>

          {previewMutation.data && (
            <div className="mt-4 p-3 rounded-lg bg-muted/50 space-y-2">
              <div className="flex items-center justify-between">
                <Badge variant={previewMutation.data.decision === 'APPROVE' ? 'default' : 'destructive'}>
                  {previewMutation.data.decision}
                </Badge>
                <span className="text-sm font-mono">
                  Confidence: <span className="font-bold">{(previewMutation.data.confidence * 100).toFixed(1)}%</span>
                </span>
              </div>
              {previewMutation.data.factors && (
                <div className="grid grid-cols-3 gap-1.5 text-xs">
                  {Object.entries(previewMutation.data.factors as FactorScore).map(([key, value]) => {
                    const pct = value * 100;
                    const color = pct >= 70 ? 'text-green-500' : pct >= 40 ? 'text-yellow-500' : 'text-red-500';
                    return (
                      <div key={key} className="flex justify-between bg-background rounded px-2 py-1">
                        <span className="text-muted-foreground capitalize">{key}</span>
                        <span className={`font-mono font-medium ${color}`}>{pct.toFixed(0)}%</span>
                      </div>
                    );
                  })}
                </div>
              )}
              <p className="text-xs text-muted-foreground">{previewMutation.data.latencyMs}ms latency</p>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Recent Decisions */}
      <Card className="bg-card border-border">
        <CardHeader className="pb-3">
          <CardTitle className="text-sm flex items-center gap-2">
            <BarChart3 className="h-4 w-4 text-primary" />
            Recent AI Decisions
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-1.5 max-h-[300px] overflow-y-auto">
            {(decisions as AIDecision[] || []).length === 0 && (
              <p className="text-xs text-muted-foreground text-center py-4">No decisions yet. Start a bot to see AI validation.</p>
            )}
            {(decisions as AIDecision[] || []).map((d: AIDecision, i: number) => (
              <div key={i} className="flex items-center gap-2 text-xs py-1.5 border-b border-border last:border-0">
                {d.decision === 'APPROVE' ? (
                  <CheckCircle className="h-3.5 w-3.5 text-green-500 shrink-0" />
                ) : (
                  <XCircle className="h-3.5 w-3.5 text-red-500 shrink-0" />
                )}
                <span className="font-mono font-medium w-[70px]">{d.symbol}</span>
                <Badge variant="outline" className="text-[10px] px-1.5">{d.side}</Badge>
                <span className="text-muted-foreground truncate flex-1">{d.reason?.split('|')[0]}</span>
                <span className="font-mono text-muted-foreground">{(d.confidence * 100).toFixed(0)}%</span>
                <span className="text-muted-foreground">{d.latencyMs}ms</span>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
