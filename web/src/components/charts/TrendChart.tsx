import React, { useMemo, useState } from 'react';
import type { TrendBucketDto } from '../../types/api';
import { formatCompactYuan, formatYuan } from '../../lib/format';

export type TrendMetric = 'expense' | 'income' | 'balance';

export const TrendChart: React.FC<{
  series: TrendBucketDto[];
  metric: TrendMetric;
  masked: boolean;
  onSelectRange?: (start: string, end: string) => void;
}> = ({ series, metric, masked, onSelectRange }) => {
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  // 未结束的会计周期里，未来日期不应被当作 0 支出来画，否则曲线尾部会被误读成“最近几天没记账”。
  const visibleSeries = useMemo(() => {
    const elapsed = series.filter((d) => !d.isFuture);
    return elapsed.length > 0 ? elapsed : series;
  }, [series]);

  const width = 760;
  const height = 248;
  const padL = 56;
  const padR = 16;
  const padT = 18;
  const padB = 34;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const { minVal, maxVal, activeVals, prevVals } = useMemo(() => {
    const act = visibleSeries.map((d) =>
      metric === 'expense' ? d.expenseCents : metric === 'income' ? d.incomeCents : d.balanceCents,
    );
    const prv = visibleSeries.map((d) =>
      metric === 'expense' ? d.prevExpenseCents : metric === 'income' ? d.prevIncomeCents : 0,
    );
    const combined = metric === 'balance' ? [...act, 0] : [...act, ...prv, 0];
    let mn = Math.min(...combined);
    let mx = Math.max(...combined);
    if (mn === mx) {
      mx = mn + 10_000;
    }
    if (metric !== 'balance') {
      mn = 0;
    }
    return { minVal: mn, maxVal: mx * 1.08 - (mn < 0 ? 0 : 0), activeVals: act, prevVals: prv };
  }, [visibleSeries, metric]);

  if (visibleSeries.length === 0) {
    return (
      <div className="h-[220px] flex items-center justify-center text-xs mm-text-muted">
        当前时间范围暂无趋势数据
      </div>
    );
  }

  const n = visibleSeries.length;
  const xAt = (i: number) => (n <= 1 ? padL + plotW / 2 : padL + (i / (n - 1)) * plotW);
  const yAt = (v: number) => {
    const ratio = (v - minVal) / Math.max(1, maxVal - minVal);
    return padT + plotH - ratio * plotH;
  };

  const mainStroke =
    metric === 'expense'
      ? 'var(--color-expense)'
      : metric === 'income'
        ? 'var(--color-income)'
        : 'var(--accent-primary)';
  const fillSoft =
    metric === 'expense'
      ? 'var(--color-expense-soft)'
      : metric === 'income'
        ? 'var(--color-income-soft)'
        : 'var(--accent-primary-soft)';

  const mainPoints = activeVals.map((v, i) => `${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`).join(' ');
  const prevPoints = prevVals.map((v, i) => `${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`).join(' ');
  const baseY = yAt(Math.max(0, minVal));
  const areaPath =
    n > 1
      ? `M ${xAt(0).toFixed(1)},${baseY.toFixed(1)} L ${activeVals
          .map((v, i) => `${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`)
          .join(' L ')} L ${xAt(n - 1).toFixed(1)},${baseY.toFixed(1)} Z`
      : '';

  const yTicks = [0, 0.5, 1].map((t) => {
    const val = minVal + (maxVal - minVal) * t;
    return { y: yAt(val), val };
  });

  const labelStep = n > 20 ? 5 : n > 10 ? 2 : 1;
  const activeItem = hoverIdx !== null ? visibleSeries[hoverIdx] : null;
  const activeValue = hoverIdx !== null ? activeVals[hoverIdx] : 0;
  const prevValue = hoverIdx !== null ? prevVals[hoverIdx] : 0;

  return (
    <div className="relative">
      <div className="flex items-center justify-between gap-2 mb-2 text-xs">
        <div className="flex items-center gap-4 mm-text-secondary">
          <span className="inline-flex items-center gap-1.5">
            <svg width="16" height="4" viewBox="0 0 16 4" aria-hidden="true">
              <line x1="0" y1="2" x2="16" y2="2" stroke={mainStroke} strokeWidth="2.5" />
            </svg>
            <span>本期</span>
          </span>
          {metric !== 'balance' && (
            <span className="inline-flex items-center gap-1.5">
              <svg width="16" height="4" viewBox="0 0 16 4" aria-hidden="true">
                <line
                  x1="0"
                  y1="2"
                  x2="16"
                  y2="2"
                  stroke="var(--text-muted)"
                  strokeWidth="1.8"
                  strokeDasharray="3 3"
                />
              </svg>
              <span>上期对照</span>
            </span>
          )}
        </div>
      </div>

      {/* 悬浮提示改为绝对定位覆盖层，不参与文档流，避免出现/消失时推动图表造成抖动 */}
      {activeItem && (
        <div className="pointer-events-none absolute right-0 top-0 z-10 mm-tooltip px-2.5 py-1 text-[11px] tabular-nums flex items-center gap-2.5 whitespace-nowrap">
          <span className="font-medium">
            {activeItem.start === activeItem.end
              ? activeItem.start
              : `${activeItem.start} ~ ${activeItem.end}`}
          </span>
          <span>本期 {formatYuan(activeValue, masked)}</span>
          {metric !== 'balance' && (
            <span className="opacity-80">上期 {formatYuan(prevValue, masked)}</span>
          )}
          <span className="opacity-80">{activeItem.count}笔</span>
        </div>
      )}

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-auto block select-none"
        role="img"
        aria-label="收支趋势对比图"
      >
        {yTicks.map((tk, idx) => (
          <g key={idx}>
            <line
              x1={padL}
              y1={tk.y}
              x2={width - padR}
              y2={tk.y}
              stroke="var(--border-hairline)"
              strokeDasharray="3 3"
            />
            <text
              x={padL - 8}
              y={tk.y + 4}
              textAnchor="end"
              fontSize="10"
              fill="var(--text-muted)"
              className="tabular-nums"
            >
              {formatCompactYuan(tk.val, masked)}
            </text>
          </g>
        ))}

        {minVal < 0 && (
          <line
            x1={padL}
            y1={yAt(0)}
            x2={width - padR}
            y2={yAt(0)}
            stroke="var(--border-strong)"
          />
        )}

        {metric !== 'balance' && n > 1 && (
          <polyline
            fill="none"
            stroke="var(--text-muted)"
            strokeWidth="1.5"
            strokeDasharray="4 4"
            points={prevPoints}
            opacity="0.75"
          />
        )}

        {areaPath && <path d={areaPath} fill={fillSoft} opacity="0.65" />}

        {n > 1 && (
          <polyline
            fill="none"
            stroke={mainStroke}
            strokeWidth="2.2"
            strokeLinejoin="round"
            strokeLinecap="round"
            points={mainPoints}
          />
        )}

        {visibleSeries.map((d, i) => {
          const cx = xAt(i);
          const cy = yAt(activeVals[i]);
          const hovered = hoverIdx === i;
          const hitW = Math.max(8, plotW / Math.max(1, n));
          return (
            <g key={d.key}>
              {(i === n - 1 || (i % labelStep === 0 && (n - 1 - i) >= Math.max(2, Math.ceil(labelStep * 0.6)))) && (
                <text
                  x={cx}
                  y={height - 10}
                  textAnchor={i === n - 1 ? 'end' : i === 0 ? 'start' : 'middle'}
                  fontSize="10"
                  fill="var(--text-muted)"
                  className="tabular-nums"
                >
                  {d.label}
                </text>
              )}

              {(hovered || d.isPartial || n <= 1) && (
                <circle
                  cx={cx}
                  cy={cy}
                  r={hovered ? 4.5 : 3.2}
                  fill={d.isPartial ? 'var(--bg-card)' : mainStroke}
                  stroke={mainStroke}
                  strokeWidth="2"
                  strokeDasharray={d.isPartial ? '2 2' : undefined}
                />
              )}

              {hovered && (
                <line
                  x1={cx}
                  y1={padT}
                  x2={cx}
                  y2={padT + plotH}
                  stroke="var(--border-strong)"
                  strokeDasharray="2 2"
                />
              )}

              <rect
                x={cx - hitW / 2}
                y={padT}
                width={hitW}
                height={plotH}
                fill="transparent"
                tabIndex={0}
                role="button"
                aria-label={`${d.label}: ${formatYuan(activeVals[i], masked)}`}
                onMouseEnter={() => setHoverIdx(i)}
                onMouseLeave={() => setHoverIdx(null)}
                onFocus={() => setHoverIdx(i)}
                onBlur={() => setHoverIdx(null)}
                onClick={() => onSelectRange?.(d.start, d.end)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSelectRange?.(d.start, d.end);
                  }
                }}
              />
            </g>
          );
        })}
      </svg>
    </div>
  );
};
