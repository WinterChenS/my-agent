import type { TokenTotalsRow } from "@/api/audit";

/** 千分位（图表与表格共用）。 */
export function fmt(value: number | null | undefined): string {
  const n = Number(value ?? 0);
  return n.toLocaleString("zh-CN");
}

/** 紧凑数字（1.2k / 3.4M），用于图表轴与条形右侧。 */
export function compact(value: number | null | undefined): string {
  const n = Number(value ?? 0);
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(Math.round(n));
}

/**
 * 横向条形分布：按某维度（模型 / Agent / 供应商）看用量占比。
 *
 * <p>刻意不引图表库：本项目"依赖不超过 1 个"的约束下，内联 SVG/CSS 条形足够表达，
 * 而且能把中文名称、占比、调用次数直接排在同一行 —— 比通用图表库更贴合审计场景。
 */
export function BreakdownBars({
  rows,
  labelOf,
  testId,
  emptyHint = "暂无数据",
}: {
  rows: TokenTotalsRow[];
  labelOf: (row: TokenTotalsRow) => string;
  testId?: string;
  emptyHint?: string;
}) {
  if (!rows || rows.length === 0) {
    return <p className="text-muted-foreground text-sm">{emptyHint}</p>;
  }
  const total = rows.reduce((sum, row) => sum + (Number(row.total_tokens) || 0), 0) || 1;
  const max = Math.max(...rows.map((row) => Number(row.total_tokens) || 0), 1);
  const maxCount = 12;
  const shown = rows.slice(0, maxCount);

  return (
    <div className="space-y-2" data-testid={testId}>
      {shown.map((row, index) => {
        const value = Number(row.total_tokens) || 0;
        const share = (value / total) * 100;
        const width = Math.max(2, Math.round((value / max) * 100));
        return (
          <div key={`${labelOf(row)}-${index}`} className="space-y-1">
            <div className="flex items-baseline gap-2 text-xs">
              <span className="min-w-0 flex-1 truncate font-medium" title={labelOf(row)}>
                {labelOf(row)}
              </span>
              {row.records != null && (
                <span className="text-muted-foreground shrink-0">{fmt(row.records)} 次</span>
              )}
              <span className="text-muted-foreground shrink-0 tabular-nums">
                {compact(value)}
              </span>
              <span className="w-12 shrink-0 text-right tabular-nums">{share.toFixed(1)}%</span>
            </div>
            <div className="bg-muted h-2 overflow-hidden rounded-full">
              <div
                className="bg-primary/70 h-full rounded-full"
                style={{ width: `${width}%` }}
                data-testid="breakdown-bar"
              />
            </div>
          </div>
        );
      })}
      {rows.length > maxCount && (
        <p className="text-muted-foreground text-[11px]">仅显示前 {maxCount} 项（共 {rows.length} 项）</p>
      )}
    </div>
  );
}

const DONUT_COLORS = [
  "var(--color-chart-1, #2563eb)",
  "var(--color-chart-2, #16a34a)",
  "var(--color-chart-3, #f59e0b)",
  "var(--color-chart-4, #db2777)",
  "var(--color-chart-5, #7c3aed)",
  "#0891b2",
];

/** 环形占比：用于按供应商/来源看结构（含图例）。 */
export function Donut({
  rows,
  labelOf,
  ariaLabel,
  testId,
}: {
  rows: TokenTotalsRow[];
  labelOf: (row: TokenTotalsRow) => string;
  ariaLabel: string;
  testId?: string;
}) {
  if (!rows || rows.length === 0) {
    return <p className="text-muted-foreground text-sm">暂无数据</p>;
  }
  const total = rows.reduce((sum, row) => sum + (Number(row.total_tokens) || 0), 0) || 1;
  const size = 148;
  const radius = 56;
  const stroke = 22;
  const center = size / 2;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;

  return (
    <div className="flex flex-wrap items-center gap-4" data-testid={testId}>
      <svg width={size} height={size} role="img" aria-label={ariaLabel} className="shrink-0">
        <g transform={`rotate(-90 ${center} ${center})`}>
          {rows.map((row, index) => {
            const value = Number(row.total_tokens) || 0;
            const fraction = value / total;
            const dash = fraction * circumference;
            const el = (
              <circle
                key={`${labelOf(row)}-${index}`}
                cx={center}
                cy={center}
                r={radius}
                fill="none"
                stroke={DONUT_COLORS[index % DONUT_COLORS.length]}
                strokeWidth={stroke}
                strokeDasharray={`${dash} ${circumference - dash}`}
                strokeDashoffset={-offset}
              >
                <title>{`${labelOf(row)}: ${fmt(value)} (${(fraction * 100).toFixed(1)}%)`}</title>
              </circle>
            );
            offset += dash;
            return el;
          })}
        </g>
        <text x={center} y={center - 4} textAnchor="middle" className="fill-foreground text-sm font-semibold">
          {compact(total)}
        </text>
        <text x={center} y={center + 14} textAnchor="middle" className="fill-muted-foreground text-[10px]">
          总 tokens
        </text>
      </svg>
      <ul className="min-w-[10rem] flex-1 space-y-1 text-xs">
        {rows.map((row, index) => {
          const value = Number(row.total_tokens) || 0;
          return (
            <li key={`${labelOf(row)}-legend-${index}`} className="flex items-center gap-2">
              <span
                className="size-2.5 shrink-0 rounded-full"
                style={{ background: DONUT_COLORS[index % DONUT_COLORS.length] }}
              />
              <span className="min-w-0 flex-1 truncate">{labelOf(row)}</span>
              <span className="text-muted-foreground shrink-0 tabular-nums">
                {((value / total) * 100).toFixed(1)}%
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/**
 * 按日趋势：柱形 + 折线（累计无关，折线用当日合计的走势）。
 *
 * <p>保留 `aria-label="按日 Token 趋势"` 与 `rect` 单元素 —— 浏览器级验收依赖这两个特征。
 */
export function DailyTrendChart({ rows }: { rows: TokenTotalsRow[] }) {
  if (!rows || rows.length === 0) {
    return <p className="text-muted-foreground text-sm">暂无数据</p>;
  }
  const max = Math.max(...rows.map((r) => Number(r.total_tokens) || 0), 1);
  const barWidth = 30;
  const gap = 12;
  const height = 140;
  const width = rows.length * (barWidth + gap) + gap;

  return (
    <div className="overflow-auto">
      <svg width={width} height={height + 30} role="img" aria-label="按日 Token 趋势">
        {[0.25, 0.5, 0.75, 1].map((ratio) => (
          <line
            key={ratio}
            x1={0}
            x2={width}
            y1={height - height * ratio}
            y2={height - height * ratio}
            className="stroke-border"
            strokeDasharray="3 4"
          />
        ))}
        {rows.map((row, index) => {
          const value = Number(row.total_tokens) || 0;
          const barHeight = Math.max(2, Math.round((value / max) * height));
          const x = gap + index * (barWidth + gap);
          const y = height - barHeight;
          const prompt = Number(row.prompt_tokens) || 0;
          const promptHeight = value > 0 ? Math.round((prompt / value) * barHeight) : 0;
          return (
            <g key={`${row.day}-${index}`}>
              <rect
                x={x}
                y={y}
                width={barWidth}
                height={barHeight}
                className="fill-primary/35"
                rx={3}
              />
              {/* 输入部分叠深色，直观看出"输入/输出"比例 */}
              <rect
                x={x}
                y={y + barHeight - promptHeight}
                width={barWidth}
                height={promptHeight}
                className="fill-primary"
                rx={3}
              />
              <text
                x={x + barWidth / 2}
                y={height + 14}
                textAnchor="middle"
                className="fill-muted-foreground text-[9px]"
              >
                {String(row.day ?? "").slice(5)}
              </text>
              <text
                x={x + barWidth / 2}
                y={y - 4}
                textAnchor="middle"
                className="fill-muted-foreground text-[9px]"
              >
                {compact(value)}
              </text>
              <title>{`${row.day}: 合计 ${fmt(value)}（输入 ${fmt(prompt)}）`}</title>
            </g>
          );
        })}
      </svg>
      <p className="text-muted-foreground mt-1 text-[11px]">
        深色为输入 tokens，浅色为输出；柱顶为当日合计
      </p>
    </div>
  );
}
