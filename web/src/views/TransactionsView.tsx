import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Download, RotateCcw, Search } from 'lucide-react';
import { useSession } from '../lib/session';
import { buildBillsParams, downloadAdminFile } from '../lib/api';
import type { BillDto, BillsResponse } from '../types/api';
import {
  billTypeBadgeClass,
  billTypeLabel,
  billTypeToneClass,
  formatYuan,
} from '../lib/format';
import {
  ColorDot,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  Panel,
} from '../components/UiPrimitives';
import { BillDrawer } from '../components/BillDrawer';

export const TransactionsView: React.FC = () => {
  const {
    adminToken,
    sessionGen,
    authedJson,
    selectedBackupId,
    billFilter,
    setBillFilter,
    privacyMasked,
    lock,
  } = useSession();

  const [drawerBill, setDrawerBill] = useState<BillDto | null>(null);
  const [exportErr, setExportErr] = useState<string | null>(null);

  const qs = buildBillsParams(selectedBackupId, billFilter);

  const query = useQuery({
    queryKey: ['bills', sessionGen, selectedBackupId, billFilter],
    queryFn: ({ signal }) =>
      authedJson<BillsResponse>(`/v1/admin/dashboard/bills${qs}`, { signal }),
    staleTime: 30_000,
    placeholderData: (prev) => prev,
  });

  const handleExport = async (format: 'csv' | 'json') => {
    if (!adminToken) return;
    setExportErr(null);
    const expQs = buildBillsParams(selectedBackupId, { ...billFilter, page: undefined, pageSize: undefined }, { format });
    try {
      await downloadAdminFile(
        `/v1/admin/dashboard/bills/export${expQs}`,
        adminToken,
        `mymoney-bills.${format}`,
        () => lock(),
      );
    } catch (e) {
      setExportErr(e instanceof Error ? e.message : '导出失败');
    }
  };

  const resetFilters = () => {
    setBillFilter({ sort: 'time_desc', page: 1, pageSize: 50 });
  };

  const data = query.data;
  const page = data?.page ?? 1;
  const pageSize = data?.pageSize ?? 50;
  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const selectedCatObj = data?.facets.categories.find(
    (c) => c.name === billFilter.category,
  );

  return (
    <div className="space-y-4">
      <Panel
        title="账单多维检索"
        action={
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={resetFilters}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium border mm-divider mm-bg-card mm-text-secondary hover:text-[var(--text-primary)]"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>重置</span>
            </button>
            <button
              type="button"
              onClick={() => handleExport('csv')}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-[var(--accent-primary)] text-white"
            >
              <Download className="w-3.5 h-3.5" />
              <span>导出 CSV</span>
            </button>
            <button
              type="button"
              onClick={() => handleExport('json')}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium border mm-divider mm-bg-card mm-text-primary"
            >
              <Download className="w-3.5 h-3.5" />
              <span>导出 JSON</span>
            </button>
          </div>
        }
      >
        {exportErr && (
          <div className="mb-3 rounded-lg px-3 py-2 text-xs mm-bg-expense-soft mm-text-expense">
            {exportErr}
          </div>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-6 gap-2.5">
          <div className="relative sm:col-span-2">
            <Search className="w-3.5 h-3.5 mm-text-muted absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              aria-label="搜索商户、商品、备注或尾号"
              placeholder="搜商户 / 商品 / 备注 / 尾号…"
              value={billFilter.q ?? ''}
              onChange={(e) =>
                setBillFilter((f) => ({ ...f, q: e.target.value || undefined, page: 1 }))
              }
              className="mm-input w-full pl-8 pr-3 py-1.5 text-xs"
            />
          </div>

          <select
            aria-label="收支类型"
            value={billFilter.type ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({ ...f, type: e.target.value || undefined, page: 1 }))
            }
            className="mm-input px-2.5 py-1.5 text-xs"
          >
            <option value="">全部类型</option>
            <option value="EXPENSE">支出</option>
            <option value="INCOME">收入</option>
            <option value="REFUND">退款</option>
            <option value="TRANSFER">转账</option>
          </select>

          <select
            aria-label="一级分类"
            value={billFilter.category ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({
                ...f,
                category: e.target.value || undefined,
                subCategory: undefined,
                page: 1,
              }))
            }
            className="mm-input px-2.5 py-1.5 text-xs"
          >
            <option value="">全部分类</option>
            {data?.facets.categories.map((c) => (
              <option key={c.name} value={c.name}>
                {c.name}
              </option>
            ))}
          </select>

          <select
            aria-label="二级分类"
            disabled={!selectedCatObj}
            value={billFilter.subCategory ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({
                ...f,
                subCategory: e.target.value || undefined,
                page: 1,
              }))
            }
            className="mm-input px-2.5 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">全部二级分类</option>
            {selectedCatObj?.subCategories.map((sc) => (
              <option key={sc} value={sc}>
                {sc}
              </option>
            ))}
          </select>

          <select
            aria-label="所属账户"
            value={billFilter.account ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({ ...f, account: e.target.value || undefined, page: 1 }))
            }
            className="mm-input px-2.5 py-1.5 text-xs"
          >
            <option value="">全部账户</option>
            {data?.facets.accounts.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>

          <select
            aria-label="报销状态"
            value={billFilter.reimburseStatus ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({
                ...f,
                reimburseStatus: e.target.value || undefined,
                page: 1,
              }))
            }
            className="mm-input px-2.5 py-1.5 text-xs"
          >
            <option value="">全部报销状态</option>
            <option value="PENDING">待报销</option>
            <option value="REIMBURSED">已报销</option>
            <option value="NONE">普通不报销</option>
          </select>

          <select
            aria-label="来源渠道"
            value={billFilter.channel ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({ ...f, channel: e.target.value || undefined, page: 1 }))
            }
            className="mm-input px-2.5 py-1.5 text-xs"
          >
            <option value="">全部来源渠道</option>
            {data?.facets.channels.map((ch) => (
              <option key={ch} value={ch}>
                {ch}
              </option>
            ))}
          </select>

          <input
            type="text"
            aria-label="开始日期 YYYY-MM-DD"
            placeholder="起 YYYY-MM-DD"
            value={billFilter.start ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({ ...f, start: e.target.value || undefined, page: 1 }))
            }
            className="mm-input px-2.5 py-1.5 text-xs tabular-nums"
          />

          <input
            type="text"
            aria-label="结束日期 YYYY-MM-DD"
            placeholder="止 YYYY-MM-DD"
            value={billFilter.end ?? ''}
            onChange={(e) =>
              setBillFilter((f) => ({ ...f, end: e.target.value || undefined, page: 1 }))
            }
            className="mm-input px-2.5 py-1.5 text-xs tabular-nums"
          />

          <input
            type="number"
            min={0}
            step="0.01"
            aria-label="最小金额 元"
            placeholder="最小金额 ¥"
            value={billFilter.minCents !== undefined ? billFilter.minCents / 100 : ''}
            onChange={(e) => {
              const v = e.target.value.trim();
              const cents = v ? Math.round(Number(v) * 100) : undefined;
              setBillFilter((f) => ({
                ...f,
                minCents: cents !== undefined && !Number.isNaN(cents) ? cents : undefined,
                page: 1,
              }));
            }}
            className="mm-input px-2.5 py-1.5 text-xs tabular-nums"
          />

          <select
            aria-label="排序方式"
            value={billFilter.sort ?? 'time_desc'}
            onChange={(e) =>
              setBillFilter((f) => ({ ...f, sort: e.target.value, page: 1 }))
            }
            className="mm-input px-2.5 py-1.5 text-xs"
          >
            <option value="time_desc">时间新到旧</option>
            <option value="time_asc">时间旧到新</option>
            <option value="price_desc">金额大到小</option>
            <option value="price_asc">金额小到大</option>
          </select>
        </div>

        {/* Dynamic Summary Bar */}
        {data && (
          <div className="mt-4 pt-3 border-t mm-divider flex flex-wrap items-center gap-x-5 gap-y-2 text-xs tabular-nums">
            <span>
              命中 <strong className="mm-text-primary">{data.summary.totalCount}</strong> 笔
            </span>
            <span className="mm-text-expense">
              净支出 {formatYuan(data.summary.expenseCents, privacyMasked)}
            </span>
            <span className="mm-text-income">
              收入 {formatYuan(data.summary.incomeCents, privacyMasked)}
            </span>
            {data.summary.refundCents > 0 && (
              <span className="mm-text-refund">
                退款 {formatYuan(data.summary.refundCents, privacyMasked)}
              </span>
            )}
            <span className="mm-text-primary">
              净额 {formatYuan(data.summary.balanceCents, privacyMasked)}
            </span>
            <span className="mm-text-secondary">
              支出笔均 {formatYuan(data.summary.avgExpenseCents, privacyMasked)}
            </span>
          </div>
        )}
      </Panel>

      {query.isLoading && !data ? (
        <LoadingSkeleton lines={8} />
      ) : query.isError ? (
        <ErrorBanner
          message={query.error instanceof Error ? query.error.message : '加载账单失败'}
          onRetry={() => query.refetch()}
        />
      ) : !data || data.items.length === 0 ? (
        <EmptyState title="当前筛选条件下没有匹配账单" />
      ) : (
        <div className="mm-card overflow-hidden">
          {/* Desktop Table */}
          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b mm-divider mm-bg-elevated mm-text-secondary">
                  <th className="py-2.5 px-4 text-left font-medium">时间</th>
                  <th className="py-2.5 px-3 text-left font-medium">分类</th>
                  <th className="py-2.5 px-3 text-left font-medium">商户 / 备注</th>
                  <th className="py-2.5 px-3 text-left font-medium">账户</th>
                  <th className="py-2.5 px-3 text-left font-medium">来源</th>
                  <th className="py-2.5 px-4 text-right font-medium">金额</th>
                </tr>
              </thead>
              <tbody className="divide-y mm-divider">
                {data.items.map((b) => (
                  <tr
                    key={b.index}
                    onClick={() => setDrawerBill(b)}
                    className="hover:bg-[var(--bg-elevated)] cursor-pointer transition-colors"
                  >
                    <td className="py-2.5 px-4 whitespace-nowrap tabular-nums mm-text-secondary">
                      {b.date} {b.timeText}
                    </td>
                    <td className="py-2.5 px-3 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1.5">
                        <ColorDot color={b.categoryColor} />
                        <span className="font-medium mm-text-primary">{b.category}</span>
                        {b.subCategory && (
                          <span className="mm-text-muted">· {b.subCategory}</span>
                        )}
                      </span>
                    </td>
                    <td className="py-2.5 px-3 max-w-[280px] truncate mm-text-primary">
                      {b.merchant || b.productName || b.note || '—'}
                      {b.merchant && b.note && (
                        <span className="mm-text-muted ml-1.5">· {b.note}</span>
                      )}
                    </td>
                    <td className="py-2.5 px-3 whitespace-nowrap mm-text-secondary">
                      {b.account}
                      {b.transferAccount ? ` → ${b.transferAccount}` : ''}
                    </td>
                    <td className="py-2.5 px-3 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1">
                        <span
                          className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${billTypeBadgeClass(b.type)}`}
                        >
                          {billTypeLabel(b.type)}
                        </span>
                        {b.reimburseStatus === 'PENDING' && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-medium mm-bg-warn-soft mm-text-warn">
                            待报销
                          </span>
                        )}
                        {b.reimburseStatus === 'REIMBURSED' && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] mm-bg-elevated mm-text-muted">
                            已报销
                          </span>
                        )}
                        <span className="text-[11px] mm-text-muted">{b.channel}</span>
                      </span>
                    </td>
                    <td className={`py-2.5 px-4 text-right whitespace-nowrap font-semibold tabular-nums ${billTypeToneClass(b.type)}`}>
                      {formatYuan(b.priceCents, privacyMasked)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Mobile Compact List */}
          <div className="md:hidden divide-y mm-divider">
            {data.items.map((b) => (
              <button
                key={b.index}
                type="button"
                onClick={() => setDrawerBill(b)}
                className="w-full p-3.5 flex items-center justify-between gap-3 text-left"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-1.5">
                    <ColorDot color={b.categoryColor} />
                    <span className="text-xs font-medium mm-text-primary truncate">
                      {b.category}
                      {b.subCategory ? ` · ${b.subCategory}` : ''}
                    </span>
                    {b.reimburseStatus === 'PENDING' && (
                      <span className="text-[10px] px-1 rounded mm-bg-warn-soft mm-text-warn">
                        待报销
                      </span>
                    )}
                  </div>
                  <div className="text-[11px] mm-text-secondary truncate mt-0.5">
                    {b.date} {b.timeText} · {b.merchant || b.note || b.account}
                  </div>
                </div>
                <div className={`text-xs font-semibold tabular-nums shrink-0 ${billTypeToneClass(b.type)}`}>
                  {formatYuan(b.priceCents, privacyMasked)}
                </div>
              </button>
            ))}
          </div>

          {/* Pagination Footer */}
          <div className="px-4 py-3 border-t mm-divider flex items-center justify-between text-xs">
            <span className="mm-text-secondary tabular-nums">
              第 {page} / {totalPages} 页 · 共 {total} 笔
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={page <= 1}
                onClick={() => setBillFilter((f) => ({ ...f, page: Math.max(1, page - 1) }))}
                className="px-3 py-1 rounded-lg border mm-divider mm-bg-card mm-text-primary disabled:opacity-40"
              >
                上一页
              </button>
              <button
                type="button"
                disabled={page >= totalPages}
                onClick={() => setBillFilter((f) => ({ ...f, page: Math.min(totalPages, page + 1) }))}
                className="px-3 py-1 rounded-lg border mm-divider mm-bg-card mm-text-primary disabled:opacity-40"
              >
                下一页
              </button>
            </div>
          </div>
        </div>
      )}

      <BillDrawer bill={drawerBill} onClose={() => setDrawerBill(null)} />
    </div>
  );
};
