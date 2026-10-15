import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { errText } from '../lib/hooks';
import { toast } from '../lib/toast';
import { Alert, Button, CopyButton } from './ui';

const TYPES = [['CAPTION', 'Caption'], ['OFFER_MESSAGE', 'WhatsApp offer'], ['REPLY', 'Customer reply'], ['HASHTAGS', 'Hashtags']] as const;

/** AI helper (P20): topic + language + tone -> editable text. The output is never sent automatically. */
export function AiAssistant({ kind = 'CAPTION', compact, onUse }: { kind?: string; compact?: boolean; onUse?: (text: string) => void }) {
  const qc = useQueryClient();
  const [topic, setTopic] = useState('');
  const [type, setType] = useState(kind);
  const [language, setLanguage] = useState<'en' | 'ta'>('en');
  const [tone, setTone] = useState('Friendly');
  const [output, setOutput] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const usage = useQuery({ queryKey: ['ai-usage'], queryFn: () => api.get('/api/v1/ai/usage') });
  const gen = useMutation({
    mutationFn: () => api.post('/api/v1/ai/generate', { type, topic, language, tone }),
    onSuccess: (r) => { setOutput(r.output); setErr(null); qc.invalidateQueries({ queryKey: ['ai-usage'] }); },
    onError: (e) => { setErr(errText(e)); toast.error(errText(e)); },
  });
  const u = usage.data;
  if (u && !u.enabled) return <Alert tone="blue">AI writing help is available on higher plans. Upgrade from Billing to use it.</Alert>;
  return (
    <div className="rounded-xl border p-3" style={{ borderColor: 'var(--border)' }}>
      <div className="mb-2 flex items-center justify-between"><b className="text-sm">✨ Write with AI</b>
        {u && <span className="muted text-xs">{u.used} / {u.limit} used this month</span>}</div>
      <label className="label" htmlFor="ai-topic">What is the offer about?</label>
      <textarea id="ai-topic" className="input" rows={compact ? 2 : 3} maxLength={300} value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="Diwali sale, 20% off on all sarees till Sunday" />
      <div className="mt-2 grid grid-cols-3 gap-2">
        {!compact && (
          <select className="input" aria-label="Type" value={type} onChange={(e) => setType(e.target.value)}>{TYPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
        )}
        <select className="input" aria-label="Language" value={language} onChange={(e) => setLanguage(e.target.value as 'en' | 'ta')}><option value="en">English</option><option value="ta">தமிழ்</option></select>
        <select className="input" aria-label="Tone" value={tone} onChange={(e) => setTone(e.target.value)}>{['Friendly', 'Festive', 'Professional'].map((t) => <option key={t}>{t}</option>)}</select>
      </div>
      {err && <p className="mt-2 text-sm text-red-600" role="alert">{err}</p>}
      <div className="mt-2"><Button type="button" busy={gen.isPending} disabled={topic.trim().length < 5 || (u && u.used >= u.limit)} onClick={() => gen.mutate()}>Generate</Button></div>
      {output && (
        <div className="mt-3">
          <label className="label" htmlFor="ai-out">Result (you can edit it)</label>
          <textarea id="ai-out" className="input" rows={5} value={output} onChange={(e) => setOutput(e.target.value)} />
          <div className="mt-2 flex gap-2"><CopyButton text={output} label="Copy" />{onUse && <Button type="button" variant="ghost" onClick={() => onUse(output)}>Use this</Button>}</div>
        </div>
      )}
    </div>
  );
}
