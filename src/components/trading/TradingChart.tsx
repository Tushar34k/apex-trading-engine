import { useEffect, useRef, useState } from "react";
import { createChart, ColorType, IChartApi, CandlestickSeries, LineSeries, createSeriesMarkers } from "lightweight-charts";
import { useCandles, useSupportResistance } from "@/hooks/api/useMarket";
import { useTrades } from "@/hooks/api/useTrades";
import { wsClient } from "@/lib/ws";
import type { WebSocketEvent, CandleData } from "@/types";

interface TradingChartProps {
  symbol?: string;
  timeframe?: string;
}

export function TradingChart({ symbol = "BTCUSDT", timeframe: initialTimeframe = "1H" }: TradingChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [timeframe, setTimeframe] = useState(initialTimeframe);

  const { data: candles } = useCandles(symbol, timeframe, 200);
  const { data: tradesList } = useTrades({ symbol });
  const { data: srLevels } = useSupportResistance(symbol, timeframe);

  useEffect(() => {
    if (!containerRef.current || !candles?.length) return;

    const chart = createChart(containerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: "hsl(215 15% 55%)",
        fontSize: 11,
        fontFamily: "JetBrains Mono, monospace",
      },
      grid: {
        vertLines: { color: "hsl(220 14% 14%)" },
        horzLines: { color: "hsl(220 14% 14%)" },
      },
      crosshair: {
        vertLine: { color: "hsl(199 89% 48% / 0.3)", width: 1, style: 2 },
        horzLine: { color: "hsl(199 89% 48% / 0.3)", width: 1, style: 2 },
      },
      rightPriceScale: { borderColor: "hsl(220 14% 18%)" },
      timeScale: { borderColor: "hsl(220 14% 18%)" },
      width: containerRef.current.clientWidth,
      height: 420,
    });

    chartRef.current = chart;

    const chartData = candles.map((c: CandleData) => ({
      time: c.time as any,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }));

    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: "#22c55e",
      downColor: "#ef4444",
      borderUpColor: "#22c55e",
      borderDownColor: "#ef4444",
      wickUpColor: "#22c55e80",
      wickDownColor: "#ef444480",
    });
    candleSeries.setData(chartData);

    // Trade markers — entry/exit arrows with tooltips
    if (tradesList?.length) {
      const markers: any[] = [];
      tradesList.forEach((t) => {
        // Entry marker
        if (t.openedAt) {
          markers.push({
            time: (new Date(t.openedAt).getTime() / 1000) as any,
            position: t.side === 'LONG' ? 'belowBar' as const : 'aboveBar' as const,
            color: '#22c55e',
            shape: 'arrowUp' as const,
            text: `▲ ${t.strategyName}\n${t.side} @ $${t.entryPrice?.toLocaleString()}\nQty: ${t.quantity}`,
          });
        }
        // Exit marker
        if (t.closedAt && t.exitPrice) {
          markers.push({
            time: (new Date(t.closedAt).getTime() / 1000) as any,
            position: t.side === 'LONG' ? 'aboveBar' as const : 'belowBar' as const,
            color: '#ef4444',
            shape: 'arrowDown' as const,
            text: `▼ EXIT ${t.strategyName}\n@ $${t.exitPrice?.toLocaleString()}\nPnL: ${t.pnl != null ? (t.pnl >= 0 ? '+' : '') + t.pnl.toFixed(2) : '—'}`,
          });
        }
      });

      markers.sort((a, b) => (a.time as number) - (b.time as number));
      if (markers.length) {
        createSeriesMarkers(candleSeries, markers);
      }
    }

    // Support/Resistance horizontal lines
    if (srLevels?.length) {
      srLevels.forEach((level) => {
        const lineSeries = chart.addSeries(LineSeries, {
          color: level.type === 'support' ? '#22c55e40' : '#ef444440',
          lineWidth: 1,
          lineStyle: 2, // dashed
          priceLineVisible: false,
          lastValueVisible: false,
          crosshairMarkerVisible: false,
        });
        // Draw horizontal line across the whole chart
        if (chartData.length >= 2) {
          lineSeries.setData([
            { time: chartData[0].time, value: level.price },
            { time: chartData[chartData.length - 1].time, value: level.price },
          ]);
        }
      });
    }

    // EMA overlay
    if (chartData.length >= 21) {
      const ema9Data = calculateEMA(chartData, 9);
      const ema21Data = calculateEMA(chartData, 21);
      
      const ema9Series = chart.addSeries(LineSeries, { color: "hsl(38 92% 50%)", lineWidth: 1 });
      ema9Series.setData(ema9Data);
      
      const ema21Series = chart.addSeries(LineSeries, { color: "hsl(199 89% 48%)", lineWidth: 1 });
      ema21Series.setData(ema21Data);
    }

    chart.timeScale().fitContent();

    // Live candle updates via WS
    const unsubWs = wsClient.subscribeMarket(symbol, (event: WebSocketEvent) => {
      if (event.type === 'PRICE_UPDATE') {
        // Could update the last candle in real-time here
      }
    });

    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        chart.applyOptions({ width: entry.contentRect.width });
      }
    });
    resizeObserver.observe(containerRef.current);

    return () => {
      unsubWs();
      resizeObserver.disconnect();
      chart.remove();
    };
  }, [candles, tradesList, srLevels, symbol]);

  const displaySymbol = symbol.replace('USDT', '/USDT');
  const lastCandle = candles?.[candles.length - 1];
  const prevCandle = candles?.[candles.length - 2];
  const changePct = lastCandle && prevCandle
    ? (((lastCandle.close - prevCandle.close) / prevCandle.close) * 100).toFixed(2)
    : null;

  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden">
      <div className="flex items-center justify-between border-b border-border px-4 py-2.5">
        <div className="flex items-center gap-3">
          <span className="text-sm font-semibold text-foreground">{displaySymbol}</span>
          {changePct && (
            <span className={`font-mono text-xs ${Number(changePct) >= 0 ? 'text-profit' : 'text-loss'}`}>
              {Number(changePct) >= 0 ? '+' : ''}{changePct}%
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {["1m", "5m", "15m", "1H", "4H", "1D"].map((tf) => (
            <button
              key={tf}
              onClick={() => setTimeframe(tf)}
              className="px-2 py-1 text-xs text-muted-foreground hover:text-foreground hover:bg-surface-2 rounded transition-colors data-[active=true]:text-primary data-[active=true]:bg-primary/10"
              data-active={tf === timeframe}
            >
              {tf}
            </button>
          ))}
        </div>
      </div>
      <div ref={containerRef} />
    </div>
  );
}

function calculateEMA(data: { time: any; close: number }[], period: number) {
  const multiplier = 2 / (period + 1);
  const result: { time: any; value: number }[] = [];
  
  let sum = 0;
  for (let i = 0; i < period && i < data.length; i++) {
    sum += data[i].close;
  }
  let ema = sum / period;
  
  for (let i = period; i < data.length; i++) {
    ema = (data[i].close - ema) * multiplier + ema;
    result.push({ time: data[i].time, value: ema });
  }
  
  return result;
}
