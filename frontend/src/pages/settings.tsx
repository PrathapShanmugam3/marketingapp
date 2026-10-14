import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { api, getAccessToken } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, fmtDateTime, inr, num } from '../lib/format';
import { errText, useSubmit } from '../lib/hooks';
import { toast } from '../lib/toast';
import { STATES, zBizName, zCity, zEmailOpt, zGstin, zName, zPincode, zPhone } from '../lib/validation';
import { Alert, Badge, Button, Card, ConfirmDialog, CopyButton, DataTable, Loading, Modal, PageHeader, StatusBadge, Tabs } from '../components/ui';
import { PasswordField, PhoneField, SelectField, TextField, UploadField } from '../components/fields';

/* ----------------------------- business profile (P05) ----------------------------- */
const profileSchema = z.object({
  name: zBizName, phone: zPhone, email: zEmailOpt, addressLine: z.string().trim().max(200, 'vr.size').optional(), city: zCity,
  state: z.string().min(1, 'vr.required'), pincode: zPincode, gstin: zGstin, language: z.enum(['en', 'ta']), logoUrl: z.string().optional(),
  brandColor: z.string().regex(/^$|^#[0-9a-fA-F]{6}$/, 'vr.required').optional().or(z.literal('')),
});
type ProfileValues = z.infer<typeof profileSchema>;

export function BusinessProfile({ onboarding }: { onboarding?: boolean }) {
  const { workspace, reload, can } = useAuth();
  const nav = useNavigate();
  const ws = useQuery({ queryKey: ['workspace'], queryFn: () => api.get('/api/v1/workspace') });
  const form = useForm<ProfileValues>({ resolver: zodResolver(profileSchema), mode: 'onBlur' });
  useEffect(() => {
    const w = ws.data; if (!w) return;
    form.reset({ name: w.name ?? '', phone: w.phone ?? '', email: w.email ?? '', addressLine: w.addressLine ?? '', city: w.city ?? '', state: w.state ?? '', pincode: w.pincode ?? '', gstin: w.gstin ?? '',
      language: w.language ?? 'en', logoUrl: w.logoUrl ?? '', brandColor: w.brandColor ?? '' });
  }, [ws.data]); // eslint-disable-line react-hooks/exhaustive-deps
  const submit = useSubmit(form, async (v) => {
    await api.put('/api/v1/workspace', v);
    await reload();
    if (onboarding) nav('/', { replace: true });
  }, { success: 'Saved' });
  if (ws.isLoading) return <Loading />;
  const o = ws.data?.onboarding;
  return (
    <>
      <PageHeader title={onboarding ? 'Set up your business' : 'Business profile'} subtitle={onboarding ? 'Tell us about your business to get started' : undefined} />
      {!onboarding && o && (
        <Card title="Getting started" className="mb-4">
          <ul className="space-y-1 text-sm">{[['whatsappConnected', 'Connect WhatsApp'], ['contactsImported', 'Add or import contacts'], ['firstCampaign', 'Send your first campaign']].map(([k, l]) => (
            <li key={k}>{o[k] ? '✅' : '⬜'} {l}</li>))}</ul>
        </Card>
      )}
      <Card>
        <form onSubmit={submit} className="grid gap-3 md:grid-cols-2" noValidate>
          <TextField form={form} name="name" label="Business name" required maxLength={100} />
          <PhoneField form={form} name="phone" label="Business mobile" required />
          <TextField form={form} name="email" label="Email" type="email" />
          <TextField form={form} name="addressLine" label="Address" maxLength={200} />
          <TextField form={form} name="city" label="City" required maxLength={60} />
          <SelectField form={form} name="state" label="State" required placeholder="Choose state" options={(ws.data?.states ?? STATES).map((s: string) => ({ value: s, label: s }))} />
          <TextField form={form} name="pincode" label="Pincode" inputMode="numeric" maxLength={6} />
          <TextField form={form} name="gstin" label="GSTIN (optional)" maxLength={15} hint="Used on your invoices" />
          <SelectField form={form} name="language" label="Language" options={[{ value: 'en', label: 'English' }, { value: 'ta', label: 'தமிழ்' }]} />
          <TextField form={form} name="brandColor" label="Brand colour" placeholder="#128C7E" maxLength={7} />
          <div className="md:col-span-2"><UploadField form={form} name="logoUrl" label="Logo" hint="JPG or PNG, up to 5 MB" /></div>
          <div className="md:col-span-2"><Button type="submit" busy={form.formState.isSubmitting} disabled={!can.owner && !onboarding}>{onboarding ? 'Continue' : 'Save changes'}</Button></div>
        </form>
      </Card>
      {workspace && !can.owner && <p className="muted mt-2 text-sm">Only the owner can change these details.</p>}
    </>
  );
}

/* --------------------------------- team (P21) --------------------------------- */
export function Team() {
  const qc = useQueryClient();
  const { user } = useAuth();
  const q = useQuery({ queryKey: ['team'], queryFn: () => api.get('/api/v1/team') });
  const [open, setOpen] = useState(false);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('STAFF');
  const [err, setErr] = useState('');
  const [rm, setRm] = useState<any | null>(null);
  const done = () => qc.invalidateQueries({ queryKey: ['team'] });
  const invite = useMutation({
    mutationFn: () => api.post('/api/v1/team', { email, role }),
    onSuccess: () => { toast.success('Invitation sent'); setOpen(false); setEmail(''); setErr(''); done(); },
    onError: (e: any) => { setErr(e.fieldErrors?.[0]?.message ?? errText(e)); },
  });
  const change = useMutation({ mutationFn: (v: { id: number; role: string }) => api.patch(`/api/v1/team/${v.id}`, { role: v.role }), onSuccess: done, onError: (e) => toast.error(errText(e)) });
  const remove = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/team/${id}`), onSuccess: () => { setRm(null); done(); }, onError: (e) => toast.error(errText(e)) });
  const resend = useMutation({ mutationFn: (id: number) => api.post(`/api/v1/team/${id}/resend`), onSuccess: () => toast.success('Invitation sent again'), onError: (e) => toast.error(errText(e)) });
  const members: any[] = q.data?.members ?? [];
  return (
    <>
      <PageHeader title="Team" subtitle={q.data ? `${members.length} of ${q.data.maxUsers} seats used` : undefined} actions={<Button onClick={() => setOpen(true)} disabled={members.length >= (q.data?.maxUsers ?? 0)}>+ Invite</Button>} />
      {q.data && members.length >= q.data.maxUsers && <Alert tone="amber">You have used all seats on your plan. Upgrade from Billing to invite more people.</Alert>}
      <Card className="mt-3">
        <DataTable rows={members} loading={q.isLoading} columns={[
          { key: 'n', header: 'Person', render: (m: any) => <div><b>{m.fullName ?? m.inviteEmail}</b><div className="muted text-xs">{m.email ?? m.inviteEmail}</div></div> },
          { key: 'r', header: 'Role', render: (m: any) => (m.role === 'OWNER' || m.userId === user?.id ? <Badge>{m.role}</Badge> :
            <select className="input !w-auto !py-1" aria-label="Role" value={m.role} onChange={(e) => change.mutate({ id: m.id, role: e.target.value })}><option value="MANAGER">Manager</option><option value="STAFF">Staff</option></select>) },
          { key: 's', header: 'Status', render: (m: any) => <StatusBadge status={m.inviteStatus === 'ACCEPTED' ? 'ACTIVE' : 'PENDING'} /> },
          { key: 'l', header: 'Last active', hideOnMobile: true, render: (m: any) => (m.lastLoginAt ? fmtDateTime(m.lastLoginAt) : '—') },
          { key: 'a', header: '', render: (m: any) => (m.role === 'OWNER' ? null : <span className="flex gap-3 text-sm">
            {m.inviteStatus === 'PENDING' && <button className="text-brand" onClick={() => resend.mutate(m.id)}>Resend</button>}
            <button className="text-red-600" onClick={() => setRm(m)}>Remove</button></span>) },
        ]} />
      </Card>
      <Modal open={open} onClose={() => setOpen(false)} title="Invite a teammate" footer={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button busy={invite.isPending} disabled={!email} onClick={() => invite.mutate()}>Send invite</Button></>}>
        <label className="label" htmlFor="inv-e">Email</label>
        <input id="inv-e" type="email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} />
        {err && <p className="mt-1 text-sm text-red-600">{err}</p>}
        <label className="label mt-3" htmlFor="inv-r">Role</label>
        <select id="inv-r" className="input" value={role} onChange={(e) => setRole(e.target.value)}><option value="MANAGER">Manager - everything except billing and team</option><option value="STAFF">Staff - only assigned leads and inbox</option></select>
      </Modal>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Remove this person?" message="They lose access immediately. Their leads become unassigned." danger confirmLabel="Remove" busy={remove.isPending} onConfirm={() => remove.mutate(rm.id)} />
    </>
  );
}

/* --------------------------- billing and wallet (P22) --------------------------- */
export function Billing() {
  const qc = useQueryClient();
  const b = useQuery({ queryKey: ['billing'], queryFn: () => api.get('/api/v1/billing') });
  const [tab, setTab] = useState('plan');
  const [amount, setAmount] = useState('1000');
  const [gstin, setGstin] = useState('');
  const [cycle, setCycle] = useState('MONTHLY');
  const [paying, setPaying] = useState<number | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => { // poll after the pay page is opened; the wallet or plan only changes once the server confirms
    if (!paying) return;
    const t = setInterval(async () => {
      try {
        const p = await api.get(`/api/v1/billing/payments/${paying}`);
        if (p.status === 'SUCCESS') { toast.success('Payment received'); setPaying(null); qc.invalidateQueries(); }
        else if (p.status === 'FAILED') { toast.error(p.failureReason || 'Payment failed'); setPaying(null); }
      } catch { /* keep polling */ }
    }, 3000);
    return () => clearInterval(t);
  }, [paying, qc]);

  const start = useMutation({
    mutationFn: (body: { kind: 'topup' | 'sub'; planCode?: string }) => (body.kind === 'topup'
      ? api.post('/api/v1/billing/topups', { amount: Number(amount), gstin: gstin || undefined })
      : api.post('/api/v1/billing/subscribe', { planCode: body.planCode, cycle, gstin: gstin || undefined })),
    onSuccess: (o) => { setErr(''); setPaying(o.paymentId); window.open(o.payUrl, '_blank', 'noopener'); },
    onError: (e: any) => { setErr(e.fieldErrors?.[0]?.message ?? errText(e)); toast.error(errText(e)); },
  });
  const cancel = useMutation({ mutationFn: () => api.post('/api/v1/billing/cancel'), onSuccess: () => { toast.success('Your plan will end at the end of this period.'); qc.invalidateQueries({ queryKey: ['billing'] }); }, onError: (e) => toast.error(errText(e)) });
  const [cancelAsk, setCancelAsk] = useState(false);
  const pdf = async (id: number) => {
    const r = await fetch(`/api/v1/billing/invoices/${id}/pdf`, { headers: { Authorization: `Bearer ${getAccessToken()}` } });
    if (!r.ok) return toast.error('Could not open the invoice.');
    window.open(URL.createObjectURL(await r.blob()), '_blank');
  };
  if (b.isLoading) return <Loading />;
  const d = b.data;
  const sub = d.subscription;
  const wallet = d.workspace?.walletBalance ?? d.workspace?.wallet_balance;
  return (
    <>
      <PageHeader title="Billing" subtitle="Plan, wallet and invoices" />
      {paying && <Alert tone="blue" action={<Button variant="ghost" onClick={() => setPaying(null)}>Stop waiting</Button>}>Waiting for your payment to be confirmed. Complete it in the payment tab that opened.</Alert>}
      <div className="my-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCardLite label="Plan" value={d.plan?.name ?? '—'} sub={sub?.status === 'TRIAL' ? `Trial ends ${fmtDate(sub.currentPeriodEnd ?? sub.trialEndsAt)}` : sub ? `Renews ${fmtDate(sub.currentPeriodEnd)}` : undefined} />
        <StatCardLite label="Wallet" value={inr(wallet)} />
        <StatCardLite label="Contacts" value={`${num(d.usage?.contacts)} / ${num(d.plan?.maxContacts)}`} />
        <StatCardLite label="Team seats" value={`${d.usage?.users} / ${d.plan?.maxUsers}`} />
      </div>
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'plan', label: 'Plans' }, { key: 'wallet', label: 'Wallet' }, { key: 'invoices', label: 'Invoices' }]} />
      <div className="mb-3 max-w-xs"><label className="label" htmlFor="b-gst">GSTIN for invoice (optional)</label><input id="b-gst" className="input" maxLength={15} value={gstin} onChange={(e) => setGstin(e.target.value.toUpperCase())} /></div>
      {err && <Alert tone="red">{err}</Alert>}
      {tab === 'plan' && (
        <>
          <div className="mb-3 inline-flex overflow-hidden rounded-lg border text-sm font-semibold" style={{ borderColor: 'var(--border)' }}>
            {['MONTHLY', 'YEARLY'].map((c) => <button key={c} className={`px-3 py-1.5 ${cycle === c ? 'bg-brand text-white' : ''}`} onClick={() => setCycle(c)}>{c === 'MONTHLY' ? 'Monthly' : 'Yearly'}</button>)}
          </div>
          <div className="grid gap-3 md:grid-cols-3">
            {d.plans.map((p: any) => {
              const price = cycle === 'YEARLY' ? p.priceYearly : p.priceMonthly;
              const current = d.plan?.code === p.code && sub?.status !== 'TRIAL';
              return (
                <Card key={p.id} title={p.name}>
                  <div className="text-2xl font-extrabold">{inr(price, 0)}<span className="muted text-sm font-normal"> / {cycle === 'YEARLY' ? 'year' : 'month'}</span></div>
                  <p className="muted text-xs">+ 18% GST</p>
                  <ul className="my-3 space-y-1 text-sm"><li>{num(p.maxContacts)} contacts</li><li>{p.maxUsers} team members</li><li>{p.maxDomains} custom domains</li>
                    {p.maxWorkspaces > 1 && <li>{p.maxWorkspaces} client workspaces</li>}
                    {Object.entries(p.features ?? {}).filter(([, v]) => v).map(([k]) => <li key={k}>✓ {k.replace(/_/g, ' ')}</li>)}</ul>
                  <Button className="w-full" disabled={current} busy={start.isPending} onClick={() => start.mutate({ kind: 'sub', planCode: p.code })}>{current ? 'Current plan' : 'Choose plan'}</Button>
                </Card>
              );
            })}
          </div>
          {sub && sub.status === 'ACTIVE' && !sub.cancelAtPeriodEnd && <p className="mt-4 text-sm"><button className="text-red-600 underline" onClick={() => setCancelAsk(true)}>Cancel subscription</button></p>}
          {sub?.cancelAtPeriodEnd && <Alert tone="amber">Your plan ends on {fmtDate(sub.currentPeriodEnd)}.</Alert>}
        </>
      )}
      {tab === 'wallet' && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card title="Add money">
            <label className="label" htmlFor="b-amt">Amount (₹500 – ₹50,000, in multiples of ₹100)</label>
            <input id="b-amt" className="input" inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} />
            <div className="my-2 flex gap-2">{[500, 1000, 5000, 10000].map((v) => <button key={v} className="btn-ghost !py-1" onClick={() => setAmount(String(v))}>₹{num(v)}</button>)}</div>
            <p className="muted text-xs">GST at 18% is added at checkout; the wallet is credited with the amount you enter.</p>
            <Button className="mt-3" busy={start.isPending} onClick={() => start.mutate({ kind: 'topup' })}>Pay</Button>
            <h3 className="mt-5 text-sm font-bold">Message rates (per message, India)</h3>
            <ul className="text-sm">{d.rates.map((r: any) => <li key={r.category} className="flex justify-between"><span>{r.category}</span><span>{inr(r.sellRate, 4)}</span></li>)}</ul>
          </Card>
          <Card title="Recent wallet activity">
            <DataTable rows={d.walletTransactions} columns={[
              { key: 'd', header: 'Date', render: (t: any) => fmtDateTime(t.createdAt) }, { key: 't', header: 'Type', render: (t: any) => t.type.replace(/_/g, ' ') },
              { key: 'a', header: 'Amount', render: (t: any) => <span className={t.amount < 0 ? 'text-red-600' : 'text-emerald-600'}>{inr(t.amount)}</span> }, { key: 'b', header: 'Balance', render: (t: any) => inr(t.balanceAfter) },
            ]} />
          </Card>
        </div>
      )}
      {tab === 'invoices' && (
        <Card><DataTable rows={d.invoices} empty="No invoices yet." columns={[
          { key: 'n', header: 'Invoice', render: (i: any) => i.invoiceNumber }, { key: 'd', header: 'Date', render: (i: any) => fmtDate(i.issuedAt) },
          { key: 't', header: 'Type', render: (i: any) => i.type }, { key: 'a', header: 'Total', render: (i: any) => inr(i.total) }, { key: 's', header: 'Status', render: (i: any) => <StatusBadge status={i.status} /> },
          { key: 'p', header: '', render: (i: any) => <button className="text-brand" onClick={() => pdf(i.id)}>View PDF</button> },
        ]} /></Card>
      )}
      <ConfirmDialog open={cancelAsk} onClose={() => setCancelAsk(false)} title="Cancel subscription?" message="You keep your plan until the end of the paid period." danger confirmLabel="Cancel plan" busy={cancel.isPending} onConfirm={() => { cancel.mutate(); setCancelAsk(false); }} />
    </>
  );
}

const StatCardLite = ({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) => (
  <div className="card"><div className="muted text-xs">{label}</div><div className="text-xl font-bold">{value}</div>{sub && <div className="muted text-xs">{sub}</div>}</div>
);

/* ------------------------- profile, security, sessions (P23) ------------------------- */
const pwSchema = z.object({ currentPassword: z.string().min(1, 'vr.required'), newPassword: z.string().min(8, 'vr.password.weak'), confirmPassword: z.string() })
  .refine((v) => v.newPassword === v.confirmPassword, { path: ['confirmPassword'], message: 'vr.password.mismatch' });

export function Account() {
  const { user, reload, logout } = useAuth();
  const qc = useQueryClient();
  const nameForm = useForm({ resolver: zodResolver(z.object({ fullName: zName })), defaultValues: { fullName: user?.fullName ?? '' } });
  const saveName = useSubmit(nameForm, async (v) => { await api.put('/api/v1/profile', { ...v, preferredLanguage: user?.preferredLanguage }); await reload(); }, { success: 'Saved' });
  const pw = useForm({ resolver: zodResolver(pwSchema) });
  const savePw = useSubmit(pw, async (v) => { await api.post('/api/v1/profile/password', v); pw.reset(); qc.invalidateQueries({ queryKey: ['sessions'] }); }, { success: 'Password changed' });
  const phone = useForm({ resolver: zodResolver(z.object({ phone: zPhone, otp: z.string().optional() })), defaultValues: { phone: '', otp: '' } });
  const [otpSent, setOtpSent] = useState(false);
  const sendOtp = async () => {
    if (!(await phone.trigger('phone'))) return;
    try { const r = await api.post('/api/v1/profile/phone/otp', { phone: phone.getValues('phone') }); setOtpSent(true); toast.success(r.devOtp ? `OTP sent (dev: ${r.devOtp})` : 'OTP sent'); } catch (e: any) { phone.setError('phone', { message: e.message }); }
  };
  const savePhone = useSubmit(phone, async (v) => { await api.put('/api/v1/profile/phone', { phone: v.phone, otp: v.otp }); setOtpSent(false); phone.reset(); await reload(); }, { success: 'Mobile number updated' });
  const sessions = useQuery({ queryKey: ['sessions'], queryFn: () => api.get<any[]>('/api/v1/auth/sessions') });
  const revoke = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/auth/sessions/${id}`), onSuccess: () => sessions.refetch(), onError: (e) => toast.error(errText(e)) });
  return (
    <>
      <PageHeader title="My account" subtitle={`${user?.email} · ${user?.phone}`} actions={<Button variant="ghost" onClick={() => logout()}>Log out</Button>} />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Name"><form onSubmit={saveName} noValidate><TextField form={nameForm} name="fullName" label="Full name" required /><div className="mt-3"><Button type="submit" busy={nameForm.formState.isSubmitting}>Save</Button></div></form></Card>
        <Card title="Change mobile number"><form onSubmit={savePhone} noValidate><PhoneField form={phone} name="phone" label="New mobile number" required />
          {otpSent && <div className="mt-2"><TextField form={phone} name="otp" label="OTP" inputMode="numeric" maxLength={6} /></div>}
          <div className="mt-3 flex gap-2"><Button type="button" variant="ghost" onClick={sendOtp}>{otpSent ? 'Resend OTP' : 'Send OTP'}</Button>{otpSent && <Button type="submit" busy={phone.formState.isSubmitting}>Verify and save</Button>}</div></form></Card>
        <Card title="Change password"><form onSubmit={savePw} noValidate className="space-y-3">
          <PasswordField form={pw} name="currentPassword" label="Current password" autoComplete="current-password" /><PasswordField form={pw} name="newPassword" label="New password" meter /><PasswordField form={pw} name="confirmPassword" label="Confirm new password" />
          <Button type="submit" busy={pw.formState.isSubmitting}>Change password</Button></form></Card>
        <Card title="Where you are logged in">
          <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>{sessions.data?.map((s) => (
            <li key={s.id} className="flex items-center justify-between gap-2 py-2 text-sm"><span className="min-w-0"><b className="block truncate">{s.userAgent || 'Unknown device'}</b><span className="muted text-xs">{s.ip} · {fmtDateTime(s.createdAt)}</span></span>
              {s.current ? <Badge tone="green">This device</Badge> : <button className="text-red-600" onClick={() => revoke.mutate(s.id)}>Log out</button>}</li>))}</ul>
        </Card>
      </div>
    </>
  );
}

/* ---------------------------------- agency (P24) ---------------------------------- */
export function AgencyClients() {
  const qc = useQueryClient();
  const { switchWorkspace } = useAuth();
  const list = useQuery({ queryKey: ['clients'], queryFn: () => api.get<any[]>('/api/v1/agency/clients') });
  const [open, setOpen] = useState(false);
  const form = useForm({ resolver: zodResolver(z.object({ name: zBizName, city: zCity, state: z.string().min(1, 'vr.required'), brandColor: z.string().optional() })), defaultValues: { name: '', city: '', state: '', brandColor: '' } });
  const submit = useSubmit(form, async (v) => { await api.post('/api/v1/agency/clients', v); setOpen(false); form.reset(); qc.invalidateQueries({ queryKey: ['clients'] }); }, { success: 'Client workspace created' });
  return (
    <>
      <PageHeader title="Clients" subtitle="Run marketing for several businesses" actions={<Button onClick={() => setOpen(true)}>+ New client</Button>} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No client workspaces yet." columns={[
        { key: 'n', header: 'Client', render: (c: any) => <b>{c.name}</b> }, { key: 'c', header: 'City', render: (c: any) => c.city }, { key: 's', header: 'Status', render: (c: any) => <StatusBadge status={c.status} /> },
        { key: 'w', header: 'Wallet', render: (c: any) => inr(c.walletBalance) }, { key: 'o', header: '', render: (c: any) => <button className="text-brand" onClick={() => switchWorkspace(c.id)}>Open</button> },
      ]} /></Card>
      <Modal open={open} onClose={() => setOpen(false)} title="New client workspace" footer={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button busy={form.formState.isSubmitting} onClick={submit}>Create</Button></>}>
        <div className="space-y-3"><TextField form={form} name="name" label="Business name" required /><TextField form={form} name="city" label="City" required />
          <SelectField form={form} name="state" label="State" required placeholder="Choose" options={STATES.map((s) => ({ value: s, label: s }))} /><TextField form={form} name="brandColor" label="Brand colour" placeholder="#128C7E" /></div>
      </Modal>
    </>
  );
}

/* --------------------------------- domains (P26) --------------------------------- */
export function Domains() {
  const qc = useQueryClient();
  const { can } = useAuth();
  const list = useQuery({ queryKey: ['domains'], queryFn: () => api.get<any[]>('/api/v1/domains') });
  const [domain, setDomain] = useState('');
  const [purpose, setPurpose] = useState('PAGES');
  const [rm, setRm] = useState<any | null>(null);
  const [warn, setWarn] = useState<any | null>(null);
  const done = () => qc.invalidateQueries({ queryKey: ['domains'] });
  const add = useMutation({ mutationFn: () => api.post('/api/v1/domains', { domain, purpose }), onSuccess: () => { setDomain(''); done(); toast.success('Domain added. Now add the DNS records shown below.'); }, onError: (e: any) => toast.error(e.fieldErrors?.[0]?.message ?? errText(e)) });
  const verify = useMutation({ mutationFn: (id: number) => api.post(`/api/v1/domains/${id}/verify`), onSuccess: (r) => { done(); r.status === 'ACTIVE' || r.status === 'VERIFIED' ? toast.success('Domain verified') : toast.info(r.lastError || 'DNS records not found yet. It can take a few minutes.'); }, onError: (e) => toast.error(errText(e)) });
  const primary = useMutation({ mutationFn: (id: number) => api.post(`/api/v1/domains/${id}/primary`), onSuccess: done, onError: (e) => toast.error(errText(e)) });
  const remove = useMutation({
    mutationFn: (v: { id: number; confirm: boolean }) => api.del(`/api/v1/domains/${v.id}?confirm=${v.confirm}`),
    onSuccess: () => { setRm(null); setWarn(null); done(); },
    onError: (e: any) => { if (e.extra?.needsConfirm) setWarn(e.extra); else toast.error(errText(e)); },
  });
  return (
    <>
      <PageHeader title="Custom domains" subtitle="Use your own domain for landing pages and short links" />
      {can.owner && <Card className="mb-4"><div className="grid gap-2 sm:grid-cols-[1fr_160px_auto]">
        <input className="input" aria-label="Domain" placeholder="offers.yourshop.com" value={domain} onChange={(e) => setDomain(e.target.value.trim().toLowerCase())} />
        <select className="input" aria-label="Purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)}><option value="PAGES">Landing pages</option><option value="LINKS">Short links</option></select>
        <Button busy={add.isPending} disabled={!domain} onClick={() => add.mutate()}>Add</Button></div></Card>}
      {list.isLoading ? <Loading /> : list.data?.map((d) => (
        <Card key={d.id} className="mb-3" title={<span>{d.domain} {d.isPrimary && <Badge tone="green">Primary</Badge>}</span>} action={<StatusBadge status={d.status} />}>
          {d.status !== 'ACTIVE' && d.instructions && (
            <div className="space-y-2 text-sm">
              <p>Add these two DNS records at your domain provider, then press Verify.</p>
              <DnsRow type="TXT" host={d.instructions.txt.host} value={d.instructions.txt.value} />
              <DnsRow type={d.instructions.routing.type} host={d.instructions.routing.host} value={d.instructions.routing.value} />
              {d.lastError && <Alert tone="amber">{d.lastError}</Alert>}
            </div>
          )}
          {can.owner && <div className="mt-3 flex gap-3 text-sm">
            {d.status !== 'ACTIVE' && <Button busy={verify.isPending} onClick={() => verify.mutate(d.id)}>Verify</Button>}
            {d.status === 'ACTIVE' && !d.isPrimary && <button className="text-brand" onClick={() => primary.mutate(d.id)}>Make primary</button>}
            <button className="text-red-600" onClick={() => { setRm(d); remove.mutate({ id: d.id, confirm: false }); }}>Remove</button></div>}
        </Card>
      ))}
      <ConfirmDialog open={!!warn} onClose={() => { setWarn(null); setRm(null); }} title={`Remove ${rm?.domain}?`} danger confirmLabel="Remove anyway" busy={remove.isPending}
        message={<>This domain is used by {warn?.pages ?? 0} landing page(s) and {warn?.links ?? 0} short link(s). They will stop working on this domain.</>} onConfirm={() => remove.mutate({ id: rm.id, confirm: true })} />
    </>
  );
}
const DnsRow = ({ type, host, value }: { type: string; host: string; value: string }) => (
  <div className="flex flex-wrap items-center gap-2 rounded-lg bg-black/5 p-2 dark:bg-white/5"><Badge>{type}</Badge><code className="text-xs">{host}</code><span>→</span><code className="min-w-0 flex-1 break-all text-xs">{value}</code><CopyButton text={value} /></div>
);

/* --------------------------------- API keys (P31) --------------------------------- */
const SCOPES = [['contacts:read', 'Read contacts'], ['contacts:write', 'Create contacts'], ['leads:write', 'Create leads'], ['automations:trigger', 'Trigger automations'], ['links:write', 'Create short links']];
export function ApiKeys() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['api-keys'], queryFn: () => api.get<any[]>('/api/v1/api-keys') });
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['contacts:write']);
  const [created, setCreated] = useState<string | null>(null);
  const [rv, setRv] = useState<any | null>(null);
  const create = useMutation({
    mutationFn: () => api.post('/api/v1/api-keys', { name, scopes }),
    onSuccess: (r) => { setOpen(false); setName(''); setCreated(r.key); qc.invalidateQueries({ queryKey: ['api-keys'] }); },
    onError: (e: any) => toast.error(e.fieldErrors?.[0]?.message ?? errText(e)),
  });
  const revoke = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/api-keys/${id}`), onSuccess: () => { setRv(null); qc.invalidateQueries({ queryKey: ['api-keys'] }); }, onError: (e) => toast.error(errText(e)) });
  return (
    <>
      <PageHeader title="API keys" subtitle="Connect your website or other tools" actions={<Button onClick={() => setOpen(true)}>+ New key</Button>} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No API keys." columns={[
        { key: 'n', header: 'Name', render: (k: any) => <b>{k.name}</b> }, { key: 'p', header: 'Key', render: (k: any) => <code className="text-xs">{k.keyPrefix}…</code> },
        { key: 's', header: 'Permissions', hideOnMobile: true, render: (k: any) => <span className="text-xs">{(Array.isArray(k.scopes) ? k.scopes : []).join(', ')}</span> },
        { key: 'u', header: 'Last used', render: (k: any) => (k.lastUsedAt ? fmtDateTime(k.lastUsedAt) : 'Never') },
        { key: 'a', header: '', render: (k: any) => (k.revokedAt ? <StatusBadge status="REVOKED" /> : <button className="text-red-600" onClick={() => setRv(k)}>Revoke</button>) },
      ]} /></Card>
      <Modal open={open} onClose={() => setOpen(false)} title="New API key" footer={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button busy={create.isPending} disabled={!name || !scopes.length} onClick={() => create.mutate()}>Create</Button></>}>
        <label className="label" htmlFor="k-n">Name</label><input id="k-n" className="input" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} placeholder="Website contact form" />
        <div className="mt-3 space-y-1">{SCOPES.map(([v, l]) => <label key={v} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={scopes.includes(v)} onChange={(e) => setScopes(e.target.checked ? [...scopes, v] : scopes.filter((x) => x !== v))} />{l}</label>)}</div>
      </Modal>
      <Modal open={!!created} onClose={() => setCreated(null)} title="Copy your key now" footer={<Button onClick={() => setCreated(null)}>Done</Button>}>
        <Alert tone="amber">This key is shown only once. Store it somewhere safe.</Alert>
        <div className="mt-3 flex items-center gap-2"><code className="min-w-0 flex-1 break-all rounded bg-black/5 p-2 text-xs dark:bg-white/10">{created}</code>{created && <CopyButton text={created} label="Copy" />}</div>
        <p className="muted mt-3 text-xs">Send it as <code>Authorization: Bearer &lt;key&gt;</code> to <code>/api/v1/integrations/…</code></p>
      </Modal>
      <ConfirmDialog open={!!rv} onClose={() => setRv(null)} title="Revoke this key?" message="Anything using it stops working immediately." danger confirmLabel="Revoke" busy={revoke.isPending} onConfirm={() => revoke.mutate(rv.id)} />
    </>
  );
}
