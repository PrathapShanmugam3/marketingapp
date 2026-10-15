import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, qs } from '../lib/api';
import { fmtDateTime, inr, num } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Badge, Button, Card, ConfirmDialog, CopyButton, DataTable, LoadMore, Loading, Modal, PageHeader, StatusBadge, Tabs } from '../components/ui';

const fe = (e: any): Record<string, string> => Object.fromEntries((e?.fieldErrors ?? []).map((x: any) => [x.field, x.message]));
const Err = ({ m }: { m?: string }) => (m ? <p className="mt-1 text-sm text-red-600" role="alert">{m}</p> : null);

/** P32 - advertiser offers. */
export function OffersList() {
  const nav = useNavigate();
  const q = useQuery({ queryKey: ['offers'], queryFn: () => api.get<any[]>('/api/v1/offers') });
  return (
    <>
      <PageHeader title="Offers" subtitle="Pay promoters only when a customer completes the action you choose" actions={<><Link to="/growth/conversions" className="btn-ghost">Conversions</Link><Link to="/growth/offers/new" className="btn-primary">+ New offer</Link></>} />
      <Card><DataTable rows={q.data} loading={q.isLoading} empty="No offers yet." onRowClick={(o: any) => nav(`/growth/offers/${o.id}`)} columns={[
        { key: 'n', header: 'Offer', render: (o: any) => <div><b>{o.name}</b><div className="muted text-xs">{o.eventName || o.eventType}</div></div> },
        { key: 's', header: 'Status', render: (o: any) => <div><StatusBadge status={o.status} />{o.pauseReason && <div className="muted text-[11px]">{o.pauseReason.replace(/_/g, ' ').toLowerCase()}</div>}</div> },
        { key: 'p', header: 'You pay', render: (o: any) => inr(o.advertiserPrice) }, { key: 'k', header: 'Clicks', hideOnMobile: true, render: (o: any) => num(o.clicks) },
        { key: 'c', header: 'Conversions', render: (o: any) => num(o.conversions) },
      ]} /></Card>
    </>
  );
}

const EMPTY = { name: '', appId: '', landingUrl: '', eventType: 'INSTALL', eventName: '', advertiserPrice: '', promoterPayout: '', dailyCap: '', totalCap: '', perPromoterDailyCap: '', attributionWindowHours: 168, holdDays: 7, minCtitSeconds: 10, incentiveAllowed: false, category: '', allowedIps: '' };

export function OfferEditor() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const qc = useQueryClient();
  const nav = useNavigate();
  const apps = useQuery({ queryKey: ['apps'], queryFn: () => api.get<any[]>('/api/v1/apps') });
  const existing = useQuery({ queryKey: ['offer', id], queryFn: () => api.get(`/api/v1/offers/${id}`), enabled: !isNew });
  const [f, setF] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [secret, setSecret] = useState<string | null>(null);
  const [tab, setTab] = useState('setup');
  const [test, setTest] = useState<any | null>(null);
  const [rot, setRot] = useState(false);
  const o = existing.data;
  const v = f ?? (isNew ? EMPTY : o && { ...EMPTY, ...o, appId: o.appId ?? '', dailyCap: o.dailyCap ?? '', totalCap: o.totalCap ?? '', perPromoterDailyCap: o.perPromoterDailyCap ?? '', category: o.category ?? '', eventName: o.eventName ?? '',
    allowedIps: Array.isArray(o.allowedIps) ? o.allowedIps.join(', ') : '' });
  const n = (x: any) => (x === '' || x == null ? null : Number(x));
  const body = () => ({ ...v, appId: n(v.appId), advertiserPrice: n(v.advertiserPrice), promoterPayout: n(v.promoterPayout), dailyCap: n(v.dailyCap), totalCap: n(v.totalCap), perPromoterDailyCap: n(v.perPromoterDailyCap),
    attributionWindowHours: Number(v.attributionWindowHours), holdDays: Number(v.holdDays), minCtitSeconds: Number(v.minCtitSeconds), allowedIps: String(v.allowedIps || '').split(',').map((s) => s.trim()).filter(Boolean) });
  const save = useMutation({
    mutationFn: () => (isNew ? api.post('/api/v1/offers', body()) : api.put(`/api/v1/offers/${id}`, body())),
    onSuccess: (r) => { setErrs({}); toast.success('Offer saved'); qc.invalidateQueries({ queryKey: ['offers'] }); qc.invalidateQueries({ queryKey: ['offer', id] }); if (isNew) { if (r.postbackSecret) setSecret(r.postbackSecret); nav(`/growth/offers/${r.id}`, { replace: true }); } },
    onError: (e: any) => { setErrs(fe(e)); toast.error(errText(e)); },
  });
  const status = useMutation({
    mutationFn: (s: string) => api.patch(`/api/v1/offers/${id}/status`, { status: s }), onSuccess: () => { qc.invalidateQueries({ queryKey: ['offer', id] }); qc.invalidateQueries({ queryKey: ['offers'] }); }, onError: (e) => toast.error(errText(e)),
  });
  const run = useMutation({ mutationFn: () => api.post(`/api/v1/offers/${id}/test`), onSuccess: (r) => { setTest(r); qc.invalidateQueries({ queryKey: ['offer', id] }); }, onError: (e) => toast.error(errText(e)) });
  const rotate = useMutation({ mutationFn: () => api.post(`/api/v1/offers/${id}/rotate-secret`), onSuccess: (r) => { setRot(false); setSecret(r.postbackSecret); }, onError: (e) => toast.error(errText(e)) });
  if (!isNew && existing.isLoading) return <Loading />;
  if (!v) return <Alert tone="red">Offer not found.</Alert>;
  const set = (k: string, val: any) => setF({ ...v, [k]: val });
  const input = (k: string, label: string, props: any = {}) => (<div><label className="label" htmlFor={`o-${k}`}>{label}</label><input id={`o-${k}`} className="input" value={v[k] ?? ''} onChange={(e) => set(k, e.target.value)} {...props} /><Err m={errs[k]} /></div>);
  const margin = v.advertiserPrice && v.promoterPayout ? Math.round((1 - Number(v.promoterPayout) / Number(v.advertiserPrice)) * 100) : null;
  return (
    <>
      <BackLink to="/growth/offers">Offers</BackLink>
      <PageHeader title={isNew ? 'New offer' : v.name} subtitle={!isNew && <StatusBadge status={o?.status} />}
        actions={<><Button variant="ghost" busy={save.isPending} onClick={() => save.mutate()}>Save</Button>
          {!isNew && o?.status !== 'ENDED' && (o?.status === 'ACTIVE' ? <Button variant="ghost" onClick={() => status.mutate('PAUSED')}>Pause</Button> : <Button busy={status.isPending} onClick={() => status.mutate('ACTIVE')}>Activate</Button>)}</>} />
      {!isNew && o?.status !== 'ACTIVE' && !o?.testedAt && <Alert tone="amber">Run a test postback before activating this offer.</Alert>}
      {!isNew && <Tabs value={tab} onChange={setTab} tabs={[{ key: 'setup', label: 'Setup' }, { key: 'postback', label: 'Postback' }]} />}
      {tab === 'setup' && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card title="What are you promoting?">
            <div className="space-y-3">
              {input('name', 'Offer name', { maxLength: 100 })}
              <div><label className="label" htmlFor="o-app">App (optional)</label><select id="o-app" className="input" value={v.appId} onChange={(e) => set('appId', e.target.value)}><option value="">None</option>{apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select><Err m={errs.appId} /></div>
              {input('landingUrl', 'Landing link (must include {click_id})', { placeholder: 'https://example.com/?click_id={click_id}' })}
              <div className="grid grid-cols-2 gap-2">
                <div><label className="label" htmlFor="o-et">Goal</label><select id="o-et" className="input" value={v.eventType} onChange={(e) => set('eventType', e.target.value)}>{['INSTALL', 'SIGNUP', 'PURCHASE', 'KYC', 'CUSTOM'].map((x) => <option key={x}>{x}</option>)}</select></div>
                {input('eventName', 'Event name (optional)')}
              </div>
              {input('category', 'Category (optional)')}
            </div>
          </Card>
          <Card title="Money">
            <div className="grid grid-cols-2 gap-2">{input('advertiserPrice', 'You pay per conversion (₹)', { inputMode: 'decimal' })}{input('promoterPayout', 'Promoter earns (₹)', { inputMode: 'decimal' })}</div>
            {margin !== null && <p className={`mt-1 text-sm ${margin < 20 ? 'text-red-600' : 'muted'}`}>Platform margin: {margin}% {margin < 20 && '(minimum 20%)'}</p>}
            <div className="mt-3 grid grid-cols-3 gap-2">{input('dailyCap', 'Daily cap', { inputMode: 'numeric' })}{input('totalCap', 'Total cap', { inputMode: 'numeric' })}{input('perPromoterDailyCap', 'Per promoter / day', { inputMode: 'numeric' })}</div>
            <div className="mt-3 grid grid-cols-3 gap-2">{input('attributionWindowHours', 'Window (hours)', { type: 'number' })}{input('holdDays', 'Hold (days)', { type: 'number' })}{input('minCtitSeconds', 'Min click→install (s)', { type: 'number' })}</div>
            <div className="mt-3">{input('allowedIps', 'Allowed postback IPs (optional, comma separated)')}</div>
            <label className="mt-3 flex items-start gap-2 text-sm"><input type="checkbox" className="mt-1" checked={!!v.incentiveAllowed} onChange={(e) => set('incentiveAllowed', e.target.checked)} />Promoters may share part of their earnings with users (incentivised traffic)</label>
          </Card>
        </div>
      )}
      {tab === 'postback' && o && (
        <div className="space-y-4">
          <Card title="Your postback URL" action={<Button variant="ghost" onClick={() => setRot(true)}>Rotate secret</Button>}>
            <p className="muted mb-2 text-sm">Fire this URL from your server or attribution tool when the goal is reached. <code>sig = {o.signatureRule}</code></p>
            <div className="flex items-start gap-2"><code className="min-w-0 flex-1 break-all rounded bg-black/5 p-2 text-xs dark:bg-white/10">{o.postbackUrl}</code><CopyButton text={o.postbackUrl} label="Copy" /></div>
            <div className="mt-3 grid gap-2">{Object.entries(o.templates ?? {}).map(([k, u]) => (<div key={k} className="flex items-start gap-2"><Badge>{k}</Badge><code className="min-w-0 flex-1 break-all text-[11px]">{String(u)}</code><CopyButton text={String(u)} /></div>))}</div>
          </Card>
          <Card title="Test it" action={<Button busy={run.isPending} onClick={() => run.mutate()}>Send test postback</Button>}>
            {o.testedAt ? <p className="text-sm">Last tested {fmtDateTime(o.testedAt)}.</p> : <p className="muted text-sm">Not tested yet.</p>}
            {test && <Alert tone={test.passed ? 'green' : 'red'}>{test.passed ? 'Postback accepted.' : `Postback result: ${test.result}`}</Alert>}
          </Card>
        </div>
      )}
      <Modal open={!!secret} onClose={() => setSecret(null)} title="Copy your postback secret" footer={<Button onClick={() => setSecret(null)}>Done</Button>}>
        <Alert tone="amber">It is shown only once. Use it to sign postbacks.</Alert>
        <div className="mt-3 flex items-center gap-2"><code className="min-w-0 flex-1 break-all rounded bg-black/5 p-2 text-xs dark:bg-white/10">{secret}</code>{secret && <CopyButton text={secret} label="Copy" />}</div>
      </Modal>
      <ConfirmDialog open={rot} onClose={() => setRot(false)} title="Rotate secret?" message="The old secret keeps working for 24 hours so you can switch over." confirmLabel="Rotate" busy={rotate.isPending} onConfirm={() => rotate.mutate()} />
    </>
  );
}

/** P33 - conversions and postback logs. */
export function Conversions() {
  const qc = useQueryClient();
  const [tab, setTab] = useState('conv');
  const [status, setStatus] = useState('');
  const [sel, setSel] = useState<number[]>([]);
  const [act, setAct] = useState<{ action: string; ids: number[] } | null>(null);
  const [reason, setReason] = useState('');
  const conv = useInfiniteQuery({
    queryKey: ['conversions', status], initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api.get(`/api/v1/conversions${qs({ status, cursor: pageParam })}`), getNextPageParam: (l: any) => l.nextCursor ?? undefined, enabled: tab === 'conv',
  });
  const logs = useQuery({ queryKey: ['pb-logs'], queryFn: () => api.get<any[]>('/api/v1/conversions/logs'), enabled: tab === 'logs' });
  const rows = conv.data?.pages.flatMap((p: any) => p.items) ?? [];
  const doAct = useMutation({
    mutationFn: () => (act!.ids.length === 1 ? api.patch(`/api/v1/conversions/${act!.ids[0]}`, { action: act!.action, reason }) : api.patch('/api/v1/conversions', { ids: act!.ids, action: act!.action, reason })),
    onSuccess: () => { toast.success('Done'); setAct(null); setReason(''); setSel([]); qc.invalidateQueries({ queryKey: ['conversions'] }); },
    onError: (e) => toast.error(errText(e)),
  });
  return (
    <>
      <BackLink to="/growth/offers">Offers</BackLink>
      <PageHeader title="Conversions" actions={sel.length > 0 && <><Button onClick={() => setAct({ action: 'approve', ids: sel })}>Approve {sel.length}</Button><Button variant="danger" onClick={() => setAct({ action: 'reject', ids: sel })}>Reject {sel.length}</Button></>} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'conv', label: 'Conversions' }, { key: 'logs', label: 'Postback log' }]} />
      {tab === 'conv' ? (
        <>
          <select className="input mb-3 !w-auto" aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All</option>{['PENDING', 'APPROVED', 'REJECTED', 'REVERSED', 'PAID'].map((s) => <option key={s}>{s}</option>)}</select>
          <Card><DataTable rows={rows} loading={conv.isLoading} empty="No conversions yet." columns={[
            { key: 'c', header: '', render: (r: any) => (r.status === 'PENDING' ? <input type="checkbox" aria-label="Select" checked={sel.includes(r.id)} onChange={(e) => setSel(e.target.checked ? [...sel, r.id] : sel.filter((x) => x !== r.id))} /> : null) },
            { key: 'o', header: 'Offer', render: (r: any) => r.offerName ?? `#${r.offerId}` }, { key: 'p', header: 'Promoter', hideOnMobile: true, render: (r: any) => r.promoterName ?? '—' },
            { key: 'a', header: 'Amount', render: (r: any) => inr(r.advertiserPrice) }, { key: 's', header: 'Status', render: (r: any) => <div><StatusBadge status={r.status} />{r.fraudFlags && <div className="text-[11px] text-red-600">flagged</div>}</div> },
            { key: 'd', header: 'When', render: (r: any) => fmtDateTime(r.createdAt) },
            { key: 'x', header: '', render: (r: any) => (
              <span className="flex gap-2 text-sm">{r.status === 'PENDING' && <><button className="text-brand" onClick={() => setAct({ action: 'approve', ids: [r.id] })}>Approve</button><button className="text-red-600" onClick={() => setAct({ action: 'reject', ids: [r.id] })}>Reject</button></>}
                {r.status === 'APPROVED' && <button className="text-red-600" onClick={() => setAct({ action: 'reverse', ids: [r.id] })}>Reverse</button>}</span>) },
          ]} /><LoadMore hasMore={!!conv.hasNextPage} busy={conv.isFetchingNextPage} onClick={() => conv.fetchNextPage()} /></Card>
        </>
      ) : (
        <Card><DataTable rows={logs.data} loading={logs.isLoading} empty="No postbacks received." columns={[
          { key: 'w', header: 'Received', render: (l: any) => fmtDateTime(l.createdAt) }, { key: 'o', header: 'Offer', render: (l: any) => `#${l.offerId}` },
          { key: 'r', header: 'Result', render: (l: any) => <Badge tone={l.result === 'ACCEPTED' ? 'green' : 'red'}>{l.result}</Badge> }, { key: 't', header: 'Txn', hideOnMobile: true, render: (l: any) => <code className="text-xs">{l.txnId}</code> },
          { key: 'i', header: 'IP', hideOnMobile: true, render: (l: any) => l.ip },
        ]} /></Card>
      )}
      <ConfirmDialog open={!!act} onClose={() => setAct(null)} danger={act?.action !== 'approve'} confirmLabel={act ? act.action[0].toUpperCase() + act.action.slice(1) : ''} busy={doAct.isPending} onConfirm={() => doAct.mutate()}
        title={act ? `${act.action[0].toUpperCase() + act.action.slice(1)} ${act.ids.length} conversion(s)?` : ''}
        message={<div>{act?.action === 'approve' ? 'The promoter earnings are released once the hold period ends.' : 'Please give a reason. The promoter will see it.'}
          {act?.action !== 'approve' && <input className="input mt-2" aria-label="Reason" placeholder="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />}</div>} />
    </>
  );
}
