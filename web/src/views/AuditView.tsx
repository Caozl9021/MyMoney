import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../lib/session';
import { buildQuery } from '../lib/api';
import type { AuditResponse, BillDto } from '../types/api';
import { formatYuan } from '../lib/format';
import {
  ColorDot,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  Panel,
} from '../components/UiPrimitives';
import { ChannelShareChart } from '../components/charts/ChannelShareChart';
import { BillDrawer } from '../components/BillDrawer';

export const AuditView: React.FC = () => {
  const { sessionGen, authedJson, selectedBackupId, privacyMasked, drillToBills } = useSession();
  const [reimburseTab, setReimburseTab] = useState<'pending' | 'reimbursed'>('pending');
  const [drawerBill, setDrawerBill] = useState<BillDto | null>(null);

  const qs = buildQuery({ backupId: selectedBackupId });
  const query = useQuery({
    queryKey: ['audit', sessionGen, selectedBackupId],
    queryFn: ({ signal }) =>
      authedJson<AuditResponse>(`/v1/admin/dashboard/audit${qs}`, { signal }),
    staleTime: 60_000,
  });

  if (query.isLoading) return <LoadingSkeleton lines={6} />;
  if (query.isError) {
    return (
      <ErrorBanner
        message={query.error instanceof Error ? query.error.message : '加载审计数据失败'}
        onRetry={() => query.refetch()}
      />
    );
  }
  const data = query.data;
  if (!data) {
    return <EmptyState title="暂无可审计的备份快照" />;
  }

  const activeReimburseList =
    reimburseTab === 'pending' ? data.reimburse.pendingBills : data.reimburse.reimbursedBills;

  return (
    <div className="space-y-5">
      {/* Reimburse KPI Strip */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">待报销总额</div>
          <div className="text-xl font-semibold tabular-nums mm-text-warn mt-1">
            {formatYuan(data.reimburse.pendingCents, privacyMasked)}
          </div>
          <div className="text-xs mm-text-muted mt-1 tabular-nums">
            共 {data.reimburse.pendingCount} 笔未报销
          </div>
        </div>

        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">累计已报销</div>
          <div className="text-xl font-semibold tabular-nums mm-text-income mt-1">
            {formatYuan(data.reimburse.reimbursedCents, privacyMasked)}
          </div>
          <div className="text-xs mm-text-muted mt-1 tabular-nums">
            共 {data.reimburse.reimbursedCount} 笔 ·{' '}
            {data.reimburse.avgReimburseDays !== null
              ? `平均 ${data.reimburse.avgReimburseDays} 天到账`
              : '暂无周期样本'}
          </div>
        </div>

        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">未分类待整理</div>
          <div className="text-xl font-semibold tabular-nums mm-text-primary mt-1">
            {data.uncategorized.count} 笔
          </div>
          <div className="text-xs mm-text-muted mt-1 tabular-nums">
            涉及净支出 {formatYuan(data.uncategorized.expenseCents, privacyMasked)}
          </div>
        </div>

        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">已删账单审计日志</div>
          <div className="text-xl font-semibold tabular-nums mm-text-primary mt-1">
            {data.deletions.length} 条
          </div>
          <div className="text-xs mm-text-muted mt-1">仅作只读追溯，不计入实时汇总</div>
        </div>
      </div>

      {/* Reimburse List + Channels Breakdown */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-7"
          title="报销追踪"
          action={
            <div className="flex items-center gap-2">
              <div className="inline-flex rounded-lg p-0.5 mm-bg-elevated border mm-divider text-xs">
                <button
                  type="button"
                  onClick={() => setReimburseTab('pending')}
                  className={`px-2.5 py-1 rounded-md font-medium ${
                    reimburseTab === 'pending'
                      ? 'bg-[var(--bg-card)] mm-text-warn'
                      : 'mm-text-secondary'
                  }`}
                >
                  待报销 ({data.reimburse.pendingCount})
                </button>
                <button
                  type="button"
                  onClick={() => setReimburseTab('reimbursed')}
                  className={`px-2.5 py-1 rounded-md font-medium ${
                    reimburseTab === 'reimbursed'
                      ? 'bg-[var(--bg-card)] mm-text-income'
                      : 'mm-text-secondary'
                  }`}
                >
                  已报销 ({data.reimburse.reimbursedCount})
                </button>
              </div>
              <button
                type="button"
                onClick={() =>
                  drillToBills({
                    reimburseStatus: reimburseTab === 'pending' ? 'PENDING' : 'REIMBURSED',
                    type: 'EXPENSE',
                  })
                }
                className="text-xs font-medium mm-text-accent hover:underline"
              >
                在明细中查看 →
              </button>
            </div>
          }
        >
          {activeReimburseList.length === 0 ? (
            <div className="py-10 text-center text-xs mm-text-muted">暂无对应报销账单</div>
          ) : (
            <div className="divide-y mm-divider max-h-[380px] overflow-y-auto">
              {activeReimburseList.slice(0, 60).map((b) => (
                <button
                  key={b.index}
                  type="button"
                  onClick={() => setDrawerBill(b)}
                  className="w-full py-2.5 flex items-center justify-between gap-3 text-left hover:bg-[var(--bg-elevated)] px-2 -mx-2 rounded-lg transition-colors text-xs"
                >
                  <div className="flex items-center gap-2.5 min-w-0">
                    <ColorDot color={b.categoryColor} />
                    <div className="min-w-0">
                      <div className="font-medium mm-text-primary truncate">
                        {b.merchant || b.note || b.category}
                      </div>
                      <div className="text-[11px] mm-text-secondary tabular-nums truncate">
                        {b.date} · {b.category} · {b.account}
                      </div>
                    </div>
                  </div>
                  <span
                    className={`font-semibold tabular-nums shrink-0 ${
                      reimburseTab === 'pending' ? 'mm-text-warn' : 'mm-text-secondary'
                    }`}
                  >
                    {formatYuan(b.priceCents, privacyMasked)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </Panel>

        <Panel
          className="xl:col-span-5"
          title="记账来源与识别渠道占比"
          subtitle="点击渠道可直接筛选账单明细"
        >
          <ChannelShareChart
            items={data.channels}
            masked={privacyMasked}
            onSelectChannel={(channel) => drillToBills({ channel })}
          />
          {data.strategies.length > 0 && (
            <div className="mt-5 pt-4 border-t mm-divider">
              <div className="text-xs font-medium mm-text-secondary mb-2">主要识别策略</div>
              <div className="flex flex-wrap gap-1.5">
                {data.strategies.slice(0, 8).map((s) => (
                  <button
                    key={s.name}
                    type="button"
                    onClick={() => drillToBills({ strategy: s.name })}
                    className="px-2 py-1 rounded-lg text-[11px] mm-bg-elevated mm-text-primary hover:border hover:border-[var(--accent-primary)] tabular-nums"
                  >
                    {s.name} · {s.count}
                  </button>
                ))}
              </div>
            </div>
          )}
        </Panel>
      </div>

      {/* Uncategorized + Deletions */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-6"
          title="未分类待整理账单"
          subtitle={`共 ${data.uncategorized.count} 笔 · 点开查看来源详情`}
        >
          {data.uncategorized.bills.length === 0 ? (
            <div className="py-8 text-center text-xs mm-text-income">所有账单均已规范分类</div>
          ) : (
            <div className="divide-y mm-divider max-h-[360px] overflow-y-auto">
              {data.uncategorized.bills.slice(0, 60).map((b) => (
                <button
                  key={b.index}
                  type="button"
                  onClick={() => setDrawerBill(b)}
                  className="w-full py-2.5 flex items-center justify-between gap-3 text-left hover:bg-[var(--bg-elevated)] px-2 -mx-2 rounded-lg transition-colors text-xs"
                >
                  <div className="min-w-0">
                    <div className="font-medium mm-text-primary truncate">
                      {b.merchant || b.productName || b.note || '无商户/备注'}
                    </div>
                    <div className="text-[11px] mm-text-secondary tabular-nums truncate">
                      {b.date} {b.timeText} · {b.account} · {b.channel}
                    </div>
                  </div>
                  <span className="font-semibold tabular-nums mm-text-expense shrink-0">
                    {formatYuan(b.priceCents, privacyMasked)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </Panel>

        <Panel
          className="xl:col-span-6"
          title="账单删除审计日志"
          subtitle="来自 JSON 9+ 快照 deletions 只读存证"
        >
          {data.deletions.length === 0 ? (
            <div className="py-8 text-center text-xs mm-text-muted">该快照无删除审计记录</div>
          ) : (
            <div className="divide-y mm-divider max-h-[360px] overflow-y-auto">
              {data.deletions.map((d) => {
                const firstBill = d.bills[0] as Record<string, unknown> | undefined;
                const cents =
                  typeof firstBill?.priceCents === 'number' ? firstBill.priceCents : null;
                const note =
                  typeof firstBill?.merchant === 'string' && firstBill.merchant
                    ? firstBill.merchant
                    : typeof firstBill?.note === 'string'
                      ? firstBill.note
                      : '';
                return (
                  <div key={d.eventId} className="py-2.5 text-xs space-y-1">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium mm-text-primary">
                        来源 {d.origin} · 主账单 #{d.principalId}
                      </span>
                      <span className="tabular-nums mm-text-secondary">
                        {cents !== null ? formatYuan(cents, privacyMasked) : '—'}
                      </span>
                    </div>
                    <div className="flex items-center justify-between gap-2 text-[11px] mm-text-muted tabular-nums">
                      <span>{d.deletedAtIso}</span>
                      <span className="truncate max-w-[200px]">
                        {note || `包含 ${d.bills.length} 条分录`}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </Panel>
      </div>

      <BillDrawer bill={drawerBill} onClose={() => setDrawerBill(null)} />
    </div>
  );
};
