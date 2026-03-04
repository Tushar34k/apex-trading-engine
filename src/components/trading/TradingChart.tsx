import { useEffect, useRef } from "react";
import { createChart, ColorType, IChartApi } from "lightweight-charts";

const generateCandleData = () => {
  const data = [];
  let time = new Date("2024-01-01").getTime() / 1000;
  let open = 42000;
  for (let i = 0; i < 200; i++) {
    const close = open + (Math.random() - 0.48) * 800;
    const high = Math.max(open, close) + Math.random() * 400;
    const low = Math.min(open, close) - Math.random() * 400;
    data.push({ time: time as any, open, high, low, close });
    open = close;
    time += 86400;
  }
  return data;
};

const generateMarkers = (data: any[]) => {
  const markers: any[] = [];
  for (let i = 30; i < data.length; i += Math.floor(Math.random() * 15 + 10)) {
    const isBuy = Math.random() > 0.4;
    markers.push({
      time: data[i].time,
      position: isBuy ? "belowBar" : "aboveBar",
      color: isBuy ? "#22c55e" : "#ef4444",
      shape: isBuy ? "arrowUp" : "arrowDown",
      text: isBuy ? "BUY" : "SELL",
    });
  }
  return markers;
};

export function TradingChart() {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

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

    const candleData = generateCandleData();
    const candleSeries = chart.addSeries({
      type: 'Candlestick',
      upColor: "#22c55e",
      downColor: "#ef4444",
      borderUpColor: "#22c55e",
      borderDownColor: "#ef4444",
      wickUpColor: "#22c55e80",
      wickDownColor: "#ef444480",
    } as any);
    candleSeries.setData(candleData);
    (candleSeries as any).setMarkers(generateMarkers(candleData));

    // EMA line
    const emaData = candleData.map((d, i) => {
      if (i < 20) return { time: d.time, value: d.close };
      const slice = candleData.slice(i - 20, i);
      const avg = slice.reduce((s, c) => s + c.close, 0) / 20;
      return { time: d.time, value: avg };
    });
    const emaSeries = chart.addSeries({ type: 'Line', color: "hsl(199 89% 48%)", lineWidth: 1 } as any);
    emaSeries.setData(emaData);

    chart.timeScale().fitContent();

    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        chart.applyOptions({ width: entry.contentRect.width });
      }
    });
    resizeObserver.observe(containerRef.current);

    return () => {
      resizeObserver.disconnect();
      chart.remove();
    };
  }, []);

  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden">
      <div className="flex items-center justify-between border-b border-border px-4 py-2.5">
        <div className="flex items-center gap-3">
          <span className="text-sm font-semibold text-foreground">BTC/USDT</span>
          <span className="font-mono text-xs text-profit">+2.34%</span>
        </div>
        <div className="flex items-center gap-2">
          {["1m", "5m", "15m", "1H", "4H", "1D"].map((tf) => (
            <button
              key={tf}
              className="px-2 py-1 text-xs text-muted-foreground hover:text-foreground hover:bg-surface-2 rounded transition-colors data-[active=true]:text-primary data-[active=true]:bg-primary/10"
              data-active={tf === "1H"}
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
