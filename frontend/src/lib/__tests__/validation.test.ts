import { describe, expect, it } from 'vitest';
import { isIndianMobile, passwordStrength, zBizName, zGstin, zHttps, zName, zPassword, zPincode } from '../validation';
import { inr, istLocalToIso, maskPhone } from '../format';
import { variableProblem } from '../../pages/whatsapp';

describe('validation', () => {
  it('accepts Indian mobiles in common formats and rejects others', () => {
    expect(isIndianMobile('9876543210')).toBe(true);
    expect(isIndianMobile('+91 98765 43210')).toBe(true);
    expect(isIndianMobile('1234567890')).toBe(false);
    expect(isIndianMobile('98765')).toBe(false);
  });
  it('person names disallow digits, business names allow them', () => {
    expect(zName.safeParse('Priya  Kumar').success).toBe(true);
    expect(zName.safeParse('Priya2').success).toBe(false);
    expect(zBizName.safeParse('Shop 24x7').success).toBe(true);
  });
  it('validates GSTIN, pincode and https urls', () => {
    expect(zGstin.safeParse('33AAAAA0000A1Z5').success).toBe(true);
    expect(zGstin.safeParse('33AAAAA0000A1X5').success).toBe(false);
    expect(zPincode.safeParse('600001').success).toBe(true);
    expect(zPincode.safeParse('012345').success).toBe(false);
    expect(zHttps.safeParse('https://example.com/x').success).toBe(true);
    expect(zHttps.safeParse('http://example.com').success).toBe(false);
    expect(zHttps.safeParse('https://127.0.0.1/x').success).toBe(false);
  });
  it('requires strong passwords', () => {
    expect(zPassword.safeParse('Passw0rd!').success).toBe(true);
    expect(zPassword.safeParse('password').success).toBe(false);
    expect(passwordStrength('Passw0rd!x')).toBeGreaterThan(passwordStrength('abc'));
  });
});

describe('format', () => {
  it('formats rupees in Indian grouping', () => expect(inr(123456.5)).toContain('1,23,456.50'));
  it('converts IST local input to UTC', () => expect(istLocalToIso('2026-01-01T10:00')).toBe('2026-01-01T04:30:00.000Z'));
  it('masks phone numbers', () => expect(maskPhone('+919876543210')).not.toContain('98765432'));
});

describe('template variables', () => {
  it('flags non-sequential or adjacent variables', () => {
    expect(variableProblem('Hi {{1}}, your code is {{2}}. Thanks')).toBeNull();
    expect(variableProblem('Hi {{2}}')).not.toBeNull();
    expect(variableProblem('{{1}}{{2}}')).not.toBeNull();
  });
});
