import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, inr, num } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Badge, Button, Card, ConfirmDialog, DataTable, Loading, Modal, PageHeader, StatCard, StatusBadge } from '../components/ui';
import { Err, fieldErrors } from '../components/common';

const PLATFORMS = ['GOOGLE', 'META', 'YOUTUBE', 'LINKEDIN', 'X', 'OTHER'];
const A0 = { platform: 'META', name: '', objective: 'LEADS', dailyBudget: '', totalBudget: '', startDate: '', endDate: '', status: 'DRAFT', externalId: '', utmCampaign: '', notes: '' };
const money = (v: any) => (v === null || v === undefined ? '—' : inr(v, 2));

export function AdsList() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const { hasFeature } = useAuth();
  const list = useQuery({ queryKey: ['ads'], queryFn: () => api.get<any[]>('/api/v1/ads') });
  const [edit, setEdit] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const n = (x: any) => (x === '' || x == null ? null : Number(x));
  const save = useMutation({
    mutationFn: () => api.post('/api/v1/ads', { ...edit, dailyBudget: n(edit.dailyBudget) ?? 0, totalBudget: n(edit.totalBudget) }),
    onSuccess: (r) => { setEdit(null); setErrs({}); qc.invalidateQueries({ queryKey: ['ads'] }); nav(`/ads/${r.id}`); }, onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); },
  });
  const tot = (list.data ?? []).reduce((a, c) => ({ spend: a.spend + Number(c.spend), clicks: a.clicks + Number(c.clicks), conv: a.conv + Number(c.conversions), rev: a.rev + Number(c.revenue) }), { spend: 0, clicks: 0, conv: 0, rev: 0 });
  return (
    <>
      <PageHeader title="Ad campaigns" subtitle="Track spend and results across Google, Meta and others" actions={<Button onClick={() => { setErrs({}); setEdit({ ...A0 }); }}>+ New campaign</Button>} />
      {!hasFeature('ads') && <Alert tone="blue">Ad tracking is part of the Growth plan.</Alert>}
      <div className="my-3 grid grid-cols-2 gap-3 md:grid-cols-4"><StatCard label="Total spend" value={inr(tot.spend, 0)} /><StatCard label="Clicks" value={num(tot.clicks)} /><StatCard label="Conversions" value={num(tot.conv)} /><StatCard label="Return on spend" value={tot.spend ? `${(tot.rev / tot.spend).toFixed(2)}x` : '—'} /></div>
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No ad campaigns yet." onRowClick={(c: any) => nav(`/ads/${c.id}`)} columns={[
        { key: 'n', header: 'Campaign', render: (c: any) => <div><b>{c.name}</b><div className="muted text-xs">{c.platform} · {c.objective}</div></div> }, { key: 's', header: 'Status', render: (c: any) => <StatusBadge status={c.status} /> },
        { key: 'sp', header: 'Spend', render: (c: any) => inr(c.spend, 0) }, { key: 'cl', header: 'Clicks', hideOnMobile: true, render: (c: any) => num(c.clicks) },
        { key: 'cpa', header: 'Cost / result', hideOnMobile: true, render: (c: any) => money(c.cpa) }, { key: 'ro', header: 'ROAS', render: (c: any) => (c.roas ? `${c.roas}x` : '—') },
      ]} /></Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} wide title="New ad campaign" footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Create</Button></>}>
        {edit && <AdForm v={edit} set={setEdit} errs={errs} />}
      </Modal>
    </>
  );
}

function AdForm({ v, set, errs }: { v: any; set: (x: any) => void; errs: Record<string, string> }) {
  const F = (k: string, label: string, props: any = {}) => (<div><label className="label" htmlFor={`ad-${k}`}>{label}</label><input id={`ad-${k}`} className="input" value={v[k] ?? ''} onChange={(e) => set({ ...v, [k]: e.target.value })} {...props} /><Err m={errs[k]} /></div>);
  const S = (k: string, label: string, opts: string[]) => (<div><label className="label" htmlFor={`ad-${k}`}>{label}</label><select id={`ad-${k}`} className="input" value={v[k]} onChange={(e) => set({ ...v, [k]: e.target.value })}>{opts.map((o) => <option key={o}>{o}</option>)}</select></div>);
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {S('platform', 'Platform', PLATFORMS)}{S('objective', 'Goal', ['AWARENESS', 'TRAFFIC', 'LEADS', 'SALES', 'APP_INSTALLS'])}
      <div className="sm:col-span-2">{F('name', 'Name', { maxLength: 100 })}</div>
      {F('dailyBudget', 'Daily budget ₹', { inputMode: 'decimal' })}{F('totalBudget', 'Total budget ₹ (optional)', { inputMode: 'decimal' })}
      {F('startDate', 'Start', { type: 'date' })}{F('endDate', 'End', { type: 'date' })}
      {S('status', 'Status', ['DRAFT', 'ACTIVE', 'PAUSED', 'ENDED'])}{F('utmCampaign', 'utm_campaign (to match your links)')}
      <div className="sm:col-span-2">{F('externalId', 'Campaign ID on the platform (for sync)')}</div>
      <div className="sm:col-span-2">{F('notes', 'Notes')}</div>
    </div>
  );
}

export function AdDetail() {
  const { id } = useParams();
  const qc = useQueryClient();
  const nav = useNavigate();
  const q = useQuery({ queryKey: ['ad', id], queryFn: () => api.get(`/api/v1/ads/${id}`) });
  const [edit, setEdit] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [m, setM] = useState({ day: new Date().toISOString().slice(0, 10), spend: '', impressions: '', clicks: '', conversions: '', revenue: '' });
  const [rm, setRm] = useState(false);
  const refresh = () => { qc.invalidateQueries({ queryKey: ['ad', id] }); qc.invalidateQueries({ queryKey: ['ads'] }); };
  const n = (x: any) => (x === '' || x == null ? null : Number(x));
  const save = useMutation({ mutationFn: () => api.put(`/api/v1/ads/${id}`, { ...edit, dailyBudget: n(edit.dailyBudget) ?? 0, totalBudget: n(edit.totalBudget) }), onSuccess: () => { setEdit(null); refresh(); toast.success('Saved'); }, onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); } });
  const addM = useMutation({ mutationFn: () => api.post(`/api/v1/ads/${id}/metrics`, { day: m.day, spend: n(m.spend), impressions: n(m.impressions), clicks: n(m.clicks), conversions: n(m.conversions), revenue: n(m.revenue) }),
    onSuccess: () => { setM({ ...m, spend: '', impressions: '', clicks: '', conversions: '', revenue: '' }); refresh(); toast.success('Added'); }, onError: (e: any) => toast.error(e.fieldErrors?.[0]?.message ?? errText(e)) });
  const sync = useMutation({ mutationFn: () => api.post(`/api/v1/ads/${id}/sync`), onSuccess: (r) => { toast.success(`Synced ${r.synced} days`); refresh(); }, onError: (e) => toast.error(errText(e)) });
  const del = useMutation({ mutationFn: () => api.del(`/api/v1/ads/${id}`), onSuccess: () => { qc.invalidateQueries({ queryKey: ['ads'] }); nav('/ads'); } });
  const upload = async (f: File) => { const fd = new FormData(); fd.append('file', f); try { const r = await api.post(`/api/v1/ads/${id}/import`, fd); toast.success(`Imported ${r.imported} rows${r.problems.length ? `, ${r.problems.length} skipped` : ''}`); refresh(); } catch (e) { toast.error(errText(e)); } };
  if (q.isLoading) return <Loading />;
  const a = q.data;
  const mf = (k: keyof typeof m, label: string) => (<input className="input" aria-label={label} placeholder={label} inputMode="decimal" value={m[k]} onChange={(e) => setM({ ...m, [k]: e.target.value })} />);
  return (
    <>
      <BackLink to="/ads">Ad campaigns</BackLink>
      <PageHeader title={a.name} subtitle={<span className="flex items-center gap-2"><Badge>{a.platform}</Badge><StatusBadge status={a.status} /></span>}
        actions={<><Button variant="ghost" busy={sync.isPending} onClick={() => sync.mutate()}>Sync latest</Button><Button variant="ghost" onClick={() => { setErrs({}); setEdit({ ...a, dailyBudget: a.dailyBudget, totalBudget: a.totalBudget ?? '', startDate: a.startDate ?? '', endDate: a.endDate ?? '', externalId: a.externalId ?? '', utmCampaign: a.utmCampaign ?? '', notes: a.notes ?? '' }); }}>Edit</Button><Button variant="ghost" onClick={() => setRm(true)}>Delete</Button></>} />
      {a.budgetUsedPercent && Number(a.budgetUsedPercent) >= 90 && <Alert tone="amber">{a.budgetUsedPercent}% of the total budget is used.</Alert>}
      <div className="my-3 grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-6">
        <StatCard label="Spend" value={inr(a.spend, 0)} sub={a.budgetUsedPercent ? `${a.budgetUsedPercent}% of budget` : undefined} /><StatCard label="Impressions" value={num(a.impressions)} /><StatCard label="Clicks" value={num(a.clicks)} sub={`CTR ${a.ctr}%`} />
        <StatCard label="Conversions" value={num(a.conversions)} /><StatCard label="Cost / click" value={money(a.cpc)} /><StatCard label="Cost / result" value={money(a.cpa)} sub={a.roas ? `ROAS ${a.roas}x` : undefined} />
      </div>
      <Card title="Add a day's numbers" action={<label className="btn-ghost cursor-pointer text-sm">Import CSV<input type="file" hidden accept=".csv,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); e.target.value = ''; }} /></label>}>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-7"><input type="date" className="input" aria-label="Day" value={m.day} onChange={(e) => setM({ ...m, day: e.target.value })} />{mf('spend', 'Spend ₹')}{mf('impressions', 'Impressions')}{mf('clicks', 'Clicks')}{mf('conversions', 'Conversions')}{mf('revenue', 'Revenue ₹')}
          <Button busy={addM.isPending} onClick={() => addM.mutate()}>Add</Button></div>
        <p className="muted mt-2 text-xs">CSV columns: day, spend, impressions, clicks, conversions, revenue. Re-adding a day replaces it.</p>
      </Card>
      <Card title="Daily results" className="mt-4"><DataTable rows={a.daily.map((d: any) => ({ ...d, id: d.day }))} empty="No numbers yet." columns={[
        { key: 'd', header: 'Day', render: (d: any) => fmtDate(d.day) }, { key: 's', header: 'Spend', render: (d: any) => inr(d.spend) }, { key: 'i', header: 'Impr.', hideOnMobile: true, render: (d: any) => num(d.impressions) },
        { key: 'c', header: 'Clicks', render: (d: any) => num(d.clicks) }, { key: 'v', header: 'Conv.', render: (d: any) => num(d.conversions) }, { key: 'r', header: 'Revenue', hideOnMobile: true, render: (d: any) => inr(d.revenue) }, { key: 'o', header: 'Source', hideOnMobile: true, render: (d: any) => <span className="muted text-xs">{d.source}</span> },
      ]} /></Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} wide title="Edit campaign" footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>{edit && <AdForm v={edit} set={setEdit} errs={errs} />}</Modal>
      <ConfirmDialog open={rm} onClose={() => setRm(false)} title="Delete this campaign?" message="Its daily numbers are deleted too." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => del.mutate()} />
    </>
  );
}
