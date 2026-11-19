import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../lib/session';
import { buildQuery } from '../lib/api';
import type { AnalyticsResponse } from '../types/api';
import { formatYuan } from '../lib/format';
import {
  ColorDot,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  Panel,
  RatioBar,
} from '../components/UiPrimitives';
import { CalendarHeatmap } from '../components/charts/CalendarHeatmap';
import { WeekdayHourMatrix } from '../components/charts/WeekdayHourMatrix';
import { AmountHistogram } from '../components/charts/AmountHistogram';

export const AnalyticsView: React.FC = () => {
  const {
    sessionGen,
    authedJson,
    selectedBackupId,
    periodMode,
    customRange,
    setCustomRange,
    privacyMasked,
    drillToBills,
  } = useSession();

  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);

  const qs = buildQuery({
    backupId: selectedBackupId,
    periodMode,
    start: customRange?.start,
    end: customRange?.end,
    category: selectedCategory ?? undefined,
  });

  const query = useQuery({
    queryKey: [
      'analytics',
      sessionGen,
      selectedBackupId,
      periodMode,
      customRange?.start,
      customRange?.end,
      selectedCategory,
    ],
    queryFn: ({ signal }) =>
      authedJson<AnalyticsResponse>(`/v1/admin/dashboard/analytics${qs}`, { signal }),
    staleTime: 60_000,
  });

  if (query.isLoading) return <LoadingSkeleton lines={8} />;
  if (query.isError) {
    return (
      <ErrorBanner
        message={query.error instanceof Error ? query.error.message : '无法加载收支分析'}
        onRetry={() => query.refetch()}
      />
    );
  }
  const data = query.data;
  if (!data) {
    return <EmptyState title="暂无可分析的备份数据" />;
  }

  const maxCatTrend = Math.max(
    1,
    ...data.categoryMonthlyTrend.map((m) => m.cents),
  );

  return (
    <div className="space-y-5">
      {/* 52-Week Calendar Heatmap */}
      <Panel
        title="消费日历热力图"
        subtitle={`${data.calendarHeatmap.start} ~ ${data.calendarHeatmap.end} · 点击任意日期下钻该日支出`}
      >
        <CalendarHeatmap
          days={data.calendarHeatmap.days}
          masked={privacyMasked}
          onSelectDay={(date) =>
            drillToBills({ start: date, end: date, type: 'EXPENSE' })
          }
        />
      </Panel>

      {/* Category & Subcategory Interactive Drilldown */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-5"
          title="一级分类排行"
          subtitle={`${data.range.label} · 点击分类查看二级构成`}
        >
          {data.categories.length === 0 ? (
            <div className="py-8 text-center text-xs mm-text-muted">该区间无净支出分类</div>
          ) : (
            <div className="space-y-2.5">
              {data.categories.map((c) => {
                const isActive = data.activeCategory === c.name;
                return (
                  <button
                    key={c.name}
                    type="button"
                    onClick={() => setSelectedCategory(c.name)}
                    className={`w-full text-left p-2 rounded-xl border transition-colors ${
                      isActive
                        ? 'mm-bg-accent-soft border-[var(--accent-primary)]/40'
                        : 'border-transparent hover:bg-[var(--bg-elevated)]'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2 text-xs mb-1">
                      <span className="inline-flex items-center gap-2 min-w-0">
                        <ColorDot color={c.color} />
                        <span className="font-medium mm-text-primary truncate">{c.name}</span>
                        <span className="text-[11px] mm-text-muted tabular-nums">{c.count}笔</span>
                      </span>
                      <span className="font-semibold tabular-nums mm-text-primary shrink-0">
                        {formatYuan(c.cents, privacyMasked)}
                      </span>
                    </div>
                    <RatioBar ratio={c.ratio} color={c.color} />
                  </button>
                );
              })}
            </div>
          )}
        </Panel>

        <Panel
          className="xl:col-span-7"
          title={
            data.activeCategory
              ? `「${data.activeCategory}」二级细分与近 6 期趋势`
              : '分类二级构成'
          }
          action={
            data.activeCategory && (
              <button
                type="button"
                onClick={() =>
                  drillToBills({
                    category: data.activeCategory ?? undefined,
                    start: data.range.start,
                    end: data.range.end,
                    type: 'EXPENSE',
                  })
                }
                className="text-xs font-medium mm-text-accent hover:underline"
              >
                筛选该分类账单 →
              </button>
            )
          }
        >
          <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
            <div className="space-y-2.5">
              <div className="text-xs font-medium mm-text-secondary">二级分类占比</div>
              {data.subCategories.length === 0 ? (
                <div className="py-6 text-xs mm-text-muted">该分类暂无二级子分类数据</div>
              ) : (
                data.subCategories.map((sub) => (
                  <button
                    key={sub.name}
                    type="button"
                    onClick={() =>
                      drillToBills({
                        category: data.activeCategory ?? undefined,
                        subCategory: sub.name === '无二级分类' ? undefined : sub.name,
                        start: data.range.start,
                        end: data.range.end,
                        type: 'EXPENSE',
                      })
                    }
                    className="w-full text-left space-y-1 p-1.5 -mx-1.5 rounded-lg hover:bg-[var(--bg-elevated)] transition-colors"
                  >
                    <div className="flex items-center justify-between text-xs">
                      <span className="mm-text-primary font-medium truncate">
                        {sub.name} <span className="mm-text-muted font-normal">({sub.count}笔)</span>
                      </span>
                      <span className="tabular-nums font-medium mm-text-primary">
                        {formatYuan(sub.cents, privacyMasked)}
                      </span>
                    </div>
                    <RatioBar ratio={sub.ratioInCategory} />
                  </button>
                ))
              )}
            </div>

            <div className="space-y-3">
              <div className="text-xs font-medium mm-text-secondary">近 6 个周期走势</div>
              <div className="space-y-2">
                {data.categoryMonthlyTrend.map((pt) => (
                  <div key={pt.start} className="space-y-1">
                    <div className="flex items-center justify-between text-xs tabular-nums">
                      <span className="mm-text-secondary">{pt.label}</span>
                      <span className="mm-text-primary font-medium">
                        {formatYuan(pt.cents, privacyMasked)}{' '}
                        <span className="mm-text-muted font-normal">({pt.count}笔)</span>
                      </span>
                    </div>
                    <RatioBar ratio={pt.cents / maxCatTrend} />
                  </div>
                ))}
              </div>
            </div>
          </div>
        </Panel>
      </div>

      {/* Time-of-Week Matrix + Amount Histogram */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-7"
          title="星期 × 时段消费分布"
          subtitle={data.range.label}
        >
          <WeekdayHourMatrix cells={data.weekdayHourMatrix} masked={privacyMasked} />
        </Panel>

        <Panel
          className="xl:col-span-5"
          title="单笔消费金额分布"
          subtitle="按消费区间统计笔数与总额"
        >
          <AmountHistogram buckets={data.amountBuckets} masked={privacyMasked} />
        </Panel>
      </div>

      {/* Merchants + Multi-Period Comparison */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel
          className="xl:col-span-5"
          title="高频商户与消费去向 Top 20"
          subtitle="点击商户可下钻历史账单"
        >
          {data.merchants.length === 0 ? (
            <div className="py-8 text-center text-xs mm-text-muted">该区间无消费记录</div>
          ) : (
            <div className="divide-y mm-divider max-h-[400px] overflow-y-auto pr-1">
              {data.merchants.map((m) => (
                <button
                  key={m.name}
                  type="button"
                  onClick={() =>
                    drillToBills({
                      q: m.name,
                      start: data.range.start,
                      end: data.range.end,
                      type: 'EXPENSE',
                    })
                  }
                  className="w-full py-2 flex items-center justify-between gap-2 text-left hover:bg-[var(--bg-elevated)] px-1.5 rounded-lg transition-colors text-xs"
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <ColorDot color={m.categoryColor} />
                    <span className="font-medium mm-text-primary truncate">{m.name}</span>
                    <span className="text-[11px] mm-text-muted shrink-0">{m.topCategory}</span>
                  </div>
                  <div className="flex items-center gap-3 shrink-0 tabular-nums">
                    <span className="mm-text-secondary">{m.count}笔</span>
                    <span className="font-semibold mm-text-primary w-20 text-right">
                      {formatYuan(m.cents, privacyMasked)}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          )}
        </Panel>

        <Panel
          className="xl:col-span-7"
          title="近 12 个周期横向对比"
          subtitle="点击任意周期行切换总览统计区间"
        >
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b mm-divider mm-text-secondary">
                  <th className="py-2 text-left font-medium">周期</th>
                  <th className="py-2 text-right font-medium">净支出</th>
                  <th className="py-2 text-right font-medium">收入</th>
                  <th className="py-2 text-right font-medium">结余</th>
                  <th className="py-2 text-right font-medium">储蓄率</th>
                  <th className="py-2 text-right font-medium">日均</th>
                  <th className="py-2 text-right font-medium">笔数</th>
                </tr>
              </thead>
              <tbody className="divide-y mm-divider tabular-nums">
                {data.periodComparison.map((row) => (
                  <tr
                    key={row.label}
                    onClick={() => setCustomRange({ start: row.start, end: row.end })}
                    className="hover:bg-[var(--bg-elevated)] cursor-pointer transition-colors"
                  >
                    <td className="py-2 font-medium mm-text-primary whitespace-nowrap">
                      {row.label}
                    </td>
                    <td className="py-2 text-right mm-text-expense">
                      {formatYuan(row.expenseCents, privacyMasked)}
                    </td>
                    <td className="py-2 text-right mm-text-income">
                      {formatYuan(row.incomeCents, privacyMasked)}
                    </td>
                    <td className="py-2 text-right font-medium mm-text-primary">
                      {formatYuan(row.balanceCents, privacyMasked)}
                    </td>
                    <td className="py-2 text-right mm-text-secondary">
                      {row.savingsRate !== null ? `${row.savingsRate.toFixed(1)}%` : '—'}
                    </td>
                    <td className="py-2 text-right mm-text-secondary">
                      {formatYuan(row.dailyAvgExpenseCents, privacyMasked)}
                    </td>
                    <td className="py-2 text-right mm-text-muted">{row.billCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </div>
  );
};
