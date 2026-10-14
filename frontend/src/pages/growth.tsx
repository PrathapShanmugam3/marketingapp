import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, getAccessToken } from '../lib/api';
import { fmtDate, fmtDateTime, istLocalToIso, num } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Badge, Button, Card, ConfirmDialog, CopyButton, DataTable, Loading, Modal, PageHeader, StatCard, StatusBadge } from '../components/ui';
import { useTags } from './contacts';

const fe = (e: any): Record<string, string> => Object.fromEntries((e?.fieldErrors ?? []).map((x: any) => [x.field, x.message]));
const Err = ({ m }: { m?: string }) => (m ? <p className="mt-1 text-sm text-red-600" role="alert">{m}</p> : null);
const useDelete = (path: string, key: string, text: string) => {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (id: number) => api.del(`${path}/${id}`), onSuccess: () => { qc.invalidateQueries({ queryKey: [key] }); toast.success(text); }, onError: (e) => toast.error(errText(e)) });
};

/* ------------------------------ apps (P27) ------------------------------ */
export function Apps() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['apps'], queryFn: () => api.get<any[]>('/api/v1/apps') });
  const [edit, setEdit] = useState<any | null>(null);
  const [rm, setRm] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const del = useDelete('/api/v1/apps', 'apps', 'App removed');
  const save = useMutation({
    mutationFn: () => (edit.id ? api.put(`/api/v1/apps/${edit.id}`, edit) : api.post('/api/v1/apps', edit)),
    onSuccess: () => { setEdit(null); setErrs({}); qc.invalidateQueries({ queryKey: ['apps'] }); toast.success('App saved'); },
    onError: (e: any) => { setErrs(fe(e)); toast.error(errText(e)); },
  });
  const F = ({ k, label, ph }: { k: string; label: string; ph?: string }) => (
    <div><label className="label" htmlFor={`app-${k}`}>{label}</label><input id={`app-${k}`} className="input" placeholder={ph} value={edit?.[k] ?? ''} onChange={(e) => setEdit({ ...edit, [k]: e.target.value })} /><Err m={errs[k]} /></div>
  );
  return (
    <>
      <PageHeader title="Apps" subtitle="Smart links that open the right store" actions={<Button onClick={() => { setErrs({}); setEdit({ name: '' }); }}>+ Add app</Button>} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="Add your app to get one smart link for Android, iPhone and web." columns={[
        { key: 'n', header: 'App', render: (a: any) => <b>{a.name}</b> }, { key: 'p', header: 'Android', hideOnMobile: true, render: (a: any) => a.androidPackage || '—' },
        { key: 'l', header: 'Smart link', render: (a: any) => (a.smartCode ? <code className="text-xs">/l/{a.smartCode}</code> : '—') },
        { key: 'x', header: '', render: (a: any) => <span className="flex gap-3 text-sm"><button className="text-brand" onClick={() => { setErrs({}); setEdit(a); }}>Edit</button><button className="text-red-600" onClick={() => setRm(a)}>Delete</button></span> },
      ]} /></Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} title={edit?.id ? 'Edit app' : 'Add app'} footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
        <div className="space-y-3"><F k="name" label="App name" /><F k="androidPackage" label="Android package" ph="com.example.app" /><F k="playStoreUrl" label="Play Store link" ph="https://play.google.com/store/apps/details?id=…" />
          <F k="iosAppId" label="iOS app ID (optional)" /><F k="appStoreUrl" label="App Store link (optional)" /><F k="webFallbackUrl" label="Website fallback" ph="https://…" /></div>
      </Modal>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this app?" message="Its smart links will stop working." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => { del.mutate(rm.id); setRm(null); }} />
    </>
  );
}

/* --------------------------- links and QR (P28) --------------------------- */
export function Links() {
  const qc = useQueryClient();
  const nav = useNavigate();
  const list = useQuery({ queryKey: ['links'], queryFn: () => api.get<any[]>('/api/v1/links') });
  const apps = useQuery({ queryKey: ['apps'], queryFn: () => api.get<any[]>('/api/v1/apps') });
  const domains = useQuery({ queryKey: ['domains'], queryFn: () => api.get<any[]>('/api/v1/domains') });
  const [open, setOpen] = useState(false);
  const [f, setF] = useState<any>({ type: 'URL', destinationUrl: '', code: '', utmSource: '', utmMedium: '', utmCampaign: '', appId: '', domainId: '', expiresAt: '' });
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [rm, setRm] = useState<any | null>(null);
  const del = useDelete('/api/v1/links', 'links', 'Link deleted');
  const toggle = useMutation({ mutationFn: (l: any) => api.patch(`/api/v1/links/${l.id}`, { active: !l.isActive }), onSuccess: () => qc.invalidateQueries({ queryKey: ['links'] }), onError: (e) => toast.error(errText(e)) });
  const create = useMutation({
    mutationFn: () => api.post('/api/v1/links', { ...f, appId: f.appId ? Number(f.appId) : null, domainId: f.domainId ? Number(f.domainId) : null, expiresAt: f.expiresAt ? istLocalToIso(f.expiresAt) : null }),
    onSuccess: (r) => { setOpen(false); setErrs({}); qc.invalidateQueries({ queryKey: ['links'] }); nav(`/links/${r.id}`); },
    onError: (e: any) => { setErrs(fe(e)); toast.error(errText(e)); },
  });
  const s = (k: string, v: string) => setF({ ...f, [k]: v });
  return (
    <>
      <PageHeader title="Links and QR codes" subtitle="Track clicks from your posters, bills and messages" actions={<Button onClick={() => setOpen(true)}>+ New link</Button>} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No short links yet." onRowClick={(l: any) => nav(`/links/${l.id}`)} columns={[
        { key: 'u', header: 'Short link', render: (l: any) => <div><code className="text-xs">{l.shortUrl}</code><div className="muted max-w-[260px] truncate text-xs">{l.destinationUrl ?? l.appName}</div></div> },
        { key: 'c', header: 'Clicks', render: (l: any) => `${num(l.clickCount)} (${num(l.uniqueClickCount)} unique)` },
        { key: 'a', header: 'Active', render: (l: any) => <input type="checkbox" aria-label="Active" checked={!!l.isActive} onClick={(e) => e.stopPropagation()} onChange={() => toggle.mutate(l)} /> },
        { key: 'x', header: '', render: (l: any) => <button className="text-red-600" onClick={(e) => { e.stopPropagation(); setRm(l); }}>Delete</button> },
      ]} /></Card>
      <Modal open={open} onClose={() => setOpen(false)} wide title="New short link" footer={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button busy={create.isPending} onClick={() => create.mutate()}>Create</Button></>}>
        <div className="space-y-3">
          <select className="input" aria-label="Type" value={f.type} onChange={(e) => s('type', e.target.value)}><option value="URL">Website link</option><option value="APP">App store link</option></select>
          {f.type === 'URL' ? <div><label className="label" htmlFor="l-d">Destination (https://)</label><input id="l-d" className="input" value={f.destinationUrl} onChange={(e) => s('destinationUrl', e.target.value)} /><Err m={errs.destinationUrl} /></div>
            : <div><label className="label" htmlFor="l-a">App</label><select id="l-a" className="input" value={f.appId} onChange={(e) => s('appId', e.target.value)}><option value="">Choose…</option>{apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select><Err m={errs.appId} /></div>}
          <div className="grid grid-cols-2 gap-2">
            <div><label className="label" htmlFor="l-c">Custom code (optional)</label><input id="l-c" className="input" maxLength={20} value={f.code} onChange={(e) => s('code', e.target.value)} /><Err m={errs.code} /></div>
            <div><label className="label" htmlFor="l-dom">Domain</label><select id="l-dom" className="input" value={f.domainId} onChange={(e) => s('domainId', e.target.value)}><option value="">Default</option>{domains.data?.filter((d) => d.status === 'ACTIVE').map((d) => <option key={d.id} value={d.id}>{d.domain}</option>)}</select></div>
          </div>
          <div className="grid grid-cols-3 gap-2">{['utmSource', 'utmMedium', 'utmCampaign'].map((k) => <input key={k} className="input" aria-label={k} placeholder={k.replace('utm', 'utm_').toLowerCase()} value={f[k]} onChange={(e) => s(k, e.target.value)} />)}</div>
          <div><label className="label" htmlFor="l-e">Expires (optional, IST)</label><input id="l-e" type="datetime-local" className="input" value={f.expiresAt} onChange={(e) => s('expiresAt', e.target.value)} /><Err m={errs.expiresAt} /></div>
        </div>
      </Modal>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this link?" message="Printed QR codes and shared links will stop working." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => { del.mutate(rm.id); setRm(null); }} />
    </>
  );
}

export function LinkDetail() {
  const { id } = useParams();
  const q = useQuery({ queryKey: ['link', id], queryFn: () => api.get(`/api/v1/links/${id}/stats`) });
  const [qr, setQr] = useState<string | null>(null);
  const loadQr = async () => {
    const r = await fetch(`/api/v1/links/${id}/qr.png`, { headers: { Authorization: `Bearer ${getAccessToken()}` } });
    if (r.ok) setQr(URL.createObjectURL(await r.blob())); else toast.error('Could not load the QR code.');
  };
  if (q.isLoading) return <Loading />;
  const d = q.data; const l = d.link;
  const url = `${l.domain ? `https://${l.domain}` : location.origin}/l/${l.code}`;
  return (
    <>
      <BackLink to="/links">Links</BackLink>
      <PageHeader title={<code className="text-lg">{url}</code>} actions={<CopyButton text={url} label="Copy" />} />
      <div className="mb-4 grid grid-cols-2 gap-3"><StatCard label="Clicks" value={num(d.clicks)} /><StatCard label="Unique visitors" value={num(d.uniqueClicks)} /></div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Last 30 days"><DataTable rows={d.byDay.map((r: any, i: number) => ({ ...r, id: i }))} empty="No clicks yet." columns={[{ key: 'd', header: 'Day', render: (r: any) => fmtDate(r.day) }, { key: 'c', header: 'Clicks', render: (r: any) => r.clicks }]} /></Card>
        <Card title="QR code">
          {qr ? <div className="text-center"><img src={qr} alt="QR code" className="mx-auto h-56 w-56" /><a className="btn-primary mt-3 inline-block" href={qr} download={`qr-${l.code}.png`}>Download PNG</a></div> : <Button variant="ghost" onClick={loadQr}>Show QR code</Button>}
          <h3 className="mt-5 text-sm font-bold">Devices</h3>
          <ul className="text-sm">{d.byPlatform.map((p: any) => <li key={p.platform} className="flex justify-between"><span>{p.platform}</span><span>{p.clicks}</span></li>)}</ul>
        </Card>
      </div>
    </>
  );
}

/* ---------------------------- landing pages (P30) ---------------------------- */
const BLOCK_DEFAULTS: Record<string, any> = {
  hero: { type: 'hero', heading: '', subheading: '', imageUrl: '', buttonText: '', buttonUrl: '' }, text: { type: 'text', html: '' }, image: { type: 'image', url: '', alt: '' },
  offer: { type: 'offer', title: '', price: '', details: '' }, countdown: { type: 'countdown', endAt: '', label: 'Offer ends in' }, form: { type: 'form' },
  whatsapp: { type: 'whatsapp', phone: '', text: 'Hi', buttonText: 'Chat on WhatsApp' }, appbadges: { type: 'appbadges', appId: '' }, map: { type: 'map', url: '', label: 'Find us on the map' },
};
const BLOCK_LABEL: Record<string, string> = { hero: 'Banner', text: 'Text', image: 'Image', offer: 'Offer', countdown: 'Countdown', form: 'Lead form', whatsapp: 'WhatsApp button', appbadges: 'App download', map: 'Map link' };

export function PagesList() {
  const nav = useNavigate();
  const list = useQuery({ queryKey: ['pages'], queryFn: () => api.get<any[]>('/api/v1/pages') });
  const [rm, setRm] = useState<any | null>(null);
  const del = useDelete('/api/v1/pages', 'pages', 'Page deleted');
  return (
    <>
      <PageHeader title="Landing pages" subtitle="Simple pages for your offers" actions={<Link to="/pages/new" className="btn-primary">+ New page</Link>} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No landing pages yet." onRowClick={(p: any) => nav(`/pages/${p.id}`)} columns={[
        { key: 't', header: 'Title', render: (p: any) => <b>{p.title}</b> }, { key: 'p', header: 'Path', render: (p: any) => <code className="text-xs">/{p.path}</code> },
        { key: 's', header: 'Status', render: (p: any) => <StatusBadge status={p.status} /> }, { key: 'v', header: 'Views', render: (p: any) => num(p.viewCount) },
        { key: 'x', header: '', render: (p: any) => <button className="text-red-600" onClick={(e) => { e.stopPropagation(); setRm(p); }}>Delete</button> },
      ]} /></Card>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this page?" message="The public link will stop working." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => { del.mutate(rm.id); setRm(null); }} />
    </>
  );
}

export function PageEditor() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const qc = useQueryClient();
  const nav = useNavigate();
  const existing = useQuery({ queryKey: ['page', id], queryFn: () => api.get(`/api/v1/pages/${id}`), enabled: !isNew });
  const forms = useQuery({ queryKey: ['forms'], queryFn: () => api.get<any[]>('/api/v1/forms') });
  const apps = useQuery({ queryKey: ['apps'], queryFn: () => api.get<any[]>('/api/v1/apps') });
  const [p, setP] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const v = p ?? (isNew ? { title: '', path: '', blocks: [BLOCK_DEFAULTS.hero], leadFormId: '', seoTitle: '', seoDescription: '' } : existing.data && { ...existing.data, leadFormId: existing.data.leadFormId ?? '', seoTitle: existing.data.seoTitle ?? '', seoDescription: existing.data.seoDescription ?? '' });
  const body = () => ({ title: v.title, path: v.path, domainId: v.domainId ?? null, blocks: v.blocks, leadFormId: v.leadFormId ? Number(v.leadFormId) : null, seoTitle: v.seoTitle, seoDescription: v.seoDescription });
  const save = useMutation({
    mutationFn: () => (isNew ? api.post('/api/v1/pages', body()) : api.put(`/api/v1/pages/${id}`, body())),
    onSuccess: (r) => { setErrs({}); toast.success('Page saved'); qc.invalidateQueries({ queryKey: ['pages'] }); if (isNew) nav(`/pages/${r.id}`); else qc.invalidateQueries({ queryKey: ['page', id] }); },
    onError: (e: any) => { setErrs(fe(e)); toast.error(errText(e)); },
  });
  const pub = useMutation({
    mutationFn: (on: boolean) => api.post(`/api/v1/pages/${id}/${on ? 'publish' : 'unpublish'}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['page', id] }), onError: (e: any) => { setErrs(fe(e)); toast.error(errText(e)); },
  });
  if (!isNew && existing.isLoading) return <Loading />;
  if (!v) return <Alert tone="red">Page not found.</Alert>;
  const blocks: any[] = v.blocks;
  const setB = (i: number, patch: any) => setP({ ...v, blocks: blocks.map((b, j) => (j === i ? { ...b, ...patch } : b)) });
  const move = (i: number, d: number) => { const n = [...blocks]; const j = i + d; if (j < 0 || j >= n.length) return; [n[i], n[j]] = [n[j], n[i]]; setP({ ...v, blocks: n }); };
  const publicUrl = existing.data?.publicUrl ?? `${location.origin}/p/${existing.data?.workspaceId ?? ''}/${v.path}`;
  const inp = (i: number, k: string, label: string, type = 'text') => (
    <div key={k}><label className="label">{label}</label>
      <input type={type} className="input" value={blocks[i][k] ?? ''} onChange={(e) => setB(i, { [k]: type === 'datetime-local' ? e.target.value : e.target.value })} /></div>
  );
  return (
    <>
      <BackLink to="/pages">Pages</BackLink>
      <PageHeader title={isNew ? 'New landing page' : v.title} subtitle={!isNew && <StatusBadge status={existing.data?.status} />}
        actions={<><Button variant="ghost" busy={save.isPending} onClick={() => save.mutate()}>Save</Button>
          {!isNew && (existing.data?.status === 'PUBLISHED' ? <Button variant="ghost" onClick={() => pub.mutate(false)}>Unpublish</Button> : <Button busy={pub.isPending} onClick={() => pub.mutate(true)}>Publish</Button>)}</>} />
      {existing.data?.status === 'PUBLISHED' && <Alert tone="green" action={<CopyButton text={publicUrl} label="Copy link" />}>Live at <a className="underline" href={publicUrl} target="_blank" rel="noreferrer">{publicUrl}</a></Alert>}
      <div className="mt-4 grid gap-4 lg:grid-cols-[320px_1fr]">
        <Card title="Page settings">
          <label className="label" htmlFor="pg-t">Title</label><input id="pg-t" className="input" value={v.title} onChange={(e) => setP({ ...v, title: e.target.value })} /><Err m={errs.title} />
          <label className="label mt-3" htmlFor="pg-p">Link path</label><input id="pg-p" className="input" value={v.path} onChange={(e) => setP({ ...v, path: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-') })} /><Err m={errs.path} />
          <label className="label mt-3" htmlFor="pg-f">Lead form</label><select id="pg-f" className="input" value={v.leadFormId} onChange={(e) => setP({ ...v, leadFormId: e.target.value })}><option value="">None</option>{forms.data?.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}</select><Err m={errs.leadFormId} />
          <label className="label mt-3" htmlFor="pg-s">Search title</label><input id="pg-s" className="input" maxLength={70} value={v.seoTitle} onChange={(e) => setP({ ...v, seoTitle: e.target.value })} />
          <label className="label mt-3" htmlFor="pg-d">Search description</label><textarea id="pg-d" className="input" rows={2} maxLength={160} value={v.seoDescription} onChange={(e) => setP({ ...v, seoDescription: e.target.value })} />
          <Err m={errs.blocks} />
        </Card>
        <div className="space-y-3">
          {blocks.map((b, i) => (
            <Card key={i} title={<Badge>{BLOCK_LABEL[b.type]}</Badge>} action={<span className="flex gap-2 text-sm"><button onClick={() => move(i, -1)} aria-label="Move up">↑</button><button onClick={() => move(i, 1)} aria-label="Move down">↓</button>
              <button className="text-red-600" onClick={() => setP({ ...v, blocks: blocks.filter((_, j) => j !== i) })}>Remove</button></span>}>
              <div className="grid gap-2 sm:grid-cols-2">
                {b.type === 'hero' && <>{inp(i, 'heading', 'Heading')}{inp(i, 'subheading', 'Sub-heading')}{inp(i, 'imageUrl', 'Image link (https)')}{inp(i, 'buttonText', 'Button text')}{inp(i, 'buttonUrl', 'Button link')}</>}
                {b.type === 'text' && <div className="sm:col-span-2"><label className="label">Text</label><textarea className="input" rows={4} value={b.html} onChange={(e) => setB(i, { html: e.target.value })} /></div>}
                {b.type === 'image' && <>{inp(i, 'url', 'Image link (https)')}{inp(i, 'alt', 'Description')}</>}
                {b.type === 'offer' && <>{inp(i, 'title', 'Offer title')}{inp(i, 'price', 'Price')}<div className="sm:col-span-2">{inp(i, 'details', 'Details')}</div></>}
                {b.type === 'countdown' && <>{inp(i, 'label', 'Label')}<div><label className="label">Ends at (IST)</label><input type="datetime-local" className="input" value={(b.endAt ?? '').slice(0, 16)} onChange={(e) => setB(i, { endAt: istLocalToIso(e.target.value) })} /></div></>}
                {b.type === 'form' && <p className="muted text-sm sm:col-span-2">Shows the lead form chosen in page settings.</p>}
                {b.type === 'whatsapp' && <>{inp(i, 'phone', 'WhatsApp number')}{inp(i, 'buttonText', 'Button text')}<div className="sm:col-span-2">{inp(i, 'text', 'Message customers send')}</div></>}
                {b.type === 'appbadges' && <div><label className="label">App</label><select className="input" value={b.appId} onChange={(e) => setB(i, { appId: Number(e.target.value) })}><option value="">Choose…</option>{apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></div>}
                {b.type === 'map' && <>{inp(i, 'url', 'Google Maps link')}{inp(i, 'label', 'Button text')}</>}
              </div>
            </Card>
          ))}
          {blocks.length < 12 && <div className="flex flex-wrap gap-2">{Object.keys(BLOCK_DEFAULTS).map((t) => <button key={t} className="btn-ghost !py-1 text-sm" onClick={() => setP({ ...v, blocks: [...blocks, { ...BLOCK_DEFAULTS[t] }] })}>+ {BLOCK_LABEL[t]}</button>)}</div>}
        </div>
      </div>
    </>
  );
}

/* ----------------------------- automations (P29) ----------------------------- */
const TRIGGERS: Record<string, string> = { CONTACT_CREATED: 'A new contact is added', TAG_ADDED: 'A tag is added', BIRTHDAY: 'It is a contact\'s birthday', FORM_SUBMITTED: 'A form is submitted', KEYWORD: 'A customer sends a keyword', LEAD_STATUS: 'A lead moves to a status', LINK_CLICKED: 'A link is clicked' };
const STEPS: Record<string, string> = { SEND_TEMPLATE: 'Send a template', WAIT: 'Wait', ADD_TAG: 'Add tag', REMOVE_TAG: 'Remove tag', IF: 'If the customer…', ASSIGN_LEAD: 'Assign lead', NOTIFY: 'Notify my team' };

export function AutomationsList() {
  const qc = useQueryClient();
  const nav = useNavigate();
  const list = useQuery({ queryKey: ['automations'], queryFn: () => api.get<any[]>('/api/v1/automations') });
  const [rm, setRm] = useState<any | null>(null);
  const del = useDelete('/api/v1/automations', 'automations', 'Automation deleted');
  const toggle = useMutation({ mutationFn: (a: any) => api.patch(`/api/v1/automations/${a.id}/status`, { status: a.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE' }), onSuccess: () => qc.invalidateQueries({ queryKey: ['automations'] }), onError: (e) => toast.error(errText(e)) });
  return (
    <>
      <PageHeader title="Automations" subtitle="Send the right message at the right time, automatically" actions={<Link to="/automations/new" className="btn-primary">+ New automation</Link>} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No automations yet. Try a welcome message for new contacts." onRowClick={(a: any) => nav(`/automations/${a.id}`)} columns={[
        { key: 'n', header: 'Name', render: (a: any) => <b>{a.name}</b> }, { key: 't', header: 'Starts when', hideOnMobile: true, render: (a: any) => TRIGGERS[a.triggerType] ?? a.triggerType },
        { key: 's', header: 'Status', render: (a: any) => <StatusBadge status={a.status} /> },
        { key: 'x', header: '', render: (a: any) => <span className="flex gap-3 text-sm"><button className="text-brand" onClick={(e) => { e.stopPropagation(); toggle.mutate(a); }}>{a.status === 'ACTIVE' ? 'Pause' : 'Activate'}</button>
          <button className="text-red-600" onClick={(e) => { e.stopPropagation(); setRm(a); }}>Delete</button></span> },
      ]} /></Card>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this automation?" message="Running flows stop." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => { del.mutate(rm.id); setRm(null); }} />
    </>
  );
}

export function AutomationEditor() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const qc = useQueryClient();
  const nav = useNavigate();
  const tags = useTags();
  const tpls = useQuery({ queryKey: ['tpl-approved'], queryFn: () => api.get<any[]>('/api/v1/templates?status=APPROVED') });
  const forms = useQuery({ queryKey: ['forms'], queryFn: () => api.get<any[]>('/api/v1/forms') });
  const links = useQuery({ queryKey: ['links'], queryFn: () => api.get<any[]>('/api/v1/links') });
  const team = useQuery({ queryKey: ['team'], queryFn: () => api.get('/api/v1/team') });
  const existing = useQuery({ queryKey: ['automation', id], queryFn: () => api.get(`/api/v1/automations/${id}`), enabled: !isNew });
  const runs = useQuery({ queryKey: ['automation-runs', id], queryFn: () => api.get<any[]>(`/api/v1/automations/${id}/runs`), enabled: !isNew });
  const [a, setA] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const v = a ?? (isNew ? { name: '', triggerType: 'CONTACT_CREATED', triggerConfig: {}, steps: [], allowReentryDays: 30 } : existing.data);
  const save = useMutation({
    mutationFn: () => (isNew ? api.post('/api/v1/automations', v) : api.put(`/api/v1/automations/${id}`, v)),
    onSuccess: (r) => { setErrs({}); toast.success('Automation saved'); qc.invalidateQueries({ queryKey: ['automations'] }); if (isNew) nav(`/automations/${r.id}`); },
    onError: (e: any) => { setErrs(fe(e)); toast.error(e.fieldErrors?.[0]?.message ?? errText(e)); },
  });
  if (!isNew && existing.isLoading) return <Loading />;
  if (!v) return <Alert tone="red">Automation not found.</Alert>;
  const cfg = v.triggerConfig ?? {};
  const steps: any[] = v.steps ?? [];
  const setCfg = (patch: any) => setA({ ...v, triggerConfig: { ...cfg, ...patch } });
  const setS = (i: number, patch: any) => setA({ ...v, steps: steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) });
  const addStep = (type: string) => setA({ ...v, steps: [...steps, type === 'WAIT' ? { type, minutes: 60 } : type === 'IF' ? { type, condition: 'REPLIED', elseGoto: -1 } : { type }] });
  const sel = (value: any, onChange: (x: string) => void, opts: { value: any; label: string }[], ph = 'Choose…') => (
    <select className="input" value={value ?? ''} onChange={(e) => onChange(e.target.value)}><option value="">{ph}</option>{opts.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</select>
  );
  const tagOpts = (tags.data ?? []).map((t) => ({ value: t.id, label: t.name }));
  return (
    <>
      <BackLink to="/automations">Automations</BackLink>
      <PageHeader title={isNew ? 'New automation' : v.name} actions={<Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button>} />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="1. When should it start?">
          <label className="label" htmlFor="au-n">Name</label><input id="au-n" className="input" value={v.name} maxLength={80} onChange={(e) => setA({ ...v, name: e.target.value })} /><Err m={errs.name} />
          <label className="label mt-3" htmlFor="au-t">Starts when</label>
          <select id="au-t" className="input" value={v.triggerType} onChange={(e) => setA({ ...v, triggerType: e.target.value, triggerConfig: {} })}>{Object.entries(TRIGGERS).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
          <div className="mt-2">
            {v.triggerType === 'TAG_ADDED' && sel(cfg.tagId, (x) => setCfg({ tagId: Number(x) }), tagOpts, 'Choose a tag')}
            {v.triggerType === 'FORM_SUBMITTED' && sel(cfg.formId, (x) => setCfg({ formId: Number(x) }), (forms.data ?? []).map((f) => ({ value: f.id, label: f.name })), 'Choose a form')}
            {v.triggerType === 'LINK_CLICKED' && sel(cfg.linkId, (x) => setCfg({ linkId: Number(x) }), (links.data ?? []).map((l) => ({ value: l.id, label: l.shortUrl })), 'Choose a link')}
            {v.triggerType === 'LEAD_STATUS' && sel(cfg.status, (x) => setCfg({ status: x }), ['NEW', 'CONTACTED', 'INTERESTED', 'WON', 'LOST'].map((s) => ({ value: s, label: s })), 'Choose a status')}
            {v.triggerType === 'KEYWORD' && <input className="input" placeholder="e.g. offer" maxLength={30} value={cfg.keyword ?? ''} onChange={(e) => setCfg({ keyword: e.target.value })} />}
            <Err m={errs.triggerConfig} />
          </div>
          <label className="label mt-3" htmlFor="au-r">Don't start again for the same contact within (days, 0 = always)</label>
          <input id="au-r" type="number" min={0} max={365} className="input" value={v.allowReentryDays} onChange={(e) => setA({ ...v, allowReentryDays: Number(e.target.value) })} /><Err m={errs.allowReentryDays} />
        </Card>
        <Card title="2. What should happen?">
          {steps.length === 0 && <p className="muted text-sm">Add your first step below.</p>}
          {steps.map((s, i) => (
            <div key={i} className="mb-2 rounded-xl border p-2" style={{ borderColor: 'var(--border)' }}>
              <div className="mb-1 flex items-center justify-between"><b className="text-sm">{i + 1}. {STEPS[s.type]}</b>
                <button className="text-sm text-red-600" onClick={() => setA({ ...v, steps: steps.filter((_, j) => j !== i) })}>Remove</button></div>
              {s.type === 'SEND_TEMPLATE' && <>{sel(s.templateId, (x) => setS(i, { templateId: Number(x), variables: [] }), (tpls.data ?? []).map((t) => ({ value: t.id, label: t.name })), 'Choose a template')}
                {Array.from({ length: tpls.data?.find((t) => t.id === s.templateId)?.variableCount ?? 0 }).map((_, k) => (
                  <select key={k} className="input mt-1" aria-label={`Variable ${k + 1}`} value={s.variables?.[k]?.source ?? ''} onChange={(e) => setS(i, { variables: Array.from({ length: tpls.data!.find((t) => t.id === s.templateId)!.variableCount }, (_, n) => (n === k ? { source: e.target.value } : s.variables?.[n] ?? { source: 'name' })) })}>
                    <option value="">{`{{${k + 1}}} - choose`}</option><option value="name">Customer name</option><option value="phone">Phone</option><option value="city">City</option></select>))}</>}
              {s.type === 'WAIT' && <div className="flex items-center gap-2"><input type="number" min={5} max={43200} className="input !w-28" value={s.minutes} onChange={(e) => setS(i, { minutes: Number(e.target.value) })} /><span className="text-sm">minutes</span></div>}
              {(s.type === 'ADD_TAG' || s.type === 'REMOVE_TAG') && sel(s.tagId, (x) => setS(i, { tagId: Number(x) }), tagOpts, 'Choose a tag')}
              {s.type === 'IF' && <div className="flex gap-2">{sel(s.condition, (x) => setS(i, { condition: x }), [{ value: 'REPLIED', label: 'replied' }, { value: 'CLICKED', label: 'clicked a link' }, { value: 'READ', label: 'read the message' }])}
                <input type="number" className="input !w-40" aria-label="Else go to step" placeholder="else → step" min={i + 2} max={steps.length} value={s.elseGoto >= 0 ? s.elseGoto + 1 : ''} onChange={(e) => setS(i, { elseGoto: e.target.value ? Number(e.target.value) - 1 : -1 })} /></div>}
              {s.type === 'ASSIGN_LEAD' && sel(s.userId, (x) => setS(i, { userId: Number(x) }), (team.data?.members ?? []).filter((m: any) => m.userId).map((m: any) => ({ value: m.userId, label: m.fullName })), 'Choose a person')}
              {s.type === 'NOTIFY' && <input className="input" maxLength={300} placeholder="Message for your team" value={s.message ?? ''} onChange={(e) => setS(i, { message: e.target.value })} />}
            </div>
          ))}
          <Err m={errs.steps} />
          {steps.length < 20 && <div className="mt-2 flex flex-wrap gap-2">{Object.entries(STEPS).map(([k, l]) => <button key={k} className="btn-ghost !py-1 text-sm" onClick={() => addStep(k)}>+ {l}</button>)}</div>}
        </Card>
      </div>
      {!isNew && (
        <Card title="Recent runs" className="mt-4"><DataTable rows={runs.data} loading={runs.isLoading} empty="No runs yet." columns={[
          { key: 'c', header: 'Contact', render: (r: any) => r.fullName ?? `#${r.contactId}` }, { key: 's', header: 'Status', render: (r: any) => <StatusBadge status={r.status} /> },
          { key: 'st', header: 'Step', render: (r: any) => (r.currentStep ?? 0) + 1 }, { key: 'd', header: 'Started', render: (r: any) => fmtDateTime(r.createdAt) },
        ]} /></Card>
      )}
    </>
  );
}
