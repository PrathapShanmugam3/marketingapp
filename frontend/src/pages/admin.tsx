import { useState } from 'react';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, fmtDateTime, inr, num } from '../lib/format';
import { errText, useDebounced } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Badge, Button, Card, ConfirmDialog, DataTable, LoadMore, Loading, Modal, PageHeader, StatCard, StatusBadge, Tabs } from '../components/ui';

const cell = (v: any) => (v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : typeof v === 'boolean' ? (v ? 'yes' : 'no') : String(v));
const humanize = (k: string) => k.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase());
/** Renders any list of flat records; used for the read-only operational tables. */
function Generic({ rows, loading, empty, skip = [] }: { rows?: any[]; loading?: boolean; empty?: string; skip?: string[] }) {
  const keys = rows && rows[0] ? Object.keys(rows[0]).filter((k) => !skip.includes(k)).slice(0, 8) : [];
  return <DataTable rows={rows?.map((r, i) => ({ id: r.id ?? i, ...r }))} loading={loading} empty={empty} columns={keys.map((k) => ({ key: k, header: humanize(k),
    render: (r: any) => (/At$|Date$|On$/.test(k) && r[k] ? fmtDateTime(r[k]) : <span className="text-xs">{cell(r[k])}</span>) }))} />;
}

/* ------------------------------ admin console (P25) ------------------------------ */
export function AdminHome() {
  const q = useQuery({ queryKey: ['admin-stats'], queryFn: () => api.get('/api/v1/admin/stats'), refetchInterval: 60_000 });
  if (q.isLoading) return <Loading />;
  const s = q.data;
  return (
    <>
      <PageHeader title="Platform overview" />
      {s.ledgerBlocked && <Alert tone="red">Payouts are blocked: the ledger check found a difference. Open Finance → Reconciliation.</Alert>}
      <div className="my-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="MRR" value={inr(s.mrr, 0)} /><StatCard label="Wallet balances" value={inr(s.walletBalanceTotal, 0)} /><StatCard label="Messages today" value={num(s.messagesToday)} /><StatCard label="Conversions today" value={num(s.conversionsToday)} />
        <StatCard label="Payouts requested" value={num(s.payoutsRequested)} sub={inr(s.payoutsPending, 0)} /><StatCard label="Open tickets" value={num(s.openTickets)} /><StatCard label="Promoters" value={num(s.promoters)} />
        <StatCard label="Dead jobs" value={num(s.jobsDead)} tone={s.jobsDead > 0 ? 'warn' : undefined} sub={`${num(s.jobsPending)} pending`} />
      </div>
      <Card title="Workspaces by status"><div className="flex flex-wrap gap-3">{s.workspacesByStatus.map((w: any) => <div key={w.status} className="flex items-center gap-2"><StatusBadge status={w.status} /><b>{num(w.n)}</b></div>)}</div></Card>
    </>
  );
}

export function AdminWorkspaces() {
  const qc = useQueryClient();
  const { user } = useAuth();
  const superAdmin = user?.adminRole === 'SUPER_ADMIN';
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const dq = useDebounced(search.trim().length >= 2 ? search.trim() : '');
  const [act, setAct] = useState<{ ws: any; mode: 'status' | 'wallet' } | null>(null);
  const [val, setVal] = useState('SUSPENDED');
  const [note, setNote] = useState('');
  const [amount, setAmount] = useState('');
  const list = useInfiniteQuery({ queryKey: ['admin-ws', dq, status], initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api.get(`/api/v1/admin/workspaces${qs({ q: dq, status, cursor: pageParam })}`), getNextPageParam: (l: any) => l.nextCursor ?? undefined });
  const rows = list.data?.pages.flatMap((p: any) => p.items) ?? [];
  const run = useMutation({
    mutationFn: () => (act!.mode === 'status' ? api.patch(`/api/v1/admin/workspaces/${act!.ws.id}`, { status: val, reason: note }) : api.post(`/api/v1/admin/workspaces/${act!.ws.id}/wallet-adjustments`, { amount: Number(amount), note })),
    onSuccess: () => { toast.success('Done'); setAct(null); setNote(''); setAmount(''); qc.invalidateQueries({ queryKey: ['admin-ws'] }); }, onError: (e) => toast.error(errText(e)),
  });
  return (
    <>
      <PageHeader title="Workspaces" />
      <div className="mb-3 flex gap-2"><input className="input" aria-label="Search" placeholder="Search name, phone or email" value={search} onChange={(e) => setSearch(e.target.value)} />
        <select className="input !w-auto" aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All</option>{['ACTIVE', 'SUSPENDED', 'CLOSED'].map((s) => <option key={s}>{s}</option>)}</select></div>
      <Card><DataTable rows={rows} loading={list.isLoading} columns={[
        { key: 'n', header: 'Business', render: (w: any) => <div><b>{w.name}</b><div className="muted text-xs">{w.ownerName} · {w.ownerPhone}</div></div> },
        { key: 'p', header: 'Plan', render: (w: any) => w.planName ?? w.planCode }, { key: 's', header: 'Status', render: (w: any) => <StatusBadge status={w.status} /> },
        { key: 'w', header: 'Wallet', render: (w: any) => inr(w.walletBalance) }, { key: 'c', header: 'Joined', hideOnMobile: true, render: (w: any) => fmtDate(w.createdAt) },
        { key: 'x', header: '', render: (w: any) => superAdmin && <span className="flex gap-3 text-sm"><button className="text-brand" onClick={() => { setVal(w.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'); setAct({ ws: w, mode: 'status' }); }}>{w.status === 'ACTIVE' ? 'Suspend' : 'Activate'}</button>
          <button className="text-brand" onClick={() => setAct({ ws: w, mode: 'wallet' })}>Adjust wallet</button></span> },
      ]} /><LoadMore hasMore={!!list.hasNextPage} busy={list.isFetchingNextPage} onClick={() => list.fetchNextPage()} /></Card>
      <Modal open={!!act} onClose={() => setAct(null)} title={act?.mode === 'status' ? `Change status of ${act.ws.name}` : `Adjust wallet of ${act?.ws.name}`} footer={<><Button variant="ghost" onClick={() => setAct(null)}>Cancel</Button><Button busy={run.isPending} disabled={!note.trim() || (act?.mode === 'wallet' && !amount)} onClick={() => run.mutate()}>Confirm</Button></>}>
        {act?.mode === 'status' ? <select className="input mb-3" aria-label="New status" value={val} onChange={(e) => setVal(e.target.value)}>{['ACTIVE', 'SUSPENDED', 'CLOSED'].map((s) => <option key={s}>{s}</option>)}</select>
          : <><label className="label" htmlFor="adj">Amount in ₹ (negative to deduct)</label><input id="adj" className="input mb-3" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.-]/g, ''))} /></>}
        <label className="label" htmlFor="adj-n">Reason (recorded in the audit log)</label><input id="adj-n" className="input" value={note} onChange={(e) => setNote(e.target.value)} />
      </Modal>
    </>
  );
}

export function AdminRatesPlans() {
  const qc = useQueryClient();
  const { user } = useAuth();
  const superAdmin = user?.adminRole === 'SUPER_ADMIN';
  const rates = useQuery({ queryKey: ['admin-rates'], queryFn: () => api.get<any[]>('/api/v1/admin/message-rates') });
  const plans = useQuery({ queryKey: ['admin-plans'], queryFn: () => api.get<any[]>('/api/v1/admin/plans') });
  const [r, setR] = useState({ category: 'MARKETING', countryCode: '91', metaRate: '', sellRate: '', effectiveFrom: '' });
  const [p, setP] = useState<any | null>(null);
  const addRate = useMutation({ mutationFn: () => api.post('/api/v1/admin/message-rates', { ...r, metaRate: Number(r.metaRate), sellRate: Number(r.sellRate), effectiveFrom: r.effectiveFrom || undefined }),
    onSuccess: () => { toast.success('Rate added'); qc.invalidateQueries({ queryKey: ['admin-rates'] }); }, onError: (e) => toast.error(errText(e)) });
  const savePlan = useMutation({ mutationFn: () => api.put(`/api/v1/admin/plans/${p.id}`, { priceMonthly: Number(p.priceMonthly), priceYearly: Number(p.priceYearly), maxUsers: Number(p.maxUsers), maxContacts: Number(p.maxContacts), maxWorkspaces: Number(p.maxWorkspaces), maxDomains: Number(p.maxDomains), active: p.isActive }),
    onSuccess: () => { toast.success('Plan updated'); setP(null); qc.invalidateQueries({ queryKey: ['admin-plans'] }); }, onError: (e) => toast.error(errText(e)) });
  return (
    <>
      <PageHeader title="Rates and plans" />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Message rates (₹ per message)">
          {superAdmin && <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
            <select className="input" aria-label="Category" value={r.category} onChange={(e) => setR({ ...r, category: e.target.value })}>{['MARKETING', 'UTILITY', 'AUTHENTICATION', 'SERVICE'].map((c) => <option key={c}>{c}</option>)}</select>
            <input className="input" aria-label="Meta rate" placeholder="Meta" inputMode="decimal" value={r.metaRate} onChange={(e) => setR({ ...r, metaRate: e.target.value })} />
            <input className="input" aria-label="Sell rate" placeholder="Sell" inputMode="decimal" value={r.sellRate} onChange={(e) => setR({ ...r, sellRate: e.target.value })} />
            <input className="input" type="date" aria-label="Effective from" value={r.effectiveFrom} onChange={(e) => setR({ ...r, effectiveFrom: e.target.value })} />
            <Button busy={addRate.isPending} disabled={!r.metaRate || !r.sellRate} onClick={() => addRate.mutate()}>Add</Button></div>}
          <Generic rows={rates.data} loading={rates.isLoading} skip={['id']} />
        </Card>
        <Card title="Plans"><DataTable rows={plans.data} loading={plans.isLoading} columns={[
          { key: 'n', header: 'Plan', render: (x: any) => <b>{x.name}</b> }, { key: 'm', header: 'Monthly', render: (x: any) => inr(x.priceMonthly, 0) }, { key: 'y', header: 'Yearly', render: (x: any) => inr(x.priceYearly, 0) },
          { key: 'c', header: 'Contacts', render: (x: any) => num(x.maxContacts) }, { key: 'a', header: '', render: (x: any) => superAdmin && <button className="text-brand" onClick={() => setP({ ...x })}>Edit</button> },
        ]} /></Card>
      </div>
      <Modal open={!!p} onClose={() => setP(null)} title={`Edit ${p?.name}`} footer={<><Button variant="ghost" onClick={() => setP(null)}>Cancel</Button><Button busy={savePlan.isPending} onClick={() => savePlan.mutate()}>Save</Button></>}>
        {p && <div className="grid grid-cols-2 gap-3">{[['priceMonthly', 'Monthly ₹'], ['priceYearly', 'Yearly ₹'], ['maxUsers', 'Users'], ['maxContacts', 'Contacts'], ['maxWorkspaces', 'Client workspaces'], ['maxDomains', 'Domains']].map(([k, l]) => (
          <div key={k}><label className="label" htmlFor={`pl-${k}`}>{l}</label><input id={`pl-${k}`} className="input" inputMode="decimal" value={p[k]} onChange={(e) => setP({ ...p, [k]: e.target.value })} /></div>))}
          <label className="col-span-2 flex items-center gap-2 text-sm"><input type="checkbox" checked={!!p.isActive} onChange={(e) => setP({ ...p, isActive: e.target.checked })} /> Available to customers</label></div>}
      </Modal>
    </>
  );
}

export function AdminSystem() {
  const qc = useQueryClient();
  const [tab, setTab] = useState('audit');
  const [action, setAction] = useState('');
  const audit = useQuery({ queryKey: ['audit', action], queryFn: () => api.get<any[]>(`/api/v1/admin/audit-logs${qs({ action: action || undefined })}`), enabled: tab === 'audit' });
  const dead = useQuery({ queryKey: ['dead-jobs'], queryFn: () => api.get<any[]>('/api/v1/admin/jobs/dead'), enabled: tab === 'jobs' });
  const retry = useMutation({ mutationFn: (id: number) => api.post(`/api/v1/admin/jobs/${id}/retry`), onSuccess: () => { toast.success('Queued again'); qc.invalidateQueries({ queryKey: ['dead-jobs'] }); }, onError: (e) => toast.error(errText(e)) });
  return (
    <>
      <PageHeader title="System" />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'audit', label: 'Audit log' }, { key: 'jobs', label: 'Dead jobs' }]} />
      {tab === 'audit' ? (
        <><input className="input mb-3 max-w-xs" aria-label="Filter by action" placeholder="Filter by action, e.g. PAYOUT_SUCCESS" value={action} onChange={(e) => setAction(e.target.value.toUpperCase())} />
          <Card><Generic rows={audit.data} loading={audit.isLoading} skip={['ipHash', 'userAgent']} empty="No entries." /></Card></>
      ) : (
        <Card><DataTable rows={dead.data} loading={dead.isLoading} empty="No dead jobs. 🎉" columns={[
          { key: 't', header: 'Type', render: (j: any) => j.type }, { key: 'e', header: 'Error', render: (j: any) => <span className="text-xs text-red-600">{(j.lastError ?? '').slice(0, 160)}</span> },
          { key: 'a', header: 'Attempts', render: (j: any) => j.attempts }, { key: 'r', header: '', render: (j: any) => <button className="text-brand" onClick={() => retry.mutate(j.id)}>Retry</button> },
        ]} /></Card>
      )}
    </>
  );
}

/* ---------------------------- finance console (P38) ---------------------------- */
export function Finance() {
  const qc = useQueryClient();
  const [tab, setTab] = useState('payouts');
  const [status, setStatus] = useState('REQUESTED');
  const [sel, setSel] = useState<number[]>([]);
  const [act, setAct] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [q, setQ] = useState('');
  const dq = useDebounced(q.trim().length >= 2 ? q.trim() : '');
  const payouts = useQuery({ queryKey: ['fin-payouts', status], queryFn: () => api.get<any[]>(`/api/v1/admin/payouts${qs({ status })}`), enabled: tab === 'payouts' });
  const promoters = useQuery({ queryKey: ['fin-promoters', dq], queryFn: () => api.get<any[]>(`/api/v1/admin/promoters${qs({ q: dq })}`), enabled: tab === 'promoters' });
  const fraud = useQuery({ queryKey: ['fin-fraud'], queryFn: () => api.get<any[]>('/api/v1/admin/fraud'), enabled: tab === 'fraud' });
  const recon = useQuery({ queryKey: ['fin-recon'], queryFn: () => api.get('/api/v1/admin/reconciliation'), enabled: tab === 'recon' });
  const [ledger, setLedger] = useState<any | null>(null);
  const [pAct, setPAct] = useState<{ p: any; status: string } | null>(null);
  const batch = useMutation({
    mutationFn: () => api.post('/api/v1/admin/payouts/batches', { payoutIds: sel, action: act, reason }),
    onSuccess: (r) => { toast.success(r.message ?? 'Done'); setAct(null); setReason(''); setSel([]); qc.invalidateQueries({ queryKey: ['fin-payouts'] }); }, onError: (e) => toast.error(errText(e)),
  });
  const setPromoter = useMutation({ mutationFn: () => api.patch(`/api/v1/admin/promoters/${pAct!.p.id}`, { status: pAct!.status, reason }), onSuccess: () => { setPAct(null); setReason(''); qc.invalidateQueries({ queryKey: ['fin-promoters'] }); }, onError: (e) => toast.error(errText(e)) });
  const runRecon = useMutation({ mutationFn: () => api.post('/api/v1/admin/reconciliation/run', {}), onSuccess: () => { toast.success('Reconciliation done'); recon.refetch(); }, onError: (e) => toast.error(errText(e)) });
  const check = useMutation({ mutationFn: () => api.post('/api/v1/admin/ledger/check'), onSuccess: setLedger, onError: (e) => toast.error(errText(e)) });
  const unblock = useMutation({ mutationFn: () => api.post('/api/v1/admin/ledger/unblock'), onSuccess: () => { toast.success('Payouts unblocked'); recon.refetch(); }, onError: (e) => toast.error(errText(e)) });
  const upload = async (f: File) => {
    const fd = new FormData(); fd.append('file', f);
    try { const r = await api.post('/api/v1/admin/reconciliation/import', fd); toast.success(`Imported: ${cell(r.matched ?? r.imported ?? 'ok')}`); recon.refetch(); } catch (e) { toast.error(errText(e)); }
  };
  const label = (a: string) => a[0].toUpperCase() + a.slice(1);
  return (
    <>
      <PageHeader title="Finance" subtitle="Payouts, promoters, fraud and reconciliation" />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'payouts', label: 'Payout queue' }, { key: 'promoters', label: 'Promoters' }, { key: 'fraud', label: 'Fraud flags' }, { key: 'recon', label: 'Reconciliation' }]} />
      {tab === 'payouts' && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <select className="input !w-auto" aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setSel([]); }}>{['REQUESTED', 'APPROVED', 'PROCESSING', 'SUCCESS', 'FAILED', 'REJECTED'].map((s) => <option key={s}>{s}</option>)}</select>
            {sel.length > 0 && <>{['approve', 'reject'].map((a) => <Button key={a} variant={a === 'reject' ? 'danger' : 'primary'} onClick={() => setAct(a)}>{label(a)} {sel.length}</Button>)}</>}
          </div>
          <Card><DataTable rows={payouts.data} loading={payouts.isLoading} empty="Nothing in this queue." columns={[
            { key: 'c', header: '', render: (p: any) => (['REQUESTED', 'APPROVED'].includes(p.status) ? <input type="checkbox" aria-label="Select" checked={sel.includes(p.id)} onChange={(e) => setSel(e.target.checked ? [...sel, p.id] : sel.filter((x) => x !== p.id))} /> : null) },
            { key: 'p', header: 'Promoter', render: (p: any) => <div><b>{p.promoterName ?? `#${p.promoterId}`}</b><div className="muted text-xs">{p.payoutRef}</div></div> },
            { key: 'a', header: 'Amount', render: (p: any) => <div>{inr(p.amount)}<div className="muted text-xs">net {inr(p.netAmount)}</div></div> },
            { key: 'r', header: 'Risk', render: (p: any) => <Badge tone={p.riskScore >= 60 ? 'red' : p.riskScore >= 30 ? 'amber' : 'green'}>{p.riskScore ?? 0}</Badge> },
            { key: 's', header: 'Status', render: (p: any) => <div><StatusBadge status={p.status} />{p.firstApproverId && p.status === 'REQUESTED' && <div className="muted text-[11px]">1 of 2 approvals</div>}</div> },
            { key: 'd', header: 'Requested', hideOnMobile: true, render: (p: any) => fmtDateTime(p.requestedAt ?? p.createdAt) },
          ]} /></Card>
        </>
      )}
      {tab === 'promoters' && (
        <><input className="input mb-3 max-w-xs" aria-label="Search" placeholder="Search name, phone or code" value={q} onChange={(e) => setQ(e.target.value)} />
          <Card><DataTable rows={promoters.data} loading={promoters.isLoading} columns={[
            { key: 'n', header: 'Promoter', render: (p: any) => <div><b>{p.fullName}</b><div className="muted text-xs">{p.phone} · {p.referralCode}</div></div> }, { key: 's', header: 'Status', render: (p: any) => <StatusBadge status={p.status} /> },
            { key: 'k', header: 'KYC', render: (p: any) => <StatusBadge status={p.kycStatus} /> }, { key: 'b', header: 'Available', render: (p: any) => inr(p.balanceAvailable) }, { key: 'l', header: 'Lifetime', hideOnMobile: true, render: (p: any) => inr(p.lifetimeEarned) },
            { key: 'x', header: '', render: (p: any) => <button className="text-sm text-brand" onClick={() => setPAct({ p, status: p.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' })}>{p.status === 'ACTIVE' ? 'Suspend' : 'Activate'}</button> },
          ]} /></Card></>
      )}
      {tab === 'fraud' && <Card><Generic rows={fraud.data} loading={fraud.isLoading} empty="No flagged conversions." /></Card>}
      {tab === 'recon' && (
        <div className="space-y-4">
          {recon.data?.ledgerBlocked && <Alert tone="red" action={<Button variant="danger" busy={unblock.isPending} onClick={() => unblock.mutate()}>Unblock payouts</Button>}>Payouts are blocked because the ledger did not balance. Investigate, then unblock (super admin only).</Alert>}
          <div className="flex flex-wrap gap-2"><Button busy={runRecon.isPending} onClick={() => runRecon.mutate()}>Reconcile yesterday</Button><Button variant="ghost" busy={check.isPending} onClick={() => check.mutate()}>Check ledger now</Button>
            <label className="btn-ghost cursor-pointer">Import settlement CSV<input type="file" hidden accept=".csv,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); e.target.value = ''; }} /></label></div>
          {ledger && <Alert tone={ledger.consistent ? 'green' : 'red'}>{ledger.consistent ? 'Ledger is consistent.' : <ul>{ledger.differences.map((d: string) => <li key={d}>{d}</li>)}</ul>}</Alert>}
          <Card title="Settlements"><Generic rows={recon.data?.settlements} loading={recon.isLoading} empty="No settlements yet." /></Card>
          <Card title="Payments held for review"><Generic rows={recon.data?.heldPayments} empty="None." /></Card>
        </div>
      )}
      <ConfirmDialog open={!!act} onClose={() => setAct(null)} danger={act === 'reject'} busy={batch.isPending} confirmLabel={act ? label(act) : ''} onConfirm={() => batch.mutate()} title={`${act ? label(act) : ''} ${sel.length} payout(s)?`}
        message={<div>{act === 'approve' ? 'Payouts above ₹1,00,000 need a second approver.' : 'The amount is returned to the promoter balance.'}{act === 'reject' && <input className="input mt-2" aria-label="Reason" placeholder="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />}</div>} />
      <ConfirmDialog open={!!pAct} onClose={() => setPAct(null)} danger={pAct?.status !== 'ACTIVE'} busy={setPromoter.isPending} confirmLabel="Confirm" onConfirm={() => setPromoter.mutate()} title={`Set ${pAct?.p.fullName} to ${pAct?.status}?`}
        message={<input className="input" aria-label="Reason" placeholder="Reason (kept in the audit log)" value={reason} onChange={(e) => setReason(e.target.value)} />} />
    </>
  );
}
