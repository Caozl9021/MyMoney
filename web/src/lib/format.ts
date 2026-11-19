const MASK_STR = '****';

export function formatYuan(cents: number, masked = false): string {
  if (masked) return `¥${MASK_STR}`;
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(Math.round(cents));
  const yuan = (abs / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return `${sign}¥${yuan}`;
}

export function formatSignedYuan(cents: number, masked = false): string {
  if (masked) return MASK_STR;
  const sign = cents > 0 ? '+' : cents < 0 ? '-' : '';
  const abs = Math.abs(Math.round(cents));
  const yuan = (abs / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return `${sign}¥${yuan}`;
}

export function formatCompactYuan(cents: number, masked = false): string {
  if (masked) return MASK_STR;
  const yuan = cents / 100;
  const abs = Math.abs(yuan);
  if (abs >= 10000) {
    return `¥${(yuan / 10000).toFixed(1)}w`;
  }
  if (abs >= 1000) {
    return `¥${(yuan / 1000).toFixed(1)}k`;
  }
  return `¥${yuan.toFixed(0)}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kib = bytes / 1024;
  if (kib < 1024) return `${kib.toFixed(1)} KiB`;
  const mib = kib / 1024;
  return `${mib.toFixed(2)} MiB`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${day} ${hh}:${mm}`;
}

export function formatShortDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${m}-${day} ${hh}:${mm}`;
}

export function billTypeLabel(t: string): string {
  switch (t) {
    case 'EXPENSE':
      return '支出';
    case 'INCOME':
      return '收入';
    case 'REFUND':
      return '退款';
    case 'TRANSFER':
      return '转账';
    default:
      return t;
  }
}

export function billTypeToneClass(t: string): string {
  switch (t) {
    case 'EXPENSE':
      return 'mm-text-expense';
    case 'INCOME':
      return 'mm-text-income';
    case 'REFUND':
      return 'mm-text-refund';
    case 'TRANSFER':
      return 'mm-text-transfer';
    default:
      return 'mm-text-primary';
  }
}

export function billTypeBadgeClass(t: string): string {
  switch (t) {
    case 'EXPENSE':
      return 'mm-bg-expense-soft mm-text-expense';
    case 'INCOME':
      return 'mm-bg-income-soft mm-text-income';
    case 'REFUND':
      return 'mm-bg-refund-soft mm-text-refund';
    case 'TRANSFER':
      return 'mm-bg-transfer-soft mm-text-transfer';
    default:
      return 'mm-bg-elevated mm-text-secondary';
  }
}

export function reimburseLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待报销';
    case 'REIMBURSED':
      return '已报销';
    default:
      return '不报销';
  }
}

export const ACCOUNT_KIND_LABELS: Record<string, string> = {
  PAYMENT: '支付账户',
  SAVINGS: '储蓄卡',
  CREDIT: '信用账户',
  CASH: '现金',
  OTHER_ASSET: '其他资产',
  OTHER_DEBT: '其他负债',
};

export const ACCOUNT_KIND_ORDER = [
  'PAYMENT',
  'SAVINGS',
  'CREDIT',
  'CASH',
  'OTHER_ASSET',
  'OTHER_DEBT',
] as const;

export function entryKindLabel(kind: string): string {
  switch (kind) {
    case 'OPENING':
      return '期初余额';
    case 'ADJUSTMENT':
      return '余额调整';
    case 'BILL':
      return '账单流水';
    case 'TRANSFER':
      return '账户转账';
    case 'REIMBURSE':
      return '报销入账';
    case 'REVERSAL':
      return '撤销冲销';
    default:
      return kind;
  }
}

export function formatIsoYmd(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function parseYmd(s: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim());
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  if (Number.isNaN(d.getTime())) return null;
  return d;
}

export function computeLedgerBounds(anchor: Date, startDay: number): { start: string; end: string } {
  const clampedDay = Math.max(1, Math.min(31, startDay));
  const y = anchor.getFullYear();
  const m = anchor.getMonth();
  const daysInCurr = new Date(y, m + 1, 0).getDate();
  const effCurrStart = new Date(y, m, Math.min(clampedDay, daysInCurr));

  let startMonthYear = y;
  let startMonthIdx = m;
  if (anchor < effCurrStart) {
    startMonthIdx -= 1;
    if (startMonthIdx < 0) {
      startMonthIdx = 11;
      startMonthYear -= 1;
    }
  }
  const sdDays = new Date(startMonthYear, startMonthIdx + 1, 0).getDate();
  const startDate = new Date(startMonthYear, startMonthIdx, Math.min(clampedDay, sdDays));

  let nextYear = startMonthYear;
  let nextMonth = startMonthIdx + 1;
  if (nextMonth > 11) {
    nextMonth = 0;
    nextYear += 1;
  }
  const ndDays = new Date(nextYear, nextMonth + 1, 0).getDate();
  const nextStartDate = new Date(nextYear, nextMonth, Math.min(clampedDay, ndDays));
  const endDate = new Date(nextStartDate.getFullYear(), nextStartDate.getMonth(), nextStartDate.getDate() - 1);

  return {
    start: formatIsoYmd(startDate),
    end: formatIsoYmd(endDate),
  };
}
