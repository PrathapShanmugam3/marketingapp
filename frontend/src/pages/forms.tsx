import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { fmtDate } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Button, Card, ConfirmDialog, CopyButton, DataTable, Loading, PageHeader, StatusBadge } from '../components/ui';
import { useTags } from './contacts';

const slugify = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 50);

/** P18 - lead capture forms. */
export function FormsList() {
  const qc = useQueryClient();
  const nav = useNavigate();
  const [del, setDel] = useState<any | null>(null);
  const q = useQuery({ queryKey: ['forms'], queryFn: () => api.get<any[]>('/api/v1/forms') });
  const rm = useMutation({
    mutationFn: (id: number) => api.del(`/api/v1/forms/${id}`),
    onSuccess: () => { setDel(null); qc.invalidateQueries({ queryKey: ['forms'] }); toast.success('Form deleted'); },
    onError: (e) => toast.error(errText(e)),
  });
  return (
    <>
      <PageHeader title="Lead forms" subtitle="Collect enquiries from a link or your website" actions={<Link to="/forms/new" className="btn-primary">+ New form</Link>} />
      <Card>
        <DataTable rows={q.data} loading={q.isLoading} empty="No forms yet. Create one to start collecting leads." onRowClick={(r: any) => nav(`/forms/${r.id}`)} columns={[
          { key: 'n', header: 'Name', render: (r: any) => <b>{r.name}</b> },
          { key: 's', header: 'Link', render: (r: any) => <span className="muted text-xs">/f/{r.slug}</span> },
          { key: 'c', header: 'Submissions', render: (r: any) => r.submissionCount ?? 0 },
          { key: 'a', header: 'Status', render: (r: any) => <StatusBadge status={r.active ? 'ACTIVE' : 'DISABLED'} /> },
          { key: 'x', header: '', render: (r: any) => <button className="text-sm text-red-600" onClick={(e) => { e.stopPropagation(); setDel(r); }}>Delete</button> },
        ]} />
      </Card>
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} title="Delete this form?" message="The public link will stop working. Leads already captured are kept." danger confirmLabel="Delete" busy={rm.isPending} onConfirm={() => rm.mutate(del.id)} />
    </>
  );
}

type Field = { key: string; label: string; type: 'text' | 'number' | 'select' | 'date'; required: boolean; options?: string[] };

export function FormEditor() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const qc = useQueryClient();
  const nav = useNavigate();
  const tags = useTags();
  const tpls = useQuery({ queryKey: ['tpl-approved'], queryFn: () => api.get<any[]>('/api/v1/templates?status=APPROVED') });
  const existing = useQuery({ queryKey: ['form', id], queryFn: () => api.get(`/api/v1/forms/${id}`), enabled: !isNew });
  const [f, setF] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const e = existing.data;
  const v = f ?? (isNew ? { name: '', slug: '', title: '', description: '', fields: [] as Field[], consentText: 'I agree to receive WhatsApp messages from this business.', successMessage: 'Thank you! We will contact you soon.', autoReplyTemplateId: '', defaultTagId: '', active: true }
    : e && { ...e, fields: (e.fields ?? []).filter((x: Field) => !['name', 'phone'].includes(x.key)), autoReplyTemplateId: e.autoReplyTemplateId ?? '', defaultTagId: e.defaultTagId ?? '', description: e.description ?? '', successMessage: e.successMessage ?? '' });
  const save = useMutation({
    mutationFn: () => {
      const body = { ...v, autoReplyTemplateId: v.autoReplyTemplateId ? Number(v.autoReplyTemplateId) : null, defaultTagId: v.defaultTagId ? Number(v.defaultTagId) : null,
        fields: v.fields.map((x: Field) => ({ ...x, options: x.type === 'select' ? x.options : undefined })) };
      return isNew ? api.post('/api/v1/forms', body) : api.put(`/api/v1/forms/${id}`, body);
    },
    onSuccess: (r) => { toast.success('Form saved'); qc.invalidateQueries({ queryKey: ['forms'] }); if (isNew) nav(`/forms/${r.id}`); },
    onError: (er: any) => { setErrs(Object.fromEntries((er.fieldErrors ?? []).map((x: any) => [x.field, x.message]))); toast.error(errText(er)); },
  });
  if (!isNew && existing.isLoading) return <Loading />;
  if (!v) return <Alert tone="red">Form not found.</Alert>;
  const set = (k: string, val: any) => setF({ ...v, [k]: val });
  const setField = (i: number, patch: Partial<Field>) => set('fields', v.fields.map((x: Field, j: number) => (j === i ? { ...x, ...patch } : x)));
  const url = `${location.origin}/f/${v.slug}`;
  const Err = ({ k }: { k: string }) => (errs[k] ? <p className="mt-1 text-sm text-red-600">{errs[k]}</p> : null);
  return (
    <>
      <BackLink to="/forms">Forms</BackLink>
      <PageHeader title={isNew ? 'New form' : v.name} actions={<Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button>} />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Details">
          <label className="label" htmlFor="f-name">Internal name</label>
          <input id="f-name" className="input" value={v.name} maxLength={60} onChange={(ev) => setF({ ...v, name: ev.target.value, slug: isNew && v.slug === slugify(v.name) ? slugify(ev.target.value) : v.slug })} /><Err k="name" />
          <label className="label mt-3" htmlFor="f-slug">Link name</label>
          <input id="f-slug" className="input" value={v.slug} maxLength={50} onChange={(ev) => set('slug', slugify(ev.target.value))} disabled={!isNew} /><Err k="slug" />
          <label className="label mt-3" htmlFor="f-title">Heading shown to customers</label>
          <input id="f-title" className="input" value={v.title} maxLength={100} onChange={(ev) => set('title', ev.target.value)} /><Err k="title" />
          <label className="label mt-3" htmlFor="f-desc">Description (optional)</label>
          <textarea id="f-desc" className="input" rows={2} maxLength={500} value={v.description} onChange={(ev) => set('description', ev.target.value)} />
          <label className="label mt-3" htmlFor="f-cons">Consent text</label>
          <textarea id="f-cons" className="input" rows={2} maxLength={300} value={v.consentText} onChange={(ev) => set('consentText', ev.target.value)} /><Err k="consentText" />
          <label className="label mt-3" htmlFor="f-ok">Thank-you message</label>
          <input id="f-ok" className="input" maxLength={200} value={v.successMessage} onChange={(ev) => set('successMessage', ev.target.value)} />
          <div className="mt-3 grid grid-cols-2 gap-2">
            <div><label className="label" htmlFor="f-tag">Tag new leads</label>
              <select id="f-tag" className="input" value={v.defaultTagId} onChange={(ev) => set('defaultTagId', ev.target.value)}><option value="">None</option>{tags.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></div>
            <div><label className="label" htmlFor="f-tpl">Auto-reply template</label>
              <select id="f-tpl" className="input" value={v.autoReplyTemplateId} onChange={(ev) => set('autoReplyTemplateId', ev.target.value)}><option value="">None</option>{tpls.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></div>
          </div>
          <label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={v.active} onChange={(ev) => set('active', ev.target.checked)} /> Accepting submissions</label>
        </Card>
        <div className="space-y-4">
          <Card title="Fields" action={v.fields.length < 8 && <Button variant="ghost" onClick={() => set('fields', [...v.fields, { key: `field_${Date.now().toString(36)}`, label: '', type: 'text', required: false }])}>+ Add field</Button>}>
            <p className="muted mb-2 text-xs">Name and mobile number are always included.</p>
            {v.fields.length === 0 && <p className="muted text-sm">No extra fields.</p>}
            {v.fields.map((x: Field, i: number) => (
              <div key={x.key} className="mb-2 rounded-xl border p-2" style={{ borderColor: 'var(--border)' }}>
                <div className="flex gap-2">
                  <input className="input" aria-label="Label" placeholder="Label" maxLength={50} value={x.label} onChange={(ev) => setField(i, { label: ev.target.value })} />
                  <select className="input !w-32" aria-label="Type" value={x.type} onChange={(ev) => setField(i, { type: ev.target.value as Field['type'] })}>
                    <option value="text">Text</option><option value="number">Number</option><option value="select">Choice</option><option value="date">Date</option></select>
                </div>
                {x.type === 'select' && <input className="input mt-2" placeholder="Options, comma separated" value={(x.options ?? []).join(', ')} onChange={(ev) => setField(i, { options: ev.target.value.split(',').map((o) => o.trim()).filter(Boolean) })} />}
                <div className="mt-2 flex items-center justify-between text-sm"><label className="flex items-center gap-2"><input type="checkbox" checked={x.required} onChange={(ev) => setField(i, { required: ev.target.checked })} /> Required</label>
                  <button className="text-red-600" onClick={() => set('fields', v.fields.filter((_: Field, j: number) => j !== i))}>Remove</button></div>
              </div>
            ))}
            <Err k="fields" />
          </Card>
          {!isNew && (
            <Card title="Share">
              <div className="flex items-center gap-2"><code className="min-w-0 flex-1 truncate rounded bg-black/5 p-2 text-xs dark:bg-white/10">{url}</code><CopyButton text={url} label="Copy link" /></div>
              <p className="muted mt-3 text-xs">Embed on your website:</p>
              <div className="flex items-start gap-2"><code className="min-w-0 flex-1 break-all rounded bg-black/5 p-2 text-xs dark:bg-white/10">{`<iframe src="${url}" width="100%" height="520" style="border:0"></iframe>`}</code>
                <CopyButton text={`<iframe src="${url}" width="100%" height="520" style="border:0"></iframe>`} label="Copy" /></div>
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
