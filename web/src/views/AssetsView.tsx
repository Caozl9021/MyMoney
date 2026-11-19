import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../lib/session';
import { buildQuery } from '../lib/api';
import type { AssetAccountDto, AssetsResponse } from '../types/api';
import {
  ACCOUNT_KIND_LABELS,
  ACCOUNT_KIND_ORDER,
  entryKindLabel,
  formatDateTime,
  formatSignedYuan,
  formatYuan,
} from '../lib/format';
import {
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  Panel,
} from '../components/UiPrimitives';
import { AssetTimelineChart } from '../components/charts/AssetTimelineChart';

export const AssetsView: React.FC = () => {
  const { sessionGen, authedJson, selectedBackupId, privacyMasked } = useSession();
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [comparedIds, setComparedIds] = useState<string[]>([]);
  const [showArchived, setShowArchived] = useState(false);

  const qs = buildQuery({
    backupId: selectedBackupId,
    accountId: selectedAccountId ?? undefined,
  });

  const query = useQuery({
    queryKey: ['assets', sessionGen, selectedBackupId, selectedAccountId],
    queryFn: ({ signal }) =>
      authedJson<AssetsResponse>(`/v1/admin/dashboard/assets${qs}`, { signal }),
    staleTime: 60_000,
    placeholderData: (prev) => prev,
  });

  const data = query.data;

  const groupedAccounts = useMemo(() => {
    if (!data) return [];
    const visible = data.accounts.filter((a) => showArchived || !a.archived);
    const groups: { kind: string; label: string; items: AssetAccountDto[]; subtotal: number }[] = [];
    const knownKinds = new Set<string>(ACCOUNT_KIND_ORDER);
    for (const kind of ACCOUNT_KIND_ORDER) {
      const items = visible.filter((a) => a.kind === kind);
      if (items.length === 0) continue;
      const subtotal = items
        .filter((a) => a.initialized)
        .reduce((acc, a) => acc + a.balanceCents, 0);
      groups.push({
        kind,
        label: ACCOUNT_KIND_LABELS[kind] ?? kind,
        items,
        subtotal,
      });
    }
    const otherItems = visible.filter((a) => !knownKinds.has(a.kind));
    if (otherItems.length > 0) {
      const subtotal = otherItems
        .filter((a) => a.initialized)
        .reduce((acc, a) => acc + a.balanceCents, 0);
      groups.push({
        kind: 'OTHER',
        label: '其他账户',
        items: otherItems,
        subtotal,
      });
    }
    return groups;
  }, [data, showArchived]);

  if (query.isLoading && !data) return <LoadingSkeleton lines={6} />;
  if (query.isError) {
    return (
      <ErrorBanner
        message={query.error instanceof Error ? query.error.message : '加载资产数据失败'}
        onRetry={() => query.refetch()}
      />
    );
  }
  if (!data) {
    return <EmptyState title="当前快照暂无资产账户数据" />;
  }

  const { summary } = data;
  const activeId = selectedAccountId ?? data.selectedAccountId;
  const activeAccount = data.accounts.find((a) => a.id === activeId) ?? null;

  const toggleCompare = (id: string) => {
    setComparedIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  };

  return (
    <div className="space-y-5">
      {/* Net Worth Summary Card aligned with App 1.10.3 */}
      <section className="mm-card p-6">
        <div className="flex flex-wrap items-center justify-between gap-2 mb-1">
          <span className="text-xs font-medium mm-text-secondary">
            净资产（仅计已初始化且纳入总览账户）
          </span>
          {summary.unknownCount > 0 && (
            <span className="px-2 py-0.5 rounded-full text-[11px] font-medium mm-bg-warn-soft mm-text-warn tabular-nums">
              {summary.unknownCount} 个账户未设期初余额
            </span>
          )}
        </div>
        <div
          className={`text-3xl font-semibold tabular-nums tracking-tight ${
            summary.netCents < 0 && !privacyMasked ? 'mm-text-expense' : 'mm-text-primary'
          }`}
        >
          {formatYuan(summary.netCents, privacyMasked)}
        </div>

        {summary.debtsCents > 0 && (
          <div className="mt-4 pt-4 border-t mm-divider grid grid-cols-2 gap-4">
            <div>
              <div className="text-xs mm-text-secondary">总资产</div>
              <div className="text-base font-semibold tabular-nums mm-text-primary mt-0.5">
                {formatYuan(summary.assetsCents, privacyMasked)}
              </div>
            </div>
            <div>
              <div className="text-xs mm-text-secondary">总负债</div>
              <div
                className={`text-base font-semibold tabular-nums mt-0.5 ${
                  privacyMasked ? 'mm-text-primary' : 'mm-text-expense'
                }`}
              >
                {formatYuan(summary.debtsCents, privacyMasked)}
              </div>
            </div>
          </div>
        )}
      </section>

      {/* Asset Timeline Replay */}
      <Panel
        title="净资产与多账户余额走势回放"
        subtitle="按 assets.entries 流水与冲销记录逐笔推演，末点严格对齐当前净资产"
      >
        <AssetTimelineChart
          timeline={data.timeline}
          accounts={data.accounts}
          comparedIds={comparedIds}
          onToggleCompare={toggleCompare}
          masked={privacyMasked}
        />
      </Panel>

      {/* Accounts Group List + Selected Account Ledger */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-5"
          title="资金账户明细"
          subtitle="点击账户右侧查看逐笔流水审计"
          action={
            <label className="inline-flex items-center gap-1.5 text-xs mm-text-secondary cursor-pointer">
              <input
                type="checkbox"
                checked={showArchived}
                onChange={(e) => setShowArchived(e.target.checked)}
              />
              <span>显示已归档</span>
            </label>
          }
        >
          <div className="space-y-4">
            {groupedAccounts.map((grp) => (
              <div key={grp.kind} className="space-y-1.5">
                <div className="flex items-center justify-between text-xs px-1">
                  <span className="font-medium mm-text-secondary">{grp.label}</span>
                  {!privacyMasked && (
                    <span className="tabular-nums mm-text-muted">
                      {formatYuan(grp.subtotal, false)}
                    </span>
                  )}
                </div>
                <div className="divide-y mm-divider rounded-xl border mm-divider overflow-hidden">
                  {grp.items.map((acc) => {
                    const isSelected = acc.id === activeId;
                    return (
                      <button
                        key={acc.id}
                        type="button"
                        onClick={() => setSelectedAccountId(acc.id)}
                        className={`w-full px-3.5 py-2.5 flex items-center justify-between gap-2 text-left transition-colors ${
                          isSelected ? 'mm-bg-accent-soft' : 'mm-bg-card hover:bg-[var(--bg-elevated)]'
                        }`}
                      >
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="text-xs font-medium mm-text-primary truncate">
                              {acc.name}
                            </span>
                            {!acc.included && (
                              <span className="text-[10px] px-1.5 rounded mm-bg-elevated mm-text-muted">
                                不计入
                              </span>
                            )}
                            {acc.archived && (
                              <span className="text-[10px] px-1.5 rounded mm-bg-elevated mm-text-muted">
                                已归档
                              </span>
                            )}
                          </div>
                          <div className="text-[11px] mm-text-muted tabular-nums">
                            {acc.initialized ? `${acc.entryCount} 条流水` : '未初始化余额'}
                          </div>
                        </div>
                        <div
                          className={`text-xs font-semibold tabular-nums shrink-0 ${
                            !acc.initialized
                              ? 'mm-text-muted'
                              : acc.balanceCents < 0 && !privacyMasked
                                ? 'mm-text-expense'
                                : 'mm-text-primary'
                          }`}
                        >
                          {acc.initialized ? formatYuan(acc.balanceCents, privacyMasked) : '待设置'}
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </Panel>

        <Panel
          className="xl:col-span-7"
          title={activeAccount ? `账户流水：${activeAccount.name}` : '账户流水审计'}
          subtitle={
            activeAccount
              ? `${ACCOUNT_KIND_LABELS[activeAccount.kind] ?? activeAccount.kind} · 当前余额 ${
                  activeAccount.initialized
                    ? formatYuan(activeAccount.balanceCents, privacyMasked)
                    : '未初始化'
                }`
              : undefined
          }
        >
          {data.accountEntries.length === 0 ? (
            <div className="py-10 text-center text-xs mm-text-muted">
              该账户暂无资产流水记录
            </div>
          ) : (
            <div className="overflow-x-auto max-h-[540px]">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b mm-divider mm-text-secondary">
                    <th className="py-2 text-left font-medium">时间</th>
                    <th className="py-2 text-left font-medium">分录类型</th>
                    <th className="py-2 text-left font-medium">说明</th>
                    <th className="py-2 text-right font-medium">变动金额</th>
                    <th className="py-2 text-right font-medium">结后余额</th>
                  </tr>
                </thead>
                <tbody className="divide-y mm-divider tabular-nums">
                  {data.accountEntries.map((e) => (
                    <tr key={e.id} className="hover:bg-[var(--bg-elevated)]">
                      <td className="py-2 pr-2 whitespace-nowrap mm-text-secondary">
                        {formatDateTime(e.timeIso)}
                      </td>
                      <td className="py-2 pr-2 whitespace-nowrap">
                        <span
                          className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                            e.kind === 'REVERSAL'
                              ? 'mm-bg-warn-soft mm-text-warn'
                              : 'mm-bg-elevated mm-text-secondary'
                          }`}
                        >
                          {entryKindLabel(e.kind)}
                        </span>
                      </td>
                      <td className="py-2 pr-2 mm-text-primary max-w-[200px] truncate">
                        {e.note || '—'}
                      </td>
                      <td
                        className={`py-2 pr-2 text-right font-medium whitespace-nowrap ${
                          privacyMasked
                            ? 'mm-text-primary'
                            : e.deltaCents > 0
                              ? 'mm-text-income'
                              : e.deltaCents < 0
                                ? 'mm-text-expense'
                                : 'mm-text-secondary'
                        }`}
                      >
                        {e.deltaCents > 0 ? '▲ ' : e.deltaCents < 0 ? '▼ ' : ''}
                        {formatSignedYuan(e.deltaCents, privacyMasked)}
                      </td>
                      <td className="py-2 text-right font-semibold mm-text-primary whitespace-nowrap">
                        {formatYuan(e.runningBalanceCents, privacyMasked)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      </div>
    </div>
  );
};
