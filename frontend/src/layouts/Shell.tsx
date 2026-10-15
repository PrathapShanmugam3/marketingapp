import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { setLanguage } from '../lib/i18n';
import { inr, relativeTime } from '../lib/format';
import { Icon } from '../components/Icon';
import { Logo } from '../components/ui';

export interface NavItem { to: string; key: string; label: string; icon: string; show?: boolean; end?: boolean }
export interface NavSection { title?: string; items: NavItem[] }

export function LanguageToggle() {
  const { i18n } = useTranslation();
  const { user } = useAuth();
  const change = async (l: 'en' | 'ta') => {
    setLanguage(l);
    if (user) { try { await api.put('/api/v1/profile', { fullName: user.fullName, preferredLanguage: l }); } catch { /* preference is best effort */ } }
  };
  return (
    <div className="inline-flex overflow-hidden rounded-lg border text-xs font-semibold" style={{ borderColor: 'var(--border)' }} role="group" aria-label="Language">
      {(['en', 'ta'] as const).map((l) => (
        <button key={l} onClick={() => change(l)} className={`px-2.5 py-1.5 ${i18n.language === l ? 'bg-brand text-white' : ''}`}>{l === 'en' ? 'EN' : 'தமிழ்'}</button>
      ))}
    </div>
  );
}

function Bell() {
  const { workspace } = useAuth();
  const [open, setOpen] = useState(false);
  const nav = useNavigate();
  const q = useQuery({ queryKey: ['notifications'], queryFn: () => api.get('/api/v1/notifications'), enabled: !!workspace, refetchInterval: 60_000 });
  if (!workspace) return null;
  const items: any[] = q.data?.items ?? [];
  return (
    <div className="relative">
      <button className="btn-ghost !p-2 relative" onClick={() => setOpen((o) => !o)} aria-label="Notifications">
        <Icon name="bell" />
        {q.data?.unread > 0 && <span className="absolute -right-1 -top-1 rounded-full bg-red-600 px-1.5 text-[10px] font-bold text-white">{q.data.unread}</span>}
      </button>
      {open && (
        <div className="card absolute right-0 z-40 mt-2 max-h-96 w-80 max-w-[90vw] overflow-y-auto !p-2 shadow-xl">
          <div className="flex items-center justify-between px-2 py-1"><b className="text-sm">Notifications</b>
            <button className="text-xs text-brand" onClick={async () => { await api.post('/api/v1/notifications/read'); q.refetch(); }}>Mark all read</button></div>
          {items.length === 0 && <p className="muted p-3 text-sm">You are all caught up.</p>}
          {items.map((n) => (
            <button key={n.id} className={`block w-full rounded-lg px-2 py-2 text-left hover:bg-black/5 dark:hover:bg-white/5 ${n.readAt ? 'opacity-60' : ''}`}
              onClick={async () => { setOpen(false); await api.post(`/api/v1/notifications/read?id=${n.id}`); q.refetch(); if (n.link) nav(n.link); }}>
              <div className="text-sm font-semibold">{n.title}</div>{n.body && <div className="muted text-xs">{n.body}</div>}<div className="muted text-[11px]">{relativeTime(n.createdAt)}</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function WorkspaceSwitcher() {
  const { workspace, workspaces, switchWorkspace } = useAuth();
  const nav = useNavigate();
  if (!workspace || workspaces.length < 2) return null;
  return (
    <select aria-label="Workspace" className="input !w-auto max-w-[11rem] !py-1.5 text-sm" value={workspace.id}
      onChange={async (e) => { await switchWorkspace(Number(e.target.value)); nav('/'); }}>
      {workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
    </select>
  );
}

export function Shell({ sections, portal, mobileTabs }: { sections: NavSection[]; portal: 'business' | 'promoter' | 'admin'; mobileTabs?: NavItem[] }) {
  const { t } = useTranslation();
  const { user, workspace, logout, isPromoter, isAdmin } = useAuth();
  const [drawer, setDrawer] = useState(false);
  const [menu, setMenu] = useState(false);
  const loc = useLocation();
  useEffect(() => { setDrawer(false); setMenu(false); }, [loc.pathname]);

  const nav = (
    <nav className="flex flex-col gap-4 p-3" aria-label="Main">
      {sections.map((s, i) => {
        const items = s.items.filter((x) => x.show !== false);
        if (!items.length) return null;
        return (
          <div key={i}>
            {s.title && <div className="muted px-3 pb-1 text-[11px] font-bold uppercase tracking-wider">{s.title}</div>}
            {items.map((it) => (
              <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium ${isActive ? 'bg-brand text-white' : 'hover:bg-black/5 dark:hover:bg-white/10'}`}>
                <Icon name={it.icon} size={18} />{t(it.key, it.label)}
              </NavLink>
            ))}
          </div>
        );
      })}
    </nav>
  );

  return (
    <div className="flex min-h-full">
      <aside className="sticky top-0 hidden h-screen w-64 shrink-0 overflow-y-auto border-r lg:block" style={{ borderColor: 'var(--border)', background: 'var(--card)' }}>
        <div className="p-4"><Link to="/"><Logo /></Link></div>
        {nav}
      </aside>
      {drawer && (
        <div className="fixed inset-0 z-50 lg:hidden" onMouseDown={(e) => e.target === e.currentTarget && setDrawer(false)} style={{ background: 'rgba(0,0,0,.5)' }}>
          <aside className="h-full w-72 max-w-[85vw] overflow-y-auto" style={{ background: 'var(--card)' }}>
            <div className="flex items-center justify-between p-4"><Logo /><button onClick={() => setDrawer(false)} aria-label="Close menu" className="text-2xl">×</button></div>
            {nav}
          </aside>
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center gap-2 border-b px-3 py-2 sm:px-5" style={{ borderColor: 'var(--border)', background: 'var(--card)' }}>
          <button className="btn-ghost !p-2 lg:hidden" onClick={() => setDrawer(true)} aria-label="Open menu"><Icon name="menu" /></button>
          <div className="lg:hidden"><Logo /></div>
          <div className="flex-1" />
          {portal === 'business' && workspace && (
            <Link to="/settings/billing" className="btn-ghost hidden !py-1.5 text-sm sm:inline-flex" title="Message wallet"><Icon name="wallet" size={16} />{inr(workspace.walletBalance)}</Link>
          )}
          <WorkspaceSwitcher />
          <LanguageToggle />
          <Bell />
          <div className="relative">
            <button className="btn-ghost !px-2 !py-1.5" onClick={() => setMenu((m) => !m)} aria-haspopup="menu" aria-expanded={menu}>
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand text-xs font-bold text-white">{user?.fullName?.[0]?.toUpperCase() ?? '?'}</span>
            </button>
            {menu && (
              <div className="card absolute right-0 z-40 mt-2 w-60 !p-2 shadow-xl" role="menu">
                <div className="px-3 py-2"><div className="truncate text-sm font-bold">{user?.fullName}</div><div className="muted truncate text-xs">{user?.email}</div></div>
                <hr style={{ borderColor: 'var(--border)' }} />
                {portal !== 'business' && workspace && <Link role="menuitem" className="block rounded-lg px-3 py-2 text-sm hover:bg-black/5" to="/">Business panel</Link>}
                {portal !== 'promoter' && isPromoter && <Link role="menuitem" className="block rounded-lg px-3 py-2 text-sm hover:bg-black/5" to="/earn">Promoter portal</Link>}
                {portal !== 'admin' && isAdmin && <Link role="menuitem" className="block rounded-lg px-3 py-2 text-sm hover:bg-black/5" to="/admin">Admin console</Link>}
                {portal === 'business' && !isPromoter && <Link role="menuitem" className="block rounded-lg px-3 py-2 text-sm hover:bg-black/5" to="/earn/join">Earn as a promoter</Link>}
                <Link role="menuitem" className="block rounded-lg px-3 py-2 text-sm hover:bg-black/5" to="/settings/profile">{t('nav.profile', 'My profile')}</Link>
                <button role="menuitem" className="block w-full rounded-lg px-3 py-2 text-left text-sm text-red-600 hover:bg-black/5" onClick={logout}>{t('nav.logout', 'Log out')}</button>
              </div>
            )}
          </div>
        </header>
        <main className={`mx-auto w-full max-w-6xl flex-1 p-4 sm:p-6 ${mobileTabs ? 'pb-24 lg:pb-6' : ''}`}><Outlet /></main>
        {mobileTabs && (
          <nav className="fixed inset-x-0 bottom-0 z-30 grid border-t lg:hidden" style={{ borderColor: 'var(--border)', background: 'var(--card)', gridTemplateColumns: `repeat(${mobileTabs.length + 1}, 1fr)`, paddingBottom: 'env(safe-area-inset-bottom)' }} aria-label="Quick">
            {mobileTabs.map((it) => (
              <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => `flex flex-col items-center gap-0.5 py-2 text-[11px] font-semibold ${isActive ? 'text-brand' : 'muted'}`}><Icon name={it.icon} />{t(it.key, it.label)}</NavLink>
            ))}
            <button onClick={() => setDrawer(true)} className="muted flex flex-col items-center gap-0.5 py-2 text-[11px] font-semibold"><Icon name="menu" />{t('nav.more', 'More')}</button>
          </nav>
        )}
      </div>
    </div>
  );
}

export function PublicLayout({ children, wide }: { children?: ReactNode; wide?: boolean }) {
  return (
    <div className="flex min-h-full flex-col items-center px-4 py-8">
      <div className="mb-6 flex w-full max-w-md items-center justify-between"><Link to="/"><Logo /></Link><LanguageToggle /></div>
      <div className={`w-full ${wide ? 'max-w-2xl' : 'max-w-md'}`}>{children ?? <Outlet />}</div>
    </div>
  );
}
