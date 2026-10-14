import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, qs } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, inr, num, pct, todayIst } from '../lib/format';
import { Alert, Card, Loading, PageHeader, StatCard } from '../components/ui';

const addDays = (iso: string, d: number) => { const x = new Date(iso + 'T00:00:00Z'); x.setUTCDate(x.getUTCDate() + d); return x.toISOString().slice(0, 10); };

export function Dashboard() {
  const { t } = useTranslation();
  const { workspace, can } = useAuth();
  const [to, setTo] = useState(todayIst());
  const [from, setFrom] = useState(addDays(todayIst(), -29));
  const invalid = from > to || to > todayIst() || (new Date(to).getTime() - new Date(from).getTime()) / 86400000 > 365;
  const q = useQuery({ queryKey: ['dashboard', from, to], queryFn: () => api.get(`/api/v1/dashboard${qs({ from, to })}`), enabled: !invalid });
  const d = q.data;
  const ob = d?.onboarding ?? {};
  const steps = [
    { done: ob.whatsappConnected, label: 'Connect WhatsApp', to: '/settings/whatsapp' },
    { done: ob.contactsImported, label: 'Add or import contacts', to: '/contacts/import' },
    { done: ob.firstCampaign, label: 'Send your first campaign', to: '/campaigns/new' },
  ];
  const showOnboarding = d && !(ob.whatsappConnected && ob.contactsImported && ob.firstCampaign);

  return (
    <>
      <PageHeader title={t('nav.dashboard')} subtitle={workspace?.status === 'TRIAL' && workspace.trialEndsAt ? `Free trial ends on ${fmtDate(workspace.trialEndsAt)}` : undefined}
        actions={<div className="flex flex-wrap items-center gap-2 text-sm">
          <label className="sr-only" htmlFor="df">From</label><input id="df" type="date" className="input !w-auto !py-1.5" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          <span className="muted">→</span>
          <label className="sr-only" htmlFor="dt">To</label><input id="dt" type="date" className="input !w-auto !py-1.5" value={to} max={todayIst()} onChange={(e) => setTo(e.target.value)} />
        </div>} />
      {invalid && <Alert tone="red">Choose a range of up to 1 year, not in the future.</Alert>}
      {d?.lowWallet && <Alert tone="amber" action={can.owner ? <Link className="btn-primary !py-1.5" to="/settings/billing">Top up</Link> : undefined}>{t('dash.lowWallet')}</Alert>}
      {showOnboarding && (
        <Card title={t('dash.onboarding')} className="mb-4">
          <ol className="grid gap-2 sm:grid-cols-3">
            {steps.map((s, i) => (
              <li key={i}><Link to={s.to} className="flex items-center gap-3 rounded-xl border p-3 hover:bg-black/5" style={{ borderColor: 'var(--border)' }}>
                <span className={`flex h-7 w-7 items-center justify-center rounded-full text-sm font-bold ${s.done ? 'bg-brand text-white' : 'border'}`}>{s.done ? '✓' : i + 1}</span>
                <span className={`text-sm font-medium ${s.done ? 'line-through opacity-60' : ''}`}>{s.label}</span></Link></li>
            ))}
          </ol>
        </Card>
      )}
      {q.isLoading && !invalid ? <Loading /> : d && (
        <>
          <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label={t('dash.messages')} value={num(d.messagesSent)} sub={`${d.campaigns} campaigns`} />
            <StatCard label={t('dash.delivery')} value={pct(d.deliveryRate)} sub={`Read rate ${pct(d.readRate)}`} />
            <StatCard label={t('dash.leads')} value={num(d.leads.new)} sub={`${d.leads.open} open${d.leads.overdueFollowUps ? ` · ${d.leads.overdueFollowUps} overdue` : ''}`} tone={d.leads.overdueFollowUps ? 'warn' : undefined} />
            <StatCard label={t('dash.wallet')} value={inr(d.walletBalance)} sub={`${d.reviews.requests} review requests · ${d.reviews.clicked} opened`} tone={d.lowWallet ? 'warn' : undefined} />
          </div>
          <Card title="Messages by day">
            {d.sentByDay.length === 0 ? <p className="muted p-6 text-center text-sm">No campaigns sent in this period.</p> : (
              <div style={{ height: 260 }}>
                <ResponsiveContainer>
                  <BarChart data={d.sentByDay.map((r: any) => ({ day: fmtDate(r.day).slice(0, 6), sent: Number(r.sent), delivered: Number(r.delivered) }))}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" /><XAxis dataKey="day" fontSize={12} /><YAxis fontSize={12} allowDecimals={false} /><Tooltip /><Legend />
                    <Bar dataKey="sent" fill="#0B1A33" name="Sent" radius={[4, 4, 0, 0]} /><Bar dataKey="delivered" fill="#12A388" name="Delivered" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </Card>
          {d.reviews.latest && <Card className="mt-4" title="Google rating"><div className="text-3xl font-bold">{d.reviews.latest.rating} <span className="text-amber-500">★</span></div><div className="muted text-sm">{num(d.reviews.latest.reviewCount)} reviews · updated {fmtDate(d.reviews.latest.capturedOn)}</div></Card>}
        </>
      )}
    </>
  );
}
