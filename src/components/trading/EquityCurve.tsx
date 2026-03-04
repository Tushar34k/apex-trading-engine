import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

const generateEquityData = () => {
  const data = [];
  let equity = 10000;
  for (let i = 0; i < 90; i++) {
    equity += (Math.random() - 0.42) * 200;
    const date = new Date(2024, 0, i + 1);
    data.push({
      date: date.toLocaleDateString("en-US", { month: "short", day: "numeric" }),
      equity: Math.round(equity * 100) / 100,
    });
  }
  return data;
};

const data = generateEquityData();

export function EquityCurve() {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="text-sm font-semibold text-foreground mb-4">Equity Curve</h3>
      <ResponsiveContainer width="100%" height={200}>
        <AreaChart data={data}>
          <defs>
            <linearGradient id="equityGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="hsl(199 89% 48%)" stopOpacity={0.3} />
              <stop offset="100%" stopColor="hsl(199 89% 48%)" stopOpacity={0} />
            </linearGradient>
          </defs>
          <XAxis
            dataKey="date"
            tick={{ fill: "hsl(215 15% 55%)", fontSize: 10 }}
            axisLine={{ stroke: "hsl(220 14% 18%)" }}
            tickLine={false}
            interval={14}
          />
          <YAxis
            tick={{ fill: "hsl(215 15% 55%)", fontSize: 10 }}
            axisLine={false}
            tickLine={false}
            tickFormatter={(v) => `$${(v / 1000).toFixed(1)}k`}
            width={50}
          />
          <Tooltip
            contentStyle={{
              background: "hsl(220 18% 10%)",
              border: "1px solid hsl(220 14% 18%)",
              borderRadius: "6px",
              fontSize: "12px",
              color: "hsl(210 20% 92%)",
            }}
            formatter={(value: number) => [`$${value.toLocaleString()}`, "Equity"]}
          />
          <Area
            type="monotone"
            dataKey="equity"
            stroke="hsl(199 89% 48%)"
            strokeWidth={2}
            fill="url(#equityGrad)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
