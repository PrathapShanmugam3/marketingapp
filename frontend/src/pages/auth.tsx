import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useTranslation } from 'react-i18next';
import { api, ApiError, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useDebounced, useSubmit, errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { BUSINESS_TYPES, STATES, zBizName, zCity, zEmail, zName, zOtp, zPassword, zPhone } from '../lib/validation';
import { Button, Card, Modal } from '../components/ui';
import { CheckField, OtpInput, PasswordField, PhoneField, ResendButton, SelectField, TextField } from '../components/fields';
import { Captcha } from '../components/Captcha';

const homeFor = (a: { isAdmin: boolean; isPromoter: boolean; workspace: any }) => (a.workspace ? '/' : a.isAdmin ? '/admin' : a.isPromoter ? '/earn' : '/');

export function Login() {
  const { t } = useTranslation();
  const auth = useAuth();
  const nav = useNavigate();
  const loc = useLocation() as any;
  const [mode, setMode] = useState<'password' | 'otp'>('password');
  const [step, setStep] = useState<'creds' | 'otp'>('creds');
  const [picker, setPicker] = useState<any[] | null>(null);
  const [lockedMsg, setLockedMsg] = useState('');
  const schema = z.object({ identifier: z.string().trim().min(1, 'login.identifier'), password: z.string().optional(), otp: z.string().optional(), rememberMe: z.boolean().optional() });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { identifier: '', password: '', otp: '', rememberMe: false } });

  const done = (body: any) => {
    const list = body.workspaces ?? [];
    if (list.length > 1) setPicker(list);
    else nav(loc.state?.from ?? (body.workspace ? '/' : body.user.adminRole !== 'NONE' ? '/admin' : body.user.accountType !== 'BUSINESS' ? '/earn' : '/'), { replace: true });
  };

  const sendOtp = async () => {
    const id = form.getValues('identifier');
    if (!id.trim()) { form.setError('identifier', { message: 'Enter your email or mobile number.' }); return; }
    const r = await api.post('/api/v1/auth/otp/send', { target: id.trim(), purpose: 'LOGIN' });
    if (r.devOtp) toast.info(`Dev OTP: ${r.devOtp}`);
    setStep('otp');
  };

  const submit = useSubmit(form, async (v) => {
    setLockedMsg('');
    if (mode === 'otp' && step === 'creds') { await sendOtp(); return; }
    if (mode === 'password' && !v.password) { form.setError('password', { message: 'Enter your password.' }); return; }
    try {
      const body = await auth.login({ identifier: v.identifier, password: mode === 'password' ? v.password : undefined, otp: v.otp || undefined, rememberMe: v.rememberMe });
      if (body.otpRequired) { if (body.devOtp) toast.info(`Dev OTP: ${body.devOtp}`); setStep('otp'); return; }
      done(body);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'ACCOUNT_LOCKED') setLockedMsg(e.message);
      throw e;
    }
  });

  return (
    <Card className="!p-6">
      <h1 className="mb-4 text-xl font-bold">{t('auth.login')}</h1>
      {lockedMsg && <div className="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-700" role="alert">{lockedMsg}</div>}
      <form onSubmit={submit} noValidate>
        <TextField form={form} name="identifier" label={t('auth.identifier')} autoComplete="username" required />
        {mode === 'password' && step === 'creds' && <PasswordField form={form} name="password" label={t('auth.password')} autoComplete="current-password" />}
        {step === 'otp' && (
          <div className="mb-3"><label className="label">One-time code</label>
            <OtpInput value={form.watch('otp') ?? ''} onChange={(v) => form.setValue('otp', v)} onComplete={() => submit()} />
            {mode === 'otp' && <div className="mt-2"><ResendButton onResend={sendOtp} label={(s) => t('auth.resendIn', { s })} resendLabel={t('auth.resend')} /></div>}
          </div>
        )}
        <div className="mb-4 flex items-center justify-between">
          <CheckField form={form} name="rememberMe" label={t('auth.remember')} />
          <Link to="/forgot" className="mb-3 text-sm font-semibold text-brand">{t('auth.forgot')}</Link>
        </div>
        <Button type="submit" className="w-full" busy={form.formState.isSubmitting}>{mode === 'otp' && step === 'creds' ? t('auth.sendCode') : t('auth.login')}</Button>
      </form>
      <button className="mt-3 w-full text-center text-sm font-semibold text-brand" onClick={() => { setMode(mode === 'password' ? 'otp' : 'password'); setStep('creds'); }}>
        {mode === 'password' ? t('auth.otpLogin') : t('auth.passwordLogin')}
      </button>
      <p className="muted mt-4 text-center text-sm"><Link to="/signup" className="font-semibold text-brand">{t('auth.noAccount')}</Link></p>
      <Modal open={!!picker} onClose={() => setPicker(null)} title={t('auth.workspacePicker')}>
        <div className="flex flex-col gap-2">
          {picker?.map((w) => (
            <button key={w.id} className="btn-ghost !justify-between" onClick={async () => { await auth.switchWorkspace(w.id); nav('/', { replace: true }); }}>
              <span>{w.name}</span><span className="muted text-xs">{w.role}</span>
            </button>
          ))}
        </div>
      </Modal>
    </Card>
  );
}

const signupSchema = z.object({
  fullName: zName, businessName: zBizName, businessType: z.enum(BUSINESS_TYPES, { errorMap: () => ({ message: 'Choose your business type.' }) }),
  phone: zPhone, email: zEmail, password: zPassword, confirmPassword: z.string().min(1, 'vr.required'), city: zCity, state: z.string().min(1, 'Choose your state.'),
  acceptTerms: z.literal(true, { errorMap: () => ({ message: 'terms.required' }) }), captchaToken: z.string().min(1, 'Please complete the check.'),
}).refine((v) => v.password === v.confirmPassword, { path: ['confirmPassword'], message: 'password.mismatch' });

export function Signup() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const form = useForm<z.infer<typeof signupSchema>>({
    resolver: zodResolver(signupSchema), mode: 'onBlur',
    defaultValues: { fullName: '', businessName: '', businessType: undefined as any, phone: '', email: '', password: '', confirmPassword: '', city: '', state: 'Tamil Nadu', acceptTerms: undefined as any, captchaToken: '' },
  });
  const phone = useDebounced(form.watch('phone'), 400);
  const email = useDebounced(form.watch('email'), 400);
  // duplicate checks on blur (debounced 400 ms) and again on submit (server)
  useEffect(() => {
    if (!phone && !email) return;
    api.get(`/api/v1/auth/availability${qs({ phone: /^\+?\d{10,14}$/.test(phone.replace(/\s/g, '')) ? phone : undefined, email: /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) ? email : undefined })}`).then((r) => {
      if (r.phoneTaken) form.setError('phone', { message: 'This number is already registered. Log in instead.' });
      if (r.emailTaken) form.setError('email', { message: 'This email is already registered.' });
    }).catch(() => undefined);
  }, [phone, email, form]);
  const onToken = useCallback((tok: string) => form.setValue('captchaToken', tok), [form]);

  const submit = useSubmit(form, async (v) => {
    const { confirmPassword, ...rest } = v;
    const r = await api.post('/api/v1/auth/signup', { ...rest, confirmPassword });
    nav('/verify', { state: { phone: v.phone, signupToken: r.signupToken, sentTo: r.otpSentTo, devOtp: r.devOtp } });
  });

  return (
    <Card className="!p-6">
      <h1 className="mb-1 text-xl font-bold">{t('auth.signup')}</h1>
      <p className="muted mb-4 text-sm">14-day free trial. No card needed.</p>
      <form onSubmit={submit} noValidate>
        <TextField form={form} name="fullName" label={t('auth.fullName')} autoComplete="name" required />
        <TextField form={form} name="businessName" label={t('auth.businessName')} autoComplete="organization" required />
        <SelectField form={form} name="businessType" label={t('auth.businessType')} required placeholder="Choose…" options={BUSINESS_TYPES.map((b) => ({ value: b, label: b.replace('_', ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) }))} />
        <PhoneField form={form} name="phone" label={t('common.phone')} required />
        <TextField form={form} name="email" label={t('common.email')} type="email" autoComplete="email" required />
        <PasswordField form={form} name="password" label={t('auth.password')} meter />
        <PasswordField form={form} name="confirmPassword" label={t('auth.confirmPassword')} />
        <div className="grid grid-cols-1 gap-x-3 sm:grid-cols-2">
          <TextField form={form} name="city" label={t('common.city')} autoComplete="address-level2" required />
          <SelectField form={form} name="state" label="State" required options={STATES.map((s) => ({ value: s, label: s }))} />
        </div>
        <CheckField form={form} name="acceptTerms" label={<>{t('auth.terms')}</>} />
        <Captcha onToken={onToken} />
        {form.formState.errors.captchaToken && <p className="err mb-2">{form.formState.errors.captchaToken.message}</p>}
        <Button type="submit" className="w-full" busy={form.formState.isSubmitting}>{t('auth.sendCode')}</Button>
      </form>
      <p className="muted mt-4 text-center text-sm"><Link to="/login" className="font-semibold text-brand">{t('auth.haveAccount')}</Link></p>
    </Card>
  );
}

export function Verify() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const auth = useAuth();
  const { state } = useLocation() as any;
  const [otp, setOtp] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (!state?.signupToken) nav('/signup', { replace: true }); if (state?.devOtp) toast.info(`Dev OTP: ${state.devOtp}`); }, [state, nav]);
  if (!state?.signupToken) return null;

  const verify = async (code: string) => {
    if (busy) return;
    setBusy(true); setErr('');
    try {
      const body = await api.post('/api/v1/auth/otp/verify', { phone: state.phone, otp: code, signupToken: state.signupToken });
      auth.applyAuth(body);
      nav('/onboarding', { replace: true });
    } catch (e) {
      setErr(errText(e)); setOtp('');
      if (e instanceof ApiError && e.code !== 'OTP_INVALID') toast.error(e.message);
    } finally { setBusy(false); }
  };

  return (
    <Card className="!p-6">
      <h1 className="mb-1 text-xl font-bold">{t('auth.otpTitle')}</h1>
      <p className="muted mb-5 text-sm">{t('auth.otpHelp', { to: state.sentTo })}</p>
      <OtpInput value={otp} onChange={setOtp} onComplete={verify} disabled={busy} error={err} />
      <div className="mt-4 flex items-center justify-between">
        <ResendButton onResend={async () => { const r = await api.post('/api/v1/auth/signup/resend', { signupToken: state.signupToken }); if (r.devOtp) toast.info(`Dev OTP: ${r.devOtp}`); }}
          label={(s) => t('auth.resendIn', { s })} resendLabel={t('auth.resend')} />
        <Button busy={busy} disabled={otp.length < 6} onClick={() => verify(otp)}>{t('auth.verify')}</Button>
      </div>
    </Card>
  );
}

export function ForgotReset() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [step, setStep] = useState<'ask' | 'reset'>('ask');
  const schema = z.object({ identifier: z.string().trim().min(1, 'vr.required'), otp: z.string().optional(), newPassword: z.string().optional(), confirmPassword: z.string().optional() });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { identifier: '', otp: '', newPassword: '', confirmPassword: '' } });
  const ask = async () => {
    const r = await api.post('/api/v1/auth/otp/send', { target: form.getValues('identifier').trim(), purpose: 'RESET_PASSWORD' });
    toast.info(r.message); if (r.devOtp) toast.info(`Dev OTP: ${r.devOtp}`);
    setStep('reset');
  };
  const submit = useSubmit(form, async (v) => {
    if (step === 'ask') return ask();
    const errs: [any, string][] = [];
    if (!zOtp.safeParse(v.otp).success) errs.push(['otp', 'vr.otp.invalid']);
    if (!zPassword.safeParse(v.newPassword).success) errs.push(['newPassword', 'vr.password.weak']);
    else if (v.newPassword !== v.confirmPassword) errs.push(['confirmPassword', 'password.mismatch']);
    if (errs.length) { errs.forEach(([f, m]) => form.setError(f, { message: m })); return; }
    await api.post('/api/v1/auth/password/reset', v);
    toast.success('Password changed. Log in with your new password.');
    nav('/login');
  });
  return (
    <Card className="!p-6">
      <h1 className="mb-1 text-xl font-bold">{t('auth.reset')}</h1>
      <p className="muted mb-4 text-sm">{step === 'ask' ? "Enter your email or mobile. If an account exists, we'll send a code." : 'Enter the code and choose a new password. Other devices will be logged out.'}</p>
      <form onSubmit={submit} noValidate>
        <TextField form={form} name="identifier" label={t('auth.identifier')} required disabled={step === 'reset'} />
        {step === 'reset' && <>
          <div className="mb-3"><label className="label">One-time code</label><OtpInput value={form.watch('otp') ?? ''} onChange={(v) => form.setValue('otp', v)} />
            {form.formState.errors.otp && <p className="err">{t(form.formState.errors.otp.message as string)}</p>}</div>
          <PasswordField form={form} name="newPassword" label={t('auth.newPassword')} meter />
          <PasswordField form={form} name="confirmPassword" label={t('auth.confirmPassword')} />
        </>}
        <Button type="submit" className="w-full" busy={form.formState.isSubmitting}>{step === 'ask' ? t('auth.sendCode') : t('auth.reset')}</Button>
      </form>
      <p className="mt-4 text-center text-sm"><Link to="/login" className="font-semibold text-brand">{t('auth.login')}</Link></p>
    </Card>
  );
}

export function InviteAccept() {
  const [sp] = useSearchParams();
  const auth = useAuth();
  const nav = useNavigate();
  const token = sp.get('token') ?? '';
  const schema = z.object({ fullName: z.string().optional(), phone: z.string().optional(), password: z.string().optional() });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { fullName: '', phone: '', password: '' } });
  const submit = useSubmit(form, async (v) => {
    const body = await api.post('/api/v1/auth/invite/accept', { token, ...v });
    auth.applyAuth(body);
    nav('/', { replace: true });
  });
  return (
    <Card className="!p-6">
      <h1 className="mb-1 text-xl font-bold">Join your team</h1>
      <p className="muted mb-4 text-sm">New to Marketing Panel? Fill in your details. If you already have an account with this email, leave them blank.</p>
      <form onSubmit={submit} noValidate>
        <TextField form={form} name="fullName" label="Full name" />
        <PhoneField form={form} name="phone" label="Mobile" />
        <PasswordField form={form} name="password" label="Password" meter />
        <Button type="submit" className="w-full" busy={form.formState.isSubmitting}>Accept invite</Button>
      </form>
    </Card>
  );
}
