// go2rtc WebRTC signalling over its WebSocket (/api/ws?src=<stream>), the same protocol as go2rtc's own
// video-rtc.js player that the /cams/ page uses: {type: 'webrtc/offer'|'webrtc/answer'|'webrtc/candidate'|'error',
// value}. Candidates trickle both ways, so the first frame doesn't wait for ICE gathering.
// No imports on purpose: scripts/cams/signal-test.mjs runs this file unchanged in a headless browser against the
// laptop's go2rtc.

/** The subset of RTCPeerConnection used here (browser and react-native-webrtc both have it). */
export type PeerLike = {
  createOffer(): Promise<{ type?: string | null; sdp?: string | null }>;
  setLocalDescription(d: any): Promise<void>;
  setRemoteDescription(d: any): Promise<void>;
  addIceCandidate(c: any): Promise<void>;
  addEventListener(type: string, cb: (ev: any) => void): void;
  removeEventListener(type: string, cb: (ev: any) => void): void;
};

export type Signal = {
  /** Closes the WebSocket (go2rtc keeps the peer connection; it lives until the peer closes it). */
  close(): void;
  /** The answer go2rtc sent, once there is one. */
  answer(): string | null;
};

export type SignalOpts = {
  /** Fatal signalling problem: socket closed before an answer, go2rtc error, bad SDP. Called at most once. */
  onError(msg: string): void;
  WebSocketImpl?: typeof WebSocket;
};

/** ws(s) URL of go2rtc's WebSocket API for a stream, from the server's https base. */
export function wsUrl(base: string, src: string): string {
  return base.replace(/^http/, 'ws').replace(/\/+$/, '') + '/api/ws?src=' + encodeURIComponent(src);
}

export function signal(pc: PeerLike, url: string, opts: SignalOpts): Signal {
  const WS = opts.WebSocketImpl ?? WebSocket;
  const ws = new WS(url);
  let closed = false;
  let failed = false;
  let offerSent = false;
  let answer: string | null = null;
  const outbox: string[] = []; // our candidates, held until the offer is out
  const inbox: string[] = []; // go2rtc's candidates, held until its answer is set

  const fail = (msg: string) => {
    if (failed || closed) return;
    failed = true;
    opts.onError(msg);
  };
  const send = (type: string, value: string) => {
    if (ws.readyState === 1) ws.send(JSON.stringify({ type, value }));
  };
  const addRemote = (c: string) =>
    pc.addIceCandidate({ candidate: c, sdpMid: '0', sdpMLineIndex: 0 }).catch(() => {
      /* a candidate we can't use (e.g. another address family) */
    });

  const onIce = (ev: any) => {
    const c: string | undefined = ev?.candidate?.candidate;
    if (!c) return; // end of gathering: go2rtc doesn't need it
    if (offerSent) send('webrtc/candidate', c);
    else outbox.push(c);
  };
  pc.addEventListener('icecandidate', onIce);

  ws.onopen = async () => {
    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      if (closed) return;
      send('webrtc/offer', offer.sdp ?? '');
      offerSent = true;
      outbox.splice(0).forEach((c) => send('webrtc/candidate', c));
    } catch (e) {
      fail(`offer: ${(e as Error)?.message ?? e}`);
    }
  };
  ws.onmessage = async (ev: MessageEvent) => {
    if (typeof ev.data !== 'string') return; // binary = MSE data, never asked for
    let msg: { type?: string; value?: string };
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    const value = typeof msg.value === 'string' ? msg.value : '';
    if (msg.type === 'webrtc/answer') {
      try {
        await pc.setRemoteDescription({ type: 'answer', sdp: value });
        answer = value;
        inbox.splice(0).forEach(addRemote);
      } catch (e) {
        fail(`answer: ${(e as Error)?.message ?? e}`);
      }
    } else if (msg.type === 'webrtc/candidate') {
      if (!value) return;
      if (answer) addRemote(value);
      else inbox.push(value);
    } else if (msg.type === 'error') {
      fail(value || 'go2rtc error');
    }
  };
  ws.onerror = () => fail('WebSocket error');
  ws.onclose = () => {
    if (!answer) fail('WebSocket closed before go2rtc answered');
  };

  return {
    close() {
      if (closed) return;
      closed = true;
      pc.removeEventListener('icecandidate', onIce);
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
      try {
        ws.close();
      } catch {
        /* already closed */
      }
    },
    answer: () => answer,
  };
}

/** Video codec go2rtc picked in its answer, e.g. "H265" (first video payload type of the m=video line). */
export function answerVideoCodec(sdp: string | null): string | null {
  if (!sdp) return null;
  const m = sdp.match(/^m=video \d+ [A-Z/]+ (\d+)/m);
  if (!m) return null;
  const r = sdp.match(new RegExp(`^a=rtpmap:${m[1]} ([A-Za-z0-9-]+)/`, 'm'));
  return r ? r[1].toUpperCase() : null;
}
