import React, { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  BarChart3,
  Database,
  Eye,
  EyeOff,
  FileSearch,
  LayoutDashboard,
  ListFilter,
  Lock,
  Menu,
  Moon,
  QrCode,
  RefreshCw,
  Settings,
  Sun,
  Wallet,
  X,
} from 'lucide-react';
import { useSession } from '../lib/session';
import type { SnapshotMetaDto, SnapshotsListResponse, SyncHeadDto, ViewId } from '../types/api';
import { formatDateTime } from '../lib/format';
import { DateRangePicker } from './DateRangePicker';

interface NavItem {
  id: ViewId;
  label: string;
  group: 'analytics' | 'ops';
  icon: React.FC<{ className?: string }>;
}

const NAV_ITEMS: NavItem[] = [
  { id: 'overview', label: '财务总览', group: 'analytics', icon: LayoutDashboard },
  { id: 'analytics', label: '收支分析', group: 'analytics', icon: BarChart3 },
  { id: 'transactions', label: '账单明细', group: 'analytics', icon: ListFilter },
  { id: 'assets', label: '资产账户', group: 'analytics', icon: Wallet },
  { id: 'audit', label: '报销审计', group: 'analytics', icon: FileSearch },
  { id: 'backups', label: '快照运维', group: 'ops', icon: Database },
  { id: 'connect', label: '连接设备', group: 'ops', icon: QrCode },
  { id: 'settings', label: '服务设置', group: 'ops', icon: Settings },
];

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const queryClient = useQueryClient();
  const {
    sessionGen,
    authedJson,
    activeView,
    setActiveView,
    theme,
    toggleTheme,
    privacyMasked,
    togglePrivacy,
    selectedBackupId,
    setSelectedBackupId,
    setMonthStartDay,
    lock,
  } = useSession();

  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const snapshotsQuery = useQuery({
    queryKey: ['snapshots', sessionGen],
    queryFn: ({ signal }) =>
      authedJson<SnapshotsListResponse>('/v1/admin/dashboard/snapshots?limit=50', { signal }),
    staleTime: 30_000,
  });

  const snapshots: SnapshotMetaDto[] = snapshotsQuery.data?.items ?? [];
  const activeSnap =
    snapshots.find((s) => s.backupId === selectedBackupId) ?? snapshots[0] ?? null;

  // 前台可见态轻量探针：15s 一次，后台标签页零请求，切回窗口立即检查。
  const syncHeadQuery = useQuery({
    queryKey: ['sync-head', sessionGen, selectedBackupId],
    queryFn: ({ signal }) =>
      authedJson<SyncHeadDto>(
        `/v1/admin/dashboard/sync-head${
          selectedBackupId ? `?backupId=${encodeURIComponent(selectedBackupId)}` : ''
        }`,
        { signal },
      ),
    refetchInterval: 15_000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
  const syncHead = syncHeadQuery.data;
  const lastSyncHead = useRef<{ backupId: string; revision: number } | null>(null);

  useEffect(() => {
    if (!syncHead) return;
    const prev = lastSyncHead.current;
    lastSyncHead.current = { backupId: syncHead.backupId, revision: syncHead.overlayRevision };
    if (!prev) return;
    if (prev.backupId === syncHead.backupId && prev.revision === syncHead.overlayRevision) return;
    // 手机端又来新记账/改账：自动无感刷新当前视图（keepPreviousData 保证不闪烁）。
    queryClient.invalidateQueries({
      predicate: (q) => q.queryKey[0] !== 'sync-head',
    });
  }, [syncHead, queryClient]);

  useEffect(() => {
    if (syncHead && activeSnap && syncHead.backupId === activeSnap.backupId) {
      if (syncHead.monthStartDay >= 1) setMonthStartDay(syncHead.monthStartDay);
    }
  }, [syncHead, activeSnap, setMonthStartDay]);

  useEffect(() => {
    if (!selectedBackupId && snapshotsQuery.data?.defaultBackupId) {
      setSelectedBackupId(snapshotsQuery.data.defaultBackupId);
    }
  }, [selectedBackupId, snapshotsQuery.data?.defaultBackupId, setSelectedBackupId]);

  useEffect(() => {
    if (activeSnap?.monthStartDay && activeSnap.monthStartDay >= 1) {
      setMonthStartDay(activeSnap.monthStartDay);
    }
  }, [activeSnap?.monthStartDay, setMonthStartDay]);

  const showDateRange = activeView === 'overview' || activeView === 'analytics';

  const handleSelectNav = (id: ViewId) => {
    setActiveView(id);
    setMobileMenuOpen(false);
  };

  return (
    <div className="min-h-screen mm-bg-canvas flex flex-col lg:flex-row">
      {/* Desktop Left Sidebar */}
      <aside className="hidden lg:flex lg:w-[232px] shrink-0 flex-col border-r mm-divider mm-bg-card sticky top-0 h-screen">
        <div className="px-5 h-14 border-b mm-divider flex items-center justify-between">
          <div className="flex items-center gap-2.5 min-w-0">
            <span className="w-2.5 h-2.5 rounded-full bg-[var(--accent-primary)] shrink-0" />
            <span className="text-sm font-semibold tracking-tight mm-text-primary truncate">
              麦米家庭账本
            </span>
          </div>
          <span className="text-[11px] font-medium px-1.5 py-0.5 rounded mm-bg-elevated mm-text-secondary">
            只读
          </span>
        </div>

        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-5" aria-label="主导航">
          <div>
            <div className="px-3 mb-1.5 text-[11px] font-medium mm-text-muted tracking-wide">
              财务分析
            </div>
            <div className="space-y-0.5">
              {NAV_ITEMS.filter((n) => n.group === 'analytics').map((item) => {
                const Icon = item.icon;
                const active = activeView === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => handleSelectNav(item.id)}
                    aria-current={active ? 'page' : undefined}
                    className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-xl text-[13px] font-medium transition-colors ${
                      active
                        ? 'mm-bg-accent-soft mm-text-accent'
                        : 'mm-text-secondary hover:bg-[var(--bg-elevated)] hover:text-[var(--text-primary)]'
                    }`}
                  >
                    <Icon className="w-4 h-4 shrink-0" />
                    <span>{item.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <div className="px-3 mb-1.5 text-[11px] font-medium mm-text-muted tracking-wide">
              系统运维
            </div>
            <div className="space-y-0.5">
              {NAV_ITEMS.filter((n) => n.group === 'ops').map((item) => {
                const Icon = item.icon;
                const active = activeView === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => handleSelectNav(item.id)}
                    aria-current={active ? 'page' : undefined}
                    className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-xl text-[13px] font-medium transition-colors ${
                      active
                        ? 'mm-bg-accent-soft mm-text-accent'
                        : 'mm-text-secondary hover:bg-[var(--bg-elevated)] hover:text-[var(--text-primary)]'
                    }`}
                  >
                    <Icon className="w-4 h-4 shrink-0" />
                    <span>{item.label}</span>
                  </button>
                );
              })}
            </div>
          </div>
        </nav>

        <div className="p-3 border-t mm-divider space-y-2">
          {activeSnap && (
            <div className="px-3 py-2 rounded-xl mm-bg-elevated text-[11px] space-y-0.5">
              <div className="font-medium mm-text-primary truncate">{activeSnap.deviceName}</div>
              <div className="mm-text-secondary tabular-nums">
                {formatDateTime(activeSnap.snapshotAt)}
              </div>
              <div className="mm-text-muted tabular-nums">
                {(syncHead && syncHead.backupId === activeSnap.backupId
                  ? syncHead.billCount
                  : activeSnap.billCount
                ).toLocaleString('zh-CN')}{' '}
                笔 · Schema v{activeSnap.backupSchema}
              </div>
            </div>
          )}
          <button
            type="button"
            onClick={() => lock()}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-xl text-xs font-medium mm-bg-elevated mm-text-secondary hover:text-[var(--color-expense)] transition-colors"
          >
            <Lock className="w-3.5 h-3.5" />
            <span>锁定退出</span>
          </button>
        </div>
      </aside>

      {/* Main Content Shell */}
      <div className="flex-1 min-w-0 flex flex-col">
        {/* Global Sticky Utility Top Bar */}
        <header className="sticky top-0 z-30 mm-bg-card border-b mm-divider px-3 sm:px-6 min-h-14 py-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0">
            <button
              type="button"
              onClick={() => setMobileMenuOpen((v) => !v)}
              aria-label="展开导航菜单"
              aria-expanded={mobileMenuOpen}
              className="lg:hidden p-2 rounded-lg mm-bg-elevated mm-text-primary"
            >
              {mobileMenuOpen ? <X className="w-4 h-4" /> : <Menu className="w-4 h-4" />}
            </button>

            <div className="flex items-center gap-2 min-w-0">
              {snapshots.length > 0 ? (
                <select
                  aria-label="切换分析备份快照"
                  value={selectedBackupId ?? snapshots[0]?.backupId ?? ''}
                  onChange={(e) => setSelectedBackupId(e.target.value || null)}
                  className="mm-input px-2.5 py-1.5 text-xs font-medium tabular-nums max-w-[205px] sm:max-w-[320px] truncate"
                >
                  {snapshots.map((s, idx) => (
                    <option key={s.backupId} value={s.backupId}>
                      {idx === 0 ? '[最新] ' : ''}
                      {s.deviceName} · {formatDateTime(s.snapshotAt)} · {s.billCount}笔
                    </option>
                  ))}
                </select>
              ) : (
                <span className="text-xs mm-text-secondary">
                  {snapshotsQuery.isLoading ? '正在加载快照…' : '暂无可用快照'}
                </span>
              )}
              {syncHead && syncHead.overlayRevision > 0 && (
                <span
                  className="inline-flex items-center gap-1.5 px-2 py-1 rounded-lg text-[11px] font-medium mm-bg-accent-soft mm-text-accent whitespace-nowrap shrink-0"
                  title={`基准快照 ${formatDateTime(syncHead.snapshotAt)}${
                    syncHead.overlayUpdatedAt
                      ? ` · 已叠加最新实时同步至 ${formatDateTime(syncHead.overlayUpdatedAt)}`
                      : ''
                  }`}
                >
                  <span className="w-1.5 h-1.5 rounded-full bg-[var(--accent-primary)]" />
                  <span className="hidden sm:inline">实时同步</span>
                </span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-1.5 sm:gap-2 flex-wrap">
            {showDateRange && <DateRangePicker />}

            <button
              type="button"
              onClick={togglePrivacy}
              aria-label={privacyMasked ? '显示真实金额' : '隐藏敏感金额'}
              title={privacyMasked ? '显示真实金额' : '隐私遮罩'}
              className={`inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium border mm-divider ${
                privacyMasked ? 'mm-bg-accent-soft mm-text-accent' : 'mm-bg-card mm-text-secondary'
              }`}
            >
              {privacyMasked ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              <span className="hidden sm:inline">{privacyMasked ? '已遮蔽' : '隐私'}</span>
            </button>

            <button
              type="button"
              onClick={toggleTheme}
              aria-label={theme === 'light' ? '切换深色模式' : '切换浅色模式'}
              title={theme === 'light' ? '深色模式' : '浅色模式'}
              className="p-1.5 rounded-lg border mm-divider mm-bg-card mm-text-secondary hover:text-[var(--text-primary)]"
            >
              {theme === 'light' ? <Moon className="w-3.5 h-3.5" /> : <Sun className="w-3.5 h-3.5" />}
            </button>

            <button
              type="button"
              onClick={() => queryClient.invalidateQueries()}
              aria-label="刷新当前数据"
              title="刷新数据"
              className="p-1.5 rounded-lg border mm-divider mm-bg-card mm-text-secondary hover:text-[var(--text-primary)]"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>

            <button
              type="button"
              onClick={() => lock()}
              aria-label="锁定仪表盘"
              className="lg:hidden p-1.5 rounded-lg border mm-divider mm-bg-card mm-text-secondary hover:text-[var(--color-expense)]"
            >
              <Lock className="w-3.5 h-3.5" />
            </button>
          </div>
        </header>

        {/* Mobile 3-Column Grid Navigation Popover */}
        {mobileMenuOpen && (
          <div className="lg:hidden border-b mm-divider mm-bg-card p-3 z-20">
            <div className="grid grid-cols-3 gap-2">
              {NAV_ITEMS.map((item) => {
                const Icon = item.icon;
                const active = activeView === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => handleSelectNav(item.id)}
                    className={`flex flex-col items-center justify-center gap-1 py-2.5 px-2 rounded-xl text-xs font-medium border ${
                      active
                        ? 'mm-bg-accent-soft mm-text-accent border-[var(--accent-primary)]/40'
                        : 'mm-bg-elevated mm-text-secondary border-transparent'
                    }`}
                  >
                    <Icon className="w-4 h-4" />
                    <span className="truncate">{item.label}</span>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Main Viewport */}
        <main className="flex-1 w-full max-w-[1640px] mx-auto px-3 sm:px-6 lg:px-8 py-5 min-w-0">
          {children}
        </main>
      </div>
    </div>
  );
};
