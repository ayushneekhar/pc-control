#!/usr/bin/python3 -I
"""pc-api: the pc-control app's backend (user service, 127.0.0.1:1990, tailnet via `tailscale serve /pc`).

GET  /pc/api/info                     host, rootd status, latest APK info
GET  /pc/api/routes[?all=1]           tailscale serve routes + app tiles as friendly cards (API-only/HIDDEN hidden)
GET  /pc/api/services                 user systemd units from ~/.config/systemd/user
POST /pc/api/services/<unit>/restart
GET  /pc/api/services/<unit>/logs     last journal lines
GET  /pc/api/root/pending             pending + running root requests
GET  /pc/api/root/history?limit=N
GET  /pc/api/root/request/<id>        one request incl. output
POST /pc/api/root/approve|deny        {id, device, signature}: relayed to rootd, which verifies the signature
POST /pc/api/enroll                   {name, pubkey}: proposes a phone key; only `sudo rootd-admin enroll` confirms
GET  /pc/api/enroll/status?fingerprint=...
GET  /pc/api/events                   SSE for the open app (live Root tab): hello, request, update, rootd, enrolled, ping
POST /pc/api/push/register            {token, name}: store the phone's FCM token (re-sent on token refresh)
POST /pc/api/push/unregister          {token}
GET  /pc/api/push/status?token=...    {configured, registered, devices}
POST /pc/api/push/test                {token?}: send a test notification through FCM now, report per-token results
POST /pc/api/notify                   {category, title, body, telegram_text?, image_b64?, link?, cam?, ts?}: the
                                      notification router: sends to Telegram (Qwen bot) and/or the app (FCM) per
                                      category settings. cam/ts (a sighting's camera key and epoch ms) only ride in
                                      the app push, so a cat alert opens Replay at that moment.
                                      502 if Telegram was wanted but failed (callers then send to Telegram directly)
GET  /pc/api/notify/settings          {masters: {telegram, app}, categories: [...], channels: {...}}
POST /pc/api/notify/settings          {masters?: {telegram?, app?}, categories?: {<id>: {telegram?, app?}}}
POST /pc/api/notify/test              {channel: telegram|app}: a labelled test message on that channel
GET  /pc/api/notify/image/<id>        a notification's picture (the app fetches it for a big-picture notification)
GET  /pc/api/downloads                active qBittorrent downloads (all categories) for the app's live progress
                                      notification: {enabled, qbt, downloads: [...], total: {...}}. Read-only.
POST /pc/api/downloads/started        {hash?, title?}: "a download just started" (the Qwen bot calls it): wakes the
                                      phone's live progress (FCM "downloads") if that category is on
POST /pc/api/hook/radarr|sonarr|seerr webhook ingest, needs the shared secret in ~/.config/pc-control/hook-secret
                                      (Authorization: Bearer/Basic password, or X-PC-Hook-Secret); maps events to categories.
                                      A Grab also starts the Qwen bot's low-space check (~/tools/localagent/lowspace.py)
POST /pc/api/crash                   {kind, ts, reason, where, text, trace_b64?, app, device, android}: a crash report
                                      the app uploads on its next start (stored in DATA_DIR/crashes, newest 30)
GET  /pc/api/crash                    stored crash reports (no traces)
GET  /pc/app.apk                      LEGACY (pre-1.2 builds' updater): serves the last laptop-hosted APK. Releases now
                                      go to GitHub Releases (scripts/release-apk.sh); remove this with apk_info/send_apk.

Push: events are also sent through Firebase Cloud Messaging (HTTP v1, service account in
~/.config/pc-control/fcm-service-account.json) to the registered phones. FCM payloads carry only notification
text and ids: root requests say "Root approval needed" + the reason, never the command, and approval still
happens over the tailnet with the phone's biometric signature.

pc-api is NOT a security boundary for root: it runs as the agent's user, so it is treated as untrusted
by rootd. Only the phone's signature releases a command. The tailnet identity check below just keeps
other tailnet nodes/users away from the service controls.
"""
import base64, glob, json, os, queue, secrets, re, socket, subprocess, sys, threading, time, urllib.error, urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
PORT = int(os.environ.get("PCAPI_PORT", "1990"))
ROOTD_SOCK = os.environ.get("ROOTD_SOCK", "/run/rootd/rootd.sock")
ROOTD_INSTALLED_MARK = os.environ.get("ROOTD_INSTALLED_MARK", "/usr/local/lib/rootd/rootd.py")
CONF_DIR = os.path.join(HOME, ".config", "pc-control")
DATA_DIR = os.environ.get("PCAPI_DATA", os.path.join(HOME, ".local", "share", "pc-control"))
UNIT_DIR = os.path.join(HOME, ".config", "systemd", "user")
VERSION = 1


def load_json(p, default):
    try:
        with open(p) as f: return json.load(f)
    except (FileNotFoundError, ValueError):
        return default


CONFIG = load_json(os.path.join(CONF_DIR, "pcapi.json"), {})


def tailnet_owner():
    try:
        d = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, timeout=10).stdout)
        return d["User"][str(d["Self"]["UserID"])]["LoginName"]
    except Exception:
        return None


ALLOWED_LOGINS = set(CONFIG.get("allowed_logins") or filter(None, [tailnet_owner()]))
ALLOW_UNAUTH_LOCAL = os.environ.get("PCAPI_ALLOW_LOCAL", "1") == "1"


# ---------------------------------------------------------------- rootd client

def rootd_call(msg, timeout=15):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(ROOTD_SOCK)
        s.sendall((json.dumps(msg) + "\n").encode())
        data = b""
        while not data.endswith(b"\n"):
            c = s.recv(65536)
            if not c: break
            data += c
        return json.loads(data or b'{"error":"no reply from rootd"}')
    finally:
        s.close()


def rootd_state():
    if os.path.exists(ROOTD_SOCK):
        try:
            r = rootd_call({"op": "ping"}, timeout=3)
            return {"state": "ok", "version": r.get("version"), "devices": r.get("devices", 0)}
        except OSError:
            return {"state": "down"}
    return {"state": "down" if os.path.exists(ROOTD_INSTALLED_MARK) else "not_installed"}


# ---------------------------------------------------------------- event hub (rootd watch -> SSE clients)

class Hub:
    def __init__(self):
        self.clients, self.lock = set(), threading.Lock()
        self.status = "starting"
        threading.Thread(target=self.watch_loop, daemon=True).start()
        threading.Thread(target=self.ping_loop, daemon=True).start()

    def subscribe(self):
        q = queue.Queue(maxsize=200)
        with self.lock: self.clients.add(q)
        return q

    def unsubscribe(self, q):
        with self.lock: self.clients.discard(q)

    def publish(self, event, data):
        msg = (event, data)
        with self.lock:
            for q in list(self.clients):
                try: q.put_nowait(msg)
                except queue.Full:      # client is not reading: drop it, the app reconnects
                    self.clients.discard(q)
                    try: q.get_nowait(); q.put_nowait(None)
                    except (queue.Empty, queue.Full): pass

    def set_status(self, st, pending=None):
        if st != self.status or pending is not None:
            self.status = st
            self.publish("rootd", {"state": st, **({"pending": pending} if pending is not None else {})})

    def watch_loop(self):
        while True:
            try:
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.settimeout(60)        # rootd pings every 20 s
                s.connect(ROOTD_SOCK)
                s.sendall(b'{"op":"watch"}\n')
                f = s.makefile("rb")
                for line in f:
                    ev = json.loads(line)
                    t = ev.get("type")
                    if t == "hello": self.set_status("ok", ev.get("pending", []))
                    elif t in ("request", "update"):
                        self.publish(t, ev["request"])
                        if NOTIFY: NOTIFY.on_root_event(t, ev["request"])
                    elif t == "enrolled": self.publish("enrolled", {"fingerprint": ev.get("fingerprint")})
                raise OSError("watch closed")
            except (OSError, ValueError):
                self.set_status(rootd_state()["state"])
                time.sleep(3)

    def ping_loop(self):
        while True:
            time.sleep(25)
            self.publish("ping", {"ts": int(time.time())})


HUB = None
PUSH = None


# ---------------------------------------------------------------- push (FCM HTTP v1)

FCM_SA = os.environ.get("PCAPI_FCM_SA", os.path.join(CONF_DIR, "fcm-service-account.json"))
FCM_API = os.environ.get("PCAPI_FCM_API", "https://fcm.googleapis.com").rstrip("/")
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
TOKEN_RE = re.compile(r"^[A-Za-z0-9_:\-]{20,4096}$")
MAX_TOKENS = 10


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def short(token):
    return "…" + token[-6:]


def clip(s, n):
    s = " ".join(str(s or "").split())
    return s if len(s) <= n else s[:n - 1] + "…"


class Push:
    """Registered FCM tokens (DATA_DIR/push-tokens.json, mode 600) + an FCM HTTP v1 sender.
    The service-account key is read from disk, kept in memory and never logged or returned."""

    def __init__(self, sa_path=FCM_SA, data_dir=DATA_DIR, api=FCM_API):
        self.sa_path, self.api = sa_path, api
        self.tokens_file = os.path.join(data_dir, "push-tokens.json")
        self.lock = threading.Lock()
        t = load_json(self.tokens_file, {})
        self.tokens = {k: v for k, v in t.items() if TOKEN_RE.match(k) and isinstance(v, dict)} \
            if isinstance(t, dict) else {}
        self._sa, self._access = None, (None, 0.0)
        self.q = queue.Queue(maxsize=500)
        threading.Thread(target=self.send_loop, daemon=True).start()

    # ---- tokens
    def configured(self):
        return os.path.exists(self.sa_path)

    def save(self):
        os.makedirs(os.path.dirname(self.tokens_file), exist_ok=True)
        tmp = self.tokens_file + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f: json.dump(self.tokens, f, indent=1)
        os.replace(tmp, self.tokens_file)

    def register(self, token, name):
        if not isinstance(token, str) or not TOKEN_RE.match(token): return 400, {"error": "bad token"}
        now = int(time.time())
        with self.lock:
            old = self.tokens.get(token) or {}
            self.tokens[token] = {"name": clip(name, 80) or "phone", "added": old.get("added", now), "seen": now}
            while len(self.tokens) > MAX_TOKENS:     # keep the most recently seen phones
                del self.tokens[min(self.tokens, key=lambda k: self.tokens[k].get("seen", 0))]
            self.save()
            n = len(self.tokens)
        return 200, {"ok": True, "registered": True, "devices": n, "configured": self.configured()}

    def unregister(self, token):
        with self.lock:
            found = self.tokens.pop(token, None) is not None
            if found: self.save()
        return found

    def status(self, token=None):
        with self.lock:
            return {"configured": self.configured(), "devices": len(self.tokens),
                    **({"registered": token in self.tokens} if token is not None else {})}

    # ---- OAuth2 (service-account JWT -> access token, cached until shortly before it expires)
    def service_account(self):
        if self._sa is None:
            with open(self.sa_path) as f: sa = json.load(f)
            from cryptography.hazmat.primitives import serialization
            key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
            self._sa = {"key": key, "email": sa["client_email"], "kid": sa.get("private_key_id"),
                        "token_uri": sa.get("token_uri") or "https://oauth2.googleapis.com/token",
                        "project": sa["project_id"]}
        return self._sa

    def access_token(self, force=False):
        with self.lock:
            tok, exp = self._access
            if tok and not force and time.time() < exp - 120: return tok
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        sa = self.service_account()
        now = int(time.time())
        head = {"alg": "RS256", "typ": "JWT", **({"kid": sa["kid"]} if sa["kid"] else {})}
        claims = {"iss": sa["email"], "scope": FCM_SCOPE, "aud": sa["token_uri"], "iat": now, "exp": now + 3600}
        signing = b64url(json.dumps(head).encode()) + "." + b64url(json.dumps(claims).encode())
        sig = sa["key"].sign(signing.encode(), padding.PKCS1v15(), hashes.SHA256())
        form = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                       "assertion": signing + "." + b64url(sig)}).encode()
        req = urllib.request.Request(sa["token_uri"], data=form,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r: d = json.load(r)
        except urllib.error.HTTPError as e:
            try: err = json.load(e).get("error", "")
            except ValueError: err = ""
            raise RuntimeError(f"OAuth token request failed: HTTP {e.code} {err}") from None
        with self.lock:
            self._access = (d["access_token"], time.time() + int(d.get("expires_in", 3600)))
        return d["access_token"]

    # ---- sending
    def send_one(self, token, data, high=True, ttl=3600):
        """-> (ok, error_code_or_None, dead_token)"""
        sa = self.service_account()
        msg = {"message": {"token": token, "data": {k: str(v) for k, v in data.items()},
                           "android": {"priority": "HIGH" if high else "NORMAL", "ttl": f"{max(1, int(ttl))}s"}}}
        body = json.dumps(msg, ensure_ascii=False).encode()
        url = f"{self.api}/v1/projects/{sa['project']}/messages:send"
        for attempt in (0, 1):
            req = urllib.request.Request(url, data=body, headers={
                "Authorization": "Bearer " + self.access_token(force=attempt > 0),
                "Content-Type": "application/json; charset=utf-8"})
            try:
                with urllib.request.urlopen(req, timeout=20) as r: json.load(r)
                return True, None, False
            except urllib.error.HTTPError as e:
                try: err = json.load(e).get("error", {})
                except ValueError: err = {}
                if e.code == 401 and attempt == 0: continue        # stale access token: refresh once
                codes = [d.get("errorCode") for d in err.get("details", []) if isinstance(d, dict)]
                code = next((c for c in codes if c), None) or err.get("status") or f"HTTP {e.code}"
                dead = e.code == 404 or "UNREGISTERED" in codes or (
                    code == "INVALID_ARGUMENT" and "registration token" in str(err.get("message", "")).lower())
                return False, code, dead
        return False, "UNAUTHENTICATED", False

    def send_all(self, data, high=True, ttl=3600, only=None):
        with self.lock: targets = [t for t in self.tokens if only is None or t == only]
        results = []
        for t in targets:
            try: ok, code, dead = self.send_one(t, data, high, ttl)
            except Exception as e:  # network, OAuth, bad key file: report the kind, never the key
                ok, code, dead = False, clip(f"{type(e).__name__}: {e}", 200), False
            if dead: self.unregister(t)
            if not ok: print(f"push: {data.get('kind')} to {short(t)} failed: {code}{' (token dropped)' if dead else ''}",
                             file=sys.stderr, flush=True)
            results.append({"token": short(t), "ok": ok, "error": code, "dropped": dead})
        return results

    def enqueue(self, data, high=True, ttl=3600):
        if not self.configured() or not self.tokens: return
        try: self.q.put_nowait((data, high, ttl))
        except queue.Full: pass

    def send_loop(self):
        while True:
            data, high, ttl = self.q.get()
            try: self.send_all(data, high, ttl)
            except Exception as e: print(f"push: send failed: {type(e).__name__}", file=sys.stderr, flush=True)

    # ---- what gets pushed. Only notification text + ids: never the command, output or anything secret.
    def on_root_event(self, t, r):
        rid, st = str(r.get("id", "")), r.get("status")
        if not rid: return
        reason = clip(r.get("reason"), 300)
        if t == "request" and st == "pending":
            ttl = int(r.get("expires", 0)) - int(time.time())
            if ttl <= 0: return
            self.enqueue({"kind": "request", "id": rid, "title": "Root approval needed",
                          "body": reason or "A command is waiting for your fingerprint.",
                          "expires": int(r.get("expires", 0))}, True, ttl)
        elif t == "update" and st == "done":
            ex = r.get("exit", -1)
            self.enqueue({"kind": "result", "id": rid,
                          "title": "Command finished" if ex == 0 else f"Command failed (exit {ex})",
                          "body": reason}, True, 600)
        elif t == "update" and st != "pending":
            self.enqueue({"kind": "dismiss", "id": rid}, False, 600)

    def notify(self, nid, title, body, extra=None):
        self.enqueue({"kind": "notify", "id": nid, "title": title, "body": body, **(extra or {})}, True, 3600)


# ---------------------------------------------------------------- notification router (Telegram + FCM)

# id, label, description, default Telegram, default app, icon. Telegram defaults = what went to Telegram before
# the router existed; app defaults are on for things worth a phone buzz that you can't afford to miss.
CATEGORIES = [
    ("cats", "Cat alerts", "A cat on one of the cams. Telegram gets the photo.", True, False, "cat"),
    ("grabbed", "Download started", "Radarr or Sonarr grabbed a release.", True, False, "download-outline"),
    ("available", "Available to watch", "A Seerr request is ready in Jellyfin.", True, False, "play-circle-outline"),
    ("problems", "Download problems", "Failed requests, stuck or manual imports.", True, True, "alert-outline"),
    ("qwen_done", "Qwen downloads finished", "Downloads you started through the Qwen bot.", True, False,
     "check-circle-outline"),
    ("system", "Backups & system alerts", "Config backup failures, kernel fixes, recording digests.", True, True,
     "server-outline"),
    ("root", "Root approvals", "A root command is waiting for your fingerprint.", False, True, "shield-key-outline"),
    ("results", "Command results", "An approved root command finished or failed.", False, True, "console"),
    ("other", "Other messages", "Anything else sent to /pc/api/notify.", False, True, "message-outline"),
    ("live", "Live download progress", "Progress in the status bar and Now Bar while something downloads.", False,
     True, "progress-download"),
]
CAT_IDS = [c[0] for c in CATEGORIES]
APP_ONLY = {"live"}         # categories with no Telegram side (live progress is a phone notification, not a message)
CHANNELS = ("telegram", "app")
TG_API = os.environ.get("PCAPI_TG_API", "https://api.telegram.org").rstrip("/")
TG_TOKEN_FILE = os.environ.get("PCAPI_TG_TOKEN_FILE", CONFIG.get("telegram_token_file")
                               or os.path.join(HOME, "tools", "localagent", ".tgbot-token"))
TG_CHAT = str(CONFIG.get("telegram_chat") or os.environ.get("PCAPI_TG_CHAT") or "")   # in ~/.config/pc-control/pcapi.json
HOOK_SECRET_FILE = os.environ.get("PCAPI_HOOK_SECRET", os.path.join(CONF_DIR, "hook-secret"))
IMG_RE = re.compile(r"^[0-9a-f]{16}$")
MAX_IMAGES = 40
POSTER_HOSTS = ("image.tmdb.org",)          # remote images pc-api may fetch for a webhook (Seerr posters)


def image_type(b):
    if b[:3] == b"\xff\xd8\xff": return "image/jpeg"
    if b[:8] == b"\x89PNG\r\n\x1a\n": return "image/png"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP": return "image/webp"
    return None


def multipart(fields, file=None):
    """-> (body, content type). file = (field, filename, content type, bytes)."""
    b = secrets.token_hex(16)
    parts = [f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items()]
    if file:
        name, fn, ct, data = file
        parts.append(f'--{b}\r\nContent-Disposition: form-data; name="{name}"; filename="{fn}"\r\n'
                     f'Content-Type: {ct}\r\n\r\n'.encode() + data + b"\r\n")
    return b"".join(parts) + f"--{b}--\r\n".encode(), f"multipart/form-data; boundary={b}"


class Notify:
    """Routes a notification of a category to Telegram (Qwen bot) and/or the app (FCM), per the settings in
    DATA_DIR/notify-settings.json. Only unsolicited notifications come here: the Qwen bot's chat replies don't."""

    def __init__(self, push, data_dir=DATA_DIR, tg_api=TG_API, token_file=TG_TOKEN_FILE, chat=TG_CHAT):
        self.push, self.tg_api, self.token_file, self.chat = push, tg_api, token_file, chat
        self.file = os.path.join(data_dir, "notify-settings.json")
        self.img_dir = os.path.join(data_dir, "notify-images")
        self.lock = threading.Lock()
        s = load_json(self.file, {})
        self.s = s if isinstance(s, dict) else {}

    # ---- settings
    def settings(self):
        with self.lock:
            m, c = self.s.get("masters") or {}, self.s.get("categories") or {}
            cats = []
            for cid, label, desc, d_tg, d_app, icon in CATEGORIES:
                cur = c.get(cid) or {}
                tg_ok = cid not in APP_ONLY
                cats.append({"id": cid, "label": label, "description": desc, "icon": icon,
                             "telegram": tg_ok and bool(cur.get("telegram", d_tg)), "app": bool(cur.get("app", d_app)),
                             "defaults": {"telegram": d_tg, "app": d_app},
                             "channels": list(CHANNELS) if tg_ok else ["app"]})
            masters = {ch: bool(m.get(ch, True)) for ch in CHANNELS}
        return {"masters": masters, "categories": cats,
                "channels": {"telegram": {"configured": self.tg_token() is not None and bool(self.chat)},
                             "app": self.push.status()}}

    def update(self, b):
        m, c = b.get("masters"), b.get("categories")
        if m is not None and not (isinstance(m, dict) and all(k in CHANNELS and isinstance(v, bool) for k, v in m.items())):
            return 400, {"error": "masters must be {telegram?: bool, app?: bool}"}
        if c is not None and not (isinstance(c, dict) and all(
                k in CAT_IDS and isinstance(v, dict) and all(ch in CHANNELS and isinstance(x, bool) for ch, x in v.items())
                for k, v in c.items())):
            return 400, {"error": "categories must be {<category>: {telegram?: bool, app?: bool}}"}
        if c and any(k in APP_ONLY and "telegram" in v for k, v in c.items()):
            return 400, {"error": f"{', '.join(sorted(APP_ONLY))}: app only, there is no Telegram switch"}
        with self.lock:
            self.s.setdefault("masters", {}).update(m or {})
            for k, v in (c or {}).items(): self.s.setdefault("categories", {}).setdefault(k, {}).update(v)
            os.makedirs(os.path.dirname(self.file), exist_ok=True)
            tmp = self.file + ".tmp"
            with open(tmp, "w") as f: json.dump(self.s, f, indent=1)
            os.replace(tmp, self.file)
        return 200, self.settings()

    def wants(self, cat):
        """-> {"telegram": bool, "app": bool} after the master switches."""
        s = self.settings()
        row = next(x for x in s["categories"] if x["id"] == cat)
        return {ch: s["masters"][ch] and row[ch] for ch in CHANNELS}

    # ---- Telegram
    def tg_token(self):
        try:
            with open(self.token_file) as f: tok = f.read().split()[0]
            return tok if re.fullmatch(r"\d+:[A-Za-z0-9_-]{20,}", tok) else None
        except (OSError, IndexError):
            return None

    def tg_send(self, text, image=None):
        """-> (ok, error). sendPhoto when there's an image (caption max 1024), else sendMessage (max 4096).
        The token never appears in errors or logs."""
        tok = self.tg_token()
        if not tok: return False, "Telegram token missing"
        if not self.chat: return False, "telegram_chat missing in ~/.config/pc-control/pcapi.json"
        if image:
            method, (body, ct) = "sendPhoto", multipart({"chat_id": self.chat, "caption": text[:1024]},
                                                       ("photo", "image.jpg", image_type(image) or "image/jpeg", image))
        else:
            method, (body, ct) = "sendMessage", multipart({"chat_id": self.chat, "text": text[:4096] or "(empty)"})
        err = None
        for attempt in (0, 1):
            req = urllib.request.Request(f"{self.tg_api}/bot{tok}/{method}", data=body, headers={"Content-Type": ct})
            try:
                with urllib.request.urlopen(req, timeout=30) as r: res = json.load(r)
                if res.get("ok"): return True, None
                err = clip(res.get("description") or "not ok", 200)
            except urllib.error.HTTPError as e:
                try: err = f"HTTP {e.code} {clip(json.load(e).get('description', ''), 200)}"
                except ValueError: err = f"HTTP {e.code}"
                if e.code < 500 and e.code != 429: break       # bad request: retrying won't help
            except (OSError, ValueError) as e:
                err = clip(f"{type(e).__name__}: {e}", 200)
            if attempt == 0: time.sleep(2)
        return False, (err or "failed").replace(tok, "<token>")

    # ---- images (kept so the phone can fetch a big picture over the tailnet)
    def store_image(self, nid, data):
        os.makedirs(self.img_dir, mode=0o700, exist_ok=True)
        with open(os.path.join(self.img_dir, nid), "wb") as f: f.write(data)
        files = sorted(os.scandir(self.img_dir), key=lambda e: e.stat().st_mtime)
        for e in files[:-MAX_IMAGES]:
            try: os.remove(e.path)
            except OSError: pass

    def image(self, nid):
        if not IMG_RE.match(nid): return None
        try:
            with open(os.path.join(self.img_dir, nid), "rb") as f: return f.read()
        except OSError:
            return None

    @staticmethod
    def fetch_poster(url):
        try:
            u = urllib.parse.urlsplit(url)
            if u.scheme != "https" or u.hostname not in POSTER_HOSTS: return None
            with urllib.request.urlopen(url, timeout=15) as r: data = r.read(8 * 1024 * 1024 + 1)
            return data if len(data) <= 8 * 1024 * 1024 and image_type(data) else None
        except (OSError, ValueError):
            return None

    # ---- send
    def send(self, cat, title, body="", telegram_text=None, image=None, link=None, cam=None, ts=None):
        """-> (http code, result). 502 when Telegram was wanted but failed, so callers can fall back to sending
        straight to Telegram themselves (alerts are never lost because pc-api had a bad moment).
        cam/ts are optional app-push extras (a sighting's camera key and epoch ms); invalid ones are dropped and
        never change the Telegram path."""
        if cat not in CAT_IDS or cat in APP_ONLY:
            return 400, {"error": f"unknown category (one of: {', '.join(c for c in CAT_IDS if c not in APP_ONLY)})"}
        title, body = clip(title, 120), str(body or "").strip()[:3000]
        if not title: return 400, {"error": "title required"}
        if image is not None and (len(image) > 10 * 1024 * 1024 or not image_type(image)):
            return 400, {"error": "image must be a JPEG, PNG or WebP under 10 MB"}
        if link is not None and not re.fullmatch(r"https?://[^\s]{1,500}", str(link)):
            return 400, {"error": "link must be an http(s) URL"}
        w, nid, res = self.wants(cat), secrets.token_hex(8), {"ok": True, "category": cat}
        res["id"] = nid
        if w["app"]:
            if not self.push.configured(): res["app"] = "not configured"
            elif not self.push.tokens: res["app"] = "no phones registered"
            else:
                extra = {"cat": cat, **({"link": link} if link else {})}
                if cam is not None and re.fullmatch(r"[A-Za-z0-9_-]{1,32}", str(cam)):
                    extra["cam"] = str(cam)
                if ts is not None:
                    try: ts_ms = int(float(ts))
                    except (TypeError, ValueError): ts_ms = None
                    if ts_ms is not None and 0 < ts_ms < 4102444800000: extra["ts"] = ts_ms
                if image:
                    self.store_image(nid, image)
                    extra["image"] = f"/pc/api/notify/image/{nid}"
                self.push.notify(nid, title, clip(body, 500), extra)
                res["app"] = "queued"
        else: res["app"] = "off"
        code = 200
        if w["telegram"]:
            text = telegram_text or (f"{title}\n{body}" if body else title)
            if link and link not in text: text += f"\n{link}"
            ok, err = self.tg_send(text, image)
            res["telegram"] = "sent" if ok else f"failed: {err}"
            if not ok: code, res["ok"] = 502, False
        else: res["telegram"] = "off"
        return code, res

    def live_kick(self, why=""):
        """A download started: wake the phone's live progress notification with a high-priority data message
        (the app then polls /pc/api/downloads over the tailnet for a while). No names in the payload.
        Throttled, so a season pack's dozen grabs make one push. -> "queued" | "off" | "throttled" | why not."""
        if not self.wants("live")["app"]: return "off"
        if not self.push.configured(): return "not configured"
        if not self.push.tokens: return "no phones registered"
        now = time.time()
        with self.lock:
            if now - getattr(self, "_live_at", 0) < LIVE_KICK_GAP: return "throttled"
            self._live_at = now
        self.push.enqueue({"kind": "downloads", "id": "live", **({"why": why} if why else {})}, True, 300)
        return "queued"

    def send_async(self, *a, **kw):
        def run():
            try:
                code, r = self.send(*a, **kw)
                if code != 200: print(f"notify: {a[0]}: {r}", file=sys.stderr, flush=True)
            except Exception as e:
                print(f"notify: {a[0]} failed: {type(e).__name__}", file=sys.stderr, flush=True)
        threading.Thread(target=run, daemon=True).start()

    def test(self, channel):
        host = socket.gethostname()
        if channel == "telegram":
            ok, err = self.tg_send(f"🧪 Test from pc-control: Telegram notifications from {host} work.")
            return (200, {"ok": True}) if ok else (502, {"error": err})
        if channel == "app":
            if not self.push.configured(): return 503, {"error": "FCM is not configured on the laptop"}
            r = self.push.send_all({"kind": "notify", "id": "test-" + secrets.token_hex(6), "cat": "other",
                                    "title": "Test notification", "body": f"Push from {host} works."}, True, 300)
            return 200, {"ok": any(x["ok"] for x in r), "sent": sum(x["ok"] for x in r), "results": r}
        return 400, {"error": "channel must be telegram or app"}

    # ---- rootd events: the app gets the usual push; Telegram (off by default) gets the same text, never the command
    def on_root_event(self, t, r):
        st = r.get("status")
        cat = "root" if t == "request" and st == "pending" else "results" if t == "update" and st == "done" else None
        w = self.wants(cat) if cat else {"telegram": False, "app": True}      # dismissals always go to the app
        if w["app"] and self.push.configured(): self.push.on_root_event(t, r)
        if cat and w["telegram"]:
            reason = clip(r.get("reason"), 300)
            if cat == "root": text = f"🔐 Root approval needed\n{reason}\nApprove it in the pc-control app."
            else:
                ex = r.get("exit", -1)
                text = f"{'✅ Command finished' if ex == 0 else f'❌ Command failed (exit {ex})'}\n{reason}"
            threading.Thread(target=self.tg_send, args=(text,), daemon=True).start()


# ---------------------------------------------------------------- webhooks (Radarr, Sonarr, Seerr -> categories)

def hook_secret():
    """Shared secret for /pc/api/hook/*, created on first use (mode 600, never logged)."""
    try:
        with open(HOOK_SECRET_FILE) as f:
            s = f.read().strip()
            if len(s) >= 32: return s
    except OSError:
        pass
    os.makedirs(os.path.dirname(HOOK_SECRET_FILE), exist_ok=True)
    s = secrets.token_urlsafe(32)
    fd = os.open(HOOK_SECRET_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: f.write(s + "\n")
    return s


def hook_authorized(headers, secret):
    """Accepts `Authorization: Bearer <s>`, a bare `Authorization: <s>` (Seerr's auth header field), Basic auth
    with the secret as password (Radarr/Sonarr username/password fields) or `X-PC-Hook-Secret: <s>`."""
    cands = [headers.get("X-PC-Hook-Secret") or ""]
    a = (headers.get("Authorization") or "").strip()
    if a.lower().startswith("bearer "): cands.append(a[7:].strip())
    elif a.lower().startswith("basic "):
        try: cands.append(base64.b64decode(a[6:].strip()).decode().partition(":")[2])
        except (ValueError, UnicodeDecodeError): pass
    else: cands.append(a)
    return any(c and secrets.compare_digest(c.encode(), secret.encode()) for c in cands)


def human_size(n):
    try: n = float(n)
    except (TypeError, ValueError): return ""
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or u == "TB": return f"{n:.1f} {u}" if u in ("GB", "TB") else f"{n:.0f} {u}"
        n /= 1024


def map_hook(source, p):
    """Webhook payload -> dict(category, title, body[, link, image_url]) or None (event we don't notify about)."""
    if source in ("radarr", "sonarr"):
        app, ev = source.title(), p.get("eventType")
        if ev == "Test":
            return {"category": "grabbed", "title": f"🧪 {app} test",
                    "body": f"Test from {app}: its webhook to pc-api works."}
        if source == "radarr":
            m = p.get("movie") or p.get("remoteMovie") or {}
            name = f"{m.get('title', '?')}" + (f" ({m['year']})" if m.get("year") else "")
        else:
            s = p.get("series") or {}
            eps = p.get("episodes") or []
            tag = ", ".join(f"S{e.get('seasonNumber', 0):02d}E{e.get('episodeNumber', 0):02d}" for e in eps[:4])
            name = f"{s.get('title', '?')}" + (f" {tag}" if tag else "")
            if len(eps) == 1 and eps[0].get("title"): name += f" · {eps[0]['title']}"
        rel = p.get("release") or {}
        if ev == "Grab":
            bits = [x for x in (rel.get("quality"), human_size(rel.get("size")), rel.get("indexer")) if x]
            return {"category": "grabbed", "title": f"{'🎬 Movie' if source == 'radarr' else '📺 Episode'} grabbed",
                    "body": name + (f"\n{' · '.join(bits)}" if bits else ""), "name": name}
        if ev == "ManualInteractionRequired":
            msgs = [m for s in (p.get("downloadStatusMessages") or []) for m in (s.get("messages") or [])]
            why = msgs[0] if msgs else (p.get("downloadStatus") or "needs a manual import")
            return {"category": "problems", "title": f"⚠️ {app} needs manual interaction",
                    "body": f"{name}\n{clip(why, 300)}"}
        return None
    if source == "seerr":
        nt = p.get("notification_type")
        cat = {"MEDIA_AVAILABLE": "available", "MEDIA_FAILED": "problems", "TEST_NOTIFICATION": "available"}.get(nt)
        if not cat: return None
        title = clip(p.get("event") or ("Seerr test" if nt == "TEST_NOTIFICATION" else "Seerr"), 120)
        if nt == "TEST_NOTIFICATION": title = "🧪 " + title
        elif nt == "MEDIA_AVAILABLE": title = "🍿 " + title
        else: title = "⚠️ " + title
        body = "\n".join(x for x in (clip(p.get("subject"), 200), clip(p.get("message"), 400)) if x)
        out = {"category": cat, "title": title, "body": body}
        media = p.get("media") or {}
        base = str(CONFIG.get("seerr_url") or "").rstrip("/")     # Seerr's public URL, from pcapi.json
        if base and media.get("media_type") in ("movie", "tv") and str(media.get("tmdbId", "")).isdigit():
            out["link"] = f"{base}/{media['media_type']}/{media['tmdbId']}"
        if p.get("image"): out["image_url"] = p["image"]
        return out
    return None


NOTIFY = None

LOWSPACE = os.environ.get("PCAPI_LOWSPACE", os.path.join(HOME, "tools", "localagent", "lowspace.py"))


def space_check(source, p):
    """A Radarr/Sonarr grab: start the Qwen bot's low-space check (will the downloads in progress take the HDD
    below its line? It alerts on Telegram at most once a day). Background, fire and forget: never raises, so it
    can't get in the way of the grab notification. Hash and size go as separate argv items, never a shell.
    -> "started" | why not."""
    try:
        if not os.path.isfile(LOWSPACE): return "no lowspace.py"
        args = ["/usr/bin/python3", LOWSPACE, "event", "--source", source]
        h = str(p.get("downloadId") or "")
        if re.fullmatch(r"[0-9A-Fa-f]{40}", h): args += ["--hash", h.upper()]
        size = (p.get("release") or {}).get("size")
        if isinstance(size, int) and not isinstance(size, bool) and size > 0: args += ["--size", str(size)]
        proc = subprocess.Popen(args, cwd=os.path.dirname(LOWSPACE), stdin=subprocess.DEVNULL, start_new_session=True)
        threading.Thread(target=proc.wait, daemon=True).start()     # reaps it
        return "started"
    except Exception as e:
        print(f"lowspace: {source} check didn't start: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
        return "failed"


# ---------------------------------------------------------------- downloads (qBittorrent -> the app's live progress)

LIVE_KICK_GAP = 20          # s between "downloads active" pushes
QBT_URL = os.environ.get("PCAPI_QBT_URL", CONFIG.get("qbt_url") or "http://127.0.0.1:8080").rstrip("/")
QBT_USER = CONFIG.get("qbt_user") or "admin"
QBT_PASSWORD_FILE = os.environ.get("PCAPI_QBT_PASSWORD_FILE", CONFIG.get("qbt_password_file")
                                   or os.path.join(HOME, "tools", "localagent", ".qbt-password"))
# qBittorrent category -> (app, base URL, config.xml holding its API key). Used only to show the movie/show title
# instead of the release name; the key is read from disk when needed and never logged or returned.
ARR = {"radarr": ("radarr", "http://127.0.0.1:7878", os.path.join(HOME, ".config", "Radarr", "config.xml")),
       "tv-sonarr": ("sonarr", "http://127.0.0.1:8989", os.path.join(HOME, ".config", "Sonarr", "config.xml"))}
# qBittorrent states that count as "downloading" -> what the app shows. Paused/stopped, errored and complete
# torrents are left out, so they never keep the phone's live notification (and its polling) alive.
DL_STATE = {"downloading": "downloading", "forcedDL": "downloading", "stalledDL": "stalled", "metaDL": "metadata",
            "forcedMetaDL": "metadata", "queuedDL": "queued", "checkingDL": "checking",
            "checkingResumeData": "checking", "allocating": "checking", "moving": "moving"}
HASH_RE = re.compile(r"^[0-9A-Fa-f]{40}$")
MAX_TITLES = 300


def release_title(name):
    """Release name -> something readable: 'Dallas.Buyers.Club.2013.1080p.BluRay' -> 'Dallas Buyers Club (2013)',
    'Show.Name.S02E05.720p' -> 'Show Name S02E05'. Falls back to the cleaned name."""
    n = re.sub(r"^www\.\S+\s*-\s*", "", " ".join(str(name or "").split()))
    n = re.sub(r"\.(mkv|mp4|avi)$", "", n, flags=re.I)
    if " " not in n: n = re.sub(r"[._]", " ", n)
    m = re.match(r"(.+?)[\s.(\[-]+(S\d{1,2}(?:E\d{1,3})?(?:-?E\d{1,3})?)\b", n, re.I)
    if m: return f"{m.group(1).strip(' -')} {m.group(2).upper()}"
    m = re.match(r"(.+?)[\s(\[]+((?:19|20)\d{2})(?:[\s)\]]|$)", n)
    if m and m.group(1).strip(): return f"{m.group(1).strip(' -')} ({m.group(2)})"
    return n.strip() or str(name or "")


class Downloads:
    """Read-only view of qBittorrent's active downloads, with Radarr/Sonarr titles when they're cheap to get:
    first from their Grab webhooks (downloadId -> title, kept in DATA_DIR/download-titles.json), then from their
    queue API (at most once per 30 s per app, only for torrents we have no title for)."""

    def __init__(self, url=QBT_URL, user=QBT_USER, password_file=QBT_PASSWORD_FILE, arr=None, data_dir=DATA_DIR):
        self.url, self.user, self.password_file = url, user, password_file
        self.arr = ARR if arr is None else arr
        self.titles_file = os.path.join(data_dir, "download-titles.json")
        t = load_json(self.titles_file, {})
        self.titles = {k: v for k, v in t.items() if HASH_RE.match(k) and isinstance(v, str)} if isinstance(t, dict) else {}
        self.lock, self.opener = threading.Lock(), None
        self.arr_at = {}
        self.cache = (0.0, None)

    # ---- titles
    def remember(self, h, title):
        if not (isinstance(h, str) and HASH_RE.match(h) and title): return False
        with self.lock:
            self.titles[h.upper()] = clip(title, 120)
            while len(self.titles) > MAX_TITLES: del self.titles[next(iter(self.titles))]
            try:
                os.makedirs(os.path.dirname(self.titles_file), exist_ok=True)
                tmp = self.titles_file + ".tmp"
                with open(tmp, "w") as f: json.dump(self.titles, f, indent=1)
                os.replace(tmp, self.titles_file)
            except OSError: pass
        return True

    def arr_lookup(self, cat):
        """Radarr/Sonarr queue -> {HASH: title}. Errors (app down, no key) just mean no titles."""
        app, base, conf = self.arr[cat]
        try:
            with open(conf) as f: key = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())[1]
            q = "includeMovie=true" if app == "radarr" else "includeSeries=true&includeEpisode=true"
            req = urllib.request.Request(f"{base}/api/v3/queue?pageSize=200&{q}", headers={"X-Api-Key": key})
            with urllib.request.urlopen(req, timeout=5) as r: recs = json.load(r).get("records") or []
        except (OSError, ValueError, TypeError, AttributeError):
            return {}
        out = {}
        for x in recs:
            h = str(x.get("downloadId") or "").upper()
            if not HASH_RE.match(h): continue
            if app == "radarr":
                m = x.get("movie") or {}
                if m.get("title"): out[h] = m["title"] + (f" ({m['year']})" if m.get("year") else "")
            else:
                s, e = x.get("series") or {}, x.get("episode") or {}
                if not s.get("title"): continue
                tag = f" S{e['seasonNumber']:02d}E{e['episodeNumber']:02d}" if isinstance(e.get("seasonNumber"), int) \
                    and isinstance(e.get("episodeNumber"), int) else ""
                # a season pack is several queue records with the same downloadId: keep just the season
                if h in out and tag: tag = f" S{e['seasonNumber']:02d}"
                out[h] = s["title"] + tag
        return out

    # ---- qBittorrent
    def qbt(self, method, **params):
        data = urllib.parse.urlencode(params).encode()
        for attempt in (0, 1):
            if self.opener is None:
                import http.cookiejar
                jar = http.cookiejar.CookieJar()
                op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
                with open(self.password_file) as f: pw = f.read().strip()
                form = urllib.parse.urlencode({"username": self.user, "password": pw}).encode()
                op.open(f"{self.url}/api/v2/auth/login", form, timeout=5).close()
                if not any("SID" in c.name for c in jar): raise PermissionError("qBittorrent login failed")
                self.opener = op
            try:
                with self.opener.open(f"{self.url}/api/v2/{method}", data, timeout=8) as r: return json.load(r)
            except urllib.error.HTTPError as e:
                self.opener = None
                if e.code in (401, 403) and attempt == 0: continue      # session expired (qBittorrent restarted)
                raise
        raise PermissionError("qBittorrent login failed")

    def active(self):
        """-> {"qbt": "ok"|"down"|"auth", "downloads": [...], "total": {...}}. Cached ~1.5 s (one phone polls ~3 s)."""
        with self.lock:
            at, res = self.cache
            if res is not None and time.time() - at < 1.5: return res
            try:
                items, st = self.qbt("torrents/info", filter="all", sort="added_on"), "ok"
            except PermissionError:
                items, st = [], "auth"
            except (OSError, ValueError):
                items, st = [], "down"
            items = [t for t in items if isinstance(t, dict) and t.get("state") in DL_STATE]
        missing = {t.get("category") for t in items if t.get("category") in self.arr
                   and str(t.get("hash", "")).upper() not in self.titles}
        for cat in missing:
            if time.time() - self.arr_at.get(cat, 0) < 30: continue
            self.arr_at[cat] = time.time()
            for h, title in self.arr_lookup(cat).items():
                if h not in self.titles: self.remember(h, title)
        out = []
        for t in items:
            h = str(t.get("hash", "")).upper()
            size, done = int(t.get("size") or 0), int(t.get("completed") or 0)
            eta = int(t.get("eta") or 0)
            state = DL_STATE[t["state"]]
            if state == "downloading" and int(t.get("dlspeed") or 0) == 0: state = "stalled"
            out.append({"id": h[:16].lower(), "name": self.titles.get(h) or release_title(t.get("name")),
                        "torrent": clip(t.get("name"), 200), "category": str(t.get("category") or ""),
                        "state": state, "progress": round(min(1.0, max(0.0, float(t.get("progress") or 0))), 4),
                        "speed": int(t.get("dlspeed") or 0), "eta": eta if 0 < eta < 8640000 else None,
                        "size": size, "done": min(done, size) if size else done,
                        "seeds": int(t.get("num_seeds") or 0), "added": int(t.get("added_on") or 0)})
        rank = {"downloading": 0, "stalled": 1, "metadata": 2, "checking": 3, "moving": 3, "queued": 4}
        out.sort(key=lambda d: (rank.get(d["state"], 5), d["added"]))
        sized = [d for d in out if d["size"] > 0]
        etas = [d["eta"] for d in out if d["eta"]]
        total = {"count": len(out), "downloading": sum(d["state"] == "downloading" for d in out),
                 "progress": round(sum(d["done"] for d in sized) / sum(d["size"] for d in sized), 4) if sized else None,
                 "speed": sum(d["speed"] for d in out), "eta": max(etas) if etas else None,
                 "size": sum(d["size"] for d in sized), "done": sum(d["done"] for d in sized)}
        res = {"qbt": st, "downloads": out, "total": total}
        with self.lock: self.cache = (time.time(), res)
        return res


DOWNLOADS = None


# ---------------------------------------------------------------- app crash reports

class Crashes:
    """Crash reports the app uploads on its next start (POST /pc/api/crash): a Java/JS stack it wrote itself, or
    Android's ApplicationExitInfo for a native crash or ANR (with the tombstone / trace when Android has one).
    Kept as DATA_DIR/crashes/<time>-<kind>-<id>.json (+ .trace, the raw tombstone protobuf or ANR text; `strings`
    shows the backtrace), mode 600, newest KEEP only. GET /pc/api/crash lists them (no traces)."""
    KEEP = 30
    KINDS = {"java", "js", "native", "anr", "other"}
    MAX_TRACE = 1024 * 1024

    def __init__(self, data_dir=DATA_DIR):
        self.dir = os.path.join(data_dir, "crashes")
        self.lock = threading.Lock()

    def store(self, b):
        kind = str(b.get("kind") or "other")
        if kind not in self.KINDS: kind = "other"
        try: ts = float(b.get("ts") or time.time())
        except (TypeError, ValueError): ts = time.time()
        trace = None
        if b.get("trace_b64"):
            try: trace = base64.b64decode(str(b["trace_b64"]), validate=True)
            except ValueError: return 400, {"error": "trace_b64 is not base64"}
            if len(trace) > self.MAX_TRACE: trace = trace[:self.MAX_TRACE]
        rec = {"kind": kind, "ts": ts, "received": time.time(),
               "app": clip(b.get("app") or "", 40), "device": clip(b.get("device") or "", 80),
               "android": clip(b.get("android") or "", 20), "reason": clip(b.get("reason") or "", 200),
               "where": clip(b.get("where") or "", 200), "text": str(b.get("text") or "")[:200_000],
               "trace_bytes": len(trace) if trace else 0}
        name = time.strftime("%Y%m%d-%H%M%S", time.localtime(ts)) + f"-{kind}-{secrets.token_hex(3)}"
        with self.lock:
            os.makedirs(self.dir, mode=0o700, exist_ok=True)
            for ext, data in ((".json", json.dumps(rec, indent=1).encode()), (".trace", trace)):
                if data is None: continue
                fd = os.open(os.path.join(self.dir, name + ext), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "wb") as f: f.write(data)
            self.prune()
        print(f"pc-api: crash report {name} ({rec['app']}, {rec['reason'] or kind})", flush=True)
        return 200, {"ok": True, "id": name}

    def prune(self):
        names = sorted({f.rsplit(".", 1)[0] for f in os.listdir(self.dir) if f.endswith(".json")})
        for old in names[:-self.KEEP]:
            for ext in (".json", ".trace"):
                try: os.remove(os.path.join(self.dir, old + ext))
                except FileNotFoundError: pass

    def list(self):
        out = []
        try: files = sorted((f for f in os.listdir(self.dir) if f.endswith(".json")), reverse=True)
        except FileNotFoundError: files = []
        for f in files:
            r = load_json(os.path.join(self.dir, f), None)
            if r: out.append({**{k: r.get(k) for k in ("kind", "ts", "app", "reason", "where", "trace_bytes")},
                              "id": f[:-5], "head": (r.get("text") or "").splitlines()[:3]})
        return out


CRASHES = Crashes()


# ---------------------------------------------------------------- routes

# key -> (name, icon, subtitle, kind). Keys: ":<port>" for non-443 ports, the backend port for "/" on 443,
# otherwise the path. kind "api" = backend endpoint used by another page (e.g. the Cat cams page), not a page
# you'd open yourself: hidden from the app's Routes list unless routes.json says otherwise.
KNOWN = {
    "1984": ("Cameras", "cctv", "go2rtc live streams", "page"),
    "/cams/": ("Cat cams", "cat", "Camera wall", "page"),
    "/detect": ("Cat detector", "eye-outline", "Cat cams backend: detections", "api"),
    "/music": ("Cat music", "music", "Cat cams backend: speaker audio", "api"),
    "/ptz": ("Pan / tilt", "arrow-all", "Cat cams backend: camera movement", "api"),
    "/rec": ("Recordings", "record-rec", "Cat cams backend: recordings", "api"),
    "/vitals": ("Vitals", "heart-pulse", "CPU, memory, processes", "page"),
    ":5055": ("Seerr", "movie-search-outline", "Requests", "page"),
    ":6767": ("Bazarr", "subtitles-outline", "Subtitles", "page"),
    ":7878": ("Radarr", "filmstrip", "Movies", "page"),
    ":8989": ("Sonarr", "television-classic", "TV shows", "page"),
    ":8096": ("Jellyfin", "play-box-multiple-outline", "Media server", "page"),
    ":9696": ("Prowlarr", "radar", "Indexers", "page"),
    ":8080": ("qBittorrent", "download-outline", "Torrents", "page"),
}
# Real pages you just don't want as tiles (KNOWN keys). The tailscale serve routes stay up; this only hides the
# cards. routes.json {"<key>": {"hidden": false}} shows one again.
HIDDEN = {"1984", ":5055", ":6767", ":7878", ":8989"}  # Cameras (go2rtc), Seerr, Bazarr, Radarr, Sonarr
# Tiles for apps that aren't `tailscale serve` routes: key -> (name, icon, subtitle, android package, port).
# The app launches the package; without it (or on builds before 1.5.2) it opens http://<tailnet host>:<port>/.
APPS = {"jellyfin": ("Jellyfin", "play-box-multiple-outline", "Movies & TV (opens the app)", "org.jellyfin.mobile", 8096)}
ROUTES_CONF = os.path.join(CONF_DIR, "routes.json")
_serve_cache = [0.0, None]


def serve_status():
    if time.time() - _serve_cache[0] < 10 and _serve_cache[1] is not None:
        return _serve_cache[1]
    p = subprocess.run(["tailscale", "serve", "status", "--json"], capture_output=True, timeout=10)
    d = json.loads(p.stdout or b"{}")
    _serve_cache[:] = [time.time(), d]
    return d


def build_routes(status, overrides):
    """tailscale serve status JSON -> route cards. overrides (routes.json) is keyed by the path for :443
    routes, or ":<port>" for other ports: {"name","icon","subtitle","kind","hidden"}; read on every call, so
    hiding a new API-only route needs no restart and no app rebuild."""
    out = []
    for hostport, web in (status.get("Web") or {}).items():
        host, _, port = hostport.rpartition(":")
        for path, h in (web.get("Handlers") or {}).items():
            if path.rstrip("/") == "/pc": continue
            target = h.get("Proxy") or h.get("Path") or h.get("Text") or ""
            url = f"https://{host}{'' if port == '443' else ':' + port}{path}"
            m = re.search(r":(\d+)", h.get("Proxy", ""))
            backend_port = m.group(1) if m else None
            if port != "443": key = ":" + port
            elif path == "/" and backend_port: key = backend_port
            else: key = path if path in KNOWN else path.rstrip("/")
            name, icon, sub, kind = KNOWN.get(key) or KNOWN.get(path) or (
                path.strip("/").replace("-", " ").title() or f"Port {port}", "web", "", "page")
            okey = path if port == "443" else ":" + port
            o = overrides.get(okey) or overrides.get(okey.rstrip("/")) or {}
            if not isinstance(o, dict): o = {}
            kind = o.get("kind", kind)
            out.append({"id": f"{port}{path}", "name": o.get("name", name), "icon": o.get("icon", icon),
                        "subtitle": o.get("subtitle", sub), "url": url, "path": path, "port": int(port),
                        "target": target, "kind": kind, "hidden": bool(o.get("hidden", kind == "api" or key in HIDDEN))})
    host = next((hp.rpartition(":")[0] for hp in (status.get("Web") or {})), None)
    for key, (name, icon, sub, pkg, port) in APPS.items():
        o = overrides.get(key) if isinstance(overrides.get(key), dict) else {}
        if not host or any(r["port"] == port for r in out): continue
        out.append({"id": key, "name": o.get("name", name), "icon": o.get("icon", icon),
                    "subtitle": o.get("subtitle", sub), "url": o.get("url", f"http://{host}:{port}/"), "path": "/",
                    "port": port, "target": f"app:{pkg}", "kind": "app", "app": o.get("app", pkg),
                    "hidden": bool(o.get("hidden", False))})
    out.sort(key=lambda r: (r["port"] != 443, r["path"] == "/", r["name"].lower()))
    return out


def list_routes(include_hidden=False):
    routes = build_routes(serve_status(), load_json(ROUTES_CONF, {}))
    return routes if include_hidden else [r for r in routes if not r["hidden"]]


# ---------------------------------------------------------------- services

UNIT_RE = re.compile(r"^[A-Za-z0-9@._:-]+\.service$")


def own_units():
    units = []
    for p in sorted(glob.glob(os.path.join(UNIT_DIR, "*.service"))):
        name = os.path.basename(p)
        if "@." in name or not UNIT_RE.match(name): continue
        if os.path.islink(p) and os.path.realpath(p) == "/dev/null": continue   # masked
        units.append(name)
    return units


PROPS = ["Id", "Description", "ActiveState", "SubState", "UnitFileState", "ActiveEnterTimestamp",
         "MainPID", "MemoryCurrent", "NRestarts", "Type", "Result"]


def list_services():
    units = own_units()
    if not units: return []
    p = subprocess.run(["systemctl", "--user", "show", "--no-pager", "-p", ",".join(PROPS), *units],
                       capture_output=True, text=True, timeout=15)
    blocks, cur = [], {}
    for line in p.stdout.splitlines():
        if not line.strip():
            if cur: blocks.append(cur); cur = {}
            continue
        k, _, v = line.partition("=")
        cur[k] = v
    if cur: blocks.append(cur)
    timers = {os.path.basename(t)[:-6] for t in glob.glob(os.path.join(UNIT_DIR, "*.timer"))}
    out = []
    for b in blocks:
        mem = b.get("MemoryCurrent", "")
        out.append({
            "unit": b.get("Id"), "description": b.get("Description", ""), "active": b.get("ActiveState"),
            "sub": b.get("SubState"), "enabled": b.get("UnitFileState"), "since": b.get("ActiveEnterTimestamp"),
            "pid": int(b.get("MainPID") or 0), "memory": int(mem) if mem.isdigit() else None,
            "restarts": int(b["NRestarts"]) if b.get("NRestarts", "").isdigit() else None,
            "type": b.get("Type"), "result": b.get("Result"),
            "timer": b.get("Id", "").removesuffix(".service") in timers,
        })
    return out


def restart_service(unit):
    if unit not in own_units(): return 404, {"error": "not one of your user services"}
    p = subprocess.run(["systemctl", "--user", "restart", "--no-block", unit], capture_output=True, text=True,
                       timeout=15)
    if p.returncode: return 500, {"error": p.stderr.strip() or "restart failed"}
    return 200, {"ok": True}


def service_logs(unit, n):
    if unit not in own_units(): return 404, {"error": "not one of your user services"}
    p = subprocess.run(["journalctl", "--user", "-u", unit, "-n", str(n), "--no-pager", "-o", "short-iso"],
                       capture_output=True, text=True, timeout=15)
    return 200, {"lines": p.stdout.splitlines()[-n:]}


# ---------------------------------------------------------------- APK

def apk_info():
    meta = load_json(os.path.join(DATA_DIR, "app.json"), None)
    if not meta or not os.path.exists(os.path.join(DATA_DIR, "app.apk")): return None
    return meta


# ---------------------------------------------------------------- HTTP

def rootd_or_503(msg, timeout=15):
    try:
        res = rootd_call(msg, timeout)
    except OSError:
        st = rootd_state()["state"]
        return 503, {"error": "rootd is not installed" if st == "not_installed" else "rootd is not running",
                     "rootd": st}
    if "error" in res:
        e = res["error"]
        code = 403 if "signature" in e or "forbidden" in e else 404 if "unknown request" in e else 409
        return code, res
    return 200, res


class H(BaseHTTPRequestHandler):
    server_version = "pc-api/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        if os.environ.get("PCAPI_LOG"): super().log_message(fmt, *a)

    def authorized(self):
        login = self.headers.get("Tailscale-User-Login")
        if login:
            return login in ALLOWED_LOGINS
        # No tailnet identity: only plain local requests (never proxied ones from tagged/shared nodes).
        return ALLOW_UNAUTH_LOCAL and not self.headers.get("X-Forwarded-For") and \
            self.client_address[0] in ("127.0.0.1", "::1")

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body(self, limit=64 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit: raise ValueError("body too large")
        data = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(data, dict): raise ValueError("body must be an object")
        return data

    def route(self, method):
        u = urllib.parse.urlsplit(self.path)
        path, qs = u.path.rstrip("/"), urllib.parse.parse_qs(u.query)
        if not self.authorized():
            return self.send_json(403, {"error": "not allowed for this tailnet identity"})
        if not path.startswith("/pc"):
            return self.send_json(404, {"error": "not found"})
        path = path[3:]
        if method == "GET":
            if path == "/app.apk": return self.send_apk()
            if path == "/api/notify/settings": return self.send_json(200, NOTIFY.settings())
            m = re.fullmatch(r"/api/notify/image/([0-9a-f]{16})", path)
            if m:
                img = NOTIFY.image(m.group(1))
                if img is None: return self.send_json(404, {"error": "no such image"})
                return self.send_bytes(img, image_type(img) or "application/octet-stream")
            if path == "/api/info":
                return self.send_json(200, {"host": socket.gethostname(), "version": VERSION,
                                            "rootd": rootd_state(), "apk": apk_info(), "push": PUSH.status()})
            if path == "/api/push/status":
                return self.send_json(200, PUSH.status(qs.get("token", [""])[0]))
            if path == "/api/whoami":
                return self.send_json(200, {"login": self.headers.get("Tailscale-User-Login"),
                                            "name": self.headers.get("Tailscale-User-Name"),
                                            "proxied": bool(self.headers.get("X-Forwarded-For"))})
            if path == "/api/routes":
                return self.send_json(200, {"routes": list_routes(qs.get("all", ["0"])[0] == "1")})
            if path == "/api/services": return self.send_json(200, {"services": list_services()})
            m = re.fullmatch(r"/api/services/([^/]+)/logs", path)
            if m: return self.send_json(*service_logs(m.group(1), min(int(qs.get("n", ["80"])[0]), 500)))
            if path == "/api/root/pending":
                code, res = rootd_or_503({"op": "list"})
                if code == 200:
                    res = {"requests": [r for r in res["requests"] if r["status"] in ("pending", "running")]}
                return self.send_json(code, res)
            if path == "/api/root/history":
                return self.send_json(*rootd_or_503({"op": "history", "limit": int(qs.get("limit", ["50"])[0])}))
            m = re.fullmatch(r"/api/root/request/([0-9a-f]{16})", path)
            if m: return self.send_json(*rootd_or_503({"op": "get", "id": m.group(1)}))
            if path == "/api/enroll/status":
                return self.send_json(*rootd_or_503({"op": "enroll_status",
                                                     "fingerprint": qs.get("fingerprint", [""])[0]}))
            if path == "/api/events": return self.sse()
            if path == "/api/crash": return self.send_json(200, {"crashes": CRASHES.list()})
            if path == "/api/downloads":
                return self.send_json(200, {**DOWNLOADS.active(), "enabled": NOTIFY.wants("live")["app"],
                                            "ts": int(time.time())})
        elif method == "POST":
            m = re.fullmatch(r"/api/hook/(radarr|sonarr|seerr)", path)
            if m: return self.hook(m.group(1))
            limit = {"/api/notify": 16 * 1024 * 1024, "/api/crash": 2 * 1024 * 1024}.get(path, 64 * 1024)
            try: b = self.body(limit)
            except ValueError as e: return self.send_json(400, {"error": str(e)})
            m = re.fullmatch(r"/api/services/([^/]+)/restart", path)
            if m: return self.send_json(*restart_service(m.group(1)))
            if path in ("/api/root/approve", "/api/root/deny"):
                op = path.rsplit("/", 1)[1]
                return self.send_json(*rootd_or_503({"op": op, "id": str(b.get("id", "")),
                                                     "device": str(b.get("device", "")),
                                                     "signature": str(b.get("signature", ""))}))
            if path == "/api/push/register":
                return self.send_json(*PUSH.register(b.get("token"), b.get("name")))
            if path == "/api/push/unregister":
                return self.send_json(200, {"ok": True, "removed": PUSH.unregister(str(b.get("token", "")))})
            if path == "/api/push/test":
                if not PUSH.configured(): return self.send_json(503, {"error": "FCM is not configured on the laptop"})
                only = b.get("token") or None
                if only and not PUSH.status(only)["registered"]:
                    return self.send_json(404, {"error": "this phone's token is not registered"})
                res = PUSH.send_all({"kind": "notify", "id": "test-" + secrets.token_hex(6),
                                     "title": "Test notification",
                                     "body": f"Push from {socket.gethostname()} works."}, True, 300, only)
                return self.send_json(200, {"sent": sum(r["ok"] for r in res), "results": res})
            if path == "/api/notify":
                img = None
                if b.get("image_b64"):
                    try: img = base64.b64decode(str(b["image_b64"]), validate=True)
                    except ValueError: return self.send_json(400, {"error": "image_b64 is not base64"})
                tt = b.get("telegram_text")
                return self.send_json(*NOTIFY.send(str(b.get("category") or "other"), b.get("title"), b.get("body"),
                                                   str(tt)[:4096] if tt else None, img, b.get("link") or None,
                                                   b.get("cam"), b.get("ts")))
            if path == "/api/crash": return self.send_json(*CRASHES.store(b))
            if path == "/api/notify/settings": return self.send_json(*NOTIFY.update(b))
            if path == "/api/notify/test": return self.send_json(*NOTIFY.test(str(b.get("channel", ""))))
            if path == "/api/downloads/started":
                if b.get("hash") and b.get("title"): DOWNLOADS.remember(str(b["hash"]), str(b["title"]))
                return self.send_json(200, {"ok": True, "app": NOTIFY.live_kick(clip(b.get("source") or "", 20))})
            if path == "/api/enroll":
                return self.send_json(*rootd_or_503({"op": "enroll_request", "name": str(b.get("name", "phone")),
                                                     "pubkey": str(b.get("pubkey", ""))}))
        return self.send_json(404, {"error": "not found"})

    def send_bytes(self, data, ctype, extra=()):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in extra: self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def hook(self, source):
        """Webhook ingest for Radarr/Sonarr/Seerr. Needs the shared secret on top of the usual local/tailnet check."""
        if not hook_authorized(self.headers, hook_secret()):
            n = int(self.headers.get("Content-Length") or 0)
            if 0 < n <= 1024 * 1024: self.rfile.read(n)
            return self.send_json(401, {"error": "bad or missing webhook secret"})
        try: p = self.body(1024 * 1024)
        except ValueError as e: return self.send_json(400, {"error": str(e)})
        n = map_hook(source, p)
        if source in ("radarr", "sonarr") and p.get("eventType") == "Grab":
            # the torrent hash -> the movie/show title for the live progress notification, then wake the phone
            if n and n.get("name"): DOWNLOADS.remember(str(p.get("downloadId") or ""), n["name"])
            NOTIFY.live_kick(source)
            space_check(source, p)
        if not n: return self.send_json(200, {"ok": True, "ignored": True})
        img = NOTIFY.fetch_poster(n["image_url"]) if n.get("image_url") else None
        return self.send_json(*NOTIFY.send(n["category"], n["title"], n["body"], None, img, n.get("link")))

    def send_apk(self):
        p = os.path.join(DATA_DIR, "app.apk")
        if not os.path.exists(p): return self.send_json(404, {"error": "no APK built yet"})
        meta = apk_info() or {}
        size = os.path.getsize(p)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition",
                         f'attachment; filename="pc-control-{meta.get("versionName", "latest")}.apk"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with open(p, "rb") as f:
            while chunk := f.read(256 * 1024): self.wfile.write(chunk)

    def sse(self):
        q = HUB.subscribe()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.close_connection = True
            pending = []
            st = rootd_state()["state"]
            if st == "ok":
                try: pending = [r for r in rootd_call({"op": "list"}, 5).get("requests", [])
                                if r["status"] in ("pending", "running")]
                except OSError: st = "down"
            self.write_event("hello", {"rootd": st, "pending": pending, "ts": int(time.time())})
            self.wfile.write(b"retry: 3000\n\n"); self.wfile.flush()
            while True:
                msg = q.get()
                if msg is None: break
                self.write_event(*msg)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            HUB.unsubscribe(q)

    def write_event(self, event, data):
        self.wfile.write(f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode())
        self.wfile.flush()

    def do_GET(self):
        try: self.route("GET")
        except Exception as e: self.fail(e)

    def do_POST(self):
        try: self.route("POST")
        except Exception as e: self.fail(e)

    def fail(self, e):
        print(f"pc-api error on {self.command} {self.path}: {type(e).__name__}: {e}", file=sys.stderr)
        try: self.send_json(500, {"error": f"internal: {type(e).__name__}"})
        except Exception: pass


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64


def main():
    global HUB, PUSH, NOTIFY, DOWNLOADS
    PUSH = Push()
    NOTIFY = Notify(PUSH)
    DOWNLOADS = Downloads()
    hook_secret()
    HUB = Hub()
    srv = Server(("127.0.0.1", PORT), H)
    print(f"pc-api on 127.0.0.1:{PORT}, allowed tailnet logins: {sorted(ALLOWED_LOGINS) or 'NONE'}, "
          f"FCM {'configured' if PUSH.configured() else 'NOT configured'}, {len(PUSH.tokens)} push token(s)", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
