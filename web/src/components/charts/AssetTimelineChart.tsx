import React, { useMemo, useState } from 'react';
import type { AssetAccountDto, AssetTimelinePointDto } from '../../types/api';
import { formatCompactYuan, formatYuan } from '../../lib/format';
import { ColorDot } from '../UiPrimitives';

const SERIES_PALETTE = [
  '#3B82F6',
  '#0E9B6E',
  '#E67E22',
  '#8B5CF6',
  '#E04F5F',
  '#0E7490',
];

export const AssetTimelineChart: React.FC<{
  timeline: AssetTimelinePointDto[];
  accounts: AssetAccountDto[];
  comparedIds: string[];
  onToggleCompare: (id: string) => void;
  masked: boolean;
}> = ({ timeline, accounts, comparedIds, onToggleCompare, masked }) => {
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  const width = 780;
  const height = 250;
  const padL = 62;
  const padR = 24;
  const padT = 18;
  const padB = 34;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const initializedAccounts = useMemo(
    () => accounts.filter((a) => a.initialized && !a.archived),
    [accounts],
  );

  const { minVal, maxVal } = useMemo(() => {
    const vals: number[] = [0];
    for (const pt of timeline) {
      vals.push(pt.netCents);
      for (const id of comparedIds) {
        vals.push(pt.accountBalances[id] ?? 0);
      }
    }
    let mn = Math.min(...vals);
    let mx = Math.max(...vals);
    if (mn === mx) mx = mn + 10_000;
    const span = mx - mn;
    return { minVal: mn - span * 0.06, maxVal: mx + span * 0.08 };
  }, [timeline, comparedIds]);

  if (timeline.length === 0) {
    return (
      <div className="h-[220px] flex items-center justify-center text-xs mm-text-muted">
        暂无可回放的资产流水历史
      </div>
    );
  }

  const n = timeline.length;
  const xAt = (i: number) => (n <= 1 ? padL + plotW / 2 : padL + (i / (n - 1)) * plotW);
  const yAt = (v: number) => {
    const ratio = (v - minVal) / Math.max(1, maxVal - minVal);
    return padT + plotH - ratio * plotH;
  };

  const netPoints = timeline.map((pt, i) => `${xAt(i).toFixed(1)},${yAt(pt.netCents).toFixed(1)}`).join(' ');
  const labelStep = n > 18 ? Math.ceil(n / 6) : n > 8 ? 2 : 1;
  const visibleTicks = new Set<number>();
  if (n > 0) {
    let lastX = -999;
    for (let i = 0; i < n; i += labelStep) {
      const cx = xAt(i);
      if (i < n - 1 && xAt(n - 1) - cx < 82) {
        continue;
      }
      if (cx - lastX >= 82 || i === 0) {
        visibleTicks.add(i);
        lastX = cx;
      }
    }
    visibleTicks.add(n - 1);
  }
  const activePt = hoverIdx !== null ? timeline[hoverIdx] : null;

  return (
    <div className="relative space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-lg mm-bg-accent-soft mm-text-accent font-medium">
            <ColorDot color="var(--accent-primary)" />
            <span>净资产走势</span>
          </span>
          {initializedAccounts.slice(0, 8).map((acc, idx) => {
            const active = comparedIds.includes(acc.id);
            const color = SERIES_PALETTE[idx % SERIES_PALETTE.length];
            return (
              <button
                key={acc.id}
                type="button"
                onClick={() => onToggleCompare(acc.id)}
                className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-lg border text-[11px] transition-colors ${
                  active
                    ? 'mm-bg-elevated border-[var(--border-strong)] mm-text-primary font-medium'
                    : 'border-transparent mm-bg-elevated mm-text-muted opacity-75 hover:opacity-100'
                }`}
              >
                <ColorDot color={color} size={7} />
                <span className="max-w-[96px] truncate">{acc.name}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 悬浮提示为覆盖层，不参与文档流，避免出现/消失时推动曲线造成抖动 */}
      {activePt && (
        <div className="pointer-events-none absolute right-0 top-0 z-10 mm-tooltip px-2.5 py-1 text-[11px] tabular-nums flex items-center gap-2.5 whitespace-nowrap">
          <span className="font-medium">{activePt.date}</span>
          <span>净资产 {formatYuan(activePt.netCents, masked)}</span>
          <span className="opacity-80">总资产 {formatYuan(activePt.assetsCents, masked)}</span>
          {activePt.debtsCents > 0 && (
            <span className="opacity-80">负债 {formatYuan(activePt.debtsCents, masked)}</span>
          )}
        </div>
      )}

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-auto block select-none"
        role="img"
        aria-label="历史净资产与多账户余额变化曲线"
      >
        {[0, 0.5, 1].map((t) => {
          const val = minVal + (maxVal - minVal) * t;
          const y = yAt(val);
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
                x={padL - 8}
                y={y + 4}
                textAnchor="end"
                fontSize="10"
                fill="var(--text-muted)"
                className="tabular-nums"
              >
                {formatCompactYuan(val, masked)}
              </text>
            </g>
          );
        })}

        {minVal < 0 && maxVal > 0 && (
          <line
            x1={padL}
            y1={yAt(0)}
            x2={width - padR}
            y2={yAt(0)}
            stroke="var(--border-strong)"
            strokeDasharray="4 4"
          />
        )}

        {comparedIds.map((accId) => {
          const idx = initializedAccounts.findIndex((a) => a.id === accId);
          if (idx < 0) return null;
          const color = SERIES_PALETTE[idx % SERIES_PALETTE.length];
          const pts = timeline
            .map((pt, i) => `${xAt(i).toFixed(1)},${yAt(pt.accountBalances[accId] ?? 0).toFixed(1)}`)
            .join(' ');
          return (
            <polyline
              key={accId}
              fill="none"
              stroke={color}
              strokeWidth="1.6"
              strokeOpacity="0.85"
              points={pts}
            />
          );
        })}

        {n > 1 && (
          <polyline
            fill="none"
            stroke="var(--accent-primary)"
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
            points={netPoints}
          />
        )}

        {timeline.map((pt, i) => {
          const cx = xAt(i);
          const cy = yAt(pt.netCents);
          const isHov = hoverIdx === i;
          const hitW = Math.max(8, plotW / Math.max(1, n));
          return (
            <g key={`${pt.date}-${i}`}>
              {visibleTicks.has(i) && (
                <text
                  x={cx}
                  y={height - 10}
                  textAnchor={i === n - 1 ? 'end' : i === 0 ? 'start' : 'middle'}
                  fontSize="10"
                  fill="var(--text-muted)"
                  className="tabular-nums"
                >
                  {pt.date}
                </text>
              )}

              {(isHov || n <= 1) && (
                <circle
                  cx={cx}
                  cy={cy}
                  r="4.5"
                  fill="var(--accent-primary)"
                  stroke="var(--bg-card)"
                  strokeWidth="2"
                />
              )}

              <rect
                x={cx - hitW / 2}
                y={padT}
                width={hitW}
                height={plotH}
                fill="transparent"
                tabIndex={0}
                aria-label={`${pt.date}: 净资产 ${formatYuan(pt.netCents, masked)}`}
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
