import { youtubeLink } from './cams/youtube';

export type SharedTarget =
  | { kind: 'youtube'; url: string }
  | { kind: 'magnet'; uri: string; name: string }
  | { kind: 'media'; url: string; source: 'imdb' | 'tmdb' | 'letterboxd' };

const MAGNET_RE = /\bmagnet:\?[^\s"'<>]+/i;
const URL_RE = /https?:\/\/[^\s"'<>]+/gi;

function magnetName(uri: string): string {
  const dn = /[?&]dn=([^&]+)/i.exec(uri);
  if (dn?.[1]) {
    try {
      const decoded = decodeURIComponent(dn[1].replace(/\+/g, ' ')).trim();
      if (decoded) return decoded;
    } catch {
      // fall through
    }
  }
  const hash = /xt=urn:btih:([A-Za-z0-9]+)/i.exec(uri);
  return hash?.[1] ? `Torrent ${hash[1].slice(0, 12)}` : 'Magnet link';
}

function mediaSource(url: string): 'imdb' | 'tmdb' | 'letterboxd' | null {
  try {
    const u = new URL(url);
    const host = u.hostname.toLowerCase().replace(/^www\./, '').replace(/^m\./, '');
    if (host === 'imdb.com' && /\/title\/tt\d+/i.test(u.pathname)) return 'imdb';
    if (host === 'themoviedb.org' && /\/(movie|tv)\/\d+/i.test(u.pathname)) return 'tmdb';
    if (host === 'letterboxd.com' && /\/film\/[^/]+/i.test(u.pathname)) return 'letterboxd';
    if (host === 'boxd.it' && /^\/[A-Za-z0-9]+/.test(u.pathname)) return 'letterboxd';
  } catch {
    return null;
  }
  return null;
}

/** Classify text shared to PC Control via Android ACTION_SEND (text/plain). */
export function parseSharedText(text: string): SharedTarget | null {
  if (!text) return null;
  const trimmed = text.trim();
  if (!trimmed) return null;

  const mag = MAGNET_RE.exec(trimmed);
  if (mag) {
    const uri = mag[0];
    return { kind: 'magnet', uri, name: magnetName(uri) };
  }

  const yt = youtubeLink(trimmed);
  if (yt) return { kind: 'youtube', url: yt };

  const urls = trimmed.match(URL_RE) ?? [];
  for (const raw of urls) {
    const clean = raw.replace(/[).,;!?]+$/, '');
    const src = mediaSource(clean);
    if (src) return { kind: 'media', url: clean, source: src };
  }

  return null;
}
