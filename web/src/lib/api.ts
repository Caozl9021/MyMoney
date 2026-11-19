import type { BillFilterState } from '../types/api';

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function apiFetchJson<T>(
  path: string,
  token: string,
  options: {
    method?: 'GET' | 'POST';
    body?: unknown;
    signal?: AbortSignal;
    onForbidden?: () => void;
    checkSessionGen?: () => boolean;
  } = {},
): Promise<T> {
  const { method = 'GET', body, signal, onForbidden, checkSessionGen } = options;
  const headers: Record<string, string> = {
    'x-admin-token': token,
  };
  let payload: string | undefined;
  if (body !== undefined) {
    headers['content-type'] = 'application/json';
    payload = JSON.stringify(body);
  }

  const resp = await fetch(path, {
    method,
    headers,
    body: payload,
    signal,
    cache: 'no-store',
  });

  if (checkSessionGen && !checkSessionGen()) {
    throw new DOMException('Session expired', 'AbortError');
  }

  if (resp.status === 403) {
    onForbidden?.();
    let msg = '管理密码无效或会话已锁定';
    try {
      const errData = (await resp.json()) as { error?: string };
      if (errData?.error) msg = errData.error;
    } catch {
      // ignore json parse error
    }
    throw new ApiError(403, msg);
  }

  if (!resp.ok) {
    let msg = `请求失败 (${resp.status})`;
    try {
      const errData = (await resp.json()) as { error?: string; detail?: string };
      if (errData?.error) msg = errData.error;
      else if (errData?.detail) msg = errData.detail;
    } catch {
      // ignore json parse error
    }
    throw new ApiError(resp.status, msg);
  }

  return (await resp.json()) as T;
}

export function buildQuery(params: Record<string, string | number | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    const str = String(v).trim();
    if (!str) continue;
    sp.set(k, str);
  }
  const qs = sp.toString();
  return qs ? `?${qs}` : '';
}

export function buildBillsParams(
  backupId: string | null,
  f: BillFilterState,
  extra: Record<string, string | number | undefined> = {},
): string {
  return buildQuery({
    backupId: backupId ?? undefined,
    q: f.q,
    type: f.type,
    category: f.category,
    subCategory: f.subCategory,
    account: f.account,
    reimburseStatus: f.reimburseStatus,
    channel: f.channel,
    strategy: f.strategy,
    minCents: f.minCents,
    maxCents: f.maxCents,
    start: f.start,
    end: f.end,
    sort: f.sort,
    page: f.page,
    pageSize: f.pageSize,
    ...extra,
  });
}

export async function downloadAdminFile(
  path: string,
  token: string,
  fallbackName: string,
  onForbidden?: () => void,
): Promise<void> {
  const resp = await fetch(path, {
    headers: { 'x-admin-token': token },
    cache: 'no-store',
  });
  if (resp.status === 403) {
    onForbidden?.();
    throw new ApiError(403, '会话已锁定，请重新输入管理密码');
  }
  if (!resp.ok) {
    let msg = `下载失败 (${resp.status})`;
    try {
      const errData = (await resp.json()) as { error?: string };
      if (errData?.error) msg = errData.error;
    } catch {
      // ignore
    }
    throw new ApiError(resp.status, msg);
  }
  const disp = resp.headers.get('content-disposition') ?? '';
  const match = /filename="?([^";\n]+)"?/i.exec(disp);
  const filename = match?.[1] ?? fallbackName;

  const blob = await resp.blob();
  const objUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objUrl);
}
