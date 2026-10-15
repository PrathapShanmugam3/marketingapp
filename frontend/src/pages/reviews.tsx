import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, getAccessToken } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, fmtDateTime, num } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Button, Card, DataTable, Loading, PageHeader, StatCard, StatusBadge } from '../components/ui';
import { useTags } from './contacts';

/** P17 - Google review requests, QR/poster, rating history. */
export function Reviews() {
  const qc = useQueryClient();
  const { can } = useAuth();
  const settings = useQuery({ queryKey: ['rv-settings'], queryFn: () => api.get('/api/v1/reviews/settings') });
  const stats = useQuery({ queryKey: ['rv-stats'], queryFn: () => api.get('/api/v1/reviews/stats') });
  const reqs = useQuery({ queryKey: ['rv-reqs'], queryFn: () => api.get<any[]>('/api/v1/reviews/requests') });
  const tpls = useQuery({ queryKey: ['tpl-approved'], queryFn: () => api.get<any[]>('/api/v1/templates?status=APPROVED') });
  const tags = useTags();
  const [form, setForm] = useState<any | null>(null);
  const [contactId, setContactId] = useState('');
  const [rating, setRating] = useState('');
  const [count, setCount] = useState('');
  const f = form ?? { googleReviewUrl: settings.data?.googleReviewUrl ?? '', templateId: settings.data?.templateId ?? '', autoSend: !!settings.data?.autoSend, triggerTagId: settings.data?.triggerTagId ?? '', delayMinutes: settings.data?.delayMinutes ?? 60 };
  const set = (k: string, v: any) => setForm({ ...f, [k]: v });
  const [errs, setErrs] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => api.put('/api/v1/reviews/settings', { ...f, templateId: f.templateId ? Number(f.templateId) : null, triggerTagId: f.triggerTagId ? Number(f.triggerTagId) : null, delayMinutes: Number(f.delayMinutes) }),
    onSuccess: () => { toast.success('Saved'); setErrs({}); qc.invalidateQueries({ queryKey: ['rv-settings'] }); },
    onError: (e: any) => { setErrs(Object.fromEntries((e.fieldErrors ?? []).map((x: any) => [x.field, x.message]))); toast.error(errText(e)); },
  });
  const send = useMutation({
    mutationFn: () => api.post('/api/v1/reviews/requests', { contactId: Number(contactId) }),
    onSuccess: () => { toast.success('Review request queued'); setContactId(''); qc.invalidateQueries({ queryKey: ['rv-reqs'] }); },
    onError: (e) => toast.error(errText(e)),
  });
  const manual = useMutation({
    mutationFn: () => api.post('/api/v1/reviews/stats', { rating: Number(rating), reviewCount: Number(count) }),
    onSuccess: () => { toast.success('Rating recorded'); setRating(''); setCount(''); qc.invalidateQueries({ queryKey: ['rv-stats'] }); },
    onError: (e) => toast.error(errText(e)),
  });
  const download = async (path: string, name: string) => {
    const r = await fetch(path, { headers: { Authorization: `Bearer ${getAccessToken()}` } });
    if (!r.ok) return toast.error('Could not download the file.');
    const a = document.createElement('a'); a.href = URL.createObjectURL(await r.blob()); a.download = name; a.click();
  };
  if (settings.isLoading || stats.isLoading) return <Loading />;
  const latest = stats.data?.latest;
  return (
    <>
      <PageHeader title="Reviews" subtitle="Ask happy customers for a Google review" />
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Google rating" value={latest ? `${latest.rating} ★` : '—'} sub={latest ? `${num(latest.reviewCount)} reviews` : 'Not recorded yet'} />
        <StatCard label="Requests sent" value={num(stats.data?.requestsSent)} />
        <StatCard label="Link clicked" value={num(stats.data?.clicked)} />
        <StatCard label="Click rate" value={`${stats.data?.clickRate ?? 0}%`} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Settings">
          <label className="label" htmlFor="rv-url">Google review link</label>
          <input id="rv-url" className="input" value={f.googleReviewUrl} onChange={(e) => set('googleReviewUrl', e.target.value)} placeholder="https://g.page/r/…/review" disabled={!can.manage} />
          {errs.googleReviewUrl && <p className="mt-1 text-sm text-red-600">{errs.googleReviewUrl}</p>}
          <label className="label mt-3" htmlFor="rv-tpl">Template (must contain the review link)</label>
          <select id="rv-tpl" className="input" value={f.templateId} onChange={(e) => set('templateId', e.target.value)} disabled={!can.manage}>
            <option value="">Choose…</option>{tpls.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          {errs.templateId && <p className="mt-1 text-sm text-red-600">{errs.templateId}</p>}
          <label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={f.autoSend} onChange={(e) => set('autoSend', e.target.checked)} disabled={!can.manage} /> Send automatically when a tag is added</label>
          {f.autoSend && (
            <div className="mt-2 grid grid-cols-2 gap-2">
              <select className="input" aria-label="Trigger tag" value={f.triggerTagId} onChange={(e) => set('triggerTagId', e.target.value)}>
                <option value="">Tag…</option>{tags.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
              <input className="input" type="number" min={0} max={10080} aria-label="Delay in minutes" value={f.delayMinutes} onChange={(e) => set('delayMinutes', e.target.value)} />
            </div>
          )}
          {can.manage && <div className="mt-3"><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></div>}
        </Card>
        <Card title="Ask for a review">
          <label className="label" htmlFor="rv-c">Contact ID</label>
          <div className="flex gap-2"><input id="rv-c" className="input" inputMode="numeric" value={contactId} onChange={(e) => setContactId(e.target.value.replace(/\D/g, ''))} placeholder="Open a contact to find its ID" />
            <Button busy={send.isPending} disabled={!contactId || !settings.data?.googleReviewUrl} onClick={() => send.mutate()}>Send</Button></div>
          {!settings.data?.googleReviewUrl && <p className="muted mt-2 text-xs">Save your Google review link first.</p>}
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="ghost" onClick={() => download('/api/v1/reviews/qr.png', 'review-qr.png')}>Download QR</Button>
            <Button variant="ghost" onClick={() => download('/api/v1/reviews/poster.pdf', 'review-poster.pdf')}>Print poster (PDF)</Button>
          </div>
          {can.manage && (
            <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
              <b className="text-sm">Record your current rating</b>
              <div className="mt-2 flex gap-2">
                <input className="input" type="number" step="0.1" min={1} max={5} placeholder="4.6" aria-label="Rating" value={rating} onChange={(e) => setRating(e.target.value)} />
                <input className="input" type="number" min={0} placeholder="Reviews" aria-label="Review count" value={count} onChange={(e) => setCount(e.target.value)} />
                <Button variant="ghost" busy={manual.isPending} disabled={!rating || !count} onClick={() => manual.mutate()}>Save</Button>
              </div>
            </div>
          )}
        </Card>
      </div>
      <Card title="Recent requests" className="mt-4">
        {reqs.data?.length === 0 && <Alert tone="blue">No review requests yet.</Alert>}
        <DataTable rows={reqs.data} loading={reqs.isLoading} columns={[
          { key: 'c', header: 'Customer', render: (r: any) => r.fullName ?? r.contactName ?? `#${r.contactId}` },
          { key: 's', header: 'Status', render: (r: any) => <StatusBadge status={r.status} /> },
          { key: 'k', header: 'Clicked', render: (r: any) => (r.clickedAt ? fmtDateTime(r.clickedAt) : '—') },
          { key: 'd', header: 'Sent', render: (r: any) => fmtDate(r.createdAt) },
        ]} />
      </Card>
      {stats.data?.history?.length > 0 && (
        <Card title="Rating history" className="mt-4">
          <DataTable rows={stats.data.history.map((h: any, i: number) => ({ ...h, id: i }))} columns={[
            { key: 'd', header: 'Date', render: (h: any) => fmtDate(h.capturedOn) }, { key: 'r', header: 'Rating', render: (h: any) => `${h.rating} ★` },
            { key: 'n', header: 'Reviews', render: (h: any) => num(h.reviewCount) }, { key: 's', header: 'Source', render: (h: any) => h.source },
          ]} />
        </Card>
      )}
    </>
  );
}
