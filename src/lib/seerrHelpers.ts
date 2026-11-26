import { SeerrStatus } from './api';

export function isRequestableMovie(status: SeerrStatus): boolean {
  return status === 'none' || status === 'deleted';
}

export function isRequestableSeason(status: SeerrStatus): boolean {
  return status === 'none' || status === 'deleted';
}

export function formatRuntime(minutes?: number | null): string | null {
  if (!minutes || minutes <= 0) return null;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h <= 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function formatDate(iso?: string | null): string | null {
  if (!iso) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso.trim());
  if (!m) return iso;
  const y = Number(m[1]);
  const mo = Number(m[2]) - 1;
  const d = Number(m[3]);
  if (mo < 0 || mo > 11 || d < 1 || d > 31) return iso;
  return `${MONTHS[mo]} ${d}, ${y}`;
}

export function computeAge(
  birthday?: string | null,
  deathday?: string | null,
  now: Date = new Date(),
): number | null {
  if (!birthday) return null;
  const bm = /^(\d{4})-(\d{2})-(\d{2})/.exec(birthday.trim());
  if (!bm) return null;
  const by = Number(bm[1]);
  const bmo = Number(bm[2]);
  const bd = Number(bm[3]);
  let ey: number;
  let emo: number;
  let ed: number;
  if (deathday) {
    const dm = /^(\d{4})-(\d{2})-(\d{2})/.exec(deathday.trim());
    if (!dm) return null;
    ey = Number(dm[1]);
    emo = Number(dm[2]);
    ed = Number(dm[3]);
  } else {
    ey = now.getFullYear();
    emo = now.getMonth() + 1;
    ed = now.getDate();
  }
  let age = ey - by;
  if (emo < bmo || (emo === bmo && ed < bd)) age -= 1;
  return age >= 0 && age < 150 ? age : null;
}

export function formatMoney(amount?: number | null): string | null {
  if (!amount || amount <= 0) return null;
  if (amount >= 1_000_000_000) return `$${(amount / 1_000_000_000).toFixed(2)}B`;
  if (amount >= 1_000_000) return `$${(amount / 1_000_000).toFixed(amount >= 100_000_000 ? 0 : 1)}M`;
  if (amount >= 1_000) return `$${Math.round(amount / 1_000)}K`;
  return `$${amount}`;
}

export function formatCompactCount(n?: number | null): string | null {
  if (!n || n <= 0) return null;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}k`;
  return String(n);
}
