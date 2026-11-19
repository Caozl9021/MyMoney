import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Eye, EyeOff, Lock, Moon, Sun } from 'lucide-react';
import { useSession } from '../lib/session';
import type { BackupSettingsResponse } from '../types/api';
import { Panel } from '../components/UiPrimitives';

export const SettingsView: React.FC = () => {
  const {
    sessionGen,
    authedJson,
    theme,
    toggleTheme,
    privacyMasked,
    togglePrivacy,
    lock,
  } = useSession();

  const settingsQ = useQuery({
    queryKey: ['backup-settings', sessionGen],
    queryFn: ({ signal }) =>
      authedJson<BackupSettingsResponse>('/v1/admin/backup-settings', { signal }),
  });

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <Panel title="外观与隐私显示" subtitle="仅作用于当前浏览器会话">
        <div className="divide-y mm-divider text-xs">
          <div className="py-3 flex items-center justify-between gap-4">
            <div className="min-w-0">
              <div className="font-medium mm-text-primary">界面配色主题</div>
              <div className="mm-text-secondary mt-0.5">
                当前使用{theme === 'light' ? '浅色' : '深色'}高对比度主题
              </div>
            </div>
            <button
              type="button"
              onClick={toggleTheme}
              className="shrink-0 whitespace-nowrap inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border mm-divider mm-bg-elevated mm-text-primary font-medium"
            >
              {theme === 'light' ? <Moon className="w-3.5 h-3.5" /> : <Sun className="w-3.5 h-3.5" />}
              <span>切换{theme === 'light' ? '深色' : '浅色'}</span>
            </button>
          </div>

          <div className="py-3 flex items-center justify-between gap-4">
            <div className="min-w-0">
              <div className="font-medium mm-text-primary">金额隐私遮罩</div>
              <div className="mm-text-secondary mt-0.5">
                开启后全局金额、图表刻度与 Tooltip 统一遮蔽为 ****
              </div>
            </div>
            <button
              type="button"
              onClick={togglePrivacy}
              className={`shrink-0 whitespace-nowrap inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border mm-divider font-medium ${
                privacyMasked ? 'mm-bg-accent-soft mm-text-accent' : 'mm-bg-elevated mm-text-primary'
              }`}
            >
              {privacyMasked ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              <span>{privacyMasked ? '已开启遮罩' : '开启遮罩'}</span>
            </button>
          </div>

          <div className="py-3 flex items-center justify-between gap-4">
            <div className="min-w-0">
              <div className="font-medium mm-text-primary">会话安全锁定</div>
              <div className="mm-text-secondary mt-0.5">
                立即清除内存中的管理密码、取消轮询并清空全部查询缓存
              </div>
            </div>
            <button
              type="button"
              onClick={() => lock()}
              className="shrink-0 whitespace-nowrap inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg mm-bg-expense-soft mm-text-expense font-medium"
            >
              <Lock className="w-3.5 h-3.5" />
              <span>立即锁定</span>
            </button>
          </div>
        </div>
      </Panel>

      <Panel title="服务端运行参数与只读保证" subtitle="配置来源于服务端环境，Web 端不改动数据">
        <div className="divide-y mm-divider text-xs">
          <div className="py-3 flex items-center justify-between gap-4">
            <span className="shrink-0 whitespace-nowrap mm-text-secondary">服务入口地址</span>
            <span className="min-w-0 font-mono mm-text-primary break-all text-right">
              {settingsQ.data?.serverUrl || '—'}
            </span>
          </div>
          <div className="py-3 flex items-center justify-between gap-4">
            <span className="shrink-0 whitespace-nowrap mm-text-secondary">控制面 URL</span>
            <span className="min-w-0 font-mono mm-text-primary break-all text-right">
              {settingsQ.data?.ctrlUrl || '（直连模式）'}
            </span>
          </div>
          <div className="py-3 flex items-center justify-between gap-4">
            <span className="shrink-0 whitespace-nowrap mm-text-secondary">预设组网 Key</span>
            <span className="mm-text-primary font-medium">
              {settingsQ.data?.networkKeyConfigured ? '已配置' : '未配置'}
            </span>
          </div>
          <div className="py-3 flex items-center justify-between gap-4">
            <span className="shrink-0 whitespace-nowrap mm-text-secondary">数据安全边界</span>
            <span className="min-w-0 mm-text-income font-medium">
              只读不可变快照分析 · 绝不改写账本与 lastSeenAt
            </span>
          </div>
        </div>
      </Panel>
    </div>
  );
};
