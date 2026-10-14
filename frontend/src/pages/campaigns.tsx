import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, ApiError, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, inr, istLocalToIso, num, pct } from '../lib/format';
import { errText, useDebounced } from '../lib/hooks';
import { subscribeCampaign } from '../lib/ws';
import { toast } from '../lib/toast';
import { isIndianMobile } from '../lib/validation';
import { AiAssistant } from '../components/AiAssistant';
import { Alert, BackLink, Badge, Button, Card, ConfirmDialog, DataTable, Loading, LoadMore, Modal, PageHeader, StatCard, StatusBadge, Tabs } from '../components/ui';
import { useTags } from './contacts';

const STATUSES = ['', 'DRAFT', 'SCHEDULED', 'QUEUED', 'SENDING', 'PAUSED', 'COMPLETED', 'CANCELLED', 'FAILED'];

export function CampaignList() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const nav = useNavigate();
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const q = useDebounced(search, 300);
  const list = useQuery({ queryKey: ['campaigns', status, q], queryFn: () => api.get(`/api/v1/campaigns${qs({ status, q: q.trim().length >= 2 ? q.trim() : undefined })}`), refetchInterval: 10000 });
  return (
    <>
      <PageHeader title={t('nav.campaigns')} actions={can.manage && <Link to="/campaigns/new" className="btn-primary">+ New campaign</Link>} />
      <Card>
        <div className="mb-3 flex flex-wrap gap-2">
          <div className="flex flex-wrap gap-1">{STATUSES.map((s) => <button key={s} className={`badge border ${status === s ? 'bg-navy text-white' : ''}`} style={{ borderColor: 'var(--border)' }} onClick={() => setStatus(s)}>{s || 'All'}</button>)}</div>
          <input className="input !w-48 !py-1.5" placeholder="Search name" aria-label="Search" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        {search.trim().length === 1 && <p className="err mb-2">Type at least 2 characters.</p>}
        <DataTable<any> rows={list.data} loading={list.isLoading} empty="No campaigns yet." onRowClick={(r) => nav(`/campaigns/${r.id}`)}
          columns={[
            { key: 'n', header: 'Campaign', render: (r) => <div><div className="font-semibold">{r.name}</div><div className="muted text-xs">{r.templateName}</div></div> },
            { key: 's', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
            { key: 'r', header: 'Recipients', render: (r) => num(r.totalRecipients) },
            { key: 'd', header: 'Delivered', render: (r) => (r.sentCount ? `${num(r.deliveredCount)} (${pct((r.deliveredCount / r.sentCount) * 100)})` : '—') },
            { key: 'w', header: 'When', render: (r) => fmtDateTime(r.scheduledAt ?? r.startedAt ?? r.createdAt) }]} />
      </Card>
    </>
  );
}

interface Mapping { type: 'field' | 'text'; value: string; fallback?: string }
const FIELDS = [['firstName', 'First name'], ['fullName', 'Full name'], ['phone', 'Mobile'], ['email', 'Email'], ['city', 'City']];

export function CampaignWizard() {
  const { id } = useParams();
  const editing = !!id;
  const nav = useNavigate();
  const qc = useQueryClient();
  const { workspace } = useAuth();
  const tags = useTags();
  const templates = useQuery({ queryKey: ['templates', 'APPROVED'], queryFn: () => api.get<any[]>('/api/v1/templates?status=APPROVED') });
  const segments = useQuery({ queryKey: ['segments'], queryFn: () => api.get<any[]>('/api/v1/segments') });
  const existing = useQuery({ queryKey: ['campaign', id], queryFn: () => api.get(`/api/v1/campaigns/${id}`), enabled: editing });
  const [step, setStep] = useState(1);
  const [name, setName] = useState('');
  const [templateId, setTemplateId] = useState('');
  const [audienceType, setAudienceType] = useState('ALL_OPTED_IN');
  const [tagIds, setTagIds] = useState<number[]>([]);
  const [segmentId, setSegmentId] = useState('');
  const [mapping, setMapping] = useState<Record<string, Mapping>>({});
  const [headerMediaUrl, setHeaderMediaUrl] = useState('');
  const [trackLinks, setTrackLinks] = useState(false);
  const [ab, setAb] = useState({ enabled: false, variants: [{ templateId: '', splitPercent: 50 }, { templateId: '', splitPercent: 50 }], metric: 'READ', testPercent: 20, winnerAfterHours: 4 });
  const [sendMode, setSendMode] = useState<'now' | 'schedule'>('now');
  const [scheduledAt, setScheduledAt] = useState('');
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [campaignId, setCampaignId] = useState<number | null>(editing ? Number(id) : null);
  const [estimate, setEstimate] = useState<any>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testPhone, setTestPhone] = useState('');
  const [testMsg, setTestMsg] = useState('');

  useEffect(() => {
    const c = existing.data; if (!c) return;
    setName(c.name); setTemplateId(String(c.templateId)); setAudienceType(c.audienceType);
    setTagIds(c.audienceJson?.tagIds ?? []); setSegmentId(String(c.audienceJson?.segmentId ?? ''));
    setMapping(c.variableMappingJson ?? {}); setHeaderMediaUrl(c.headerMediaUrl ?? ''); setTrackLinks(!!c.trackLinks);
    if (c.scheduledAt) { setSendMode('schedule'); setScheduledAt(new Date(new Date(c.scheduledAt).getTime() + 330 * 60000).toISOString().slice(0, 16)); }
    if (c.variants?.length) setAb({ enabled: true, variants: c.variants.map((v: any) => ({ templateId: String(v.templateId), splitPercent: v.splitPercent })), metric: c.abMetric ?? 'READ', testPercent: c.abTestPercent ?? 20, winnerAfterHours: c.abWinnerAfterHours ?? 4 });
  }, [existing.data]);

  const tpl = (templates.data ?? []).find((x) => String(x.id) === templateId);
  const nVars: number = tpl?.variableCount ?? 0;
  const mediaHeader = tpl && ['IMAGE', 'VIDEO', 'DOCUMENT'].includes(tpl.headerType);
  useEffect(() => { // default mapping: {{1}} = first name
    if (!tpl) return;
    setMapping((m) => { const n = { ...m }; for (let i = 1; i <= nVars; i++) if (!n[i]) n[i] = { type: 'field', value: i === 1 ? 'firstName' : 'fullName', fallback: 'Customer' }; return n; });
  }, [tpl, nVars]);

  const validate = (s: number) => {
    const e: Record<string, string> = {};
    if (s >= 1) {
      if (name.trim().length < 3 || name.trim().length > 100) e.name = 'Campaign name must be 3 to 100 characters.';
      if (!templateId) e.template = 'Choose an approved template.';
      if (audienceType === 'TAGS' && (tagIds.length < 1 || tagIds.length > 10)) e.audience = 'Choose who should receive this.';
      if (audienceType === 'SEGMENT' && !segmentId) e.audience = 'Choose who should receive this.';
    }
    if (s >= 2) {
      for (let i = 1; i <= nVars; i++) {
        const m = mapping[i];
        if (!m || (m.type === 'text' && !m.value.trim()) || (m.type === 'field' && ['email', 'city'].includes(m.value) && !(m.fallback ?? '').trim())) { e.mapping = 'Map every variable or give fallback text.'; break; }
      }
      if (mediaHeader && !headerMediaUrl) e.header = 'Upload the header image.';
      if (ab.enabled) {
        const total = ab.variants.reduce((a, v) => a + Number(v.splitPercent), 0);
        if (total !== 100) e.ab = 'Variant splits must add up to 100%.';
        else if (ab.variants.some((v) => !v.templateId)) e.ab = 'Choose an approved template for every variant.';
      }
    }
    if (s >= 3 && sendMode === 'schedule') {
      const t = scheduledAt ? new Date(scheduledAt + ':00+05:30').getTime() : 0;
      if (!t || t < Date.now() + 10 * 60000 || t > Date.now() + 90 * 86400000) e.when = 'Choose a time at least 10 minutes from now.';
    }
    setErrs(e);
    return Object.keys(e).length === 0;
  };

  const body = () => ({
    name: name.trim(), templateId: Number(ab.enabled ? ab.variants[0].templateId : templateId), audienceType,
    audience: audienceType === 'TAGS' ? { tagIds } : audienceType === 'SEGMENT' ? { segmentId: Number(segmentId) } : {},
    variableMapping: Object.fromEntries(Object.entries(mapping).filter(([k]) => Number(k) <= nVars)),
    headerMediaUrl: headerMediaUrl || null, scheduledAt: sendMode === 'schedule' ? istLocalToIso(scheduledAt) : null, trackLinks,
    abTest: ab.enabled ? { enabled: true, variants: ab.variants.map((v) => ({ templateId: Number(v.templateId), splitPercent: Number(v.splitPercent) })), metric: ab.metric, testPercent: Number(ab.testPercent), winnerAfterHours: Number(ab.winnerAfterHours) } : null,
  });

  const saveDraft = async (): Promise<number | null> => {
    setBusy(true);
    try {
      const r = await (campaignId ? api.put(`/api/v1/campaigns/${campaignId}`, body()) : api.post('/api/v1/campaigns', body()));
      setCampaignId(r.id); qc.invalidateQueries({ queryKey: ['campaigns'] });
      return r.id;
    } catch (e) {
      if (e instanceof ApiError && e.fieldErrors.length) { const m: Record<string, string> = {}; e.fieldErrors.forEach((f) => { m[f.field === 'templateId' ? 'template' : f.field === 'scheduledAt' ? 'when' : f.field === 'variableMapping' ? 'mapping' : f.field === 'headerMediaUrl' ? 'header' : f.field] = f.message; }); setErrs(m);
        if (m.name || m.template || m.audience) setStep(1); }
      toast.error(errText(e)); return null;
    } finally { setBusy(false); }
  };

  const next = async () => {
    if (!validate(step)) return;
    if (step === 3) { // server validation + estimate before the confirm step
      const cid = await saveDraft(); if (!cid) return;
      try { setEstimate(await api.post(`/api/v1/campaigns/${cid}/estimate`)); setStep(4); } catch (e) { toast.error(errText(e)); }
      return;
    }
    setStep(step + 1);
  };

  const confirmSend = async () => {
    if (!campaignId) return;
    setBusy(true);
    try {
      const r = await api.post(`/api/v1/campaigns/${campaignId}/${sendMode === 'schedule' ? 'schedule' : 'send'}`);
      toast.success(sendMode === 'schedule' ? 'Campaign scheduled.' : 'Campaign is on its way.');
      qc.invalidateQueries({ queryKey: ['campaigns'] }); qc.invalidateQueries({ queryKey: ['wa-account'] });
      nav(`/campaigns/${r.id}`);
    } catch (e) { toast.error(errText(e)); setConfirm(false); } finally { setBusy(false); }
  };

  const testSend = async () => {
    if (!isIndianMobile(testPhone)) { setTestMsg('Enter a valid 10-digit mobile number.'); return; }
    const cid = campaignId ?? (await saveDraft()); if (!cid) return;
    try { const r = await api.post(`/api/v1/campaigns/${cid}/test-send`, { phone: testPhone }); setTestMsg(`Test message sent. ${r.remaining} test sends left.`); } catch (e) { setTestMsg(errText(e)); }
  };

  const mappingRow = (i: number) => {
    const m = mapping[i] ?? { type: 'field', value: 'firstName', fallback: '' };
    const set = (p: Partial<Mapping>) => setMapping((x) => ({ ...x, [i]: { ...m, ...p } }));
    return (
      <div key={i} className="mb-3 grid items-start gap-2 sm:grid-cols-[4rem_9rem_1fr_1fr]">
        <span className="font-mono text-sm pt-2.5">{`{{${i}}}`}</span>
        <select className="input" value={m.type} onChange={(e) => set({ type: e.target.value as any, value: e.target.value === 'field' ? 'firstName' : '' })} aria-label={`Variable ${i} source`}><option value="field">Contact field</option><option value="text">Fixed text</option></select>
        {m.type === 'field' ? <select className="input" value={m.value} onChange={(e) => set({ value: e.target.value })}>{FIELDS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
          : <input className="input" maxLength={100} value={m.value} onChange={(e) => set({ value: e.target.value })} placeholder="Text (max 100)" />}
        {m.type === 'field' ? <input className="input" maxLength={100} value={m.fallback ?? ''} onChange={(e) => set({ fallback: e.target.value })} placeholder={['email', 'city'].includes(m.value) ? 'Fallback text (required)' : 'Fallback text (optional)'} /> : <span />}
      </div>
    );
  };

  if (editing && existing.isLoading) return <Loading />;
  const steps = ['Audience', 'Personalise', 'Schedule', 'Confirm'];
  return (
    <>
      <BackLink to="/campaigns">Campaigns</BackLink>
      <PageHeader title={editing ? 'Edit campaign' : 'New campaign'} subtitle={`Step ${step} of 4 · ${steps[step - 1]}`} />
      <div className="mb-4 flex gap-1" aria-hidden>{steps.map((s, i) => <div key={s} className="h-1.5 flex-1 rounded-full" style={{ background: i < step ? '#12A388' : 'var(--border)' }} />)}</div>
      <Card>
        {step === 1 && (
          <div className="max-w-2xl">
            <label className="label" htmlFor="cn">Campaign name *</label><input id="cn" className="input" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} aria-invalid={!!errs.name} />{errs.name && <p className="err">{errs.name}</p>}
            <label className="label mt-3" htmlFor="ct">Template *</label>
            <select id="ct" className="input" value={templateId} onChange={(e) => setTemplateId(e.target.value)} aria-invalid={!!errs.template}><option value="">Choose an approved template…</option>{(templates.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.name} ({t.language})</option>)}</select>
            {errs.template && <p className="err">{errs.template}</p>}
            {tpl && <div className="mt-2 rounded-xl bg-brand-soft p-3 text-sm text-navy whitespace-pre-wrap">{tpl.body}</div>}
            {!templates.isLoading && !templates.data?.length && <Alert tone="amber">You have no approved templates yet. <Link to="/templates/new" className="font-semibold underline">Create one</Link>.</Alert>}
            <label className="label mt-3">Audience *</label>
            <div className="flex flex-wrap gap-2">{[['ALL_OPTED_IN', 'All opted-in'], ['TAGS', 'Tags'], ['SEGMENT', 'Segment']].map(([v, l]) => <button key={v} type="button" className={audienceType === v ? 'btn-primary' : 'btn-ghost'} onClick={() => setAudienceType(v)}>{l}</button>)}</div>
            {audienceType === 'TAGS' && <div className="mt-2 flex flex-wrap gap-1">{(tags.data ?? []).map((tg) => { const on = tagIds.includes(tg.id); return <button type="button" key={tg.id} className="badge border" style={{ background: on ? tg.color : 'transparent', color: on ? '#fff' : undefined, borderColor: tg.color }} onClick={() => setTagIds((s) => on ? s.filter((x) => x !== tg.id) : s.length < 10 ? [...s, tg.id] : s)}>{tg.name} · {tg.contactCount}</button>; })}</div>}
            {audienceType === 'SEGMENT' && <select className="input mt-2" value={segmentId} onChange={(e) => setSegmentId(e.target.value)}><option value="">Choose a segment…</option>{(segments.data ?? []).map((s) => <option key={s.id} value={s.id}>{s.name} ({s.contactCount})</option>)}</select>}
            {errs.audience && <p className="err">{errs.audience}</p>}
            <p className="muted mt-2 text-xs">Only contacts who opted in receive marketing messages. Opted-out contacts are always removed.</p>
          </div>
        )}
        {step === 2 && (
          <div>
            {nVars > 0 ? <><h3 className="mb-2 font-bold">Personalisation</h3>{Array.from({ length: nVars }, (_, i) => mappingRow(i + 1))}</> : <p className="muted mb-3 text-sm">This template has no variables.</p>}
            {errs.mapping && <p className="err mb-2">{errs.mapping}</p>}
            {mediaHeader && <div className="mb-4"><label className="label">Header {tpl.headerType.toLowerCase()} *</label>
              <input type="file" className="input" accept={tpl.headerType === 'IMAGE' ? 'image/jpeg,image/png' : tpl.headerType === 'VIDEO' ? 'video/mp4' : 'application/pdf'} onChange={async (e) => { const f = e.target.files?.[0]; if (!f) return; try { setHeaderMediaUrl((await api.upload(f, 'header')).url); } catch (er) { toast.error(errText(er)); } }} />
              {headerMediaUrl && <p className="mt-1 text-sm text-brand">Uploaded ✓</p>}{errs.header && <p className="err">{errs.header}</p>}</div>}
            <label className="mb-4 flex items-center gap-2 text-sm"><input type="checkbox" checked={trackLinks} onChange={(e) => setTrackLinks(e.target.checked)} /> Track link clicks (URLs in fixed text become short links)</label>
            <div className="rounded-xl border p-3" style={{ borderColor: 'var(--border)' }}>
              <label className="flex items-center gap-2 font-semibold"><input type="checkbox" checked={ab.enabled} onChange={(e) => setAb({ ...ab, enabled: e.target.checked })} /> A/B test</label>
              {ab.enabled && (
                <div className="mt-3 space-y-2">
                  {ab.variants.map((v, i) => (
                    <div key={i} className="grid items-center gap-2 sm:grid-cols-[2rem_1fr_6rem_auto]"><b>{'ABC'[i]}</b>
                      <select className="input" value={v.templateId} onChange={(e) => setAb({ ...ab, variants: ab.variants.map((x, j) => (j === i ? { ...x, templateId: e.target.value } : x)) })}><option value="">Template…</option>{(templates.data ?? []).filter((t) => !tpl || t.category === tpl.category).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select>
                      <div className="flex items-center gap-1"><input className="input" type="number" min={10} max={90} value={v.splitPercent} onChange={(e) => setAb({ ...ab, variants: ab.variants.map((x, j) => (j === i ? { ...x, splitPercent: Number(e.target.value) } : x)) })} aria-label="Split %" />%</div>
                      {ab.variants.length > 2 && <button className="text-red-600" onClick={() => setAb({ ...ab, variants: ab.variants.filter((_, j) => j !== i) })}>×</button>}
                    </div>))}
                  {ab.variants.length < 3 && <button className="text-sm font-semibold text-brand" onClick={() => setAb({ ...ab, variants: [...ab.variants, { templateId: '', splitPercent: 10 }] })}>+ Add variant</button>}
                  <div className="grid gap-2 sm:grid-cols-3">
                    <div><label className="label">Winner metric</label><select className="input" value={ab.metric} onChange={(e) => setAb({ ...ab, metric: e.target.value })}><option value="READ">Read rate</option><option value="CLICK">Click rate</option></select></div>
                    <div><label className="label">Test group (10–50%)</label><input className="input" type="number" min={10} max={50} value={ab.testPercent} onChange={(e) => setAb({ ...ab, testPercent: Number(e.target.value) })} /></div>
                    <div><label className="label">Pick winner after (1–24 h)</label><input className="input" type="number" min={1} max={24} value={ab.winnerAfterHours} onChange={(e) => setAb({ ...ab, winnerAfterHours: Number(e.target.value) })} /></div>
                  </div>
                  {errs.ab && <p className="err">{errs.ab}</p>}
                </div>)}
            </div>
            <div className="mt-4"><AiAssistant kind="OFFER_MESSAGE" compact /></div>
          </div>
        )}
        {step === 3 && (
          <div className="max-w-xl">
            <div className="mb-3 flex gap-2"><button className={sendMode === 'now' ? 'btn-primary' : 'btn-ghost'} onClick={() => setSendMode('now')}>Send now</button><button className={sendMode === 'schedule' ? 'btn-primary' : 'btn-ghost'} onClick={() => setSendMode('schedule')}>Schedule</button></div>
            {sendMode === 'schedule' && <div><label className="label">Send at (IST)</label><input type="datetime-local" className="input" value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)} aria-invalid={!!errs.when} />
              {errs.when && <p className="err">{errs.when}</p>}
              {scheduledAt && (() => { const h = Number(scheduledAt.slice(11, 13)); return h < 9 || h >= 21 ? <p className="mt-1 text-sm text-amber-600">Outside the recommended 9 AM – 9 PM IST window.</p> : null; })()}</div>}
            <div className="mt-5 rounded-xl border p-3" style={{ borderColor: 'var(--border)' }}>
              <b className="text-sm">Send a test message</b>
              <div className="mt-2 flex gap-2"><input className="input" placeholder="Mobile number" value={testPhone} onChange={(e) => setTestPhone(e.target.value)} inputMode="tel" /><Button variant="ghost" onClick={testSend}>Send test</Button></div>
              {testMsg && <p className="muted mt-1 text-sm">{testMsg}</p>}<p className="muted mt-1 text-xs">Up to 5 test sends per campaign. Test messages are charged like normal messages.</p>
            </div>
          </div>
        )}
        {step === 4 && estimate && (
          <div className="max-w-xl">
            <dl className="grid grid-cols-2 gap-3">
              <div className="card"><dt className="muted text-xs">Recipients</dt><dd className="text-2xl font-bold">{num(estimate.recipients)}</dd></div>
              <div className="card"><dt className="muted text-xs">Estimated cost</dt><dd className="text-2xl font-bold">{inr(estimate.cost, 4)}</dd></div>
              <div className="card"><dt className="muted text-xs">Wallet balance</dt><dd className={`text-2xl font-bold ${estimate.sufficient ? '' : 'text-red-600'}`}>{inr(estimate.balance, 2)}</dd></div>
              <div className="card"><dt className="muted text-xs">When</dt><dd className="text-sm font-semibold">{sendMode === 'now' ? 'Immediately' : fmtDateTime(istLocalToIso(scheduledAt))}</dd></div>
            </dl>
            {estimate.recipients < 1 && <Alert tone="red">No opted-in contacts match this audience.</Alert>}
            {!estimate.sufficient && <Alert tone="red" action={<Link to="/settings/billing" className="btn-primary !py-1.5">Top up</Link>}>Your wallet cannot cover this campaign.</Alert>}
            {!estimate.withinLimit && <Alert tone="red">Recipients exceed your number's Meta messaging limit.</Alert>}
            {estimate.warnings?.map((w: string) => <Alert key={w} tone="amber">{w}</Alert>)}
            <p className="muted text-sm">The estimated cost is reserved from your wallet when you confirm; anything unused is released at the end.</p>
          </div>
        )}
        <div className="mt-6 flex flex-wrap justify-between gap-2">
          <div className="flex gap-2">{step > 1 && <Button variant="ghost" onClick={() => setStep(step - 1)}>Back</Button>}{step < 4 && <Button variant="ghost" busy={busy} onClick={async () => { if (validate(1) && (await saveDraft())) { toast.success('Draft saved.'); } }}>Save draft</Button>}</div>
          {step < 4 ? <Button busy={busy} onClick={next}>{step === 3 ? 'Review' : 'Next'}</Button>
            : <Button busy={busy} disabled={!estimate || estimate.recipients < 1 || !estimate.sufficient || !estimate.withinLimit} onClick={() => setConfirm(true)}>{sendMode === 'schedule' ? 'Schedule campaign' : 'Send campaign'}</Button>}
        </div>
      </Card>
      <Modal open={confirm} onClose={() => setConfirm(false)} title="Confirm campaign" footer={<><Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button><Button busy={busy} onClick={confirmSend}>Confirm & {sendMode === 'schedule' ? 'schedule' : 'send'}</Button></>}>
        <dl className="space-y-2 text-sm"><div className="flex justify-between"><dt className="muted">Name</dt><dd className="font-semibold">{name}</dd></div><div className="flex justify-between"><dt className="muted">Recipients</dt><dd className="font-semibold">{num(estimate?.recipients)}</dd></div>
          <div className="flex justify-between"><dt className="muted">Cost (reserved)</dt><dd className="font-semibold">{inr(estimate?.cost, 4)}</dd></div>
          <div className="flex justify-between"><dt className="muted">Time</dt><dd className="font-semibold">{sendMode === 'now' ? 'Now' : fmtDateTime(istLocalToIso(scheduledAt))}</dd></div></dl>
      </Modal>
    </>
  );
}

export function CampaignReport() {
  const { id } = useParams();
  const { can, workspace } = useAuth();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const sq = useDebounced(search, 300);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [live, setLive] = useState<any>(null);
  const c = useQuery({ queryKey: ['campaign', id], queryFn: () => api.get(`/api/v1/campaigns/${id}`), refetchInterval: (q) => (['SENDING', 'QUEUED'].includes((q.state.data as any)?.status) ? 5000 : false) });
  useEffect(() => { if (!workspace) return; return subscribeCampaign(workspace.id, Number(id), (m) => { setLive(m); if (m.status !== c.data?.status) qc.invalidateQueries({ queryKey: ['campaign', id] }); }); }, [workspace, id]);
  const msgs = useInfiniteQuery({ queryKey: ['cmsgs', id, status, sq, c.data?.sentCount], queryFn: ({ pageParam }) => api.get(`/api/v1/campaigns/${id}/messages${qs({ status, q: sq.trim().length >= 2 ? sq.trim() : undefined, cursor: pageParam })}`),
    initialPageParam: undefined as number | undefined, getNextPageParam: (l: any) => l.nextCursor ?? undefined });
  const act = async (a: string) => { try { const r = await api.post(`/api/v1/campaigns/${id}/${a}`); if (a === 'duplicate') nav(`/campaigns/${r.id}/edit`); else { qc.invalidateQueries({ queryKey: ['campaign', id] }); setConfirm(null); } } catch (e) { toast.error(errText(e)); setConfirm(null); } };
  const d = c.data;
  if (c.isLoading) return <Loading />;
  if (!d) return null;
  const sent = live?.sent ?? d.sentCount, delivered = live?.delivered ?? d.deliveredCount, read = live?.read ?? d.readCount, failed = live?.failed ?? d.failedCount;
  const rows = msgs.data?.pages.flatMap((p: any) => p.items) ?? [];
  const progress = d.totalRecipients ? Math.round(((sent + failed) / d.totalRecipients) * 100) : 0;
  return (
    <>
      <BackLink to="/campaigns">Campaigns</BackLink>
      <PageHeader title={d.name} subtitle={`${d.templateName} · ${d.category}`} actions={<>
        <StatusBadge status={live?.status ?? d.status} />
        {can.manage && d.actions.includes('edit') && <Link to={`/campaigns/${id}/edit`} className="btn-ghost">Edit</Link>}
        {can.manage && d.actions.includes('pause') && <Button variant="ghost" onClick={() => act('pause')}>Pause</Button>}
        {can.manage && d.actions.includes('resume') && <Button onClick={() => act('resume')}>Resume</Button>}
        {can.manage && d.actions.includes('cancel') && <Button variant="danger" onClick={() => setConfirm('cancel')}>Cancel</Button>}
        {can.manage && d.actions.includes('duplicate') && <Button variant="ghost" onClick={() => act('duplicate')}>Duplicate</Button>}
        {can.manage && <Button variant="ghost" onClick={() => api.download(`/api/v1/campaigns/${id}/messages.csv`, `campaign-${id}.csv`).catch((e) => toast.error(errText(e)))}>Export CSV</Button>}
      </>} />
      {d.pausedBySystem && d.status === 'PAUSED' && <Alert tone="amber">This campaign was paused automatically (WhatsApp disconnected, quality RED, or repeated failures). Fix the cause, then resume.</Alert>}
      {['SENDING', 'QUEUED'].includes(live?.status ?? d.status) && <div className="mb-4 h-2 overflow-hidden rounded-full" style={{ background: 'var(--border)' }} aria-label={`Progress ${progress}%`}><div className="h-full bg-brand transition-all" style={{ width: `${progress}%` }} /></div>}
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <StatCard label="Recipients" value={num(d.totalRecipients)} /><StatCard label="Sent" value={num(sent)} />
        <StatCard label="Delivered" value={num(delivered)} sub={sent ? pct((delivered / sent) * 100) : undefined} /><StatCard label="Read" value={num(read)} sub={sent ? pct((read / sent) * 100) : undefined} />
        <StatCard label="Failed" value={num(failed)} tone={failed ? 'warn' : undefined} sub={`Replies ${num(live?.replied ?? d.repliedCount)} · cost ${inr(d.actualCost, 2)}`} />
      </div>
      {d.variants?.length > 0 && (
        <Card title="A/B test" className="mb-4"><DataTable<any> rows={d.variants} columns={[
          { key: 'l', header: 'Variant', render: (v) => <b>{v.label} {v.isWinner && <Badge tone="green">Winner</Badge>}</b> }, { key: 't', header: 'Template', render: (v) => v.templateName }, { key: 's', header: 'Split', render: (v) => `${v.splitPercent}%` },
          { key: 'se', header: 'Sent', render: (v) => num(v.sentCount) }, { key: 'r', header: 'Read', render: (v) => `${num(v.readCount)} (${pct(v.sentCount ? (v.readCount / v.sentCount) * 100 : 0)})` }, { key: 'c', header: 'Clicks', render: (v) => num(v.clickCount) }]} /></Card>)}
      <Card title="Recipients">
        <div className="mb-3 flex flex-wrap gap-2">
          {['', 'QUEUED', 'SENT', 'DELIVERED', 'READ', 'FAILED'].map((s) => <button key={s} className={`badge border ${status === s ? 'bg-navy text-white' : ''}`} style={{ borderColor: 'var(--border)' }} onClick={() => setStatus(s)}>{s || 'All'}</button>)}
          <input className="input !w-48 !py-1.5" placeholder="Search recipient" aria-label="Search recipient" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <DataTable<any> rows={rows} loading={msgs.isLoading} columns={[
          { key: 'n', header: 'Name', render: (r) => <div><div className="font-semibold">{r.fullName}</div><div className="muted text-xs">{r.phone}</div></div> }, { key: 'v', header: 'Variant', render: (r) => r.variant ?? '—' },
          { key: 's', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
          { key: 'e', header: 'Details', render: (r) => (r.status === 'FAILED' ? <span className="text-sm text-red-600">{r.reason} <span className="muted">({r.errorCode})</span></span> : <span className="muted text-xs">{fmtDateTime(r.readAt ?? r.deliveredAt ?? r.sentAt)}</span>) }]} />
        <LoadMore hasMore={!!msgs.hasNextPage} busy={msgs.isFetchingNextPage} onClick={() => msgs.fetchNextPage()} />
      </Card>
      <ConfirmDialog open={confirm === 'cancel'} onClose={() => setConfirm(null)} danger title="Cancel campaign" message="Nothing more will be sent and the unused wallet reservation is released." confirmLabel="Cancel campaign" onConfirm={() => act('cancel')} />
    </>
  );
}
