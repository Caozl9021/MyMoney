import React, { useEffect } from 'react';
import { X } from 'lucide-react';
import type { BillDto } from '../types/api';
import {
  billTypeBadgeClass,
  billTypeLabel,
  billTypeToneClass,
  formatDateTime,
  formatYuan,
  reimburseLabel,
} from '../lib/format';
import { useSession } from '../lib/session';
import { ColorDot } from './UiPrimitives';

export const BillDrawer: React.FC<{
  bill: BillDto | null;
  onClose: () => void;
}> = ({ bill, onClose }) => {
  const { privacyMasked } = useSession();

  useEffect(() => {
    if (!bill) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [bill, onClose]);

  if (!bill) return null;

  const kvRows: { label: string; value: string | null | undefined; mono?: boolean }[] = [
    { label: '记录时间', value: `${bill.date} ${bill.timeText}` },
    {
      label: '一级 / 二级分类',
      value: bill.subCategory ? `${bill.category} · ${bill.subCategory}` : bill.category,
    },
    {
      label: bill.type === 'TRANSFER' ? '转出账户' : '所属账户',
      value: bill.account,
    },
    ...(bill.transferAccount
      ? [{ label: '转入账户', value: bill.transferAccount }]
      : []),
    ...(bill.merchant ? [{ label: '商户原文', value: bill.merchant }] : []),
    ...(bill.productName ? [{ label: '商品明细', value: bill.productName }] : []),
    ...(bill.note ? [{ label: '账单备注', value: bill.note }] : []),
    ...(bill.method ? [{ label: '支付方式', value: bill.method }] : []),
    ...(bill.orderTail ? [{ label: '订单尾号', value: bill.orderTail, mono: true }] : []),
    ...(bill.transferFeeCents > 0
      ? [
          {
            label: '转账手续费',
            value: formatYuan(bill.transferFeeCents, privacyMasked),
            mono: true,
          },
        ]
      : []),
    {
      label: '报销状态',
      value: reimburseLabel(bill.reimburseStatus),
    },
    ...(bill.reimbursedAt
      ? [
          {
            label: '报销到账时间',
            value: formatDateTime(new Date(bill.reimbursedAt).toISOString()),
          },
        ]
      : []),
    {
      label: '余额联动',
      value: bill.assetLinked ? `已联动资产流水${bill.assetKey ? ` (${bill.assetKey})` : ''}` : '未计入账户余额',
    },
    { label: '记账来源渠道', value: bill.channel },
    { label: '识别策略', value: bill.strategy, mono: true },
    ...(bill.sourcePkg ? [{ label: '来源应用包名', value: bill.sourcePkg, mono: true }] : []),
    ...(bill.feeParentKey ? [{ label: '关联转账键值', value: bill.feeParentKey, mono: true }] : []),
    ...(bill.syncId ? [{ label: '同步标识', value: bill.syncId, mono: true }] : []),
  ];

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="账单收据详情">
      <div
        className="fixed inset-0 bg-black/35"
        onClick={onClose}
        aria-hidden="true"
      />
      <aside className="relative z-10 w-full max-w-md mm-bg-card border-l mm-divider h-full flex flex-col shadow-2xl">
        <header className="px-5 py-4 border-b mm-divider flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <ColorDot color={bill.categoryColor} size={10} />
            <span className="text-sm font-semibold mm-text-primary">收据核对详情</span>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭账单详情"
            className="p-1.5 rounded-lg hover:bg-[var(--bg-elevated)] mm-text-secondary"
          >
            <X className="w-4 h-4" />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          <div className="mm-card-subtle p-5 text-center space-y-2">
            <div className="flex items-center justify-center gap-2">
              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${billTypeBadgeClass(bill.type)}`}>
                {billTypeLabel(bill.type)}
              </span>
              {bill.reimburseStatus === 'PENDING' && (
                <span className="px-2 py-0.5 rounded-full text-xs font-medium mm-bg-warn-soft mm-text-warn">
                  待报销（计入当前支出）
                </span>
              )}
              {bill.reimburseStatus === 'REIMBURSED' && (
                <span className="px-2 py-0.5 rounded-full text-xs font-medium mm-bg-elevated mm-text-secondary border mm-divider">
                  已报销（净支出已扣除）
                </span>
              )}
            </div>
            <div className={`text-3xl font-semibold tabular-nums tracking-tight ${billTypeToneClass(bill.type)}`}>
              {formatYuan(bill.priceCents, privacyMasked)}
            </div>
            <div className="text-xs mm-text-secondary">
              {bill.merchant || bill.note || bill.category}
            </div>
          </div>

          <div className="border-t mm-divider divide-y mm-divider">
            {kvRows.map((row) => (
              <div key={row.label} className="py-2.5 flex items-start justify-between gap-4 text-xs">
                <span className="mm-text-secondary shrink-0">{row.label}</span>
                <span
                  className={`mm-text-primary text-right break-all ${row.mono ? 'font-mono text-[11px] tabular-nums' : ''}`}
                >
                  {row.value || '—'}
                </span>
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
  );
};
