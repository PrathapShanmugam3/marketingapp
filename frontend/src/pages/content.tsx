import { useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, fmtDateTime, istLocalToIso, num, toIstLocalInput } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Badge, Button, Card, ConfirmDialog, CopyButton, DataTable, Loading, PageHeader, StatusBadge, Tabs } from '../components/ui';
import { Err, fieldErrors } from '../components/common';
import { AiAssistant } from '../components/AiAssistant';

const TYPES: Record<string, string> = { BLOG: 'Blog post', IDEA: 'Idea', SOCIAL: 'Social post', EMAIL: 'E-mail', VIDEO: 'Video' };
const check = (s: string) => (s === 'PASS' ? '✅' : s === 'WARN' ? '⚠️' : '❌');

/** Content calendar: ideas, drafts, scheduled and published pieces, by month. */
export function ContentCalendar() {
  const nav = useNavigate();
  const { hasFeature, workspace } = useAuth();
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const [type, setType] = useState('');
  const list = useQuery({ queryKey: ['content', month, type], queryFn: () => api.get<any[]>(`/api/v1/content?month=${month}${type ? `&type=${type}` : ''}`) });
  const blogUrl = `${location.origin}/b/${workspace?.id}`;
  return (
    <>
      <PageHeader title="Content" subtitle="Plan, write and publish" actions={<><Link to="/seo" className="btn-ghost">SEO tools</Link><Link to="/content/new" className="btn-primary">+ New</Link></>} />
      {!hasFeature('content') && <Alert tone="blue">Content planning and the blog are part of the Growth plan.</Alert>}
      <Alert tone="green" action={<CopyButton text={blogUrl} label="Copy" />}>Your blog: <a className="underline" href={blogUrl} target="_blank" rel="noreferrer">{blogUrl}</a> · sitemap at <code>/b/{workspace?.id}/sitemap.xml</code></Alert>
      <div className="my-3 flex flex-wrap gap-2"><input type="month" className="input !w-auto" aria-label="Month" value={month} onChange={(e) => setMonth(e.target.value)} />
        <select className="input !w-auto" aria-label="Type" value={type} onChange={(e) => setType(e.target.value)}><option value="">All types</option>{Object.entries(TYPES).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></div>
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="Nothing planned for this month." onRowClick={(c: any) => nav(`/content/${c.id}`)} columns={[
        { key: 'd', header: 'Date', render: (c: any) => fmtDate(c.publishAt ?? c.createdAt) }, { key: 't', header: 'Title', render: (c: any) => <b>{c.title}</b> },
        { key: 'y', header: 'Type', hideOnMobile: true, render: (c: any) => TYPES[c.type] }, { key: 's', header: 'Status', render: (c: any) => <StatusBadge status={c.status} /> },
        { key: 'v', header: 'Views', hideOnMobile: true, render: (c: any) => (c.type === 'BLOG' ? num(c.viewCount) : '—') },
      ]} /></Card>
    </>
  );
}

export function ContentEditor() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const qc = useQueryClient();
  const nav = useNavigate();
  const { workspace } = useAuth();
  const ta = useRef<HTMLTextAreaElement>(null);
  const existing = useQuery({ queryKey: ['content-item', id], queryFn: () => api.get(`/api/v1/content/${id}`), enabled: !isNew });
  const seo = useQuery({ queryKey: ['content-seo', id, existing.data?.updatedAt], queryFn: () => api.get(`/api/v1/content/${id}/seo-check`), enabled: !isNew && existing.data?.type === 'BLOG' });
  const [f, setF] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [rm, setRm] = useState(false);
  const e = existing.data;
  const v = f ?? (isNew ? { type: 'BLOG', title: '', slug: '', bodyHtml: '', coverUrl: '', seoTitle: '', seoDescription: '', keywords: '', status: 'DRAFT', publishAt: '' }
    : e && { type: e.type, title: e.title, slug: e.slug ?? '', bodyHtml: e.bodyHtml ?? '', coverUrl: e.coverUrl ?? '', seoTitle: e.seoTitle ?? '', seoDescription: e.seoDescription ?? '', keywords: e.keywords ?? '', status: e.status, publishAt: e.publishAt ? toIstLocalInput(e.publishAt) : '' });
  const set = (k: string, val: any) => setF({ ...v, [k]: val });
  const save = useMutation({
    mutationFn: (status?: string) => { const b = { ...v, status: status ?? v.status, publishAt: v.publishAt ? istLocalToIso(v.publishAt) : null }; return isNew ? api.post('/api/v1/content', b) : api.put(`/api/v1/content/${id}`, b); },
    onSuccess: (r) => { setErrs({}); setF(null); toast.success('Saved'); qc.invalidateQueries({ queryKey: ['content'] }); qc.invalidateQueries({ queryKey: ['content-item', id] }); if (isNew) nav(`/content/${r.id}`, { replace: true }); },
    onError: (er: any) => { setErrs(fieldErrors(er)); toast.error(errText(er)); },
  });
  const del = useMutation({ mutationFn: () => api.del(`/api/v1/content/${id}`), onSuccess: () => { qc.invalidateQueries({ queryKey: ['content'] }); nav('/content'); }, onError: (er) => toast.error(errText(er)) });
  if (!isNew && existing.isLoading) return <Loading />;
  if (!v) return <Alert tone="red">Not found.</Alert>;
  const wrap = (open: string, close: string, ph = 'text') => {
    const el = ta.current; if (!el) return;
    const a = el.selectionStart, b = el.selectionEnd; const sel = v.bodyHtml.slice(a, b) || ph;
    set('bodyHtml', v.bodyHtml.slice(0, a) + open + sel + close + v.bodyHtml.slice(b));
  };
  const blog = v.type === 'BLOG';
  const url = `${location.origin}/b/${workspace?.id}/${v.slug}`;
  return (
    <>
      <BackLink to="/content">Content</BackLink>
      <PageHeader title={isNew ? 'New content' : v.title} subtitle={e && <StatusBadge status={e.status} />}
        actions={<><Button variant="ghost" busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button>{blog && v.status !== 'PUBLISHED' && <Button busy={save.isPending} onClick={() => save.mutate('PUBLISHED')}>Publish now</Button>}{!isNew && <Button variant="ghost" onClick={() => setRm(true)}>Delete</Button>}</>} />
      {e?.status === 'PUBLISHED' && blog && <Alert tone="green" action={<CopyButton text={url} label="Copy link" />}>Live at <a className="underline" href={url} target="_blank" rel="noreferrer">{url}</a></Alert>}
      <div className="mt-4 grid gap-4 lg:grid-cols-[1fr_340px]">
        <Card>
          <div className="grid gap-3 sm:grid-cols-3">
            <div><label className="label" htmlFor="c-ty">Type</label><select id="c-ty" className="input" value={v.type} disabled={!isNew} onChange={(ev) => set('type', ev.target.value)}>{Object.entries(TYPES).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></div>
            <div><label className="label" htmlFor="c-st">Status</label><select id="c-st" className="input" value={v.status} onChange={(ev) => set('status', ev.target.value)}>{['IDEA', 'DRAFT', 'SCHEDULED', 'PUBLISHED'].map((s) => <option key={s}>{s}</option>)}</select></div>
            <div><label className="label" htmlFor="c-at">{v.status === 'SCHEDULED' ? 'Publish at (IST)' : 'Planned date (IST)'}</label><input id="c-at" type="datetime-local" className="input" value={v.publishAt} onChange={(ev) => set('publishAt', ev.target.value)} /><Err m={errs.publishAt} /></div>
          </div>
          <label className="label mt-3" htmlFor="c-ti">Title</label><input id="c-ti" className="input" maxLength={150} value={v.title} onChange={(ev) => set('title', ev.target.value)} /><Err m={errs.title} />
          {blog && <><label className="label mt-3" htmlFor="c-sl">Link</label><div className="flex items-center gap-1 text-sm"><span className="muted">/b/{workspace?.id}/</span><input id="c-sl" className="input" value={v.slug} placeholder="auto from title" onChange={(ev) => set('slug', ev.target.value)} /></div><Err m={errs.slug} /></>}
          <div className="mt-3 flex flex-wrap items-center gap-1"><label className="label !mb-0 mr-2" htmlFor="c-bo">{blog ? 'Article' : 'Notes / draft'}</label>
            {blog && [['<h2>', '</h2>', 'Heading', 'H2'], ['<strong>', '</strong>', 'bold', 'B'], ['<em>', '</em>', 'italic', 'I'], ['<ul><li>', '</li></ul>', 'item', '• List'], ['<a href="https://">', '</a>', 'link text', 'Link']].map(([o, c, ph, l]) => (
              <button type="button" key={l} className="btn-ghost !px-2 !py-0.5 text-xs" onClick={() => wrap(o, c, ph)}>{l}</button>))}</div>
          <textarea id="c-bo" ref={ta} className="input mt-1 font-mono" rows={16} value={v.bodyHtml} onChange={(ev) => set('bodyHtml', ev.target.value)} /><Err m={errs.bodyHtml} />
          <label className="label mt-3" htmlFor="c-co">Cover image</label>
          <div className="flex gap-2"><input id="c-co" className="input" placeholder="https://… or upload" value={v.coverUrl} onChange={(ev) => set('coverUrl', ev.target.value)} />
            <label className="btn-ghost cursor-pointer">Upload<input type="file" hidden accept="image/jpeg,image/png" onChange={async (ev) => { const fl = ev.target.files?.[0]; if (!fl) return; try { set('coverUrl', (await api.upload(fl, 'image')).url); } catch (er) { toast.error(errText(er)); } }} /></label></div><Err m={errs.coverUrl} />
        </Card>
        <div className="space-y-4">
          <Card title="Search (SEO)">
            <label className="label" htmlFor="c-sk">Focus keywords (comma separated)</label><input id="c-sk" className="input" value={v.keywords} onChange={(ev) => set('keywords', ev.target.value)} />
            <label className="label mt-3" htmlFor="c-st2">Search title <span className="muted">{v.seoTitle.length}/70</span></label><input id="c-st2" className="input" maxLength={70} value={v.seoTitle} onChange={(ev) => set('seoTitle', ev.target.value)} placeholder={v.title} /><Err m={errs.seoTitle} />
            <label className="label mt-3" htmlFor="c-sd">Search description <span className="muted">{v.seoDescription.length}/160</span></label><textarea id="c-sd" className="input" rows={3} maxLength={160} value={v.seoDescription} onChange={(ev) => set('seoDescription', ev.target.value)} /><Err m={errs.seoDescription} />
          </Card>
          {seo.data && (
            <Card title={<span>Checklist <Badge tone={seo.data.score >= 80 ? 'green' : seo.data.score >= 50 ? 'amber' : 'red'}>{seo.data.score}/100</Badge></span>}>
              <p className="muted mb-2 text-xs">Save to refresh after editing.</p>
              <ul className="space-y-1 text-sm">{seo.data.checks.map((c: any) => <li key={c.key}>{check(c.status)} {c.label}{c.detail && <span className="muted"> — {c.detail}</span>}</li>)}</ul>
            </Card>
          )}
          <AiAssistant kind="CAPTION" compact onUse={(t) => set('bodyHtml', (v.bodyHtml ? v.bodyHtml + '\n' : '') + `<p>${t.replace(/\n/g, '<br>')}</p>`)} />
        </div>
      </div>
      <ConfirmDialog open={rm} onClose={() => setRm(false)} title="Delete this item?" message="If it is a published post, its link stops working." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => del.mutate()} />
    </>
  );
}

/* ------------------------------- SEO tools ------------------------------- */
export function SeoTools() {
  const qc = useQueryClient();
  const [tab, setTab] = useState('audit');
  const [url, setUrl] = useState('');
  const [result, setResult] = useState<any | null>(null);
  const [err, setErr] = useState('');
  const history = useQuery({ queryKey: ['seo-audits'], queryFn: () => api.get<any[]>('/api/v1/seo/audits') });
  const keywords = useQuery({ queryKey: ['seo-kw'], queryFn: () => api.get<any[]>('/api/v1/seo/keywords'), enabled: tab === 'keywords' });
  const audit = useMutation({ mutationFn: () => api.post('/api/v1/seo/audit', { url }), onSuccess: (r) => { setResult(r); setErr(''); qc.invalidateQueries({ queryKey: ['seo-audits'] }); }, onError: (e: any) => { setErr(e.fieldErrors?.[0]?.message ?? errText(e)); } });
  const [kw, setKw] = useState('');
  const [pos, setPos] = useState<Record<number, string>>({});
  const add = useMutation({ mutationFn: () => api.post('/api/v1/seo/keywords', { keyword: kw }), onSuccess: () => { setKw(''); qc.invalidateQueries({ queryKey: ['seo-kw'] }); }, onError: (e: any) => toast.error(e.fieldErrors?.[0]?.message ?? errText(e)) });
  const rank = useMutation({ mutationFn: (id: number) => api.post(`/api/v1/seo/keywords/${id}/rank`, { position: Number(pos[id]) }), onSuccess: (_, id) => { setPos({ ...pos, [id]: '' }); qc.invalidateQueries({ queryKey: ['seo-kw'] }); }, onError: (e: any) => toast.error(e.fieldErrors?.[0]?.message ?? errText(e)) });
  const del = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/seo/keywords/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ['seo-kw'] }) });
  return (
    <>
      <BackLink to="/content">Content</BackLink>
      <PageHeader title="SEO tools" subtitle="Check a page and track where you rank" />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'audit', label: 'Page check' }, { key: 'keywords', label: 'Keyword rankings' }]} />
      {tab === 'audit' ? (
        <div className="grid gap-4 lg:grid-cols-[1fr_320px]">
          <Card>
            <div className="flex gap-2"><input className="input" aria-label="Page address" placeholder="https://yourshop.in" value={url} onChange={(e) => setUrl(e.target.value)} /><Button busy={audit.isPending} disabled={!url} onClick={() => audit.mutate()}>Check</Button></div>
            <Err m={err} />
            {result && <div className="mt-4"><div className="mb-2 flex items-center gap-3"><b className="text-3xl">{result.score}</b><span className="muted">/ 100 · {result.url}</span></div>
              <ul className="space-y-1 text-sm">{result.checks.map((c: any) => <li key={c.key}>{check(c.status)} {c.label}{c.detail && <span className="muted"> — {c.detail}</span>}</li>)}</ul></div>}
          </Card>
          <Card title="Recent checks"><ul className="space-y-2 text-sm">{history.data?.length === 0 && <li className="muted">None yet.</li>}{history.data?.map((h) => (
            <li key={h.id}><button className="w-full text-left" onClick={async () => setResult(await api.get(`/api/v1/seo/audits/${h.id}`))}><b>{h.score}</b> <span className="muted">{h.url}</span><div className="muted text-[11px]">{fmtDateTime(h.createdAt)}</div></button></li>))}</ul></Card>
        </div>
      ) : (
        <>
          <Alert tone="blue">Search engines do not share rankings by API. Look up your position (or copy it from Google Search Console) and log it here to see the trend.</Alert>
          <div className="my-3 flex gap-2"><input className="input max-w-sm" aria-label="Keyword" placeholder="e.g. silk saree chennai" value={kw} onChange={(e) => setKw(e.target.value)} /><Button busy={add.isPending} disabled={kw.trim().length < 2} onClick={() => add.mutate()}>Track</Button></div>
          <Card><DataTable rows={keywords.data} loading={keywords.isLoading} empty="No keywords tracked." columns={[
            { key: 'k', header: 'Keyword', render: (k: any) => <b>{k.keyword}</b> },
            { key: 'p', header: 'Position', render: (k: any) => (k.position ? <span>#{k.position}{k.previous && k.previous !== k.position && <span className={k.position < k.previous ? 'ml-1 text-emerald-600' : 'ml-1 text-red-600'}>{k.position < k.previous ? '▲' : '▼'}{Math.abs(k.previous - k.position)}</span>}</span> : '—') },
            { key: 'c', header: 'Last checked', hideOnMobile: true, render: (k: any) => (k.checkedOn ? fmtDate(k.checkedOn) : '—') },
            { key: 'l', header: 'Log today', render: (k: any) => <span className="flex gap-1"><input className="input !w-20 !py-1" aria-label="Position" inputMode="numeric" value={pos[k.id] ?? ''} onChange={(e) => setPos({ ...pos, [k.id]: e.target.value.replace(/\D/g, '') })} /><Button variant="ghost" disabled={!pos[k.id]} onClick={() => rank.mutate(k.id)}>Save</Button></span> },
            { key: 'x', header: '', render: (k: any) => <button className="text-red-600" onClick={() => del.mutate(k.id)}>Remove</button> },
          ]} /></Card>
        </>
      )}
    </>
  );
}
