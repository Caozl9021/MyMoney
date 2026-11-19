import React, { useState } from 'react';
import type { AmountBucketDto } from '../../types/api';
import { formatCompactYuan, formatYuan } from '../../lib/format';

export const AmountHistogram: React.FC<{
  buckets: AmountBucketDto[];
  masked: boolean;
}> = ({ buckets, masked }) => {
  const [mode, setMode] = useState<'count' | 'cents'>('count');
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  const width = 460;
  const height = 210;
  const padL = 44;
  const padR = 12;
  const padT = 16;
  const padB = 28;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const vals = buckets.map((b) => (mode === 'count' ? b.count : b.cents));
  const maxVal = Math.max(1, ...vals) * 1.15;
  const slotW = plotW / Math.max(1, buckets.length);
  const barW = Math.min(34, slotW * 0.56);
  const active = hoverIdx !== null ? buckets[hoverIdx] : null;

  return (
    <div className="relative space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <div className="inline-flex rounded-lg p-0.5 mm-bg-elevated border mm-divider">
          <button
            type="button"
            onClick={() => setMode('count')}
            className={`px-2 py-0.5 rounded-md text-[11px] font-medium ${
              mode === 'count' ? 'bg-[var(--bg-card)] mm-text-accent' : 'mm-text-secondary'
            }`}
          >
            按笔数
          </button>
          <button
            type="button"
            onClick={() => setMode('cents')}
            className={`px-2 py-0.5 rounded-md text-[11px] font-medium ${
              mode === 'cents' ? 'bg-[var(--bg-card)] mm-text-accent' : 'mm-text-secondary'
            }`}
          >
            按金额
          </button>
        </div>
      </div>

      {/* 悬浮提示为覆盖层，不参与文档流，避免出现/消失时推动柱状图造成抖动 */}
      {active && (
        <div className="pointer-events-none absolute right-0 top-0 z-10 mm-tooltip px-2 py-0.5 text-[11px] tabular-nums whitespace-nowrap">
          {active.label}: {active.count}笔 · {formatYuan(active.cents, masked)}
        </div>
      )}

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-auto block select-none"
        role="img"
        aria-label="单笔金额分布直方图"
      >
        {[0, 0.5, 1].map((t) => {
          const val = maxVal * t;
          const y = padT + plotH - t * plotH;
          return (
            <g key={t}>
              <line
                x1={padL}
                y1={y}
                x2={width - padR}
                y2={y}
                stroke="var(--border-hairline)"
                strokeDasharray="3 3"
              />
              <text
                x={padL - 6}
                y={y + 3}
                textAnchor="end"
                fontSize="9"
                fill="var(--text-muted)"
                className="tabular-nums"
              >
                {mode === 'count' ? Math.round(val) : formatCompactYuan(val, masked)}
              </text>
            </g>
          );
        })}

        {buckets.map((b, i) => {
          const val = mode === 'count' ? b.count : b.cents;
          const h = (val / maxVal) * plotH;
          const cx = padL + slotW * i + slotW / 2;
          const y = padT + plotH - h;
          const isHov = hoverIdx === i;
          return (
            <g key={b.label}>
              <rect
                x={cx - barW / 2}
                y={y}
                width={barW}
                height={h}
                rx="4"
                fill="var(--accent-primary)"
                fillOpacity={isHov ? 1 : 0.8}
              />
              <text
                x={cx}
                y={height - 8}
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
                aria-label={`${b.label}: ${b.count}笔 ${formatYuan(b.cents, masked)}`}
                onMouseEnter={() => setHoverIdx(i)}
                onMouseLeave={() => setHoverIdx(null)}
                onFocus={() => setHoverIdx(i)}
                onBlur={() => setHoverIdx(null)}
              />
            </g>
          );
        })}
      </svg>
    </div>
  );
};
