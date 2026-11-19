export type ThemeMode = 'light' | 'dark';
export type PeriodMode = 'ledger' | 'calendar';
export type ViewId =
  | 'overview'
  | 'analytics'
  | 'transactions'
  | 'assets'
  | 'audit'
  | 'backups'
  | 'connect'
  | 'settings';

export interface SnapshotMetaDto {
  backupId: string;
  deviceId: string;
  deviceName: string;
  appVersion: string;
  backupSchema: number;
  sha256: string;
  sizeBytes: number;
  billCount: number;
  snapshotAt: string;
  uploadedAt: string;
  monthStartDay?: number;
  overlayRevision?: number;
  overlayUpdatedAt?: string | null;
  overlayCount?: number;
  accountCount?: number;
  entryCount?: number;
  deletionCount?: number;
}

/** 轻量实时同步探针 `GET /v1/admin/dashboard/sync-head`。 */
export interface SyncHeadDto {
  backupId: string;
  deviceId: string;
  deviceName: string;
  snapshotAt: string;
  overlayRevision: number;
  overlayUpdatedAt: string | null;
  billCount: number;
  monthStartDay: number;
}

export interface SnapshotsListResponse {
  defaultBackupId: string | null;
  items: SnapshotMetaDto[];
  hasMore: boolean;
  nextOffset: number;
}

export interface DateRangeInfo {
  start: string;
  end: string;
  fromMillis: number;
  toMillis: number;
  days: number;
  isPartial: boolean;
  elapsedDays: number;
  compareStart: string;
  compareEnd: string;
  compareFromMillis: number;
  compareToMillis: number;
  periodMode: PeriodMode;
  effectiveStartDay: number;
  label: string;
}

export interface PeriodTotalsDto {
  expenseCents: number;
  incomeCents: number;
  refundCents: number;
  transferCents: number;
  balanceCents: number;
  expenseCount: number;
  incomeCount: number;
  refundCount: number;
  transferCount: number;
  totalCount: number;
  pendingReimburseCents: number;
  pendingReimburseCount: number;
  reimbursedCents: number;
  reimbursedCount: number;
}

export interface DeltaInfo {
  previousCents: number;
  previousCount: number;
  diffCents: number;
  percent: number | null;
  direction: 'up' | 'down' | 'flat';
  tone: 'good' | 'bad' | 'neutral';
  smallBase: boolean;
  baselineLabel: string;
}

export interface AssetSummaryDto {
  assetsCents: number;
  debtsCents: number;
  netCents: number;
  unknownCount: number;
}

export interface BillDto {
  index: number;
  localId: number | null;
  syncId: string;
  priceCents: number;
  type: 'EXPENSE' | 'INCOME' | 'REFUND' | 'TRANSFER' | string;
  timeMillis: number;
  timeIso: string;
  date: string;
  timeText: string;
  createdAt: number;
  category: string;
  rawCategory: string | null;
  subCategory: string | null;
  categoryColor: string;
  account: string;
  accountId: string | null;
  transferAccount: string | null;
  transferAccountId: string | null;
  merchant: string | null;
  productName: string | null;
  productSource: string | null;
  note: string | null;
  noteOrigin: string | null;
  noteTemplateSnapshot: string | null;
  method: string | null;
  orderTail: string | null;
  sourcePkg: string;
  strategy: string;
  channel: string;
  recognitionId: string | null;
  assetLinked: boolean;
  assetKey: string;
  transferFeeCents: number;
  feeParentKey: string | null;
  reimburseStatus: 'NONE' | 'PENDING' | 'REIMBURSED' | string;
  reimbursedAt: number | null;
  reimburseAccountId: string | null;
  countsInExpense: boolean;
}

export interface TrendBucketDto {
  key: string;
  label: string;
  start: string;
  end: string;
  expenseCents: number;
  incomeCents: number;
  refundCents: number;
  balanceCents: number;
  prevExpenseCents: number;
  prevIncomeCents: number;
  count: number;
  isPartial: boolean;
  isFuture: boolean;
}

export interface AnnualFlowBucketDto {
  key: string;
  label: string;
  start: string;
  end: string;
  expenseCents: number;
  incomeCents: number;
  refundCents: number;
  balanceCents: number;
  isPartial: boolean;
}

export interface RankItemDto {
  name: string;
  category?: string;
  color?: string;
  cents: number;
  count: number;
  avgCents: number;
  ratio: number;
  delta?: DeltaInfo;
}

export interface OverviewResponse {
  snapshot: SnapshotMetaDto;
  range: DateRangeInfo;
  totals: PeriodTotalsDto;
  kpis: {
    netExpense: { cents: number; count: number; delta: DeltaInfo };
    income: { cents: number; count: number; delta: DeltaInfo };
    balance: { cents: number; refundCents: number; savingsRate: number | null; delta: DeltaInfo };
    dailyAvgExpense: { cents: number; elapsedDays: number; delta: DeltaInfo };
    netWorth: AssetSummaryDto;
  };
  trendSeries: TrendBucketDto[];
  annualFlow: AnnualFlowBucketDto[];
  topCategories: RankItemDto[];
  topSubCategories: RankItemDto[];
  topAccounts: RankItemDto[];
  signals: {
    pendingReimburseCents: number;
    pendingReimburseCount: number;
    periodUncategorizedCount: number;
    totalUncategorizedCount: number;
    backupAgeDays: number;
    topLargeBills: BillDto[];
  };
  recentBills: BillDto[];
}

export interface HeatmapDayDto {
  date: string;
  weekday: number;
  cents: number;
  count: number;
  tier: number;
  inRange: boolean;
  topCategories: { name: string; cents: number }[];
}

export interface MatrixCellDto {
  weekday: number;
  weekdayLabel: string;
  slot: number;
  slotLabel: string;
  cents: number;
  count: number;
  intensity: number;
}

export interface SubCategoryStatDto {
  name: string;
  cents: number;
  count: number;
  avgCents: number;
  ratioInCategory: number;
}

export interface CategoryMonthlyTrendDto {
  label: string;
  start: string;
  end: string;
  cents: number;
  count: number;
}

export interface MerchantStatDto {
  name: string;
  cents: number;
  count: number;
  avgCents: number;
  topCategory: string;
  categoryColor: string;
  ratio: number;
}

export interface AmountBucketDto {
  label: string;
  count: number;
  cents: number;
  countRatio: number;
  centsRatio: number;
}

export interface PeriodComparisonRowDto {
  label: string;
  start: string;
  end: string;
  expenseCents: number;
  incomeCents: number;
  refundCents: number;
  balanceCents: number;
  savingsRate: number | null;
  billCount: number;
  expenseCount: number;
  dailyAvgExpenseCents: number;
}

export interface AnalyticsResponse {
  snapshot: SnapshotMetaDto;
  range: DateRangeInfo;
  calendarHeatmap: {
    start: string;
    end: string;
    thresholds: number[];
    days: HeatmapDayDto[];
  };
  weekdayHourMatrix: MatrixCellDto[];
  categories: RankItemDto[];
  activeCategory: string | null;
  subCategories: SubCategoryStatDto[];
  categoryMonthlyTrend: CategoryMonthlyTrendDto[];
  merchants: MerchantStatDto[];
  amountBuckets: AmountBucketDto[];
  periodComparison: PeriodComparisonRowDto[];
}

export interface BillsResponse {
  snapshot: SnapshotMetaDto;
  summary: {
    totalCount: number;
    expenseCount: number;
    expenseCents: number;
    incomeCents: number;
    refundCents: number;
    transferCents: number;
    balanceCents: number;
    avgExpenseCents: number;
  };
  facets: {
    categories: { name: string; color: string; subCategories: string[] }[];
    accounts: string[];
    channels: string[];
    strategies: string[];
  };
  total: number;
  page: number;
  pageSize: number;
  items: BillDto[];
}

export interface AssetAccountDto {
  id: string;
  name: string;
  kind: string;
  aliases: string[];
  sortOrder: number;
  archived: boolean;
  included: boolean;
  initialized: boolean;
  balanceCents: number;
  entryCount: number;
  createdAt: number;
  updatedAt: number;
}

export interface AssetTimelinePointDto {
  date: string;
  timestampMillis: number;
  assetsCents: number;
  debtsCents: number;
  netCents: number;
  accountBalances: Record<string, number>;
}

export interface AssetEntryDto {
  id: string;
  operationId: string;
  accountId: string;
  deltaCents: number;
  runningBalanceCents: number;
  kind: string;
  note: string;
  createdAt: number;
  timeIso: string;
  billKey: string | null;
  reversalOf: string | null;
}

export interface AssetsResponse {
  snapshot: SnapshotMetaDto;
  summary: AssetSummaryDto;
  accounts: AssetAccountDto[];
  timeline: AssetTimelinePointDto[];
  selectedAccountId: string | null;
  accountEntries: AssetEntryDto[];
}

export interface ChannelStatDto {
  name: string;
  count: number;
  expenseCents: number;
  ratio: number;
}

export interface DeletionLogDto {
  eventId: string;
  deletedAt: number;
  deletedAtIso: string;
  origin: string;
  principalId: number;
  recognitionId: string | null;
  groupId: string;
  bills: Record<string, unknown>[];
}

export interface AuditResponse {
  snapshot: SnapshotMetaDto;
  reimburse: {
    pendingCents: number;
    pendingCount: number;
    reimbursedCents: number;
    reimbursedCount: number;
    avgReimburseDays: number | null;
    pendingBills: BillDto[];
    reimbursedBills: BillDto[];
  };
  channels: ChannelStatDto[];
  strategies: ChannelStatDto[];
  uncategorized: {
    count: number;
    expenseCents: number;
    bills: BillDto[];
  };
  deletions: DeletionLogDto[];
}

export interface BillDiffRowDto {
  key: string;
  left: BillDto | null;
  right: BillDto | null;
  changedFields: string[];
}

export interface AccountDiffDto {
  accountId: string;
  name: string;
  leftBalanceCents: number;
  rightBalanceCents: number;
  diffCents: number;
}

export interface DiffResponse {
  left: SnapshotMetaDto;
  right: SnapshotMetaDto;
  summaryDelta: {
    billCountDiff: number;
    expenseDiffCents: number;
    incomeDiffCents: number;
    refundDiffCents: number;
    balanceDiffCents: number;
    netWorthDiffCents: number;
    addedCount: number;
    removedCount: number;
    modifiedCount: number;
  };
  addedBills: BillDiffRowDto[];
  removedBills: BillDiffRowDto[];
  modifiedBills: BillDiffRowDto[];
  accountDiffs: AccountDiffDto[];
}

export interface BackupOverviewResponse {
  activeDevices: number;
  liveBackups: number;
  liveBytes: number;
  lastBackupAt: string | null;
  recentBackups: {
    backupId: string;
    deviceId: string;
    deviceName: string;
    billCount: number;
    sizeBytes: number;
    uploadedAt: string;
    snapshotAt: string;
    appVersion: string;
    backupSchema: number;
  }[];
  serverTime: string;
}

export interface BackupSourceDto {
  deviceId: string;
  name: string;
  createdAt: string;
  lastSeenAt: string | null;
  lastBackupAt: string | null;
  backupCount: number;
  ledgerChangeCount: number;
  ruleCount: number;
  archived: boolean;
  revoked: boolean;
}

export interface StorageAuditResponse {
  indexedCount: number;
  deletedCount: number;
  orphanFiles: string[];
  partialFiles: string[];
  missingFiles: string[];
  corruptFiles: string[];
  pendingRecycleFiles: string[];
}

export interface BackupSettingsResponse {
  serverUrl: string;
  ctrlUrl: string;
  networkKeyConfigured: boolean;
}

export interface ConnectCodeResponse {
  connectCode: string;
  expiresInSeconds: number;
  oneTime: boolean;
  invitationId: string;
  expiresAt: string;
  serverTime: string;
  qrEncodable: boolean;
  qrSvg: string | null;
}

export interface ConnectCodeStatusResponse {
  invitationId: string;
  status: 'PENDING' | 'CONSUMED' | 'CANCELLED' | 'EXPIRED' | string;
  expiresAt: string;
  consumedAt: string | null;
  deviceId: string | null;
  deviceName: string | null;
  cancelledAt: string | null;
  serverTime: string;
}

export interface BillFilterState {
  q?: string;
  type?: string;
  category?: string;
  subCategory?: string;
  account?: string;
  reimburseStatus?: string;
  channel?: string;
  strategy?: string;
  minCents?: number;
  maxCents?: number;
  start?: string;
  end?: string;
  sort?: string;
  page?: number;
  pageSize?: number;
}
