const IST = 'Asia/Kolkata';

/** Indian grouping, e.g. 1,00,000. */
export const num = (n: number | string | null | undefined) => (n === null || n === undefined || n === '' ? '0' : new Intl.NumberFormat('en-IN').format(Number(n)));

export const inr = (n: number | string | null | undefined, digits = 2) => {
  const v = Number(n ?? 0);
  return '₹' + new Intl.NumberFormat('en-IN', { minimumFractionDigits: v % 1 === 0 ? 0 : Math.min(digits, 2), maximumFractionDigits: digits }).format(v);
};

/** All timestamps are UTC from the API and shown in IST: DD MMM YYYY. */
export const fmtDate = (iso?: string | null) => {
  if (!iso) return '—';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00+05:30' : iso);
  return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: IST }).format(d);
};

export const fmtDateTime = (iso?: string | null) => {
  if (!iso) return '—';
  const d = new Date(iso);
  const date = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: IST }).format(d);
  const time = new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', hour12: true, timeZone: IST }).format(d);
  return `${date}, ${time}`;
};

/** value for <input type="datetime-local"> in IST from an ISO instant */
export const toIstLocalInput = (iso?: string | null) => {
  if (!iso) return '';
  const d = new Date(iso);
  const parts = new Intl.DateTimeFormat('sv-SE', { timeZone: IST, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(d);
  return parts.replace(' ', 'T');
};

/** datetime-local value (picked in IST) to a UTC ISO instant */
export const istLocalToIso = (v: string) => (v ? new Date(v + ':00+05:30').toISOString() : undefined);

export const todayIst = () => new Intl.DateTimeFormat('sv-SE', { timeZone: IST }).format(new Date());

export const relativeTime = (iso?: string | null) => {
  if (!iso) return '';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return fmtDate(iso);
};

export const pct = (n?: number) => `${(n ?? 0).toFixed(1)}%`;
export const maskPhone = (p?: string) => (p ? p.replace(/(\+\d{2})(\d{4})(\d+)(\d{2})/, '$1 $2•••• $4') : '');
