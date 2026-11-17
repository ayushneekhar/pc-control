import { useEffect, useRef, useState } from 'react';
import { serverUrl } from '../api';
import { CamLink, LinkKind, LinkState } from './link';

export type LiveStream = { toURL(): string };

const IDLE: LinkState = {
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

const homeLinks = new Set<CamLink>();

/** Stop the Home wall's links before opening Cams, so the same cams are not pulled twice. */
export function stopHomeLive() {
  homeLinks.forEach((l) => l.stop());
  homeLinks.clear();
}

/** A CamLink that runs while `src` is set. Returns its state and (video) stream. */
export function useLiveCam(
  src: string | null,
  kind: LinkKind,
  opts?: { firstFrameMs?: number; maxAttempts?: number; onState?: (s: LinkState) => void; owner?: 'home' },
) {
  const [state, setState] = useState<LinkState>(IDLE);
  const [stream, setStream] = useState<LiveStream | null>(null);
  const cb = useRef(opts?.onState);
  useEffect(() => {
    cb.current = opts?.onState;
  });
  const firstFrameMs = opts?.firstFrameMs;
  const maxAttempts = opts?.maxAttempts;
  const owner = opts?.owner;
  useEffect(() => {
    if (!src) return;
    const l = new CamLink({
      base: serverUrl(),
      src,
      kind,
      onState: (s) => {
        setState(s);
        cb.current?.(s);
      },
      onStream: (s) => setStream(s as LiveStream | null),
      firstFrameMs,
      maxAttempts,
    });
    if (owner === 'home') homeLinks.add(l);
    l.start();
    return () => {
      if (owner === 'home') homeLinks.delete(l);
      l.stop();
      setStream(null);
    };
  }, [src, kind, firstFrameMs, maxAttempts, owner]);
  return src ? { state, stream } : { state: IDLE, stream: null };
}
