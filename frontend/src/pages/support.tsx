import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, inr, relativeTime } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Badge, Button, Card, DataTable, Loading, Modal, PageHeader, StatusBadge } from '../components/ui';

const CATEGORIES = [['BILLING', 'Billing and payments'], ['PAYOUT', 'Payout'], ['WHATSAPP', 'WhatsApp'], ['TRACKING', 'Tracking and conversions'], ['ACCOUNT', 'My account'], ['OTHER', 'Something else']];

/** P39 - support tickets for businesses and promoters; admins see all tickets (and can write internal notes). */
export function Tickets({ staff }: { staff?: boolean }) {
  const nav = useNavigate();
  const base = staff ? '/admin/tickets' : location.pathname.startsWith('/earn') ? '/earn/support' : '/support';
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState(false);
  const list = useQuery({ queryKey: ['tickets', status], queryFn: () => api.get<any[]>(`/api/v1/support/tickets${status ? `?status=${status}` : ''}`) });
  return (
    <>
      <PageHeader title="Support" subtitle={staff ? 'All customer tickets' : 'We usually reply within one working day'} actions={!staff && <Button onClick={() => setOpen(true)}>+ New ticket</Button>} />
      <select className="input mb-3 !w-auto" aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All</option>{['OPEN', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'].map((s) => <option key={s} value={s}>{s.replace('_', ' ')}</option>)}</select>
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No tickets." onRowClick={(t: any) => nav(`${base}/${t.id}`)} columns={[
        { key: 's', header: 'Subject', render: (t: any) => <div><b>{t.subject}</b><div className="muted text-xs">{t.category} · #{t.id}</div></div> },
        { key: 'st', header: 'Status', render: (t: any) => <StatusBadge status={t.status} /> }, { key: 'p', header: 'Priority', hideOnMobile: true, render: (t: any) => (t.priority === 'HIGH' ? <Badge tone="red">High</Badge> : t.priority) },
        { key: 'u', header: 'Updated', render: (t: any) => relativeTime(t.updatedAt) },
      ]} /></Card>
      <NewTicket open={open} onClose={() => setOpen(false)} onDone={(id) => { setOpen(false); nav(`${base}/${id}`); }} />
    </>
  );
}

function NewTicket({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (id: number) => void }) {
  const [v, setV] = useState({ category: 'OTHER', subject: '', message: '' });
  const [files, setFiles] = useState<string[]>([]);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const create = useMutation({
    mutationFn: () => api.post('/api/v1/support/tickets', { ...v, attachments: files }),
    onSuccess: (r) => { toast.success('Ticket created'); setV({ category: 'OTHER', subject: '', message: '' }); setFiles([]); onDone(r.id); },
    onError: (e: any) => { setErrs(Object.fromEntries((e.fieldErrors ?? []).map((x: any) => [x.field, x.message]))); toast.error(errText(e)); },
  });
  return (
    <Modal open={open} onClose={onClose} title="New support ticket" footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={create.isPending} disabled={!v.subject || !v.message} onClick={() => create.mutate()}>Submit</Button></>}>
      <div className="space-y-3">
        <select className="input" aria-label="Category" value={v.category} onChange={(e) => setV({ ...v, category: e.target.value })}>{CATEGORIES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
        <div><label className="label" htmlFor="t-s">Subject</label><input id="t-s" className="input" maxLength={120} value={v.subject} onChange={(e) => setV({ ...v, subject: e.target.value })} />{errs.subject && <p className="text-sm text-red-600">{errs.subject}</p>}</div>
        <div><label className="label" htmlFor="t-m">What happened?</label><textarea id="t-m" className="input" rows={5} maxLength={4000} value={v.message} onChange={(e) => setV({ ...v, message: e.target.value })} />{errs.message && <p className="text-sm text-red-600">{errs.message}</p>}</div>
        <div className="flex flex-wrap items-center gap-2">{files.map((f) => <span key={f} className="rounded bg-black/5 px-2 py-1 text-xs dark:bg-white/10">{f.split('/').pop()} <button className="text-red-600" onClick={() => setFiles(files.filter((x) => x !== f))}>×</button></span>)}
          {files.length < 3 && <label className="btn-ghost cursor-pointer">📎 Attach screenshot<input type="file" hidden accept="image/jpeg,image/png,application/pdf" onChange={async (e) => {
            const f = e.target.files?.[0]; if (!f) return; try { setFiles([...files, (await api.upload(f, f.type === 'application/pdf' ? 'document' : 'image')).url]); } catch (er) { toast.error(errText(er)); } e.target.value = ''; }} /></label>}</div>
      </div>
    </Modal>
  );
}

export function TicketThread({ staff }: { staff?: boolean }) {
  const { id } = useParams();
  const qc = useQueryClient();
  const { isAdmin } = useAuth();
  const [body, setBody] = useState('');
  const [internal, setInternal] = useState(false);
  const q = useQuery({ queryKey: ['ticket', id], queryFn: () => api.get(`/api/v1/support/tickets/${id}`), refetchInterval: 20_000 });
  const send = useMutation({
    mutationFn: () => api.post(`/api/v1/support/tickets/${id}/messages`, { body, internal }),
    onSuccess: () => { setBody(''); setInternal(false); qc.invalidateQueries({ queryKey: ['ticket', id] }); }, onError: (e) => toast.error(errText(e)),
  });
  const patch = useMutation({ mutationFn: (b: any) => api.patch(`/api/v1/support/tickets/${id}`, b), onSuccess: () => qc.invalidateQueries({ queryKey: ['ticket', id] }), onError: (e) => toast.error(errText(e)) });
  if (q.isLoading) return <Loading />;
  const t = q.data;
  const closed = t.status === 'CLOSED';
  return (
    <>
      <BackLink to={staff ? '/admin/tickets' : location.pathname.startsWith('/earn') ? '/earn/support' : '/support'}>Tickets</BackLink>
      <PageHeader title={t.subject} subtitle={<span className="flex items-center gap-2"><StatusBadge status={t.status} /><span className="muted text-xs">#{t.id} · {t.category}</span></span>}
        actions={(isAdmin || staff) ? <select className="input !w-auto" aria-label="Status" value={t.status} onChange={(e) => patch.mutate({ status: e.target.value })}>{['OPEN', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'].map((s) => <option key={s}>{s}</option>)}</select>
          : t.status !== 'CLOSED' && <Button variant="ghost" onClick={() => patch.mutate({ status: 'CLOSED' })}>Close ticket</Button>} />
      {t.reference && <Alert tone="blue">Linked payout {t.reference.payoutRef}: {inr(t.reference.amount)} · {t.reference.status}{t.reference.utr ? ` · UTR ${t.reference.utr}` : ''}{t.reference.failureReason ? ` · ${t.reference.failureReason}` : ''}</Alert>}
      <div className="my-4 space-y-3">{t.messages.map((m: any) => {
        const mine = !m.senderAdminRole === !(isAdmin || staff);
        return (
          <div key={m.id} className={`flex ${mine ? 'justify-end' : ''}`}><div className={`max-w-[85%] rounded-2xl px-3 py-2 text-sm ${m.isInternal ? 'bg-amber-100 text-amber-900 dark:bg-amber-900/40 dark:text-amber-100' : mine ? 'bg-brand text-white' : 'bg-black/5 dark:bg-white/10'}`}>
            <div className="mb-1 text-[11px] opacity-80">{m.senderName}{m.senderAdminRole ? ' (support)' : ''}{m.isInternal ? ' · internal note' : ''} · {fmtDateTime(m.createdAt)}</div>
            <p className="whitespace-pre-wrap break-words">{m.body}</p>
            {(Array.isArray(m.attachments) ? m.attachments : []).map((a: string) => <a key={a} href={a} target="_blank" rel="noreferrer" className="mt-1 block underline">Attachment</a>)}</div></div>);
      })}</div>
      {closed ? <Alert tone="blue">This ticket is closed. Create a new ticket if you need more help.</Alert> : (
        <Card><textarea className="input" rows={3} aria-label="Reply" placeholder="Write a reply…" maxLength={4000} value={body} onChange={(e) => setBody(e.target.value)} />
          <div className="mt-2 flex items-center justify-between">{(isAdmin || staff) ? <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={internal} onChange={(e) => setInternal(e.target.checked)} /> Internal note</label> : <span />}
            <Button busy={send.isPending} disabled={!body.trim()} onClick={() => send.mutate()}>Send</Button></div></Card>
      )}
    </>
  );
}
