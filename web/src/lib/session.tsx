import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, apiFetchJson } from './api';
import type {
  BillFilterState,
  PeriodMode,
  SnapshotsListResponse,
  ThemeMode,
  ViewId,
} from '../types/api';

const SESSION_STORAGE_KEY = 'mm_admin_session';

export interface CustomRange {
  start: string;
  end: string;
}

interface SessionContextValue {
  adminToken: string | null;
  isUnlocked: boolean;
  /** 刷新后正在用 sessionStorage 里的短期会话令牌恢复登录态。 */
  isRestoring: boolean;
  sessionGen: number;
  unlockError: string | null;
  isUnlocking: boolean;
  unlock: (token: string) => Promise<void>;
  lock: (reason?: string) => void;
  authedJson: <T>(path: string, options?: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal }) => Promise<T>;
  activeView: ViewId;
  setActiveView: (v: ViewId) => void;
  theme: ThemeMode;
  toggleTheme: () => void;
  privacyMasked: boolean;
  togglePrivacy: () => void;
  selectedBackupId: string | null;
  setSelectedBackupId: (id: string | null) => void;
  periodMode: PeriodMode;
  setPeriodMode: (m: PeriodMode) => void;
  customRange: CustomRange | null;
  setCustomRange: (r: CustomRange | null) => void;
  monthStartDay: number;
  setMonthStartDay: (d: number) => void;
  billFilter: BillFilterState;
  setBillFilter: React.Dispatch<React.SetStateAction<BillFilterState>>;
  drillToBills: (filter: BillFilterState) => void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export const SessionProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const queryClient = useQueryClient();

  const [adminToken, setAdminToken] = useState<string | null>(null);
  const [isRestoring, setIsRestoring] = useState<boolean>(() => {
    try {
      return !!sessionStorage.getItem(SESSION_STORAGE_KEY);
    } catch {
      return false;
    }
  });
  const [sessionGen, setSessionGen] = useState<number>(1);
  const tokenRef = useRef<string | null>(null);
  const genRef = useRef<number>(1);

  const [unlockError, setUnlockError] = useState<string | null>(null);
  const [isUnlocking, setIsUnlocking] = useState<boolean>(false);

  const [activeView, setActiveView] = useState<ViewId>('overview');
  const [theme, setTheme] = useState<ThemeMode>('light');
  const [privacyMasked, setPrivacyMasked] = useState<boolean>(false);
  const [selectedBackupId, setSelectedBackupId] = useState<string | null>(null);
  const [periodMode, setPeriodMode] = useState<PeriodMode>('ledger');
  const [customRange, setCustomRange] = useState<CustomRange | null>(null);
  const [monthStartDay, setMonthStartDay] = useState<number>(1);
  const [billFilter, setBillFilter] = useState<BillFilterState>({ sort: 'time_desc', page: 1, pageSize: 50 });

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const lock = useCallback(
    (reason?: string) => {
      const current = tokenRef.current;
      tokenRef.current = null;
      try {
        sessionStorage.removeItem(SESSION_STORAGE_KEY);
      } catch {
        // 浏览器禁用 storage 时仍保持内存锁定。
      }
      if (current) {
        // 主动退出时让服务端立即吊销这枚短期会话（失败也不阻塞本地锁定）。
        void apiFetchJson('/v1/admin/session/logout', current, { method: 'POST' }).catch(() => undefined);
      }
      genRef.current += 1;
      setAdminToken(null);
      setSessionGen(genRef.current);
      setUnlockError(reason ?? null);
      queryClient.clear();
    },
    [queryClient],
  );

  // 刷新后自动用 sessionStorage 中的短期会话令牌恢复，不再重新输入管理密码。
  useEffect(() => {
    let stored: string | null = null;
    try {
      stored = sessionStorage.getItem(SESSION_STORAGE_KEY);
    } catch {
      stored = null;
    }
    if (!stored) {
      setIsRestoring(false);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const snapRes = await apiFetchJson<SnapshotsListResponse>(
          '/v1/admin/dashboard/snapshots?limit=20',
          stored,
        );
        if (cancelled) return;
        genRef.current += 1;
        tokenRef.current = stored;
        setSessionGen(genRef.current);
        setAdminToken(stored);
        if (snapRes.defaultBackupId) {
          setSelectedBackupId((prev) => prev ?? snapRes.defaultBackupId);
        }
        queryClient.setQueryData(['snapshots', genRef.current], snapRes);
      } catch {
        try {
          sessionStorage.removeItem(SESSION_STORAGE_KEY);
        } catch {
          // ignore
        }
      } finally {
        if (!cancelled) setIsRestoring(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [queryClient]);

  const unlock = useCallback(
    async (rawToken: string) => {
      const clean = rawToken.trim();
      if (!clean) {
        setUnlockError('请输入管理密码');
        return;
      }
      setIsUnlocking(true);
      setUnlockError(null);
      try {
        const snapRes = await apiFetchJson<SnapshotsListResponse>(
          '/v1/admin/dashboard/snapshots?limit=20',
          clean,
        );
        // 用管理密码换一枚 12 小时的短期会话令牌，前端只持久化该令牌（不落管理密码）。
        let sessionToken = clean;
        try {
          const issued = await apiFetchJson<{ sessionToken: string; expiresInSeconds: number }>(
            '/v1/admin/session',
            clean,
            { method: 'POST' },
          );
          sessionToken = issued.sessionToken;
          try {
            sessionStorage.setItem(SESSION_STORAGE_KEY, sessionToken);
          } catch {
            // 浏览器禁用 storage 时退化为仅内存会话（刷新仍需重输），不影响本次解锁。
          }
        } catch (err) {
          // 旧版服务端没有会话端点时回退为内存令牌（刷新仍需重输），不阻塞解锁。
          if (!(err instanceof ApiError && err.status === 404)) throw err;
          sessionToken = clean;
        }
        genRef.current += 1;
        tokenRef.current = sessionToken;
        setSessionGen(genRef.current);
        setAdminToken(sessionToken);
        if (snapRes.defaultBackupId) {
          setSelectedBackupId((prev) => prev ?? snapRes.defaultBackupId);
        }
        queryClient.setQueryData(['snapshots', genRef.current], snapRes);
      } catch (err) {
        const msg = err instanceof Error ? err.message : '解锁失败，请检查密码或服务连接';
        setUnlockError(msg);
      } finally {
        setIsUnlocking(false);
      }
    },
    [queryClient],
  );

  const authedJson = useCallback(
    async <T,>(
      path: string,
      options: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal } = {},
    ): Promise<T> => {
      const tok = tokenRef.current;
      const myGen = genRef.current;
      if (!tok) {
        throw new Error('会话未解锁');
      }
      return apiFetchJson<T>(path, tok, {
        ...options,
        checkSessionGen: () => genRef.current === myGen && tokenRef.current !== null,
        onForbidden: () => lock('管理密码已失效或服务端重启，请重新解锁'),
      });
    },
    [lock],
  );

  const toggleTheme = useCallback(() => {
    setTheme((t) => (t === 'light' ? 'dark' : 'light'));
  }, []);

  const togglePrivacy = useCallback(() => {
    setPrivacyMasked((v) => !v);
  }, []);

  const drillToBills = useCallback((filter: BillFilterState) => {
    setBillFilter({
      sort: 'time_desc',
      page: 1,
      pageSize: 50,
      ...filter,
    });
    setActiveView('transactions');
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({
      adminToken,
      isUnlocked: adminToken !== null,
      isRestoring,
      sessionGen,
      unlockError,
      isUnlocking,
      unlock,
      lock,
      authedJson,
      activeView,
      setActiveView,
      theme,
      toggleTheme,
      privacyMasked,
      togglePrivacy,
      selectedBackupId,
      setSelectedBackupId,
      periodMode,
      setPeriodMode,
      customRange,
      setCustomRange,
      monthStartDay,
      setMonthStartDay,
      billFilter,
      setBillFilter,
      drillToBills,
    }),
    [
      adminToken,
      isRestoring,
      sessionGen,
      unlockError,
      isUnlocking,
      unlock,
      lock,
      authedJson,
      activeView,
      theme,
      toggleTheme,
      privacyMasked,
      togglePrivacy,
      selectedBackupId,
      periodMode,
      customRange,
      monthStartDay,
      billFilter,
      drillToBills,
    ],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
};

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be inside SessionProvider');
  return ctx;
}
