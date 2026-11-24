import { serverUrl, TransferJob } from './api';
import { parseSpaceJobsSseBlocks } from './spaceHelpers';

/**
 * Subscribe to live Google Drive transfer job updates via SSE (`/pc/api/space/jobs/stream`).
 * Uses XMLHttpRequest `onprogress` streaming (matching `src/lib/events.ts`), with exponential
 * reconnect backoff and a watchdog to recycle stale or large buffers.
 */
export function subscribeSpaceJobs(
  onJobs: (jobs: TransferJob[]) => void,
  onState?: (connected: boolean) => void,
): () => void {
  let stopped = false;
  let xhr: XMLHttpRequest | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let watchdog: ReturnType<typeof setInterval> | null = null;
  let retryDelay = 1500;
  let seen = 0;
  let buf = '';
  let lastData = 0;
  let connected = false;

  const setConnected = (next: boolean) => {
    if (next !== connected) {
      connected = next;
      onState?.(next);
    }
  };

  const closeXhr = () => {
    const cur = xhr;
    xhr = null;
    if (cur) {
      cur.onprogress = cur.onerror = cur.onload = cur.onreadystatechange = null;
      try {
        cur.abort();
      } catch {}
    }
  };

  const scheduleReconnect = (delay: number) => {
    closeXhr();
    setConnected(false);
    if (stopped) return;
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = setTimeout(connect, delay);
  };

  const connect = () => {
    if (stopped) return;
    retryTimer = null;
    seen = 0;
    buf = '';
    lastData = Date.now();
    const x = new XMLHttpRequest();
    xhr = x;
    x.open('GET', serverUrl() + '/pc/api/space/jobs/stream');
    x.setRequestHeader('Accept', 'text/event-stream');
    x.setRequestHeader('Cache-Control', 'no-cache');

    const consume = () => {
      if (xhr !== x) return;
      const text = x.responseText || '';
      if (text.length <= seen) return;
      lastData = Date.now();
      buf += text.slice(seen);
      seen = text.length;
      const { events, rest } = parseSpaceJobsSseBlocks(buf);
      buf = rest;
      for (const ev of events) {
        if (ev.event === 'snapshot' || ev.event === 'jobs') {
          retryDelay = 1500;
          setConnected(true);
          onJobs(ev.jobs);
        }
      }
    };

    const fail = () => {
      if (xhr !== x) return;
      const d = retryDelay;
      retryDelay = Math.min(retryDelay * 2, 15000);
      scheduleReconnect(d);
    };

    x.onprogress = consume;
    x.onreadystatechange = () => {
      if (x.readyState === 3 || x.readyState === 4) consume();
    };
    x.onerror = fail;
    x.onload = fail;
    x.send();
  };

  connect();
  watchdog = setInterval(() => {
    // Server pings every ~20s; recycle stale streams or large responseText buffers
    if (xhr && (Date.now() - lastData > 65000 || seen > 2_000_000)) {
      scheduleReconnect(0);
    }
  }, 10000);

  return () => {
    stopped = true;
    if (retryTimer) clearTimeout(retryTimer);
    if (watchdog) clearInterval(watchdog);
    retryTimer = null;
    watchdog = null;
    closeXhr();
    setConnected(false);
  };
}
