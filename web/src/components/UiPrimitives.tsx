import React from 'react';
import { AlertCircle, Database } from 'lucide-react';
import type { DeltaInfo } from '../types/api';
import { formatCompactYuan } from '../lib/format';

export const ColorDot: React.FC<{ color?: string; size?: number }> = ({
  color = '#64748B',
  size = 8,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 8 8"
    className="shrink-0 inline-block"
    aria-hidden="true"
  >
    <circle cx="4" cy="4" r="4" fill={color} />
  </svg>
);

export const RatioBar: React.FC<{ ratio: number; color?: string }> = ({
  ratio,
  color = 'var(--accent-primary)',
}) => {
  const pct = Math.max(0, Math.min(100, Math.round(ratio * 1000) / 10));
  return (
    <svg
      viewBox="0 0 100 3"
      preserveAspectRatio="none"
      className="w-full h-[3px] block rounded-full overflow-hidden"
      aria-hidden="true"
    >
      <rect x="0" y="0" width="100" height="3" rx="1.5" fill="var(--bg-subtle)" />
      {pct > 0 && <rect x="0" y="0" width={pct} height="3" rx="1.5" fill={color} />}
    </svg>
  );
};

export const DeltaBadge: React.FC<{ delta?: DeltaInfo | null; masked?: boolean }> = ({
  delta,
  masked = false,
}) => {
  if (!delta) return null;
  if (masked) {
    return (
      <span className="inline-flex items-center gap-1 text-xs tabular-nums mm-text-muted">
        {delta.baselineLabel}
      </span>
    );
  }
  if (delta.smallBase || delta.percent === null) {
    return (
      <span className="inline-flex items-center gap-1 text-xs tabular-nums mm-text-secondary">
        <span>上期 {formatCompactYuan(delta.previousCents, masked)}</span>
        <span className="mm-text-muted">({delta.previousCount}笔)</span>
      </span>
    );
  }

  const arrow = delta.direction === 'up' ? '▲' : delta.direction === 'down' ? '▼' : '—';
  const toneClass =
    delta.tone === 'good'
      ? 'mm-text-income'
      : delta.tone === 'bad'
        ? 'mm-text-expense'
        : 'mm-text-secondary';
  const sign = delta.percent > 0 ? '+' : '';

  return (
    <span className={`inline-flex items-center gap-1 text-xs font-medium tabular-nums ${toneClass}`}>
      <span aria-hidden="true">{arrow}</span>
      <span>
        {sign}
        {delta.percent.toFixed(1)}%
      </span>
      <span className="font-normal mm-text-muted">{delta.baselineLabel}</span>
    </span>
  );
};

export const Panel: React.FC<{
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}> = ({ title, subtitle, action, children, className = '' }) => (
  <section className={`mm-card p-5 ${className}`}>
    <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-2.5 mb-4">
      <div className="min-w-0">
        <h2 className="text-[15px] font-semibold tracking-tight mm-text-primary">{title}</h2>
        {subtitle && <p className="text-xs mm-text-secondary mt-0.5">{subtitle}</p>}
      </div>
      {action && <div className="shrink-0 flex flex-wrap items-center gap-2">{action}</div>}
    </div>
    {children}
  </section>
);

export const LoadingSkeleton: React.FC<{ lines?: number }> = ({ lines = 4 }) => (
  <div className="space-y-4">
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-3">
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className="mm-card p-4 h-24 animate-pulse mm-bg-card" />
      ))}
    </div>
    <div className="mm-card p-5 space-y-3">
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className="h-5 rounded mm-bg-elevated animate-pulse" />
      ))}
    </div>
  </div>
);

export const ErrorBanner: React.FC<{
  message: string;
  onRetry?: () => void;
}> = ({ message, onRetry }) => (
  <div className="mm-card p-5 border-[var(--color-expense)]/40 mm-bg-expense-soft flex flex-col sm:flex-row sm:items-center justify-between gap-3">
    <div className="flex items-start gap-3">
      <AlertCircle className="w-5 h-5 mm-text-expense shrink-0 mt-0.5" />
      <div>
        <div className="text-sm font-medium mm-text-primary">数据加载失败</div>
        <div className="text-xs mm-text-secondary mt-0.5">{message}</div>
      </div>
    </div>
    {onRetry && (
      <button
        type="button"
        onClick={onRetry}
        className="px-3 py-1.5 rounded-lg text-xs font-medium bg-[var(--bg-card)] border mm-divider mm-text-primary hover:opacity-90 shrink-0"
      >
        重试
      </button>
    )}
  </div>
);

export const EmptyState: React.FC<{
  title: string;
  hint?: string;
  action?: React.ReactNode;
}> = ({ title, hint, action }) => (
  <div className="mm-card p-8 text-center flex flex-col items-center justify-center gap-2">
    <Database className="w-8 h-8 mm-text-muted" />
    <div className="text-sm font-medium mm-text-primary">{title}</div>
    {hint && <div className="text-xs mm-text-secondary max-w-md">{hint}</div>}
    {action && <div className="mt-2">{action}</div>}
  </div>
);
