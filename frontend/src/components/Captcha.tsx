import { useEffect, useRef } from 'react';

declare global { interface Window { turnstile?: any } }

/**
 * Cloudflare Turnstile widget. Without VITE_TURNSTILE_SITE_KEY (local development) it renders nothing and reports a dev token,
 * which the backend accepts only when no CAPTCHA secret is configured.
 */
export function Captcha({ onToken }: { onToken: (t: string) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const key = import.meta.env.VITE_TURNSTILE_SITE_KEY as string | undefined;
  useEffect(() => {
    if (!key) { onToken('dev'); return; }
    const render = () => window.turnstile?.render(ref.current, { sitekey: key, callback: onToken, 'expired-callback': () => onToken('') });
    if (window.turnstile) { render(); return; }
    const s = document.createElement('script');
    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
    s.async = true;
    s.onload = render;
    document.head.appendChild(s);
  }, [key, onToken]);
  return key ? <div ref={ref} className="mb-3" /> : null;
}
