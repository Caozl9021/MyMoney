import React, { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, GitCompare, HardDrive } from 'lucide-react';
import { useSession } from '../lib/session';
import { buildQuery, downloadAdminFile } from '../lib/api';
import type {
  BackupOverviewResponse,
  BackupSourceDto,
  DiffResponse,
  SnapshotsListResponse,
  StorageAuditResponse,
} from '../types/api';
import {
  formatBytes,
  formatDateTime,
  formatSignedYuan,
  formatYuan,
} from '../lib/format';
import { ErrorBanner, LoadingSkeleton, Panel } from '../components/UiPrimitives';

export const BackupsView: React.FC = () => {
  const queryClient = useQueryClient();
  const {
    adminToken,
    sessionGen,
    authedJson,
    selectedBackupId,
    setSelectedBackupId,
    setActiveView,
    privacyMasked,
    lock,
  } = useSession();

  const [offset, setOffset] = useState(0);
  const [leftId, setLeftId] = useState<string>('');
  const [rightId, setRightId] = useState<string>('');
  const [opError, setOpError] = useState<string | null>(null);

  const overviewQ = useQuery({
    queryKey: ['backup-overview', sessionGen],
    queryFn: ({ signal }) =>
      authedJson<BackupOverviewResponse>('/v1/admin/backup-overview', { signal }),
  });

  const backupsQ = useQuery({
    queryKey: ['admin-backups', sessionGen, offset],
    queryFn: ({ signal }) =>
      authedJson<SnapshotsListResponse>(`/v1/admin/backups?limit=20&offset=${offset}`, { signal }),
  });

  const sourcesQ = useQuery({
    queryKey: ['backup-sources', sessionGen],
    queryFn: ({ signal }) =>
      authedJson<{ items: BackupSourceDto[] }>('/v1/admin/backup-sources', { signal }),
  });

  const storageQ = useQuery({
    queryKey: ['storage-audit', sessionGen],
    queryFn: ({ signal }) =>
      authedJson<StorageAuditResponse>('/v1/admin/storage-audit', { signal }),
  });

  const items = backupsQ.data?.items ?? [];

  useEffect(() => {
    if (items.length >= 2 && !leftId && !rightId) {
      setRightId(items[0].backupId);
      setLeftId(items[1].backupId);
    }
  }, [items, leftId, rightId]);

  const diffQs = buildQuery({ leftBackupId: leftId, rightBackupId: rightId });
  const diffQ = useQuery({
    queryKey: ['snapshot-diff', sessionGen, leftId, rightId],
    queryFn: ({ signal }) =>
      authedJson<DiffResponse>(`/v1/admin/dashboard/diff${diffQs}`, { signal }),
    enabled: Boolean(leftId && rightId && leftId !== rightId),
  });

  const archiveMutation = useMutation({
    mutationFn: ({ deviceId, archived }: { deviceId: string; archived: boolean }) =>
      authedJson<{ archived: boolean }>(`/v1/admin/backup-sources/${deviceId}/archive`, {
        method: 'POST',
        body: { archived },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['backup-sources', sessionGen] });
    },
    onError: (e) => setOpError(e instanceof Error ? e.message : '操作失败'),
  });

  const handleDownload = async (backupId: string) => {
    if (!adminToken) return;
    setOpError(null);
    try {
      await downloadAdminFile(
        `/v1/admin/backups/${backupId}/download`,
        adminToken,
        `mymoney-backup-${backupId.slice(0, 8)}.json`,
        () => lock(),
      );
    } catch (e) {
      setOpError(e instanceof Error ? e.message : '下载失败');
    }
  };

  if (overviewQ.isLoading && backupsQ.isLoading) {
    return <LoadingSkeleton lines={6} />;
  }

  return (
    <div className="space-y-5">
      {(overviewQ.isError || backupsQ.isError) && (
        <ErrorBanner message="加载运维信息失败，请重试" onRetry={() => overviewQ.refetch()} />
      )}
      {opError && (
        <div className="mm-card p-3 mm-bg-expense-soft mm-text-expense text-xs">{opError}</div>
      )}

      {/* Top Ops Summary + Storage Audit */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">活跃设备数</div>
          <div className="text-xl font-semibold tabular-nums mm-text-primary mt-1">
            {overviewQ.data?.activeDevices ?? 0}
          </div>
          <div className="text-[11px] mm-text-muted mt-1">未撤销认证设备</div>
        </div>
        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">有效云备份数</div>
          <div className="text-xl font-semibold tabular-nums mm-text-primary mt-1">
            {overviewQ.data?.liveBackups ?? 0}
          </div>
          <div className="text-[11px] mm-text-muted mt-1 tabular-nums">
            共占用 {formatBytes(overviewQ.data?.liveBytes ?? 0)}
          </div>
        </div>
        <div className="mm-card p-4">
          <div className="text-xs mm-text-secondary">最近备份上传</div>
          <div className="text-sm font-semibold tabular-nums mm-text-primary mt-1.5">
            {formatDateTime(overviewQ.data?.lastBackupAt)}
          </div>
          <div className="text-[11px] mm-text-muted mt-1">
            服务时间 {formatDateTime(overviewQ.data?.serverTime)}
          </div>
        </div>
        <div className="mm-card p-4">
          <div className="flex items-center justify-between text-xs mm-text-secondary">
            <span>存储卷只读巡检</span>
            <HardDrive className="w-3.5 h-3.5 mm-text-accent" />
          </div>
          {storageQ.data ? (
            <>
              <div className="text-xl font-semibold tabular-nums mm-text-primary mt-1">
                {Math.max(
                  0,
                  storageQ.data.indexedCount -
                    storageQ.data.missingFiles.length -
                    storageQ.data.corruptFiles.length,
                )}{' '}
                / {storageQ.data.indexedCount}
              </div>
              <div
                className={`text-[11px] mt-1 tabular-nums ${
                  storageQ.data.missingFiles.length > 0 || storageQ.data.corruptFiles.length > 0
                    ? 'mm-text-expense'
                    : 'mm-text-income'
                }`}
              >
                缺失 {storageQ.data.missingFiles.length} · 损坏 {storageQ.data.corruptFiles.length} ·
                孤儿 {storageQ.data.orphanFiles.length}
              </div>
            </>
          ) : (
            <div className="text-xs mm-text-muted mt-2">巡检中…</div>
          )}
        </div>
      </div>

      {/* Backups List */}
      <Panel
        title="全量未删除备份快照"
        subtitle="点击「分析」切换全局数据源，或选择两份快照执行 Diff 对比"
      >
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b mm-divider mm-text-secondary">
                <th className="py-2 text-left font-medium">设备名称</th>
                <th className="py-2 text-left font-medium">快照导出时间</th>
                <th className="py-2 text-right font-medium">账单数</th>
                <th className="py-2 text-right font-medium">大小</th>
                <th className="py-2 text-left font-medium">版本</th>
                <th className="py-2 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y mm-divider tabular-nums">
              {items.map((b) => {
                const isCurrent = selectedBackupId === b.backupId;
                return (
                  <tr key={b.backupId} className="hover:bg-[var(--bg-elevated)]">
                    <td className="py-2.5 pr-3 font-medium mm-text-primary whitespace-nowrap">
                      {b.deviceName}
                      {isCurrent && (
                        <span className="ml-2 px-1.5 py-0.5 rounded text-[10px] mm-bg-accent-soft mm-text-accent">
                          当前分析源
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 pr-3 mm-text-secondary whitespace-nowrap">
                      {formatDateTime(b.snapshotAt)}
                    </td>
                    <td className="py-2.5 pr-3 text-right mm-text-primary">{b.billCount}</td>
                    <td className="py-2.5 pr-3 text-right mm-text-secondary">
                      {formatBytes(b.sizeBytes)}
                    </td>
                    <td className="py-2.5 pr-3 mm-text-muted whitespace-nowrap">
                      {b.appVersion} (v{b.backupSchema})
                    </td>
                    <td className="py-2.5 text-right whitespace-nowrap">
                      <div className="inline-flex items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => {
                            setSelectedBackupId(b.backupId);
                            setActiveView('overview');
                          }}
                          className="px-2 py-1 rounded-lg mm-bg-accent-soft mm-text-accent text-[11px] font-medium"
                        >
                          分析
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDownload(b.backupId)}
                          className="inline-flex items-center gap-1 px-2 py-1 rounded-lg border mm-divider mm-bg-card mm-text-primary text-[11px]"
                        >
                          <Download className="w-3 h-3" />
                          <span>下载</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <div className="mt-3 pt-3 border-t mm-divider flex items-center justify-between text-xs">
          <span className="mm-text-muted tabular-nums">当前偏移 {offset}</span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={offset === 0}
              onClick={() => setOffset((o) => Math.max(0, o - 20))}
              className="px-3 py-1 rounded-lg border mm-divider mm-bg-card disabled:opacity-40"
            >
              上一页
            </button>
            <button
              type="button"
              disabled={!backupsQ.data?.hasMore}
              onClick={() => setOffset(backupsQ.data?.nextOffset ?? offset + 20)}
              className="px-3 py-1 rounded-lg border mm-divider mm-bg-card disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        </div>
      </Panel>

      {/* Two-Snapshot Structural Diff */}
      <Panel
        title="历史快照结构化差异对比 (Diff)"
        subtitle="对比同一家庭空间内任意两份快照的账单变更与净资产变动"
      >
        <div className="flex flex-wrap items-center gap-3 mb-4">
          <select
            aria-label="基准旧快照"
            value={leftId}
            onChange={(e) => setLeftId(e.target.value)}
            className="mm-input px-2.5 py-1.5 text-xs tabular-nums"
          >
            <option value="">选择旧快照（基准）</option>
            {items.map((b) => (
              <option key={b.backupId} value={b.backupId}>
                {b.deviceName} · {formatDateTime(b.snapshotAt)} ({b.billCount}笔)
              </option>
            ))}
          </select>

          <GitCompare className="w-4 h-4 mm-text-muted shrink-0" />

          <select
            aria-label="目标新快照"
            value={rightId}
            onChange={(e) => setRightId(e.target.value)}
            className="mm-input px-2.5 py-1.5 text-xs tabular-nums"
          >
            <option value="">选择新快照（目标）</option>
            {items.map((b) => (
              <option key={b.backupId} value={b.backupId}>
                {b.deviceName} · {formatDateTime(b.snapshotAt)} ({b.billCount}笔)
              </option>
            ))}
          </select>
        </div>

        {leftId && rightId && leftId === rightId && (
          <div className="text-xs mm-text-muted py-4">请选择两份不同的快照进行对比。</div>
        )}

        {diffQ.isLoading && <div className="text-xs mm-text-muted py-4">正在计算快照差异…</div>}
        {diffQ.data && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs tabular-nums">
              <div className="mm-card-subtle p-3">
                <div className="mm-text-secondary">账单变化</div>
                <div className="text-sm font-semibold mm-text-primary mt-1">
                  +{diffQ.data.summaryDelta.addedCount} / -{diffQ.data.summaryDelta.removedCount} / 改
                  {diffQ.data.summaryDelta.modifiedCount}
                </div>
              </div>
              <div className="mm-card-subtle p-3">
                <div className="mm-text-secondary">总支出差额</div>
                <div className="text-sm font-semibold mm-text-expense mt-1">
                  {formatSignedYuan(diffQ.data.summaryDelta.expenseDiffCents, privacyMasked)}
                </div>
              </div>
              <div className="mm-card-subtle p-3">
                <div className="mm-text-secondary">总收入差额</div>
                <div className="text-sm font-semibold mm-text-income mt-1">
                  {formatSignedYuan(diffQ.data.summaryDelta.incomeDiffCents, privacyMasked)}
                </div>
              </div>
              <div className="mm-card-subtle p-3">
                <div className="mm-text-secondary">净资产差额</div>
                <div className="text-sm font-semibold mm-text-accent mt-1">
                  {formatSignedYuan(diffQ.data.summaryDelta.netWorthDiffCents, privacyMasked)}
                </div>
              </div>
            </div>

            {diffQ.data.accountDiffs.length > 0 && (
              <div>
                <div className="text-xs font-medium mm-text-secondary mb-2">账户余额变动</div>
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2 text-xs tabular-nums">
                  {diffQ.data.accountDiffs.map((ad) => (
                    <div
                      key={ad.accountId}
                      className="mm-card-subtle px-3 py-2 flex items-center justify-between"
                    >
                      <span className="mm-text-primary font-medium truncate">{ad.name}</span>
                      <span
                        className={
                          privacyMasked
                            ? 'mm-text-primary'
                            : ad.diffCents >= 0
                              ? 'mm-text-income'
                              : 'mm-text-expense'
                        }
                      >
                        {formatSignedYuan(ad.diffCents, privacyMasked)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {(diffQ.data.addedBills.length > 0 ||
              diffQ.data.modifiedBills.length > 0 ||
              diffQ.data.removedBills.length > 0) && (
              <div className="space-y-2 text-xs">
                {diffQ.data.addedBills.slice(0, 10).map((row) => (
                  <div
                    key={`add-${row.key}`}
                    className="px-3 py-2 rounded-lg mm-bg-income-soft flex items-center justify-between gap-2 tabular-nums"
                  >
                    <span className="truncate">
                      <strong className="mm-text-income">[新增]</strong> {row.right?.date}{' '}
                      {row.right?.category} · {row.right?.merchant || row.right?.note || '—'}
                    </span>
                    <span className="font-semibold shrink-0">
                      {row.right ? formatYuan(row.right.priceCents, privacyMasked) : ''}
                    </span>
                  </div>
                ))}
                {diffQ.data.modifiedBills.slice(0, 10).map((row) => (
                  <div
                    key={`mod-${row.key}`}
                    className="px-3 py-2 rounded-lg mm-bg-warn-soft flex items-center justify-between gap-2 tabular-nums"
                  >
                    <span className="truncate">
                      <strong className="mm-text-warn">[修改: {row.changedFields.join('、')}]</strong>{' '}
                      {row.right?.date} {row.right?.category}
                    </span>
                    <span className="font-semibold shrink-0">
                      {row.right ? formatYuan(row.right.priceCents, privacyMasked) : ''}
                    </span>
                  </div>
                ))}
                {diffQ.data.removedBills.slice(0, 10).map((row) => (
                  <div
                    key={`rem-${row.key}`}
                    className="px-3 py-2 rounded-lg mm-bg-expense-soft flex items-center justify-between gap-2 tabular-nums"
                  >
                    <span className="truncate">
                      <strong className="mm-text-expense">[删除]</strong> {row.left?.date}{' '}
                      {row.left?.category} · {row.left?.merchant || row.left?.note || '—'}
                    </span>
                    <span className="font-semibold shrink-0">
                      {row.left ? formatYuan(row.left.priceCents, privacyMasked) : ''}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Panel>

      {/* Devices Sources Management */}
      <Panel title="家庭设备与归档状态">
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b mm-divider mm-text-secondary">
                <th className="py-2 text-left font-medium">设备名称</th>
                <th className="py-2 text-left font-medium">最近同步时间</th>
                <th className="py-2 text-left font-medium">最近备份</th>
                <th className="py-2 text-right font-medium">备份数</th>
                <th className="py-2 text-right font-medium">状态 / 操作</th>
              </tr>
            </thead>
            <tbody className="divide-y mm-divider tabular-nums">
              {sourcesQ.data?.items.map((src) => (
                <tr key={src.deviceId}>
                  <td className="py-2.5 pr-3 font-medium mm-text-primary">{src.name}</td>
                  <td className="py-2.5 pr-3 mm-text-secondary">
                    {formatDateTime(src.lastSeenAt)}
                  </td>
                  <td className="py-2.5 pr-3 mm-text-secondary">
                    {formatDateTime(src.lastBackupAt)}
                  </td>
                  <td className="py-2.5 pr-3 text-right mm-text-primary">{src.backupCount}</td>
                  <td className="py-2.5 text-right whitespace-nowrap">
                    <div className="inline-flex items-center gap-2">
                      {src.revoked ? (
                        <span className="px-2 py-0.5 rounded mm-bg-expense-soft mm-text-expense text-[11px]">
                          已撤销
                        </span>
                      ) : src.archived ? (
                        <span className="px-2 py-0.5 rounded mm-bg-elevated mm-text-muted text-[11px]">
                          已归档
                        </span>
                      ) : (
                        <span className="px-2 py-0.5 rounded mm-bg-income-soft mm-text-income text-[11px]">
                          正常
                        </span>
                      )}
                      {!src.revoked && (
                        <button
                          type="button"
                          disabled={archiveMutation.isPending}
                          onClick={() =>
                            archiveMutation.mutate({
                              deviceId: src.deviceId,
                              archived: !src.archived,
                            })
                          }
                          className="px-2.5 py-1 rounded-lg border mm-divider mm-bg-card text-[11px]"
                        >
                          {src.archived ? '取消归档' : '归档'}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  );
};
