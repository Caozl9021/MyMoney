import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowUpRight, AlertTriangle, Clock, ReceiptText } from 'lucide-react';
import { useSession } from '../lib/session';
import { buildQuery } from '../lib/api';
import type { BillDto, OverviewResponse } from '../types/api';
import {
  billTypeBadgeClass,
  billTypeLabel,
  billTypeToneClass,
  formatCompactYuan,
  formatYuan,
} from '../lib/format';
import {
  ColorDot,
  DeltaBadge,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  Panel,
} from '../components/UiPrimitives';
import { TrendChart, type TrendMetric } from '../components/charts/TrendChart';
import { AnnualFlowChart } from '../components/charts/AnnualFlowChart';
import { CategoryRankList } from '../components/charts/CategoryRankList';
import { BillDrawer } from '../components/BillDrawer';

export const OverviewView: React.FC = () => {
  const {
    sessionGen,
    authedJson,
    selectedBackupId,
    periodMode,
    customRange,
    setCustomRange,
    privacyMasked,
    setActiveView,
    drillToBills,
  } = useSession();

  const [chartTab, setChartTab] = useState<'trend' | 'annual'>('trend');
  const [trendMetric, setTrendMetric] = useState<TrendMetric>('expense');
  const [rankTab, setRankTab] = useState<'category' | 'subCategory' | 'account'>('category');
  const [drawerBill, setDrawerBill] = useState<BillDto | null>(null);

  const qs = buildQuery({
    backupId: selectedBackupId,
    periodMode,
    start: customRange?.start,
    end: customRange?.end,
  });

  const query = useQuery({
    queryKey: ['overview', sessionGen, selectedBackupId, periodMode, customRange?.start, customRange?.end],
    queryFn: ({ signal }) =>
      authedJson<OverviewResponse>(`/v1/admin/dashboard/overview${qs}`, { signal }),
    staleTime: 60_000,
  });

  if (query.isLoading) return <LoadingSkeleton lines={6} />;
  if (query.isError) {
    return (
      <ErrorBanner
        message={query.error instanceof Error ? query.error.message : '无法加载总览数据'}
        onRetry={() => query.refetch()}
      />
    );
  }
  const data = query.data;
  if (!data) {
    return (
      <EmptyState
        title="暂无有效云备份快照"
        hint="请在手机端完成首次云备份上传后刷新此页面。"
      />
    );
  }

  const { kpis, range, signals } = data;
  const rankItems =
    rankTab === 'category'
      ? data.topCategories
      : rankTab === 'subCategory'
        ? data.topSubCategories
        : data.topAccounts;

  return (
    <div className="space-y-5">
      {/* 5-Card Clickable KPI Strip */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-3">
        <button
          type="button"
          onClick={() => {
            setChartTab('trend');
            setTrendMetric('expense');
          }}
          className={`mm-card p-4 text-left transition-colors ${
            chartTab === 'trend' && trendMetric === 'expense'
              ? 'border-[var(--accent-primary)]/50'
              : 'hover:border-[var(--border-strong)]'
          }`}
        >
          <div className="flex items-center justify-between text-xs mm-text-secondary mb-1">
            <span>净支出</span>
            <span className="tabular-nums whitespace-nowrap">{kpis.netExpense.count}笔</span>
          </div>
          <div className="text-xl font-semibold tabular-nums mm-text-expense">
            {formatYuan(kpis.netExpense.cents, privacyMasked)}
          </div>
          <div className="mt-1.5">
            <DeltaBadge delta={kpis.netExpense.delta} masked={privacyMasked} />
          </div>
        </button>

        <button
          type="button"
          onClick={() => {
            setChartTab('trend');
            setTrendMetric('income');
          }}
          className={`mm-card p-4 text-left transition-colors ${
            chartTab === 'trend' && trendMetric === 'income'
              ? 'border-[var(--accent-primary)]/50'
              : 'hover:border-[var(--border-strong)]'
          }`}
        >
          <div className="flex items-center justify-between text-xs mm-text-secondary mb-1">
            <span>总收入</span>
            <span className="tabular-nums whitespace-nowrap">{kpis.income.count}笔</span>
          </div>
          <div className="text-xl font-semibold tabular-nums mm-text-income">
            {formatYuan(kpis.income.cents, privacyMasked)}
          </div>
          <div className="mt-1.5">
            <DeltaBadge delta={kpis.income.delta} masked={privacyMasked} />
          </div>
        </button>

        <button
          type="button"
          onClick={() => {
            setChartTab('trend');
            setTrendMetric('balance');
          }}
          className={`mm-card p-4 text-left transition-colors ${
            chartTab === 'trend' && trendMetric === 'balance'
              ? 'border-[var(--accent-primary)]/50'
              : 'hover:border-[var(--border-strong)]'
          }`}
        >
          <div className="flex items-center justify-between text-xs mm-text-secondary mb-1">
            <span>净结余</span>
            <span className="tabular-nums whitespace-nowrap">
              {kpis.balance.savingsRate !== null
                ? `储蓄率 ${kpis.balance.savingsRate.toFixed(1)}%`
                : '无收入'}
            </span>
          </div>
          <div className="text-xl font-semibold tabular-nums mm-text-primary">
            {formatYuan(kpis.balance.cents, privacyMasked)}
          </div>
          <div className="mt-1.5 flex items-center justify-between gap-2 text-xs">
            <DeltaBadge delta={kpis.balance.delta} masked={privacyMasked} />
            {kpis.balance.refundCents > 0 && (
              <span className="mm-text-refund tabular-nums">
                退 {formatCompactYuan(kpis.balance.refundCents, privacyMasked)}
              </span>
            )}
          </div>
        </button>

        <div className="mm-card p-4">
          <div className="flex items-center justify-between text-xs mm-text-secondary mb-1">
            <span>日均支出</span>
            <span className="tabular-nums whitespace-nowrap">已过{kpis.dailyAvgExpense.elapsedDays}天</span>
          </div>
          <div className="text-xl font-semibold tabular-nums mm-text-primary">
            {formatYuan(kpis.dailyAvgExpense.cents, privacyMasked)}
          </div>
          <div className="mt-1.5">
            <DeltaBadge delta={kpis.dailyAvgExpense.delta} masked={privacyMasked} />
          </div>
        </div>

        <button
          type="button"
          onClick={() => setActiveView('assets')}
          className="mm-card p-4 text-left hover:border-[var(--border-strong)] transition-colors"
        >
          <div className="flex items-center justify-between text-xs mm-text-secondary mb-1">
            <span>当前净资产</span>
            <ArrowUpRight className="w-3.5 h-3.5 mm-text-muted" />
          </div>
          <div
            className={`text-xl font-semibold tabular-nums ${
              kpis.netWorth.netCents < 0 && !privacyMasked ? 'mm-text-expense' : 'mm-text-primary'
            }`}
          >
            {formatYuan(kpis.netWorth.netCents, privacyMasked)}
          </div>
          <div className="mt-1.5 text-xs mm-text-secondary tabular-nums flex items-center gap-2 truncate">
            <span>资产 {formatCompactYuan(kpis.netWorth.assetsCents, privacyMasked)}</span>
            {kpis.netWorth.debtsCents > 0 && (
              <span className={privacyMasked ? '' : 'mm-text-expense'}>
                负债 {formatCompactYuan(kpis.netWorth.debtsCents, privacyMasked)}
              </span>
            )}
          </div>
        </button>
      </div>

      {/* Main Chart + Breakdown Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-8"
          title={chartTab === 'trend' ? '收支趋势与同口径对比' : '近 12 个周期收支流向'}
          subtitle={`${range.label} · ${range.isPartial ? `当期已过 ${range.elapsedDays} 天` : `共 ${range.days} 天`}`}
          action={
            <div className="flex flex-wrap items-center gap-2">
              {chartTab === 'trend' && (
                <div className="inline-flex rounded-lg p-0.5 mm-bg-elevated border mm-divider text-xs">
                  {(
                    [
                      ['expense', '支出'],
                      ['income', '收入'],
                      ['balance', '结余'],
                    ] as const
                  ).map(([m, label]) => (
                    <button
                      key={m}
                      type="button"
                      onClick={() => setTrendMetric(m)}
                      className={`px-2 py-1 rounded-md font-medium ${
                        trendMetric === m ? 'bg-[var(--bg-card)] mm-text-accent' : 'mm-text-secondary'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              )}
              <div className="inline-flex rounded-lg p-0.5 mm-bg-elevated border mm-divider text-xs">
                <button
                  type="button"
                  onClick={() => setChartTab('trend')}
                  className={`px-2.5 py-1 rounded-md font-medium ${
                    chartTab === 'trend'
                      ? 'bg-[var(--bg-card)] mm-text-accent'
                      : 'mm-text-secondary'
                  }`}
                >
                  周期趋势
                </button>
                <button
                  type="button"
                  onClick={() => setChartTab('annual')}
                  className={`px-2.5 py-1 rounded-md font-medium ${
                    chartTab === 'annual'
                      ? 'bg-[var(--bg-card)] mm-text-accent'
                      : 'mm-text-secondary'
                  }`}
                >
                  近12个月收支
                </button>
              </div>
            </div>
          }
        >
          {chartTab === 'trend' ? (
            <TrendChart
              series={data.trendSeries}
              metric={trendMetric}
              masked={privacyMasked}
              onSelectRange={(start, end) =>
                drillToBills({
                  start,
                  end,
                  type: trendMetric === 'income' ? 'INCOME' : trendMetric === 'expense' ? 'EXPENSE' : undefined,
                })
              }
            />
          ) : (
            <AnnualFlowChart
              buckets={data.annualFlow}
              masked={privacyMasked}
              onSelectPeriod={(start, end) => setCustomRange({ start, end })}
            />
          )}
        </Panel>

        <Panel
          className="xl:col-span-4"
          title="支出构成"
          subtitle={`净支出 ${formatYuan(data.totals.expenseCents, privacyMasked)}`}
          action={
            <div className="inline-flex rounded-lg p-0.5 mm-bg-elevated border mm-divider text-xs">
              {(
                [
                  ['category', '分类'],
                  ['subCategory', '二级'],
                  ['account', '账户'],
                ] as const
              ).map(([tab, label]) => (
                <button
                  key={tab}
                  type="button"
                  onClick={() => setRankTab(tab)}
                  className={`px-2 py-1 rounded-md font-medium ${
                    rankTab === tab ? 'bg-[var(--bg-card)] mm-text-accent' : 'mm-text-secondary'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          }
        >
          <CategoryRankList
            items={rankItems}
            masked={privacyMasked}
            maxRows={7}
            onItemClick={(item) => {
              if (rankTab === 'category') {
                drillToBills({
                  category: item.name,
                  start: range.start,
                  end: range.end,
                  type: 'EXPENSE',
                });
              } else if (rankTab === 'subCategory') {
                drillToBills({
                  category: item.category,
                  subCategory: item.name,
                  start: range.start,
                  end: range.end,
                  type: 'EXPENSE',
                });
              } else {
                drillToBills({
                  account: item.name,
                  start: range.start,
                  end: range.end,
                  type: 'EXPENSE',
                });
              }
            }}
          />
        </Panel>
      </div>

      {/* Signals + Recent Bills Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel className="xl:col-span-5" title="待关注事项与单笔大额">
          <div className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
              <button
                type="button"
                onClick={() =>
                  drillToBills({ reimburseStatus: 'PENDING', type: 'EXPENSE' })
                }
                className="mm-card-subtle p-3 text-left hover:border-[var(--border-strong)] transition-colors"
              >
                <div className="flex items-center gap-1.5 text-[11px] mm-text-secondary">
                  <ReceiptText className="w-3.5 h-3.5 mm-text-warn" />
                  <span>待报销</span>
                </div>
                <div className="text-sm font-semibold tabular-nums mm-text-warn mt-1">
                  {formatYuan(signals.pendingReimburseCents, privacyMasked)}
                </div>
                <div className="text-[11px] mm-text-muted tabular-nums">
                  {signals.pendingReimburseCount} 笔未报销
                </div>
              </button>

              <button
                type="button"
                onClick={() => setActiveView('audit')}
                className="mm-card-subtle p-3 text-left hover:border-[var(--border-strong)] transition-colors"
              >
                <div className="flex items-center gap-1.5 text-[11px] mm-text-secondary">
                  <AlertTriangle className="w-3.5 h-3.5 mm-text-accent" />
                  <span>未分类</span>
                </div>
                <div className="text-sm font-semibold tabular-nums mm-text-primary mt-1">
                  {signals.periodUncategorizedCount} 笔
                </div>
                <div className="text-[11px] mm-text-muted tabular-nums">
                  全库共 {signals.totalUncategorizedCount} 笔
                </div>
              </button>

              <button
                type="button"
                onClick={() => setActiveView('backups')}
                className="mm-card-subtle p-3 text-left hover:border-[var(--border-strong)] transition-colors"
              >
                <div className="flex items-center gap-1.5 text-[11px] mm-text-secondary">
                  <Clock className="w-3.5 h-3.5 mm-text-muted" />
                  <span>快照鲜度</span>
                </div>
                <div className="text-sm font-semibold tabular-nums mm-text-primary mt-1">
                  {signals.backupAgeDays} 天前
                </div>
                <div className="text-[11px] mm-text-muted">查看历史快照</div>
              </button>
            </div>

            <div>
              <div className="text-xs font-medium mm-text-secondary mb-2">本期大额支出 Top 3</div>
              {signals.topLargeBills.length === 0 ? (
                <div className="text-xs mm-text-muted py-4 text-center">本期无支出记录</div>
              ) : (
                <div className="divide-y mm-divider">
                  {signals.topLargeBills.map((b) => (
                    <button
                      key={b.index}
                      type="button"
                      onClick={() => setDrawerBill(b)}
                      className="w-full py-2.5 flex items-center justify-between gap-3 text-left hover:bg-[var(--bg-elevated)] px-2 -mx-2 transition-colors"
                    >
                      <div className="flex items-center gap-2.5 min-w-0">
                        <ColorDot color={b.categoryColor} />
                        <div className="min-w-0">
                          <div className="text-xs font-medium mm-text-primary truncate">
                            {b.merchant || b.note || b.category}
                          </div>
                          <div className="text-[11px] mm-text-muted tabular-nums truncate">
                            {b.date} · {b.category} · {b.account}
                          </div>
                        </div>
                      </div>
                      <span className="text-xs font-semibold tabular-nums mm-text-expense shrink-0">
                        {formatYuan(b.priceCents, privacyMasked)}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </Panel>

        <Panel
          className="xl:col-span-7"
          title="近期账单速览"
          subtitle="当前查询周期内最新 8 笔记录"
          action={
            <button
              type="button"
              onClick={() => drillToBills({ start: range.start, end: range.end })}
              className="text-xs font-medium mm-text-accent hover:underline"
            >
              查看全部明细 →
            </button>
          }
        >
          {data.recentBills.length === 0 ? (
            <div className="py-10 text-center text-xs mm-text-muted">本期暂无账单记录</div>
          ) : (
            <div className="divide-y mm-divider">
              {data.recentBills.map((b) => (
                <button
                  key={b.index}
                  type="button"
                  onClick={() => setDrawerBill(b)}
                  className="w-full py-2.5 flex items-center justify-between gap-3 text-left hover:bg-[var(--bg-elevated)] px-2 -mx-2 rounded-lg transition-colors"
                >
                  <div className="flex items-center gap-2.5 min-w-0">
                    <ColorDot color={b.categoryColor} />
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium mm-text-primary truncate">
                          {b.category}
                          {b.subCategory ? ` · ${b.subCategory}` : ''}
                        </span>
                        <span
                          className={`px-1.5 py-0.2 rounded text-[10px] font-medium ${billTypeBadgeClass(b.type)}`}
                        >
                          {billTypeLabel(b.type)}
                        </span>
                        {b.reimburseStatus === 'PENDING' && (
                          <span className="px-1.5 py-0.2 rounded text-[10px] font-medium mm-bg-warn-soft mm-text-warn">
                            待报销
                          </span>
                        )}
                      </div>
                      <div className="text-[11px] mm-text-secondary truncate">
                        {b.date} {b.timeText} · {b.merchant || b.note || b.account}
                      </div>
                    </div>
                  </div>
                  <div className="text-right shrink-0">
                    <div className={`text-xs font-semibold tabular-nums ${billTypeToneClass(b.type)}`}>
                      {formatYuan(b.priceCents, privacyMasked)}
                    </div>
                    <div className="text-[11px] mm-text-muted truncate max-w-[110px]">
                      {b.account}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </Panel>
      </div>

      <BillDrawer bill={drawerBill} onClose={() => setDrawerBill(null)} />
    </div>
  );
};
