import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { fmtDate, inr, num } from '../lib/format';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Badge, Button, Card, ConfirmDialog, DataTable, Modal, PageHeader, StatCard, StatusBadge, Tabs } from '../components/ui';
import { Err, fieldErrors } from '../components/common';

/* -------------------------------- catalog -------------------------------- */
const P0 = { name: '', description: '', sku: '', category: '', price: '', compareAtPrice: '', imageUrl: '', inStock: true, active: true };

export function Catalog() {
  const qc = useQueryClient();
  const { hasFeature, workspace } = useAuth();
  const [q, setQ] = useState('');
  const list = useQuery({ queryKey: ['products', q], queryFn: () => api.get<any[]>(`/api/v1/catalog/products${q.trim().length >= 2 ? `?q=${encodeURIComponent(q.trim())}` : ''}`) });
  const [edit, setEdit] = useState<any | null>(null);
  const [share, setShare] = useState<any | null>(null);
  const [contactId, setContactId] = useState('');
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [rm, setRm] = useState<any | null>(null);
  const num2 = (x: any) => (x === '' || x == null ? null : Number(x));
  const save = useMutation({
    mutationFn: () => { const b = { ...edit, price: num2(edit.price), compareAtPrice: num2(edit.compareAtPrice) }; return edit.id ? api.put(`/api/v1/catalog/products/${edit.id}`, b) : api.post('/api/v1/catalog/products', b); },
    onSuccess: () => { setEdit(null); setErrs({}); qc.invalidateQueries({ queryKey: ['products'] }); toast.success('Product saved'); }, onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); },
  });
  const del = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/catalog/products/${id}`), onSuccess: () => { setRm(null); qc.invalidateQueries({ queryKey: ['products'] }); }, onError: (e) => toast.error(errText(e)) });
  const doShare = useMutation({ mutationFn: () => api.post(`/api/v1/catalog/products/${share.id}/share`, { contactId: Number(contactId) }), onSuccess: () => { toast.success('Sent on WhatsApp'); setShare(null); setContactId(''); }, onError: (e) => toast.error(errText(e)) });
  const url = `${location.origin}/c/${workspace?.id}`;
  const F = (k: string, label: string, props: any = {}) => (<div><label className="label" htmlFor={`pr-${k}`}>{label}</label><input id={`pr-${k}`} className="input" value={edit?.[k] ?? ''} onChange={(e) => setEdit({ ...edit, [k]: e.target.value })} {...props} /><Err m={errs[k]} /></div>);
  return (
    <>
      <PageHeader title="Catalog" subtitle="Products you can share on WhatsApp and show on a public page" actions={<Button onClick={() => { setErrs({}); setEdit({ ...P0 }); }}>+ Add product</Button>} />
      {!hasFeature('catalog') && <Alert tone="blue">The catalog is part of the Growth plan.</Alert>}
      <Alert tone="green">Your public catalog: <a className="underline" href={url} target="_blank" rel="noreferrer">{url}</a></Alert>
      <input className="input my-3 max-w-xs" aria-label="Search" placeholder="Search products" value={q} onChange={(e) => setQ(e.target.value)} />
      <Card><DataTable rows={list.data} loading={list.isLoading} empty="No products yet." columns={[
        { key: 'i', header: '', render: (p: any) => (p.imageUrl ? <img src={p.imageUrl} alt="" className="h-10 w-10 rounded object-cover" /> : null) },
        { key: 'n', header: 'Product', render: (p: any) => <div><b>{p.name}</b><div className="muted text-xs">{p.category} {p.sku && `· ${p.sku}`}</div></div> },
        { key: 'p', header: 'Price', render: (p: any) => <>{inr(p.price, 0)}{p.compareAtPrice && <s className="muted ml-1 text-xs">{inr(p.compareAtPrice, 0)}</s>}</> },
        { key: 's', header: 'Stock', render: (p: any) => (p.inStock ? <Badge tone="green">In stock</Badge> : <Badge tone="red">Out</Badge>) },
        { key: 'x', header: '', render: (p: any) => <span className="flex gap-3 text-sm"><button className="text-brand" onClick={() => setShare(p)}>Share</button><button className="text-brand" onClick={() => { setErrs({}); setEdit({ ...p, compareAtPrice: p.compareAtPrice ?? '', imageUrl: p.imageUrl ?? '', description: p.description ?? '', sku: p.sku ?? '', category: p.category ?? '' }); }}>Edit</button><button className="text-red-600" onClick={() => setRm(p)}>Delete</button></span> },
      ]} /></Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} wide title={edit?.id ? 'Edit product' : 'Add product'} footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
        {edit && <div className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">{F('name', 'Name', { maxLength: 120 })}</div>
          {F('price', 'Price (₹)', { inputMode: 'decimal' })}{F('compareAtPrice', 'Original price (optional)', { inputMode: 'decimal' })}
          {F('category', 'Category')}{F('sku', 'SKU / code')}
          <div className="sm:col-span-2"><label className="label" htmlFor="pr-d">Description</label><textarea id="pr-d" className="input" rows={3} maxLength={1000} value={edit.description} onChange={(e) => setEdit({ ...edit, description: e.target.value })} /></div>
          <div className="sm:col-span-2">{F('imageUrl', 'Image link')}<label className="btn-ghost mt-1 cursor-pointer">Upload image<input type="file" hidden accept="image/jpeg,image/png" onChange={async (e) => { const f = e.target.files?.[0]; if (!f) return; try { setEdit({ ...edit, imageUrl: (await api.upload(f, 'image')).url }); } catch (er) { toast.error(errText(er)); } }} /></label></div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={edit.inStock} onChange={(e) => setEdit({ ...edit, inStock: e.target.checked })} /> In stock</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={edit.active} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} /> Show on catalog</label>
        </div>}
      </Modal>
      <Modal open={!!share} onClose={() => setShare(null)} title={`Share ${share?.name}`} footer={<><Button variant="ghost" onClick={() => setShare(null)}>Cancel</Button><Button busy={doShare.isPending} disabled={!contactId} onClick={() => doShare.mutate()}>Send</Button></>}>
        <p className="muted mb-2 text-sm">Sends the photo, price and a link inside the customer's open 24-hour window.</p>
        <label className="label" htmlFor="sh-c">Contact ID</label><input id="sh-c" className="input" inputMode="numeric" value={contactId} onChange={(e) => setContactId(e.target.value.replace(/\D/g, ''))} />
      </Modal>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this product?" message={rm?.name} danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => del.mutate(rm.id)} />
    </>
  );
}

/* ------------------------------ coupons and loyalty ------------------------------ */
export function Rewards() {
  const [tab, setTab] = useState('coupons');
  const { hasFeature } = useAuth();
  return (
    <>
      <PageHeader title="Coupons and loyalty" subtitle="Bring customers back with discounts, points and referrals" />
      {!hasFeature('loyalty') && <Alert tone="blue">Coupons and loyalty are part of the Growth plan.</Alert>}
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'coupons', label: 'Coupons' }, { key: 'loyalty', label: 'Loyalty points' }]} />
      {tab === 'coupons' ? <Coupons /> : <Loyalty />}
    </>
  );
}

const C0 = { code: '', description: '', discountType: 'PERCENT', discountValue: '', maxDiscount: '', minOrder: '', maxRedemptions: '', perContactLimit: 1, validFrom: '', validUntil: '', active: true };

function Coupons() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['coupons'], queryFn: () => api.get('/api/v1/coupons') });
  const [edit, setEdit] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [rm, setRm] = useState<any | null>(null);
  const [chk, setChk] = useState({ code: '', orderAmount: '', contactId: '' });
  const [chkResult, setChkResult] = useState<string>('');
  const n = (x: any) => (x === '' || x == null ? null : Number(x));
  const iso = (x: string) => (x ? new Date(x + ':00+05:30').toISOString() : null);
  const save = useMutation({
    mutationFn: () => { const b = { ...edit, discountValue: n(edit.discountValue), maxDiscount: n(edit.maxDiscount), minOrder: n(edit.minOrder), maxRedemptions: n(edit.maxRedemptions), perContactLimit: n(edit.perContactLimit), validFrom: iso(edit.validFrom), validUntil: iso(edit.validUntil) }; return edit.id ? api.put(`/api/v1/coupons/${edit.id}`, b) : api.post('/api/v1/coupons', b); },
    onSuccess: () => { setEdit(null); setErrs({}); qc.invalidateQueries({ queryKey: ['coupons'] }); toast.success('Coupon saved'); }, onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); },
  });
  const del = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/coupons/${id}`), onSuccess: () => { setRm(null); qc.invalidateQueries({ queryKey: ['coupons'] }); }, onError: (e) => { setRm(null); toast.error(errText(e)); } });
  const run = async (commit: boolean) => {
    try { const r = await api.post(`/api/v1/coupons/${commit ? 'redeem' : 'validate'}`, { code: chk.code, orderAmount: Number(chk.orderAmount), contactId: chk.contactId ? Number(chk.contactId) : null });
      setChkResult(`${commit ? 'Redeemed' : 'Valid'}: discount ${inr(r.discount)}, customer pays ${inr(r.payable)}`); if (commit) qc.invalidateQueries({ queryKey: ['coupons'] }); } catch (e) { setChkResult(errText(e)); }
  };
  const F = (k: string, label: string, props: any = {}) => (<div><label className="label" htmlFor={`cp-${k}`}>{label}</label><input id={`cp-${k}`} className="input" value={edit?.[k] ?? ''} onChange={(e) => setEdit({ ...edit, [k]: e.target.value })} {...props} /><Err m={errs[k]} /></div>);
  const st = q.data?.stats;
  return (
    <>
      <div className="my-3 grid grid-cols-3 gap-3"><StatCard label="Redemptions" value={num(st?.redemptions)} /><StatCard label="Discount given" value={inr(st?.discountGiven, 0)} /><StatCard label="Order value" value={inr(st?.orderValue, 0)} /></div>
      <div className="mb-3"><Button onClick={() => { setErrs({}); setEdit({ ...C0 }); }}>+ New coupon</Button></div>
      <Card><DataTable rows={q.data?.items} loading={q.isLoading} empty="No coupons yet." columns={[
        { key: 'c', header: 'Code', render: (c: any) => <div><b className="font-mono">{c.code}</b><div className="muted text-xs">{c.description}</div></div> },
        { key: 'd', header: 'Discount', render: (c: any) => (c.discountType === 'PERCENT' ? `${Number(c.discountValue)}%${c.maxDiscount ? ` (max ${inr(c.maxDiscount, 0)})` : ''}` : inr(c.discountValue, 0)) },
        { key: 'u', header: 'Used', render: (c: any) => `${c.redeemedCount}${c.maxRedemptions ? ` / ${c.maxRedemptions}` : ''}` },
        { key: 'v', header: 'Valid until', hideOnMobile: true, render: (c: any) => (c.validUntil ? fmtDate(c.validUntil) : 'No end') }, { key: 's', header: 'Status', render: (c: any) => <StatusBadge status={c.active ? 'ACTIVE' : 'DISABLED'} /> },
        { key: 'x', header: '', render: (c: any) => <span className="flex gap-3 text-sm"><button className="text-brand" onClick={() => { setErrs({}); setEdit({ ...c, maxDiscount: c.maxDiscount ?? '', maxRedemptions: c.maxRedemptions ?? '', description: c.description ?? '', validFrom: '', validUntil: '' }); }}>Edit</button><button className="text-red-600" onClick={() => setRm(c)}>Delete</button></span> },
      ]} /></Card>
      <Card title="Check or redeem a code at the counter" className="mt-4">
        <div className="grid gap-2 sm:grid-cols-4"><input className="input" aria-label="Code" placeholder="Code" value={chk.code} onChange={(e) => setChk({ ...chk, code: e.target.value.toUpperCase() })} />
          <input className="input" aria-label="Order amount" placeholder="Order ₹" inputMode="decimal" value={chk.orderAmount} onChange={(e) => setChk({ ...chk, orderAmount: e.target.value })} />
          <input className="input" aria-label="Contact ID" placeholder="Contact ID (optional)" inputMode="numeric" value={chk.contactId} onChange={(e) => setChk({ ...chk, contactId: e.target.value.replace(/\D/g, '') })} />
          <div className="flex gap-2"><Button variant="ghost" disabled={!chk.code || !chk.orderAmount} onClick={() => run(false)}>Check</Button><Button disabled={!chk.code || !chk.orderAmount} onClick={() => run(true)}>Redeem</Button></div></div>
        {chkResult && <p className="mt-2 text-sm">{chkResult}</p>}
      </Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} wide title={edit?.id ? 'Edit coupon' : 'New coupon'} footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
        {edit && <div className="grid gap-3 sm:grid-cols-2">
          {F('code', 'Code', { maxLength: 20, style: { textTransform: 'uppercase' } })}
          <div><label className="label" htmlFor="cp-t">Type</label><select id="cp-t" className="input" value={edit.discountType} onChange={(e) => setEdit({ ...edit, discountType: e.target.value })}><option value="PERCENT">Percent off</option><option value="FLAT">Flat ₹ off</option></select></div>
          {F('discountValue', edit.discountType === 'PERCENT' ? 'Percent' : 'Amount ₹', { inputMode: 'decimal' })}
          {edit.discountType === 'PERCENT' && F('maxDiscount', 'Maximum discount ₹ (optional)', { inputMode: 'decimal' })}
          {F('minOrder', 'Minimum order ₹', { inputMode: 'decimal' })}{F('maxRedemptions', 'Total uses (blank = unlimited)', { inputMode: 'numeric' })}
          {F('perContactLimit', 'Uses per customer', { inputMode: 'numeric' })}
          {F('validFrom', 'Starts (IST)', { type: 'datetime-local' })}{F('validUntil', 'Ends (IST)', { type: 'datetime-local' })}
          <div className="sm:col-span-2">{F('description', 'Note for your team')}</div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={edit.active} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} /> Active</label>
        </div>}
      </Modal>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this coupon?" message={rm?.code} danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => del.mutate(rm.id)} />
    </>
  );
}

function Loyalty() {
  const qc = useQueryClient();
  const settings = useQuery({ queryKey: ['loy-settings'], queryFn: () => api.get('/api/v1/loyalty/settings') });
  const members = useQuery({ queryKey: ['loy-members'], queryFn: () => api.get('/api/v1/loyalty/members') });
  const [s, setS] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [cid, setCid] = useState('');
  const [amt, setAmt] = useState('');
  const [pts, setPts] = useState('');
  const [acct, setAcct] = useState<any | null>(null);
  const [ref, setRef] = useState({ code: '', referred: '' });
  const v = s ?? settings.data;
  const save = useMutation({
    mutationFn: () => api.put('/api/v1/loyalty/settings', { enabled: !!v.enabled, pointsPer100: Number(v.pointsPer100), rupeesPerPoint: Number(v.rupeesPerPoint), referralBonus: Number(v.referralBonus), refereeBonus: Number(v.refereeBonus), silverAt: Number(v.silverAt), goldAt: Number(v.goldAt) }),
    onSuccess: () => { setS(null); setErrs({}); qc.invalidateQueries({ queryKey: ['loy-settings'] }); toast.success('Saved'); }, onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); },
  });
  const look = async () => { try { setAcct(await api.get(`/api/v1/loyalty/contacts/${cid}`)); } catch (e) { toast.error(errText(e)); setAcct(null); } };
  const after = async (p: Promise<any>, msg: string) => { try { await p; toast.success(msg); await look(); qc.invalidateQueries({ queryKey: ['loy-members'] }); } catch (e) { toast.error(errText(e)); } };
  if (!v) return null;
  const F = (k: string, label: string) => (<div><label className="label" htmlFor={`ly-${k}`}>{label}</label><input id={`ly-${k}`} className="input" inputMode="decimal" value={v[k] ?? ''} onChange={(e) => setS({ ...v, [k]: e.target.value })} /><Err m={errs[k]} /></div>);
  return (
    <div className="mt-3 grid gap-4 lg:grid-cols-2">
      <Card title="Program rules">
        <label className="mb-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={!!v.enabled} onChange={(e) => setS({ ...v, enabled: e.target.checked })} /> Program is on</label>
        <div className="grid grid-cols-2 gap-3">{F('pointsPer100', 'Points per ₹100')}{F('rupeesPerPoint', '₹ value of 1 point')}{F('referralBonus', 'Referrer bonus (points)')}{F('refereeBonus', 'New customer bonus (points)')}{F('silverAt', 'Silver from (lifetime points)')}{F('goldAt', 'Gold from (lifetime points)')}</div>
        <div className="mt-3"><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></div>
      </Card>
      <Card title="Customer account">
        <div className="flex gap-2"><input className="input" aria-label="Contact ID" placeholder="Contact ID" inputMode="numeric" value={cid} onChange={(e) => setCid(e.target.value.replace(/\D/g, ''))} /><Button variant="ghost" disabled={!cid} onClick={look}>Open</Button></div>
        {acct && <div className="mt-3 space-y-3 text-sm">
          <div className="flex flex-wrap items-center gap-3"><b className="text-2xl">{num(acct.points)} pts</b><Badge tone={acct.tier === 'GOLD' ? 'amber' : acct.tier === 'SILVER' ? 'blue' : 'gray'}>{acct.tier}</Badge><span className="muted">worth {inr(acct.value)}</span><span className="muted">code <code>{acct.referralCode}</code></span></div>
          <div className="grid grid-cols-2 gap-2"><div className="flex gap-2"><input className="input" aria-label="Order amount" placeholder="Order ₹" inputMode="decimal" value={amt} onChange={(e) => setAmt(e.target.value)} /><Button disabled={!amt} onClick={() => after(api.post('/api/v1/loyalty/earn', { contactId: Number(cid), orderAmount: Number(amt) }), 'Points added')}>Earn</Button></div>
            <div className="flex gap-2"><input className="input" aria-label="Points" placeholder="Points" inputMode="numeric" value={pts} onChange={(e) => setPts(e.target.value.replace(/\D/g, ''))} /><Button variant="ghost" disabled={!pts} onClick={() => after(api.post('/api/v1/loyalty/redeem', { contactId: Number(cid), points: Number(pts) }), 'Points redeemed')}>Redeem</Button></div></div>
          <ul className="max-h-40 overflow-y-auto text-xs">{acct.history.map((h: any) => <li key={h.id} className="flex justify-between border-b py-1" style={{ borderColor: 'var(--border)' }}><span>{h.reason}{h.note ? ` · ${h.note}` : ''}</span><b className={h.points < 0 ? 'text-red-600' : 'text-emerald-600'}>{h.points > 0 ? '+' : ''}{h.points}</b></li>)}</ul>
        </div>}
        <h3 className="mt-4 text-sm font-bold">Refer a friend</h3>
        <div className="mt-1 flex gap-2"><input className="input" aria-label="Referral code" placeholder="Code" value={ref.code} onChange={(e) => setRef({ ...ref, code: e.target.value.toUpperCase() })} /><input className="input" aria-label="New customer ID" placeholder="New customer ID" inputMode="numeric" value={ref.referred} onChange={(e) => setRef({ ...ref, referred: e.target.value.replace(/\D/g, '') })} />
          <Button variant="ghost" disabled={!ref.code || !ref.referred} onClick={() => after(api.post('/api/v1/loyalty/refer', { referralCode: ref.code, referredContactId: Number(ref.referred) }), 'Referral recorded')}>Apply</Button></div>
      </Card>
      <Card title={`Members (${num(members.data?.members?.length)}) · ${num(members.data?.pointsOutstanding)} points outstanding`} className="lg:col-span-2">
        <DataTable rows={members.data?.members?.map((m: any) => ({ ...m, id: m.contactId }))} loading={members.isLoading} empty="No members yet." columns={[
          { key: 'n', header: 'Customer', render: (m: any) => <div><b>{m.fullName}</b><div className="muted text-xs">{m.phone}</div></div> }, { key: 't', header: 'Tier', render: (m: any) => <Badge>{m.tier}</Badge> },
          { key: 'p', header: 'Points', render: (m: any) => num(m.points) }, { key: 'l', header: 'Lifetime', hideOnMobile: true, render: (m: any) => num(m.lifetimePoints) }, { key: 'r', header: 'Referrals', hideOnMobile: true, render: (m: any) => m.referrals },
        ]} />
      </Card>
    </div>
  );
}
