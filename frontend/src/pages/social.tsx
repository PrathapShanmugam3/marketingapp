import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, istLocalToIso } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Button, Card, ConfirmDialog, EmptyState, Loading, Modal, PageHeader, StatusBadge } from '../components/ui';
import { AiAssistant } from '../components/AiAssistant';

/** P19 - Facebook / Instagram scheduling. Needs the Pro plan; accounts are connected with a page token (OAuth hand-off) per the SRS. */
export function Social() {
  const qc = useQueryClient();
  const { can, hasFeature } = useAuth();
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const [composer, setComposer] = useState(false);
  const [connect, setConnect] = useState(false);
  const [cancel, setCancel] = useState<any | null>(null);
  const [disc, setDisc] = useState<any | null>(null);
  const [detail, setDetail] = useState<any | null>(null);
  const from = `${month}-01`;
  const next = new Date(`${from}T00:00:00`); next.setMonth(next.getMonth() + 1);
  const to = next.toISOString().slice(0, 10);
  const accounts = useQuery({ queryKey: ['soc-accounts'], queryFn: () => api.get<any[]>('/api/v1/social/accounts'), enabled: hasFeature('social') });
  const posts = useQuery({ queryKey: ['soc-posts', month], queryFn: () => api.get<any[]>(`/api/v1/social/posts${qs({ from, to })}`), enabled: hasFeature('social') });
  const cancelM = useMutation({
    mutationFn: (id: number) => api.post(`/api/v1/social/posts/${id}/cancel`),
    onSuccess: () => { setCancel(null); qc.invalidateQueries({ queryKey: ['soc-posts'] }); toast.success('Post cancelled'); },
    onError: (e) => toast.error(errText(e)),
  });
  const discM = useMutation({
    mutationFn: (id: number) => api.del(`/api/v1/social/accounts/${id}`),
    onSuccess: () => { setDisc(null); qc.invalidateQueries({ queryKey: ['soc-accounts'] }); },
    onError: (e) => toast.error(errText(e)),
  });
  if (!hasFeature('social')) return <><PageHeader title="Social posts" /><Alert tone="blue">Scheduling posts to Facebook and Instagram is part of the Pro plan. Upgrade from Billing to use it.</Alert></>;
  const days = posts.data ? Object.entries(posts.data.reduce((m: Record<string, any[]>, p) => { const d = (p.scheduledAt ?? p.createdAt).slice(0, 10); (m[d] ||= []).push(p); return m; }, {})).sort() : [];
  return (
    <>
      <PageHeader title="Social posts" subtitle="Plan and publish to Facebook and Instagram"
        actions={<><Button variant="ghost" onClick={() => setConnect(true)} disabled={!can.owner}>Connect account</Button><Button onClick={() => setComposer(true)} disabled={!accounts.data?.length}>+ New post</Button></>} />
      <Card title="Connected accounts" className="mb-4">
        {accounts.isLoading ? <Loading /> : accounts.data?.length === 0 ? <EmptyState text="Connect a Facebook page or Instagram account to start posting." /> : (
          <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>{accounts.data!.map((a) => (
            <li key={a.id} className="flex items-center justify-between gap-2 py-2"><span><b>{a.name}</b> <span className="muted text-xs">{a.platform}</span></span>
              <span className="flex items-center gap-2"><StatusBadge status={a.status} />{can.owner && <button className="text-sm text-red-600" onClick={() => setDisc(a)}>Disconnect</button>}</span></li>))}</ul>
        )}
      </Card>
      <div className="mb-3 flex items-center gap-2"><label className="label !mb-0" htmlFor="soc-m">Month</label><input id="soc-m" type="month" className="input !w-auto" value={month} onChange={(e) => setMonth(e.target.value)} /></div>
      {posts.isLoading ? <Loading /> : days.length === 0 ? <EmptyState text="Nothing scheduled this month." /> : days.map(([d, list]) => (
        <Card key={d} title={new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })} className="mb-3">
          {list.map((p: any) => (
            <div key={p.id} className="flex items-center gap-3 border-b py-2 last:border-0" style={{ borderColor: 'var(--border)' }}>
              <button className="min-w-0 flex-1 text-left" onClick={async () => setDetail(await api.get(`/api/v1/social/posts/${p.id}`))}>
                <div className="truncate text-sm">{p.caption}</div><div className="muted text-xs">{p.postType} · {fmtDateTime(p.scheduledAt ?? p.createdAt)}</div></button>
              <StatusBadge status={p.status} />
              {['SCHEDULED', 'PENDING'].includes(p.status) && <button className="text-sm text-red-600" onClick={() => setCancel(p)}>Cancel</button>}
            </div>
          ))}
        </Card>
      ))}
      <Composer open={composer} onClose={() => setComposer(false)} accounts={accounts.data ?? []} onDone={() => { setComposer(false); qc.invalidateQueries({ queryKey: ['soc-posts'] }); }} />
      <ConnectModal open={connect} onClose={() => setConnect(false)} onDone={() => { setConnect(false); qc.invalidateQueries({ queryKey: ['soc-accounts'] }); }} />
      <Modal open={!!detail} onClose={() => setDetail(null)} title="Post details">
        {detail && (<div className="space-y-2 text-sm"><p className="whitespace-pre-wrap">{detail.caption}</p>
          {detail.targets?.map((t: any) => <div key={t.id} className="flex items-center justify-between rounded-lg bg-black/5 p-2 dark:bg-white/5"><span>{t.accountName} · {t.platform}</span><StatusBadge status={t.status} /></div>)}
          {detail.targets?.some((t: any) => t.errorMessage) && <Alert tone="red">{detail.targets.find((t: any) => t.errorMessage).errorMessage}</Alert>}</div>)}
      </Modal>
      <ConfirmDialog open={!!cancel} onClose={() => setCancel(null)} title="Cancel this post?" message="It will not be published." danger confirmLabel="Cancel post" busy={cancelM.isPending} onConfirm={() => cancelM.mutate(cancel.id)} />
      <ConfirmDialog open={!!disc} onClose={() => setDisc(null)} title="Disconnect account?" message="Scheduled posts for this account will not be published." danger confirmLabel="Disconnect" busy={discM.isPending} onConfirm={() => discM.mutate(disc.id)} />
    </>
  );
}

function Composer({ open, onClose, accounts, onDone }: { open: boolean; onClose: () => void; accounts: any[]; onDone: () => void }) {
  const [ids, setIds] = useState<number[]>([]);
  const [caption, setCaption] = useState('');
  const [type, setType] = useState('IMAGE');
  const [media, setMedia] = useState<string[]>([]);
  const [when, setWhen] = useState('');
  const [errs, setErrs] = useState<Record<string, string>>({});
  const hasIg = accounts.some((a) => ids.includes(a.id) && a.platform === 'INSTAGRAM');
  const post = useMutation({
    mutationFn: () => api.post('/api/v1/social/posts', { accountIds: ids, caption, postType: type, media, scheduledAt: when ? istLocalToIso(when) : undefined }),
    onSuccess: () => { toast.success(when ? 'Post scheduled' : 'Publishing now'); setCaption(''); setMedia([]); setWhen(''); onDone(); },
    onError: (e: any) => { setErrs(Object.fromEntries((e.fieldErrors ?? []).map((x: any) => [x.field, x.message]))); toast.error(errText(e)); },
  });
  return (
    <Modal open={open} onClose={onClose} wide title="New post" footer={<><Button variant="ghost" onClick={onClose}>Close</Button>
      <Button busy={post.isPending} disabled={!ids.length || !caption.trim() || (hasIg && !media.length)} onClick={() => post.mutate()}>{when ? 'Schedule' : 'Publish now'}</Button></>}>
      <div className="space-y-3">
        <div className="flex flex-wrap gap-2">{accounts.filter((a) => a.status === 'CONNECTED').map((a) => (
          <label key={a.id} className="flex items-center gap-2 rounded-lg border px-2 py-1 text-sm" style={{ borderColor: 'var(--border)' }}>
            <input type="checkbox" checked={ids.includes(a.id)} onChange={(e) => setIds(e.target.checked ? [...ids, a.id] : ids.filter((x) => x !== a.id))} />{a.name} <span className="muted text-xs">{a.platform}</span></label>))}</div>
        <div><label className="label" htmlFor="sp-cap">Caption</label><textarea id="sp-cap" className="input" rows={4} maxLength={2200} value={caption} onChange={(e) => setCaption(e.target.value)} />
          {errs.caption && <p className="text-sm text-red-600">{errs.caption}</p>}</div>
        <AiAssistant kind="CAPTION" compact onUse={setCaption} />
        <div className="grid grid-cols-2 gap-2">
          <div><label className="label" htmlFor="sp-type">Type</label><select id="sp-type" className="input" value={type} onChange={(e) => setType(e.target.value)}><option value="IMAGE">Image</option><option value="VIDEO">Video / Reel</option></select></div>
          <div><label className="label" htmlFor="sp-when">Schedule (IST, empty = now)</label><input id="sp-when" type="datetime-local" className="input" value={when} onChange={(e) => setWhen(e.target.value)} /></div>
        </div>
        <div><label className="label">Media {hasIg && <span className="muted">(required for Instagram)</span>}</label>
          <div className="flex flex-wrap items-center gap-2">{media.map((m) => <span key={m} className="rounded bg-black/5 px-2 py-1 text-xs dark:bg-white/10">{m.split('/').pop()} <button className="text-red-600" onClick={() => setMedia(media.filter((x) => x !== m))}>×</button></span>)}
            <label className="btn-ghost cursor-pointer">Upload<input type="file" hidden accept={type === 'VIDEO' ? 'video/mp4' : 'image/jpeg,image/png'} onChange={async (e) => {
              const f = e.target.files?.[0]; if (!f) return;
              try { setMedia([...media, (await api.upload(f, type === 'VIDEO' ? 'video' : 'image')).url]); } catch (er) { toast.error(errText(er)); }
              e.target.value = ''; }} /></label></div>
          {errs.media && <p className="text-sm text-red-600">{errs.media}</p>}</div>
        {errs.scheduledAt && <p className="text-sm text-red-600">{errs.scheduledAt}</p>}
      </div>
    </Modal>
  );
}

function ConnectModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const [v, setV] = useState({ platform: 'FACEBOOK', externalId: '', name: '', accessToken: '' });
  const m = useMutation({ mutationFn: () => api.post('/api/v1/social/accounts', v), onSuccess: () => { toast.success('Account connected'); onDone(); }, onError: (e) => toast.error(errText(e)) });
  const set = (k: string, val: string) => setV({ ...v, [k]: val });
  return (
    <Modal open={open} onClose={onClose} title="Connect account" footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={m.isPending} disabled={!v.externalId || !v.name || !v.accessToken} onClick={() => m.mutate()}>Connect</Button></>}>
      <div className="space-y-3">
        <Alert tone="blue">Sign in with Facebook from your Meta Business account and paste the page details here. Tokens are stored encrypted.</Alert>
        <select className="input" aria-label="Platform" value={v.platform} onChange={(e) => set('platform', e.target.value)}><option value="FACEBOOK">Facebook page</option><option value="INSTAGRAM">Instagram business</option></select>
        <input className="input" placeholder="Page / account ID" aria-label="Account id" value={v.externalId} onChange={(e) => set('externalId', e.target.value.trim())} />
        <input className="input" placeholder="Account name" aria-label="Account name" value={v.name} onChange={(e) => set('name', e.target.value)} />
        <input className="input" type="password" placeholder="Access token" aria-label="Access token" autoComplete="off" value={v.accessToken} onChange={(e) => set('accessToken', e.target.value.trim())} />
      </div>
    </Modal>
  );
}
