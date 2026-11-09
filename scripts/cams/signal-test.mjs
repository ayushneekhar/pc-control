// Headless check of the app's go2rtc signalling (src/lib/cams/signal.ts, run unchanged in Chromium/Helium):
// per stream it connects like the app does (video-only / audio-only / sendonly mic), then reports time to
// connected and to first frame, the codec go2rtc picked, fps and packets. Laptop only (no phone needed).
//   PW_CORE=<dir with node_modules/playwright-core> node scripts/cams/signal-test.mjs [base] [stream:kind ...]
//   base default http://127.0.0.1:1984 (go2rtc direct); pass the tailnet https URL to go through tailscale serve.
//   kinds: video (default), hd (video, offer relabelled so go2rtc's H.265 answer is checked), audio, talk.
// talk sends a silent fake mic (a silent WAV), so nothing audible reaches the laptop speakers.
import { Buffer } from 'buffer';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '../..');
const req = createRequire(path.join(process.env.PW_CORE || root, 'node_modules/'));
const { chromium } = req('playwright-core');
const ts = createRequire(path.join(root, 'node_modules/'))('typescript');

const args = process.argv.slice(2);
const base = args[0]?.startsWith('http') ? args.shift() : 'http://127.0.0.1:1984';
const jobs = (args.length ? args : ['cat:video', 'cat2:video', 'cat3:video', 'cat3hd:hd', 'cat3hd:audio', 'mic:audio']).map((a) => {
  const [src, kind = 'video'] = a.split(':');
  return { src, kind };
});

const js = ts.transpileModule(fs.readFileSync(path.join(root, 'src/lib/cams/signal.ts'), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText.replace(/^export /gm, '');

// 1 s of 8 kHz silence for the fake mic
const wav = path.join(os.tmpdir(), 'pcc-silence.wav');
{
  const n = 8000, b = Buffer.alloc(44 + n * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * 2, 4); b.write('WAVEfmt ', 8); b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(8000, 24); b.writeUInt32LE(16000, 28);
  b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(n * 2, 40);
  fs.writeFileSync(wav, b);
}

const browser = await chromium.launch({
  executablePath: process.env.BROWSER || '/usr/bin/helium',
  headless: true,
  args: ['--mute-audio', '--disable-audio-output', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
    `--use-file-for-fake-audio-capture=${wav}%noloop`, '--autoplay-policy=no-user-gesture-required'],
});
const page = await browser.newPage();
await page.goto(base.replace(/\/+$/, '') + '/links.html');
let fail = 0;
for (const job of jobs) {
  const r = await page.evaluate(async ({ js, base, job }) => {
    const mod = new Function(js + '\nreturn { signal, wsUrl, answerVideoCodec };')();
    const t0 = performance.now();
    const pc = new RTCPeerConnection({ bundlePolicy: 'max-bundle' });
    let mic = null;
    if (job.kind === 'talk') {
      mic = await navigator.mediaDevices.getUserMedia({ audio: true });
      pc.addTransceiver(mic.getAudioTracks()[0], { direction: 'sendonly' });
    } else pc.addTransceiver(job.kind === 'audio' ? 'audio' : 'video', { direction: 'recvonly' });
    // hd: like a phone with hardware HEVC, offer H.265 (relabelled H.264 payload type) and undo it on the answer
    let hdPt = null, real = null;
    const relabel = (sdp, a, b) => sdp.replace(`a=rtpmap:${hdPt} ${a}/90000`, `a=rtpmap:${hdPt} ${b}/90000`);
    const peer = job.kind !== 'hd' ? pc : {
      createOffer: async () => {
        real = await pc.createOffer();
        hdPt = real.sdp.match(/a=rtpmap:(\d+) H264\/90000/)[1];
        return { type: 'offer', sdp: relabel(real.sdp, 'H264', 'H265') };
      },
      setLocalDescription: () => pc.setLocalDescription(real),
      setRemoteDescription: (d) => pc.setRemoteDescription({ type: 'answer', sdp: relabel(d.sdp, 'H265', 'H264') }),
      addIceCandidate: (c) => pc.addIceCandidate(c),
      addEventListener: (t, f) => pc.addEventListener(t, f),
      removeEventListener: (t, f) => pc.removeEventListener(t, f),
    };
    let err = null, tConn = null, tFrame = null, rawAnswer = null;
    const v = document.createElement('video');
    v.muted = true; v.playsInline = true; document.body.append(v);
    pc.ontrack = (e) => { v.srcObject = new MediaStream([e.track]); v.play().catch(() => {}); };
    pc.onconnectionstatechange = () => { if (pc.connectionState === 'connected' && tConn == null) tConn = performance.now() - t0; };
    if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(() => { tFrame = performance.now() - t0; });
    const WS = class extends WebSocket { constructor(u) { super(u); this.addEventListener('message', (e) => { try { const m = JSON.parse(e.data); if (m.type === 'webrtc/answer') rawAnswer = m.value; } catch {} }); } };
    const sig = mod.signal(peer, mod.wsUrl(base, job.src), { onError: (m) => { err = m; }, WebSocketImpl: WS });
    const deadline = t0 + (job.kind === 'video' || job.kind === 'hd' ? 12000 : 6000);
    while (performance.now() < deadline && !err && !(tConn != null && (tFrame != null || job.kind === 'hd' || job.kind === 'audio' || job.kind === 'talk'))) await new Promise((r) => setTimeout(r, 50));
    sig.close();   // like the app: the socket goes once connected, the peer connection stays
    const s1 = {}; (await pc.getStats()).forEach((s) => { if (s.type === 'inbound-rtp' || s.type === 'outbound-rtp') s1[s.type] = s; });
    await new Promise((r) => setTimeout(r, 3000));
    const s2 = {}; (await pc.getStats()).forEach((s) => { if (s.type === 'inbound-rtp' || s.type === 'outbound-rtp') s2[s.type] = s; });
    const inb = s2['inbound-rtp'], out = s2['outbound-rtp'];
    const res = {
      src: job.src, kind: job.kind, err, state: pc.connectionState,
      connectedMs: tConn && Math.round(tConn), firstFrameMs: tFrame && Math.round(tFrame),
      codec: mod.answerVideoCodec(rawAnswer) || (rawAnswer?.match(/a=rtpmap:\d+ ([A-Za-z0-9]+)\//)?.[1] ?? null),
      fps: inb?.framesDecoded != null ? +((inb.framesDecoded - (s1['inbound-rtp']?.framesDecoded ?? 0)) / 3).toFixed(1) : undefined,
      size: inb?.frameWidth ? `${inb.frameWidth}x${inb.frameHeight}` : undefined,
      jitterBufferMs: inb?.jitterBufferEmittedCount ? Math.round(1000 * inb.jitterBufferDelay / inb.jitterBufferEmittedCount) : undefined,
      packetsIn: inb?.packetsReceived, bytesIn: inb?.bytesReceived, packetsOut: out?.packetsSent,
    };
    pc.close(); mic?.getTracks().forEach((t) => t.stop()); v.remove();
    return res;
  }, { js, base, job });
  const ok = !r.err && r.state === 'connected' && (r.kind === 'talk' ? r.packetsOut > 0 : r.packetsIn > 0) &&
    (r.kind !== 'hd' || r.codec === 'H265') && (r.kind !== 'video' || r.fps > 0);
  if (!ok) fail++;
  console.log(ok ? 'ok  ' : 'FAIL', JSON.stringify(r));
}
await browser.close();
process.exit(fail ? 1 : 0);
