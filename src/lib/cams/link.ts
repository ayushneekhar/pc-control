// One WebRTC connection to one go2rtc stream (react-native-webrtc), with reconnect + backoff and per-second
// stats. Three kinds, each its own peer connection, like the /cams/ page:
//   video  recvonly video only. Audio never rides along: the page measured ~400 ms of extra video delay in
//          Chrome when the cam's audio shared the connection (lip sync), and libwebrtc does the same here.
//   audio  recvonly audio only (Listen: the laptop mic "mic", or the CP Plus mic via "cat3hd").
//   talk   sendonly phone mic to go2rtc's "talk" backchannel (laptop speakers). The mic is captured only for
//          the life of this link and stopped on stop().
import { mediaDevices, MediaStream, RTCPeerConnection } from 'react-native-webrtc';
import { answerVideoCodec, signal, Signal, wsUrl } from './signal';

export type LinkKind = 'video' | 'audio' | 'talk';
export type LinkPhase = 'connecting' | 'live' | 'stalled' | 'retrying' | 'error' | 'stopped';

export type LinkState = {
  phase: LinkPhase;
  /** Frames decoded in the last second (video), 0 otherwise. */
  fps: number;
  /** Video codec go2rtc answered with (H264 / H265). */
  codec: string | null;
  /** libwebrtc's decoderImplementation, e.g. "c2.exynos.hevc.decoder" or "MediaCodec". */
  decoder: string | null;
  size: string | null;
  /** Average time frames waited in the jitter buffer (ms), since connect. */
  bufferMs: number | null;
  /** Round trip to go2rtc (ms), from the ICE candidate pair. */
  rttMs: number | null;
  everPlayed: boolean;
  error: string | null;
  /** Seconds until the next attempt while retrying. */
  retryIn: number | null;
  attempt: number;
};

export type LinkOpts = {
  base: string;
  src: string;
  kind: LinkKind;
  onState(s: LinkState): void;
  /** The remote stream to render (video) or null when it goes away. Audio links play by themselves. */
  onStream?(s: MediaStream | null): void;
  /** No first frame within this time: the link reports 'error' and stops retrying (HD fallback). Video only. */
  firstFrameMs?: number;
  /** Give up after this many failed attempts in a row (default: never). */
  maxAttempts?: number;
};

const BACKOFF = [500, 1000, 2000, 4000, 8000, 10000];
const STALL_MS = 6000; // live video with no new frames this long: reconnect
const FIRST_FRAME_MS = 15000; // a connection that never shows a frame: reconnect (cat3hd waits for a keyframe)
const DISCONNECT_GRACE_MS = 2500; // ICE 'disconnected' often recovers on its own (Wi-Fi <-> 5G)

export class CamLink {
  private o: LinkOpts;
  private pc: RTCPeerConnection | null = null;
  private sig: Signal | null = null;
  private mic: MediaStream | null = null;
  private timers: ReturnType<typeof setTimeout>[] = [];
  private statTimer: ReturnType<typeof setInterval> | null = null;
  private gen = 0;
  private fails = 0;
  private lastFrames = -1;
  private lastFrameAt = 0;
  private startedAt = 0;
  private state: LinkState = {
    phase: 'stopped',
    fps: 0,
    codec: null,
    decoder: null,
    size: null,
    bufferMs: null,
    rttMs: null,
    everPlayed: false,
    error: null,
    retryIn: null,
    attempt: 0,
  };

  constructor(o: LinkOpts) {
    this.o = o;
  }

  get src() {
    return this.o.src;
  }

  start() {
    this.fails = 0;
    this.connect();
  }

  /** Drop the connection and connect again now (e.g. back from the background). */
  restart() {
    this.fails = 0;
    this.connect();
  }

  stop() {
    this.gen++;
    this.teardown();
    this.set({ phase: 'stopped', fps: 0, retryIn: null });
  }

  private set(p: Partial<LinkState>) {
    this.state = { ...this.state, ...p };
    this.o.onState(this.state);
  }

  private teardown() {
    this.timers.forEach(clearTimeout);
    this.timers = [];
    if (this.statTimer) clearInterval(this.statTimer);
    this.statTimer = null;
    this.sig?.close();
    this.sig = null;
    if (this.pc) {
      const pc = this.pc;
      this.pc = null;
      try {
        pc.close();
      } catch {
        /* closed */
      }
    }
    if (this.mic) {
      this.mic.getTracks().forEach((t) => t.stop());
      this.mic.release();
      this.mic = null;
    }
    this.o.onStream?.(null);
  }

  private retry(why: string) {
    this.teardown();
    this.fails++;
    if (this.o.maxAttempts && this.fails >= this.o.maxAttempts) {
      this.set({ phase: 'error', error: why, fps: 0, retryIn: null });
      return;
    }
    const ms = BACKOFF[Math.min(this.fails - 1, BACKOFF.length - 1)];
    this.set({ phase: 'retrying', error: why, fps: 0, retryIn: Math.ceil(ms / 1000) });
    const g = ++this.gen;
    setTimeout(() => g === this.gen && this.connect(), ms);
  }

  private async connect() {
    const g = ++this.gen;
    this.teardown();
    this.lastFrames = -1;
    this.lastFrameAt = 0;
    this.startedAt = Date.now();
    this.set({ phase: 'connecting', fps: 0, retryIn: null, attempt: this.state.attempt + 1, bufferMs: null, rttMs: null });
    const { kind } = this.o;
    const pc = new RTCPeerConnection({ bundlePolicy: 'max-bundle', iceServers: [] });
    this.pc = pc;
    try {
      if (kind === 'talk') {
        const m = (await mediaDevices.getUserMedia({ audio: true, video: false })) as MediaStream;
        if (g !== this.gen) {
          m.getTracks().forEach((t) => t.stop());
          m.release();
          return;
        }
        this.mic = m;
        pc.addTransceiver(m.getAudioTracks()[0], { direction: 'sendonly' });
      } else {
        pc.addTransceiver(kind, { direction: 'recvonly' });
      }
    } catch (e) {
      if (g !== this.gen) return;
      this.teardown();
      this.set({ phase: 'error', error: kind === 'talk' ? 'Microphone unavailable' : `${(e as Error)?.message ?? e}`, retryIn: null });
      return;
    }

    pc.addEventListener('track', (ev: any) => {
      if (g !== this.gen || kind !== 'video') return;
      const s: MediaStream = ev.streams?.[0] ?? new MediaStream([ev.track]);
      this.o.onStream?.(s);
    });
    let discTimer: ReturnType<typeof setTimeout> | null = null;
    pc.addEventListener('connectionstatechange', () => {
      if (g !== this.gen) return;
      const st = pc.connectionState;
      if (st === 'connected') {
        if (discTimer) clearTimeout(discTimer);
        discTimer = null;
        this.sig?.close(); // signalling is done; go2rtc keeps the peer connection
        this.sig = null;
        this.fails = 0;
        if (kind !== 'video') this.set({ phase: 'live', error: null, everPlayed: true });
      } else if (st === 'failed' || st === 'closed') {
        this.retry('Connection lost');
      } else if (st === 'disconnected' && !discTimer) {
        discTimer = setTimeout(() => {
          if (g === this.gen && pc.connectionState !== 'connected') this.retry('Connection lost');
        }, DISCONNECT_GRACE_MS);
        this.timers.push(discTimer);
      }
    });

    this.sig = signal(pc as any, wsUrl(this.o.base, this.o.src), {
      onError: (msg) => g === this.gen && this.retry(msg),
    });
    this.statTimer = setInterval(() => g === this.gen && this.poll(g), 1000);
  }

  private async poll(g: number) {
    const pc = this.pc;
    if (!pc) return;
    let inb: any = null,
      out: any = null,
      pair: any = null;
    const codecs = new Map<string, string>();
    try {
      const rep: any = await pc.getStats();
      rep.forEach((s: any) => {
        if (s.type === 'inbound-rtp' && (s.kind ?? s.mediaType) === (this.o.kind === 'video' ? 'video' : 'audio')) inb = s;
        else if (s.type === 'outbound-rtp') out = s;
        else if (s.type === 'candidate-pair' && (s.nominated || s.selected) && s.state === 'succeeded') pair = s;
        else if (s.type === 'codec') codecs.set(s.id, s.mimeType);
      });
    } catch {
      return;
    }
    if (g !== this.gen) return;
    const now = Date.now();
    const rttMs = pair?.currentRoundTripTime != null ? Math.round(pair.currentRoundTripTime * 1000) : this.state.rttMs;
    if (this.o.kind !== 'video') {
      // audio: "live" once connected; talk counts packets out, listen packets in
      this.set({
        rttMs,
        codec:
          (inb?.codecId && codecs.get(inb.codecId)?.split('/')[1]) ||
          (out?.codecId && codecs.get(out.codecId)?.split('/')[1]) ||
          this.state.codec,
      });
      return;
    }
    const frames: number = inb?.framesDecoded ?? 0;
    const fps = this.lastFrames < 0 || frames < this.lastFrames ? 0 : frames - this.lastFrames;
    this.lastFrames = frames;
    if (fps > 0) this.lastFrameAt = now;
    const codecMime = inb?.codecId ? codecs.get(inb.codecId) : null;
    const patch: Partial<LinkState> = {
      fps,
      rttMs,
      codec: codecMime?.split('/')[1]?.toUpperCase() ?? answerVideoCodec(this.sig?.answer() ?? null) ?? this.state.codec,
      decoder: inb?.decoderImplementation ?? this.state.decoder,
      size: inb?.frameWidth ? `${inb.frameWidth}×${inb.frameHeight}` : this.state.size,
      bufferMs: inb?.jitterBufferEmittedCount
        ? Math.round((1000 * inb.jitterBufferDelay) / inb.jitterBufferEmittedCount)
        : this.state.bufferMs,
    };
    if (fps > 0) {
      patch.phase = 'live';
      patch.everPlayed = true;
      patch.error = null;
    } else if (this.lastFrameAt) {
      if (now - this.lastFrameAt > STALL_MS) return this.retry('No frames');
      patch.phase = 'stalled';
    } else if (now - this.startedAt > (this.o.firstFrameMs ?? FIRST_FRAME_MS)) {
      if (this.o.firstFrameMs) {
        this.teardown();
        this.set({ ...patch, phase: 'error', error: 'No picture', retryIn: null });
        return;
      }
      return this.retry('No picture');
    }
    this.set(patch);
  }
}
