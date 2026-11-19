import React, { useMemo, useState } from 'react';
import type { HeatmapDayDto } from '../../types/api';
import { formatYuan } from '../../lib/format';

const TIER_FILLS = [
  'var(--heat-0)',
  'var(--heat-1)',
  'var(--heat-2)',
  'var(--heat-3)',
  'var(--heat-4)',
];

const WD_LABELS = ['一', '', '三', '', '五', '', '日'];

export const CalendarHeatmap: React.FC<{
  days: HeatmapDayDto[];
  masked: boolean;
  onSelectDay?: (date: string) => void;
}> = ({ days, masked, onSelectDay }) => {
  const [active, setActive] = useState<HeatmapDayDto | null>(null);

  const { columns, monthMarkers } = useMemo(() => {
    if (days.length === 0) return { columns: [], monthMarkers: [] };
    const padBefore = days[0].weekday; // 0..6 (Mon..Sun)
    const padded: (HeatmapDayDto | null)[] = [
      ...Array.from({ length: padBefore }, () => null),
      ...days,
    ];
    const cols: (HeatmapDayDto | null)[][] = [];
    for (let i = 0; i < padded.length; i += 7) {
      cols.push(padded.slice(i, i + 7));
    }

    const markers: { col: number; label: string }[] = [];
    let lastYm = '';
    cols.forEach((col, colIdx) => {
      const firstReal = col.find((c) => c !== null);
      if (!firstReal) return;
      const ym = firstReal.date.slice(0, 7);
      if (ym !== lastYm) {
        lastYm = ym;
        const mNum = Number(firstReal.date.slice(5, 7));
        markers.push({ col: colIdx, label: `${mNum}月` });
      }
    });

    return { columns: cols, monthMarkers: markers };
  }, [days]);

  if (days.length === 0) {
    return <div className="py-8 text-center text-xs mm-text-muted">暂无日历消费数据</div>;
  }

  const cellSize = 11;
  const gap = 3;
  const step = cellSize + gap;
  const padL = 22;
  const padT = 18;
  const svgW = padL + columns.length * step + 6;
  const svgH = padT + 7 * step + 4;

  return (
    <div className="relative space-y-2.5">
      <div className="flex items-center text-xs min-h-6">
        <div className="flex items-center gap-1.5 mm-text-muted">
          <span>低</span>
          {TIER_FILLS.map((fill, idx) => (
            <svg key={idx} width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
              <rect width="10" height="10" rx="2" fill={fill} />
            </svg>
          ))}
          <span>高</span>
        </div>
      </div>

      {/* 悬浮提示为覆盖层，不参与文档流，避免出现/消失时推动日历造成抖动 */}
      {active && (
        <div className="pointer-events-none absolute right-0 top-0 z-10 mm-tooltip px-2.5 py-1 text-[11px] tabular-nums flex items-center gap-2 max-w-full">
          <span className="font-medium whitespace-nowrap">{active.date}</span>
          <span className="whitespace-nowrap">{formatYuan(active.cents, masked)}</span>
          <span className="opacity-80 whitespace-nowrap">{active.count}笔</span>
          {active.topCategories.length > 0 && (
            <span className="opacity-85 truncate">
              ({active.topCategories.map((c) => c.name).join(' · ')})
            </span>
          )}
        </div>
      )}

      <div className="overflow-x-auto pb-1">
        <svg
          viewBox={`0 0 ${svgW} ${svgH}`}
          className="w-full min-w-[660px] h-auto block select-none"
          role="img"
          aria-label="消费日历热力图"
        >
          {WD_LABELS.map((lbl, rowIdx) =>
            lbl ? (
              <text
                key={rowIdx}
                x={0}
                y={padT + rowIdx * step + 9}
                fontSize="9"
                fill="var(--text-muted)"
              >
                {lbl}
              </text>
            ) : null,
          )}

          {monthMarkers.map((mk) => (
            <text
              key={`${mk.col}-${mk.label}`}
              x={padL + mk.col * step}
              y={11}
              fontSize="9"
              fill="var(--text-muted)"
            >
              {mk.label}
            </text>
          ))}

          {columns.map((col, colIdx) =>
            col.map((cell, rowIdx) => {
              if (!cell) return null;
              const fill = TIER_FILLS[Math.max(0, Math.min(4, cell.tier))];
              const isSelected = active?.date === cell.date;
              return (
                <rect
                  key={cell.date}
                  x={padL + colIdx * step}
                  y={padT + rowIdx * step}
                  width={cellSize}
                  height={cellSize}
                  rx="2.5"
                  fill={fill}
                  stroke={
                    isSelected
                      ? 'var(--text-primary)'
                      : cell.inRange
                        ? 'var(--border-strong)'
                        : undefined
                  }
                  strokeWidth={isSelected ? 1.4 : cell.inRange ? 0.6 : 0}
                  tabIndex={0}
                  role="button"
                  aria-label={`${cell.date} 支出 ${formatYuan(cell.cents, masked)} ${cell.count}笔`}
                  onMouseEnter={() => setActive(cell)}
                  onMouseLeave={() => setActive(null)}
                  onFocus={() => setActive(cell)}
                  onBlur={() => setActive(null)}
                  onClick={() => onSelectDay?.(cell.date)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onSelectDay?.(cell.date);
                    }
                  }}
                />
              );
            }),
          )}
        </svg>
      </div>
    </div>
  );
};
