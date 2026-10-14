export type ToastKind = 'success' | 'error' | 'info';
export interface ToastMsg { id: number; kind: ToastKind; text: string }
type Listener = (t: ToastMsg[]) => void;

let list: ToastMsg[] = [];
let seq = 1;
const listeners = new Set<Listener>();
const emit = () => listeners.forEach((l) => l(list));

export const subscribeToasts = (l: Listener) => { listeners.add(l); l(list); return () => { listeners.delete(l); }; };

const push = (kind: ToastKind, text: string) => {
  const id = seq++;
  list = [...list, { id, kind, text }].slice(-4);
  emit();
  setTimeout(() => { list = list.filter((t) => t.id !== id); emit(); }, kind === 'error' ? 6000 : 3500);
};

export const toast = {
  success: (t: string) => push('success', t),
  error: (t: string) => push('error', t),
  info: (t: string) => push('info', t),
};
