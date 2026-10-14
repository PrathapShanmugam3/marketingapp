/** Fetch wrapper: access token lives in memory, refresh token in an HttpOnly cookie (rotated on every refresh). */
export interface FieldErr { field: string; code: string; message: string }

export class ApiError extends Error {
  status: number;
  code: string;
  fieldErrors: FieldErr[];
  extra: Record<string, any>;
  retryAfter?: number;
  constructor(status: number, body: any, retryAfter?: number) {
    super(body?.message || 'Something went wrong. Please try again.');
    this.status = status;
    this.code = body?.code || 'INTERNAL_ERROR';
    this.fieldErrors = body?.fieldErrors || [];
    this.extra = body || {};
    this.retryAfter = retryAfter;
  }
}

let accessToken: string | null = null;
let onUnauthorized: () => void = () => {};
let refreshing: Promise<boolean> | null = null;

export const setAccessToken = (t: string | null) => { accessToken = t; };
export const getAccessToken = () => accessToken;
export const setUnauthorizedHandler = (fn: () => void) => { onUnauthorized = fn; };

export const currentWorkspaceId = (): number | null => {
  try { const v = localStorage.getItem('mp_ws'); return v ? Number(v) : null; } catch { return null; }
};
export const rememberWorkspace = (id: number | null) => {
  try { if (id) localStorage.setItem('mp_ws', String(id)); else localStorage.removeItem('mp_ws'); } catch { /* storage may be blocked */ }
};

const lang = () => { try { return localStorage.getItem('mp_lang') || 'en'; } catch { return 'en'; } };

export async function refreshSession(): Promise<any | null> {
  const res = await fetch('/api/v1/auth/refresh', {
    method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ workspaceId: currentWorkspaceId() }),
  });
  if (!res.ok) return null;
  const body = await res.json();
  accessToken = body.accessToken;
  return body;
}

async function tryRefresh(): Promise<boolean> {
  if (!refreshing) refreshing = refreshSession().then((b) => !!b).catch(() => false).finally(() => { refreshing = null; });
  return refreshing;
}

async function request<T>(method: string, path: string, body?: unknown, retry = true): Promise<T> {
  const headers: Record<string, string> = { 'Accept-Language': lang() };
  let payload: BodyInit | undefined;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  const res = await fetch(path, { method, headers, body: payload, credentials: 'include' });
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('json') ? await res.json().catch(() => null) : ct.startsWith('text/') ? await res.text() : null;
  if (res.ok) return data as T;
  if (res.status === 401 && retry && !path.startsWith('/api/v1/auth/')) {
    if (await tryRefresh()) return request<T>(method, path, body, false);
    onUnauthorized();
  }
  const ra = res.headers.get('Retry-After');
  throw new ApiError(res.status, data, ra ? Number(ra) : undefined);
}

export const api = {
  get: <T = any>(p: string) => request<T>('GET', p),
  post: <T = any>(p: string, b?: unknown) => request<T>('POST', p, b ?? {}),
  put: <T = any>(p: string, b?: unknown) => request<T>('PUT', p, b ?? {}),
  patch: <T = any>(p: string, b?: unknown) => request<T>('PATCH', p, b ?? {}),
  del: <T = any>(p: string, b?: unknown) => request<T>('DELETE', p, b),
  upload: async (file: File, kind = 'image'): Promise<{ url: string; contentType: string }> => {
    const fd = new FormData();
    fd.append('file', file);
    return request('POST', `/api/v1/uploads?kind=${kind}`, fd);
  },
  /** Downloads an authenticated file (CSV/PDF) through the access token. */
  download: async (path: string, filename: string) => {
    const res = await fetch(path, { headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}, credentials: 'include' });
    if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => null));
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  },
  blobUrl: async (path: string): Promise<string> => {
    const res = await fetch(path, { headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}, credentials: 'include' });
    if (!res.ok) throw new ApiError(res.status, null);
    return URL.createObjectURL(await res.blob());
  },
};

export const qs = (params: Record<string, any>) => {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return;
    if (Array.isArray(v)) v.forEach((x) => p.append(k, String(x))); else p.append(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : '';
};
