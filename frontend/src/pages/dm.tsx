import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, relativeTime } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Badge, Button, EmptyState, Loading, Modal, PageHeader, StatusBadge } from '../components/ui';

/** Facebook Messenger and Instagram DMs. */
export function SocialDm() {
  const qc = useQueryClient();
  const { hasFeature, can } = useAuth();
  const [status, setStatus] = useState('OPEN');
  const [sel, setSel] = useState<number | null>(null);
  const [sim, setSim] = useState(false);
  const threads = useQuery({ queryKey: ['dm-threads', status], queryFn: () => api.get<any[]>(`/api/v1/social/dm/threads?status=${status}`), refetchInterval: 15_000, enabled: hasFeature('dm') });
  return (
    <>
      <PageHeader title="Social messages" subtitle="Messenger and Instagram DMs" actions={can.manage && <Button variant="ghost" onClick={() => setSim(true)}>Simulate a message</Button>} />
      {!hasFeature('dm') && <Alert tone="blue">Social DMs are part of the Growth plan.</Alert>}
      <Alert tone="amber">Point your Meta app's Messenger/Instagram webhook at <code>/webhooks/social</code> (same verify token as WhatsApp). Meta allows replies within 24 hours of the customer's message.</Alert>
      <div className="mt-4 grid gap-4 md:grid-cols-[320px_1fr]">
        <div className={sel ? 'hidden md:block' : ''}>
          <div className="mb-2 flex gap-2">{['OPEN', 'RESOLVED'].map((s) => <button key={s} className={status === s ? 'btn-primary !py-1' : 'btn-ghost !py-1'} onClick={() => setStatus(s)}>{s === 'OPEN' ? 'Open' : 'Resolved'}</button>)}</div>
          {threads.isLoading ? <Loading /> : (threads.data?.length ?? 0) === 0 ? <EmptyState text="No messages yet." /> : (
            <ul className="card divide-y !p-0" style={{ borderColor: 'var(--border)' }}>{threads.data!.map((t) => (
              <li key={t.id}><button className={`flex w-full items-start gap-3 p-3 text-left hover:bg-black/5 dark:hover:bg-white/5 ${sel === t.id ? 'bg-black/5 dark:bg-white/10' : ''}`} onClick={() => setSel(t.id)}>
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand/15 font-bold text-brand">{(t.displayName || t.platformUserId || '?')[0].toUpperCase()}</span>
                <span className="min-w-0 flex-1"><span className="flex items-center justify-between gap-2"><b className="truncate text-sm">{t.displayName || `User ${t.platformUserId.slice(-4)}`}</b><span className="muted shrink-0 text-[11px]">{relativeTime(t.updatedAt)}</span></span>
                  <span className="muted block truncate text-xs">{t.platform === 'INSTAGRAM' ? '📷' : '💬'} {t.lastMessagePreview}</span></span>
                {t.unreadCount > 0 && <span className="rounded-full bg-brand px-1.5 text-[11px] font-bold text-white">{t.unreadCount}</span>}
              </button></li>))}</ul>
          )}
        </div>
        <div className={!sel ? 'hidden md:block' : ''}>{sel ? <Thread key={sel} id={sel} onBack={() => setSel(null)} onChanged={() => qc.invalidateQueries({ queryKey: ['dm-threads'] })} /> : <div className="card muted hidden p-10 text-center md:block">Choose a conversation.</div>}</div>
      </div>
      <Simulate open={sim} onClose={() => setSim(false)} onDone={() => { setSim(false); qc.invalidateQueries({ queryKey: ['dm-threads'] }); }} />
    </>
  );
}

function Thread({ id, onBack, onChanged }: { id: number; onBack: () => void; onChanged: () => void }) {
  const [text, setText] = useState('');
  const bottom = useRef<HTMLDivElement>(null);
  const q = useQuery({ queryKey: ['dm-thread', id], queryFn: () => api.get(`/api/v1/social/dm/threads/${id}`), refetchInterval: 8_000 });
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }); onChanged(); }, [q.data?.items?.length]); // eslint-disable-line react-hooks/exhaustive-deps
  const send = useMutation({ mutationFn: () => api.post(`/api/v1/social/dm/threads/${id}/messages`, { text }), onSuccess: () => { setText(''); q.refetch(); }, onError: (e) => toast.error(errText(e)) });
  const patch = useMutation({ mutationFn: (status: string) => api.patch(`/api/v1/social/dm/threads/${id}`, { status }), onSuccess: () => { q.refetch(); onChanged(); }, onError: (e) => toast.error(errText(e)) });
  if (q.isLoading) return <Loading />;
  const t = q.data.thread;
  return (
    <div className="card flex h-[calc(100vh-14rem)] min-h-[380px] flex-col !p-0">
      <div className="flex items-center gap-2 border-b p-3" style={{ borderColor: 'var(--border)' }}>
        <button className="btn-ghost !p-1 md:hidden" onClick={onBack} aria-label="Back">←</button>
        <div className="flex-1"><b>{t.displayName || `User ${t.platformUserId.slice(-4)}`}</b> <StatusBadge status={t.status} /></div>
        <Button variant="ghost" onClick={() => patch.mutate(t.status === 'OPEN' ? 'RESOLVED' : 'OPEN')}>{t.status === 'OPEN' ? 'Resolve' : 'Reopen'}</Button>
      </div>
      <div className="flex-1 space-y-2 overflow-y-auto p-3">{q.data.items.map((m: any) => (
        <div key={m.id} className={`flex ${m.direction === 'OUT' ? 'justify-end' : ''}`}><div className={`max-w-[80%] rounded-2xl px-3 py-2 text-sm ${m.direction === 'OUT' ? 'bg-brand text-white' : 'bg-black/5 dark:bg-white/10'}`}>
          <p className="whitespace-pre-wrap break-words">{m.body}</p><div className="mt-1 text-[10px] opacity-70">{fmtDateTime(m.createdAt)}{m.senderName ? ` · ${m.senderName}` : ''}</div></div></div>))}<div ref={bottom} /></div>
      <div className="border-t p-3" style={{ borderColor: 'var(--border)' }}>
        {!q.data.windowOpen ? <Alert tone="amber">The 24-hour reply window has closed. You can reply after the customer messages you again.</Alert> : (
          <div className="flex gap-2"><textarea className="input" rows={2} maxLength={1000} aria-label="Reply" placeholder="Type a reply…" value={text} onChange={(e) => setText(e.target.value)} /><Button busy={send.isPending} disabled={!text.trim()} onClick={() => send.mutate()}>Send</Button></div>
        )}
      </div>
    </div>
  );
}

function Simulate({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const accounts = useQuery({ queryKey: ['soc-accounts'], queryFn: () => api.get<any[]>('/api/v1/social/accounts'), enabled: open });
  const [acct, setAcct] = useState('');
  const [name, setName] = useState('Test Customer');
  const [text, setText] = useState('Hi, what is the price?');
  const a = accounts.data?.find((x) => String(x.id) === acct);
  const go = useMutation({ mutationFn: () => api.post('/api/v1/social/dm/simulate', { platform: a.platform, pageId: a.externalId, senderId: 'sim-' + name.replace(/\W/g, '').toLowerCase(), senderName: name, text }), onSuccess: onDone, onError: (e) => toast.error(errText(e)) });
  return (
    <Modal open={open} onClose={onClose} title="Simulate an incoming message" footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={go.isPending} disabled={!a || !text.trim()} onClick={() => go.mutate()}>Send</Button></>}>
      <p className="muted mb-3 text-sm">Sandbox only: adds a message as if a customer had written to your page.</p>
      <select className="input mb-2" aria-label="Account" value={acct} onChange={(e) => setAcct(e.target.value)}><option value="">Choose an account…</option>{accounts.data?.filter((x) => ['FACEBOOK', 'INSTAGRAM'].includes(x.platform)).map((x) => <option key={x.id} value={x.id}>{x.name} ({x.platform})</option>)}</select>
      <input className="input mb-2" aria-label="Customer name" value={name} onChange={(e) => setName(e.target.value)} /><textarea className="input" rows={2} aria-label="Message" value={text} onChange={(e) => setText(e.target.value)} />
      <Badge>{a ? a.platform : ''}</Badge>
    </Modal>
  );
}
