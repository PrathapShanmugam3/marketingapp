import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, qs } from '../lib/api';
import { fmtDate, inr, num, todayIst } from '../lib/format';
import { Alert, Card, DataTable, Loading, PageHeader, StatCard } from '../components/ui';

const addDays = (iso: string, d: number) => { const x = new Date(iso + 'T00:00:00Z'); x.setUTCDate(x.getUTCDate() + d); return x.toISOString().slice(0, 10); };
const rate = (a: number, b: number) => (b ? `${((a * 100) / b).toFixed(1)}%` : '—');

/** One page for every channel: reach, leads, revenue, ads and retention. */
export function Analytics() {
  const [to, setTo] = useState(todayIst());
  const [from, setFrom] = useState(addDays(todayIst(), -29));
  const invalid = from > to || (new Date(to).getTime() - new Date(from).getTime()) / 86400000 > 365;
  const q = useQuery({ queryKey: ['analytics', from, to], queryFn: () => api.get(`/api/v1/analytics/overview${qs({ from, to })}`), enabled: !invalid });
  const d = q.data;
  return (
    <>
      <PageHeader title="Analytics" subtitle="All channels in one place" actions={<div className="flex items-center gap-2"><input type="date" className="input !w-auto" aria-label="From" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /><span>–</span><input type="date" className="input !w-auto" aria-label="To" value={to} max={todayIst()} onChange={(e) => setTo(e.target.value)} /></div>} />
      {invalid && <Alert tone="red">Choose a range of up to one year, with the start before the end.</Alert>}
      {q.isLoading && !invalid && <Loading />}
      {d && <Body d={d} />}
    </>
  );
}

function Body({ d }: { d: any }) {
  const ch = d.channels; const l = d.leads; const a = d.ads; const r = d.retention; const b = d.blended;
  return (
    <>
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="New leads" value={num(l.total)} sub={`${l.won} won · ${l.winRate}%`} /><StatCard label="Revenue won" value={inr(l.wonValue, 0)} sub={`${inr(l.pipelineValue, 0)} in pipeline`} />
        <StatCard label="Link clicks" value={num(d.traffic.linkClicks)} sub={`${num(d.traffic.blogViews)} blog views`} /><StatCard label="Ad spend" value={inr(a.spend, 0)} sub={a.roas ? `${a.roas}x return` : undefined} />
      </div>
      <Card title="Activity by day">
        <div style={{ height: 280 }}><ResponsiveContainer><LineChart data={d.series}><CartesianGrid strokeDasharray="3 3" stroke="var(--border)" /><XAxis dataKey="day" tickFormatter={(x) => fmtDate(x).slice(0, 6)} minTickGap={24} /><YAxis allowDecimals={false} /><Tooltip /><Legend />
          <Line type="monotone" dataKey="whatsapp" name="WhatsApp" stroke="#12A388" dot={false} strokeWidth={2} /><Line type="monotone" dataKey="email" name="E-mail" stroke="#3B82F6" dot={false} strokeWidth={2} /><Line type="monotone" dataKey="sms" name="SMS" stroke="#F59E0B" dot={false} strokeWidth={2} />
          <Line type="monotone" dataKey="leads" name="Leads" stroke="#8B5CF6" dot={false} strokeWidth={2} /><Line type="monotone" dataKey="clicks" name="Link clicks" stroke="#EF4444" dot={false} strokeWidth={2} /></LineChart></ResponsiveContainer></div>
      </Card>
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card title="Messages by channel"><DataTable rows={[
          { id: 1, name: 'WhatsApp', sent: ch.whatsapp.sent, extra: `${rate(ch.whatsapp.read, ch.whatsapp.sent)} read · ${num(ch.whatsapp.replies)} replies`, cost: null },
          { id: 2, name: 'E-mail', sent: ch.email.sent, extra: `${num(ch.email.unsubscribed)} unsubscribed`, cost: null },
          { id: 3, name: 'SMS', sent: ch.sms.sent, extra: `${num(ch.sms.failed)} failed`, cost: ch.sms.spend },
          { id: 4, name: 'Push', sent: ch.push.sent, extra: '', cost: null },
          { id: 5, name: 'Social posts', sent: ch.social.posts, extra: `${num(ch.social.dmsReceived)} DMs received`, cost: null },
        ]} columns={[{ key: 'n', header: 'Channel', render: (x: any) => <b>{x.name}</b> }, { key: 's', header: 'Sent', render: (x: any) => num(x.sent) }, { key: 'e', header: 'Result', render: (x: any) => <span className="text-xs">{x.extra}</span> }, { key: 'c', header: 'Cost', render: (x: any) => (x.cost ? inr(x.cost) : '—') }]} /></Card>
        <Card title="Leads by source"><DataTable rows={l.bySource.map((x: any) => ({ ...x, id: x.source }))} empty="No leads in this period." columns={[
          { key: 's', header: 'Source', render: (x: any) => <b>{x.source.replace('_', ' ')}</b> }, { key: 'l', header: 'Leads', render: (x: any) => num(x.leads) }, { key: 'w', header: 'Won', render: (x: any) => num(x.won) }, { key: 'v', header: 'Won value', render: (x: any) => inr(x.wonValue, 0) },
        ]} /></Card>
        <Card title="Ads"><DataTable rows={a.byPlatform.map((x: any) => ({ ...x, id: x.platform }))} empty="No ad numbers in this period." columns={[
          { key: 'p', header: 'Platform', render: (x: any) => <b>{x.platform}</b> }, { key: 's', header: 'Spend', render: (x: any) => inr(x.spend, 0) }, { key: 'c', header: 'Clicks', render: (x: any) => num(x.clicks) }, { key: 'a', header: 'Cost / result', render: (x: any) => (x.cpa ? inr(x.cpa) : '—') }, { key: 'r', header: 'ROAS', render: (x: any) => (x.roas ? `${x.roas}x` : '—') },
        ]} />
          <p className="muted mt-3 text-sm">Blended cost per lead: <b>{b.costPerLead ? inr(b.costPerLead) : '—'}</b> · revenue won per ₹1 of ads: <b>{b.revenueToSpend ?? '—'}</b></p></Card>
        <Card title="Customers and loyalty"><dl className="grid grid-cols-2 gap-3 text-sm">
          <div><dt className="muted">Contacts</dt><dd className="text-xl font-bold">{num(r.contacts)}</dd><dd className="muted text-xs">{num(r.optedIn)} opted in · {num(r.newContacts)} new</dd></div>
          <div><dt className="muted">Google rating</dt><dd className="text-xl font-bold">{r.googleRating ? `${r.googleRating.rating} ★` : '—'}</dd><dd className="muted text-xs">{r.googleRating ? `${num(r.googleRating.reviewCount)} reviews` : ''}</dd></div>
          <div><dt className="muted">Coupons used</dt><dd className="text-xl font-bold">{num(r.couponRedemptions)}</dd><dd className="muted text-xs">{inr(r.couponDiscount, 0)} off on {inr(r.couponOrderValue, 0)}</dd></div>
          <div><dt className="muted">Loyalty points</dt><dd className="text-xl font-bold">{num(r.pointsIssued)}</dd><dd className="muted text-xs">{num(r.pointsRedeemed)} redeemed · {num(r.referrals)} referrals</dd></div></dl></Card>
        <Card title="Top short links" className="lg:col-span-2"><DataTable rows={d.traffic.topLinks.map((x: any) => ({ ...x, id: x.code }))} empty="No clicks in this period." columns={[
          { key: 'c', header: 'Link', render: (x: any) => <code className="text-xs">/l/{x.code}</code> }, { key: 'd', header: 'Destination', render: (x: any) => <span className="muted text-xs">{x.destination}</span> }, { key: 'n', header: 'Clicks', render: (x: any) => num(x.clicks) },
        ]} /></Card>
      </div>
    </>
  );
}
