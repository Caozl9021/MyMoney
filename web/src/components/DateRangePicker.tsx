import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Calendar, ChevronLeft, ChevronRight, X } from 'lucide-react';
import { computeLedgerBounds, formatIsoYmd, parseYmd } from '../lib/format';
import { useSession } from '../lib/session';

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];

export const DateRangePicker: React.FC<{ activeLabel?: string }> = ({ activeLabel }) => {
  const { periodMode, setPeriodMode, customRange, setCustomRange, monthStartDay } = useSession();
  const [open, setOpen] = useState(false);
  const [cursorMonth, setCursorMonth] = useState<Date>(() => {
    const base = customRange?.end ? parseYmd(customRange.end) : null;
    const d = base ?? new Date();
    return new Date(d.getFullYear(), d.getMonth(), 1);
  });
  const [draftStart, setDraftStart] = useState<string | null>(customRange?.start ?? null);
  const [draftEnd, setDraftEnd] = useState<string | null>(customRange?.end ?? null);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    setDraftStart(customRange?.start ?? null);
    setDraftEnd(customRange?.end ?? null);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const onClickOutside = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onClickOutside);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onClickOutside);
    };
  }, [open, customRange]);

  const calendarCells = useMemo(() => {
    const year = cursorMonth.getFullYear();
    const month = cursorMonth.getMonth();
    const firstDay = new Date(year, month, 1);
    const offset = (firstDay.getDay() + 6) % 7; // Monday = 0
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const cells: { ymd: string | null; day: number | null }[] = [];
    for (let i = 0; i < offset; i++) cells.push({ ymd: null, day: null });
    for (let d = 1; d <= daysInMonth; d++) {
      const dt = new Date(year, month, d);
      cells.push({ ymd: formatIsoYmd(dt), day: d });
    }
    return cells;
  }, [cursorMonth]);

  const applyPreset = (key: 'current_ledger' | 'prev_ledger' | 'this_month' | 'last_90' | 'this_year' | 'last_365') => {
    const now = new Date();
    if (key === 'current_ledger') {
      setPeriodMode('ledger');
      setCustomRange(null);
      setOpen(false);
      return;
    }
    if (key === 'prev_ledger') {
      const curr = computeLedgerBounds(now, monthStartDay);
      const currStart = parseYmd(curr.start) ?? now;
      const prevAnchor = new Date(currStart.getFullYear(), currStart.getMonth(), currStart.getDate() - 1);
      const prev = computeLedgerBounds(prevAnchor, monthStartDay);
      setPeriodMode('ledger');
      setCustomRange(prev);
      setOpen(false);
      return;
    }
    if (key === 'this_month') {
      setPeriodMode('calendar');
      setCustomRange(null);
      setOpen(false);
      return;
    }
    if (key === 'last_90') {
      const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 89);
      setCustomRange({ start: formatIsoYmd(start), end: formatIsoYmd(now) });
      setOpen(false);
      return;
    }
    if (key === 'this_year') {
      const start = new Date(now.getFullYear(), 0, 1);
      setCustomRange({ start: formatIsoYmd(start), end: formatIsoYmd(now) });
      setOpen(false);
      return;
    }
    const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 364);
    setCustomRange({ start: formatIsoYmd(start), end: formatIsoYmd(now) });
    setOpen(false);
  };

  const handleDayClick = (ymd: string) => {
    if (!draftStart || (draftStart && draftEnd)) {
      setDraftStart(ymd);
      setDraftEnd(null);
      return;
    }
    if (ymd < draftStart) {
      setDraftEnd(draftStart);
      setDraftStart(ymd);
    } else {
      setDraftEnd(ymd);
    }
  };

  const confirmDraft = () => {
    if (draftStart && draftEnd) {
      setCustomRange({ start: draftStart, end: draftEnd });
      setOpen(false);
    } else if (draftStart) {
      setCustomRange({ start: draftStart, end: draftStart });
      setOpen(false);
    }
  };

  const buttonText = customRange
    ? `${customRange.start} – ${customRange.end}`
    : activeLabel ?? (periodMode === 'ledger' ? `记账周期(${monthStartDay}日)` : '本自然月');

  return (
    <div className="relative" ref={rootRef}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label="选择统计周期与日期范围"
        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium mm-input hover:border-[var(--border-strong)] tabular-nums"
      >
        <Calendar className="w-3.5 h-3.5 mm-text-accent shrink-0" />
        <span className="truncate max-w-[175px]">{buttonText}</span>
        {customRange && (
          <span
            role="button"
            tabIndex={0}
            aria-label="恢复默认周期"
            onClick={(e) => {
              e.stopPropagation();
              setCustomRange(null);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.stopPropagation();
                setCustomRange(null);
              }
            }}
            className="ml-0.5 rounded hover:bg-[var(--bg-subtle)] p-0.5"
          >
            <X className="w-3 h-3 mm-text-muted" />
          </span>
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="自定义日期与周期"
          className="absolute right-0 mt-2 w-[310px] max-w-[calc(100vw-24px)] mm-card p-4 shadow-lg z-50"
        >
          <div className="flex items-center justify-between mb-3">
            <div className="inline-flex rounded-lg p-0.5 mm-bg-elevated border mm-divider text-xs">
              <button
                type="button"
                onClick={() => {
                  setPeriodMode('ledger');
                  setCustomRange(null);
                }}
                className={`px-2.5 py-1 rounded-md font-medium ${
                  periodMode === 'ledger' && !customRange
                    ? 'bg-[var(--bg-card)] mm-text-accent shadow-xs'
                    : 'mm-text-secondary'
                }`}
              >
                记账周期({monthStartDay}日)
              </button>
              <button
                type="button"
                onClick={() => {
                  setPeriodMode('calendar');
                  setCustomRange(null);
                }}
                className={`px-2.5 py-1 rounded-md font-medium ${
                  periodMode === 'calendar' && !customRange
                    ? 'bg-[var(--bg-card)] mm-text-accent shadow-xs'
                    : 'mm-text-secondary'
                }`}
              >
                自然月
              </button>
            </div>
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label="关闭日历"
              className="p-1 rounded hover:bg-[var(--bg-elevated)] mm-text-muted"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>

          <div className="grid grid-cols-3 gap-1.5 mb-3">
            {[
              { id: 'current_ledger', label: '当前周期' },
              { id: 'prev_ledger', label: '上个周期' },
              { id: 'this_month', label: '本自然月' },
              { id: 'last_90', label: '近90天' },
              { id: 'this_year', label: '今年以来' },
              { id: 'last_365', label: '近1年' },
            ].map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() =>
                  applyPreset(
                    p.id as 'current_ledger' | 'prev_ledger' | 'this_month' | 'last_90' | 'this_year' | 'last_365',
                  )
                }
                className="px-2 py-1.5 rounded-lg text-[11px] font-medium mm-bg-elevated mm-text-primary hover:border hover:border-[var(--accent-primary)]"
              >
                {p.label}
              </button>
            ))}
          </div>

          <div className="border-t mm-divider pt-3">
            <div className="flex items-center justify-between mb-2">
              <button
                type="button"
                aria-label="上一月"
                onClick={() =>
                  setCursorMonth((d) => new Date(d.getFullYear(), d.getMonth() - 1, 1))
                }
                className="p-1 rounded hover:bg-[var(--bg-elevated)] mm-text-secondary"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <span className="text-xs font-semibold tabular-nums mm-text-primary">
                {cursorMonth.getFullYear()}年{cursorMonth.getMonth() + 1}月
              </span>
              <button
                type="button"
                aria-label="下一月"
                onClick={() =>
                  setCursorMonth((d) => new Date(d.getFullYear(), d.getMonth() + 1, 1))
                }
                className="p-1 rounded hover:bg-[var(--bg-elevated)] mm-text-secondary"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>

            <div className="grid grid-cols-7 text-center text-[11px] mm-text-muted mb-1">
              {WEEKDAYS.map((w) => (
                <div key={w} className="py-1">
                  {w}
                </div>
              ))}
            </div>

            <div className="grid grid-cols-7 gap-0.5 text-center text-xs tabular-nums">
              {calendarCells.map((cell, idx) => {
                if (!cell.ymd || cell.day === null) {
                  return <div key={`e-${idx}`} className="h-7" />;
                }
                const isEdge = cell.ymd === draftStart || cell.ymd === draftEnd;
                const inRange =
                  Boolean(draftStart && draftEnd) &&
                  cell.ymd > (draftStart as string) &&
                  cell.ymd < (draftEnd as string);

                return (
                  <button
                    key={cell.ymd}
                    type="button"
                    onClick={() => handleDayClick(cell.ymd as string)}
                    className={`h-7 rounded-md flex items-center justify-center transition-colors ${
                      isEdge
                        ? 'bg-[var(--accent-primary)] text-white font-semibold'
                        : inRange
                          ? 'mm-bg-accent-soft mm-text-accent font-medium'
                          : 'mm-text-primary hover:bg-[var(--bg-elevated)]'
                    }`}
                  >
                    {cell.day}
                  </button>
                );
              })}
            </div>

            <div className="flex items-center justify-between gap-2 mt-3 pt-2.5 border-t mm-divider">
              <div className="text-[11px] tabular-nums mm-text-secondary truncate">
                {draftStart
                  ? draftEnd
                    ? `${draftStart} → ${draftEnd}`
                    : `${draftStart}（请选结束日）`
                  : '点选起止日期'}
              </div>
              <button
                type="button"
                disabled={!draftStart}
                onClick={confirmDraft}
                className="px-3 py-1 rounded-lg text-xs font-medium bg-[var(--accent-primary)] text-white disabled:opacity-40 shrink-0"
              >
                应用
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
