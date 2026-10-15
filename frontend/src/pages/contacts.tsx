import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useFieldArray } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useTranslation } from 'react-i18next';
import { api, ApiError, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, num } from '../lib/format';
import { errText, useDebounced, useSubmit } from '../lib/hooks';
import { toast } from '../lib/toast';
import { zEmailOpt, zName, zPhone } from '../lib/validation';
import { Alert, BackLink, Badge, Button, Card, ConfirmDialog, DataTable, EmptyState, LoadMore, Loading, Modal, PageHeader, StatusBadge, Tabs } from '../components/ui';
import { CheckField, PhoneField, SelectField, TextField } from '../components/fields';

export function useTags() {
  return useQuery({ queryKey: ['tags'], queryFn: () => api.get<any[]>('/api/v1/tags') });
}

function TagChips({ tags }: { tags: { id: number; name: string; color: string }[] }) {
  return <div className="flex flex-wrap gap-1">{tags.map((t) => <span key={t.id} className="badge text-white" style={{ background: t.color }}>{t.name}</span>)}</div>;
}

export function ContactsList() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const q = useDebounced(search, 300);
  const [optIn, setOptIn] = useState('');
  const [city, setCity] = useState('');
  const [tagIds, setTagIds] = useState<number[]>([]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [size, setSize] = useState(25);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [allMatching, setAllMatching] = useState(false);
  const [confirm, setConfirm] = useState<null | 'delete'>(null);
  const [tagModal, setTagModal] = useState(false);
  const tags = useTags();
  const searchInvalid = search.trim().length === 1;
  const filters = { q: q.trim().length >= 2 ? q.trim() : undefined, optIn, city, tagIds, from, to, size };

  const list = useInfiniteQuery({
    queryKey: ['contacts', filters],
    queryFn: ({ pageParam }) => api.get(`/api/v1/contacts${qs({ ...filters, cursor: pageParam })}`),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last: any) => last.nextCursor ?? undefined,
  });
  const rows: any[] = useMemo(() => list.data?.pages.flatMap((p: any) => p.items) ?? [], [list.data]);
  const total = list.data?.pages[0]?.total ?? 0;
  useEffect(() => { setSelected(new Set()); setAllMatching(false); }, [q, optIn, city, tagIds.join(), from, to, size]);

  const bulk = useMutation({
    mutationFn: (b: any) => api.post('/api/v1/contacts/bulk', b),
    onSuccess: (r: any) => { toast.success(`${num(r.affected)} contacts updated.`); setSelected(new Set()); setAllMatching(false); qc.invalidateQueries({ queryKey: ['contacts'] }); setConfirm(null); setTagModal(false); },
    onError: (e) => toast.error(errText(e)),
  });
  const bulkBody = (action: string, extra: any = {}) => ({ action, ids: allMatching ? undefined : [...selected], allMatching, filter: { q: filters.q, optIn: optIn || undefined, city: city || undefined }, ...extra });
  const count = allMatching ? total : selected.size;
  const toggle = (id: number) => setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  return (
    <>
      <PageHeader title={t('nav.contacts')} subtitle={`${num(total)} contacts`}
        actions={<>
          {can.manage && <Button variant="ghost" onClick={() => api.download(`/api/v1/contacts/export${qs({ q: filters.q, optIn })}`, 'contacts.csv').catch((e) => toast.error(errText(e)))}>Export CSV</Button>}
          {can.manage && <Link to="/contacts/import" className="btn-ghost">Import</Link>}
          {can.manage && <Link to="/contacts/tags" className="btn-ghost">Tags & segments</Link>}
          <Link to="/contacts/new" className="btn-primary">+ {t('common.add')}</Link>
        </>} />
      <Card>
        <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
          <div><input aria-label="Search" className="input" placeholder="Search name, phone, email" value={search} onChange={(e) => setSearch(e.target.value)} maxLength={50} aria-invalid={searchInvalid} />
            {searchInvalid && <p className="err">Type at least 2 characters.</p>}</div>
          <select aria-label="Opt-in status" className="input" value={optIn} onChange={(e) => setOptIn(e.target.value)}><option value="">All opt-in</option><option value="OPTED_IN">Opted in</option><option value="OPTED_OUT">Opted out</option><option value="UNKNOWN">Unknown</option></select>
          <input aria-label="City" className="input" placeholder="City" value={city} onChange={(e) => setCity(e.target.value)} maxLength={60} />
          <select aria-label="Page size" className="input" value={size} onChange={(e) => setSize(Number(e.target.value))}>{[25, 50, 100].map((n) => <option key={n} value={n}>{n} per page</option>)}</select>
          <div className="flex items-center gap-2 sm:col-span-2"><label className="muted text-xs">Added</label><input type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" /><input type="date" className="input" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" /></div>
          <div className="flex flex-wrap gap-1 sm:col-span-2">
            {(tags.data ?? []).map((tg) => {
              const on = tagIds.includes(tg.id);
              return <button key={tg.id} className="badge border" style={{ background: on ? tg.color : 'transparent', color: on ? '#fff' : undefined, borderColor: tg.color }}
                onClick={() => setTagIds((s) => on ? s.filter((x) => x !== tg.id) : s.length < 10 ? [...s, tg.id] : s)}>{tg.name}</button>;
            })}
          </div>
        </div>
        {can.manage && count > 0 && (
          <div className="mb-3 flex flex-wrap items-center gap-2 rounded-xl bg-brand-soft p-3 text-sm text-navy">
            <b>{num(count)} selected</b>
            {!allMatching && selected.size === rows.length && total > rows.length && <button className="font-semibold underline" onClick={() => setAllMatching(true)}>Select all {num(Math.min(total, 10000))} matching</button>}
            {allMatching && total > 10000 && <span className="text-red-700">Select up to 10,000 contacts.</span>}
            <span className="flex-1" />
            <Button variant="ghost" className="!bg-white" onClick={() => setTagModal(true)}>Add tag</Button>
            <Button variant="danger" onClick={() => setConfirm('delete')}>{t('common.delete')}</Button>
          </div>
        )}
        <DataTable<any> loading={list.isLoading} rows={rows} empty={<EmptyState text="No contacts match." action={<Link to="/contacts/new" className="btn-primary">+ Add contact</Link>} />}
          onRowClick={(r) => nav(`/contacts/${r.id}`)}
          columns={[
            ...(can.manage ? [{ key: 'sel', header: '', className: 'w-8', render: (r: any) => <input type="checkbox" aria-label={`Select ${r.fullName}`} checked={allMatching || selected.has(r.id)} onClick={(e) => e.stopPropagation()} onChange={() => toggle(r.id)} /> }] : []),
            { key: 'n', header: t('common.name'), render: (r) => <div><div className="font-semibold">{r.fullName}</div><div className="muted text-xs">{r.city}</div></div> },
            { key: 'p', header: t('common.phone'), render: (r) => r.phone },
            { key: 't', header: 'Tags', hideOnMobile: true, render: (r) => <TagChips tags={r.tags} /> },
            { key: 'o', header: 'Opt-in', render: (r) => <StatusBadge status={r.optInStatus} /> },
            { key: 'c', header: 'Added', render: (r) => fmtDate(r.createdAt) },
          ]} />
        <LoadMore hasMore={!!list.hasNextPage} busy={list.isFetchingNextPage} onClick={() => list.fetchNextPage()} />
      </Card>
      <ConfirmDialog open={confirm === 'delete'} onClose={() => setConfirm(null)} danger busy={bulk.isPending} title="Delete contacts" confirmLabel={t('common.delete')}
        typeWord={count > 50 ? 'DELETE' : undefined} message={`Delete ${num(count)} contact(s)? Contacts in an active campaign cannot be deleted.`}
        onConfirm={() => bulk.mutate(bulkBody('DELETE', { confirm: count > 50 ? 'DELETE' : undefined }))} />
      <Modal open={tagModal} onClose={() => setTagModal(false)} title="Add a tag to selected contacts">
        <div className="flex flex-wrap gap-2">{(tags.data ?? []).map((tg) => <button key={tg.id} className="badge text-white" style={{ background: tg.color }} onClick={() => bulk.mutate(bulkBody('TAG', { tagId: tg.id }))}>{tg.name}</button>)}</div>
        {!tags.data?.length && <p className="muted text-sm">Create a tag first in Tags & segments.</p>}
      </Modal>
    </>
  );
}

const customRow = z.object({ key: z.string().regex(/^[a-z0-9_]{1,30}$/, 'Field names use lowercase letters, numbers and _.'), value: z.string().max(200, 'vr.size') });
const contactSchema = z.object({
  fullName: zName, phone: zPhone, email: zEmailOpt, city: z.string().trim().max(60, 'City is too long.').optional(),
  dateOfBirth: z.string().optional().refine((v) => { if (!v) return true; const d = new Date(v); const age = (Date.now() - d.getTime()) / 31557600000; return d < new Date() && age >= 1 && age <= 120; }, 'Enter a valid date of birth.'),
  language: z.string().optional(), tagIds: z.array(z.number()).max(20, 'A contact can have up to 20 tags.'),
  whatsappConsent: z.boolean(), optInSource: z.string().optional(), custom: z.array(customRow).max(10),
}).refine((v) => !v.whatsappConsent || !!v.optInSource, { path: ['optInSource'], message: 'Choose how consent was collected.' });
type ContactForm = z.infer<typeof contactSchema>;

export function ContactForm() {
  const { id } = useParams();
  const editing = !!id && id !== 'new';
  const nav = useNavigate();
  const qc = useQueryClient();
  const tags = useTags();
  const { workspace } = useAuth();
  const [planLimit, setPlanLimit] = useState(false);
  const [dupId, setDupId] = useState<number | null>(null);
  const existing = useQuery({ queryKey: ['contact', id], queryFn: () => api.get(`/api/v1/contacts/${id}`), enabled: editing });
  const form = useForm<ContactForm>({ resolver: zodResolver(contactSchema), defaultValues: { fullName: '', phone: '', email: '', city: '', dateOfBirth: '', language: '', tagIds: [], whatsappConsent: false, optInSource: '', custom: [] } });
  const custom = useFieldArray({ control: form.control, name: 'custom' });
  useEffect(() => {
    const c = existing.data;
    if (!c) return;
    form.reset({ fullName: c.fullName, phone: c.phone, email: c.email ?? '', city: c.city ?? '', dateOfBirth: c.dateOfBirth ?? '', language: c.language ?? '', tagIds: c.tags.map((x: any) => x.id),
      whatsappConsent: c.optInStatus === 'OPTED_IN', optInSource: c.optInSource ?? '', custom: Object.entries(c.customFields ?? {}).map(([key, value]) => ({ key, value: String(value) })) });
  }, [existing.data, form]);
  const optedOut = existing.data?.optInStatus === 'OPTED_OUT';

  const submit = useSubmit(form, async (v) => {
    setPlanLimit(false); setDupId(null);
    const body = { fullName: v.fullName, phone: v.phone, email: v.email || null, city: v.city || null, dateOfBirth: v.dateOfBirth || null, language: v.language || null, tagIds: v.tagIds,
      whatsappConsent: v.whatsappConsent, optInSource: v.whatsappConsent ? v.optInSource : null, customFields: Object.fromEntries(v.custom.map((c) => [c.key, c.value])) };
    try {
      await (editing ? api.put(`/api/v1/contacts/${id}`, body) : api.post('/api/v1/contacts', body));
      toast.success('Contact saved.');
      qc.invalidateQueries({ queryKey: ['contacts'] });
      nav('/contacts');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'PLAN_LIMIT_REACHED') setPlanLimit(true);
      if (e instanceof ApiError && e.code === 'DUPLICATE' && e.extra.existingId) setDupId(e.extra.existingId);
      throw e;
    }
  });
  const selectedTags: number[] = form.watch('tagIds');
  const consent = form.watch('whatsappConsent');

  if (editing && existing.isLoading) return <Loading />;
  return (
    <>
      <BackLink to="/contacts">Contacts</BackLink>
      <PageHeader title={editing ? 'Edit contact' : 'Add contact'} actions={editing ? <StatusBadge status={existing.data?.optInStatus} /> : undefined} />
      {planLimit && <Alert tone="amber" action={<Link className="btn-primary !py-1.5" to="/settings/billing">Upgrade plan</Link>}>Your plan's contact limit ({num(workspace?.maxContacts)}) is reached.</Alert>}
      {dupId && <Alert tone="amber" action={<Link className="btn-primary !py-1.5" to={`/contacts/${dupId}`}>Open it</Link>}>A contact with this number already exists.</Alert>}
      {optedOut && <Alert tone="red">This customer opted out. They can opt in again only by messaging you or filling a form.</Alert>}
      <Card>
        <form onSubmit={submit} noValidate className="grid gap-x-4 sm:grid-cols-2">
          <TextField form={form} name="fullName" label="Full name" required />
          <PhoneField form={form} name="phone" label="Mobile" required />
          <TextField form={form} name="email" label="Email" type="email" />
          <TextField form={form} name="city" label="City" maxLength={60} />
          <TextField form={form} name="dateOfBirth" label="Date of birth" type="date" />
          <SelectField form={form} name="language" label="Language" placeholder="Not set" options={[{ value: 'en', label: 'English' }, { value: 'ta', label: 'தமிழ்' }]} />
          <div className="sm:col-span-2">
            <label className="label">Tags</label>
            <div className="mb-3 flex flex-wrap gap-1">
              {(tags.data ?? []).map((tg) => { const on = selectedTags.includes(tg.id);
                return <button type="button" key={tg.id} className="badge border" style={{ background: on ? tg.color : 'transparent', color: on ? '#fff' : undefined, borderColor: tg.color }}
                  onClick={() => form.setValue('tagIds', on ? selectedTags.filter((x) => x !== tg.id) : [...selectedTags, tg.id], { shouldDirty: true })}>{tg.name}</button>; })}
              {!tags.data?.length && <span className="muted text-sm">No tags yet. <Link to="/contacts/tags" className="text-brand">Create one</Link></span>}
            </div>
            {form.formState.errors.tagIds && <p className="err">{form.formState.errors.tagIds.message as string}</p>}
          </div>
          <div className="sm:col-span-2"><CheckField form={form} name="whatsappConsent" label="Customer agreed to receive WhatsApp messages" /></div>
          {consent && <SelectField form={form} name="optInSource" label="How was consent collected?" required placeholder="Choose…" options={[{ value: 'MANUAL', label: 'Manually (told us)' }, { value: 'QR', label: 'QR code' }, { value: 'FORM', label: 'Form' }, { value: 'WHATSAPP', label: 'WhatsApp message' }]} />}
          <div className="sm:col-span-2">
            <div className="mb-1 flex items-center justify-between"><label className="label !mb-0">Custom fields</label>
              {custom.fields.length < 10 && <button type="button" className="text-sm font-semibold text-brand" onClick={() => custom.append({ key: '', value: '' })}>+ Add field</button>}</div>
            {custom.fields.map((f, i) => (
              <div key={f.id} className="mb-2 flex items-start gap-2">
                <div className="w-2/5"><input className="input" placeholder="field_name" aria-label="Field name" {...form.register(`custom.${i}.key` as const)} /><p className="err">{form.formState.errors.custom?.[i]?.key?.message}</p></div>
                <div className="flex-1"><input className="input" placeholder="value" aria-label="Value" maxLength={200} {...form.register(`custom.${i}.value` as const)} /></div>
                <button type="button" className="p-2 text-red-600" onClick={() => custom.remove(i)} aria-label="Remove field">×</button>
              </div>
            ))}
          </div>
          <div className="mt-2 flex gap-2 sm:col-span-2"><Button type="submit" busy={form.formState.isSubmitting}>Save contact</Button><Link to="/contacts" className="btn-ghost">Cancel</Link></div>
        </form>
      </Card>
    </>
  );
}

export function ImportContacts() {
  const nav = useNavigate();
  const [step, setStep] = useState(1);
  const [file, setFile] = useState<File | null>(null);
  const [up, setUp] = useState<any>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [country, setCountry] = useState('91');
  const [dup, setDup] = useState('SKIP');
  const [tagId, setTagId] = useState('');
  const [newTag, setNewTag] = useState('');
  const [consent, setConsent] = useState(false);
  const [preview, setPreview] = useState<any[] | null>(null);
  const [job, setJob] = useState<any>(null);
  const [err, setErr] = useState('');
  const tags = useTags();
  const fields = [['fullName', 'Name'], ['phone', 'Mobile number'], ['email', 'Email'], ['city', 'City'], ['dateOfBirth', 'Date of birth'], ['language', 'Language']];
  const used = Object.values(mapping);
  const dupMap = used.length !== new Set(used.filter(Boolean)).size;

  const doUpload = async () => {
    if (!file) return;
    setErr('');
    if (file.size > 5 * 1024 * 1024 || !/\.(csv|xlsx)$/i.test(file.name)) { setErr('Upload a CSV or Excel file under 5 MB.'); return; }
    try {
      const fd = new FormData();
      fd.append('file', file);
      const body = await api.post('/api/v1/contacts/imports', fd);
      setUp(body); setMapping(body.suggestedMapping ?? {}); setStep(2);
    } catch (e) { setErr(e instanceof ApiError && e.fieldErrors[0] ? e.fieldErrors[0].message : errText(e)); }
  };
  const doPreview = async () => {
    if (!Object.values(mapping).includes('phone')) { setErr('Map a column to Mobile number.'); return; }
    if (dupMap) { setErr('Each field can be mapped only once.'); return; }
    setErr('');
    try { const r = await api.post(`/api/v1/contacts/imports/${up.id}/preview`, { mapping, defaultCountryCode: country }); setPreview(r.rows); setStep(3); } catch (e) { setErr(errText(e)); }
  };
  const start = async () => {
    if (!consent) { setErr('Confirm you have consent to message these contacts.'); return; }
    setErr('');
    try {
      const r = await api.post(`/api/v1/contacts/imports/${up.id}/start`, { mapping, defaultCountryCode: country, duplicateMode: dup, tagId: tagId ? Number(tagId) : null, newTagName: newTag || null, consentConfirmed: consent });
      setJob(r); setStep(4);
    } catch (e) { setErr(errText(e)); }
  };
  useEffect(() => {
    if (step !== 4 || !job || ['COMPLETED', 'FAILED'].includes(job.status)) return;
    const t = setInterval(async () => setJob(await api.get(`/api/v1/contacts/imports/${job.id}`)), 2000);
    return () => clearInterval(t);
  }, [step, job]);

  return (
    <>
      <BackLink to="/contacts">Contacts</BackLink>
      <PageHeader title="Import contacts" subtitle={`Step ${step} of 4`} />
      {err && <Alert tone="red">{err}</Alert>}
      <Card>
        {step === 1 && (
          <div>
            <p className="muted mb-3 text-sm">CSV or Excel (.xlsx), up to 5 MB and 10,000 rows. The first row must contain column names.</p>
            <input type="file" accept=".csv,.xlsx" className="input" aria-label="File" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            <Button className="mt-4" disabled={!file} onClick={doUpload}>Upload & continue</Button>
          </div>
        )}
        {step === 2 && up && (
          <div>
            <p className="mb-3 text-sm">{num(up.totalRows)} rows found. Map each column to a contact field.</p>
            <div className="grid gap-3 sm:grid-cols-2">
              {up.headers.map((h: string, i: number) => (
                <div key={i}><label className="label">{h || `Column ${i + 1}`} <span className="muted text-xs font-normal">e.g. {up.preview[0]?.[i]}</span></label>
                  <select className="input" value={mapping[i] ?? ''} onChange={(e) => setMapping((m) => ({ ...m, [i]: e.target.value }))}>
                    <option value="">Do not import</option>{fields.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
              ))}
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div><label className="label">Default country code</label><select className="input" value={country} onChange={(e) => setCountry(e.target.value)}>{[['91', 'India +91'], ['1', 'US +1'], ['44', 'UK +44'], ['971', 'UAE +971'], ['65', 'Singapore +65']].map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
              <div><label className="label">If a number already exists</label><select className="input" value={dup} onChange={(e) => setDup(e.target.value)}><option value="SKIP">Skip it</option><option value="UPDATE">Update the contact</option></select></div>
            </div>
            <Button className="mt-4" onClick={doPreview}>Preview first 10 rows</Button>
          </div>
        )}
        {step === 3 && preview && (
          <div>
            <DataTable<any> rows={preview.map((r) => ({ ...r, id: r.row }))} columns={[
              { key: 'r', header: 'Row', render: (r) => r.row }, { key: 'n', header: 'Name', render: (r) => r.values.fullName ?? '—' }, { key: 'p', header: 'Mobile', render: (r) => r.values.phone ?? '—' },
              { key: 'e', header: 'Result', render: (r) => r.error ? <Badge tone="red">{r.error}</Badge> : <Badge tone="green">OK</Badge> }]} />
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div><label className="label">Add an existing tag</label><select className="input" value={tagId} onChange={(e) => setTagId(e.target.value)}><option value="">None</option>{(tags.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></div>
              <div><label className="label">…or a new tag</label><input className="input" value={newTag} maxLength={40} onChange={(e) => setNewTag(e.target.value)} placeholder="e.g. Diwali list" /></div>
            </div>
            <label className="mt-4 flex items-start gap-2 text-sm"><input type="checkbox" className="mt-1" checked={consent} onChange={(e) => setConsent(e.target.checked)} /><span>These customers agreed to receive WhatsApp messages.</span></label>
            <div className="mt-4 flex gap-2"><Button variant="ghost" onClick={() => setStep(2)}>Back</Button><Button onClick={start}>Start import</Button></div>
          </div>
        )}
        {step === 4 && job && (
          <div>
            <div className="mb-3 flex items-center gap-2"><StatusBadge status={job.status} />{job.status === 'PROCESSING' && <span className="muted text-sm">Importing in the background…</span>}</div>
            <div className="grid grid-cols-3 gap-3 text-center">
              <div className="card"><div className="text-2xl font-bold">{num(job.importedRows)}</div><div className="muted text-xs">Imported</div></div>
              <div className="card"><div className="text-2xl font-bold">{num(job.updatedRows)}</div><div className="muted text-xs">Updated</div></div>
              <div className="card"><div className="text-2xl font-bold">{num(job.failedRows)}</div><div className="muted text-xs">Skipped / failed</div></div>
            </div>
            {job.errorReportUrl && <a className="btn-ghost mt-4" href={job.errorReportUrl} download>Download error report (CSV)</a>}
            {job.status === 'COMPLETED' && <Button className="mt-4 ml-2" onClick={() => nav('/contacts')}>Go to contacts</Button>}
          </div>
        )}
      </Card>
    </>
  );
}

const SWATCHES = ['#12A388', '#0B1A33', '#E8B64B', '#E5484D', '#3B82F6', '#8B5CF6', '#EC4899', '#F97316', '#14B8A6', '#6B7280'];

export function TagsSegments() {
  const [tab, setTab] = useState('tags');
  return (
    <>
      <BackLink to="/contacts">Contacts</BackLink>
      <PageHeader title="Tags & segments" />
      <Tabs tabs={[{ key: 'tags', label: 'Tags' }, { key: 'segments', label: 'Segments' }]} value={tab} onChange={setTab} />
      {tab === 'tags' ? <TagsTab /> : <SegmentsTab />}
    </>
  );
}

function TagsTab() {
  const qc = useQueryClient();
  const tags = useTags();
  const [edit, setEdit] = useState<any | null>(null);
  const [del, setDel] = useState<any | null>(null);
  const schema = z.object({ name: z.string().trim().min(1, 'vr.required').max(40, 'vr.size'), color: z.string().regex(/^#[0-9a-fA-F]{6}$/) });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { name: '', color: SWATCHES[0] } });
  useEffect(() => { if (edit) form.reset({ name: edit.name ?? '', color: edit.color ?? SWATCHES[0] }); }, [edit, form]);
  const submit = useSubmit(form, async (v) => {
    await (edit?.id ? api.put(`/api/v1/tags/${edit.id}`, v) : api.post('/api/v1/tags', v));
    qc.invalidateQueries({ queryKey: ['tags'] }); setEdit(null);
  }, { success: 'Tag saved.' });
  return (
    <Card title="Tags" action={<Button onClick={() => setEdit({})}>+ New tag</Button>}>
      <DataTable<any> rows={tags.data} loading={tags.isLoading} columns={[
        { key: 'n', header: 'Tag', render: (r) => <span className="badge text-white" style={{ background: r.color }}>{r.name}</span> },
        { key: 'c', header: 'Contacts', render: (r) => num(r.contactCount) },
        { key: 'a', header: '', render: (r) => <div className="flex justify-end gap-2"><button className="text-sm text-brand" onClick={() => setEdit(r)}>Edit</button><button className="text-sm text-red-600" onClick={() => setDel(r)}>Delete</button></div> }]} />
      <Modal open={!!edit} onClose={() => setEdit(null)} title={edit?.id ? 'Edit tag' : 'New tag'}>
        <form onSubmit={submit} noValidate>
          <TextField form={form} name="name" label="Tag name" maxLength={40} required />
          <label className="label">Colour</label>
          <div className="mb-4 flex flex-wrap gap-2">{SWATCHES.map((c) => <button type="button" key={c} aria-label={c} className="h-8 w-8 rounded-full border-2" style={{ background: c, borderColor: form.watch('color') === c ? 'var(--fg)' : 'transparent' }} onClick={() => form.setValue('color', c)} />)}</div>
          <Button type="submit" busy={form.formState.isSubmitting}>Save</Button>
        </form>
      </Modal>
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} danger title="Delete tag" message={`Delete the tag "${del?.name}"? It is removed from all contacts.`}
        onConfirm={async () => { try { await api.del(`/api/v1/tags/${del.id}`); qc.invalidateQueries({ queryKey: ['tags'] }); setDel(null); } catch (e) { toast.error(errText(e)); setDel(null); } }} />
    </Card>
  );
}

interface Cond { field: string; op: string; value: string }
const FIELD_OPS: Record<string, { label: string; ops: [string, string][] }> = {
  tag: { label: 'Tag', ops: [['has', 'has'], ['not_has', 'does not have']] },
  city: { label: 'City', ops: [['eq', 'is'], ['contains', 'contains']] },
  optIn: { label: 'Opt-in', ops: [['eq', 'is']] },
  language: { label: 'Language', ops: [['eq', 'is']] },
  createdAt: { label: 'Added', ops: [['after', 'on or after'], ['before', 'before']] },
  birthMonth: { label: 'Birthday month', ops: [['eq', 'is']] },
};

function SegmentsTab() {
  const qc = useQueryClient();
  const tags = useTags();
  const segs = useQuery({ queryKey: ['segments'], queryFn: () => api.get<any[]>('/api/v1/segments') });
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [match, setMatch] = useState('AND');
  const [conds, setConds] = useState<Cond[]>([{ field: 'city', op: 'eq', value: '' }]);
  const [count, setCount] = useState<number | null>(null);
  const [err, setErr] = useState('');
  const filter = useMemo(() => ({ match, conditions: conds.map((c) => ({ field: c.field, op: c.op, value: c.field === 'tag' || c.field === 'birthMonth' ? Number(c.value) : c.value })) }), [match, conds]);
  const debounced = useDebounced(filter, 500);
  useEffect(() => {
    if (!open || conds.some((c) => !c.value)) { setCount(null); return; }
    api.post('/api/v1/segments/preview', debounced).then((r) => setCount(r.count)).catch(() => setCount(null));
  }, [debounced, open, conds]);
  const save = async () => {
    setErr('');
    if (name.trim().length < 3) { setErr('Segment name must be 3 to 60 characters.'); return; }
    if (conds.length === 0) { setErr('Add at least one condition.'); return; }
    try { await api.post('/api/v1/segments', { name, filter }); qc.invalidateQueries({ queryKey: ['segments'] }); setOpen(false); setName(''); } catch (e) { setErr(errText(e)); }
  };
  const valueInput = (c: Cond, i: number) => {
    const set = (v: string) => setConds((a) => a.map((x, j) => (j === i ? { ...x, value: v } : x)));
    if (c.field === 'tag') return <select className="input" value={c.value} onChange={(e) => set(e.target.value)}><option value="">Choose…</option>{(tags.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select>;
    if (c.field === 'optIn') return <select className="input" value={c.value} onChange={(e) => set(e.target.value)}><option value="">Choose…</option>{['OPTED_IN', 'OPTED_OUT', 'UNKNOWN'].map((v) => <option key={v}>{v}</option>)}</select>;
    if (c.field === 'language') return <select className="input" value={c.value} onChange={(e) => set(e.target.value)}><option value="">Choose…</option><option value="en">English</option><option value="ta">தமிழ்</option></select>;
    if (c.field === 'createdAt') return <input type="date" className="input" value={c.value} onChange={(e) => set(e.target.value)} />;
    if (c.field === 'birthMonth') return <select className="input" value={c.value} onChange={(e) => set(e.target.value)}><option value="">Choose…</option>{Array.from({ length: 12 }, (_, k) => <option key={k} value={k + 1}>{new Date(2000, k, 1).toLocaleString('en', { month: 'long' })}</option>)}</select>;
    return <input className="input" value={c.value} maxLength={100} onChange={(e) => set(e.target.value)} />;
  };
  return (
    <Card title="Saved segments" action={<Button onClick={() => setOpen(true)}>+ New segment</Button>}>
      <DataTable<any> rows={segs.data} loading={segs.isLoading} columns={[
        { key: 'n', header: 'Segment', render: (r) => <b>{r.name}</b> }, { key: 'c', header: 'Contacts', render: (r) => num(r.contactCount) },
        { key: 'a', header: '', render: (r) => <button className="text-sm text-red-600" onClick={async () => { try { await api.del(`/api/v1/segments/${r.id}`); qc.invalidateQueries({ queryKey: ['segments'] }); } catch (e) { toast.error(errText(e)); } }}>Delete</button> }]} />
      <Modal open={open} onClose={() => setOpen(false)} title="New segment" wide footer={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button onClick={save}>Save segment</Button></>}>
        {err && <Alert tone="red">{err}</Alert>}
        <label className="label">Name</label><input className="input mb-3" value={name} maxLength={60} onChange={(e) => setName(e.target.value)} />
        <div className="mb-2 flex items-center gap-2 text-sm">Match <select className="input !w-auto" value={match} onChange={(e) => setMatch(e.target.value)}><option value="AND">all conditions</option><option value="OR">any condition</option></select></div>
        {conds.map((c, i) => (
          <div key={i} className="mb-2 grid grid-cols-[1fr_1fr_1.4fr_auto] items-center gap-2">
            <select className="input" value={c.field} onChange={(e) => setConds((a) => a.map((x, j) => (j === i ? { field: e.target.value, op: FIELD_OPS[e.target.value].ops[0][0], value: '' } : x)))}>{Object.entries(FIELD_OPS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}</select>
            <select className="input" value={c.op} onChange={(e) => setConds((a) => a.map((x, j) => (j === i ? { ...x, op: e.target.value } : x)))}>{FIELD_OPS[c.field].ops.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
            {valueInput(c, i)}
            <button className="p-2 text-red-600" aria-label="Remove condition" onClick={() => setConds((a) => a.filter((_, j) => j !== i))}>×</button>
          </div>
        ))}
        {conds.length < 10 && <button className="text-sm font-semibold text-brand" onClick={() => setConds((a) => [...a, { field: 'city', op: 'eq', value: '' }])}>+ Add condition</button>}
        <div className="mt-3 rounded-xl bg-brand-soft p-3 text-sm text-navy">{count === null ? 'Fill in the values to see the live count.' : <><b>{num(count)}</b> contacts match right now.</>}</div>
      </Modal>
    </Card>
  );
}
