import { useEffect, useState } from 'react';
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { ApiError } from './api';
import { toast } from './toast';

/** Debounced value (e.g. 400 ms duplicate checks on blur). */
export function useDebounced<T>(value: T, ms = 400) {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

/**
 * Submit helper: disables double submit while the request is in flight, shows a toast plus field errors from the server
 * (SRS 6.1) and focuses the first invalid field.
 */
export function useSubmit<T extends FieldValues>(form: UseFormReturn<T>, fn: (values: T) => Promise<unknown>, opts?: { success?: string }) {
  const { t } = useTranslation();
  return form.handleSubmit(async (values) => {
    try {
      await fn(values);
      if (opts?.success) toast.success(opts.success);
    } catch (e) {
      if (e instanceof ApiError) {
        let focused = false;
        for (const fe of e.fieldErrors) {
          try {
            form.setError(fe.field as Path<T>, { type: 'server', message: fe.message || fe.code });
            if (!focused) { form.setFocus(fe.field as Path<T>); focused = true; }
          } catch { /* field not on this form */ }
        }
        toast.error(e.message);
      } else {
        toast.error(t('common.error'));
      }
    }
  }, (errors) => {
    const first = Object.keys(errors)[0];
    if (first) form.setFocus(first as Path<T>);
  });
}

export const errText = (e: unknown) => (e instanceof ApiError ? e.message : 'Something went wrong. Please try again.');

export function useCountdown(seconds: number) {
  const [left, setLeft] = useState(seconds);
  useEffect(() => {
    if (left <= 0) return;
    const t = setTimeout(() => setLeft((x) => x - 1), 1000);
    return () => clearTimeout(t);
  }, [left]);
  return [left, () => setLeft(seconds)] as const;
}
