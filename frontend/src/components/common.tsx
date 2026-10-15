

/** Maps the server's field errors onto a {field: message} object for simple (non react-hook-form) forms. */
export const fieldErrors = (e: any): Record<string, string> => Object.fromEntries((e?.fieldErrors ?? []).map((x: any) => [x.field, x.message]));

export const Err = ({ m }: { m?: string }) => (m ? <p className="mt-1 text-sm text-red-600" role="alert">{m}</p> : null);

/** Audience picker value used by campaigns and broadcasts. */
export interface Audience { audienceType: string; audience: any }

export function AudiencePicker({ value, onChange, tags, segments }: { value: Audience; onChange: (a: Audience) => void; tags: any[]; segments: any[] }) {
  const ids: number[] = value.audience?.tagIds ?? [];
  return (
    <div>
      <div className="flex flex-wrap gap-2">
        {[['ALL_OPTED_IN', 'Everyone who opted in'], ['TAGS', 'Tags'], ['SEGMENT', 'Segment']].map(([v, l]) => (
          <button key={v} type="button" className={value.audienceType === v ? 'btn-primary' : 'btn-ghost'} onClick={() => onChange({ audienceType: v, audience: v === 'ALL_OPTED_IN' ? null : {} })}>{l}</button>
        ))}
      </div>
      {value.audienceType === 'TAGS' && (
        <div className="mt-2 flex flex-wrap gap-1">{tags.map((t) => { const on = ids.includes(t.id); return (
          <button type="button" key={t.id} className="badge border" style={{ background: on ? t.color : 'transparent', color: on ? '#fff' : undefined, borderColor: t.color }}
            onClick={() => onChange({ ...value, audience: { tagIds: on ? ids.filter((x) => x !== t.id) : [...ids, t.id] } })}>{t.name}</button>); })}</div>
      )}
      {value.audienceType === 'SEGMENT' && (
        <select className="input mt-2" aria-label="Segment" value={value.audience?.segmentId ?? ''} onChange={(e) => onChange({ ...value, audience: { segmentId: Number(e.target.value) } })}>
          <option value="">Choose a segment…</option>{segments.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      )}
    </div>
  );
}

