import { useCallback, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { api, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, fmtDateTime, inr, num } from '../lib/format';
import { errText, useDebounced, useSubmit } from '../lib/hooks';
import { toast } from '../lib/toast';
import { zEmail, zName, zPassword, zPhone } from '../lib/validation';
import { Alert, Badge, Button, Card, ConfirmDialog, CopyButton, DataTable, EmptyState, Loading, Modal, PageHeader, StatCard, StatusBadge, Tabs } from '../components/ui';
import { CheckField, OtpInput, PasswordField, PhoneField, TextField } from '../components/fields';
import { Captcha } from '../components/Captcha';

/* ------------------------------ join (P34) ------------------------------ */
const schema = z.object({ fullName: zName, phone: zPhone, email: zEmail, password: zPassword, confirmPassword: z.string(), referralCode: z.string().trim().toUpperCase().max(12).optional(), acceptTerms: z.literal(true, { errorMap: () => ({ message: 'vr.required' }) }) })
  .refine((v) => v.password === v.confirmPassword, { path: ['confirmPassword'], message: 'password.mismatch' });

export function EarnSignup() {
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const { applyAuth, status, user } = useAuth();
  const [step, setStep] = useState<{ token: string; sentTo: string; devOtp?: string } | null>(null);
  const [otp, setOtp] = useState('');
  const [captcha, setCaptcha] = useState('');
  const onToken = useCallback((t: string) => setCaptcha(t), []);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), mode: 'onBlur', defaultValues: { fullName: '', phone: '', email: '', password: '', confirmPassword: '', referralCode: sp.get('ref') ?? '', acceptTerms: undefined as any } });
  const join = useMutation({
    mutationFn: () => api.post('/api/v1/earn/signup', { ...form.getValues(), captchaToken: captcha }),
    onSuccess: () => { toast.success('You are now a promoter'); window.location.assign('/earn'); }, onError: (e) => toast.error(errText(e)),
  });
  const submit = useSubmit(form, async (v) => {
    const r = await api.post('/api/v1/earn/signup', { ...v, captchaToken: captcha });
    if (r.otpRequired) { setStep({ token: r.signupToken, sentTo: r.otpSentTo, devOtp: r.devOtp }); return; }
    toast.success('You are now a promoter'); window.location.assign('/earn');
  });
  const verify = async (code: string) => {
    try {
      const r = await api.post('/api/v1/earn/signup', { ...form.getValues(), otp: code, signupToken: step!.token });
      applyAuth(r); nav('/earn', { replace: true });
    } catch (e) { toast.error(errText(e)); setOtp(''); }
  };
  if (status === 'authed' && user) {
    return (
      <Card className="!p-6"><h1 className="mb-1 text-xl font-bold">Become a promoter</h1><p className="muted mb-4 text-sm">You are logged in as {user.fullName}. Add a promoter profile to this account.</p>
        <label className="label" htmlFor="ref">Referral code (optional)</label><input id="ref" className="input mb-3" value={form.watch('referralCode')} onChange={(e) => form.setValue('referralCode', e.target.value)} />
        <label className="mb-3 flex items-start gap-2 text-sm"><input type="checkbox" className="mt-1" onChange={(e) => form.setValue('acceptTerms', e.target.checked as any)} />I accept the promoter terms: no fake installs, no bots, no misleading ads.</label>
        <Button busy={join.isPending} disabled={!form.watch('acceptTerms')} onClick={() => join.mutate()}>Join</Button></Card>
    );
  }
  if (step) {
    return (
      <Card className="!p-6"><h1 className="mb-1 text-xl font-bold">Verify your mobile</h1><p className="muted mb-4 text-sm">Enter the 6-digit code sent to {step.sentTo}.{step.devOtp && <b> (dev OTP: {step.devOtp})</b>}</p>
        <OtpInput value={otp} onChange={setOtp} onComplete={verify} /><div className="mt-4"><Button onClick={() => verify(otp)} disabled={otp.length !== 6}>Verify and join</Button></div></Card>
    );
  }
  return (
    <Card className="!p-6">
      <h1 className="mb-1 text-xl font-bold">Earn by promoting apps</h1><p className="muted mb-4 text-sm">Share your link. Get paid for every genuine install or sign-up.</p>
      <form onSubmit={submit} noValidate className="space-y-3">
        <TextField form={form} name="fullName" label="Full name" required autoComplete="name" /><PhoneField form={form} name="phone" label="Mobile number" required />
        <TextField form={form} name="email" label="Email" type="email" required autoComplete="email" /><PasswordField form={form} name="password" label="Password" meter /><PasswordField form={form} name="confirmPassword" label="Confirm password" />
        <TextField form={form} name="referralCode" label="Referral code (optional)" maxLength={12} />
        <CheckField form={form} name="acceptTerms" label="I accept the promoter terms: no fake installs, no bots, no misleading ads." />
        <Captcha onToken={onToken} />
        <Button type="submit" className="w-full" busy={form.formState.isSubmitting}>Send OTP</Button>
        <p className="muted text-center text-sm">Already have an account? <Link to="/login" className="text-brand">Log in</Link></p>
      </form>
    </Card>
  );
}

/* ---------------------------------- dashboard ---------------------------------- */
export function EarnDashboard() {
  const stats = useQuery({ queryKey: ['earn-stats'], queryFn: () => api.get('/api/v1/earn/stats') });
  const profile = useQuery({ queryKey: ['earn-profile'], queryFn: () => api.get('/api/v1/earn/profile') });
  if (stats.isLoading || profile.isLoading) return <Loading />;
  const s = stats.data; const p = profile.data;
  return (
    <>
      <PageHeader title={`Hello, ${p.fullName?.split(' ')[0] ?? ''}`} subtitle="Your earnings at a glance" actions={<Link to="/earn/withdraw" className="btn-primary">Withdraw</Link>} />
      {p.kycRequiredForPayout && <Alert tone="amber" action={<Link to="/earn/kyc" className="btn-ghost">Verify now</Link>}>Verify your PAN to withdraw money.</Alert>}
      {p.status && p.status !== 'ACTIVE' && <Alert tone="red">Your promoter account is {p.status.toLowerCase()}. Contact support.</Alert>}
      <div className="my-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Available" value={inr(s.available)} /><StatCard label="Pending (on hold)" value={inr(s.pending)} /><StatCard label="Lifetime earned" value={inr(s.lifetimeEarned)} /><StatCard label="Paid out" value={inr(s.paid)} />
        <StatCard label="Clicks" value={num(s.clicks)} /><StatCard label="Conversions" value={num(s.conversions)} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Your referral link"><div className="flex items-center gap-2"><code className="min-w-0 flex-1 truncate rounded bg-black/5 p-2 text-xs dark:bg-white/10">{p.referralLink}</code><CopyButton text={p.referralLink} label="Copy" /></div>
          <p className="muted mt-2 text-xs">Your code: <b>{p.referralCode}</b>. Refer another promoter and earn a share of their earnings.</p></Card>
        <Card title="Top offers"><DataTable rows={s.byOffer} empty="Share an offer link to see results here." columns={[{ key: 'n', header: 'Offer', render: (r: any) => r.name }, { key: 'c', header: 'Clicks', render: (r: any) => r.clicks }, { key: 'v', header: 'Conv.', render: (r: any) => r.conversions }]} /></Card>
      </div>
      <Card title="Recent conversions" className="mt-4"><DataTable rows={s.recentConversions} empty="No conversions yet." columns={[
        { key: 'o', header: 'Offer', render: (r: any) => r.offerName }, { key: 'a', header: 'Earning', render: (r: any) => inr(r.promoterPayout) }, { key: 's', header: 'Status', render: (r: any) => <StatusBadge status={r.status} /> },
        { key: 'h', header: 'Releases', hideOnMobile: true, render: (r: any) => (r.holdUntil ? fmtDate(r.holdUntil) : '—') }, { key: 'd', header: 'When', render: (r: any) => fmtDateTime(r.createdAt) },
      ]} /></Card>
    </>
  );
}

/* ---------------------------------- offer wall (P35) ---------------------------------- */
export function OfferWall() {
  const { data: p } = useQuery({ queryKey: ['earn-profile'], queryFn: () => api.get('/api/v1/earn/profile') });
  const [q, setQ] = useState('');
  const [eventType, setEventType] = useState('');
  const dq = useDebounced(q.trim().length >= 2 ? q.trim() : '');
  const offers = useQuery({ queryKey: ['wall', dq, eventType], queryFn: () => api.get<any[]>(`/api/v1/earn/offers${qs({ q: dq, eventType })}`) });
  const link = (id: number) => `${location.origin}/o/${id}/${p?.referralCode}`;
  return (
    <>
      <PageHeader title="Offers" subtitle="Pick an offer and share your link" />
      <div className="mb-3 flex gap-2"><input className="input" aria-label="Search" placeholder="Search offers" value={q} onChange={(e) => setQ(e.target.value)} />
        <select className="input !w-auto" aria-label="Goal" value={eventType} onChange={(e) => setEventType(e.target.value)}><option value="">All goals</option>{['INSTALL', 'SIGNUP', 'PURCHASE', 'KYC', 'CUSTOM'].map((x) => <option key={x}>{x}</option>)}</select></div>
      {offers.isLoading ? <Loading /> : offers.data?.length === 0 ? <EmptyState text="No offers available right now. Check back soon." /> : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">{offers.data!.map((o) => (
          <Card key={o.id}>
            <div className="flex items-start justify-between gap-2"><b>{o.name}</b><span className="text-lg font-extrabold text-brand">{inr(o.promoterPayout)}</span></div>
            <div className="mt-1 flex flex-wrap gap-1"><Badge>{o.eventName || o.eventType}</Badge>{o.category && <Badge>{o.category}</Badge>}{o.incentiveAllowed && <Badge tone="blue">Incentive OK</Badge>}</div>
            <p className="muted mt-2 text-xs">Earnings are held {o.holdDays} days after the conversion. Tracking window {Math.round(o.attributionWindowHours / 24)} days.</p>
            <div className="mt-3 flex items-center gap-2"><code className="min-w-0 flex-1 truncate rounded bg-black/5 p-1.5 text-[11px] dark:bg-white/10">{link(o.id)}</code><CopyButton text={link(o.id)} label="Copy" /></div>
          </Card>))}</div>
      )}
    </>
  );
}

/* ------------------------------------- KYC ------------------------------------- */
export function Kyc() {
  const qc = useQueryClient();
  const profile = useQuery({ queryKey: ['earn-profile'], queryFn: () => api.get('/api/v1/earn/profile') });
  const form = useForm({ resolver: zodResolver(z.object({ pan: z.string().trim().toUpperCase().regex(/^[A-Z]{5}[0-9]{4}[A-Z]$/, 'vr.pan.invalid'), nameOnPan: zName, dateOfBirth: z.string().min(1, 'vr.required'), acceptTerms: z.literal(true, { errorMap: () => ({ message: 'vr.required' }) }) })),
    defaultValues: { pan: '', nameOnPan: '', dateOfBirth: '', acceptTerms: undefined as any } });
  const submit = useSubmit(form, async (v) => { await api.post('/api/v1/earn/kyc', v); qc.invalidateQueries({ queryKey: ['earn-profile'] }); }, { success: 'KYC verified' });
  if (profile.isLoading) return <Loading />;
  const verified = !profile.data.kycRequiredForPayout;
  return (
    <>
      <PageHeader title="Verify your identity" subtitle="PAN is required by law before the first payout" />
      {verified ? <Alert tone="green">Your PAN is verified. You can withdraw money.</Alert> : (
        <Card className="max-w-lg"><form onSubmit={submit} noValidate className="space-y-3">
          <TextField form={form} name="pan" label="PAN" maxLength={10} required autoComplete="off" /><TextField form={form} name="nameOnPan" label="Name as on PAN" required />
          <TextField form={form} name="dateOfBirth" label="Date of birth" type="date" required />
          <CheckField form={form} name="acceptTerms" label="I confirm these details are mine and consent to verification." />
          <Button type="submit" busy={form.formState.isSubmitting}>Verify</Button></form></Card>
      )}
    </>
  );
}

/* --------------------------- payout methods + withdraw (P36, P37) --------------------------- */
export function Payouts() {
  const qc = useQueryClient();
  const profile = useQuery({ queryKey: ['earn-profile'], queryFn: () => api.get('/api/v1/earn/profile') });
  const methods = useQuery({ queryKey: ['methods'], queryFn: () => api.get<any[]>('/api/v1/earn/payout-methods') });
  const history = useQuery({ queryKey: ['withdrawals'], queryFn: () => api.get<any[]>('/api/v1/earn/withdrawals') });
  const [tab, setTab] = useState('withdraw');
  const [adding, setAdding] = useState(false);
  const [rm, setRm] = useState<any | null>(null);
  const [amount, setAmount] = useState('');
  const [methodId, setMethodId] = useState('');
  const [otp, setOtp] = useState('');
  const [otpSent, setOtpSent] = useState<string | null>(null);
  const done = () => { qc.invalidateQueries({ queryKey: ['methods'] }); qc.invalidateQueries({ queryKey: ['withdrawals'] }); qc.invalidateQueries({ queryKey: ['earn-stats'] }); };
  const sendOtp = useMutation({ mutationFn: (purpose: string) => api.post('/api/v1/earn/otp', { purpose }), onSuccess: (r) => { setOtpSent(r.devOtp ? `dev: ${r.devOtp}` : r.sentTo); toast.success('OTP sent'); }, onError: (e) => toast.error(errText(e)) });
  const withdraw = useMutation({
    mutationFn: () => api.post('/api/v1/earn/withdrawals', { amount: Number(amount), methodId: Number(methodId), otp }),
    onSuccess: () => { toast.success('Withdrawal requested'); setAmount(''); setOtp(''); setOtpSent(null); done(); setTab('history'); }, onError: (e) => toast.error(errText(e)),
  });
  const del = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/earn/payout-methods/${id}`), onSuccess: () => { setRm(null); done(); }, onError: (e) => toast.error(errText(e)) });
  const def = useMutation({ mutationFn: (id: number) => api.post(`/api/v1/earn/payout-methods/${id}/default`), onSuccess: done, onError: (e) => toast.error(errText(e)) });
  const available = Number(profile.data?.balanceAvailable ?? 0);
  const usable = methods.data?.filter((m) => m.isVerified) ?? [];
  return (
    <>
      <PageHeader title="Payouts" subtitle={`Available: ${inr(available)}`} />
      {profile.data?.kycRequiredForPayout && <Alert tone="amber" action={<Link to="/earn/kyc" className="btn-ghost">Verify PAN</Link>}>Verify your PAN before withdrawing.</Alert>}
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'withdraw', label: 'Withdraw' }, { key: 'methods', label: 'Bank / UPI' }, { key: 'history', label: 'History' }]} />
      {tab === 'withdraw' && (
        <Card className="max-w-lg">
          <label className="label" htmlFor="w-a">Amount (₹100 – ₹50,000)</label><input id="w-a" className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))} />
          <button className="mt-1 text-sm text-brand" onClick={() => setAmount(String(Math.min(available, 50000)))}>Withdraw maximum</button>
          <label className="label mt-3" htmlFor="w-m">To</label>
          <select id="w-m" className="input" value={methodId} onChange={(e) => setMethodId(e.target.value)}><option value="">Choose…</option>{usable.map((m) => <option key={m.id} value={m.id}>{m.type === 'UPI' ? 'UPI' : `Bank ••${m.accountLast4}`} · {m.holderName}</option>)}</select>
          {methods.data && usable.length === 0 && <p className="muted mt-1 text-xs">Add a verified bank account or UPI ID first.</p>}
          <div className="mt-3 flex items-center gap-2"><Button variant="ghost" busy={sendOtp.isPending} onClick={() => sendOtp.mutate('WITHDRAW')}>{otpSent ? 'Resend OTP' : 'Send OTP'}</Button>{otpSent && <span className="muted text-xs">Sent to {otpSent}</span>}</div>
          {otpSent && <div className="mt-2"><OtpInput value={otp} onChange={setOtp} /></div>}
          <div className="mt-4"><Button busy={withdraw.isPending} disabled={!amount || !methodId || otp.length !== 6 || Number(amount) > available} onClick={() => withdraw.mutate()}>Withdraw</Button></div>
        </Card>
      )}
      {tab === 'methods' && (
        <>
          <div className="mb-3"><Button onClick={() => setAdding(true)}>+ Add bank account or UPI</Button></div>
          <Card><DataTable rows={methods.data} loading={methods.isLoading} empty="No payout methods." columns={[
            { key: 't', header: 'Method', render: (m: any) => (m.type === 'UPI' ? 'UPI' : `Bank ••${m.accountLast4} (${m.ifsc})`) }, { key: 'h', header: 'Name', render: (m: any) => m.holderName },
            { key: 'v', header: 'Status', render: (m: any) => <div>{m.isVerified ? <Badge tone="green">Verified</Badge> : <Badge tone="red">Name mismatch</Badge>}{m.isDefault && <> <Badge>Default</Badge></>}{m.cooldownUntil && new Date(m.cooldownUntil) > new Date() && <div className="muted text-[11px]">Usable after {fmtDateTime(m.cooldownUntil)}</div>}</div> },
            { key: 'x', header: '', render: (m: any) => <span className="flex gap-3 text-sm">{!m.isDefault && m.isVerified && <button className="text-brand" onClick={() => def.mutate(m.id)}>Make default</button>}<button className="text-red-600" onClick={() => setRm(m)}>Remove</button></span> },
          ]} /></Card>
        </>
      )}
      {tab === 'history' && (
        <Card><DataTable rows={history.data} loading={history.isLoading} empty="No withdrawals yet." columns={[
          { key: 'd', header: 'Requested', render: (p: any) => fmtDateTime(p.createdAt) }, { key: 'a', header: 'Amount', render: (p: any) => inr(p.amount) }, { key: 'n', header: 'You get', render: (p: any) => inr(p.netAmount) },
          { key: 's', header: 'Status', render: (p: any) => <div><StatusBadge status={p.status} />{p.rejectReason && <div className="text-[11px] text-red-600">{p.rejectReason}</div>}</div> }, { key: 'u', header: 'UTR', hideOnMobile: true, render: (p: any) => p.utr || '—' },
        ]} /></Card>
      )}
      <AddMethod open={adding} onClose={() => setAdding(false)} onDone={() => { setAdding(false); done(); }} sendOtp={() => sendOtp.mutateAsync('PAYOUT_METHOD')} />
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Remove this payout method?" message="Pending withdrawals to it are not affected." danger confirmLabel="Remove" busy={del.isPending} onConfirm={() => del.mutate(rm.id)} />
    </>
  );
}

function AddMethod({ open, onClose, onDone, sendOtp }: { open: boolean; onClose: () => void; onDone: () => void; sendOtp: () => Promise<any> }) {
  const [v, setV] = useState({ type: 'UPI', upiId: '', holderName: '', accountNumber: '', accountNumberConfirm: '', ifsc: '', otp: '' });
  const [sent, setSent] = useState(false);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const set = (k: string, val: string) => setV({ ...v, [k]: val });
  const add = useMutation({ mutationFn: () => api.post('/api/v1/earn/payout-methods', v), onSuccess: () => { toast.success('Payout method added. It can be used after a 24-hour safety wait.'); onDone(); },
    onError: (e: any) => { setErrs(Object.fromEntries((e.fieldErrors ?? []).map((x: any) => [x.field, x.message]))); toast.error(errText(e)); } });
  const f = (k: string, label: string, props: any = {}) => (<div><label className="label" htmlFor={`pm-${k}`}>{label}</label><input id={`pm-${k}`} className="input" value={(v as any)[k]} onChange={(e) => set(k, e.target.value)} {...props} />{errs[k] && <p className="mt-1 text-sm text-red-600">{errs[k]}</p>}</div>);
  return (
    <Modal open={open} onClose={onClose} title="Add payout method" footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={add.isPending} disabled={v.otp.length !== 6} onClick={() => add.mutate()}>Add</Button></>}>
      <div className="space-y-3">
        <select className="input" aria-label="Type" value={v.type} onChange={(e) => set('type', e.target.value)}><option value="UPI">UPI ID</option><option value="BANK">Bank account</option></select>
        {v.type === 'UPI' ? f('upiId', 'UPI ID', { placeholder: 'name@bank' }) : <>{f('accountNumber', 'Account number', { inputMode: 'numeric', autoComplete: 'off' })}{f('accountNumberConfirm', 'Confirm account number', { inputMode: 'numeric', autoComplete: 'off' })}{f('ifsc', 'IFSC', { maxLength: 11 })}</>}
        {f('holderName', 'Account holder name (must match your PAN name)')}
        <div className="flex items-center gap-2"><Button variant="ghost" onClick={async () => { await sendOtp(); setSent(true); }}>{sent ? 'Resend OTP' : 'Send OTP'}</Button></div>
        {sent && f('otp', 'OTP', { inputMode: 'numeric', maxLength: 6 })}
      </div>
    </Modal>
  );
}

export function Earnings() {
  const q = useQuery({ queryKey: ['ledger'], queryFn: () => api.get<any[]>('/api/v1/earn/ledger') });
  return (
    <>
      <PageHeader title="Earnings history" />
      <Card><DataTable rows={q.data} loading={q.isLoading} empty="Nothing here yet." columns={[
        { key: 'd', header: 'Date', render: (r: any) => fmtDateTime(r.createdAt) }, { key: 't', header: 'Type', render: (r: any) => r.type.replace(/_/g, ' ') },
        { key: 'a', header: 'Amount', render: (r: any) => <span className={Number(r.amount) < 0 ? 'text-red-600' : 'text-emerald-600'}>{inr(r.amount)}</span> }, { key: 'b', header: 'Bucket', hideOnMobile: true, render: (r: any) => r.bucket },
        { key: 'n', header: 'Note', hideOnMobile: true, render: (r: any) => r.note },
      ]} /></Card>
    </>
  );
}
