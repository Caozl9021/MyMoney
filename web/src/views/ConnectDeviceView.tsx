import React, { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, Copy, QrCode, XCircle } from 'lucide-react';
import { useSession } from '../lib/session';
import type {
  BackupSettingsResponse,
  ConnectCodeResponse,
  ConnectCodeStatusResponse,
} from '../types/api';
import { formatDateTime } from '../lib/format';
import { Panel } from '../components/UiPrimitives';

export const ConnectDeviceView: React.FC = () => {
  const { sessionGen, isUnlocked, authedJson } = useSession();

  const settingsQ = useQuery({
    queryKey: ['backup-settings', sessionGen],
    queryFn: ({ signal }) =>
      authedJson<BackupSettingsResponse>('/v1/admin/backup-settings', { signal }),
  });

  const [deviceName, setDeviceName] = useState('');
  const [serverUrl, setServerUrl] = useState('');
  const [ctrlUrl, setCtrlUrl] = useState('');
  const [authKey, setAuthKey] = useState('');
  const [initializedFromSettings, setInitializedFromSettings] = useState(false);

  const [issued, setIssued] = useState<ConnectCodeResponse | null>(null);
  const [statusData, setStatusData] = useState<ConnectCodeStatusResponse | null>(null);
  const [errMsg, setErrMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (settingsQ.data && !initializedFromSettings) {
      setServerUrl(settingsQ.data.serverUrl);
      setCtrlUrl(settingsQ.data.ctrlUrl);
      setInitializedFromSettings(true);
    }
  }, [settingsQ.data, initializedFromSettings]);

  // Reset sensitive invitation state immediately if session locks or switches
  useEffect(() => {
    setIssued(null);
    setStatusData(null);
    setErrMsg(null);
  }, [sessionGen]);

  // Poll invitation status every 2 seconds while PENDING
  useEffect(() => {
    if (!isUnlocked || !issued) return;
    const curStatus = statusData?.status ?? 'PENDING';
    if (curStatus !== 'PENDING') return;

    let cancelled = false;
    const invId = issued.invitationId;

    const timer = window.setInterval(async () => {
      try {
        const res = await authedJson<ConnectCodeStatusResponse>(
          `/v1/admin/connect-codes/${invId}`,
        );
        if (!cancelled) {
          setStatusData(res);
        }
      } catch {
        // 403 handler in authedJson automatically locks session
      }
    }, 2000);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [isUnlocked, issued, statusData?.status, authedJson]);

  const handleGenerate = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErrMsg(null);
    setStatusData(null);
    try {
      const res = await authedJson<ConnectCodeResponse>('/v1/admin/connect-code', {
        method: 'POST',
        body: {
          deviceName: deviceName.trim(),
          serverUrl: serverUrl.trim(),
          ctrlUrl: ctrlUrl.trim(),
          authKey: authKey.trim(),
        },
      });
      setIssued(res);
    } catch (err) {
      setErrMsg(err instanceof Error ? err.message : '生成连接码失败');
    } finally {
      setBusy(false);
    }
  };

  const handleCancel = async () => {
    if (!issued) return;
    setBusy(true);
    try {
      const res = await authedJson<ConnectCodeStatusResponse>(
        `/v1/admin/connect-codes/${issued.invitationId}/cancel`,
        { method: 'POST', body: {} },
      );
      setStatusData(res);
    } catch (err) {
      setErrMsg(err instanceof Error ? err.message : '作废连接码失败');
    } finally {
      setBusy(false);
    }
  };

  const handleCopy = async () => {
    if (!issued?.connectCode) return;
    await navigator.clipboard.writeText(issued.connectCode);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1800);
  };

  const curStatus = statusData?.status ?? (issued ? 'PENDING' : null);

  return (
    <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
      <Panel
        className="xl:col-span-6"
        title="签发一次性连接码 (MMJOIN2)"
        subtitle="生成后在麦米 App 设置 → 家庭同步中扫码或粘贴连接码"
      >
        <form onSubmit={handleGenerate} className="space-y-4">
          <div>
            <label htmlFor="cc-device-name" className="block text-xs font-medium mm-text-secondary mb-1">
              预填设备名称（可选）
            </label>
            <input
              id="cc-device-name"
              type="text"
              value={deviceName}
              onChange={(e) => setDeviceName(e.target.value)}
              placeholder="例如：小米14"
              className="mm-input w-full px-3 py-2 text-xs"
            />
          </div>

          <div>
            <label htmlFor="cc-server-url" className="block text-xs font-medium mm-text-secondary mb-1">
              家庭服务地址
            </label>
            <input
              id="cc-server-url"
              type="text"
              value={serverUrl}
              onChange={(e) => setServerUrl(e.target.value)}
              placeholder="http://home-sync:18080"
              className="mm-input w-full px-3 py-2 text-xs font-mono"
            />
          </div>

          <div>
            <label htmlFor="cc-ctrl-url" className="block text-xs font-medium mm-text-secondary mb-1">
              网络控制面地址（局域网直连可留空）
            </label>
            <input
              id="cc-ctrl-url"
              type="text"
              value={ctrlUrl}
              onChange={(e) => setCtrlUrl(e.target.value)}
              placeholder="可选，如自建 Headscale URL"
              className="mm-input w-full px-3 py-2 text-xs font-mono"
            />
          </div>

          <div>
            <label htmlFor="cc-auth-key" className="block text-xs font-medium mm-text-secondary mb-1">
              覆盖一次性组网 Key（
              {settingsQ.data?.networkKeyConfigured ? '服务端已配置默认值，留空即复用' : '当前未预设'}）
            </label>
            <input
              id="cc-auth-key"
              type="password"
              autoComplete="off"
              value={authKey}
              onChange={(e) => setAuthKey(e.target.value)}
              placeholder="留空复用服务端配置"
              className="mm-input w-full px-3 py-2 text-xs font-mono"
            />
          </div>

          {errMsg && (
            <div className="rounded-lg px-3 py-2 text-xs mm-bg-expense-soft mm-text-expense">
              {errMsg}
            </div>
          )}

          <button
            type="submit"
            disabled={busy}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-medium bg-[var(--accent-primary)] text-white disabled:opacity-50"
          >
            <QrCode className="w-4 h-4" />
            <span>{busy ? '生成中…' : '生成一次性连接码'}</span>
          </button>
        </form>
      </Panel>

      <Panel
        className="xl:col-span-6"
        title="二维码与邀请状态"
        subtitle="纯本地原生 Rust 渲染 SVG 二维码，绝不请求任何第三方二维码服务"
      >
        {!issued ? (
          <div className="py-16 text-center text-xs mm-text-muted">
            点击左侧生成一次性连接码（5 分钟有效）
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <span
                className={`px-2.5 py-1 rounded-full font-medium ${
                  curStatus === 'CONSUMED'
                    ? 'mm-bg-income-soft mm-text-income'
                    : curStatus === 'CANCELLED' || curStatus === 'EXPIRED'
                      ? 'mm-bg-expense-soft mm-text-expense'
                      : 'mm-bg-accent-soft mm-text-accent'
                }`}
              >
                状态：{curStatus}
              </span>
              <span className="mm-text-secondary tabular-nums">
                过期时间：{formatDateTime(statusData?.expiresAt ?? issued.expiresAt)}
              </span>
            </div>

            {statusData?.status === 'CONSUMED' && (
              <div className="mm-card-subtle p-3 text-xs mm-text-income">
                设备「{statusData.deviceName || statusData.deviceId}」已于{' '}
                {formatDateTime(statusData.consumedAt)} 成功绑定！
              </div>
            )}

            {issued.qrSvg && curStatus === 'PENDING' && (
              <div className="flex justify-center p-4 bg-white rounded-2xl border mm-divider">
                <img
                  src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(issued.qrSvg)}`}
                  alt="麦米设备连接二维码"
                  className="w-56 h-56"
                />
              </div>
            )}

            <div className="space-y-2">
              <div className="flex items-center justify-between text-xs mm-text-secondary">
                <span>文本连接码（请妥善保管）</span>
                <button
                  type="button"
                  onClick={handleCopy}
                  className="inline-flex items-center gap-1 mm-text-accent font-medium"
                >
                  {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copied ? '已复制' : '复制'}</span>
                </button>
              </div>
              <textarea
                readOnly
                rows={3}
                aria-label="MMJOIN2 文本连接码"
                value={issued.connectCode}
                className="mm-input w-full p-2.5 text-[11px] font-mono break-all"
              />
            </div>

            {curStatus === 'PENDING' && (
              <button
                type="button"
                disabled={busy}
                onClick={handleCancel}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium mm-bg-expense-soft mm-text-expense"
              >
                <XCircle className="w-3.5 h-3.5" />
                <span>立即作废此连接码</span>
              </button>
            )}
          </div>
        )}
      </Panel>
    </div>
  );
};
