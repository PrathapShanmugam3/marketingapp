import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDateTime, inr, istLocalToIso, num } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, BackLink, Button, Card, ConfirmDialog, DataTable, Loading, PageHeader, StatCard, StatusBadge, Tabs } from '../components/ui';
import { AudiencePicker, Err, fieldErrors, type Audience } from '../components/common';
import { useTags } from './contacts';

const LABEL: Record<string, string> = { EMAIL: 'E-mail', SMS: 'SMS', PUSH: 'Push' };

/** E-mail, SMS and web-push broadcasts. */
export function Broadcasts() {
  const { hasFeature } = useAuth();
  const nav = useNavigate();
  const [channel, setChannel] = useState('EMAIL');
  const list = useQuery({ queryKey: ['broadcasts', channel], queryFn: () => api.get<any[]>(`/api/v1/broadcasts?channel=${channel}`) });
  const enabled = hasFeature(channel.toLowerCase());
  return (
    <>
      <PageHeader title="E-mail, SMS and push" subtitle="Reach customers outside WhatsApp" actions={<Link to={`/broadcasts/new?channel=${channel}`} className="btn-primary">+ New {LABEL[channel]}</Link>} />
      <Tabs value={channel} onChange={setChannel} tabs={Object.entries(LABEL).map(([k, l]) => ({ key: k, label: l }))} />
      {!enabled && <Alert tone="blue">{LABEL[channel]} broadcasts are part of the Growth plan. Upgrade from Billing to send them.</Alert>}
      <Card className="mt-3"><DataTable rows={list.data} loading={list.isLoading} empty={`No ${LABEL[channel]} broadcasts yet.`} onRowClick={(b: any) => nav(`/broadcasts/${b.id}`)} columns={[
        { key: 'n', header: 'Name', render: (b: any) => <div><b>{b.name}</b><div className="muted max-w-xs truncate text-xs">{b.subject}</div></div> },
        { key: 's', header: 'Status', render: (b: any) => <div><StatusBadge status={b.status} />{b.scheduledAt && b.status === 'SCHEDULED' && <div className="muted text-[11px]">{fmtDateTime(b.scheduledAt)}</div>}</div> },
        { key: 'r', header: 'Sent', render: (b: any) => `${num(b.sentCount)} / ${num(b.totalCount)}` }, { key: 'u', header: 'Unsubscribed', hideOnMobile: true, render: (b: any) => num(b.unsubCount) },
        { key: 'c', header: 'Cost', hideOnMobile: true, render: (b: any) => (Number(b.cost) > 0 ? inr(b.cost) : '—') },
      ]} /></Card>
    </>
  );
}

export function BroadcastEditor() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const qc = useQueryClient();
  const nav = useNavigate();
  const tags = useTags();
  const segments = useQuery({ queryKey: ['segments'], queryFn: () => api.get<any[]>('/api/v1/segments') });
  const existing = useQuery({ queryKey: ['broadcast', id], queryFn: () => api.get(`/api/v1/broadcasts/${id}`), enabled: !isNew, refetchInterval: (q) => (q.state.data?.status === 'SENDING' ? 3000 : false) });
  const recipients = useQuery({ queryKey: ['broadcast-recipients', id], queryFn: () => api.get(`/api/v1/broadcasts/${id}/recipients`), enabled: !isNew && !!existing.data && existing.data.channel !== 'PUSH' && !['DRAFT', 'SCHEDULED'].includes(existing.data.status) });
  const [f, setF] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [test, setTest] = useState('');
  const [when, setWhen] = useState('');
  const [confirm, setConfirm] = useState(false);
  const channel = existing.data?.channel ?? (new URLSearchParams(location.search).get('channel') || 'EMAIL');
  const v = f ?? (isNew ? { name: '', subject: '', body: '', audienceType: 'ALL_OPTED_IN', audience: null } : existing.data && { name: existing.data.name, subject: existing.data.subject ?? '', body: existing.data.body, audienceType: existing.data.audienceType, audience: existing.data.audienceJson });
  const set = (k: string, val: any) => setF({ ...v, [k]: val });
  const editable = isNew || ['DRAFT', 'SCHEDULED'].includes(existing.data?.status);
  const save = useMutation({
    mutationFn: () => (isNew ? api.post('/api/v1/broadcasts', { ...v, channel }) : api.put(`/api/v1/broadcasts/${id}`, { ...v, channel })),
    onSuccess: (r) => { setErrs({}); setF(null); toast.success('Saved'); qc.invalidateQueries({ queryKey: ['broadcasts'] }); qc.invalidateQueries({ queryKey: ['broadcast', id] }); if (isNew) nav(`/broadcasts/${r.id}`, { replace: true }); },
    onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); },
  });
  const act = (path: string, body?: any, ok?: string) => api.post(`/api/v1/broadcasts/${id}/${path}`, body).then((r) => { if (ok) toast.success(ok); qc.invalidateQueries({ queryKey: ['broadcast', id] }); qc.invalidateQueries({ queryKey: ['broadcasts'] }); return r; });
  const send = useMutation({ mutationFn: () => act('send', {}, 'Sending started'), onSuccess: () => setConfirm(false), onError: (e) => { setConfirm(false); toast.error(errText(e)); } });
  const schedule = useMutation({ mutationFn: () => act('schedule', { scheduledAt: istLocalToIso(when) }, 'Scheduled'), onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); } });
  const cancel = useMutation({ mutationFn: () => act('cancel', {}, 'Cancelled'), onError: (e) => toast.error(errText(e)) });
  const sendTest = useMutation({ mutationFn: () => act('test', { to: test }, 'Test sent'), onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); } });
  if (!isNew && existing.isLoading) return <Loading />;
  if (!v) return <Alert tone="red">Not found.</Alert>;
  const e = existing.data;
  const est = e?.estimate;
  const sms = channel === 'SMS';
  return (
    <>
      <BackLink to="/broadcasts">Broadcasts</BackLink>
      <PageHeader title={isNew ? `New ${LABEL[channel]} broadcast` : v.name} subtitle={e && <StatusBadge status={e.status} />}
        actions={editable && <Button variant="ghost" busy={save.isPending} onClick={() => save.mutate()}>Save draft</Button>} />
      {!isNew && !editable && (
        <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
          <StatCard label="Recipients" value={num(e.totalCount)} /><StatCard label="Delivered to provider" value={num(e.sentCount)} /><StatCard label="Failed" value={num(e.failedCount)} /><StatCard label="Unsubscribed" value={num(e.unsubCount)} />
        </div>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Message">
          <label className="label" htmlFor="b-name">Name (only you see this)</label>
          <input id="b-name" className="input" value={v.name} disabled={!editable} maxLength={100} onChange={(ev) => set('name', ev.target.value)} /><Err m={errs.name} />
          {channel !== 'SMS' && (<><label className="label mt-3" htmlFor="b-subj">{channel === 'PUSH' ? 'Title' : 'Subject'}</label>
            <input id="b-subj" className="input" value={v.subject} disabled={!editable} maxLength={channel === 'PUSH' ? 65 : 150} onChange={(ev) => set('subject', ev.target.value)} /><Err m={errs.subject} /></>)}
          <label className="label mt-3" htmlFor="b-body">{channel === 'EMAIL' ? 'Message (basic HTML allowed)' : 'Message'}</label>
          <textarea id="b-body" className="input font-mono" rows={channel === 'EMAIL' ? 10 : 4} value={v.body} disabled={!editable} onChange={(ev) => set('body', ev.target.value)} /><Err m={errs.body} />
          <p className="muted mt-1 text-xs">Use {'{{first_name}}'}, {'{{name}}'} and {'{{city}}'} to personalise.{sms && ` ${v.body.length} characters.`}{channel === 'EMAIL' && ' An unsubscribe link is added automatically.'}</p>
          {sms && <p className="muted mt-1 text-xs">SMS in India must use a DLT-registered sender and template with your provider.</p>}
        </Card>
        <div className="space-y-4">
          {channel !== 'PUSH' && (
            <Card title="Audience"><AudiencePicker value={{ audienceType: v.audienceType, audience: v.audience }} tags={tags.data ?? []} segments={segments.data ?? []} onChange={(a: Audience) => setF({ ...v, ...a })} />
              <Err m={errs.audienceType || errs.audience} />
              <p className="muted mt-2 text-xs">Only opted-in contacts who have not unsubscribed from this channel.{channel === 'EMAIL' ? ' Contacts need an e-mail address.' : ''}</p></Card>
          )}
          {est && editable && (
            <Card title="Before you send">
              <dl className="grid grid-cols-2 gap-3 text-sm"><div><dt className="muted">Recipients</dt><dd className="text-xl font-bold">{num(est.recipients)}</dd></div>
                {sms && <div><dt className="muted">Cost ({est.segments} segment{est.segments > 1 ? 's' : ''} each)</dt><dd className="text-xl font-bold">{inr(est.cost, 2)}</dd></div>}
                {sms && <div><dt className="muted">Wallet</dt><dd className={`text-xl font-bold ${est.sufficient ? '' : 'text-red-600'}`}>{inr(est.balance)}</dd></div>}
                {est.dailyRemaining !== undefined && <div><dt className="muted">E-mails left today</dt><dd className="text-xl font-bold">{num(est.dailyRemaining)}</dd></div>}</dl>
              {sms && !est.sufficient && <Alert tone="red" action={<Link to="/settings/billing" className="btn-primary !py-1.5">Top up</Link>}>Your wallet cannot cover this broadcast.</Alert>}
              {channel !== 'PUSH' && <div className="mt-3 flex gap-2"><input className="input" aria-label="Send a test to" placeholder={sms ? 'Mobile number' : 'your@email.com'} value={test} onChange={(ev) => setTest(ev.target.value)} />
                <Button variant="ghost" busy={sendTest.isPending} disabled={!test} onClick={() => sendTest.mutate()}>Send test</Button></div>}
              <Err m={errs.to} />
              <div className="mt-4 flex flex-wrap items-center gap-2">
                <Button disabled={est.recipients < 1 || (sms && !est.sufficient) || !!f} onClick={() => setConfirm(true)}>Send now</Button>
                <input type="datetime-local" className="input !w-auto" aria-label="Schedule at (IST)" value={when} onChange={(ev) => setWhen(ev.target.value)} />
                <Button variant="ghost" busy={schedule.isPending} disabled={!when || !!f} onClick={() => schedule.mutate()}>Schedule</Button>
              </div>
              {f && <p className="muted mt-2 text-xs">Save your changes first.</p>}<Err m={errs.scheduledAt} />
              {e.status === 'SCHEDULED' && <Alert tone="blue" action={<Button variant="ghost" onClick={() => cancel.mutate()}>Unschedule</Button>}>Scheduled for {fmtDateTime(e.scheduledAt)}.</Alert>}
            </Card>
          )}
          {e?.status === 'SENDING' && <Alert tone="blue" action={<Button variant="danger" onClick={() => cancel.mutate()}>Stop</Button>}>Sending… {num(e.sentCount)} of {num(e.totalCount)}</Alert>}
        </div>
      </div>
      {recipients.data && (
        <Card title="Recipients" className="mt-4"><DataTable rows={recipients.data.items} columns={[
          { key: 'n', header: 'Name', render: (r: any) => r.fullName }, { key: 'c', header: channel === 'EMAIL' ? 'E-mail' : 'Mobile', render: (r: any) => (channel === 'EMAIL' ? r.email : r.phone) },
          { key: 's', header: 'Status', render: (r: any) => <div><StatusBadge status={r.status} />{r.error && <div className="text-[11px] text-red-600">{r.error}</div>}</div> }, { key: 't', header: 'Time', hideOnMobile: true, render: (r: any) => (r.sentAt ? fmtDateTime(r.sentAt) : '—') },
        ]} /></Card>
      )}
      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} title={`Send to ${num(est?.recipients)} people?`} confirmLabel="Send" busy={send.isPending} onConfirm={() => send.mutate()}
        message={<span>This cannot be undone.{sms ? ` ${inr(est?.cost, 2)} will be taken from your wallet; undelivered messages are refunded.` : ''}</span>} />
    </>
  );
}
