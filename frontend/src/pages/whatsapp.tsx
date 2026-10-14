import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { errText, useSubmit } from '../lib/hooks';
import { toast } from '../lib/toast';
import { fmtDateTime } from '../lib/format';
import { isIndianMobile } from '../lib/validation';
import { Alert, BackLink, Button, Card, ConfirmDialog, DataTable, Loading, PageHeader, StatusBadge, Tabs } from '../components/ui';
import { SelectField, TextAreaField, TextField, UploadField } from '../components/fields';

declare global { interface Window { FB?: any; fbAsyncInit?: () => void } }

export function WhatsAppConnect() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['wa-account'], queryFn: () => api.get('/api/v1/whatsapp/account') });
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [waba, setWaba] = useState('');
  const [phoneId, setPhoneId] = useState('');
  const session = useRef<{ waba?: string; phone?: string }>({});
  const acct = q.data?.account;

  useEffect(() => { // Embedded Signup returns waba_id and phone_number_id through a postMessage
    const h = (e: MessageEvent) => {
      if (!/facebook\.com$/.test(new URL(e.origin).hostname)) return;
      try { const d = JSON.parse(e.data); if (d.type === 'WA_EMBEDDED_SIGNUP' && d.event === 'FINISH') { session.current = { waba: d.data.waba_id, phone: d.data.phone_number_id }; } } catch { /* not ours */ }
    };
    window.addEventListener('message', h);
    return () => window.removeEventListener('message', h);
  }, []);

  const connect = async (code: string, w: string, p: string) => {
    setBusy(true);
    try { await api.post('/api/v1/whatsapp/connect', { code, wabaId: w, phoneNumberId: p }); toast.success('WhatsApp connected.'); qc.invalidateQueries({ queryKey: ['wa-account'] }); }
    catch (e) { toast.error(e instanceof ApiError ? e.message : 'Connection was cancelled. Please try again.'); } finally { setBusy(false); }
  };

  const launchSignup = () => {
    const appId = q.data?.metaAppId;
    const init = () => {
      window.FB.init({ appId, autoLogAppEvents: true, xfbml: true, version: 'v21.0' });
      window.FB.login((r: any) => {
        if (r.authResponse?.code) setTimeout(() => connect(r.authResponse.code, session.current.waba ?? '', session.current.phone ?? ''), 800);
        else toast.error('Connection was cancelled. Please try again.');
      }, { response_type: 'code', override_default_response_type: true, extras: { setup: {}, featureType: '', sessionInfoVersion: '3' } });
    };
    if (window.FB) { init(); return; }
    const s = document.createElement('script');
    s.src = 'https://connect.facebook.net/en_US/sdk.js'; s.async = true; s.onload = init;
    document.body.appendChild(s);
  };

  if (q.isLoading) return <Loading />;
  const connected = q.data?.connected;
  return (
    <>
      <PageHeader title="WhatsApp" subtitle="One business number per workspace, connected through Meta Embedded Signup." />
      {acct && acct.status === 'DISCONNECTED' && <Alert tone="red">Your number is disconnected. Scheduled campaigns are paused.</Alert>}
      {acct?.qualityRating === 'RED' && <Alert tone="red">Your number's quality rating is RED. New campaigns are blocked until it recovers.</Alert>}
      <Card title="Connected number">
        {acct ? (
          <dl className="grid gap-3 text-sm sm:grid-cols-2">
            <div><dt className="muted text-xs">Number</dt><dd className="text-lg font-bold">{acct.displayPhone}</dd></div>
            <div><dt className="muted text-xs">Display name</dt><dd>{acct.verifiedName ?? '—'}</dd></div>
            <div><dt className="muted text-xs">Status</dt><dd><StatusBadge status={acct.status} /></dd></div>
            <div><dt className="muted text-xs">Quality rating</dt><dd><StatusBadge status={acct.qualityRating} /></dd></div>
            <div><dt className="muted text-xs">Messaging limit</dt><dd>{acct.messagingLimit ?? '—'}</dd></div>
            <div><dt className="muted text-xs">Connected</dt><dd>{fmtDateTime(acct.connectedAt)}</dd></div>
          </dl>
        ) : <p className="muted text-sm">No number connected yet.</p>}
        <div className="mt-4 flex flex-wrap gap-2">
          {!q.data?.mock && <Button busy={busy} onClick={launchSignup}>{acct ? 'Reconnect' : 'Connect with Meta'}</Button>}
          {connected && <Button variant="danger" onClick={() => setConfirm(true)}>Disconnect</Button>}
        </div>
        {q.data?.mock && (
          <div className="mt-4 rounded-xl border border-dashed p-4" style={{ borderColor: 'var(--border)' }}>
            <b className="text-sm">Sandbox mode</b>
            <p className="muted mb-3 text-sm">No Meta app is configured, so messages are simulated. Enter any ids to connect a sandbox number (set WHATSAPP_MOCK=false and META_APP_ID to go live).</p>
            <div className="grid gap-2 sm:grid-cols-2"><input className="input" placeholder="WABA id" value={waba} onChange={(e) => setWaba(e.target.value)} aria-label="WABA id" /><input className="input" placeholder="Phone number id" value={phoneId} onChange={(e) => setPhoneId(e.target.value)} aria-label="Phone number id" /></div>
            <Button className="mt-3" busy={busy} disabled={!waba || !phoneId} onClick={() => connect('sandbox', waba, phoneId)}>{acct ? 'Reconnect sandbox number' : 'Connect sandbox number'}</Button>
          </div>
        )}
      </Card>
      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} danger typeWord="DISCONNECT" title="Disconnect WhatsApp" confirmLabel="Disconnect" message="Running campaigns are paused and nothing can be sent until you reconnect. Your templates are kept."
        onConfirm={async () => {
          try { await api.del('/api/v1/whatsapp/account', { confirm: 'DISCONNECT' }); toast.success('WhatsApp disconnected.'); } catch (e) { toast.error(errText(e)); }
          setConfirm(false); qc.invalidateQueries({ queryKey: ['wa-account'] });
        }} />
    </>
  );
}

export function TemplatesList() {
  const { can } = useAuth();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [status, setStatus] = useState('');
  const q = useQuery({ queryKey: ['templates', status], queryFn: () => api.get(`/api/v1/templates${status ? `?status=${status}` : ''}`), refetchInterval: 15000 });
  const sync = useMutation({ mutationFn: () => api.post('/api/v1/templates/sync'), onSuccess: () => { toast.success('Status refreshed.'); qc.invalidateQueries({ queryKey: ['templates'] }); } });
  return (
    <>
      <PageHeader title="Templates" subtitle="WhatsApp needs Meta-approved templates for business-initiated messages."
        actions={can.manage && <><Button variant="ghost" busy={sync.isPending} onClick={() => sync.mutate()}>Refresh status</Button><Link to="/templates/new" className="btn-primary">+ New template</Link></>} />
      <Tabs tabs={[['', 'All'], ['APPROVED', 'Approved'], ['PENDING', 'Pending'], ['REJECTED', 'Rejected'], ['DRAFT', 'Drafts']].map(([key, label]) => ({ key, label }))} value={status} onChange={setStatus} />
      <Card>
        <DataTable<any> rows={q.data} loading={q.isLoading} empty="No templates yet. Create your first one." onRowClick={(r) => can.manage && ['DRAFT', 'REJECTED'].includes(r.status) && nav(`/templates/${r.id}`)}
          columns={[
            { key: 'n', header: 'Name', render: (r) => <div><div className="font-mono text-sm font-semibold">{r.name}</div><div className="muted line-clamp-1 max-w-md text-xs">{r.body}</div></div> },
            { key: 'c', header: 'Category', render: (r) => r.category }, { key: 'l', header: 'Lang', render: (r) => r.language },
            { key: 's', header: 'Status', render: (r) => <div><StatusBadge status={r.status} />{r.rejectionReason && <div className="err max-w-xs">{r.rejectionReason}</div>}</div> },
            { key: 'a', header: '', render: (r) => (can.manage && ['DRAFT', 'REJECTED'].includes(r.status) ? <span className="text-sm text-brand">Edit</span> : null) }]} />
      </Card>
    </>
  );
}

const btnSchema = z.object({ type: z.enum(['QUICK_REPLY', 'URL', 'PHONE_NUMBER']), text: z.string().trim().min(1, 'vr.required').max(25, 'Button text can be up to 25 characters.'), url: z.string().optional(), phone: z.string().optional() });
const tplSchema = z.object({
  name: z.string().trim().regex(/^[a-z0-9_]{3,512}$/, 'Use lowercase letters, numbers and _ only.'),
  category: z.enum(['MARKETING', 'UTILITY'], { errorMap: () => ({ message: 'Choose a category.' }) }), language: z.enum(['en', 'ta'], { errorMap: () => ({ message: 'Choose a language.' }) }),
  headerType: z.enum(['NONE', 'TEXT', 'IMAGE', 'VIDEO', 'DOCUMENT']), headerText: z.string().max(60, 'Header text can be up to 60 characters.').optional(), headerMediaUrl: z.string().optional(),
  body: z.string().trim().min(1, 'Body must be 1 to 1,024 characters.').max(1024, 'Body must be 1 to 1,024 characters.'), samples: z.array(z.string().max(100)).optional(),
  footer: z.string().max(60, 'Footer can be up to 60 characters.').optional(), buttons: z.array(btnSchema).max(10, 'A template can have up to 10 buttons.'),
});
type TplForm = z.infer<typeof tplSchema>;

/** Meta's variable rules: {{1}}..{{n}} in order, none at the very start/end, never side by side. */
export function variableProblem(text: string): string | null {
  const re = /\{\{(\d+)\}\}/g;
  let m: RegExpExecArray | null; let expected = 1; let lastEnd = -10;
  while ((m = re.exec(text))) {
    if (Number(m[1]) !== expected) return 'Variables must be numbered {{1}}, {{2}}… in order.';
    if (m.index === 0 || m.index + m[0].length === text.length) return 'A variable cannot be at the very start or end of the text.';
    if (m.index === lastEnd) return 'Two variables cannot be side by side.';
    lastEnd = m.index + m[0].length; expected++;
  }
  return null;
}
const countVars = (s: string) => (s.match(/\{\{\d+\}\}/g) ?? []).length;

export function TemplateBuilder() {
  const { id } = useParams();
  const editing = !!id && id !== 'new';
  const nav = useNavigate();
  const qc = useQueryClient();
  const [submitNow, setSubmitNow] = useState(false);
  const existing = useQuery({ queryKey: ['template', id], queryFn: () => api.get(`/api/v1/templates/${id}`), enabled: editing });
  const form = useForm<TplForm>({ resolver: zodResolver(tplSchema), defaultValues: { name: '', category: undefined as any, language: 'en', headerType: 'NONE', headerText: '', headerMediaUrl: '', body: '', samples: [], footer: '', buttons: [] } });
  const buttons = useFieldArray({ control: form.control, name: 'buttons' });
  const body = form.watch('body') ?? '';
  const nVars = countVars(body);
  const w = form.watch();
  useEffect(() => {
    const t = existing.data; if (!t) return;
    form.reset({ name: t.name, category: t.category, language: t.language, headerType: t.headerType, headerText: t.headerText ?? '', headerMediaUrl: t.headerMediaUrl ?? '', body: t.body, samples: t.sampleValuesJson ?? [], footer: t.footer ?? '',
      buttons: (t.buttonsJson ?? []).map((b: any) => ({ type: b.type, text: b.text, url: b.url ?? '', phone: b.phone_number ?? '' })) });
  }, [existing.data, form]);

  const insertVar = () => form.setValue('body', `${body}{{${nVars + 1}}}`, { shouldDirty: true });
  const submit = useSubmit(form, async (v) => {
    const errs: [any, string][] = [];
    const bp = variableProblem(v.body); if (bp) errs.push(['body', bp]);
    const hp = v.headerType === 'TEXT' ? (v.headerText ? variableProblem(v.headerText) : 'Header text can be up to 60 characters.') : null; if (hp) errs.push(['headerText', hp]);
    if (v.headerType === 'TEXT' && countVars(v.headerText ?? '') > 1) errs.push(['headerText', 'Header text can have at most 1 variable.']);
    if (['IMAGE', 'VIDEO', 'DOCUMENT'].includes(v.headerType) && !v.headerMediaUrl) errs.push(['headerMediaUrl', 'Upload the header file.']);
    for (let i = 0; i < nVars; i++) if (!(v.samples?.[i] ?? '').trim()) { errs.push(['samples', 'Add a sample for each variable.']); break; }
    if (v.footer && /\{\{\d+\}\}/.test(v.footer)) errs.push(['footer', 'Footer can be up to 60 characters, with no variables.']);
    v.buttons.forEach((b, i) => {
      if (b.type === 'URL' && !/^https:\/\/[^\s]+$/.test(b.url ?? '')) errs.push([`buttons.${i}.url`, 'vr.url.invalid']);
      if (b.type === 'PHONE_NUMBER' && !isIndianMobile(b.phone ?? '')) errs.push([`buttons.${i}.phone`, 'vr.mobile.invalid']);
    });
    if (v.buttons.filter((b) => b.type === 'URL').length > 2) errs.push(['buttons', 'At most 2 URL buttons.']);
    if (v.buttons.filter((b) => b.type === 'PHONE_NUMBER').length > 1) errs.push(['buttons', 'At most 1 phone button.']);
    if (errs.length) { errs.forEach(([f, m]) => form.setError(f, { message: m })); return; }
    const payload = { name: v.name, category: v.category, language: v.language, headerType: v.headerType, headerText: v.headerType === 'TEXT' ? v.headerText : null, headerMediaUrl: v.headerMediaUrl || null,
      body: v.body, sampleValues: (v.samples ?? []).slice(0, nVars), footer: v.footer || null,
      buttons: v.buttons.map((b) => ({ type: b.type, text: b.text, ...(b.type === 'URL' ? { url: b.url } : {}), ...(b.type === 'PHONE_NUMBER' ? { phone_number: b.phone } : {}) })), submit: submitNow };
    await (editing ? api.put(`/api/v1/templates/${id}`, payload) : api.post('/api/v1/templates', payload));
    qc.invalidateQueries({ queryKey: ['templates'] });
    toast.success(submitNow ? 'Submitted to Meta for approval.' : 'Draft saved.');
    nav('/templates');
  });

  const preview = useMemo(() => {
    let b = body; (w.samples ?? []).forEach((s, i) => { b = b.replace(`{{${i + 1}}}`, s || `{{${i + 1}}}`); });
    return b;
  }, [body, w.samples]);
  const hasOptOut = w.buttons.some((b) => /stop|unsubscribe|opt.?out/i.test(b.text));
  const footerShown = w.category === 'MARKETING' && !hasOptOut && !(w.footer ?? '').toLowerCase().includes('stop') ? ((w.footer ? w.footer + ' | ' : '') + 'Reply STOP to unsubscribe').slice(0, 60) : w.footer;

  if (editing && existing.isLoading) return <Loading />;
  return (
    <>
      <BackLink to="/templates">Templates</BackLink>
      <PageHeader title={editing ? 'Edit template' : 'New template'} subtitle="Meta reviews every template. Marketing templates get an opt-out line automatically." />
      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <form onSubmit={submit} noValidate>
            <TextField form={form} name="name" label="Template name" hint="Lowercase letters, numbers and _ only (e.g. diwali_offer)" required />
            <div className="grid gap-x-3 sm:grid-cols-2">
              <SelectField form={form} name="category" label="Category" required placeholder="Choose…" options={[{ value: 'MARKETING', label: 'Marketing' }, { value: 'UTILITY', label: 'Utility' }]} />
              <SelectField form={form} name="language" label="Language" required options={[{ value: 'en', label: 'English' }, { value: 'ta', label: 'தமிழ் (Tamil)' }]} />
            </div>
            <SelectField form={form} name="headerType" label="Header" options={[{ value: 'NONE', label: 'None' }, { value: 'TEXT', label: 'Text' }, { value: 'IMAGE', label: 'Image' }, { value: 'VIDEO', label: 'Video (MP4, up to 16 MB)' }, { value: 'DOCUMENT', label: 'Document (PDF)' }]} />
            {w.headerType === 'TEXT' && <TextField form={form} name="headerText" label="Header text" maxLength={60} />}
            {['IMAGE', 'VIDEO', 'DOCUMENT'].includes(w.headerType) && <UploadField form={form} name="headerMediaUrl" label="Header sample file" kind="header" accept={w.headerType === 'IMAGE' ? 'image/jpeg,image/png' : w.headerType === 'VIDEO' ? 'video/mp4' : 'application/pdf'} />}
            <TextAreaField form={form} name="body" label="Body" rows={5} maxLength={1024} required />
            <button type="button" className="mb-3 text-sm font-semibold text-brand" onClick={insertVar}>+ Insert variable {`{{${nVars + 1}}}`}</button>
            {nVars > 0 && (
              <div className="mb-3 rounded-xl border p-3" style={{ borderColor: 'var(--border)' }}>
                <b className="text-sm">Sample values (required by Meta)</b>
                {Array.from({ length: nVars }, (_, i) => <div key={i} className="mt-2 flex items-center gap-2"><span className="w-12 text-sm font-mono">{`{{${i + 1}}}`}</span>
                  <input className="input" maxLength={100} aria-label={`Sample for variable ${i + 1}`} {...form.register(`samples.${i}` as const)} placeholder="e.g. Ravi" /></div>)}
                {form.formState.errors.samples && <p className="err">{(form.formState.errors.samples as any).message}</p>}
              </div>
            )}
            <TextField form={form} name="footer" label="Footer" maxLength={60} hint="No variables." />
            <div className="mb-3">
              <div className="mb-1 flex items-center justify-between"><label className="label !mb-0">Buttons</label>{buttons.fields.length < 10 && <button type="button" className="text-sm font-semibold text-brand" onClick={() => buttons.append({ type: 'QUICK_REPLY', text: '' })}>+ Add button</button>}</div>
              {buttons.fields.map((f, i) => {
                const t = w.buttons[i]?.type;
                return (
                  <div key={f.id} className="mb-2 grid grid-cols-[8rem_1fr_auto] items-start gap-2">
                    <select className="input" {...form.register(`buttons.${i}.type` as const)}><option value="QUICK_REPLY">Quick reply</option><option value="URL">Link</option><option value="PHONE_NUMBER">Call</option></select>
                    <div className="space-y-1"><input className="input" placeholder="Button text" maxLength={25} {...form.register(`buttons.${i}.text` as const)} />
                      {t === 'URL' && <input className="input" placeholder="https://…" {...form.register(`buttons.${i}.url` as const)} />}
                      {t === 'PHONE_NUMBER' && <input className="input" placeholder="+91…" {...form.register(`buttons.${i}.phone` as const)} />}
                      {['text', 'url', 'phone'].map((k) => (form.formState.errors.buttons?.[i] as any)?.[k]?.message && <p key={k} className="err">{(form.formState.errors.buttons?.[i] as any)[k].message}</p>)}</div>
                    <button type="button" className="p-2 text-red-600" aria-label="Remove button" onClick={() => buttons.remove(i)}>×</button>
                  </div>
                );
              })}
              {form.formState.errors.buttons && !Array.isArray(form.formState.errors.buttons) && <p className="err">{(form.formState.errors.buttons as any).message}</p>}
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="submit" variant="ghost" busy={form.formState.isSubmitting && !submitNow} onClick={() => setSubmitNow(false)}>Save draft</Button>
              <Button type="submit" busy={form.formState.isSubmitting && submitNow} onClick={() => setSubmitNow(true)}>Submit for approval</Button>
            </div>
          </form>
        </Card>
        <div className="lg:col-span-2"><div className="sticky top-20">
          <div className="muted mb-2 text-xs font-bold uppercase">Live preview</div>
          <div className="rounded-3xl p-4" style={{ background: '#E5DDD5' }}>
            <div className="ml-auto max-w-[90%] rounded-xl rounded-tr-none bg-white p-3 text-sm text-gray-900 shadow">
              {w.headerType === 'TEXT' && w.headerText && <div className="mb-1 font-bold">{w.headerText}</div>}
              {['IMAGE', 'VIDEO', 'DOCUMENT'].includes(w.headerType) && <div className="mb-2 flex h-24 items-center justify-center rounded bg-gray-200 text-xs text-gray-500">{w.headerType}</div>}
              <div className="whitespace-pre-wrap">{preview || 'Your message appears here…'}</div>
              {footerShown && <div className="mt-2 text-xs text-gray-500">{footerShown}</div>}
              <div className="mt-1 text-right text-[10px] text-gray-400">10:30 AM ✓✓</div>
            </div>
            {w.buttons.map((b, i) => <div key={i} className="ml-auto mt-1 max-w-[90%] rounded-lg bg-white p-2 text-center text-sm font-semibold text-sky-600 shadow">{b.text || 'Button'}</div>)}
          </div></div></div>
      </div>
    </>
  );
}
