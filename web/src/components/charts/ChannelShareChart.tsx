import React from 'react';
import type { ChannelStatDto } from '../../types/api';
import { formatYuan } from '../../lib/format';
import { ColorDot } from '../UiPrimitives';

const CHANNEL_COLORS = [
  '#0F9E85',
  '#3B82F6',
  '#E67E22',
  '#8B5CF6',
  '#0E7490',
  '#D97706',
  '#64748B',
];

export const ChannelShareChart: React.FC<{
  items: ChannelStatDto[];
  masked: boolean;
  onSelectChannel?: (name: string) => void;
}> = ({ items, masked, onSelectChannel }) => {
  if (items.length === 0) {
    return <div className="py-8 text-center text-xs mm-text-muted">暂无记账来源记录</div>;
  }

  let cursor = 0;
  const segments = items.map((item, idx) => {
    const w = Math.max(0.5, item.ratio * 100);
    const x = cursor;
    cursor += w;
    return {
      ...item,
      x,
      w: Math.min(100 - x, w),
      color: CHANNEL_COLORS[idx % CHANNEL_COLORS.length],
    };
  });

  return (
    <div className="space-y-4">
      <svg
        viewBox="0 0 100 6"
        preserveAspectRatio="none"
        className="w-full h-2.5 rounded-full overflow-hidden block"
        role="img"
        aria-label="记账来源分布比例条"
      >
        <rect x="0" y="0" width="100" height="6" fill="var(--bg-subtle)" />
        {segments.map((seg) => (
          <rect
            key={seg.name}
            x={seg.x}
            y="0"
            width={seg.w}
            height="6"
            fill={seg.color}
          />
        ))}
      </svg>

      <div className="space-y-2">
        {segments.map((seg) => (
          <button
            key={seg.name}
            type="button"
            onClick={() => onSelectChannel?.(seg.name)}
            className="w-full flex items-center justify-between gap-2 text-xs py-1 px-1.5 -mx-1.5 rounded-lg hover:bg-[var(--bg-elevated)] transition-colors"
          >
            <span className="inline-flex items-center gap-2 min-w-0">
              <ColorDot color={seg.color} />
              <span className="font-medium mm-text-primary truncate">{seg.name}</span>
            </span>
            <span className="flex items-center gap-3 shrink-0 tabular-nums">
              <span className="mm-text-secondary">
                {seg.count}笔 ({(seg.ratio * 100).toFixed(1)}%)
              </span>
              <span className="font-medium mm-text-primary w-24 text-right">
                {formatYuan(seg.expenseCents, masked)}
              </span>
            </span>
          </button>
        ))}
      </div>
    </div>
  );
};
