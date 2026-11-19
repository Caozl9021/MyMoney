import React from 'react';
import type { RankItemDto } from '../../types/api';
import { formatYuan } from '../../lib/format';
import { ColorDot, DeltaBadge, RatioBar } from '../UiPrimitives';

export const CategoryRankList: React.FC<{
  items: RankItemDto[];
  masked: boolean;
  maxRows?: number;
  onItemClick?: (item: RankItemDto) => void;
}> = ({ items, masked, maxRows = 8, onItemClick }) => {
  const visible = items.slice(0, maxRows);

  if (visible.length === 0) {
    return (
      <div className="py-10 text-center text-xs mm-text-muted">当前周期暂无支出构成</div>
    );
  }

  return (
    <div className="space-y-3">
      {visible.map((item) => (
        <button
          key={`${item.category ?? ''}-${item.name}`}
          type="button"
          onClick={() => onItemClick?.(item)}
          className="w-full text-left group space-y-1.5 rounded-lg p-1.5 -mx-1.5 hover:bg-[var(--bg-elevated)] transition-colors"
        >
          <div className="flex items-center justify-between gap-2 text-xs">
            <div className="flex items-center gap-2 min-w-0">
              {item.color && <ColorDot color={item.color} />}
              <span className="font-medium mm-text-primary truncate">{item.name}</span>
              {item.category && (
                <span className="text-[11px] mm-text-muted truncate">{item.category}</span>
              )}
              <span className="text-[11px] mm-text-muted tabular-nums shrink-0">
                {item.count}笔
              </span>
            </div>
            <div className="flex items-center gap-2 shrink-0 tabular-nums">
              <span className="text-[11px] mm-text-secondary">
                {(item.ratio * 100).toFixed(1)}%
              </span>
              <span className="font-semibold mm-text-primary">
                {formatYuan(item.cents, masked)}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <div className="flex-1 min-w-0">
              <RatioBar ratio={item.ratio} color={item.color ?? 'var(--accent-primary)'} />
            </div>
            {item.delta && (
              <div className="shrink-0">
                <DeltaBadge delta={item.delta} masked={masked} />
              </div>
            )}
          </div>
        </button>
      ))}
    </div>
  );
};
