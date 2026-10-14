import { useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { subscribeToasts, type ToastMsg } from '../lib/toast';
import { toast } from '../lib/toast';

export const Spinner = ({ className = '' }: { className?: string }) => (
  <span role="status" aria-label="Loading" className={`inline-block h-5 w-5 animate-spin rounded-full border-2 border-brand border-t-transparent ${className}`} />
);

export const Loading = () => <div className="flex justify-center p-10"><Spinner /></div>;

export function Button({ busy, children, variant = 'primary', ...p }: React.ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean; variant?: 'primary' | 'ghost' | 'danger' }) {
  return (
    <button {...p} disabled={p.disabled || busy} className={`btn-${variant} ${p.className ?? ''}`}>
      {busy && <Spinner className="!h-4 !w-4 !border-white" />}{children}
    </button>
  );
}

const TONES: Record<string, string> = {
  green: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  amber: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  red: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  blue: 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300',
  gray: 'bg-gray-100 text-gray-700 dark:bg-gray-700/50 dark:text-gray-200',
};
export const Badge = ({ tone = 'gray', children }: { tone?: keyof typeof TONES; children: ReactNode }) => <span className={`badge ${TONES[tone]}`}>{children}</span>;

const STATUS_TONE: Record<string, string> = {
  APPROVED: 'green', ACTIVE: 'green', COMPLETED: 'green', PUBLISHED: 'green', SUCCESS: 'green', CONNECTED: 'green', VERIFIED: 'green', PAID: 'green', DELIVERED: 'green', READ: 'green', WON: 'green', OPTED_IN: 'green', RESOLVED: 'green', GREEN: 'green', MATCHED: 'green', ACCEPTED: 'green',
  PENDING: 'amber', DRAFT: 'gray', SCHEDULED: 'blue', QUEUED: 'blue', SENDING: 'blue', PROCESSING: 'blue', TRIAL: 'blue', REQUESTED: 'amber', WAITING_CUSTOMER: 'amber', PAUSED: 'amber', PARTIAL: 'amber', YELLOW: 'amber', NEW: 'blue', CONTACTED: 'blue', INTERESTED: 'amber', OPEN: 'blue', UNKNOWN: 'gray', PUBLISHING: 'blue', SENT: 'blue',
  REJECTED: 'red', FAILED: 'red', CANCELLED: 'gray', SUSPENDED: 'red', PAST_DUE: 'red', DISABLED: 'red', LOST: 'red', OPTED_OUT: 'red', RED: 'red', BLOCKED: 'red', REVERSED: 'red', MISMATCH: 'red', EXPIRED: 'red', DEAD: 'red', CLOSED: 'gray', ENDED: 'gray', ON_HOLD: 'amber',
};
export const StatusBadge = ({ status }: { status?: string | null }) => (status ? <Badge tone={(STATUS_TONE[status] ?? 'gray') as any}>{status.replace(/_/g, ' ')}</Badge> : null);

export const Card = ({ title, action, children, className = '' }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) => (
  <section className={`card ${className}`}>
    {(title || action) && <div className="mb-3 flex items-center justify-between gap-2">{title && <h2 className="text-base font-bold">{title}</h2>}{action}</div>}
    {children}
  </section>
);

export const PageHeader = ({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) => (
  <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
    <div><h1 className="text-xl font-bold sm:text-2xl">{title}</h1>{subtitle && <p className="muted mt-1 text-sm">{subtitle}</p>}</div>
    {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
  </div>
);

export const StatCard = ({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'warn' }) => (
  <div className="card !p-4">
    <div className="muted text-xs font-semibold uppercase tracking-wide">{label}</div>
    <div className={`mt-1 text-2xl font-bold ${tone === 'warn' ? 'text-amber-600' : ''}`}>{value}</div>
    {sub && <div className="muted mt-0.5 text-xs">{sub}</div>}
  </div>
);

export const EmptyState = ({ text, action }: { text?: ReactNode; action?: ReactNode }) => {
  const { t } = useTranslation();
  return <div className="muted flex flex-col items-center gap-3 p-8 text-center text-sm"><span>{text ?? t('common.none')}</span>{action}</div>;
};

export const Alert = ({ tone = 'amber', children, action }: { tone?: 'amber' | 'red' | 'green' | 'blue'; children: ReactNode; action?: ReactNode }) => {
  const c = { amber: 'border-amber-300 bg-amber-50 text-amber-900 dark:bg-amber-900/20 dark:text-amber-200', red: 'border-red-300 bg-red-50 text-red-900 dark:bg-red-900/20 dark:text-red-200', green: 'border-emerald-300 bg-emerald-50 text-emerald-900 dark:bg-emerald-900/20 dark:text-emerald-200', blue: 'border-sky-300 bg-sky-50 text-sky-900 dark:bg-sky-900/20 dark:text-sky-200' }[tone];
  return <div className={`mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border p-3 text-sm ${c}`} role="alert"><div>{children}</div>{action}</div>;
};

export function Modal({ open, onClose, title, children, footer, wide }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', h);
    ref.current?.focus();
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.removeEventListener('keydown', h); document.body.style.overflow = prev; };
  }, [open, onClose]);
  if (!open) return null;
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 p-0 sm:items-center sm:p-4" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" className={`card max-h-[92vh] w-full overflow-y-auto !rounded-b-none outline-none sm:!rounded-b-2xl ${wide ? 'sm:max-w-3xl' : 'sm:max-w-lg'}`}>
        <div className="mb-3 flex items-start justify-between gap-3"><h2 className="text-lg font-bold">{title}</h2>
          <button onClick={onClose} className="muted -mr-1 rounded p-1 text-xl leading-none" aria-label="Close">×</button></div>
        {children}
        {footer && <div className="mt-4 flex flex-wrap justify-end gap-2">{footer}</div>}
      </div>
    </div>, document.body);
}

/** Confirmation, optionally requiring a typed word (e.g. DELETE / DISCONNECT). */
export function ConfirmDialog({ open, onClose, onConfirm, title, message, confirmLabel, typeWord, danger, busy }:
  { open: boolean; onClose: () => void; onConfirm: () => void; title: ReactNode; message: ReactNode; confirmLabel?: string; typeWord?: string; danger?: boolean; busy?: boolean }) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState('');
  useEffect(() => { if (!open) setTyped(''); }, [open]);
  return (
    <Modal open={open} onClose={onClose} title={title}
      footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
        <Button variant={danger ? 'danger' : 'primary'} busy={busy} disabled={!!typeWord && typed !== typeWord} onClick={onConfirm}>{confirmLabel ?? t('common.confirm')}</Button></>}>
      <div className="text-sm">{message}</div>
      {typeWord && <div className="mt-3"><label className="label">Type <b>{typeWord}</b> to confirm</label><input className="input" value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus /></div>}
    </Modal>
  );
}

export interface Column<T> { key: string; header: ReactNode; render: (row: T) => ReactNode; className?: string; hideOnMobile?: boolean }

export function DataTable<T extends { id?: number | string }>({ columns, rows, loading, empty, onRowClick }: { columns: Column<T>[]; rows: T[] | undefined; loading?: boolean; empty?: ReactNode; onRowClick?: (r: T) => void }) {
  if (loading) return <Loading />;
  if (!rows || rows.length === 0) return <EmptyState text={empty} />;
  return (
    <div className="-mx-4 overflow-x-auto sm:mx-0">
      <table className="w-full min-w-[560px] border-collapse">
        <thead><tr className="border-b" style={{ borderColor: 'var(--border)' }}>{columns.map((c) => <th key={c.key} className={`th ${c.className ?? ''}`}>{c.header}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.id ?? i} className={`border-b ${onRowClick ? 'cursor-pointer hover:bg-black/5 dark:hover:bg-white/5' : ''}`} style={{ borderColor: 'var(--border)' }} onClick={() => onRowClick?.(r)}>
              {columns.map((c) => <td key={c.key} className={`td ${c.className ?? ''}`}>{c.render(r)}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Tabs({ tabs, value, onChange }: { tabs: { key: string; label: ReactNode }[]; value: string; onChange: (k: string) => void }) {
  return (
    <div className="mb-4 flex gap-1 overflow-x-auto border-b" role="tablist" style={{ borderColor: 'var(--border)' }}>
      {tabs.map((t) => (
        <button key={t.key} role="tab" aria-selected={value === t.key} onClick={() => onChange(t.key)}
          className={`whitespace-nowrap border-b-2 px-3 py-2 text-sm font-semibold ${value === t.key ? 'border-brand text-brand' : 'muted border-transparent'}`}>{t.label}</button>
      ))}
    </div>
  );
}

export function Toaster() {
  const [list, setList] = useState<ToastMsg[]>([]);
  useEffect(() => subscribeToasts(setList), []);
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-20 z-[60] flex flex-col items-center gap-2 px-4 lg:bottom-6" aria-live="polite">
      {list.map((t) => (
        <div key={t.id} className={`pointer-events-auto max-w-md rounded-xl px-4 py-3 text-sm font-medium text-white shadow-lg ${t.kind === 'error' ? 'bg-red-600' : t.kind === 'success' ? 'bg-brand-dark' : 'bg-navy'}`}>{t.text}</div>
      ))}
    </div>
  );
}

export function CopyButton({ text, label }: { text: string; label?: string }) {
  const { t } = useTranslation();
  return <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs" onClick={async () => { await navigator.clipboard?.writeText(text); toast.success(t('common.copied')); }}>{label ?? t('common.copy')}</button>;
}

export const BackLink = ({ to, children }: { to: string; children: ReactNode }) => <Link to={to} className="muted mb-3 inline-block text-sm hover:underline">← {children}</Link>;

export const Logo = ({ light }: { light?: boolean }) => (
  <span className="inline-flex items-center gap-2 font-bold"><span className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand text-sm text-white">MP</span><span className={light ? 'text-white' : ''}>Marketing Panel</span></span>
);

/** Simple load-more list footer for keyset pagination. */
export function LoadMore({ hasMore, busy, onClick }: { hasMore: boolean; busy?: boolean; onClick: () => void }) {
  const { t } = useTranslation();
  if (!hasMore) return null;
  return <div className="mt-3 text-center"><Button variant="ghost" busy={busy} onClick={onClick}>{t('common.loadMore')}</Button></div>;
}
