import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, refreshSession, rememberWorkspace, setAccessToken, setUnauthorizedHandler } from './api';
import { setLanguage } from './i18n';

export type Role = 'OWNER' | 'MANAGER' | 'STAFF' | 'AGENCY_ADMIN';

export interface Workspace {
  id: number; name: string; status: string; role?: Role; planCode?: string; planName?: string; trialEndsAt?: string; walletBalance?: number | string;
  features?: Record<string, boolean>; logoUrl?: string; brandColor?: string; state?: string; maxUsers?: number; maxContacts?: number; maxDomains?: number; maxWorkspaces?: number;
}
export interface User { id: number; fullName: string; email: string; phone: string; preferredLanguage: 'en' | 'ta'; adminRole: string; accountType: string }

interface AuthState {
  status: 'loading' | 'authed' | 'anon';
  user: User | null;
  workspace: Workspace | null;
  workspaces: { id: number; name: string; status: string; role: Role }[];
}

interface Ctx extends AuthState {
  role: Role | null;
  isAdmin: boolean;
  isPromoter: boolean;
  can: { manage: boolean; owner: boolean; staffOnly: boolean };
  hasFeature: (f: string) => boolean;
  applyAuth: (body: any) => void;
  login: (p: { identifier: string; password?: string; otp?: string; rememberMe?: boolean }) => Promise<any>;
  logout: () => Promise<void>;
  switchWorkspace: (id: number) => Promise<void>;
  reload: () => Promise<void>;
}

const AuthContext = createContext<Ctx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [s, setS] = useState<AuthState>({ status: 'loading', user: null, workspace: null, workspaces: [] });

  const applyAuth = useCallback((body: any) => {
    setAccessToken(body.accessToken);
    rememberWorkspace(body.workspace?.id ?? null);
    if (body.user?.preferredLanguage) setLanguage(body.user.preferredLanguage);
    setS({ status: 'authed', user: body.user, workspace: body.workspace ?? null, workspaces: body.workspaces ?? [] });
  }, []);

  const clear = useCallback(() => {
    setAccessToken(null);
    setS({ status: 'anon', user: null, workspace: null, workspaces: [] });
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(clear);
    refreshSession().then((b) => (b ? applyAuth(b) : clear())).catch(clear);
  }, [applyAuth, clear]);

  const login = useCallback(async (p: { identifier: string; password?: string; otp?: string; rememberMe?: boolean }) => {
    const body = await api.post('/api/v1/auth/login', p);
    if (body.accessToken) applyAuth(body);
    return body;
  }, [applyAuth]);

  const logout = useCallback(async () => {
    try { await api.post('/api/v1/auth/logout'); } catch { /* already logged out */ }
    rememberWorkspace(null);
    clear();
  }, [clear]);

  const switchWorkspace = useCallback(async (id: number) => {
    applyAuth(await api.post('/api/v1/auth/workspace/select', { workspaceId: id }));
  }, [applyAuth]);

  const reload = useCallback(async () => {
    const b = await api.get('/api/v1/auth/me');
    setS((p) => ({ ...p, user: b.user, workspace: b.workspace ?? null, workspaces: b.workspaces ?? [] }));
  }, []);

  const value = useMemo<Ctx>(() => {
    const role = (s.workspace?.role as Role) ?? null;
    const owner = role === 'OWNER' || role === 'AGENCY_ADMIN';
    return {
      ...s,
      role,
      isAdmin: !!s.user && s.user.adminRole !== 'NONE',
      isPromoter: !!s.user && (s.user.accountType === 'PROMOTER' || s.user.accountType === 'BOTH'),
      can: { owner, manage: owner || role === 'MANAGER', staffOnly: role === 'STAFF' },
      hasFeature: (f: string) => !!s.workspace?.features?.[f],
      applyAuth, login, logout, switchWorkspace, reload,
    };
  }, [s, applyAuth, login, logout, switchWorkspace, reload]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => {
  const c = useContext(AuthContext);
  if (!c) throw new Error('useAuth outside AuthProvider');
  return c;
};
