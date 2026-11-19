import React, { useMemo, useState } from 'react';
import type { AnnualFlowBucketDto } from '../../types/api';
import { formatCompactYuan, formatYuan } from '../../lib/format';

export const AnnualFlowChart: React.FC<{
  buckets: AnnualFlowBucketDto[];
  masked: boolean;
  onSelectPeriod?: (start: string, end: string) => void;
}> = ({ buckets, masked, onSelectPeriod }) => {
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  const width = 760;
  const height = 248;
  const padL = 56;
  const padR = 16;
  const padT = 18;
  const padB = 34;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const { maxVal } = useMemo(() => {
    const vals = buckets.flatMap((b) => [b.expenseCents, b.incomeCents]);
    return { maxVal: Math.max(10_000, ...vals) * 1.12 };
  }, [buckets]);

  if (buckets.length === 0) {
    return (
      <div className="h-[220px] flex items-center justify-center text-xs mm-text-muted">
        暂无近12周期数据
      </div>
    );
  }

  const slotW = plotW / buckets.length;
  const barW = Math.min(18, Math.max(6, slotW * 0.28));
  const yAt = (v: number) => padT + plotH - (Math.max(0, v) / maxVal) * plotH;
  const baseY = padT + plotH;

  const active = hoverIdx !== null ? buckets[hoverIdx] : null;
  const yTicks = [0, 0.5, 1].map((t) => ({
    val: maxVal * t,
    y: yAt(maxVal * t),
  }));

  return (
    <div className="relative">
      <div className="flex flex-wrap items-center gap-2 mb-2 text-xs">
        <div className="flex items-center gap-4 mm-text-secondary">
          <span className="inline-flex items-center gap-1.5">
            <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
              <rect width="10" height="10" rx="2" fill="var(--color-income)" />
            </svg>
            <span>收入</span>
          </span>
          <span className="inline-flex items-center gap-1.5">
            <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
              <rect width="10" height="10" rx="2" fill="var(--color-expense)" />
            </svg>
            <span>净支出</span>
          </span>
        </div>
      </div>

      {/* 悬浮提示为覆盖层，不参与文档流，避免出现/消失时推动柱状图造成抖动 */}
      {active && (
        <div className="pointer-events-none absolute right-0 top-0 z-10 mm-tooltip px-2.5 py-1 text-[11px] tabular-nums flex items-center gap-2.5 whitespace-nowrap">
          <span className="font-medium">
            {active.start} ~ {active.end}
          </span>
          <span>收入 {formatYuan(active.incomeCents, masked)}</span>
          <span>支出 {formatYuan(active.expenseCents, masked)}</span>
          <span>结余 {formatYuan(active.balanceCents, masked)}</span>
        </div>
      )}

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-auto block select-none"
        role="img"
        aria-label="近12周期收入与支出对比柱状图"
      >
        {yTicks.map((tk, i) => (
          <g key={i}>
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

        {buckets.map((b, i) => {
          const cx = padL + slotW * i + slotW / 2;
          const incH = Math.max(0, baseY - yAt(b.incomeCents));
          const expH = Math.max(0, baseY - yAt(b.expenseCents));
          const hovered = hoverIdx === i;

          return (
            <g key={b.key}>
              {hovered && (
                <rect
                  x={padL + slotW * i + 2}
                  y={padT}
                  width={Math.max(4, slotW - 4)}
                  height={plotH}
                  rx="6"
                  fill="var(--bg-elevated)"
                />
              )}

              <rect
                x={cx - barW - 2}
                y={baseY - incH}
                width={barW}
                height={incH}
                rx="3"
                fill="var(--color-income)"
                fillOpacity={b.isPartial ? 0.55 : 0.9}
                stroke={b.isPartial ? 'var(--color-income)' : undefined}
                strokeDasharray={b.isPartial ? '2 2' : undefined}
              />

              <rect
                x={cx + 2}
                y={baseY - expH}
                width={barW}
                height={expH}
                rx="3"
                fill="var(--color-expense)"
                fillOpacity={b.isPartial ? 0.55 : 0.9}
                stroke={b.isPartial ? 'var(--color-expense)' : undefined}
                strokeDasharray={b.isPartial ? '2 2' : undefined}
              />

              <text
                x={cx}
                y={height - 10}
                textAnchor="middle"
                fontSize="10"
                fill="var(--text-muted)"
                className="tabular-nums"
              >
                {b.label}
              </text>

              <rect
                x={padL + slotW * i}
                y={padT}
                width={slotW}
                height={plotH}
                fill="transparent"
                tabIndex={0}
                role="button"
                aria-label={`${b.label}: 收入${formatYuan(b.incomeCents, masked)}, 支出${formatYuan(b.expenseCents, masked)}`}
                onMouseEnter={() => setHoverIdx(i)}
                onMouseLeave={() => setHoverIdx(null)}
                onFocus={() => setHoverIdx(i)}
                onBlur={() => setHoverIdx(null)}
                onClick={() => onSelectPeriod?.(b.start, b.end)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSelectPeriod?.(b.start, b.end);
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
