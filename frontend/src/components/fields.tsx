import { useEffect, useId, useRef, useState, type ClipboardEvent, type KeyboardEvent, type ReactNode } from 'react';
import type { UseFormReturn } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { api } from '../lib/api';
import { passwordStrength } from '../lib/validation';
import { toast } from '../lib/toast';
import { errText } from '../lib/hooks';

/** Translates a Zod message key; server messages (already localised) pass through. */
export function useErrText() {
  const { t } = useTranslation();
  return (msg?: string) => (msg && /^[a-z]+(\.[A-Za-z]+)+$/.test(msg) ? t(msg) : msg);
}

interface Base { form: UseFormReturn<any>; name: string; label?: ReactNode; hint?: ReactNode; required?: boolean }

function Wrap({ id, label, hint, required, error, children }: { id: string; label?: ReactNode; hint?: ReactNode; required?: boolean; error?: string; children: ReactNode }) {
  return (
    <div className="mb-3">
      {label && <label htmlFor={id} className="label">{label}{required && <span className="text-red-600"> *</span>}</label>}
      {children}
      {hint && !error && <p className="muted mt-1 text-xs">{hint}</p>}
      {error && <p id={`${id}-err`} className="err" role="alert">{error}</p>}
    </div>
  );
}

const errOf = (form: UseFormReturn<any>, name: string): string | undefined => {
  const parts = name.split('.');
  let e: any = form.formState.errors;
  for (const p of parts) e = e?.[p];
  return e?.message as string | undefined;
};

export function TextField({ form, name, label, hint, required, type = 'text', placeholder, maxLength, autoComplete, inputMode, onBlurExtra, disabled }:
  Base & { type?: string; placeholder?: string; maxLength?: number; autoComplete?: string; inputMode?: 'text' | 'numeric' | 'tel' | 'email' | 'decimal'; onBlurExtra?: (v: string) => void; disabled?: boolean }) {
  const id = useId();
  const et = useErrText();
  const error = et(errOf(form, name));
  const reg = form.register(name, { onBlur: (e) => onBlurExtra?.(e.target.value) });
  return (
    <Wrap id={id} label={label} hint={hint} required={required} error={error}>
      <input id={id} className="input" type={type} placeholder={placeholder} maxLength={maxLength} autoComplete={autoComplete} inputMode={inputMode} disabled={disabled}
        aria-invalid={!!error} aria-describedby={error ? `${id}-err` : undefined} {...reg} />
    </Wrap>
  );
}

export function TextAreaField({ form, name, label, hint, required, rows = 3, maxLength, placeholder }: Base & { rows?: number; maxLength?: number; placeholder?: string }) {
  const id = useId();
  const et = useErrText();
  const error = et(errOf(form, name));
  const value: string = form.watch(name) ?? '';
  return (
    <Wrap id={id} label={label} hint={hint} required={required} error={error}>
      <textarea id={id} className="input" rows={rows} maxLength={maxLength} placeholder={placeholder} aria-invalid={!!error} {...form.register(name)} />
      {maxLength && <p className="muted mt-1 text-right text-xs">{value.length}/{maxLength}</p>}
    </Wrap>
  );
}

export function SelectField({ form, name, label, hint, required, options, placeholder }: Base & { options: { value: string | number; label: string }[]; placeholder?: string }) {
  const id = useId();
  const et = useErrText();
  const error = et(errOf(form, name));
  return (
    <Wrap id={id} label={label} hint={hint} required={required} error={error}>
      <select id={id} className="input" aria-invalid={!!error} {...form.register(name)}>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </Wrap>
  );
}

export function CheckField({ form, name, label }: Base) {
  const id = useId();
  const et = useErrText();
  const error = et(errOf(form, name));
  return (
    <div className="mb-3">
      <label htmlFor={id} className="flex cursor-pointer items-start gap-2 text-sm">
        <input id={id} type="checkbox" className="mt-1 h-4 w-4 accent-[#12A388]" aria-invalid={!!error} {...form.register(name)} />
        <span>{label}</span>
      </label>
      {error && <p className="err" role="alert">{error}</p>}
    </div>
  );
}

export function PasswordField({ form, name, label, meter, autoComplete = 'new-password' }: Base & { meter?: boolean; autoComplete?: string }) {
  const id = useId();
  const et = useErrText();
  const [show, setShow] = useState(false);
  const error = et(errOf(form, name));
  const value: string = form.watch(name) ?? '';
  const s = passwordStrength(value);
  return (
    <Wrap id={id} label={label} error={error}>
      <div className="relative">
        <input id={id} className="input pr-16" type={show ? 'text' : 'password'} autoComplete={autoComplete} aria-invalid={!!error} {...form.register(name)} />
        <button type="button" onClick={() => setShow((x) => !x)} className="absolute inset-y-0 right-2 px-2 text-xs font-semibold text-brand" aria-label="Show or hide password">{show ? 'Hide' : 'Show'}</button>
      </div>
      {meter && value && (
        <div className="mt-2 flex gap-1" aria-label={`Password strength ${s} of 4`}>
          {[1, 2, 3, 4].map((i) => <span key={i} className="h-1.5 flex-1 rounded-full" style={{ background: i <= s ? (s <= 2 ? '#E8B64B' : '#12A388') : 'var(--border)' }} />)}
        </div>
      )}
    </Wrap>
  );
}

const CODES = [['+91', 'IN +91'], ['+1', 'US +1'], ['+44', 'UK +44'], ['+971', 'AE +971'], ['+65', 'SG +65'], ['+61', 'AU +61'], ['+60', 'MY +60'], ['+94', 'LK +94']];

/** Country picker (default +91) + number. The form value is the combined E.164-style string. */
export function PhoneField({ form, name, label, required }: Base) {
  const id = useId();
  const et = useErrText();
  const error = et(errOf(form, name));
  const current: string = form.watch(name) ?? '';
  const code = CODES.map((c) => c[0]).sort((a, b) => b.length - a.length).find((c) => current.startsWith(c)) ?? '+91';
  const digits = current.startsWith('+') ? current.slice(code.length) : current;
  const set = (c: string, d: string) => form.setValue(name, d ? c + d.replace(/[^\d]/g, '') : '', { shouldDirty: true, shouldValidate: form.formState.isSubmitted });
  return (
    <Wrap id={id} label={label} required={required} error={error}>
      <div className="flex gap-2">
        <select aria-label="Country code" className="input w-28 shrink-0" value={code} onChange={(e) => set(e.target.value, digits)}>
          {CODES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
        <input id={id} className="input" inputMode="tel" autoComplete="tel-national" value={digits} aria-invalid={!!error}
          onChange={(e) => set(code, e.target.value)} onBlur={() => form.trigger(name)} placeholder="98765 43210" maxLength={14} />
      </div>
      <input type="hidden" {...form.register(name)} />
    </Wrap>
  );
}

/** Six boxes; auto-submit on the 6th digit; paste supported. */
export function OtpInput({ value, onChange, onComplete, disabled, error }: { value: string; onChange: (v: string) => void; onComplete?: (v: string) => void; disabled?: boolean; error?: string }) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = value.padEnd(6, ' ').split('');
  const set = (i: number, d: string) => {
    const arr = value.padEnd(6, ' ').split('');
    arr[i] = d || ' ';
    const v = arr.join('').replace(/\s+$/, '');
    onChange(v);
    if (d && i < 5) refs.current[i + 1]?.focus();
    if (v.length === 6 && !v.includes(' ')) onComplete?.(v);
  };
  const key = (i: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !digits[i].trim() && i > 0) refs.current[i - 1]?.focus();
  };
  const paste = (e: ClipboardEvent<HTMLInputElement>) => {
    const p = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!p) return;
    e.preventDefault();
    onChange(p);
    refs.current[Math.min(p.length, 5)]?.focus();
    if (p.length === 6) onComplete?.(p);
  };
  return (
    <div>
      <div className="flex justify-between gap-2" role="group" aria-label="One-time code">
        {digits.map((d, i) => (
          <input key={i} ref={(el) => { refs.current[i] = el; }} className="input !px-0 text-center text-xl font-bold" style={{ width: '3rem' }} inputMode="numeric" autoComplete={i === 0 ? 'one-time-code' : 'off'}
            maxLength={1} value={d.trim()} disabled={disabled} aria-label={`Digit ${i + 1}`} aria-invalid={!!error}
            onChange={(e) => set(i, e.target.value.replace(/\D/g, '').slice(-1))} onKeyDown={(e) => key(i, e)} onPaste={paste} />
        ))}
      </div>
      {error && <p className="err" role="alert">{error}</p>}
    </div>
  );
}

/** Uploads through /api/v1/uploads (signature-checked on the server) and writes the URL into the form. */
export function UploadField({ form, name, label, kind = 'image', accept = 'image/jpeg,image/png', hint }: Base & { kind?: string; accept?: string }) {
  const id = useId();
  const [busy, setBusy] = useState(false);
  const et = useErrText();
  const url: string = form.watch(name) ?? '';
  const error = et(errOf(form, name));
  const pick = async (f?: File) => {
    if (!f) return;
    setBusy(true);
    try {
      const r = await api.upload(f, kind);
      form.setValue(name, r.url, { shouldDirty: true });
      form.clearErrors(name);
    } catch (e) { toast.error(errText(e)); } finally { setBusy(false); }
  };
  const isImg = url && /\.(png|jpe?g)$/i.test(url);
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <div className="flex items-center gap-3">
        {isImg && <img src={url} alt="" className="h-14 w-14 rounded-lg border object-cover" style={{ borderColor: 'var(--border)' }} />}
        {url && !isImg && <a href={url} target="_blank" rel="noreferrer" className="text-sm text-brand underline">View file</a>}
        <label className="btn-ghost cursor-pointer" htmlFor={id}>{busy ? '…' : url ? 'Replace' : 'Upload'}</label>
        <input id={id} type="file" accept={accept} className="sr-only" onChange={(e) => pick(e.target.files?.[0])} />
        {url && <button type="button" className="text-sm text-red-600" onClick={() => form.setValue(name, '', { shouldDirty: true })}>Remove</button>}
      </div>
    </Wrap>
  );
}

export function DateTimeField({ form, name, label, hint, required }: Base) {
  return <TextField form={form} name={name} label={label} hint={hint} required={required} type="datetime-local" />;
}

/** Re-sendable code countdown button. */
export function ResendButton({ seconds = 30, onResend, label, resendLabel }: { seconds?: number; onResend: () => Promise<void> | void; label: (s: number) => string; resendLabel: string }) {
  const [left, setLeft] = useState(seconds);
  useEffect(() => { if (left <= 0) return; const t = setTimeout(() => setLeft(left - 1), 1000); return () => clearTimeout(t); }, [left]);
  return (
    <button type="button" className="text-sm font-semibold text-brand disabled:text-gray-400" disabled={left > 0}
      onClick={async () => { await onResend(); setLeft(seconds); }}>
      {left > 0 ? label(left) : resendLabel}
    </button>
  );
}
