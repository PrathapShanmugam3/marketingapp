import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, relativeTime } from '../lib/format';
import { errText, useDebounced } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Badge, Button, EmptyState, Loading, PageHeader, StatusBadge } from '../components/ui';
import { useTeam } from './leads';

/** P16 - shared inbox. Two panes on desktop, list -> thread on mobile. Replies are free text inside the 24 h window, template-only outside it. */
export function Inbox() {
  const qc = useQueryClient();
  const { can } = useAuth();
  const [status, setStatus] = useState('OPEN');
  const [unread, setUnread] = useState(false);
  const [search, setSearch] = useState('');
  const q = useDebounced(search.trim().length >= 2 ? search.trim() : '');
  const [sel, setSel] = useState<number | null>(null);
  const list = useQuery({ queryKey: ['inbox', status, unread, q], queryFn: () => api.get<any[]>(`/api/v1/inbox/conversations${qs({ status, unread: unread || undefined, q })}`), refetchInterval: 15_000 });
  const conv = list.data?.find((c) => c.id === sel);
  return (
    <>
      <PageHeader title="Inbox" subtitle="Replies from your customers" />
      <div className="grid gap-4 md:grid-cols-[320px_1fr]">
        <div className={sel ? 'hidden md:block' : ''}>
          <input className="input mb-2" placeholder="Search name or number" aria-label="Search" value={search} onChange={(e) => setSearch(e.target.value)} />
          <div className="mb-2 flex flex-wrap items-center gap-2 text-sm">
            {['OPEN', 'CLOSED'].map((s) => <button key={s} className={status === s ? 'btn-primary !py-1' : 'btn-ghost !py-1'} onClick={() => setStatus(s)}>{s === 'OPEN' ? 'Open' : 'Closed'}</button>)}
            <label className="ml-auto flex items-center gap-1"><input type="checkbox" checked={unread} onChange={(e) => setUnread(e.target.checked)} /> Unread</label>
          </div>
          {list.isLoading ? <Loading /> : (list.data?.length ?? 0) === 0 ? <EmptyState text="No conversations yet. Replies to your campaigns will show up here." /> : (
            <ul className="card divide-y !p-0" style={{ borderColor: 'var(--border)' }}>
              {list.data!.map((c) => (
                <li key={c.id}>
                  <button className={`flex w-full items-start gap-3 p-3 text-left hover:bg-black/5 dark:hover:bg-white/5 ${sel === c.id ? 'bg-black/5 dark:bg-white/10' : ''}`} onClick={() => setSel(c.id)}>
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand/15 font-bold text-brand">{(c.fullName || '?')[0]}</span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center justify-between gap-2"><b className="truncate text-sm">{c.fullName}</b><span className="muted shrink-0 text-[11px]">{relativeTime(c.updatedAt)}</span></span>
                      <span className="muted block truncate text-xs">{c.lastMessagePreview || '—'}</span>
                    </span>
                    {c.unreadCount > 0 && <span className="rounded-full bg-brand px-1.5 text-[11px] font-bold text-white">{c.unreadCount}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className={!sel ? 'hidden md:block' : ''}>
          {sel && conv ? <Thread key={sel} conv={conv} canAssign={can.manage} onBack={() => setSel(null)} onChanged={() => qc.invalidateQueries({ queryKey: ['inbox'] })} />
            : <div className="card muted hidden p-10 text-center md:block">Choose a conversation to read and reply.</div>}
        </div>
      </div>
    </>
  );
}

function Thread({ conv, canAssign, onBack, onChanged }: { conv: any; canAssign: boolean; onBack: () => void; onChanged: () => void }) {
  const [text, setText] = useState('');
  const [attachment, setAttachment] = useState<string | null>(null);
  const [tplId, setTplId] = useState('');
  const [vars, setVars] = useState<string[]>([]);
  const bottom = useRef<HTMLDivElement>(null);
  const team = useTeam();
  const msgs = useQuery({ queryKey: ['inbox-msgs', conv.id], queryFn: () => api.get(`/api/v1/inbox/conversations/${conv.id}/messages`), refetchInterval: 8_000 });
  const tpls = useQuery({ queryKey: ['tpl-approved'], queryFn: () => api.get<any[]>('/api/v1/templates?status=APPROVED') });
  const windowOpen = msgs.data?.windowOpen ?? conv.windowOpen;
  const tpl = tpls.data?.find((t) => String(t.id) === tplId);
  useEffect(() => { if (conv.unreadCount > 0) api.post(`/api/v1/inbox/conversations/${conv.id}/read`).then(onChanged).catch(() => {}); }, [conv.id]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }); }, [msgs.data?.items?.length]);
  useEffect(() => setVars(Array.from({ length: tpl?.variableCount ?? 0 }, () => '')), [tplId]); // eslint-disable-line react-hooks/exhaustive-deps
  const send = useMutation({
    mutationFn: () => api.post(`/api/v1/inbox/conversations/${conv.id}/messages`, windowOpen ? { text, attachmentUrl: attachment ?? undefined } : { templateId: Number(tplId), variables: vars }),
    onSuccess: () => { setText(''); setAttachment(null); setTplId(''); msgs.refetch(); onChanged(); },
    onError: (e) => toast.error(errText(e)),
  });
  const patch = useMutation({
    mutationFn: (b: any) => api.patch(`/api/v1/inbox/conversations/${conv.id}`, b),
    onSuccess: () => { onChanged(); toast.success('Updated'); },
    onError: (e) => toast.error(errText(e)),
  });
  const optedOut = conv.optInStatus === 'OPTED_OUT';
  const canSend = !optedOut && (windowOpen ? (text.trim().length > 0 || !!attachment) && text.length <= 4096 : !!tplId && vars.every((v) => v.trim()));
  return (
    <div className="card flex h-[calc(100vh-11rem)] min-h-[420px] flex-col !p-0">
      <div className="flex flex-wrap items-center gap-2 border-b p-3" style={{ borderColor: 'var(--border)' }}>
        <button className="btn-ghost !p-1 md:hidden" onClick={onBack} aria-label="Back">←</button>
        <div className="min-w-0 flex-1"><b>{conv.fullName}</b><div className="muted text-xs">{conv.phone} {optedOut && <Badge tone="red">Opted out</Badge>}</div></div>
        {canAssign && (
          <select className="input !w-auto !py-1 text-sm" aria-label="Assign" value={conv.assignedTo ?? ''} onChange={(e) => patch.mutate(e.target.value ? { assignedTo: Number(e.target.value) } : { clearAssignee: true })}>
            <option value="">Unassigned</option>{team.data?.map((m: any) => <option key={m.userId} value={m.userId}>{m.fullName}</option>)}
          </select>
        )}
        <StatusBadge status={conv.status} />
        <Button variant="ghost" onClick={() => patch.mutate({ status: conv.status === 'OPEN' ? 'CLOSED' : 'OPEN' })}>{conv.status === 'OPEN' ? 'Close' : 'Reopen'}</Button>
      </div>
      <div className="flex-1 space-y-2 overflow-y-auto p-3">
        {msgs.isLoading ? <Loading /> : msgs.data.items.map((m: any) => (
          <div key={m.id} className={`flex ${m.direction === 'OUT' ? 'justify-end' : ''}`}>
            <div className={`max-w-[80%] rounded-2xl px-3 py-2 text-sm ${m.direction === 'OUT' ? 'bg-brand text-white' : 'bg-black/5 dark:bg-white/10'}`}>
              {m.mediaUrl && /\.(png|jpe?g)$/i.test(m.mediaUrl) && <img src={m.mediaUrl} alt="" className="mb-1 max-h-48 rounded-lg" />}
              {m.mediaUrl && !/\.(png|jpe?g)$/i.test(m.mediaUrl) && <a href={m.mediaUrl} className="underline" target="_blank" rel="noreferrer">Attachment</a>}
              <p className="whitespace-pre-wrap break-words">{m.body}</p>
              <div className="mt-1 text-[10px] opacity-70">{fmtDateTime(m.createdAt)}{m.direction === 'OUT' && ` · ${m.status}`}{m.senderName ? ` · ${m.senderName}` : ''}</div>
              {m.status === 'FAILED' && m.errorMessage && <div className="text-[11px] text-red-200">{m.errorMessage}</div>}
            </div>
          </div>
        ))}
        <div ref={bottom} />
      </div>
      <div className="border-t p-3" style={{ borderColor: 'var(--border)' }}>
        {optedOut ? <Alert tone="red">This customer opted out. You cannot message them.</Alert> : (
          <>
            {!windowOpen && (
              <div className="mb-2 space-y-2">
                <Alert tone="amber">The 24 hour reply window has closed. Send an approved template to start the conversation again.</Alert>
                <select className="input" aria-label="Template" value={tplId} onChange={(e) => setTplId(e.target.value)}>
                  <option value="">Choose a template…</option>{tpls.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                </select>
                {tpl && <p className="muted rounded-lg bg-black/5 p-2 text-xs dark:bg-white/5">{tpl.body}</p>}
                {vars.map((v, i) => <input key={i} className="input" placeholder={`Value for {{${i + 1}}}`} value={v} onChange={(e) => setVars(vars.map((x, j) => (j === i ? e.target.value : x)))} />)}
              </div>
            )}
            {windowOpen && (
              <>
                {attachment && <div className="mb-1 text-xs">Attached: {attachment.split('/').pop()} <button className="text-red-600" onClick={() => setAttachment(null)}>remove</button></div>}
                <textarea className="input" rows={2} maxLength={4096} aria-label="Message" placeholder="Type a reply…" value={text} onChange={(e) => setText(e.target.value)} />
              </>
            )}
            <div className="mt-2 flex items-center gap-2">
              {windowOpen && (
                <label className="btn-ghost cursor-pointer">📎 Attach
                  <input type="file" hidden accept="image/jpeg,image/png,application/pdf" onChange={async (e) => {
                    const f = e.target.files?.[0]; if (!f) return;
                    try { setAttachment((await api.upload(f, f.type === 'application/pdf' ? 'document' : 'image')).url); } catch (er) { toast.error(errText(er)); }
                    e.target.value = '';
                  }} />
                </label>
              )}
              <span className="muted ml-auto text-xs">{windowOpen && msgs.data?.windowClosesAt ? `Window closes ${relativeTime(msgs.data.windowClosesAt)}` : ''}</span>
              <Button busy={send.isPending} disabled={!canSend} onClick={() => send.mutate()}>Send</Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
