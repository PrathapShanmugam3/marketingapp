import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, inr, istLocalToIso, toIstLocalInput } from '../lib/format';
import { errText, useSubmit } from '../lib/hooks';
import { toast } from '../lib/toast';
import { zPhone, zName } from '../lib/validation';
import { Alert, BackLink, Badge, Button, Card, Loading, Modal, PageHeader, StatusBadge } from '../components/ui';
import { PhoneField, SelectField, TextAreaField, TextField } from '../components/fields';

const COLUMNS = ['NEW', 'CONTACTED', 'INTERESTED', 'WON', 'LOST'] as const;
const SOURCES = ['FORM', 'WHATSAPP', 'CALL', 'WALK_IN', 'INSTAGRAM', 'FACEBOOK', 'REFERRAL', 'OTHER'];

export function useTeam() {
  const { can } = useAuth();
  return useQuery({ queryKey: ['team-lite'], queryFn: async () => (can.owner ? (await api.get('/api/v1/team')).members.filter((m: any) => m.userId) : []) });
}

export function LeadsBoard() {
  const qc = useQueryClient();
  const nav = useNavigate();
  const { can } = useAuth();
  const [adding, setAdding] = useState(false);
  const [lost, setLost] = useState<any | null>(null);
  const [lostReason, setLostReason] = useState('');
  const board = useQuery({ queryKey: ['leads'], queryFn: () => api.get('/api/v1/leads') });
  const move = useMutation({
    mutationFn: (v: { id: number; status: string; lostReason?: string }) => api.patch(`/api/v1/leads/${v.id}`, { status: v.status, lostReason: v.lostReason }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['leads'] }); setLost(null); setLostReason(''); },
    onError: (e) => toast.error(errText(e)),
  });
  const items: any[] = board.data?.items ?? [];
  const drop = (status: string, e: React.DragEvent) => {
    e.preventDefault();
    const id = Number(e.dataTransfer.getData('text/plain'));
    const lead = items.find((l) => l.id === id);
    if (!lead || lead.status === status) return;
    if (status === 'LOST') setLost({ ...lead, status });
    else move.mutate({ id, status });
  };
  return (
    <>
      <PageHeader title="Leads" subtitle={can.staffOnly ? 'Showing leads assigned to you.' : undefined} actions={<Button onClick={() => setAdding(true)}>+ New lead</Button>} />
      {board.isLoading ? <Loading /> : (
        <div className="-mx-4 flex gap-3 overflow-x-auto px-4 pb-4 sm:mx-0 sm:px-0">
          {COLUMNS.map((col) => (
            <div key={col} className="w-72 shrink-0 rounded-2xl p-2" style={{ background: 'color-mix(in srgb, var(--border) 40%, transparent)' }} onDragOver={(e) => e.preventDefault()} onDrop={(e) => drop(col, e)}>
              <div className="mb-2 flex items-center justify-between px-2"><b className="text-sm">{col.replace('_', ' ')}</b><Badge>{board.data?.counts?.[col] ?? 0}</Badge></div>
              <div className="flex flex-col gap-2">
                {items.filter((l) => l.status === col).map((l) => (
                  <div key={l.id} draggable onDragStart={(e) => e.dataTransfer.setData('text/plain', String(l.id))} className="card cursor-grab !p-3 active:cursor-grabbing" role="button" tabIndex={0}
                    onClick={() => nav(`/leads/${l.id}`)} onKeyDown={(e) => e.key === 'Enter' && nav(`/leads/${l.id}`)}>
                    <div className="font-semibold">{l.contactName}</div><div className="muted text-xs">{l.contactPhone} · {l.source.replace('_', ' ')}</div>
                    {l.expectedValue && <div className="mt-1 text-xs font-semibold">{inr(l.expectedValue)}</div>}
                    {l.followUpAt && <div className={`mt-1 text-xs ${l.overdue ? 'font-bold text-red-600' : 'muted'}`}>{l.overdue ? 'Overdue · ' : 'Follow up · '}{fmtDateTime(l.followUpAt)}</div>}
                    <select aria-label="Move to" className="input mt-2 !py-1 text-xs" value={l.status} onClick={(e) => e.stopPropagation()}
                      onChange={(e) => (e.target.value === 'LOST' ? setLost({ ...l, status: 'LOST' }) : move.mutate({ id: l.id, status: e.target.value }))}>{COLUMNS.map((c) => <option key={c}>{c}</option>)}</select>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
      <Modal open={!!lost} onClose={() => setLost(null)} title="Why was this lead lost?" footer={<><Button variant="ghost" onClick={() => setLost(null)}>Cancel</Button><Button disabled={!lostReason.trim()} onClick={() => move.mutate({ id: lost.id, status: 'LOST', lostReason })}>Mark as lost</Button></>}>
        <select className="input mb-2" onChange={(e) => setLostReason(e.target.value)} value=""><option value="">Pick a common reason…</option>{['Price too high', 'Chose a competitor', 'No response', 'Not interested', 'Out of area'].map((r) => <option key={r}>{r}</option>)}</select>
        <input className="input" maxLength={255} placeholder="Or write the reason" value={lostReason} onChange={(e) => setLostReason(e.target.value)} />
      </Modal>
      <NewLead open={adding} onClose={() => setAdding(false)} />
    </>
  );
}

const leadSchema = z.object({ mode: z.enum(['new', 'existing']), contactId: z.string().optional(), fullName: z.string().optional(), phone: z.string().optional(), source: z.string().min(1, 'Choose where this lead came from.'),
  requirement: z.string().max(1000, 'Keep it under 1,000 characters.').optional(), expectedValue: z.string().optional(), followUpAt: z.string().optional() });

function NewLead({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const found = useQuery({ queryKey: ['lead-contact-search', search], queryFn: () => api.get(`/api/v1/contacts?q=${encodeURIComponent(search)}&size=25`), enabled: search.trim().length >= 2 });
  const form = useForm<z.infer<typeof leadSchema>>({ resolver: zodResolver(leadSchema), defaultValues: { mode: 'existing', source: 'CALL', requirement: '', expectedValue: '', followUpAt: '', contactId: '' } });
  const mode = form.watch('mode');
  const submit = useSubmit(form, async (v) => {
    const errs: [any, string][] = [];
    if (v.mode === 'existing' && !v.contactId) errs.push(['contactId', 'Choose or add a contact.']);
    if (v.mode === 'new' && !zName.safeParse(v.fullName).success) errs.push(['fullName', 'vr.name.invalid']);
    if (v.mode === 'new' && !zPhone.safeParse(v.phone).success) errs.push(['phone', 'vr.mobile.invalid']);
    if (v.followUpAt && new Date(v.followUpAt + ':00+05:30').getTime() < Date.now() + 5 * 60000) errs.push(['followUpAt', 'Choose a future time.']);
    if (errs.length) { errs.forEach(([f, m]) => form.setError(f, { message: m })); return; }
    await api.post('/api/v1/leads', { contactId: v.mode === 'existing' ? Number(v.contactId) : undefined, newContact: v.mode === 'new' ? { fullName: v.fullName, phone: v.phone, tagIds: [], whatsappConsent: false } : undefined,
      source: v.source, requirement: v.requirement || null, expectedValue: v.expectedValue ? Number(v.expectedValue) : null, followUpAt: istLocalToIso(v.followUpAt ?? '') ?? null });
    qc.invalidateQueries({ queryKey: ['leads'] }); form.reset(); onClose();
  }, { success: 'Lead added.' });
  return (
    <Modal open={open} onClose={onClose} title="New lead">
      <form onSubmit={submit} noValidate>
        <div className="mb-3 flex gap-2"><button type="button" className={mode === 'existing' ? 'btn-primary' : 'btn-ghost'} onClick={() => form.setValue('mode', 'existing')}>Existing contact</button>
          <button type="button" className={mode === 'new' ? 'btn-primary' : 'btn-ghost'} onClick={() => form.setValue('mode', 'new')}>New contact</button></div>
        {mode === 'existing' ? (
          <div className="mb-3"><input className="input" placeholder="Search by name or phone (2+ characters)" value={search} onChange={(e) => setSearch(e.target.value)} />
            <select className="input mt-2" {...form.register('contactId')} size={Math.min(5, (found.data?.items?.length ?? 0) + 1)}><option value="">Choose…</option>{found.data?.items?.map((c: any) => <option key={c.id} value={c.id}>{c.fullName} · {c.phone}</option>)}</select>
            {form.formState.errors.contactId && <p className="err">{form.formState.errors.contactId.message}</p>}</div>
        ) : (<><TextField form={form} name="fullName" label="Name" required /><PhoneField form={form} name="phone" label="Mobile" required /></>)}
        <SelectField form={form} name="source" label="Source" required options={SOURCES.map((s) => ({ value: s, label: s.replace('_', ' ') }))} />
        <TextAreaField form={form} name="requirement" label="Requirement" maxLength={1000} />
        <TextField form={form} name="expectedValue" label="Expected value (₹)" type="number" />
        <TextField form={form} name="followUpAt" label="Follow-up at" type="datetime-local" />
        <Button type="submit" busy={form.formState.isSubmitting}>Add lead</Button>
      </form>
    </Modal>
  );
}

export function LeadDetail() {
  const { id } = useParams();
  const qc = useQueryClient();
  const team = useTeam();
  const { can } = useAuth();
  const [note, setNote] = useState('');
  const [noteErr, setNoteErr] = useState('');
  const q = useQuery({ queryKey: ['lead', id], queryFn: () => api.get(`/api/v1/leads/${id}`) });
  const l = q.data;
  const patch = async (body: any) => { try { await api.patch(`/api/v1/leads/${id}`, body); qc.invalidateQueries({ queryKey: ['lead', id] }); qc.invalidateQueries({ queryKey: ['leads'] }); } catch (e) { toast.error(errText(e)); } };
  if (q.isLoading) return <Loading />;
  if (!l) return null;
  return (
    <>
      <BackLink to="/leads">Leads</BackLink>
      <PageHeader title={l.contactName} subtitle={`${l.contactPhone}${l.contactEmail ? ' · ' + l.contactEmail : ''}`} actions={<StatusBadge status={l.status} />} />
      <div className="grid gap-4 lg:grid-cols-3">
        <Card title="Details" className="lg:col-span-1">
          <dl className="space-y-3 text-sm">
            <div><dt className="muted text-xs">Source</dt><dd>{l.source.replace('_', ' ')}</dd></div>
            <div><dt className="muted text-xs">Requirement</dt><dd>{l.requirement || '—'}</dd></div>
            <div><dt className="muted text-xs">Expected value</dt><dd>{l.expectedValue ? inr(l.expectedValue) : '—'}</dd></div>
            {l.status === 'LOST' && <div><dt className="muted text-xs">Lost reason</dt><dd>{l.lostReason}</dd></div>}
            <div><label className="label">Status</label><select className="input" value={l.status} onChange={(e) => (e.target.value === 'LOST' ? (() => { const r = window.prompt('Why was this lead lost?'); if (r) patch({ status: 'LOST', lostReason: r }); })() : patch({ status: e.target.value }))}>{COLUMNS.map((c) => <option key={c}>{c}</option>)}</select></div>
            {can.owner && <div><label className="label">Assigned to</label><select className="input" value={l.assignedTo ?? ''} onChange={(e) => patch(e.target.value ? { assignedTo: Number(e.target.value) } : { clearAssignee: true })}><option value="">Unassigned</option>{(team.data ?? []).map((m: any) => <option key={m.userId} value={m.userId}>{m.fullName}</option>)}</select></div>}
            <div><label className="label">Follow-up</label><input type="datetime-local" className="input" defaultValue={toIstLocalInput(l.followUpAt)} onBlur={(e) => e.target.value && patch({ followUpAt: istLocalToIso(e.target.value) })} />
              {l.overdue && <p className="err">Follow-up is overdue.</p>}</div>
            <Link to={`/contacts/${l.contactId}`} className="text-brand">Open contact</Link>
          </dl>
        </Card>
        <Card title="Timeline" className="lg:col-span-2">
          <div className="mb-4"><textarea className="input" rows={2} placeholder="Add a note…" maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} aria-invalid={!!noteErr} />
            {noteErr && <p className="err">{noteErr}</p>}
            <Button className="mt-2" onClick={async () => { if (!note.trim()) { setNoteErr('Note cannot be empty.'); return; } setNoteErr(''); try { await api.post(`/api/v1/leads/${id}/notes`, { note }); setNote(''); qc.invalidateQueries({ queryKey: ['lead', id] }); } catch (e) { toast.error(errText(e)); } }}>Add note</Button></div>
          <ol className="space-y-3">{l.activities.map((a: any) => (
            <li key={a.id} className="border-l-2 border-brand pl-3"><div className="text-sm">{a.description}</div><div className="muted text-xs">{a.type.replace('_', ' ')} · {a.author ?? 'System'} · {fmtDateTime(a.createdAt)}</div></li>))}</ol>
        </Card>
      </div>
    </>
  );
}
