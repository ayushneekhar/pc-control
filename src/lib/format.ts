export function bytes(n: number | null | undefined, digits = 1): string {
  if (n == null || !isFinite(n)) return '–';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i === 0 ? 0 : digits)} ${u[i]}`;
}

export function rate(n: number | null | undefined): string {
  return n == null ? '–' : `${bytes(n)}/s`;
}

export function duration(secs: number): string {
  secs = Math.max(0, Math.floor(secs));
  const d = Math.floor(secs / 86400), h = Math.floor((secs % 86400) / 3600), m = Math.floor((secs % 3600) / 60);
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m`;
  return `${secs}s`;
}

export function ago(ts: number): string {
  const s = Date.now() / 1000 - ts;
  if (s < 45) return 'just now';
  return `${duration(s)} ago`;
}

export function clock(ts: number): string {
  const d = new Date(ts * 1000);
  const now = new Date();
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return hm;
  return `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} ${hm}`;
}
