// Canonical approval payload + key fingerprint, shared by the app and the server tests.
// Self-contained on purpose (no imports): server/tests runs this exact file under Node and checks it
// byte-for-byte against rootd's Python implementation.

export type Decision = 'approve' | 'deny';

export type SignableRequest = {
  id: string;
  nonce: string;
  expires: number;
  argv: string[];
};

/** UTF-8 encode (no TextEncoder dependency). Lone surrogates become U+FFFD like TextEncoder. */
export function utf8(s: string): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) {
        c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00);
        i++;
      } else c = 0xfffd;
    } else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return Uint8Array.from(out);
}

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

export function sha256Hex(data: Uint8Array): string {
  const h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  const bitLen = data.length * 8;
  const padded = new Uint8Array(((data.length + 9 + 63) >> 6) << 6);
  padded.set(data);
  padded[data.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(bitLen / 0x100000000));
  view.setUint32(padded.length - 4, bitLen >>> 0);
  const w = new Uint32Array(64);
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15], b = w[i - 2];
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K[i] + w[i]) >>> 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const mj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + mj) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
  }
  return Array.from(h, (x) => x.toString(16).padStart(8, '0')).join('');
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function base64ToBytes(b64: string): Uint8Array {
  const clean = b64.replace(/[^A-Za-z0-9+/]/g, '');
  const out: number[] = [];
  let buf = 0, bits = 0;
  for (const ch of clean) {
    buf = (buf << 6) | B64.indexOf(ch);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((buf >> bits) & 0xff);
    }
  }
  return Uint8Array.from(out);
}

/** Exactly what rootd hashes: JSON.stringify(argv) with no spaces, UTF-8. */
export function canonicalArgv(argv: string[]): string {
  return JSON.stringify(argv);
}

export function argvHash(argv: string[]): string {
  return sha256Hex(utf8(canonicalArgv(argv)));
}

/** Same rule as rootd.bad_char: control (except tab/newline), format, surrogate, line/paragraph separators. */
function makeBad(): RegExp {
  try {
    return new RegExp('(?![\\t\\n])[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]', 'u');
  } catch {
    // Engines without Unicode property escapes: the common dangerous ones. rootd checks again anyway.
    return /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u00ad\u061c\u180e\u200b-\u200f\u202a-\u202e\u2060-\u206f\u2028\u2029\ufeff\ufff9-\ufffb\ud800-\udfff]/;
  }
}
const BAD = makeBad();

export function argvLooksSafe(argv: unknown): argv is string[] {
  return Array.isArray(argv) && argv.length > 0 && argv.every((a) => typeof a === 'string' && !BAD.test(a));
}

/**
 * The bytes the phone signs. Built on the phone from the argv it shows, so a request can't be swapped
 * behind the screen: rootd recomputes this from the argv IT stored.
 */
export function buildPayload(decision: Decision, req: SignableRequest): string {
  if (decision !== 'approve' && decision !== 'deny') throw new Error('bad decision');
  if (!/^[0-9a-f]{16}$/.test(req.id) || !/^[0-9a-f]{32}$/.test(req.nonce)) throw new Error('bad request id/nonce');
  if (!Number.isSafeInteger(req.expires)) throw new Error('bad expiry');
  if (!argvLooksSafe(req.argv)) throw new Error('argv contains characters that cannot be shown faithfully');
  return `rootd-v1\n${decision}\n${req.id}\n${req.nonce}\n${req.expires}\n${argvHash(req.argv)}`;
}

/** Short enrollment code: first 64 bits of SHA-256 over the DER SubjectPublicKeyInfo, as XXXX-XXXX-XXXX-XXXX. */
export function keyFingerprint(spkiDerBase64: string): string {
  const h = sha256Hex(base64ToBytes(spkiDerBase64)).toUpperCase();
  return [0, 4, 8, 12].map((i) => h.slice(i, i + 4)).join('-');
}

/** Visible rendering of one argv element: shows tabs/newlines so nothing hides on screen. */
export function showArg(a: string): string {
  return a.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/\t/g, '\\t');
}
