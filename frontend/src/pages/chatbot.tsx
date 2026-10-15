import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Badge, Button, Card, ConfirmDialog, DataTable, Modal, PageHeader } from '../components/ui';
import { Err, fieldErrors } from '../components/common';
import { useTags } from './contacts';

const TYPES: Record<string, string> = { CONTAINS: 'Message contains a keyword', EXACT: 'Message is exactly a keyword', FIRST_MESSAGE: 'First message from a customer', FALLBACK: 'Nothing else matched' };
const EMPTY = { name: '', matchType: 'CONTAINS', keywords: '', replyText: '', thenTagId: '', handoff: false, priority: 100, active: true };

/** WhatsApp auto-replies: the first matching rule answers inside the 24-hour window. */
export function Chatbot() {
  const qc = useQueryClient();
  const { hasFeature } = useAuth();
  const tags = useTags();
  const rules = useQuery({ queryKey: ['bot-rules'], queryFn: () => api.get<any[]>('/api/v1/chatbot/rules') });
  const [edit, setEdit] = useState<any | null>(null);
  const [errs, setErrs] = useState<Record<string, string>>({});
  const [rm, setRm] = useState<any | null>(null);
  const [tryText, setTryText] = useState('');
  const [tryResult, setTryResult] = useState<any | null>(null);
  const save = useMutation({
    mutationFn: () => {
      const body = { ...edit, keywords: String(edit.keywords).split(',').map((s) => s.trim()).filter(Boolean), thenTagId: edit.thenTagId ? Number(edit.thenTagId) : null, priority: Number(edit.priority) };
      return edit.id ? api.put(`/api/v1/chatbot/rules/${edit.id}`, body) : api.post('/api/v1/chatbot/rules', body);
    },
    onSuccess: () => { setEdit(null); setErrs({}); qc.invalidateQueries({ queryKey: ['bot-rules'] }); toast.success('Rule saved'); },
    onError: (e: any) => { setErrs(fieldErrors(e)); toast.error(errText(e)); },
  });
  const del = useMutation({ mutationFn: (id: number) => api.del(`/api/v1/chatbot/rules/${id}`), onSuccess: () => { setRm(null); qc.invalidateQueries({ queryKey: ['bot-rules'] }); }, onError: (e) => toast.error(errText(e)) });
  const toggle = useMutation({ mutationFn: (r: any) => api.put(`/api/v1/chatbot/rules/${r.id}`, { ...r, keywords: r.keywords ?? [], thenTagId: r.thenTagId, active: !r.active }), onSuccess: () => qc.invalidateQueries({ queryKey: ['bot-rules'] }), onError: (e) => toast.error(errText(e)) });
  const open = (r?: any) => { setErrs({}); setEdit(r ? { ...r, keywords: (r.keywords ?? []).join(', '), thenTagId: r.thenTagId ?? '' } : { ...EMPTY }); };
  const kwNeeded = edit && ['EXACT', 'CONTAINS'].includes(edit.matchType);
  return (
    <>
      <PageHeader title="Chatbot" subtitle="Reply to common questions automatically on WhatsApp" actions={<Button onClick={() => open()}>+ New rule</Button>} />
      {!hasFeature('chatbot') && <Alert tone="blue">The chatbot is part of the Growth plan.</Alert>}
      <Alert tone="amber">Replies only go out inside the 24-hour window, never to customers who opted out, and never while a team member is assigned to the chat.</Alert>
      <Card className="mt-4"><DataTable rows={rules.data} loading={rules.isLoading} empty="No rules yet. Start with opening hours or prices." columns={[
        { key: 'n', header: 'Rule', render: (r: any) => <div><b>{r.name}</b><div className="muted text-xs">{TYPES[r.matchType]}{r.keywords?.length ? `: ${r.keywords.join(', ')}` : ''}</div></div> },
        { key: 'r', header: 'Reply', hideOnMobile: true, render: (r: any) => <span className="line-clamp-2 max-w-sm text-sm">{r.replyText}</span> },
        { key: 'h', header: 'Used', render: (r: any) => <>{r.hitCount}{r.handoff && <> <Badge tone="amber">hand-off</Badge></>}</> },
        { key: 'a', header: 'On', render: (r: any) => <input type="checkbox" aria-label="Active" checked={!!r.active} onChange={() => toggle.mutate(r)} /> },
        { key: 'x', header: '', render: (r: any) => <span className="flex gap-3 text-sm"><button className="text-brand" onClick={() => open(r)}>Edit</button><button className="text-red-600" onClick={() => setRm(r)}>Delete</button></span> },
      ]} /></Card>
      <Card title="Try it" className="mt-4">
        <div className="flex gap-2"><input className="input" aria-label="Customer message" placeholder="Type what a customer might write" value={tryText} onChange={(e) => setTryText(e.target.value)} />
          <Button variant="ghost" disabled={!tryText.trim()} onClick={async () => setTryResult(await api.post('/api/v1/chatbot/test', { text: tryText, firstMessage: false }))}>Test</Button></div>
        {tryResult && <p className="mt-2 text-sm">{tryResult.matched ? <>Rule <b>{tryResult.ruleName}</b> would reply: “{tryResult.reply}”</> : 'No rule matches. The customer would get no automatic reply.'}</p>}
      </Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} wide title={edit?.id ? 'Edit rule' : 'New rule'} footer={<><Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
        {edit && <div className="space-y-3">
          <div><label className="label" htmlFor="r-n">Name</label><input id="r-n" className="input" maxLength={80} value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /><Err m={errs.name} /></div>
          <div><label className="label" htmlFor="r-t">When</label><select id="r-t" className="input" value={edit.matchType} onChange={(e) => setEdit({ ...edit, matchType: e.target.value })}>{Object.entries(TYPES).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></div>
          {kwNeeded && <div><label className="label" htmlFor="r-k">Keywords (comma separated)</label><input id="r-k" className="input" placeholder="price, rate, cost" value={edit.keywords} onChange={(e) => setEdit({ ...edit, keywords: e.target.value })} /><Err m={errs.keywords} /></div>}
          <div><label className="label" htmlFor="r-r">Reply</label><textarea id="r-r" className="input" rows={4} maxLength={1000} value={edit.replyText} onChange={(e) => setEdit({ ...edit, replyText: e.target.value })} /><Err m={errs.replyText} /></div>
          <div className="grid grid-cols-2 gap-2">
            <div><label className="label" htmlFor="r-tag">Then add tag</label><select id="r-tag" className="input" value={edit.thenTagId} onChange={(e) => setEdit({ ...edit, thenTagId: e.target.value })}><option value="">None</option>{tags.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</select></div>
            <div><label className="label" htmlFor="r-p">Order (lower runs first)</label><input id="r-p" type="number" className="input" value={edit.priority} onChange={(e) => setEdit({ ...edit, priority: e.target.value })} /></div>
          </div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={edit.handoff} onChange={(e) => setEdit({ ...edit, handoff: e.target.checked })} /> Notify the owner to continue the chat personally</label>
        </div>}
      </Modal>
      <ConfirmDialog open={!!rm} onClose={() => setRm(null)} title="Delete this rule?" message="Customers will no longer get this automatic reply." danger confirmLabel="Delete" busy={del.isPending} onConfirm={() => del.mutate(rm.id)} />
    </>
  );
}
