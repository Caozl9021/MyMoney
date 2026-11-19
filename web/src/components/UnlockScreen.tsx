import React, { useState } from 'react';
import { Lock, ShieldCheck, ArrowRight } from 'lucide-react';
import { useSession } from '../lib/session';

export const UnlockScreen: React.FC = () => {
  const { unlock, unlockError, isUnlocking } = useSession();
  const [input, setInput] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    await unlock(input);
    setInput('');
  };

  return (
    <div className="min-h-screen mm-bg-canvas flex items-center justify-center p-4">
      <div className="w-full max-w-md mm-card p-7">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-xl mm-bg-accent-soft flex items-center justify-center">
            <ShieldCheck className="w-5 h-5 mm-text-accent" />
          </div>
          <div>
            <h1 className="text-lg font-semibold tracking-tight mm-text-primary">
              麦米家庭财务分析与云备份
            </h1>
            <p className="text-xs mm-text-secondary">只读不可变快照工作台 · 内存安全会话</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="admin-token-input" className="block text-xs font-medium mm-text-secondary mb-1.5">
              家庭管理密码
            </label>
            <div className="relative">
              <Lock className="w-4 h-4 mm-text-muted absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
              <input
                id="admin-token-input"
                type="password"
                autoComplete="off"
                autoFocus
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="输入服务管理密码解锁"
                className="mm-input w-full pl-9 pr-3 py-2.5 text-sm"
              />
            </div>
          </div>

          {unlockError && (
            <div className="rounded-lg px-3 py-2 text-xs mm-bg-expense-soft mm-text-expense">
              {unlockError}
            </div>
          )}

          <button
            type="submit"
            disabled={isUnlocking || !input.trim()}
            className="w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-xl text-sm font-medium bg-[var(--accent-primary)] text-white disabled:opacity-50 transition-opacity"
          >
            <span>{isUnlocking ? '验证中…' : '解锁仪表盘'}</span>
            <ArrowRight className="w-4 h-4" />
          </button>
        </form>

        <div className="mt-5 pt-4 border-t mm-divider text-[11px] mm-text-muted leading-relaxed">
          密码仅存于当前页面内存，关闭或刷新页面后自动锁定，不写入 Cookie 或本地存储。
        </div>
      </div>
    </div>
  );
};
