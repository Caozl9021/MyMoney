import React, { useState } from 'react';
import type { MatrixCellDto } from '../../types/api';
import { formatYuan } from '../../lib/format';

const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const SLOTS = ['00–03', '03–06', '06–09', '09–12', '12–15', '15–18', '18–21', '21–24'];

export const WeekdayHourMatrix: React.FC<{
  cells: MatrixCellDto[];
  masked: boolean;
}> = ({ cells, masked }) => {
  const [hovered, setHovered] = useState<MatrixCellDto | null>(null);

  const cellW = 42;
  const cellH = 22;
  const gap = 4;
  const padL = 36;
  const padT = 22;
  const svgW = padL + 8 * (cellW + gap);
  const svgH = padT + 7 * (cellH + gap);

  return (
    <div className="relative space-y-2">
      <div className="min-h-6 flex items-center text-xs">
        <span className="mm-text-muted">颜色深度按消费金额强度归一</span>
      </div>

      {/* 悬浮提示为覆盖层，不参与文档流，避免出现/消失时推动矩阵造成抖动 */}
      {hovered && (
        <div className="pointer-events-none absolute right-0 top-0 z-10 mm-tooltip px-2.5 py-1 text-[11px] tabular-nums flex items-center gap-2 whitespace-nowrap">
          <span>
            {hovered.weekdayLabel} {hovered.slotLabel}
          </span>
          <span>{formatYuan(hovered.cents, masked)}</span>
          <span className="opacity-80">{hovered.count}笔</span>
        </div>
      )}

      <div className="overflow-x-auto">
        <svg
          viewBox={`0 0 ${svgW} ${svgH}`}
          className="w-full min-w-[360px] h-auto block select-none"
          role="img"
          aria-label="星期与消费时段分布矩阵"
        >
          {SLOTS.map((slot, sIdx) => (
            <text
              key={slot}
              x={padL + sIdx * (cellW + gap) + cellW / 2}
              y={13}
              textAnchor="middle"
              fontSize="9"
              fill="var(--text-muted)"
              className="tabular-nums"
            >
              {slot}
            </text>
          ))}

          {WEEKDAYS.map((wd, wIdx) => (
            <text
              key={wd}
              x={padL - 6}
              y={padT + wIdx * (cellH + gap) + cellH / 2 + 3}
              textAnchor="end"
              fontSize="10"
              fill="var(--text-muted)"
            >
              {wd}
            </text>
          ))}

          {cells.map((cell) => {
            const active =
              hovered?.weekday === cell.weekday && hovered?.slot === cell.slot;
            const opacity = cell.cents <= 0 ? 0.14 : Math.max(0.22, Math.min(1, 0.2 + cell.intensity * 0.8));
            const fill = cell.cents <= 0 ? 'var(--bg-subtle)' : 'var(--accent-primary)';
            return (
              <rect
                key={`${cell.weekday}-${cell.slot}`}
                x={padL + cell.slot * (cellW + gap)}
                y={padT + cell.weekday * (cellH + gap)}
                width={cellW}
                height={cellH}
                rx="4"
                fill={fill}
                fillOpacity={cell.cents <= 0 ? 1 : opacity}
                stroke={active ? 'var(--text-primary)' : undefined}
                strokeWidth={active ? 1.5 : 0}
                tabIndex={0}
                aria-label={`${cell.weekdayLabel} ${cell.slotLabel}: ${formatYuan(cell.cents, masked)} (${cell.count}笔)`}
                onMouseEnter={() => setHovered(cell)}
                onMouseLeave={() => setHovered(null)}
                onFocus={() => setHovered(cell)}
                onBlur={() => setHovered(null)}
              />
            );
          })}
        </svg>
      </div>
    </div>
  );
};
