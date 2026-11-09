// In-app live updates: SSE over XMLHttpRequest (RN streams responseText via onprogress).
// Foreground only (the Root tab stays live while the app is open). Background notifications come via FCM.
import { serverUrl } from './api';

export type ServerEvent = { event: string; data: any };
type Listener = (e: ServerEvent) => void;

export class EventStream {
  private xhr: XMLHttpRequest | null = null;
  private seen = 0;
  private buf = '';
  private retry = 1000;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private watchdog: ReturnType<typeof setInterval> | null = null;
  private lastData = 0;
  private stopped = true;
  connected = false;

  constructor(private onEvent: Listener, private onState: (connected: boolean) => void) {}

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.open();
    this.watchdog = setInterval(() => {
      // server pings every ~25 s; also recycle long-lived connections so responseText doesn't grow forever
      if (this.xhr && (Date.now() - this.lastData > 65000 || this.seen > 2_000_000)) this.reconnect(0);
    }, 10000);
  }

  stop() {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    if (this.watchdog) clearInterval(this.watchdog);
    this.timer = this.watchdog = null;
    this.close();
    this.setConnected(false);
  }

  reconnectNow() {
    if (!this.stopped) this.reconnect(0);
  }

  private setConnected(c: boolean) {
    if (c !== this.connected) {
      this.connected = c;
      this.onState(c);
    }
  }

  private close() {
    const x = this.xhr;
    this.xhr = null;
    if (x) {
      x.onprogress = x.onerror = x.onload = x.onreadystatechange = null;
      try {
        x.abort();
      } catch {}
    }
  }

  private reconnect(delay: number) {
    this.close();
    this.setConnected(false);
    if (this.stopped) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.open(), delay);
  }

  private open() {
    this.timer = null;
    this.seen = 0;
    this.buf = '';
    this.lastData = Date.now();
    const x = new XMLHttpRequest();
    this.xhr = x;
    x.open('GET', serverUrl() + '/pc/api/events');
    x.setRequestHeader('Accept', 'text/event-stream');
    x.setRequestHeader('Cache-Control', 'no-cache');
    x.onprogress = () => this.consume(x);
    x.onreadystatechange = () => {
      if (x.readyState === 3 || x.readyState === 4) this.consume(x);
    };
    const fail = () => {
      if (this.xhr !== x) return;
      const d = this.retry;
      this.retry = Math.min(this.retry * 2, 30000);
      this.reconnect(d);
    };
    x.onerror = fail;
    x.onload = fail; // stream ended
    x.send();
  }

  private consume(x: XMLHttpRequest) {
    if (this.xhr !== x) return;
    const text = x.responseText || '';
    if (text.length <= this.seen) return;
    this.lastData = Date.now();
    this.buf += text.slice(this.seen);
    this.seen = text.length;
    let idx: number;
    while ((idx = this.buf.indexOf('\n\n')) >= 0) {
      const block = this.buf.slice(0, idx);
      this.buf = this.buf.slice(idx + 2);
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (!data.length) continue;
      let parsed: unknown;
      try {
        parsed = JSON.parse(data.join('\n'));
      } catch {
        continue;
      }
      if (event === 'hello') {
        this.retry = 1000;
        this.setConnected(true);
      }
      this.onEvent({ event, data: parsed });
    }
  }
}
