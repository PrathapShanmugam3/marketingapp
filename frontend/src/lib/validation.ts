import { z } from 'zod';

/**
 * Same rules as the backend (SRS chapter 6). Messages are i18n KEYS; the Field components translate them,
 * so Zod errors and server errors both show in English or Tamil.
 */
const collapse = (s: string) => s.trim().replace(/\s{2,}/g, ' ');

export const isIndianMobile = (v: string) => {
  const s = v.replace(/[\s\-().]/g, '');
  if (/^\+91\d{10}$/.test(s)) return /^[6-9]/.test(s.slice(3));
  if (/^\d{10}$/.test(s)) return /^[6-9]/.test(s);
  if (/^\+(?!91)\d{8,14}$/.test(s)) return true; // other countries: shape only, server checks with libphonenumber
  return false;
};

export const zName = z.string().transform(collapse).pipe(z.string().min(2, 'vr.name.invalid').max(100, 'vr.name.invalid').regex(/^[\p{L}\p{M} .'&-]+$/u, 'vr.name.invalid'));
export const zBizName = z.string().transform(collapse).pipe(z.string().min(2, 'vr.name.invalid').max(100, 'vr.name.invalid').regex(/^[\p{L}\p{M}\p{Nd} .'&,()/-]+$/u, 'vr.name.invalid'));
export const zPhone = z.string().trim().min(1, 'vr.required').refine(isIndianMobile, 'vr.mobile.invalid');
export const zEmail = z.string().trim().toLowerCase().max(150, 'vr.email.invalid').regex(/^[^@\s]+@[^@\s]+\.[^@\s]+$/, 'vr.email.invalid');
export const zEmailOpt = z.string().trim().toLowerCase().max(150).regex(/^$|^[^@\s]+@[^@\s]+\.[^@\s]+$/, 'vr.email.invalid').optional().or(z.literal(''));
export const zPassword = z.string().min(8, 'vr.password.weak').max(64, 'vr.password.weak')
  .refine((v) => v === v.trim(), 'vr.password.weak').refine((v) => /[A-Z]/.test(v) && /[a-z]/.test(v) && /\d/.test(v) && /[^A-Za-z0-9\s]/.test(v), 'vr.password.weak');
export const zOtp = z.string().regex(/^\d{6}$/, 'vr.otp.invalid');
export const zCity = z.string().transform(collapse).pipe(z.string().min(2, 'vr.city.invalid').max(60, 'vr.city.invalid').regex(/^[\p{L}\p{M} .'-]+$/u, 'vr.city.invalid'));
export const zGstin = z.string().trim().toUpperCase().regex(/^$|^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/, 'vr.gstin.invalid').optional().or(z.literal(''));
export const zPincode = z.string().trim().regex(/^$|^[1-9][0-9]{5}$/, 'vr.pincode.invalid').optional().or(z.literal(''));
export const zHttps = z.string().trim().max(500, 'vr.url.invalid').refine((v) => { try { const u = new URL(v); return u.protocol === 'https:' && !/^[\d.]+$/.test(u.hostname) && u.hostname !== 'localhost'; } catch { return false; } }, 'vr.url.invalid');
export const zHttpsOpt = z.string().trim().refine((v) => v === '' || (() => { try { const u = new URL(v); return u.protocol === 'https:'; } catch { return false; } })(), 'vr.url.invalid').optional();
export const zMoney = z.coerce.number({ invalid_type_error: 'vr.money.invalid' }).min(0, 'vr.money.invalid').max(99999999.99, 'vr.money.invalid');
export const zRequired = z.string().trim().min(1, 'vr.required');
export const zText = (max: number) => z.string().trim().max(max, 'vr.size').refine((v) => !/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/.test(v), 'vr.text.unsupported');

export const GST_STATE_CODES: Record<string, string> = {
  'Jammu and Kashmir': '01', 'Himachal Pradesh': '02', Punjab: '03', Chandigarh: '04', Uttarakhand: '05', Haryana: '06', Delhi: '07', Rajasthan: '08', 'Uttar Pradesh': '09', Bihar: '10', Sikkim: '11',
  'Arunachal Pradesh': '12', Nagaland: '13', Manipur: '14', Mizoram: '15', Tripura: '16', Meghalaya: '17', Assam: '18', 'West Bengal': '19', Jharkhand: '20', Odisha: '21', Chhattisgarh: '22',
  'Madhya Pradesh': '23', Gujarat: '24', 'Dadra and Nagar Haveli and Daman and Diu': '26', Maharashtra: '27', Karnataka: '29', Goa: '30', Lakshadweep: '31', Kerala: '32', 'Tamil Nadu': '33',
  Puducherry: '34', 'Andaman and Nicobar Islands': '35', Telangana: '36', 'Andhra Pradesh': '37', Ladakh: '38',
};
export const STATES = Object.keys(GST_STATE_CODES);

export const BUSINESS_TYPES = ['RETAIL', 'CLINIC', 'SALON', 'EDUCATION', 'REAL_ESTATE', 'RESTAURANT', 'AGENCY', 'OTHER'] as const;

/** Password strength 0-4 for the meter on sign-up. */
export const passwordStrength = (v: string) => {
  let s = 0;
  if (v.length >= 8) s++;
  if (/[A-Z]/.test(v) && /[a-z]/.test(v)) s++;
  if (/\d/.test(v)) s++;
  if (/[^A-Za-z0-9\s]/.test(v)) s++;
  return s;
};
