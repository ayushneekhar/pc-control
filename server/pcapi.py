#!/usr/bin/python3 -I
"""pc-api: the pc-control app's backend (user service, 127.0.0.1:1990, tailnet via `tailscale serve /pc`).

GET  /pc/api/info                     host, rootd status, latest APK info
GET  /pc/api/routes[?all=1]           tailscale serve routes + app tiles as friendly cards (API-only/HIDDEN hidden)
GET  /pc/api/services                 user systemd units from ~/.config/systemd/user
POST /pc/api/services/<unit>/restart
GET  /pc/api/services/<unit>/logs     last journal lines
GET  /pc/api/health                   one-call health overview: problems, services, downloads, Radarr/Sonarr queues,
                                      Jellyfin streams and disks. Each source has a ~2 s budget and runs
                                      concurrently; a source that is down only marks its section unavailable.
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
GET  /pc/api/vpn                      {enabled, tunnel_up, forwarded_port, qbt_interface, public_ip, last_error}
POST /pc/api/vpn                      {enabled: bool}: route qBittorrent through Proton WireGuard (or back).
GET  /pc/api/agent                    {engine, claude_active, gemini_active, available, switching, options}
POST /pc/api/agent                    {engine: "claude"|"gemini"}: detached switch via systemd-run --user (202)
GET  /pc/api/agent/usage[?refresh=1]  per-account usage across harnesses (via ai-usage --json, cached ~60 s)
GET  /pc/api/agent/settings           Claude & Gemini model, effort, account/instance, auto-rotate & options
POST /pc/api/agent/settings           update Claude and/or Gemini agent settings
POST /pc/api/agent/reset              start a fresh agent conversation via ~/remote-pc/bin/agent-reset
GET  /pc/api/agentview/state          {enabled, state, web_reachable, services}: Agentview capture.state + web probe
POST /pc/api/agentview/toggle         {enabled: bool}: atomically toggle capture.state and sync agentview-web.service
GET  /pc/api/keyring                  {locked: bool, rdp: <gnome-remote-desktop active state>}
POST /pc/api/keyring/unlock           {password}: unlock GNOME login keyring via --stdin and restart RDP
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
import base64, concurrent.futures, datetime, glob, hashlib, ipaddress, json, math, os, queue, secrets, re, socket, stat, struct, subprocess, sys, tempfile, threading, time, urllib.error, urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
if _SERVER_DIR not in sys.path:
    sys.path.insert(0, _SERVER_DIR)
import bazarr_trigger as bazarr_mod  # noqa: E402
import chat as chat_mod  # noqa: E402
import link_preview as link_preview_mod  # noqa: E402
import watch as watch_mod  # noqa: E402

CHAT = None
WATCH = None


def _chat_manager():
    global CHAT
    if CHAT is None:
        CHAT = chat_mod.ChatManager(notify=NOTIFY)
    return CHAT


def _watch_service():
    global WATCH
    if WATCH is None:
        WATCH = watch_mod.WatchService(
            seerr_client_fn=lambda: SEERR,
            jellyfin_url_fn=lambda: JELLYFIN_URL,
            jellyfin_key_fn=_jellyfin_api_key,
            arr_config_fn=lambda: ARR,
            downloads_fn=lambda: DOWNLOADS,
            preferred_user_fn=lambda: str(CONFIG.get("jellyfin_user") or "ayush"),
        )
    return WATCH

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
PCAPI_CONF = os.path.join(CONF_DIR, "pcapi.json")


def save_config(updates, path=None):
    """Merge a few settings into ~/.config/pc-control/pcapi.json (mode 600) without dropping the rest."""
    path = path or PCAPI_CONF
    current = load_json(path, {})
    if not isinstance(current, dict): current = {}
    current.update(updates)
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w") as f: json.dump(current, f, indent=1)
        os.replace(tmp, path)
    except Exception:
        try: os.remove(tmp)
        except OSError: pass
        raise
    if os.path.abspath(path) == os.path.abspath(PCAPI_CONF): CONFIG.update(updates)


def tailnet_owner():
    try:
        d = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, timeout=10).stdout)
        return d["User"][str(d["Self"]["UserID"])]["LoginName"]
    except Exception:
        return None


ALLOWED_LOGINS = set(CONFIG.get("allowed_logins") or filter(None, [tailnet_owner()]))
ALLOW_UNAUTH_LOCAL = os.environ.get("PCAPI_ALLOW_LOCAL", "1") == "1"
LOCAL_TOKEN_FILE = os.environ.get("PCAPI_LOCAL_TOKEN", os.path.join(CONF_DIR, "local-token"))
API_TOKEN_FILE = os.path.join(CONF_DIR, "api-token")
CF_ACCESS_TOKEN_FILE = os.path.join(CONF_DIR, "cf-access-token.json")
DEFAULT_PUBLIC_API_URL = "https://api.neekhar.dev"
SSE_KEEPALIVE_SEC = 20

PROXY_UPSTREAMS = {
    "vitals": os.environ.get("PCAPI_VITALS_URL", "http://127.0.0.1:1989/vitals/api").rstrip("/"),
    "detect": os.environ.get("PCAPI_DETECT_URL", "http://127.0.0.1:1986/detect").rstrip("/"),
    "ptz": os.environ.get("PCAPI_PTZ_URL", "http://127.0.0.1:1985/ptz").rstrip("/"),
    "music": os.environ.get("PCAPI_MUSIC_URL", "http://127.0.0.1:1988/music").rstrip("/"),
}
PROXY_TIMEOUT = 15
PROXY_MUSIC_PLAY_TIMEOUT = 60
PROXY_MAX_REQ_BODY = 2 * 1024 * 1024
PROXY_MAX_RESP_BODY = 16 * 1024 * 1024


def local_token():
    """Shared bearer token for localhost requests without tailscale-serve headers, auto-created (0600)."""
    try:
        with open(LOCAL_TOKEN_FILE) as f:
            s = f.read().strip()
            if s: return s
    except FileNotFoundError:
        pass
    os.makedirs(os.path.dirname(LOCAL_TOKEN_FILE), mode=0o700, exist_ok=True)
    s = secrets.token_urlsafe(32)
    fd = os.open(LOCAL_TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: f.write(s + "\n")
    return s


def local_token_authorized(headers):
    tok = local_token()
    cands = [headers.get("X-PC-Local-Token") or ""]
    a = (headers.get("Authorization") or "").strip()
    if a.lower().startswith("bearer "):
        cands.append(a[7:].strip())
    return any(c and secrets.compare_digest(c, tok) for c in cands)


def load_api_token():
    """Read unified API bearer token from PCAPI_API_TOKEN env var (path or literal) or ~/.config/pc-control/api-token."""
    env_val = os.environ.get("PCAPI_API_TOKEN")
    if env_val is not None:
        raw = env_val.strip()
        if not raw:
            return None
        if os.path.sep in raw:
            try:
                with open(raw, encoding="utf-8") as f:
                    s = f.read().strip()
                    return s if s else None
            except OSError:
                return None
        return raw
    try:
        with open(API_TOKEN_FILE, encoding="utf-8") as f:
            s = f.read().strip()
            return s if s else None
    except OSError:
        return None


def api_token_authorized(headers):
    """Verify `Authorization: Bearer <token>` against the unified public API bearer token."""
    tok = load_api_token()
    if not tok:
        return False
    auth = (headers.get("Authorization") or "").strip() if headers is not None else ""
    if not auth.lower().startswith("bearer "):
        return False
    cand = auth[7:].strip()
    return bool(cand) and secrets.compare_digest(cand, tok)


def load_cf_access_token():
    """Read Cloudflare Access service token JSON from PCAPI_CF_ACCESS_TOKEN (JSON or path) or ~/.config/pc-control/cf-access-token.json."""
    env_val = os.environ.get("PCAPI_CF_ACCESS_TOKEN")
    if env_val is not None:
        raw = env_val.strip()
        if not raw:
            return {}
        if raw.startswith("{"):
            try:
                parsed = json.loads(raw)
                return parsed if isinstance(parsed, dict) else {}
            except ValueError:
                return {}
        data = load_json(raw, {})
        return data if isinstance(data, dict) else {}
    data = load_json(CF_ACCESS_TOKEN_FILE, {})
    return data if isinstance(data, dict) else {}


def public_config():
    """Build the Tailscale/local-only public API sync payload for the Android app."""
    tok = load_api_token() or ""
    cf = load_cf_access_token()
    client_id = str(cf.get("client_id") or cf.get("cf_access_client_id") or "").strip()
    client_secret = str(cf.get("client_secret") or cf.get("cf_access_client_secret") or "").strip()
    hostname = str(cf.get("hostname") or "").strip()
    cfg_url = str(os.environ.get("PCAPI_PUBLIC_API_URL") or CONFIG.get("public_api_url") or "").strip()
    if cfg_url:
        api_url = cfg_url.rstrip("/")
    elif hostname:
        api_url = hostname.rstrip("/") if hostname.startswith(("http://", "https://")) else f"https://{hostname.rstrip('/')}"
    else:
        api_url = DEFAULT_PUBLIC_API_URL
    return {
        "api_url": api_url,
        "api_token": tok,
        "cf_access_client_id": client_id,
        "cf_access_client_secret": client_secret,
    }


def _has_path_traversal(raw_path):
    """Return True if raw or percent-decoded path contains '..' segments, backslashes, or NUL bytes."""
    for candidate in (raw_path, urllib.parse.unquote(raw_path)):
        if "\\" in candidate or "\x00" in candidate:
            return True
        if any(seg == ".." for seg in candidate.split("/")):
            return True
    return False


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
                    elif t == "rate_limited":
                        self.publish("rate_limited", ev)
                        if NOTIFY:
                            NOTIFY.send_async("system", "Root approval rate limited",
                                              "rootd rate-limited command submissions from the laptop.")
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
    ("service_down", "Service down", "A long-running user service stopped or failed, and when it recovers.", True, True,
     "server-off"),
    ("vpn_down", "Torrent VPN down", "qBittorrent lost its Proton VPN route or its forwarded port, and when it recovers.",
     True, True, "shield-off-outline"),
    ("usage_limits", "Usage limits", "An AI account is running low on 5-hour or weekly quota.", False, True, "gauge"),
    ("keyring", "Keyring locked", "GNOME keyring is locked after a reboot; tap to unlock RDP.", False, True,
     "key-outline"),
    ("root", "Root approvals", "A root command is waiting for your fingerprint.", False, True, "shield-key-outline"),
    ("results", "Command results", "An approved root command finished or failed.", False, True, "console"),
    ("other", "Other messages", "Anything else sent to /pc/api/notify.", False, True, "message-outline"),
    ("chat", "Agent chat", "Replies from the PC agent in the Chat tab.", False, True, "message-text-outline"),
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
        if link is not None and not re.fullmatch(r"(?:https?|pccontrol)://[^\s]{1,500}", str(link)):
            return 400, {"error": "link must be an http(s) or pccontrol URL"}
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
        if ev in ("ImportFailed", "DownloadFailed"):
            msgs = [m for s in (p.get("downloadStatusMessages") or []) for m in (s.get("messages") or [])]
            why = msgs[0] if msgs else (p.get("message") or p.get("downloadStatus") or "import failed")
            return {"category": "problems", "title": f"⚠️ {app} import failed",
                    "body": f"{name}\n{clip(why, 300)}"}
        if ev == "Health":
            lvl = str(p.get("level") or "").lower()
            icon = "🔴" if lvl == "error" else "⚠️"
            msg = clip(p.get("message") or p.get("type") or "Health issue", 350)
            return {"category": "problems", "title": f"{icon} {app} health issue", "body": msg}
        if ev == "HealthRestored":
            msg = clip(p.get("message") or p.get("type") or "Health issue resolved", 350)
            return {"category": "problems", "title": f"✅ {app} health restored", "body": msg}
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
        jf = str(CONFIG.get("jellyfin_public_url") or "").rstrip("/")   # Jellyfin's public URL, from pcapi.json
        jf_id = media.get("jellyfinId")
        if (nt == "MEDIA_AVAILABLE" and jf and isinstance(jf_id, str)
                and re.fullmatch(r"[0-9a-fA-F]{32}", jf_id)):
            out["link"] = f"{jf}/web/#/details?id={jf_id}"
            sid = str(CONFIG.get("jellyfin_server_id") or "")
            if re.fullmatch(r"[0-9a-fA-F]{32}", sid): out["link"] += f"&serverId={sid}"
        else:
            base = str(CONFIG.get("seerr_url") or "").rstrip("/")     # Seerr's public URL, from pcapi.json
            if base and media.get("media_type") in ("movie", "tv") and str(media.get("tmdbId", "")).isdigit():
                out["link"] = f"{base}/{media['media_type']}/{media['tmdbId']}"
        if p.get("image"): out["image_url"] = p["image"]
        return out
    return None


NOTIFY = None
WATCHER = None
BAZARR = None

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

    def arr_lookup(self, cat, timeout=5):
        """Radarr/Sonarr queue -> {HASH: title}. Errors (app down, no key) just mean no titles."""
        app, base, conf = self.arr[cat]
        try:
            with open(conf) as f: key = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())[1]
            q = "includeMovie=true" if app == "radarr" else "includeSeries=true&includeEpisode=true"
            req = urllib.request.Request(f"{base}/api/v3/queue?pageSize=200&{q}", headers={"X-Api-Key": key})
            with urllib.request.urlopen(req, timeout=timeout) as r: recs = json.load(r).get("records") or []
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
    def qbt(self, method, timeout=8, get=False, **params):
        data = None if get else urllib.parse.urlencode(params).encode()
        for attempt in (0, 1):
            if self.opener is None:
                import http.cookiejar
                jar = http.cookiejar.CookieJar()
                op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
                with open(self.password_file) as f: pw = f.read().strip()
                form = urllib.parse.urlencode({"username": self.user, "password": pw}).encode()
                op.open(f"{self.url}/api/v2/auth/login", form, timeout=timeout).close()
                if not any("SID" in c.name for c in jar): raise PermissionError("qBittorrent login failed")
                self.opener = op
            try:
                with self.opener.open(f"{self.url}/api/v2/{method}", data, timeout=timeout) as r:
                    raw = r.read()
                return json.loads(raw) if raw.strip() else {}
            except urllib.error.HTTPError as e:
                self.opener = None
                if e.code in (401, 403) and attempt == 0: continue      # session expired (qBittorrent restarted)
                raise
        raise PermissionError("qBittorrent login failed")

    def preferences(self, timeout=8):
        return self.qbt("app/preferences", get=True, timeout=timeout)

    def set_preferences(self, prefs, timeout=8):
        return self.qbt("app/setPreferences", timeout=timeout, json=json.dumps(prefs))

    def active(self, timeout=8):
        """-> {"qbt": "ok"|"down"|"auth", "downloads": [...], "total": {...}}. Cached ~1.5 s (one phone polls ~3 s)."""
        with self.lock:
            at, res = self.cache
            if res is not None and time.time() - at < 1.5: return res
            try:
                items, st = self.qbt("torrents/info", timeout=timeout, filter="all", sort="added_on"), "ok"
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
            for h, title in self.arr_lookup(cat, timeout=timeout).items():
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
                        "speed": int(t.get("dlspeed") or 0), "upspeed": int(t.get("upspeed") or 0),
                        "eta": eta if 0 < eta < 8640000 else None,
                        "size": size, "done": min(done, size) if size else done,
                        "seeds": int(t.get("num_seeds") or 0), "added": int(t.get("added_on") or 0)})
        rank = {"downloading": 0, "stalled": 1, "metadata": 2, "checking": 3, "moving": 3, "queued": 4}
        out.sort(key=lambda d: (rank.get(d["state"], 5), d["added"]))
        sized = [d for d in out if d["size"] > 0]
        etas = [d["eta"] for d in out if d["eta"]]
        total = {"count": len(out), "downloading": sum(d["state"] == "downloading" for d in out),
                 "progress": round(sum(d["done"] for d in sized) / sum(d["size"] for d in sized), 4) if sized else None,
                 "speed": sum(d["speed"] for d in out), "upspeed": sum(d["upspeed"] for d in out),
                 "eta": max(etas) if etas else None,
                 "size": sum(d["size"] for d in sized), "done": sum(d["done"] for d in sized)}
        res = {"qbt": st, "downloads": out, "total": total}
        with self.lock: self.cache = (time.time(), res)
        return res


DOWNLOADS = None


# ---------------------------------------------------------------- torrent VPN (qBittorrent -> Proton WireGuard)

VPN_INTERFACE = os.environ.get("PCAPI_VPN_INTERFACE", "proton0")
VPN_SOURCE_IP4 = "10.2.0.2"
VPN_DEFAULT_PORT = 28655
VPN_NATPMP_GATEWAY = os.environ.get("PCAPI_NATPMP_GATEWAY", "10.2.0.1")
VPN_NATPMP_PORT = int(os.environ.get("PCAPI_NATPMP_PORT", "5351"))
VPN_HANDSHAKE_MAX = 180          # s, a WireGuard handshake older than this means the tunnel is down
VPN_RENEW = 45                   # s, NAT-PMP mappings are requested with a 60 s lifetime
VPN_ALERT_AFTER = 180            # s unhealthy before one vpn_down alert
VPN_IP_INTERVAL = 600            # s between public-IP lookups
VPN_NATPMP_BACKOFF = 60          # s to wait after result 4 (out of resources)
VPN_WG_KICK = os.environ.get("PCAPI_WG_KICK", "/run/proton-wg-kick/kick")


class NatPmpError(Exception):
    def __init__(self, result, message=""):
        super().__init__(message or f"NAT-PMP result {result}")
        self.result = result


class NatPmp:
    """Minimal RFC 6886 NAT-PMP client (UDP, addr 10.2.0.1:5351). bound to 10.2.0.2 so the gateway maps the
    Proton tunnel address, never the normal LAN one. request(1) = UDP, request(2) = TCP."""

    def __init__(self, gateway=VPN_NATPMP_GATEWAY, port=VPN_NATPMP_PORT, bind=VPN_SOURCE_IP4, timeout=1.5):
        self.gateway, self.port, self.bind, self.timeout = gateway, port, bind, timeout

    def request(self, opcode, internal, suggested=0, lifetime=60):
        if opcode not in (1, 2): raise ValueError("opcode must be 1 (UDP) or 2 (TCP)")
        # version, opcode, reserved, internal port, suggested external port, lifetime
        req = struct.pack("!BBHHHI", 0, opcode, 0, int(internal) & 0xffff, int(suggested) & 0xffff,
                          max(0, min(int(lifetime), 0xffffffff)))
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.bind((self.bind, 0))
            s.settimeout(self.timeout)
            s.sendto(req, (self.gateway, self.port))
            data, _ = s.recvfrom(128)
        finally:
            s.close()
        if len(data) < 16:
            raise NatPmpError(-1, "short NAT-PMP reply")
        version, op, result = struct.unpack("!BBH", data[:4])
        if version != 0 or op != 128 + opcode:
            raise NatPmpError(-1, "bad NAT-PMP reply")
        if result:
            raise NatPmpError(result, f"NAT-PMP refused the mapping (result {result})")
        _, internal_port, external_port, life = struct.unpack("!IHHI", data[4:16])
        return {"internal": internal_port, "external": external_port, "lifetime": life}


def fetch_public_ip(source=VPN_SOURCE_IP4, host="ifconfig.me", timeout=4):
    """The public IP as seen through proton0: a plain HTTP request whose socket is bound to 10.2.0.2."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.settimeout(timeout)
        s.bind((source, 0))
        s.connect((host, 80))
        s.sendall(b"GET /ip HTTP/1.1\r\nHost: ifconfig.me\r\nConnection: close\r\nUser-Agent: pc-control\r\n\r\n")
        data = b""
        while len(data) < 8192:
            chunk = s.recv(4096)
            if not chunk: break
            data += chunk
    finally:
        s.close()
    body = data.split(b"\r\n\r\n", 1)[-1].decode("ascii", "ignore").strip()
    return str(ipaddress.ip_address(body))


class VpnManager:
    """qBittorrent -> Proton WireGuard only, switchable from the app.

    enabled: qBittorrent's outgoing interface is proton0 (libtorrent binds to it, so a down tunnel leaks nothing)
    and a NAT-PMP mapping for the public port is kept alive (UDP + TCP, renewed every ~45 s). disabled: the
    interface goes back to automatic and the listen port returns to the stored one. State lives in pcapi.json.
    """

    def __init__(self, notify, qbt=None, conf_file=None, net=None, ip_fetcher=None,
                 renew=VPN_RENEW, check_interval=15, alert_after=VPN_ALERT_AFTER, kick_file=None):
        self.notify = notify
        self.qbt = qbt
        self.conf_file = conf_file or PCAPI_CONF
        self.net = net or NatPmp()
        self.ip_fetcher = ip_fetcher or fetch_public_ip
        self.renew, self.interval, self.alert_after = renew, check_interval, alert_after
        self.kick_file = kick_file
        self.lock = threading.RLock()
        self.stop = threading.Event()
        conf = load_json(self.conf_file, {})
        if not isinstance(conf, dict): conf = {}
        self.enabled = bool(conf.get("torrent_vpn_enabled", False))
        self.previous_port = self._port(conf.get("torrent_vpn_previous_port"), VPN_DEFAULT_PORT)
        self.forwarded_port = None
        self.public_ip = None
        self.last_error = None
        self.qbt_interface = ""
        self.natpmp_ok = False
        self._qbt_listen_port = None
        self._ip_at = 0.0
        self._next_renew = 0.0
        self._unhealthy_since = None
        self._alerted = False
        self._started_enabled = False
        self._kick_missing_logged = False

    @staticmethod
    def _port(value, default):
        try:
            p = int(value)
            return p if 1 <= p <= 65535 else default
        except (TypeError, ValueError):
            return default

    # ---- tunnel / qBittorrent
    def _iface_exists(self):
        return os.path.exists(f"/sys/class/net/{VPN_INTERFACE}")

    def _handshake_age(self):
        """Seconds since the newest handshake, or None when `wg` can't be read (pc-api is not root)."""
        try:
            p = subprocess.run(["wg", "show", VPN_INTERFACE, "latest-handshakes"],
                               capture_output=True, text=True, timeout=3)
            if p.returncode != 0: return None
            stamps = [int(line.split()[1]) for line in p.stdout.splitlines()
                      if len(line.split()) >= 2 and line.split()[1].isdigit()]
            newest = max(stamps, default=0)
            if newest <= 0: return None
            return max(0.0, time.time() - newest)
        except (OSError, ValueError, subprocess.SubprocessError):
            return None

    def _tunnel_up(self):
        if not self._iface_exists(): return False
        age = self._handshake_age()
        if age is not None: return age <= VPN_HANDSHAKE_MAX
        return self.natpmp_ok

    def _notify(self, title, body):
        fn = getattr(self.notify, "send_async", None) or getattr(self.notify, "send", None)
        if fn:
            try: fn("vpn_down", title, body)
            except Exception as e: print(f"vpn: notify failed: {type(e).__name__}", file=sys.stderr, flush=True)

    def _set_qbt_port(self, port):
        port = self._port(port, 0)
        if not port or port == self._qbt_listen_port: return
        self.qbt.set_preferences({"listen_port": port})
        self._qbt_listen_port = port

    # ---- state changes
    def _enable_locked(self, persist=True):
        if not self.qbt: raise RuntimeError("qBittorrent client is not configured")
        prefs = self.qbt.preferences()
        if isinstance(prefs, dict):
            current = self._port(prefs.get("listen_port"), 0)
            self._qbt_listen_port = current or None
            if persist: self.previous_port = current or self.previous_port
        self.qbt.set_preferences({"current_network_interface": VPN_INTERFACE, "upnp": False})
        self.qbt_interface = VPN_INTERFACE
        self.enabled = True
        self._unhealthy_since, self._alerted = None, False
        self._next_renew = 0.0
        self._ip_at = 0.0
        if persist:
            save_config({"torrent_vpn_enabled": True, "torrent_vpn_previous_port": self.previous_port},
                        self.conf_file)

    def _disable_locked(self):
        if self.qbt:
            self.qbt.set_preferences({"current_network_interface": "", "listen_port": self.previous_port})
        self.qbt_interface = ""
        self._qbt_listen_port = None
        self.enabled = False
        self.forwarded_port = None
        self.public_ip = None
        self.natpmp_ok = False
        self.last_error = None
        self._unhealthy_since, self._alerted = None, False
        save_config({"torrent_vpn_enabled": False}, self.conf_file)

    def set_enabled(self, enabled):
        with self.lock:
            if enabled == self.enabled:
                if enabled: self._renew_locked()
                return self._status_locked()
            if enabled:
                self._enable_locked()
                self._renew_locked()
            else:
                self._disable_locked()
            return self._status_locked()

    # ---- NAT-PMP mapping (both protocols). Proton expects internal=0, suggested=1 and forwards the public
    # port P to the same port P on 10.2.0.2; passing qBittorrent's port gets a new P on every request.
    def _map(self):
        udp = self.net.request(1, 0, 1, 60)
        tcp = self.net.request(2, 0, 1, 60)
        return udp, tcp

    def _kick_watchdog_locked(self):
        path = self.kick_file if self.kick_file is not None else os.environ.get("PCAPI_WG_KICK", VPN_WG_KICK)
        if not path: return
        parent = os.path.dirname(path)
        if parent and not os.path.isdir(parent):
            if not self._kick_missing_logged:
                self._kick_missing_logged = True
                print(f"vpn: watchdog kick dir missing ({parent}); skipping kick",
                      file=sys.stderr, flush=True)
            return
        tmp = f"{path}.tmp"
        fd = -1
        try:
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
            with os.fdopen(fd, "w") as f:
                fd = -1
                f.write(f"{int(time.time())}\n")
            os.replace(tmp, path)
            self._kick_missing_logged = False
        except OSError as e:
            if fd >= 0:
                try: os.close(fd)
                except OSError: pass
            try: os.remove(tmp)
            except OSError: pass
            if not self._kick_missing_logged:
                self._kick_missing_logged = True
                print(f"vpn: watchdog kick failed ({path}): {type(e).__name__}: {e}",
                      file=sys.stderr, flush=True)

    def _renew_locked(self):
        natpmp_failed = False
        try:
            try:
                udp, tcp = self._map()
                external = self._port(udp.get("external"), 0)
                if not external: raise NatPmpError(-1, "NAT-PMP returned no external port")
                tcp_external = self._port(tcp.get("external"), 0)
            except Exception:
                natpmp_failed = True
                raise
            if tcp_external and tcp_external != external:
                print(f"vpn: NAT-PMP ports differ (udp={external}, tcp={tcp_external}); using UDP",
                      file=sys.stderr, flush=True)
            self._set_qbt_port(external)
            self.forwarded_port = external
            self.natpmp_ok = True
            self.last_error = None
            self._next_renew = time.time() + self.renew
        except Exception as e:
            self.natpmp_ok = False
            self.forwarded_port = None
            self.last_error = clip(f"{type(e).__name__}: {e}", 240)
            backoff = VPN_NATPMP_BACKOFF if isinstance(e, NatPmpError) and e.result == 4 else 15
            self._next_renew = time.time() + backoff
            if natpmp_failed and self.enabled: self._kick_watchdog_locked()

    def _refresh_public_ip_locked(self):
        now = time.time()
        # A known IP is refreshed every 10 min; until the first success, retry on each loop tick.
        if self.public_ip is not None and now - self._ip_at < VPN_IP_INTERVAL: return
        if not self._iface_exists() or not self.natpmp_ok: return
        try:
            ip = self.ip_fetcher()
        except Exception:
            return
        if ip:
            self.public_ip = str(ip)
            self._ip_at = now

    def _status_check_locked(self):
        healthy = self._iface_exists() and self.natpmp_ok
        age = self._handshake_age() if healthy else None
        if healthy and age is not None and age > VPN_HANDSHAKE_MAX: healthy = False
        now = time.time()
        if healthy:
            self._unhealthy_since = None
            if self._alerted:
                self._alerted = False
                self._notify("Torrent VPN back up", "qBittorrent is routed through Proton again.")
            return
        if self._unhealthy_since is None:
            self._unhealthy_since = now
        elif not self._alerted and now - self._unhealthy_since >= self.alert_after:
            self._alerted = True
            why = self.last_error or ("proton0 is not up" if not self._iface_exists()
                                      else "Proton's NAT-PMP gateway is not answering")
            self._notify("Torrent VPN down", f"qBittorrent is not routed through Proton. {why}")

    # ---- status / loop
    def _status_locked(self):
        return {"enabled": self.enabled,
                "tunnel_up": self._tunnel_up() if self.enabled else False,
                "forwarded_port": self.forwarded_port if self.enabled else None,
                "qbt_interface": self.qbt_interface if self.enabled else "",
                "public_ip": self.public_ip if self.enabled else None,
                "last_error": self.last_error}

    def status(self):
        with self.lock:
            return self._status_locked()

    def start(self):
        threading.Thread(target=self.loop, daemon=True, name="torrent-vpn").start()
        return self

    def loop(self):
        while not self.stop.is_set():
            try:
                with self.lock:
                    if self.enabled:
                        if not self._started_enabled:
                            self._started_enabled = True
                            try:
                                self._enable_locked(persist=False)
                            except Exception as e:
                                self._started_enabled = False      # qBittorrent may still be starting; retry
                                self.last_error = clip(f"{type(e).__name__}: {e}", 240)
                        if time.time() >= self._next_renew: self._renew_locked()
                        self._refresh_public_ip_locked()
                        self._status_check_locked()
                    else:
                        self._started_enabled = False
            except Exception as e:
                print(f"vpn: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
            self.stop.wait(self.interval)


def vpn_health():
    return VPN.status() if VPN else {"enabled": False, "tunnel_up": False, "forwarded_port": None,
                                     "qbt_interface": "", "public_ip": None, "last_error": None}


VPN = None


# ---------------------------------------------------------------- always-on PC agent switch (/pc/api/agent)

AGENT_SWITCH = os.environ.get("PCAPI_AGENT_SWITCH", os.path.join(HOME, "remote-pc", "bin", "agent-switch"))
AGENT_OPTIONS = (
    {"id": "claude", "label": "Claude", "model": "Opus 5.5"},
    {"id": "gemini", "label": "Gemini", "model": "Gemini 4 Neon"},
)
AGENT_ENGINES = frozenset(o["id"] for o in AGENT_OPTIONS)
AGENT_SWITCH_TTL = 35.0          # s, how long switching=True stays set while waiting for the target unit


class AgentManager:
    """Switch the always-on PC agent between Claude (pc-agent.service) and Gemini (gemini-agent.service)
    via ~/remote-pc/bin/agent-switch. Switches launch detached via `systemd-run --user --collect --quiet`
    so they outlive the request even if the calling agent itself is being stopped."""

    def __init__(self, script=None, run_fn=None, clock=time.monotonic, switch_ttl=AGENT_SWITCH_TTL):
        self.script = script
        self.run_fn = run_fn
        self.clock = clock
        self.switch_ttl = switch_ttl
        self.lock = threading.Lock()
        self._switch_target = None
        self._switch_until = 0.0

    def _script_path(self):
        p = self.script if self.script is not None else AGENT_SWITCH
        return os.path.abspath(os.path.expanduser(p))

    def _is_available(self, path):
        return os.path.isfile(path) and os.access(path, os.X_OK)

    def _run(self, argv, timeout=5):
        fn = self.run_fn or subprocess.run
        return fn(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)

    @staticmethod
    def _options():
        return [dict(o) for o in AGENT_OPTIONS]

    def _unavailable(self):
        return {
            "engine": "claude",
            "claude_active": False,
            "gemini_active": False,
            "available": False,
            "switching": False,
            "options": self._options(),
        }

    def status(self, timeout=5):
        path = self._script_path()
        if not self._is_available(path):
            with self.lock:
                self._switch_target = None
                self._switch_until = 0.0
            return self._unavailable()
        try:
            r = self._run([path, "status"], timeout=timeout)
        except FileNotFoundError:
            with self.lock:
                self._switch_target = None
                self._switch_until = 0.0
            return self._unavailable()
        if r.returncode != 0:
            raise RuntimeError(clip((r.stderr or r.stdout or f"exit {r.returncode}").strip(), 200))
        try:
            d = json.loads(r.stdout or "{}")
        except ValueError as e:
            raise RuntimeError(f"bad agent-switch JSON: {e}")
        if not isinstance(d, dict):
            raise RuntimeError("agent-switch JSON must be an object")
        engine = d.get("engine") if d.get("engine") in AGENT_ENGINES else "claude"
        claude_active = bool(d.get("claude_active", False))
        gemini_active = bool(d.get("gemini_active", False))
        now = self.clock()
        with self.lock:
            switching = False
            if self._switch_target is not None:
                target = self._switch_target
                target_active = claude_active if target == "claude" else gemini_active
                other_active = gemini_active if target == "claude" else claude_active
                if (target_active and not other_active) or now >= self._switch_until:
                    self._switch_target = None
                    self._switch_until = 0.0
                else:
                    switching = True
        return {
            "engine": engine,
            "claude_active": claude_active,
            "gemini_active": gemini_active,
            "available": True,
            "switching": switching,
            "options": self._options(),
        }

    def switch(self, engine, timeout=10):
        if not isinstance(engine, str) or engine not in AGENT_ENGINES:
            return 400, {"error": "engine must be 'claude' or 'gemini'"}
        path = self._script_path()
        if not self._is_available(path):
            return 503, {"error": "agent-switch is not installed"}
        argv = ["systemd-run", "--user", "--collect", "--quiet", path, engine]
        try:
            r = self._run(argv, timeout=timeout)
        except Exception as e:
            return 502, {"error": clip(f"agent switch failed: {e}", 240)}
        if r.returncode != 0:
            detail = (r.stderr or r.stdout or f"exit {r.returncode}").strip()
            return 502, {"error": clip(f"agent switch failed: {detail}", 240)}
        with self.lock:
            self._switch_target = engine
            self._switch_until = self.clock() + self.switch_ttl
        return 202, {
            "engine": engine,
            "claude_active": False,
            "gemini_active": False,
            "available": True,
            "switching": True,
            "options": self._options(),
        }


def agent_health(timeout=None):
    return AGENT.status(timeout=HEALTH_TIMEOUT if timeout is None else timeout)


AGENT = AgentManager()


# ---------------------------------------------------------------- agent usage, settings & reset (/pc/api/agent/*)

AI_USAGE_BIN = os.environ.get("PCAPI_AI_USAGE", os.path.join(HOME, "remote-pc", "bin", "ai-usage"))
AGENT_RESET_BIN = os.environ.get("PCAPI_AGENT_RESET", os.path.join(HOME, "remote-pc", "bin", "agent-reset"))
CLAUDE_AGENT_SETTINGS = os.environ.get(
    "PCAPI_CLAUDE_AGENT_SETTINGS", os.path.join(HOME, ".config", "claude-agent", "settings.json")
)
GEMINI_AGENT_SETTINGS = os.environ.get(
    "PCAPI_GEMINI_AGENT_SETTINGS", os.path.join(HOME, ".config", "gemini-agent", "settings.json")
)
GEMINI_FRESH_FLAG = os.environ.get(
    "PCAPI_GEMINI_FRESH_FLAG", os.path.join(HOME, "remote-pc", ".fresh-gemini")
)
T3_SETTINGS_FILE = os.environ.get(
    "PCAPI_T3_SETTINGS", os.path.join(HOME, ".t3", "userdata", "settings.json")
)
T3_PROVIDERS_DIR = os.environ.get(
    "PCAPI_T3_PROVIDERS_DIR", os.path.join(HOME, ".t3", "userdata", "providers", "antigravity")
)
CLAUDE_CONFIG_FILE = os.environ.get("PCAPI_CLAUDE_CONFIG", os.path.join(HOME, ".claude.json"))
CLAUDE_CREDS_FILE = os.environ.get(
    "PCAPI_CLAUDE_CREDS", os.path.join(HOME, ".claude", ".credentials.json")
)
AGENT_USAGE_CACHE_FILE = os.environ.get(
    "PCAPI_AGENT_USAGE_CACHE", os.path.join(HOME, ".cache", "pc-api", "agent-usage.json")
)
AGENT_USAGE_CACHE_TTL = 60.0

CLAUDE_MODELS = (
    {"id": "claude-opus-5-5", "label": "Opus 5.5"},
    {"id": "claude-sonnet-5-5", "label": "Sonnet 5.5"},
    {"id": "claude-haiku-5-5", "label": "Haiku 5.5"},
    {"id": "claude-fable-5-2", "label": "Fable 5.2"},
)
CLAUDE_MODEL_IDS = frozenset(m["id"] for m in CLAUDE_MODELS)
CLAUDE_EFFORTS = ("low", "medium", "high", "xhigh", "max")

GEMINI_MODELS = (
    {"id": "gemini-4-carbon", "label": "Gemini 4 Carbon"},
    {"id": "gemini-4-neon", "label": "Gemini 4 Neon"},
    {"id": "gemini-4-argon", "label": "Gemini 4 Argon"},
    {"id": "gemini-4-hydrogen", "label": "Gemini 4 Hydrogen"},
    {"id": "gemini-3.8-flash", "label": "Gemini 3.8 Flash"},
    {"id": "gemini-pro-orchestrator", "label": "Orchestrator (Carbon)"},
    {"id": "gemini-pro-orchestrator-neon", "label": "Orchestrator (Neon)"},
    {"id": "gemini-pro-orchestrator-g1-pro", "label": "Orchestrator (Argon)"},
)
GEMINI_MODEL_IDS = frozenset(m["id"] for m in GEMINI_MODELS)
GEMINI_EFFORTS = ("low", "medium", "high", "xhigh")


def _read_json_file(path, default=None):
    try:
        with open(path, "r", encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else (default if default is not None else {})
    except Exception:
        return default if default is not None else {}


def _write_json_atomic(path, data, mode=0o600):
    d = os.path.dirname(os.path.abspath(path))
    os.makedirs(d, exist_ok=True)
    tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    try:
        os.chmod(tmp, mode)
    except OSError:
        pass
    os.replace(tmp, path)


def _read_claude_email(claude_config_path=None):
    p = claude_config_path if claude_config_path is not None else CLAUDE_CONFIG_FILE
    raw = _read_json_file(os.path.expanduser(p))
    oa = raw.get("oauthAccount") if isinstance(raw, dict) else None
    email = oa.get("emailAddress") if isinstance(oa, dict) else None
    return email.strip() if isinstance(email, str) and email.strip() else None


def _claude_has_credentials(claude_creds_path=None):
    p = claude_creds_path if claude_creds_path is not None else CLAUDE_CREDS_FILE
    raw = _read_json_file(os.path.expanduser(p))
    oauth = raw.get("claudeAiOauth") if isinstance(raw, dict) else None
    if not isinstance(oauth, dict):
        return False
    return bool(
        str(oauth.get("accessToken") or "").strip()
        or str(oauth.get("refreshToken") or "").strip()
    )


def _parse_iso_utc(ts):
    if not ts or not isinstance(ts, str):
        return None
    try:
        iso = ts.strip()
        if iso.endswith("Z"):
            iso = iso[:-1] + "+00:00"
        dt = datetime.datetime.fromisoformat(iso)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=datetime.timezone.utc)
        return dt.astimezone(datetime.timezone.utc)
    except ValueError:
        return None


def _window_is_unexpired(win, now_dt=None):
    if not isinstance(win, dict):
        return False
    if win.get("remaining_pct") is None and win.get("used_pct") is None:
        return False
    resets_at = win.get("resets_at")
    if isinstance(resets_at, str) and resets_at.strip():
        reset_dt = _parse_iso_utc(resets_at)
        if reset_dt is not None:
            ref = now_dt or datetime.datetime.now(datetime.timezone.utc)
            if reset_dt <= ref:
                return False
    return True


def _is_rate_limit_error(acc):
    if not isinstance(acc, dict):
        return False
    if acc.get("rate_limited"):
        return True
    text = f"{acc.get('error') or ''} {acc.get('status') or ''}".lower()
    return "429" in text or "rate limit" in text or "rate_limit" in text


def _list_t3_antigravity_instances(t3_settings_path=None, t3_providers_dir=None, email_map=None):
    settings_p = os.path.expanduser(t3_settings_path if t3_settings_path is not None else T3_SETTINGS_FILE)
    providers_p = os.path.expanduser(t3_providers_dir if t3_providers_dir is not None else T3_PROVIDERS_DIR)
    raw = _read_json_file(settings_p)
    providers = raw.get("providerInstances") if isinstance(raw, dict) else None
    if not isinstance(providers, dict):
        return []
    out = []
    for iid, entry in providers.items():
        if not isinstance(entry, dict):
            continue
        if entry.get("driver") != "antigravity" or entry.get("enabled") is False:
            continue
        inst_id = str(iid)
        label = str(entry.get("displayName") or inst_id)
        digest = hashlib.sha256(inst_id.encode("utf-8")).hexdigest()
        tok = os.path.join(providers_p, digest, "antigravity-acp", "antigravity-oauth-token")
        try:
            signed_in = os.path.isfile(tok) and os.path.getsize(tok) > 0
        except OSError:
            signed_in = False
        email = (email_map or {}).get(inst_id)
        out.append({"id": inst_id, "label": label, "signed_in": signed_in, "email": email})
    return out


_DURATION_PART_RE = re.compile(r"(\d+)\s*([dhms])", re.IGNORECASE)


def _duration_to_resets_at(dur):
    if not dur or not isinstance(dur, str):
        return None
    parts = _DURATION_PART_RE.findall(dur)
    if not parts:
        return None
    secs = 0
    for n_s, unit in parts:
        n = int(n_s)
        u = unit.lower()
        if u == "d":
            secs += n * 86400
        elif u == "h":
            secs += n * 3600
        elif u == "m":
            secs += n * 60
        elif u == "s":
            secs += n
    if secs <= 0:
        return None
    dt = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=secs)
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _normalize_usage_window(win):
    if not isinstance(win, dict):
        return None
    rem = win.get("remaining_pct")
    used = win.get("used_pct")
    if rem is None and used is None:
        return None
    resets_at = win.get("resets_at")
    if not resets_at and win.get("refreshes_in"):
        resets_at = _duration_to_resets_at(win.get("refreshes_in"))
    return {
        "remaining_pct": rem,
        "used_pct": used,
        "refreshes_in": win.get("refreshes_in"),
        "resets_at": resets_at,
        "status": win.get("status"),
    }


def _normalize_usage_report(raw, claude_config_path=None, claude_creds_path=None):
    accounts = []
    emails = {}
    agy_accounts = raw.get("antigravity_accounts")
    if isinstance(agy_accounts, list) and agy_accounts:
        for acc in agy_accounts:
            if not isinstance(acc, dict):
                continue
            inst_id = str(acc.get("instance_id") or "antigravity")
            label = str(acc.get("display_name") or inst_id)
            email = acc.get("account")
            if isinstance(email, str) and email.strip():
                emails[inst_id] = email.strip()
            sections = acc.get("sections") if isinstance(acc.get("sections"), dict) else {}
            gem = sections.get("gemini") if isinstance(sections.get("gemini"), dict) else {}
            accounts.append({
                "id": inst_id,
                "harness": "gemini",
                "label": label,
                "email": email,
                "signed_in": bool(acc.get("signed_in", True)),
                "status": acc.get("status"),
                "error": acc.get("error"),
                "five_hour": _normalize_usage_window(gem.get("five_hour")),
                "weekly": _normalize_usage_window(gem.get("weekly")),
            })
    else:
        agy = raw.get("antigravity")
        if isinstance(agy, dict):
            sections = agy.get("sections") if isinstance(agy.get("sections"), dict) else {}
            gem = sections.get("gemini") if isinstance(sections.get("gemini"), dict) else {}
            accounts.append({
                "id": "antigravity",
                "harness": "gemini",
                "label": "Antigravity",
                "email": agy.get("account"),
                "signed_in": bool(agy.get("signed_in", not bool(agy.get("error")))),
                "status": agy.get("status"),
                "error": agy.get("error"),
                "five_hour": _normalize_usage_window(gem.get("five_hour")),
                "weekly": _normalize_usage_window(gem.get("weekly")),
            })

    claude = raw.get("claude")
    if isinstance(claude, dict):
        c_email = claude.get("account") or _read_claude_email(claude_config_path)
        if "signed_in" in claude:
            c_signed_in = bool(claude["signed_in"])
        elif not claude.get("error"):
            c_signed_in = True
        else:
            c_signed_in = bool(c_email) or _claude_has_credentials(claude_creds_path)
        c_acc = {
            "id": "claude",
            "harness": "claude",
            "label": "Claude",
            "email": c_email,
            "signed_in": c_signed_in,
            "status": claude.get("status"),
            "error": claude.get("error"),
            "five_hour": _normalize_usage_window(claude.get("five_hour")),
            "weekly": _normalize_usage_window(claude.get("weekly")),
        }
        if claude.get("stale"):
            c_acc["stale"] = True
        if claude.get("rate_limited"):
            c_acc["rate_limited"] = True
        if claude.get("retry_after_s") is not None:
            c_acc["retry_after_s"] = claude.get("retry_after_s")
        accounts.append(c_acc)

    oc = raw.get("opencode")
    if isinstance(oc, dict):
        oc_err = oc.get("error")
        ds = oc.get("deepseek") if isinstance(oc.get("deepseek"), dict) else {}
        zen = oc.get("zen") if isinstance(oc.get("zen"), dict) else {}

        ds_info = None
        if ds:
            if ds.get("error"):
                ds_info = {"error": str(ds["error"])}
            elif "total_balance" in ds:
                cur = str(ds.get("currency") or "USD")
                bal = float(ds["total_balance"])
                sym = "$" if cur == "USD" else ""
                suffix = f" {cur}" if cur != "USD" else ""
                ds_info = {
                    "is_available": bool(ds.get("is_available", False)),
                    "currency": cur,
                    "total_balance": bal,
                    "formatted": f"{sym}{bal:.2f}{suffix}",
                }

        zen_info = None
        if zen:
            if zen.get("status") == "ok":
                models_raw = zen.get("models") if isinstance(zen.get("models"), list) else []
                models = [
                    {
                        "model": str(m.get("model") or ""),
                        "messages": int(m.get("messages") or 0),
                        "cost": m.get("cost"),
                    }
                    for m in models_raw
                    if isinstance(m, dict) and m.get("model")
                ]
                zen_info = {
                    "status": "ok",
                    "days": int(zen.get("days") or 7),
                    "sessions": int(zen.get("sessions") or 0),
                    "messages": int(zen.get("messages") or 0),
                    "total_cost": str(zen.get("total_cost") or "$0.00"),
                    "zen_messages": int(zen.get("zen_messages") or 0),
                    "models": models,
                }
            elif zen.get("error"):
                zen_info = {"status": "n/a", "error": str(zen["error"])}

        has_oc_data = bool(
            (ds_info and not ds_info.get("error"))
            or (zen_info and zen_info.get("status") == "ok")
        )
        accounts.append({
            "id": "opencode",
            "harness": "opencode",
            "label": "OpenCode / DeepSeek",
            "email": None,
            "signed_in": not bool(oc_err) and (has_oc_data or not (ds_info and ds_info.get("error"))),
            "status": None,
            "error": oc_err,
            "five_hour": None,
            "weekly": None,
            "deepseek": ds_info,
            "zen": zen_info,
        })

    return accounts, emails


class AgentUsageManager:
    """Query ~/remote-pc/bin/ai-usage --json with disk persistence and stale-while-revalidate."""

    def __init__(self, script=None, run_fn=None, clock=time.monotonic, ttl=AGENT_USAGE_CACHE_TTL,
                 claude_config_path=None, claude_creds_path=None, cache_path=None, on_update=None):
        self.script = script
        self.run_fn = run_fn
        self.clock = clock
        self.ttl = ttl
        self.claude_config_path = claude_config_path
        self.claude_creds_path = claude_creds_path
        self.cache_path = cache_path
        self.on_update = on_update
        self.lock = threading.Lock()
        self._fetch_lock = threading.Lock()
        self._cached = None
        self._cached_at = None
        self._refreshing = False
        self.emails = {}
        self._load_disk_cache()

    def _cache_file(self):
        p = self.cache_path if self.cache_path is not None else AGENT_USAGE_CACHE_FILE
        return os.path.expanduser(p) if p else ""

    def _load_disk_cache(self):
        path = self._cache_file()
        if not path:
            return
        data = _read_json_file(path, default={})
        if not isinstance(data, dict) or not isinstance(data.get("accounts"), list):
            return
        ts = int(data.get("ts") or 0)
        accounts = data["accounts"]
        emails = {}
        raw_emails = data.get("emails")
        if isinstance(raw_emails, dict):
            for k, v in raw_emails.items():
                if isinstance(k, str) and isinstance(v, str) and v.strip():
                    emails[k] = v.strip()
        for acc in accounts:
            if isinstance(acc, dict) and acc.get("harness") == "gemini":
                em = acc.get("email")
                aid = acc.get("id")
                if isinstance(aid, str) and isinstance(em, str) and em.strip():
                    emails.setdefault(aid, em.strip())
        with self.lock:
            self._cached = {
                "ts": ts,
                "accounts": accounts,
            }
            # Loaded from disk -> treat as stale so first request returns instantly & refreshes in bg.
            self._cached_at = None
            self.emails.update(emails)

    def _save_disk_cache(self, payload, emails):
        path = self._cache_file()
        if not path:
            return
        try:
            _write_json_atomic(
                path,
                {
                    "ts": payload["ts"],
                    "accounts": payload["accounts"],
                    "emails": dict(emails),
                },
                mode=0o600,
            )
        except OSError:
            pass

    def _script_path(self):
        p = self.script if self.script is not None else AI_USAGE_BIN
        return os.path.abspath(os.path.expanduser(p))

    def _is_available(self, path):
        return os.path.isfile(path) and os.access(path, os.X_OK)

    def _run(self, argv, timeout=45):
        fn = self.run_fn or subprocess.run
        return fn(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)

    def _fetch_and_store(self, timeout=45):
        with self._fetch_lock:
            path = self._script_path()
            if not self._is_available(path):
                return 503, {"error": "ai-usage is not installed"}
            try:
                r = self._run([path, "--json"], timeout=timeout)
            except Exception as e:
                return 502, {"error": clip(f"ai-usage failed: {e}", 240)}
            stdout = (r.stdout or "").strip()
            if not stdout:
                detail = (r.stderr or f"exit {r.returncode}").strip()
                return 502, {"error": clip(f"ai-usage failed: {detail}", 240)}
            try:
                raw = json.loads(stdout)
            except ValueError as e:
                return 502, {"error": clip(f"bad ai-usage JSON: {e}", 240)}
            if not isinstance(raw, dict):
                return 502, {"error": "ai-usage JSON must be an object"}

            accounts, emails = _normalize_usage_report(raw, self.claude_config_path, self.claude_creds_path)
            with self.lock:
                prev_cached = self._cached
            prev_claude = None
            if isinstance(prev_cached, dict) and isinstance(prev_cached.get("accounts"), list):
                prev_claude = next(
                    (a for a in prev_cached["accounts"]
                     if isinstance(a, dict) and (a.get("id") == "claude" or a.get("harness") == "claude")),
                    None,
                )
            now_dt = datetime.datetime.now(datetime.timezone.utc)
            for acc in accounts:
                if not isinstance(acc, dict) or (acc.get("id") != "claude" and acc.get("harness") != "claude"):
                    continue
                if acc.get("five_hour") is None and acc.get("weekly") is None and _is_rate_limit_error(acc):
                    if prev_claude is not None and prev_claude.get("signed_in"):
                        acc["signed_in"] = True
                    if prev_claude is not None:
                        prev_fh = (
                            prev_claude.get("five_hour")
                            if _window_is_unexpired(prev_claude.get("five_hour"), now_dt)
                            else None
                        )
                        prev_wk = (
                            prev_claude.get("weekly")
                            if _window_is_unexpired(prev_claude.get("weekly"), now_dt)
                            else None
                        )
                        if prev_fh is not None or prev_wk is not None:
                            acc["five_hour"] = prev_fh
                            acc["weekly"] = prev_wk
                            acc["stale"] = True
                            acc["rate_limited"] = True
                            if not acc.get("status"):
                                acc["status"] = acc.get("error") or "Rate limited"
                            acc["error"] = None
            payload = {
                "ts": int(time.time()),
                "cached": False,
                "stale": False,
                "accounts": accounts,
            }
            with self.lock:
                self._cached = {
                    "ts": payload["ts"],
                    "accounts": accounts,
                }
                self._cached_at = self.clock()
                self.emails.update(emails)
                emails_snapshot = dict(self.emails)
            self._save_disk_cache(self._cached, emails_snapshot)
            cb = self.on_update
            if cb is not None:
                try:
                    cb(accounts)
                except Exception as e:
                    print(f"usage-alerts callback error: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
            return 200, payload

    def _start_bg_refresh(self, timeout=45):
        with self.lock:
            if self._refreshing:
                return
            self._refreshing = True

        def _worker():
            try:
                self._fetch_and_store(timeout=timeout)
            except Exception:
                pass
            finally:
                with self.lock:
                    self._refreshing = False

        t = threading.Thread(target=_worker, name="agent-usage-refresh", daemon=True)
        t.start()

    def get(self, force_refresh=False, timeout=45):
        if not force_refresh:
            now = self.clock()
            with self.lock:
                cached = self._cached
                cached_at = self._cached_at
            if cached is not None:
                is_fresh = cached_at is not None and (now - cached_at) < self.ttl
                if is_fresh:
                    return 200, {**cached, "cached": True, "stale": False}
                self._start_bg_refresh(timeout=timeout)
                return 200, {**cached, "cached": True, "stale": True}

        return self._fetch_and_store(timeout=timeout)


USAGE_ALERTS_CONFIG_FILE = os.environ.get(
    "PCAPI_USAGE_ALERTS_CONFIG", os.path.join(DATA_DIR, "usage-alerts.json")
)
USAGE_ALERTS_STATE_FILE = os.environ.get(
    "PCAPI_USAGE_ALERTS_STATE", os.path.join(DATA_DIR, "usage-alerts-state.json")
)
DEFAULT_USAGE_ALERTS = {"enabled": True, "five_hour_pct": 20, "weekly_pct": 10}


class AgentSettingsManager:
    """Persist and validate Claude (~/.config/claude-agent/settings.json), Gemini
    (~/.config/gemini-agent/settings.json), and usage alert thresholds."""

    def __init__(self, claude_path=None, gemini_path=None, gemini_fresh_flag=None,
                 t3_settings_path=None, t3_providers_dir=None, claude_config_path=None,
                 usage_mgr=None, usage_alerts_path=None,
                 claude_thread_file=None, gemini_thread_file=None, t3_client=None):
        self.claude_path = claude_path
        self.gemini_path = gemini_path
        self.gemini_fresh_flag = gemini_fresh_flag
        self.t3_settings_path = t3_settings_path
        self.t3_providers_dir = t3_providers_dir
        self.claude_config_path = claude_config_path
        self.usage_mgr = usage_mgr
        self.usage_alerts_path = usage_alerts_path
        self.claude_thread_file = claude_thread_file
        self.gemini_thread_file = gemini_thread_file
        self.t3_client = t3_client
        self.lock = threading.Lock()

    def _claude_thread_file(self):
        if self.claude_thread_file is not None:
            return os.path.expanduser(self.claude_thread_file)
        if self.claude_path is not None:
            return None
        return os.path.expanduser(chat_mod.CLAUDE_T3_THREAD_FILE)

    def _gemini_thread_file(self):
        if self.gemini_thread_file is not None:
            return os.path.expanduser(self.gemini_thread_file)
        if self.gemini_path is not None:
            return None
        return os.path.expanduser(chat_mod.T3_THREAD_FILE)

    def _configure_t3_thread(self, engine):
        """Best-effort: apply updated model/effort settings to an active T3 Code thread."""
        tf = self._claude_thread_file() if engine == "claude" else self._gemini_thread_file()
        if not tf:
            return False
        state = _read_json_file(tf)
        thread_id = state.get("thread_id")
        if not isinstance(thread_id, str) or not thread_id.strip():
            return False
        model_sel = (
            chat_mod.build_claude_model_selection(self._claude_file())
            if engine == "claude"
            else chat_mod.build_gemini_model_selection(self._gemini_file())
        )
        try:
            client = self.t3_client or chat_mod.T3Client(timeout=5.0)
            client.call_tool(
                "t3_thread_configure",
                {"threadId": thread_id.strip(), "modelSelection": model_sel},
            )
            return True
        except Exception:
            return False

    def _claude_file(self):
        return os.path.expanduser(self.claude_path if self.claude_path is not None else CLAUDE_AGENT_SETTINGS)

    def _gemini_file(self):
        return os.path.expanduser(self.gemini_path if self.gemini_path is not None else GEMINI_AGENT_SETTINGS)

    def _fresh_flag(self):
        return os.path.expanduser(self.gemini_fresh_flag if self.gemini_fresh_flag is not None else GEMINI_FRESH_FLAG)

    def _usage_alerts_file(self):
        p = self.usage_alerts_path if self.usage_alerts_path is not None else USAGE_ALERTS_CONFIG_FILE
        return os.path.expanduser(p)

    def get_usage_alerts(self):
        with self.lock:
            raw = _read_json_file(self._usage_alerts_file())
        enabled = raw.get("enabled") if isinstance(raw.get("enabled"), bool) else DEFAULT_USAGE_ALERTS["enabled"]
        fh = raw.get("five_hour_pct")
        fh = int(fh) if isinstance(fh, (int, float)) and not isinstance(fh, bool) and 1 <= fh <= 95 else DEFAULT_USAGE_ALERTS["five_hour_pct"]
        wk = raw.get("weekly_pct")
        wk = int(wk) if isinstance(wk, (int, float)) and not isinstance(wk, bool) and 1 <= wk <= 95 else DEFAULT_USAGE_ALERTS["weekly_pct"]
        return {"enabled": enabled, "five_hour_pct": fh, "weekly_pct": wk}

    def get(self):
        with self.lock:
            c_raw = _read_json_file(self._claude_file())
            g_raw = _read_json_file(self._gemini_file())

        email_map = dict(self.usage_mgr.emails) if self.usage_mgr is not None else {}
        instances = _list_t3_antigravity_instances(self.t3_settings_path, self.t3_providers_dir, email_map)
        default_inst = next((i["id"] for i in instances if i["signed_in"]), instances[0]["id"] if instances else "antigravity")

        c_model = c_raw.get("model") if c_raw.get("model") in CLAUDE_MODEL_IDS else "claude-opus-5-5"
        c_effort = c_raw.get("effort") if c_raw.get("effort") in CLAUDE_EFFORTS else "high"

        g_inst = g_raw.get("instance_id") if isinstance(g_raw.get("instance_id"), str) and g_raw.get("instance_id").strip() else default_inst
        g_model = g_raw.get("model") if isinstance(g_raw.get("model"), str) and g_raw.get("model").strip() else "gemini-4-neon"
        g_thought = g_raw.get("thought_level") if g_raw.get("thought_level") in GEMINI_EFFORTS else "medium"
        g_auto = bool(g_raw.get("auto_rotate", False))

        return {
            "claude": {
                "account": _read_claude_email(self.claude_config_path),
                "model": c_model,
                "effort": c_effort,
                "models": [dict(m) for m in CLAUDE_MODELS],
                "efforts": list(CLAUDE_EFFORTS),
            },
            "gemini": {
                "instance_id": g_inst,
                "model": g_model,
                "thought_level": g_thought,
                "auto_rotate": g_auto,
                "instances": instances,
                "models": [dict(m) for m in GEMINI_MODELS],
                "efforts": list(GEMINI_EFFORTS),
            },
            "usage_alerts": self.get_usage_alerts(),
        }

    def update(self, body):
        if not isinstance(body, dict):
            return 400, {"error": "body must be a JSON object"}
        c_upd = body.get("claude")
        g_upd = body.get("gemini")
        u_upd = body.get("usage_alerts")
        if c_upd is None and g_upd is None and u_upd is None:
            return 400, {"error": "provide 'claude', 'gemini', and/or 'usage_alerts' settings"}

        if c_upd is not None:
            if not isinstance(c_upd, dict):
                return 400, {"error": "claude must be an object"}
            if "model" in c_upd and c_upd["model"] not in CLAUDE_MODEL_IDS:
                return 400, {"error": f"invalid claude model: {c_upd['model']!r}"}
            if "effort" in c_upd and c_upd["effort"] not in CLAUDE_EFFORTS:
                return 400, {"error": f"invalid claude effort: {c_upd['effort']!r}"}

        if g_upd is not None:
            if not isinstance(g_upd, dict):
                return 400, {"error": "gemini must be an object"}
            if "instance_id" in g_upd:
                iid = g_upd["instance_id"]
                if not isinstance(iid, str) or not iid.strip():
                    return 400, {"error": "invalid gemini instance_id"}
                instances = _list_t3_antigravity_instances(self.t3_settings_path, self.t3_providers_dir)
                known_ids = {i["id"] for i in instances}
                if known_ids and iid not in known_ids:
                    return 400, {"error": f"unknown gemini instance_id: {iid!r}"}
            if "model" in g_upd:
                m = g_upd["model"]
                if not isinstance(m, str) or not m.strip() or len(m) > 100:
                    return 400, {"error": "invalid gemini model"}
            if "thought_level" in g_upd and g_upd["thought_level"] not in GEMINI_EFFORTS:
                return 400, {"error": f"invalid gemini thought_level: {g_upd['thought_level']!r}"}
            if "auto_rotate" in g_upd and not isinstance(g_upd["auto_rotate"], bool):
                return 400, {"error": "gemini auto_rotate must be boolean"}

        if u_upd is not None:
            if not isinstance(u_upd, dict):
                return 400, {"error": "usage_alerts must be an object"}
            if "enabled" in u_upd and not isinstance(u_upd["enabled"], bool):
                return 400, {"error": "usage_alerts.enabled must be boolean"}
            for field in ("five_hour_pct", "weekly_pct"):
                if field in u_upd:
                    v = u_upd[field]
                    if isinstance(v, bool) or not isinstance(v, (int, float)) or not (1 <= v <= 95):
                        return 400, {"error": f"usage_alerts.{field} must be between 1 and 95"}

        with self.lock:
            if c_upd is not None:
                c_path = self._claude_file()
                cur = _read_json_file(c_path)
                if "model" in c_upd:
                    cur["model"] = c_upd["model"]
                if "effort" in c_upd:
                    cur["effort"] = c_upd["effort"]
                _write_json_atomic(c_path, cur)

            if g_upd is not None:
                g_path = self._gemini_file()
                cur = _read_json_file(g_path)
                prev_inst = cur.get("instance_id")
                if "instance_id" in g_upd:
                    cur["instance_id"] = g_upd["instance_id"].strip()
                if "model" in g_upd:
                    cur["model"] = g_upd["model"].strip()
                if "thought_level" in g_upd:
                    cur["thought_level"] = g_upd["thought_level"]
                if "auto_rotate" in g_upd:
                    cur["auto_rotate"] = g_upd["auto_rotate"]
                _write_json_atomic(g_path, cur)
                if "instance_id" in g_upd and cur.get("instance_id") != prev_inst:
                    flag = self._fresh_flag()
                    try:
                        os.makedirs(os.path.dirname(os.path.abspath(flag)), exist_ok=True)
                        with open(flag, "a", encoding="utf-8"):
                            pass
                    except OSError:
                        pass

            if u_upd is not None:
                u_path = self._usage_alerts_file()
                cur = _read_json_file(u_path)
                if "enabled" in u_upd:
                    cur["enabled"] = u_upd["enabled"]
                if "five_hour_pct" in u_upd:
                    cur["five_hour_pct"] = int(u_upd["five_hour_pct"])
                if "weekly_pct" in u_upd:
                    cur["weekly_pct"] = int(u_upd["weekly_pct"])
                _write_json_atomic(u_path, cur)

        if c_upd is not None and ("model" in c_upd or "effort" in c_upd):
            self._configure_t3_thread("claude")
        if g_upd is not None and ("model" in g_upd or "thought_level" in g_upd or "instance_id" in g_upd):
            self._configure_t3_thread("gemini")

        return 200, self.get()


def agent_reset(script=None, run_fn=None, timeout=10):
    path = os.path.abspath(os.path.expanduser(script if script is not None else AGENT_RESET_BIN))
    if not (os.path.isfile(path) and os.access(path, os.X_OK)):
        return 503, {"error": "agent-reset is not installed"}
    fn = run_fn or subprocess.run
    try:
        r = fn([path], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
    except Exception as e:
        return 502, {"error": clip(f"agent reset failed: {e}", 240)}
    if r.returncode != 0:
        detail = (r.stderr or r.stdout or f"exit {r.returncode}").strip()
        return 502, {"error": clip(f"agent reset failed: {detail}", 240)}
    msg = (r.stdout or "Reset requested").strip()
    return 200, {"ok": True, "message": msg}


AGENT_USAGE = AgentUsageManager()
AGENT_SETTINGS = AgentSettingsManager(usage_mgr=AGENT_USAGE)


# ---------------------------------------------------------------- usage alerts (activity-triggered, no polling)

def _stat_tree_sig(paths):
    """Cheap local-only (max_mtime_ns, entry_count) across watched paths (zero network)."""
    max_mtime = 0
    count = 0
    for spec in paths:
        if isinstance(spec, tuple):
            pattern, depth = spec
        else:
            pattern, depth = spec, 1
        expanded = os.path.expanduser(pattern)
        roots = glob.glob(expanded) if any(c in expanded for c in "*?[") else [expanded]
        for root in roots:
            try:
                st = os.stat(root)
            except OSError:
                continue
            count += 1
            if st.st_mtime_ns > max_mtime:
                max_mtime = st.st_mtime_ns
            if depth <= 0 or not os.path.isdir(root):
                continue
            stack = [(root, depth)]
            while stack:
                d, rem = stack.pop()
                try:
                    with os.scandir(d) as it:
                        for entry in it:
                            try:
                                est = entry.stat(follow_symlinks=False)
                            except OSError:
                                continue
                            count += 1
                            if est.st_mtime_ns > max_mtime:
                                max_mtime = est.st_mtime_ns
                            if rem > 1 and entry.is_dir(follow_symlinks=False):
                                stack.append((entry.path, rem - 1))
                except OSError:
                    continue
    return (max_mtime, count)


def _default_activity_paths():
    return [
        (os.path.join(HOME, ".t3", "userdata", "providers", "antigravity", "*", "antigravity-acp", "log"), 1),
        (os.path.join(HOME, ".claude", "projects"), 2),
        (os.path.join(HOME, ".local", "state", "gemini-agent"), 1),
        (os.path.join(HOME, ".local", "share", "opencode", "opencode.db"), 0),
        (os.path.join(HOME, ".local", "share", "opencode", "opencode.db-wal"), 0),
        (os.path.join(HOME, ".local", "share", "opencode", "log"), 1),
    ]


class UsageAlertWatcher:
    """Watches local agent activity mtimes and runs ai-usage at most once per min_interval (30 min)
    when activity is seen. When nothing is running there are zero network calls."""

    def __init__(self, notify, usage_mgr, settings_mgr, state_file=None, watch_paths=None,
                 min_interval=1800.0, poll_interval=15.0, clock=time.monotonic):
        self.notify = notify
        self.usage_mgr = usage_mgr
        self.settings_mgr = settings_mgr
        self.state_file = state_file
        self.watch_paths = watch_paths
        self.min_interval = min_interval
        self.poll_interval = poll_interval
        self.clock = clock
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self._last_sig = None
        self._pending = False
        self._last_refresh = None
        self._dedupe = self._load_state()

    def _state_path(self):
        p = self.state_file if self.state_file is not None else USAGE_ALERTS_STATE_FILE
        return os.path.expanduser(p) if p else ""

    def _load_state(self):
        p = self._state_path()
        if not p:
            return {}
        raw = _read_json_file(p, default={})
        return {str(k): str(v) for k, v in raw.items() if isinstance(k, str) and isinstance(v, str)}

    def _save_state(self, state):
        p = self._state_path()
        if not p:
            return
        try:
            _write_json_atomic(p, state, mode=0o600)
        except OSError:
            pass

    def activity_signature(self):
        paths = self.watch_paths if self.watch_paths is not None else _default_activity_paths()
        return _stat_tree_sig(paths)

    def _send(self, title, body):
        fn = getattr(self.notify, "send_async", None) or getattr(self.notify, "send", None)
        if fn:
            fn("usage_limits", title, body)

    def evaluate(self, accounts):
        """Evaluate normalized usage accounts against configured thresholds and send deduped alerts.
        Returns list of (account_id, alert_kind, title, body) sent."""
        if not isinstance(accounts, list):
            return []
        cfg = self.settings_mgr.get_usage_alerts() if self.settings_mgr is not None else dict(DEFAULT_USAGE_ALERTS)
        if not cfg.get("enabled", True):
            return []
        five_pct = cfg.get("five_hour_pct", DEFAULT_USAGE_ALERTS["five_hour_pct"])
        week_pct = cfg.get("weekly_pct", DEFAULT_USAGE_ALERTS["weekly_pct"])

        to_send = []
        changed = False
        with self.lock:
            dedupe = self._dedupe
            for acc in accounts:
                if not isinstance(acc, dict):
                    continue
                if not acc.get("signed_in") or acc.get("error") or acc.get("harness") == "opencode":
                    continue
                aid = str(acc.get("id") or "")
                if not aid:
                    continue
                name = str(acc.get("email") or acc.get("label") or aid)

                fh = acc.get("five_hour")
                if isinstance(fh, dict):
                    rem = fh.get("remaining_pct")
                    if isinstance(rem, (int, float)) and not isinstance(rem, bool):
                        token = str(fh.get("resets_at") or fh.get("status") or "window")
                        k_zero = f"{aid}:five_hour_zero"
                        k_low = f"{aid}:five_hour_low"
                        if rem <= 0:
                            if dedupe.get(k_zero) != token:
                                dedupe[k_zero] = token
                                dedupe[k_low] = token
                                changed = True
                                title = f"{name}: 5h limit reached"
                                body = "5-hour quota is at 0% remaining."
                                to_send.append((aid, "five_hour_zero", title, body))
                        elif rem <= five_pct:
                            if dedupe.get(k_low) != token:
                                dedupe[k_low] = token
                                changed = True
                                title = f"{name}: 5h quota low"
                                body = f"{round(rem)}% remaining on 5-hour limit."
                                to_send.append((aid, "five_hour_low", title, body))
                        else:
                            if k_low in dedupe:
                                del dedupe[k_low]
                                changed = True
                            if k_zero in dedupe:
                                del dedupe[k_zero]
                                changed = True

                wk = acc.get("weekly")
                if isinstance(wk, dict):
                    rem = wk.get("remaining_pct")
                    if isinstance(rem, (int, float)) and not isinstance(rem, bool):
                        token = str(wk.get("resets_at") or wk.get("status") or "window")
                        k_wk = f"{aid}:weekly_low"
                        if rem <= week_pct:
                            if dedupe.get(k_wk) != token:
                                dedupe[k_wk] = token
                                changed = True
                                title = f"{name}: weekly quota low"
                                body = f"{round(rem)}% remaining on weekly limit."
                                to_send.append((aid, "weekly_low", title, body))
                        else:
                            if k_wk in dedupe:
                                del dedupe[k_wk]
                                changed = True

            if changed:
                self._save_state(dict(dedupe))

        for _, _, title, body in to_send:
            self._send(title, body)
        return to_send

    def check_activity(self):
        """Check local activity mtimes; trigger ai-usage refresh only if activity changed and
        at least min_interval has elapsed since the previous activity-triggered refresh."""
        sig = self.activity_signature()
        with self.lock:
            if self._last_sig is None:
                self._last_sig = sig
                return False
            if sig != self._last_sig:
                self._last_sig = sig
                self._pending = True
            if not self._pending:
                return False
            cfg = self.settings_mgr.get_usage_alerts() if self.settings_mgr is not None else dict(DEFAULT_USAGE_ALERTS)
            if not cfg.get("enabled", True):
                self._pending = False
                return False
            now = self.clock()
            if self._last_refresh is not None and (now - self._last_refresh) < self.min_interval:
                return False
            self._pending = False
            self._last_refresh = now

        if self.usage_mgr is not None:
            code, payload = self.usage_mgr.get(force_refresh=True)
            # If usage_mgr does not have our callback wired, evaluate directly.
            if code == 200 and getattr(self.usage_mgr, "on_update", None) is None:
                self.evaluate(payload.get("accounts") or [])
        return True

    def start(self):
        self._last_sig = self.activity_signature()
        threading.Thread(target=self._loop, daemon=True, name="usage-alert-watcher").start()
        return self

    def _loop(self):
        while not self.stop.is_set():
            try:
                self.check_activity()
            except Exception as e:
                print(f"usage-alert-watcher: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
            self.stop.wait(self.poll_interval)


# ---------------------------------------------------------------- cat cam master switch (/pc/api/catcam)

CATCAM_UNITS = ("catcam", "cam-ptz", "camrec", "catdetect", "cam-music")
CATCAM_STATE_FILE = os.environ.get("PCAPI_CATCAM_STATE", os.path.join(DATA_DIR, "catcam-state.json"))


class CatCamManager:
    """Master switch for the cat cam systemd user stack: disable --now when off, enable --now when on."""

    def __init__(self, unit_dir=None, state_file=None, run_fn=None):
        self.unit_dir = unit_dir
        self.state_file = state_file
        self.run_fn = run_fn
        self.lock = threading.Lock()

    def _unit_dir(self):
        return os.path.expanduser(self.unit_dir if self.unit_dir is not None else UNIT_DIR)

    def _state_file(self):
        return os.path.expanduser(self.state_file if self.state_file is not None else CATCAM_STATE_FILE)

    def existing_units(self):
        udir = self._unit_dir()
        return [name for name in CATCAM_UNITS if os.path.exists(os.path.join(udir, f"{name}.service"))]

    def _run(self, argv, timeout=20):
        fn = self.run_fn or subprocess.run
        return fn(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)

    def _unit_states(self, units):
        if not units:
            return {}
        try:
            r = self._run(["systemctl", "--user", "is-active", *[f"{u}.service" for u in units]], timeout=10)
            lines = [ln.strip() for ln in (r.stdout or "").splitlines()]
        except Exception:
            lines = []
        out = {}
        for i, name in enumerate(units):
            out[name] = lines[i] if i < len(lines) and lines[i] else "unknown"
        return out

    def _load_enabled(self):
        raw = _read_json_file(self._state_file(), default={})
        if isinstance(raw.get("enabled"), bool):
            return raw["enabled"]
        return True

    def status(self):
        with self.lock:
            enabled = self._load_enabled()
        units = self.existing_units()
        return {"enabled": enabled, "units": self._unit_states(units)}

    def set_enabled(self, enabled):
        if not isinstance(enabled, bool):
            return 400, {"error": "enabled must be true or false"}
        with self.lock:
            units = self.existing_units()
            if units:
                action = "enable" if enabled else "disable"
                try:
                    r = self._run(["systemctl", "--user", action, "--now", *[f"{u}.service" for u in units]], timeout=25)
                except Exception as e:
                    return 502, {"error": clip(f"systemctl {action} failed: {e}", 240)}
                if r.returncode != 0:
                    detail = (r.stderr or r.stdout or f"exit {r.returncode}").strip()
                    return 502, {"error": clip(f"systemctl {action} failed: {detail}", 240)}
            _write_json_atomic(self._state_file(), {"enabled": enabled}, mode=0o600)
        return 200, self.status()


CATCAM = CatCamManager()


# ---------------------------------------------------------------- catdetect snapshots (/pc/api/catdetect/snaps*)

CATDETECT_SNAPS_DIR = os.environ.get(
    "PCAPI_CATDETECT_SNAPS_DIR",
    os.path.join(HOME, "remote-pc", "snaps", "detections"),
)
CATDETECT_SNAP_RE = re.compile(r"^([a-z0-9_-]+)-(\d{8})-(\d{6})\.jpg$")
CATDETECT_CAM_RE = re.compile(r"^[a-z0-9_-]+$")
CATDETECT_SNAPS_DEFAULT_LIMIT = 300
CATDETECT_SNAPS_MAX_LIMIT = 300


def _parse_catdetect_snap_ts_ms(date_s, time_s, fallback_mtime):
    try:
        dt = datetime.datetime.strptime(f"{date_s}{time_s}", "%Y%m%d%H%M%S")
        return int(dt.timestamp() * 1000)
    except (ValueError, OSError):
        return int(fallback_mtime * 1000)


def list_catdetect_snaps(cam=None, limit=CATDETECT_SNAPS_DEFAULT_LIMIT, snaps_dir=None):
    """Scan saved catdetect detection JPEGs and return newest-first metadata + per-cam counts."""
    cam_filter = (cam or "").strip()
    if cam_filter == "all":
        cam_filter = ""
    if cam_filter and not CATDETECT_CAM_RE.fullmatch(cam_filter):
        return 400, {"error": "invalid cam filter"}

    try:
        lim = int(limit) if limit is not None and str(limit).strip() != "" else CATDETECT_SNAPS_DEFAULT_LIMIT
    except (TypeError, ValueError):
        return 400, {"error": "invalid limit"}
    lim = max(1, min(lim, CATDETECT_SNAPS_MAX_LIMIT))

    base_dir = os.path.expanduser(snaps_dir if snaps_dir is not None else CATDETECT_SNAPS_DIR)
    counts = {"all": 0, "cat": 0, "cat2": 0, "cat3": 0}
    items = []
    if os.path.isdir(base_dir):
        try:
            with os.scandir(base_dir) as it:
                for entry in it:
                    m = CATDETECT_SNAP_RE.fullmatch(entry.name)
                    if not m:
                        continue
                    try:
                        if not entry.is_file():
                            continue
                        st = entry.stat()
                    except OSError:
                        continue
                    cam_key, date_s, time_s = m.group(1), m.group(2), m.group(3)
                    counts["all"] += 1
                    counts[cam_key] = counts.get(cam_key, 0) + 1
                    if cam_filter and cam_key != cam_filter:
                        continue
                    items.append({
                        "filename": entry.name,
                        "cam": cam_key,
                        "ts_ms": _parse_catdetect_snap_ts_ms(date_s, time_s, st.st_mtime),
                        "size_bytes": st.st_size,
                        "url": f"/pc/api/catdetect/snaps/{entry.name}",
                    })
        except OSError:
            pass

    items.sort(key=lambda s: (s["ts_ms"], s["filename"]), reverse=True)
    return 200, {
        "snaps": items[:lim],
        "total": len(items),
        "counts": counts,
    }


def read_catdetect_snap(raw_filename, snaps_dir=None):
    """Strictly validate a detection snapshot filename and return (code, bytes_or_error_dict)."""
    if not isinstance(raw_filename, str) or not raw_filename:
        return 400, {"error": "invalid snapshot filename"}
    decoded = urllib.parse.unquote(raw_filename)
    for cand in (raw_filename, decoded):
        if "/" in cand or "\\" in cand or ".." in cand or "\x00" in cand:
            return 400, {"error": "invalid snapshot filename"}
    if not CATDETECT_SNAP_RE.fullmatch(raw_filename) or decoded != raw_filename:
        return 400, {"error": "invalid snapshot filename"}

    base_dir = os.path.expanduser(snaps_dir if snaps_dir is not None else CATDETECT_SNAPS_DIR)
    base_real = os.path.realpath(base_dir)
    full_path = os.path.realpath(os.path.join(base_real, raw_filename))
    if not full_path.startswith(base_real + os.sep):
        return 400, {"error": "invalid snapshot filename"}
    if not os.path.isfile(full_path):
        return 404, {"error": "snapshot not found"}
    try:
        with open(full_path, "rb") as f:
            return 200, f.read()
    except OSError:
        return 404, {"error": "snapshot not found"}


# ---------------------------------------------------------------- Agentview master switch (/pc/api/agentview/*)

AGENTVIEW_STATE_FILE = os.environ.get(
    "PCAPI_AGENTVIEW_STATE",
    os.path.join(HOME, "tools", "agentview", "state", "capture.state"),
)
AGENTVIEW_RULES_FILE = os.environ.get(
    "PCAPI_AGENTVIEW_RULES",
    os.path.join(HOME, "tools", "agentview", "rules.json"),
)
AGENTVIEW_WEB_URL = os.environ.get(
    "PCAPI_AGENTVIEW_WEB_URL",
    "http://127.0.0.1:1991/agentview/api/state",
)
AGENTVIEW_WEB_UNIT = "agentview-web.service"
AGENTVIEW_SYSTEM_UNITS = (
    ("agentview-front", "agentview-front.service"),
    ("agentview-agentproxy", "agentview-agentproxy.service"),
    ("agentview-agentproxy-watch.timer", "agentview-agentproxy-watch.timer"),
)
AGENTVIEW_USER_UNITS = (
    ("agentview-mitm", "agentview-mitm.service"),
    ("agentview-web", "agentview-web.service"),
)


class AgentViewManager:
    """Master switch for the entire Agentview stack via capture.state + agentview-web.service sync."""

    def __init__(
        self,
        state_file=None,
        rules_file=None,
        web_url=None,
        run_fn=None,
        probe_fn=None,
        sleep_fn=None,
        monotonic_fn=None,
        start_timeout=6.0,
        stop_timeout=4.0,
        poll_interval=0.25,
    ):
        self.state_file = state_file
        self.rules_file = rules_file
        self.web_url = web_url
        self.run_fn = run_fn
        self.probe_fn = probe_fn
        self.sleep_fn = sleep_fn
        self.monotonic_fn = monotonic_fn
        self.start_timeout = float(start_timeout)
        self.stop_timeout = float(stop_timeout)
        self.poll_interval = float(poll_interval)
        self.lock = threading.Lock()

    def _state_file(self):
        return os.path.expanduser(self.state_file if self.state_file is not None else AGENTVIEW_STATE_FILE)

    def _rules_file(self):
        if self.rules_file is not None:
            return os.path.expanduser(self.rules_file)
        # Do not touch the real ~/tools/agentview/rules.json when state_file is overridden (e.g. unit tests).
        if self.state_file is not None:
            return None
        return os.path.expanduser(AGENTVIEW_RULES_FILE)

    def _web_url(self):
        return self.web_url if self.web_url is not None else AGENTVIEW_WEB_URL

    def _now(self):
        return (self.monotonic_fn or time.monotonic)()

    def _sleep(self, secs):
        if secs > 0:
            (self.sleep_fn or time.sleep)(secs)

    def _run(self, argv, timeout=10):
        fn = self.run_fn or subprocess.run
        return fn(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)

    def _read_capture_state(self):
        path = self._state_file()
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except FileNotFoundError:
            return "off"
        except OSError:
            return "off"
        try:
            st = os.fstat(fd)
            if not stat.S_ISREG(st.st_mode) or st.st_size > 64:
                return "off"
            with os.fdopen(fd, "rb") as f:
                fd = -1
                raw = f.read(64)
        except OSError:
            return "off"
        finally:
            if fd >= 0:
                try:
                    os.close(fd)
                except OSError:
                    pass
        text = raw.decode("utf-8", "replace").strip()
        return "on" if text == "on" else "off"

    def _write_capture_state(self, enabled):
        target = os.path.abspath(self._state_file())
        parent = os.path.dirname(target)
        if os.path.lexists(parent):
            pst = os.lstat(parent)
            if stat.S_ISLNK(pst.st_mode) or not stat.S_ISDIR(pst.st_mode):
                raise ValueError("capture.state parent directory is a symlink or not a directory")
        else:
            os.makedirs(parent, mode=0o700, exist_ok=True)

        prev_st = None
        if os.path.lexists(target):
            prev_st = os.lstat(target)
            if stat.S_ISLNK(prev_st.st_mode) or not stat.S_ISREG(prev_st.st_mode):
                raise ValueError("capture.state must be a regular file, not a symlink")

        payload = b"on\n" if enabled else b"off\n"
        fd, tmp = tempfile.mkstemp(prefix=".capture.state.", dir=parent)
        try:
            os.fchmod(fd, 0o600)
            if prev_st is not None and os.geteuid() == 0:
                os.fchown(fd, prev_st.st_uid, prev_st.st_gid)
            with os.fdopen(fd, "wb") as f:
                fd = -1
                f.write(payload)
                f.flush()
                os.fsync(f.fileno())
            if os.path.lexists(target):
                cur_st = os.lstat(target)
                if stat.S_ISLNK(cur_st.st_mode) or not stat.S_ISREG(cur_st.st_mode):
                    raise ValueError("capture.state must be a regular file, not a symlink")
            os.replace(tmp, target)
            tmp = None
        finally:
            if fd >= 0:
                try:
                    os.close(fd)
                except OSError:
                    pass
            if tmp is not None:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
        try:
            dfd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
        except OSError:
            pass

    def _sync_rules_enabled(self, enabled):
        """Best-effort sync of rules.json's top-level enabled flag so agentview-web doesn't revert capture.state."""
        rules_path = self._rules_file()
        if not rules_path:
            return
        target = os.path.abspath(rules_path)
        parent = os.path.dirname(target)
        try:
            if not os.path.lexists(target):
                return
            prev_st = os.lstat(target)
            if stat.S_ISLNK(prev_st.st_mode) or not stat.S_ISREG(prev_st.st_mode):
                return
            fd = os.open(target, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                with os.fdopen(fd, "r", encoding="utf-8") as f:
                    fd = -1
                    data = json.load(f)
            finally:
                if fd >= 0:
                    os.close(fd)
            if not isinstance(data, dict) or data.get("enabled") is enabled:
                return
            data["enabled"] = enabled
            raw = (json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
            wfd, tmp = tempfile.mkstemp(prefix=".rules.json.", dir=parent)
            try:
                mode = stat.S_IMODE(prev_st.st_mode) or 0o600
                os.fchmod(wfd, mode)
                if os.geteuid() == 0:
                    os.fchown(wfd, prev_st.st_uid, prev_st.st_gid)
                with os.fdopen(wfd, "wb") as f:
                    wfd = -1
                    f.write(raw)
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, target)
                tmp = None
            finally:
                if wfd >= 0:
                    try:
                        os.close(wfd)
                    except OSError:
                        pass
                if tmp is not None:
                    try:
                        os.unlink(tmp)
                    except OSError:
                        pass
        except Exception:
            pass

    def _probe_web(self, timeout=1.0):
        url = self._web_url()
        if self.probe_fn is not None:
            try:
                return bool(self.probe_fn(url, timeout))
            except Exception:
                return False
        req = urllib.request.Request(url, method="GET", headers={"Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return 200 <= getattr(resp, "status", 200) < 300
        except Exception:
            return False

    def _query_units(self, units, user=False):
        out = {key: "unknown" for key, _ in units}
        if not units:
            return out
        cmd = ["systemctl"]
        if user:
            cmd.append("--user")
        cmd.append("is-active")
        cmd.extend(unit for _, unit in units)
        try:
            r = self._run(cmd, timeout=2)
            lines = [ln.strip() for ln in (r.stdout or "").splitlines()]
        except Exception:
            return out
        for (key, _), val in zip(units, lines):
            if val:
                out[key] = val
        return out

    def _services_status(self):
        out = self._query_units(AGENTVIEW_SYSTEM_UNITS, user=False)
        out.update(self._query_units(AGENTVIEW_USER_UNITS, user=True))
        return out

    def _systemctl_web(self, action):
        try:
            self._run(["systemctl", "--user", action, AGENTVIEW_WEB_UNIT], timeout=10)
        except Exception:
            pass

    def status(self):
        with self.lock:
            state = self._read_capture_state()
        return {
            "enabled": state == "on",
            "state": state,
            "web_reachable": self._probe_web(timeout=1.0),
            "services": self._services_status(),
        }

    def toggle(self, enabled):
        if not isinstance(enabled, bool):
            return 400, {"error": "enabled must be true or false"}
        with self.lock:
            self._sync_rules_enabled(enabled)
            try:
                self._write_capture_state(enabled)
            except Exception as e:
                return 500, {"error": clip(f"failed to write capture.state: {e}", 240)}

            start = self._now()
            if enabled:
                deadline = start + self.start_timeout
                fallback_at = start + min(1.0, self.start_timeout / 2.0)
                tried_systemctl = False
                reachable = False
                while True:
                    reachable = self._probe_web(timeout=1.0)
                    if reachable:
                        break
                    now = self._now()
                    if not tried_systemctl and now >= fallback_at:
                        tried_systemctl = True
                        self._systemctl_web("start")
                        reachable = self._probe_web(timeout=1.0)
                        if reachable:
                            break
                        now = self._now()
                    if now >= deadline:
                        if not tried_systemctl:
                            tried_systemctl = True
                            self._systemctl_web("start")
                            reachable = self._probe_web(timeout=1.0)
                        break
                    self._sleep(min(self.poll_interval, max(0.0, deadline - now)))
                return 200, {
                    "enabled": True,
                    "state": "on",
                    "web_reachable": reachable,
                    "services": self._services_status(),
                }
            else:
                deadline = start + self.stop_timeout
                fallback_at = start + min(1.0, self.stop_timeout / 2.0)
                tried_systemctl = False
                reachable = True
                while True:
                    reachable = self._probe_web(timeout=1.0)
                    if not reachable:
                        break
                    now = self._now()
                    if not tried_systemctl and now >= fallback_at:
                        tried_systemctl = True
                        self._systemctl_web("stop")
                        reachable = self._probe_web(timeout=1.0)
                        if not reachable:
                            break
                        now = self._now()
                    if now >= deadline:
                        if not tried_systemctl:
                            tried_systemctl = True
                            self._systemctl_web("stop")
                            reachable = self._probe_web(timeout=1.0)
                        break
                    self._sleep(min(self.poll_interval, max(0.0, deadline - now)))
                return 200, {
                    "enabled": False,
                    "state": "off",
                    "web_reachable": reachable,
                    "services": self._services_status(),
                }


AGENTVIEW = AgentViewManager()


# ---------------------------------------------------------------- GNOME keyring unlock (/pc/api/keyring*)

KEYRING_UNLOCK_BIN = os.environ.get("PCAPI_KEYRING_UNLOCK", os.path.join(HOME, "remote-pc", "bin", "keyring-unlock"))
KEYRING_RDP_UNIT = "gnome-remote-desktop"
KEYRING_MAX_FAILURES = 5
KEYRING_WINDOW_SECS = 600


class KeyringManager:
    """Checks GNOME login keyring lock state over the session D-Bus and unlocks it via keyring-unlock --stdin.
    The password is never passed in argv/env, never stored on disk, and never logged or echoed."""

    def __init__(self, notify=None, unlock_bin=KEYRING_UNLOCK_BIN, run_fn=None, clock=time.monotonic,
                 max_failures=KEYRING_MAX_FAILURES, window_secs=KEYRING_WINDOW_SECS):
        self.notify = notify
        self.unlock_bin = unlock_bin
        self._run = run_fn or subprocess.run
        self._clock = clock
        self.max_failures = max_failures
        self.window_secs = window_secs
        self._lock = threading.Lock()
        self._failures = []

    def _env(self):
        env = dict(os.environ)
        env.setdefault("DBUS_SESSION_BUS_ADDRESS", f"unix:path=/run/user/{os.getuid()}/bus")
        return env

    def _is_locked(self):
        r = self._run(
            ["busctl", "--user", "get-property", "org.freedesktop.secrets",
             "/org/freedesktop/secrets/collection/login", "org.freedesktop.Secret.Collection", "Locked"],
            capture_output=True, text=True, timeout=5, env=self._env(),
        )
        if r.returncode != 0:
            raise RuntimeError("could not read keyring status")
        out = (r.stdout or "").strip()
        if out == "b true":
            return True
        if out == "b false":
            return False
        raise RuntimeError("unexpected keyring status")

    def _rdp_state(self):
        try:
            r = self._run(
                ["systemctl", "--user", "is-active", KEYRING_RDP_UNIT],
                capture_output=True, text=True, timeout=5, env=self._env(),
            )
            return (r.stdout or "").strip() or "unknown"
        except (OSError, subprocess.TimeoutExpired):
            return "unknown"

    def status(self):
        return {"locked": self._is_locked(), "rdp": self._rdp_state()}

    def _prune_failures_locked(self, now):
        cutoff = now - self.window_secs
        self._failures = [t for t in self._failures if t > cutoff]

    def unlock(self, password):
        if not isinstance(password, str) or not password or any(ch in password for ch in ("\n", "\r", "\x00")):
            return 400, {"ok": False, "error": "invalid password"}
        with self._lock:
            now = self._clock()
            self._prune_failures_locked(now)
            if len(self._failures) >= self.max_failures:
                retry_after = max(1, int(self.window_secs - (now - self._failures[0])) + 1)
                return 429, {"ok": False, "error": "Too many failed unlock attempts. Try again later.",
                             "retry_after": retry_after}
        try:
            r = self._run(
                [self.unlock_bin, "--stdin"],
                input=password + "\n",
                capture_output=True, text=True, timeout=30, env=self._env(),
            )
        except FileNotFoundError:
            return 503, {"ok": False, "error": "keyring-unlock helper is not installed"}
        except subprocess.TimeoutExpired:
            return 504, {"ok": False, "error": "keyring unlock timed out"}
        except OSError:
            return 502, {"ok": False, "error": "could not run keyring-unlock"}
        finally:
            password = ""

        if r.returncode != 0:
            with self._lock:
                now = self._clock()
                self._prune_failures_locked(now)
                self._failures.append(now)
            return 401, {"ok": False, "error": "Wrong keyring password"}

        with self._lock:
            self._failures.clear()

        try:
            self._run(
                ["systemctl", "--user", "restart", KEYRING_RDP_UNIT],
                capture_output=True, text=True, timeout=20, env=self._env(),
            )
        except (OSError, subprocess.TimeoutExpired):
            pass

        try:
            locked = self._is_locked()
        except Exception:
            locked = False
        return 200, {"ok": True, "locked": locked, "rdp": self._rdp_state()}

    def notify_if_locked_on_startup(self):
        try:
            st = self.status()
        except Exception:
            return False
        if st.get("locked") and self.notify is not None:
            self.notify.send_async("keyring", "Keyring locked", "Keyring locked after reboot: tap to unlock RDP")
            return True
        return False


KEYRING = KeyringManager()


# ---------------------------------------------------------------- GitHub Releases OTA/APK proxy (/pc/api/ota/*)


def _load_app_github_config():
    app_json = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app.json")
    cfg = load_json(app_json, {})
    gh = (cfg.get("expo") or {}).get("extra", {}).get("github") or {}
    return (
        gh.get("repo") or "ayushneekhar/pc-control",
        gh.get("otaTag") or "ota-android",
    )


_APP_GH_REPO, _APP_OTA_TAG = _load_app_github_config()
OTA_GITHUB_REPO = os.environ.get("PCAPI_GITHUB_REPO") or _APP_GH_REPO
OTA_GITHUB_TAG = os.environ.get("PCAPI_OTA_TAG") or _APP_OTA_TAG
OTA_RELEASES_BASE = os.environ.get("PCAPI_OTA_RELEASES_BASE") or ""
OTA_MANIFEST_TTL = float(os.environ.get("PCAPI_OTA_MANIFEST_TTL", "15"))

OTA_ALLOWED_FILES = {
    "ota.version.json": "application/json; charset=utf-8",
    "bundle.zip": "application/zip",
    "apk/version.json": "application/json; charset=utf-8",
    "apk/pc-control.apk": "application/vnd.android.package-archive",
}
OTA_PATCH_RE = re.compile(r"^patch-\d+-to-\d+\.zip$")
OTA_MANIFEST_FILES = frozenset({"ota.version.json", "apk/version.json"})


def ota_content_type(filename):
    if filename in OTA_ALLOWED_FILES:
        return OTA_ALLOWED_FILES[filename]
    if OTA_PATCH_RE.fullmatch(filename):
        return "application/zip"
    return None


def ota_upstream_url(filename, repo=None, ota_tag=None, releases_base=None):
    if not ota_content_type(filename):
        return None
    base = (releases_base if releases_base is not None else OTA_RELEASES_BASE).rstrip("/")
    if not base:
        r = repo or OTA_GITHUB_REPO
        base = f"https://github.com/{r}/releases"
    if filename == "apk/version.json":
        return f"{base}/latest/download/version.json"
    if filename == "apk/pc-control.apk":
        return f"{base}/latest/download/pc-control.apk"
    tag = ota_tag or OTA_GITHUB_TAG
    return f"{base}/download/{tag}/{filename}"


def _ota_default_open(url, timeout):
    req = urllib.request.Request(url, headers={"User-Agent": "pc-api/1", "Accept": "*/*"})
    return urllib.request.urlopen(req, timeout=timeout)


class OtaProxy:
    """Live GitHub Releases proxy for /pc/api/ota/* with short-TTL in-memory caching for small JSON manifests
    and chunked streaming (zero RAM buffering) for binary assets (bundle.zip, patch-*.zip, apk/pc-control.apk)."""

    def __init__(self, opener=None, ttl=None):
        self._opener = opener
        self._ttl = ttl
        self._lock = threading.Lock()
        self._manifest_cache = {}  # filename -> (monotonic_ts, bytes)

    def _open(self, url, timeout):
        fn = self._opener or _ota_default_open
        return fn(url, timeout)

    def _get_ttl(self):
        return OTA_MANIFEST_TTL if self._ttl is None else self._ttl

    def clear_cache(self):
        with self._lock:
            self._manifest_cache.clear()

    def fetch_manifest(self, filename, refresh=False):
        url = ota_upstream_url(filename)
        if not url:
            return 404, {"error": f"no OTA resource {filename}"}
        ttl = self._get_ttl()
        now = time.monotonic()
        if not refresh and ttl > 0:
            with self._lock:
                entry = self._manifest_cache.get(filename)
                if entry and (now - entry[0]) < ttl:
                    return 200, entry[1]
        try:
            with self._open(url, 15) as r:
                data = r.read(1024 * 1024)
        except urllib.error.HTTPError as e:
            code = 404 if e.code == 404 else 502
            return code, {"error": f"upstream GitHub returned HTTP {e.code} for {filename}"}
        except (OSError, ValueError) as e:
            return 502, {"error": clip(f"upstream GitHub fetch failed for {filename}: {type(e).__name__}: {e}", 240)}
        if ttl > 0:
            with self._lock:
                self._manifest_cache[filename] = (time.monotonic(), data)
        return 200, data

    def open_stream(self, filename):
        url = ota_upstream_url(filename)
        if not url:
            return 404, {"error": f"no OTA resource {filename}"}
        try:
            resp = self._open(url, 30)
            return 200, resp
        except urllib.error.HTTPError as e:
            code = 404 if e.code == 404 else 502
            return code, {"error": f"upstream GitHub returned HTTP {e.code} for {filename}"}
        except (OSError, ValueError) as e:
            return 502, {"error": clip(f"upstream GitHub fetch failed for {filename}: {type(e).__name__}: {e}", 240)}


OTA_PROXY = OtaProxy()

OTA_VERSION_RE = re.compile(r"^\d{1,9}$")
OTA_TRIGGER_FILE = os.environ.get(
    "PCAPI_OTA_TRIGGER_FILE",
    os.path.join(HOME, ".local", "state", "pc-control", "ota-trigger.json"),
)


class OtaTriggerStore:
    """Persists the latest agent-triggered OTA version and broadcasts live wakeups over SSE (/pc/api/events).

    We deliberately do NOT send a cat=chat FCM push for OTA triggers because PushService.kt only suppresses
    Android notifications when ChatScreen is in the foreground; SSE (/pc/api/events) wakes foreground JS
    immediately on every tab with zero notification tray noise, and GET /pc/api/ota/trigger + SSE hello
    catch up as soon as a backgrounded app returns to the foreground."""

    def __init__(self, path=None):
        self._path = path or OTA_TRIGGER_FILE
        self._lock = threading.Lock()
        self._state = self._load()

    def _load(self):
        d = load_json(self._path, None)
        if not isinstance(d, dict):
            return None
        v = str(d.get("version") or "").strip()
        if not OTA_VERSION_RE.fullmatch(v):
            return None
        return {
            "version": v,
            "notes": clip(d.get("notes") or "", 300),
            "triggered_at": int(d.get("triggered_at") or 0),
        }

    def get(self):
        with self._lock:
            return dict(self._state) if self._state else {"version": None}

    def record(self, version, notes=""):
        state = {
            "version": str(version).strip(),
            "notes": clip(notes or "", 300),
            "triggered_at": int(time.time()),
        }
        with self._lock:
            self._state = state
            parent = os.path.dirname(self._path)
            if parent:
                os.makedirs(parent, mode=0o700, exist_ok=True)
            tmp = self._path + ".tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            try:
                with os.fdopen(fd, "w") as f:
                    json.dump(state, f, indent=1)
                os.replace(tmp, self._path)
            except Exception:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                raise
            return dict(state)

    def trigger(self, body=None, ota_proxy=None, hub=None):
        b = body if isinstance(body, dict) else {}
        proxy = ota_proxy if ota_proxy is not None else OTA_PROXY
        raw_ver = b.get("version")
        raw_notes = b.get("notes")

        if raw_ver is not None and str(raw_ver).strip() != "":
            ver = str(raw_ver).strip()
            if not OTA_VERSION_RE.fullmatch(ver):
                return 400, {"error": "version must be a numeric OTA version"}
            notes = clip(raw_notes or "", 300)
            proxy.clear_cache()
        else:
            code, payload = proxy.fetch_manifest("ota.version.json", refresh=True)
            if code != 200:
                return code, payload
            try:
                manifest = json.loads(payload)
            except (ValueError, TypeError):
                return 502, {"error": "upstream ota.version.json is not valid JSON"}
            if not isinstance(manifest, dict):
                return 502, {"error": "upstream ota.version.json is not a JSON object"}
            mver = manifest.get("version")
            ver = str(mver).strip() if isinstance(mver, (int, str)) and not isinstance(mver, bool) else ""
            if not OTA_VERSION_RE.fullmatch(ver):
                return 502, {"error": "upstream ota.version.json has no valid version"}
            notes_src = raw_notes if raw_notes is not None and str(raw_notes).strip() else manifest.get("releaseNotes")
            notes = clip(notes_src or "", 300)

        state = self.record(ver, notes)
        target_hub = hub if hub is not None else HUB
        sse_clients = 0
        if target_hub is not None:
            with target_hub.lock:
                sse_clients = len(target_hub.clients)
            target_hub.publish("ota_trigger", state)
        return 200, {"ok": True, **state, "sse_clients": sse_clients}


OTA_TRIGGER = OtaTriggerStore()


# ---------------------------------------------------------------- health overview (/pc/api/health)

HEALTH_TIMEOUT = 2.0        # seconds, shared by all sources (they run concurrently)
JELLYFIN_URL = os.environ.get("PCAPI_JELLYFIN_URL", CONFIG.get("jellyfin_url") or "http://127.0.0.1:8096").rstrip("/")
JELLYFIN_KEY_FILE = os.environ.get("PCAPI_JELLYFIN_KEY_FILE", CONFIG.get("jellyfin_key_file")
                                   or os.path.join(HOME, "tools", "localagent", ".jellyfin-key"))


def watched_service(s):
    """A unit the Health tab and the down watcher care about. Oneshot and timer-driven units are normally
    inactive, and static/disabled units aren't expected to run, so none of those should ever raise an alert."""
    return s.get("enabled") == "enabled" and s.get("type") != "oneshot" and not s.get("timer")


def service_name(unit):
    return str(unit or "service").removesuffix(".service")


def health_services(timeout=HEALTH_TIMEOUT):
    return {"units": list_services(timeout=timeout)}


def health_downloads(timeout=HEALTH_TIMEOUT):
    r = DOWNLOADS.active(timeout=timeout)
    items = r.get("downloads") or []
    stalled = [d for d in items if d.get("state") == "stalled"]
    return {"qbt": r.get("qbt"), "total": r.get("total"), "items": items[:6],
            "stalled": len(stalled), "stalled_items": [d.get("name") for d in stalled[:3]]}


def _arr_needs_action(x):
    status = str(x.get("status") or "").lower()
    tracked = str(x.get("trackedDownloadStatus") or "").lower()
    state = str(x.get("trackedDownloadState") or "").lower()
    return status in ("warning", "failed") or tracked in ("warning", "error") or \
        state in ("importpending", "importblocked", "importfailed")


def _arr_record_title(app, x):
    if app == "radarr":
        m = x.get("movie") or {}
        return str(m.get("title") or x.get("title") or "Movie") + (f" ({m['year']})" if m.get("year") else "")
    s, e = x.get("series") or {}, x.get("episode") or {}
    title = str(s.get("title") or x.get("title") or "Episode")
    sn, en = e.get("seasonNumber"), e.get("episodeNumber")
    if isinstance(sn, int) and isinstance(en, int):
        title += f" S{sn:02d}E{en:02d}"
    return title


def _arr_reason(x):
    if x.get("errorMessage"): return clip(x["errorMessage"], 220)
    for m in x.get("statusMessages") or []:
        msgs = m.get("messages") if isinstance(m, dict) else None
        if msgs: return clip(msgs[0], 220)
    return str(x.get("trackedDownloadState") or x.get("status") or "needs attention").replace("_", " ")


def arr_queue(cat, timeout=HEALTH_TIMEOUT):
    """Radarr/Sonarr queue summary. The API key is read from their local config.xml and never returned/logged."""
    app, base, conf = ARR[cat]
    with open(conf) as f: key = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())[1]
    q = "includeMovie=true" if app == "radarr" else "includeSeries=true&includeEpisode=true"
    req = urllib.request.Request(f"{base}/api/v3/queue?pageSize=200&{q}", headers={"X-Api-Key": key})
    with urllib.request.urlopen(req, timeout=timeout) as r: recs = json.load(r).get("records") or []
    need = []
    for x in recs:
        if not isinstance(x, dict) or not _arr_needs_action(x): continue
        need.append({"title": _arr_record_title(app, x), "state": str(x.get("trackedDownloadState")
                    or x.get("status") or "attention").replace("_", " "), "reason": _arr_reason(x)})
    return {"queue": len(recs), "needs_action": len(need), "items": need[:5]}


def jellyfin_streams(timeout=HEALTH_TIMEOUT):
    """Now-playing sessions from Jellyfin's local API. Key from ~/tools/localagent/.jellyfin-key (never logged)."""
    with open(JELLYFIN_KEY_FILE) as f: key = f.read().strip()
    if not key: raise ValueError("no Jellyfin API key")
    req = urllib.request.Request(f"{JELLYFIN_URL}/Sessions",
                                 headers={"Authorization": f'MediaBrowser Token="{key}"'})
    with urllib.request.urlopen(req, timeout=timeout) as r: sessions = json.load(r)
    out = []
    for s in sessions if isinstance(sessions, list) else []:
        item, play = s.get("NowPlayingItem") or {}, s.get("PlayState") or {}
        if not isinstance(item, dict) or not item: continue
        method = str(play.get("PlayMethod") or ("Transcode" if s.get("TranscodingInfo") else "DirectPlay"))
        method = {"DirectPlay": "direct", "DirectStream": "direct_stream", "Transcode": "transcode"}.get(
            method, method.lower())
        series, sn, en = item.get("SeriesName") or "", item.get("ParentIndexNumber"), item.get("IndexNumber")
        subtitle = series + (f" S{sn:02d}E{en:02d}" if isinstance(sn, int) and isinstance(en, int) else "")
        run, pos = item.get("RunTimeTicks"), play.get("PositionTicks")
        progress = round(min(1.0, max(0.0, pos / run)), 4) if isinstance(run, int) and run > 0 and isinstance(pos, int) else None
        out.append({"user": str(s.get("UserName") or "Someone"),
                    "title": str(item.get("Name") or series or "Something"),
                    "subtitle": subtitle, "method": method, "paused": bool(play.get("IsPaused")),
                    "device": str(s.get("DeviceName") or s.get("Client") or ""), "progress": progress})
    return {"streams": out}


def _disk_kind(device, mount):
    base = re.sub(r"p?\d+$", "", os.path.basename(device or "")) or ""
    try:
        with open(f"/sys/block/{base}/queue/rotational") as f:
            return "HDD" if f.read().strip() == "1" else "SSD"
    except OSError:
        return "HDD" if re.search(r"/(media|mnt)/", mount or "") else "SSD"


def health_disks():
    """SSD/HDD usage. Uses psutil when available (same source as the Vitals page), with a / fallback."""
    disks, seen = [], set()
    try:
        import psutil
        for part in psutil.disk_partitions():
            if part.fstype in ("squashfs", "vfat") or part.device.startswith("/dev/loop") or part.mountpoint in seen:
                continue
            try: u = psutil.disk_usage(part.mountpoint)
            except OSError: continue
            seen.add(part.mountpoint)
            disks.append({"mount": part.mountpoint, "kind": _disk_kind(part.device, part.mountpoint),
                          "used": u.used, "total": u.total, "pct": round(u.percent, 1)})
    except ImportError:
        pass
    if not disks:
        import shutil
        u = shutil.disk_usage("/")
        disks.append({"mount": "/", "kind": "SSD", "used": u.used, "total": u.total,
                      "pct": round(u.used / u.total * 100, 1) if u.total else 0})
    return {"disks": disks}


def health_sources(timeout=HEALTH_TIMEOUT):
    return {
        "services": lambda: health_services(timeout),
        "downloads": lambda: health_downloads(timeout),
        "radarr": lambda: arr_queue("radarr", timeout),
        "sonarr": lambda: arr_queue("tv-sonarr", timeout),
        "jellyfin": lambda: jellyfin_streams(timeout),
        "disks": health_disks,
        "vpn": vpn_health,
        "agent": lambda: agent_health(timeout),
    }


def _health_gather(sources, timeout):
    """Run every source in its own daemon thread, then wait at most `timeout` for all of them together. A source
    that errors or is still running is reported as unavailable, never as a failed call."""
    out = {}

    def run(key, fn):
        try: out[key] = ("ok", fn())
        except Exception as e: out[key] = ("error", type(e).__name__)

    threads = []
    for key, fn in sources.items():
        t = threading.Thread(target=run, args=(key, fn), daemon=True, name=f"health-{key}")
        t.start()
        threads.append((key, t))
    deadline = time.monotonic() + max(0.05, timeout)
    for key, t in threads:
        t.join(max(0.0, deadline - time.monotonic()))
    return dict(out)


def _health_section(raw, key):
    if key not in raw: return {"available": False, "error": "timeout"}
    ok, value = raw[key]
    if ok != "ok": return {"available": False, "error": value}
    return {"available": True, **(value or {})}


def health_report(sources=None, timeout=HEALTH_TIMEOUT):
    raw = _health_gather(sources if sources is not None else health_sources(timeout), timeout)
    services = _health_section(raw, "services")
    downloads = _health_section(raw, "downloads")
    radarr = _health_section(raw, "radarr")
    sonarr = _health_section(raw, "sonarr")
    jellyfin = _health_section(raw, "jellyfin")
    disks = _health_section(raw, "disks")
    vpn = _health_section(raw, "vpn")
    agent = _health_section(raw, "agent")

    if services["available"]:
        counts = {"running": 0, "failed": 0, "inactive": 0, "other": 0}
        for s in services.get("units") or []:
            a = s.get("active")
            counts["running" if a == "active" else "failed" if a == "failed"
                   else "inactive" if a == "inactive" else "other"] += 1
        services["counts"] = counts

    problems = []

    def source_problem(label, section):
        if not section["available"]:
            problems.append({"kind": "source", "severity": "warn", "title": f"{label} unavailable",
                             "detail": section.get("error") or "no answer"})

    source_problem("Services", services)
    source_problem("Downloads", downloads)
    source_problem("Radarr", radarr)
    source_problem("Sonarr", sonarr)
    source_problem("Jellyfin", jellyfin)
    source_problem("Disks", disks)
    if "vpn" in raw:
        source_problem("VPN", vpn)
        if vpn["available"] and vpn.get("enabled") and not vpn.get("tunnel_up"):
            problems.append({"kind": "vpn", "severity": "warn", "title": "Torrent VPN is down",
                             "detail": vpn.get("last_error") or "qBittorrent is not routed through Proton"})
    if "agent" in raw and not agent["available"] and agent.get("error"):
        source_problem("Agent", agent)

    if services["available"]:
        for s in services.get("units") or []:
            if not watched_service(s) or s.get("active") == "active": continue
            a = s.get("active") or "inactive"
            problems.append({"kind": "service", "unit": s.get("unit"),
                             "severity": "bad" if a == "failed" else "warn",
                             "title": f"{service_name(s.get('unit'))} is {a}",
                             "detail": s.get("description") or s.get("sub") or ""})

    if downloads["available"]:
        if downloads.get("qbt") in ("down", "auth"):
            problems.append({"kind": "downloads", "severity": "warn", "title": "qBittorrent unavailable",
                             "detail": "sign-in failed" if downloads.get("qbt") == "auth" else "not answering"})
        if downloads.get("stalled"):
            n = downloads["stalled"]
            names = ", ".join(x for x in downloads.get("stalled_items") or [] if x)
            problems.append({"kind": "stalled", "severity": "warn",
                             "title": f"{n} stalled download{'s' if n != 1 else ''}",
                             "detail": names})

    for label, section in (("Radarr", radarr), ("Sonarr", sonarr)):
        if not section["available"] or not section.get("needs_action"): continue
        n = section["needs_action"]
        first = (section.get("items") or [{}])[0]
        detail = " · ".join(x for x in (first.get("title"), first.get("reason")) if x)
        problems.append({"kind": "arr", "severity": "bad" if n > 0 else "warn",
                         "title": f"{label}: {n} need{'s' if n == 1 else ''} manual action",
                         "detail": detail})

    if disks["available"]:
        for d in disks.get("disks") or []:
            total, used = d.get("total") or 0, d.get("used") or 0
            if total and (total - used) / total < 0.10:
                problems.append({"kind": "disk", "severity": "bad",
                                 "title": f"{d.get('kind') or 'Disk'} under 10% free",
                                 "detail": f"{d.get('mount')}: {human_size(total - used)} free of {human_size(total)}"})

    return {"ts": int(time.time()), "problems": problems, "services": services, "downloads": downloads,
            "radarr": radarr, "sonarr": sonarr, "jellyfin": jellyfin, "disks": disks, "vpn": vpn,
            "agent": agent}


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
    "/vitals": ("Vitals", "heart-pulse", "Laptop vitals API (app)", "api"),
    ":5055": ("Seerr", "movie-search-outline", "Requests", "page"),
    ":6767": ("Bazarr", "subtitles-outline", "Subtitles", "page"),
    ":7878": ("Radarr", "filmstrip", "Movies", "page"),
    ":8989": ("Sonarr", "television-classic", "TV shows", "page"),
    ":8096": ("Jellyfin", "play-box-multiple-outline", "Media server", "page"),
    ":8920": ("Jellyfin", "play-box-multiple-outline", "Media server", "page"),
    ":9696": ("Prowlarr", "radar", "Indexers", "page"),
    ":8080": ("qBittorrent", "download-outline", "Torrents", "page"),
}
# Real pages you just don't want as tiles (KNOWN keys). The tailscale serve routes stay up; this only hides the
# cards. routes.json {"<key>": {"hidden": false}} shows one again.
HIDDEN = {"1984", ":5055", ":6767", ":7878", ":8989"}  # Cameras (go2rtc), Seerr, Bazarr, Radarr, Sonarr
# Tiles for apps that aren't `tailscale serve` routes: key -> (name, icon, subtitle, android package, port).
# The app launches the package; without it (or on builds before 1.5.2) it opens https://<tailnet host>:<port>/.
APPS = {"jellyfin": ("Jellyfin", "play-box-multiple-outline", "Movies & TV (opens the app)", "org.jellyfin.mobile", 8920)}
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
        if not host: continue
        existing = next((r for r in out if r["port"] == port), None)
        if existing is not None:
            port_o = overrides.get(f":{port}") if isinstance(overrides.get(f":{port}"), dict) else {}
            merged_o = {**port_o, **o}
            existing["name"] = merged_o.get("name", name)
            existing["icon"] = merged_o.get("icon", icon)
            existing["subtitle"] = merged_o.get("subtitle", sub)
            existing["kind"] = merged_o.get("kind", "app")
            existing["app"] = merged_o.get("app", pkg)
            if "url" in merged_o:
                existing["url"] = merged_o["url"]
            if "hidden" in merged_o:
                existing["hidden"] = bool(merged_o["hidden"])
            continue
        default_url = f"https://{host}:{port}/"
        if key == "jellyfin":
            pub = str(CONFIG.get("jellyfin_public_url") or "").strip()
            if pub:
                default_url = pub if pub.endswith("/") else pub + "/"
        out.append({"id": key, "name": o.get("name", name), "icon": o.get("icon", icon),
                    "subtitle": o.get("subtitle", sub), "url": o.get("url", default_url), "path": "/",
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


def list_services(timeout=15):
    units = own_units()
    if not units: return []
    p = subprocess.run(["systemctl", "--user", "show", "--no-pager", "-p", ",".join(PROPS), *units],
                       capture_output=True, text=True, timeout=timeout)
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


PROTECTED_STOP_UNITS = {"pc-api.service"}


def service_action(unit, action):
    if action not in ("start", "stop", "restart"):
        return 400, {"error": "action must be start, stop, or restart"}
    if unit not in own_units():
        return 404, {"error": "not one of your user services"}
    if action == "stop" and unit in PROTECTED_STOP_UNITS:
        return 403, {"error": f"cannot stop {unit}"}
    p = subprocess.run(["systemctl", "--user", action, "--no-block", unit], capture_output=True, text=True,
                       timeout=15)
    if p.returncode:
        return 500, {"error": p.stderr.strip() or f"{action} failed"}
    return 200, {"ok": True}


def restart_service(unit):
    return service_action(unit, "restart")


def service_logs(unit, n):
    if unit not in own_units(): return 404, {"error": "not one of your user services"}
    p = subprocess.run(["journalctl", "--user", "-u", unit, "-n", str(n), "--no-pager", "-o", "short-iso"],
                       capture_output=True, text=True, timeout=15)
    return 200, {"lines": p.stdout.splitlines()[-n:]}


class ServiceWatcher:
    """Watch enabled long-running user units. After two consecutive bad checks it sends one `service_down`
    notification through the usual router, and one recovery notification when the unit is active again. Oneshot,
    timer-driven, static/disabled units are ignored, and a unit must have been seen active first."""

    def __init__(self, notify, list_fn=None, interval=60, checks=2):
        self.notify = notify
        self.list_fn = list_fn or (lambda: list_services(timeout=15))
        self.interval, self.checks = interval, checks
        self.state = {}
        self.lock = threading.Lock()
        self.stop = threading.Event()

    def start(self):
        threading.Thread(target=self.loop, daemon=True, name="service-watcher").start()
        return self

    def loop(self):
        while not self.stop.is_set():
            try: self.check()
            except Exception as e:
                print(f"service-watcher: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
            self.stop.wait(self.interval)

    def _send(self, title, body):
        fn = getattr(self.notify, "send_async", None) or getattr(self.notify, "send", None)
        if fn: fn("service_down", title, body)

    def check(self, services=None):
        services = self.list_fn() if services is None else services
        with self.lock:
            for s in services:
                if not watched_service(s): continue
                unit = s.get("unit")
                if not unit: continue
                st = self.state.setdefault(unit, {"seen_active": False, "bad": 0, "alerted": False})
                name = service_name(unit)
                if s.get("active") == "active":
                    st["seen_active"], st["bad"] = True, 0
                    if st["alerted"]:
                        st["alerted"] = False
                        self._send(f"{name} recovered", s.get("description") or "It is running again.")
                    continue
                if not st["seen_active"]: continue
                st["bad"] += 1
                if st["bad"] >= self.checks and not st["alerted"]:
                    st["alerted"] = True
                    self._send(f"{name} is {s.get('active') or 'inactive'}",
                               s.get("description") or "The unit is not running.")


# ---------------------------------------------------------------- APK

def apk_info():
    meta = load_json(os.path.join(DATA_DIR, "app.json"), None)
    if not meta or not os.path.exists(os.path.join(DATA_DIR, "app.apk")): return None
    return meta


# ---------------------------------------------------------------- round 4 features

QBT_ADD_CATEGORY = "qwen"
SEERR_URL = os.environ.get("PCAPI_SEERR_URL", CONFIG.get("seerr_url_local") or "http://127.0.0.1:5055").rstrip("/")
SEERR_SETTINGS = os.environ.get("PCAPI_SEERR_SETTINGS", os.path.join(HOME, ".config", "seerr", "settings.json"))
KBD_LOCK_UNIT = "kbd-lock.service"

ALL_TORRENT_STATE = {
    **DL_STATE,
    "stoppedDL": "paused", "pausedDL": "paused",
    "stoppedUP": "stopped", "pausedUP": "stopped",
    "uploading": "seeding", "forcedUP": "seeding", "stalledUP": "seeding",
    "queuedUP": "seeding", "checkingUP": "checking",
    "error": "error", "missingFiles": "error",
}
PAUSED_RAW_STATES = {"stoppedDL", "pausedDL", "stoppedUP", "pausedUP"}
LETTERBOXD_HOSTS = {"letterboxd.com", "www.letterboxd.com", "boxd.it"}


def _qbt_invalidate_cache():
    if DOWNLOADS is not None:
        with DOWNLOADS.lock:
            DOWNLOADS.cache = (0.0, None)


def downloads_all(dl=None, timeout=8):
    """All qBittorrent torrents (active, paused, seeding, stopped, error) + alt-speed + Arr queues."""
    dl = DOWNLOADS if dl is None else dl
    alt_speed = False
    try:
        items = dl.qbt("torrents/info", timeout=timeout, filter="all", sort="added_on")
        st = "ok"
        try:
            mode = dl.qbt("transfer/speedLimitsMode", get=True, timeout=timeout)
            alt_speed = str(mode).strip() == "1"
        except (OSError, ValueError, PermissionError):
            alt_speed = False
    except PermissionError:
        items, st = [], "auth"
    except (OSError, ValueError):
        items, st = [], "down"
    items = [t for t in (items if isinstance(items, list) else []) if isinstance(t, dict)]
    missing = {t.get("category") for t in items if t.get("category") in dl.arr
               and str(t.get("hash", "")).upper() not in dl.titles}
    for cat in missing:
        if time.time() - dl.arr_at.get(cat, 0) < 30:
            continue
        dl.arr_at[cat] = time.time()
        for h, title in dl.arr_lookup(cat, timeout=timeout).items():
            if h not in dl.titles:
                dl.remember(h, title)
    out = []
    for t in items:
        h = str(t.get("hash", "")).upper()
        size, done = int(t.get("size") or 0), int(t.get("completed") or 0)
        eta = int(t.get("eta") or 0)
        raw_state = str(t.get("state") or "")
        state = ALL_TORRENT_STATE.get(raw_state, raw_state or "unknown")
        if state == "downloading" and int(t.get("dlspeed") or 0) == 0:
            state = "stalled"
        out.append({
            "hash": h.lower(),
            "id": h[:16].lower(),
            "name": dl.titles.get(h) or release_title(t.get("name")),
            "torrent": clip(t.get("name"), 200),
            "category": str(t.get("category") or ""),
            "state": state,
            "raw_state": raw_state,
            "paused": raw_state in PAUSED_RAW_STATES,
            "progress": round(min(1.0, max(0.0, float(t.get("progress") or 0))), 4),
            "speed": int(t.get("dlspeed") or 0),
            "upspeed": int(t.get("upspeed") or 0),
            "eta": eta if state in ("downloading", "stalled", "metadata") and 0 < eta < 8640000 else None,
            "size": size,
            "done": min(done, size) if size else done,
            "seeds": int(t.get("num_seeds") or 0),
            "ratio": round(float(t.get("ratio") or 0), 2),
            "added": int(t.get("added_on") or 0),
        })
    rank = {"downloading": 0, "stalled": 1, "metadata": 2, "checking": 3, "moving": 3,
            "queued": 4, "paused": 5, "error": 6, "seeding": 7, "stopped": 8}
    out.sort(key=lambda d: (rank.get(d["state"], 9), -d["added"]))
    active = [d for d in out if d["raw_state"] in DL_STATE]
    sized = [d for d in active if d["size"] > 0]
    etas = [d["eta"] for d in active if d["eta"]]
    total = {
        "count": len(out),
        "active": len(active),
        "downloading": sum(d["state"] == "downloading" for d in active),
        "progress": round(sum(d["done"] for d in sized) / sum(d["size"] for d in sized), 4) if sized else None,
        "speed": sum(d["speed"] for d in out),
        "upspeed": sum(d["upspeed"] for d in out),
        "eta": max(etas) if etas else None,
    }
    arr_res = {}
    for key, cat in (("radarr", "radarr"), ("sonarr", "tv-sonarr")):
        try:
            arr_res[key] = {"available": True, **arr_queue(cat, timeout=3)}
        except Exception as e:
            arr_res[key] = {"available": False, "error": type(e).__name__}
    return {"qbt": st, "alt_speed": alt_speed, "torrents": out, "total": total,
            "radarr": arr_res["radarr"], "sonarr": arr_res["sonarr"], "ts": int(time.time())}


def downloads_action(action, body, dl=None):
    dl = DOWNLOADS if dl is None else dl
    h = str(body.get("hash") or "").strip()
    if not HASH_RE.match(h):
        return 400, {"error": "invalid torrent hash"}
    try:
        if action == "pause":
            dl.qbt("torrents/stop", hashes=h.lower())
        elif action == "resume":
            dl.qbt("torrents/start", hashes=h.lower())
        elif action == "delete":
            del_files = "true" if bool(body.get("delete_files")) else "false"
            dl.qbt("torrents/delete", hashes=h.lower(), deleteFiles=del_files)
        else:
            return 400, {"error": "unknown action"}
    except (OSError, ValueError, PermissionError) as e:
        return 502, {"error": clip(f"qBittorrent {action} failed: {e}", 240)}
    _qbt_invalidate_cache()
    return 200, {"ok": True}


def downloads_set_alt_speed(body, dl=None):
    dl = DOWNLOADS if dl is None else dl
    enabled = body.get("enabled")
    if not isinstance(enabled, bool):
        return 400, {"error": "enabled must be true or false"}
    try:
        cur = str(dl.qbt("transfer/speedLimitsMode", get=True)).strip() == "1"
        if cur != enabled:
            dl.qbt("transfer/toggleSpeedLimitsMode")
        after = str(dl.qbt("transfer/speedLimitsMode", get=True)).strip() == "1"
    except (OSError, ValueError, PermissionError) as e:
        return 502, {"error": clip(f"qBittorrent alt-speed failed: {e}", 240)}
    return 200, {"ok": True, "alt_speed": after}


def downloads_add_magnet(body, dl=None, notify=None):
    dl = DOWNLOADS if dl is None else dl
    notify = NOTIFY if notify is None else notify
    uri = str(body.get("uri") or "").strip()
    if not uri.lower().startswith("magnet:?") or len(uri) > 4096:
        return 400, {"error": "uri must be a magnet: link"}
    already = False
    try:
        dl.qbt("torrents/add", urls=uri, category=QBT_ADD_CATEGORY)
    except urllib.error.HTTPError as e:
        if e.code == 409:
            already = True
        else:
            return 502, {"error": clip(f"qBittorrent add failed: HTTP {e.code}", 240)}
    except (OSError, ValueError, PermissionError) as e:
        return 502, {"error": clip(f"qBittorrent add failed: {e}", 240)}
    _qbt_invalidate_cache()
    if notify is not None and not already:
        try:
            notify.live_kick("app")
        except Exception:
            pass
    return 200, {"ok": True, "already_exists": already, "category": QBT_ADD_CATEGORY}


def arr_retry_import(body):
    app = str(body.get("app") or "").strip().lower()
    cat = {"radarr": "radarr", "sonarr": "tv-sonarr"}.get(app)
    if not cat or cat not in ARR:
        return 400, {"error": "app must be radarr or sonarr"}
    _, base, conf = ARR[cat]
    try:
        with open(conf) as f:
            key = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())[1]
        data = json.dumps({"name": "RefreshMonitoredDownloads"}).encode()
        req = urllib.request.Request(
            f"{base}/api/v3/command", data=data, method="POST",
            headers={"X-Api-Key": key, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=10) as r:
            res = json.loads(r.read() or b"{}")
    except (OSError, ValueError, TypeError, AttributeError) as e:
        return 502, {"error": clip(f"{app} retry failed: {e}", 240)}
    return 200, {"ok": True, "app": app, "command_id": res.get("id")}


def _jellyfin_api_key():
    with open(JELLYFIN_KEY_FILE) as f:
        key = f.read().strip()
    if not key:
        raise ValueError("no Jellyfin API key")
    return key


def jellyfin_refresh_library():
    try:
        key = _jellyfin_api_key()
        req = urllib.request.Request(
            f"{JELLYFIN_URL}/Library/Refresh", data=b"", method="POST",
            headers={"Authorization": f'MediaBrowser Token="{key}"'},
        )
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
    except (OSError, ValueError) as e:
        return 502, {"error": clip(f"Jellyfin refresh failed: {e}", 240)}
    return 200, {"ok": True}


JELLYFIN_ITEM_ID_RE = re.compile(r"^[0-9a-fA-F]{32}$")


def _jellyfin_extract_item_id(media_url):
    """Extract a 32-hex Jellyfin item ID from a mediaUrl's query/fragment `id` param or path segment."""
    if not isinstance(media_url, str) or not media_url.strip():
        return None
    parts = urllib.parse.urlsplit(media_url.strip())
    # Check `id` query parameter in both URL query and hash-router fragment (e.g. #/details?id=...)
    frag_path, _, frag_query = parts.fragment.partition("?")
    for qs in (parts.query, frag_query):
        if not qs:
            continue
        for val in urllib.parse.parse_qs(qs).get("id", ()):
            if JELLYFIN_ITEM_ID_RE.match(val):
                return val
    # Fallback: check path segments in URL path and fragment path
    for raw_path in (parts.path, frag_path):
        for seg in raw_path.split("/"):
            if JELLYFIN_ITEM_ID_RE.match(seg):
                return seg
    return None


def _jellyfin_lookup_item_by_tmdb(key, tmdb_id, media_type):
    include_type = "Series" if media_type == "tv" else "Movie"
    qs = urllib.parse.urlencode({
        "Recursive": "true",
        "IncludeItemTypes": include_type,
        "AnyProviderIdEquals": f"tmdb.{tmdb_id}",
        "Fields": "ProviderIds",
    })
    req = urllib.request.Request(
        f"{JELLYFIN_URL}/Items?{qs}",
        headers={"Authorization": f'MediaBrowser Token="{key}"'},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        data = json.loads(r.read() or b"{}")
    items = data.get("Items") if isinstance(data, dict) else None
    matched = watch_mod._match_item_by_tmdb(items, tmdb_id)
    if matched:
        return str(matched["Id"])
    return None


JELLYFIN_TICKS_PER_SEC = 10_000_000
JELLYFIN_IMAGE_TYPES = {
    "primary": "Primary",
    "backdrop": "Backdrop",
    "thumb": "Thumb",
    "logo": "Logo",
    "chapter": "Chapter",
}
JELLYFIN_PLAYSTATE_COMMANDS = {
    c.lower(): c for c in (
        "PlayPause", "Pause", "Unpause", "Stop",
        "NextTrack", "PreviousTrack", "Rewind", "FastForward", "Seek",
    )
}
JELLYFIN_GENERAL_COMMANDS = {
    c.lower(): c for c in (
        "SetAudioStreamIndex", "SetSubtitleStreamIndex",
        "VolumeUp", "VolumeDown", "ToggleMute", "SetVolume",
    )
}


def _jellyfin_resolve_target_device(device_name=None):
    explicit_device = isinstance(device_name, str) and bool(device_name.strip())
    target_device = (
        device_name.strip() if explicit_device
        else (str(CONFIG.get("jellyfin_tv_device") or "").strip() or "Lumio Arc 7")
    )
    return target_device, explicit_device


def _jellyfin_get_sessions(key, timeout=10):
    req = urllib.request.Request(
        f"{JELLYFIN_URL}/Sessions",
        headers={"Authorization": f'MediaBrowser Token="{key}"'},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read() or b"[]")
    return data if isinstance(data, list) else []


JELLYFIN_TV_SUBSTRINGS = (
    "android tv", "androidtv", "fire tv", "firetv", "apple tv", "appletv",
    "webos", "tizen", "roku", "shield", "projector", "chromecast",
    "google tv", "googletv", "bravia", "lumio", "kodi", "xbox", "playstation",
)
JELLYFIN_TV_WORD_RE = re.compile(r"\btv\b", re.IGNORECASE)


def _jellyfin_is_tv_session(session):
    if not isinstance(session, dict):
        return False
    fields = " ".join(
        str(session.get(k) or "")
        for k in ("Client", "DeviceName", "DeviceType")
    ).strip()
    if not fields:
        return False
    low = fields.lower()
    if any(kw in low for kw in JELLYFIN_TV_SUBSTRINGS):
        return True
    return bool(JELLYFIN_TV_WORD_RE.search(fields))


def _jellyfin_pick_best_session(matches):
    if not matches:
        return None
    for s in matches:
        if isinstance(s.get("NowPlayingItem"), dict):
            return s
    return matches[0]


def _jellyfin_find_tv_session(sessions, device_name=None, explicit_device=False, session_id=None):
    controllable = [
        s for s in (sessions if isinstance(sessions, list) else [])
        if isinstance(s, dict) and s.get("SupportsRemoteControl") is True and s.get("Id")
    ]
    # 1. Exact sessionId match (if provided and still online)
    sid = str(session_id or "").strip()
    if sid:
        for s in controllable:
            if str(s.get("Id") or "") == sid:
                return s
        # If sessionId was explicitly requested without a fallback deviceName, still allow
        # explicit deviceName if provided; otherwise if neither matches, fall through below.

    needle = str(device_name or "").strip().lower()
    if needle:
        # 2. Exact DeviceName match (case-insensitive)
        exact = [s for s in controllable if str(s.get("DeviceName") or "").strip().lower() == needle]
        if exact:
            return _jellyfin_pick_best_session(exact)
        # 3. Substring DeviceName match (case-insensitive)
        substr = [s for s in controllable if needle in str(s.get("DeviceName") or "").lower()]
        if substr:
            return _jellyfin_pick_best_session(substr)

    # 4. Dynamic multi-TV fallback when deviceName was not explicitly overridden
    if not explicit_device:
        tv_matches = [s for s in controllable if _jellyfin_is_tv_session(s)]
        if tv_matches:
            return _jellyfin_pick_best_session(tv_matches)
    return None


def _jellyfin_tv_offline_response(target_device=None, explicit_device=False):
    if explicit_device and target_device:
        msg = f"{target_device} isn't open in Jellyfin right now. Open Jellyfin on {target_device} and try again."
    else:
        msg = "No TVs are open in Jellyfin right now. Open Jellyfin on any TV and try again."
    return 409, {
        "ok": False,
        "code": "tv_offline",
        "error": msg,
    }


def jellyfin_tv_devices():
    """Return all active remote-controllable Jellyfin sessions, sorted TVs first."""
    default_device, _ = _jellyfin_resolve_target_device(None)
    default_low = default_device.lower()
    try:
        key = _jellyfin_api_key()
        sessions = _jellyfin_get_sessions(key, timeout=10)
    except (OSError, ValueError) as e:
        return 502, {"ok": False, "error": clip(f"Jellyfin TV devices lookup failed: {e}", 240)}

    devices = []
    for s in sessions if isinstance(sessions, list) else []:
        if not isinstance(s, dict):
            continue
        sid = str(s.get("Id") or "").strip()
        if not sid or s.get("SupportsRemoteControl") is not True:
            continue
        dev_name = str(s.get("DeviceName") or s.get("Client") or "Jellyfin Device").strip()
        client = str(s.get("Client") or "").strip() or None
        user_name = str(s.get("UserName") or "").strip() or None
        now_item = s.get("NowPlayingItem") if isinstance(s.get("NowPlayingItem"), dict) else None
        is_playing = now_item is not None
        now_title = None
        if is_playing:
            series_name = str(now_item.get("SeriesName") or "").strip()
            item_name = str(now_item.get("Name") or "").strip()
            now_title = f"{series_name} · {item_name}" if (series_name and item_name) else (series_name or item_name or None)
        devices.append({
            "sessionId": sid,
            "deviceName": dev_name,
            "client": client,
            "userName": user_name,
            "isTv": _jellyfin_is_tv_session(s),
            "isPlaying": is_playing,
            "nowPlayingTitle": clip(now_title, 120) if now_title else None,
        })

    def _sort_key(d):
        dname = d["deviceName"].lower()
        is_default = (dname == default_low) or (bool(default_low) and default_low in dname)
        return (
            0 if d["isTv"] else 1,
            0 if d["isPlaying"] else 1,
            0 if is_default else 1,
            dname,
        )

    devices.sort(key=_sort_key)
    return 200, {
        "ok": True,
        "devices": devices,
        "defaultDevice": default_device,
    }


def _jellyfin_safe_int(val):
    if val is None or isinstance(val, bool):
        return None
    try:
        return int(val)
    except (TypeError, ValueError):
        return None


def _jellyfin_safe_float(val):
    if val is None or isinstance(val, bool):
        return None
    try:
        f = float(val)
        return f if math.isfinite(f) else None
    except (TypeError, ValueError):
        return None


def _jellyfin_image_proxy_url(item_id, image_type="Primary", series_id=None):
    if not isinstance(item_id, str) or not JELLYFIN_ITEM_ID_RE.match(item_id):
        return None
    params = {"type": image_type}
    if isinstance(series_id, str) and JELLYFIN_ITEM_ID_RE.match(series_id) and series_id != item_id:
        params["seriesId"] = series_id
    return f"/pc/api/jellyfin/image/{item_id}?{urllib.parse.urlencode(params)}"


def jellyfin_tv_session(device_name=None, session_id=None):
    """Return structured playback state for the active controllable Jellyfin TV session."""
    explicit_sid = str(session_id or "").strip() or None
    target_device, explicit_device = _jellyfin_resolve_target_device(device_name)
    try:
        key = _jellyfin_api_key()
        sessions = _jellyfin_get_sessions(key, timeout=10)
    except (OSError, ValueError) as e:
        return 502, {"ok": False, "error": clip(f"Jellyfin TV session lookup failed: {e}", 240)}

    session = _jellyfin_find_tv_session(
        sessions,
        target_device,
        explicit_device=explicit_device,
        session_id=explicit_sid,
    )
    if not session:
        return 200, {
            "connected": False,
            "sessionId": None,
            "deviceName": target_device,
            "client": None,
            "appVersion": None,
            "isPlaying": False,
            "isPaused": False,
            "isMuted": False,
            "volumeLevel": None,
            "positionTicks": 0,
            "runTimeTicks": 0,
            "positionSeconds": 0.0,
            "durationSeconds": 0.0,
            "canSeek": False,
            "audioStreamIndex": None,
            "subtitleStreamIndex": None,
            "nowPlaying": None,
            "audioTracks": [],
            "subtitleTracks": [],
            "segments": [],
            "supportedCommands": [],
        }

    play_state = session.get("PlayState") if isinstance(session.get("PlayState"), dict) else {}
    now_item = session.get("NowPlayingItem") if isinstance(session.get("NowPlayingItem"), dict) else None
    is_playing = now_item is not None

    pos_ticks = max(0, _jellyfin_safe_int(play_state.get("PositionTicks")) or 0) if is_playing else 0
    runtime_ticks = max(0, _jellyfin_safe_int(now_item.get("RunTimeTicks")) or 0) if is_playing else 0
    vol_raw = _jellyfin_safe_int(play_state.get("VolumeLevel"))
    volume_level = max(0, min(100, vol_raw)) if vol_raw is not None else None

    if "CanSeek" in play_state:
        can_seek = bool(play_state.get("CanSeek")) and is_playing
    else:
        can_seek = is_playing and runtime_ticks > 0

    now_playing = None
    audio_tracks = []
    subtitle_tracks = []
    segments = []
    if is_playing:
        item_id = str(now_item.get("Id") or "").strip()
        if item_id and JELLYFIN_ITEM_ID_RE.match(item_id):
            try:
                sess_uid = str(session.get("UserId") or "").strip() or None
                segments = _watch_service().get_media_segments(item_id, uid=sess_uid, use_cache=True)
            except Exception:
                segments = []
        item_type = str(now_item.get("Type") or "").strip()
        series_id_raw = str(now_item.get("SeriesId") or "").strip()
        series_id = series_id_raw if JELLYFIN_ITEM_ID_RE.match(series_id_raw) else None
        parent_backdrop_raw = str(now_item.get("ParentBackdropItemId") or "").strip()
        parent_backdrop_id = parent_backdrop_raw if JELLYFIN_ITEM_ID_RE.match(parent_backdrop_raw) else None

        poster_url = _jellyfin_image_proxy_url(item_id, "Primary", series_id=series_id)
        if not poster_url and series_id:
            poster_url = _jellyfin_image_proxy_url(series_id, "Primary")

        has_own_backdrop = bool(now_item.get("BackdropImageTags"))
        backdrop_target = item_id if has_own_backdrop else (parent_backdrop_id or series_id or item_id)
        backdrop_url = _jellyfin_image_proxy_url(backdrop_target, "Backdrop", series_id=series_id)

        now_playing = {
            "itemId": item_id,
            "name": str(now_item.get("Name") or ""),
            "type": item_type,
            "seriesName": (str(now_item["SeriesName"]) if now_item.get("SeriesName") else None),
            "seasonName": (str(now_item["SeasonName"]) if now_item.get("SeasonName") else None),
            "seasonNumber": _jellyfin_safe_int(now_item.get("ParentIndexNumber")),
            "episodeNumber": _jellyfin_safe_int(now_item.get("IndexNumber")),
            "year": _jellyfin_safe_int(now_item.get("ProductionYear")),
            "posterUrl": poster_url,
            "backdropUrl": backdrop_url,
        }

        for stream in (now_item.get("MediaStreams") or []):
            if not isinstance(stream, dict):
                continue
            s_type = str(stream.get("Type") or "")
            s_idx = _jellyfin_safe_int(stream.get("Index"))
            if s_idx is None:
                continue
            title = str(
                stream.get("DisplayTitle")
                or stream.get("Title")
                or stream.get("Language")
                or f"{s_type} {s_idx}"
            ).strip()
            if s_type == "Audio":
                audio_tracks.append({
                    "index": s_idx,
                    "displayTitle": title,
                    "language": (str(stream["Language"]) if stream.get("Language") else None),
                    "codec": (str(stream["Codec"]) if stream.get("Codec") else None),
                    "channels": _jellyfin_safe_int(stream.get("Channels")),
                    "isDefault": bool(stream.get("IsDefault")),
                })
            elif s_type == "Subtitle":
                subtitle_tracks.append({
                    "index": s_idx,
                    "displayTitle": title,
                    "language": (str(stream["Language"]) if stream.get("Language") else None),
                    "codec": (str(stream["Codec"]) if stream.get("Codec") else None),
                    "isExternal": bool(stream.get("IsExternal")),
                    "isDefault": bool(stream.get("IsDefault")),
                })

    supported = [
        str(c) for c in (session.get("SupportedCommands") or [])
        if isinstance(c, str) and c.strip()
    ]

    return 200, {
        "connected": True,
        "sessionId": str(session["Id"]),
        "deviceName": str(session.get("DeviceName") or session.get("Client") or target_device),
        "client": (str(session["Client"]) if session.get("Client") else None),
        "appVersion": (str(session["ApplicationVersion"]) if session.get("ApplicationVersion") else None),
        "isPlaying": is_playing,
        "isPaused": bool(play_state.get("IsPaused")) if is_playing else False,
        "isMuted": bool(play_state.get("IsMuted")),
        "volumeLevel": volume_level,
        "positionTicks": pos_ticks,
        "runTimeTicks": runtime_ticks,
        "positionSeconds": round(pos_ticks / JELLYFIN_TICKS_PER_SEC, 3),
        "durationSeconds": round(runtime_ticks / JELLYFIN_TICKS_PER_SEC, 3),
        "canSeek": can_seek,
        "audioStreamIndex": _jellyfin_safe_int(play_state.get("AudioStreamIndex")) if is_playing else None,
        "subtitleStreamIndex": _jellyfin_safe_int(play_state.get("SubtitleStreamIndex")) if is_playing else None,
        "nowPlaying": now_playing,
        "audioTracks": audio_tracks,
        "subtitleTracks": subtitle_tracks,
        "segments": segments,
        "supportedCommands": supported,
    }


def jellyfin_tv_command(body):
    """Send a playstate, seek, track-switch, or volume command to the active Jellyfin TV session."""
    raw_cmd = str(body.get("command") or "").strip()
    if not raw_cmd:
        return 400, {"ok": False, "error": "command is required"}
    cmd_key = raw_cmd.lower()

    is_seek_relative = cmd_key == "seekrelative"
    playstate_cmd = JELLYFIN_PLAYSTATE_COMMANDS.get(cmd_key)
    general_cmd = JELLYFIN_GENERAL_COMMANDS.get(cmd_key)
    if not (is_seek_relative or playstate_cmd or general_cmd):
        return 400, {"ok": False, "error": f"Unsupported TV command: {raw_cmd}"}

    # Validate command-specific parameters before hitting Jellyfin
    seek_ticks = None
    delta_seconds = None
    cmd_args = {}
    if playstate_cmd == "Seek":
        raw_ticks = _jellyfin_safe_int(body.get("seekPositionTicks"))
        raw_secs = _jellyfin_safe_float(body.get("positionSeconds"))
        if raw_ticks is not None and raw_ticks >= 0:
            seek_ticks = raw_ticks
        elif raw_secs is not None and raw_secs >= 0:
            seek_ticks = int(round(raw_secs * JELLYFIN_TICKS_PER_SEC))
        else:
            return 400, {"ok": False, "error": "Seek requires non-negative seekPositionTicks or positionSeconds"}
    elif is_seek_relative:
        delta_seconds = _jellyfin_safe_float(body.get("deltaSeconds"))
        if delta_seconds is None:
            return 400, {"ok": False, "error": "SeekRelative requires numeric deltaSeconds"}
    elif general_cmd == "SetAudioStreamIndex":
        idx = _jellyfin_safe_int(body.get("index"))
        if idx is None or idx < 0:
            return 400, {"ok": False, "error": "SetAudioStreamIndex requires non-negative integer index"}
        cmd_args = {"Index": str(idx)}
    elif general_cmd == "SetSubtitleStreamIndex":
        idx = _jellyfin_safe_int(body.get("index"))
        if idx is None or idx < -1:
            return 400, {"ok": False, "error": "SetSubtitleStreamIndex requires integer index >= -1"}
        cmd_args = {"Index": str(idx)}
    elif general_cmd == "SetVolume":
        vol = _jellyfin_safe_float(body.get("volume"))
        if vol is None:
            return 400, {"ok": False, "error": "SetVolume requires numeric volume (0..100)"}
        cmd_args = {"Volume": str(max(0, min(100, int(round(vol)))))}

    explicit_sid = str(body.get("sessionId") or "").strip() or None
    target_device, explicit_device = _jellyfin_resolve_target_device(body.get("deviceName"))

    try:
        key = _jellyfin_api_key()
        session = None
        if explicit_sid and not is_seek_relative:
            session_id = explicit_sid
        else:
            sessions = _jellyfin_get_sessions(key, timeout=10)
            session = _jellyfin_find_tv_session(
                sessions,
                target_device,
                explicit_device=explicit_device,
                session_id=explicit_sid,
            )
            if not session:
                return _jellyfin_tv_offline_response(target_device, explicit_device=explicit_device)
            session_id = str(session["Id"])

        sid_quoted = urllib.parse.quote(session_id, safe="")
        headers = {"Authorization": f'MediaBrowser Token="{key}"'}

        if is_seek_relative:
            play_state = session.get("PlayState") if isinstance(session.get("PlayState"), dict) else {}
            now_item = session.get("NowPlayingItem") if isinstance(session.get("NowPlayingItem"), dict) else {}
            cur_ticks = max(0, _jellyfin_safe_int(play_state.get("PositionTicks")) or 0)
            runtime_ticks = max(0, _jellyfin_safe_int(now_item.get("RunTimeTicks")) or 0)
            target_ticks = cur_ticks + int(round(delta_seconds * JELLYFIN_TICKS_PER_SEC))
            if runtime_ticks > 0:
                target_ticks = max(0, min(runtime_ticks, target_ticks))
            else:
                target_ticks = max(0, target_ticks)
            qs = urllib.parse.urlencode({"SeekPositionTicks": str(target_ticks)})
            req = urllib.request.Request(
                f"{JELLYFIN_URL}/Sessions/{sid_quoted}/Playing/Seek?{qs}",
                data=b"",
                method="POST",
                headers=headers,
            )
            with urllib.request.urlopen(req, timeout=10) as r:
                r.read()
            return 200, {
                "ok": True,
                "command": "SeekRelative",
                "sessionId": session_id,
                "positionTicks": target_ticks,
                "positionSeconds": round(target_ticks / JELLYFIN_TICKS_PER_SEC, 3),
            }

        if playstate_cmd:
            url = f"{JELLYFIN_URL}/Sessions/{sid_quoted}/Playing/{playstate_cmd}"
            if playstate_cmd == "Seek":
                url += "?" + urllib.parse.urlencode({"SeekPositionTicks": str(seek_ticks)})
            req = urllib.request.Request(url, data=b"", method="POST", headers=headers)
            with urllib.request.urlopen(req, timeout=10) as r:
                r.read()
            res = {"ok": True, "command": playstate_cmd, "sessionId": session_id}
            if playstate_cmd == "Seek":
                res["positionTicks"] = seek_ticks
                res["positionSeconds"] = round(seek_ticks / JELLYFIN_TICKS_PER_SEC, 3)
            return 200, res

        payload = json.dumps({"Name": general_cmd, "Arguments": cmd_args}).encode("utf-8")
        headers["Content-Type"] = "application/json"
        req = urllib.request.Request(
            f"{JELLYFIN_URL}/Sessions/{sid_quoted}/Command",
            data=payload,
            method="POST",
            headers=headers,
        )
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
        return 200, {"ok": True, "command": general_cmd, "sessionId": session_id}
    except urllib.error.HTTPError as e:
        if e.code == 404 and explicit_sid:
            return _jellyfin_tv_offline_response(target_device, explicit_device=explicit_device)
        return 502, {"ok": False, "error": clip(f"Jellyfin TV command failed: HTTP {e.code}", 240)}
    except (OSError, ValueError) as e:
        return 502, {"ok": False, "error": clip(f"Jellyfin TV command failed: {e}", 240)}


def jellyfin_proxy_image(item_id, image_type_name="Primary", series_id=None, max_width=None, quality=None, include_etag=False, image_index=None):
    """Fetch a Jellyfin item image (Primary/Backdrop/Thumb/Logo/Chapter) server-side without exposing API credentials."""
    def _ret(code, payload, ctype, etag=None):
        return (code, payload, ctype, etag) if include_etag else (code, payload, ctype)

    if not isinstance(item_id, str) or not JELLYFIN_ITEM_ID_RE.match(item_id):
        return _ret(400, {"error": "invalid Jellyfin item id"}, None)
    img_type = JELLYFIN_IMAGE_TYPES.get(str(image_type_name or "Primary").strip().lower())
    if not img_type:
        return _ret(400, {"error": "unsupported image type"}, None)

    chapter_idx = None
    if img_type == "Chapter":
        chapter_idx = _jellyfin_safe_int(image_index)
        if chapter_idx is None or chapter_idx < 0 or chapter_idx > 10000:
            return _ret(400, {"error": "invalid chapter imageIndex"}, None)

    mw = _jellyfin_safe_int(max_width)
    mw = max(64, min(1920, mw)) if mw is not None else 600
    qual = _jellyfin_safe_int(quality)
    qual = max(10, min(100, qual)) if qual is not None else 85

    fallback_series_id = (
        series_id.strip()
        if isinstance(series_id, str) and JELLYFIN_ITEM_ID_RE.match(series_id.strip()) and series_id.strip() != item_id
        else None
    )

    try:
        key = _jellyfin_api_key()
    except (OSError, ValueError) as e:
        return _ret(502, {"error": clip(f"Jellyfin key error: {e}", 240)}, None)

    qs = urllib.parse.urlencode({"maxWidth": str(mw), "quality": str(qual)})
    headers = {"Authorization": f'MediaBrowser Token="{key}"'}
    candidates = [item_id] + ([fallback_series_id] if fallback_series_id else [])

    for idx, candidate in enumerate(candidates):
        if img_type == "Chapter":
            url = f"{JELLYFIN_URL}/Items/{candidate}/Images/Chapter/{chapter_idx}?{qs}"
        else:
            url = f"{JELLYFIN_URL}/Items/{candidate}/Images/{img_type}?{qs}"
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                data = r.read(8 * 1024 * 1024)
                raw_ct = (r.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
                ctype = raw_ct if raw_ct.startswith("image/") else (image_type(data) or "image/jpeg")
                etag = (r.headers.get("ETag") or "").strip() or None
                return _ret(200, data, ctype, etag)
        except urllib.error.HTTPError as e:
            if e.code == 404 and idx + 1 < len(candidates):
                continue
            if e.code == 404:
                return _ret(404, {"error": "image not found"}, None)
            return _ret(502, {"error": clip(f"Jellyfin image fetch failed: HTTP {e.code}", 240)}, None)
        except (OSError, ValueError) as e:
            return _ret(502, {"error": clip(f"Jellyfin image fetch failed: {e}", 240)}, None)
    return _ret(404, {"error": "image not found"}, None)


def _jellyfin_send_remote_command(key, session_id, item_id, media_type, title="", start_position_ticks=None):
    sid = urllib.parse.quote(session_id, safe="")
    if media_type == "tv":
        qs = urllib.parse.urlencode({
            "itemId": item_id,
            "itemType": "Series",
            "itemName": title,
        })
        url = f"{JELLYFIN_URL}/Sessions/{sid}/Viewing?{qs}"
        action = "view"
    else:
        params = {
            "itemIds": item_id,
            "playCommand": "PlayNow",
        }
        if isinstance(start_position_ticks, int) and start_position_ticks > 0:
            params["startPositionTicks"] = str(start_position_ticks)
        qs = urllib.parse.urlencode(params)
        url = f"{JELLYFIN_URL}/Sessions/{sid}/Playing?{qs}"
        action = "play"
    req = urllib.request.Request(
        url, data=b"", method="POST",
        headers={"Authorization": f'MediaBrowser Token="{key}"'},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        r.read()
    return action


def jellyfin_play_on_tv(body):
    """Send an available movie, TV show, or specific episode to the configured Jellyfin Android TV session.
    Accepts optional `jellyfinItemId` (32-hex) to play a specific item/episode directly on TV.
    Level 2 (auto-launching org.jellyfin.androidtv via Android TV Remote v2 / ADB when no
    session is active) can hook in right after `_jellyfin_find_tv_session` returns None."""
    raw_jf_item_id = body.get("jellyfinItemId")
    explicit_item_id = None
    if raw_jf_item_id is not None and str(raw_jf_item_id).strip():
        cand = str(raw_jf_item_id).strip()
        if not JELLYFIN_ITEM_ID_RE.match(cand):
            return 400, {"ok": False, "error": "Invalid jellyfinItemId."}
        explicit_item_id = cand

    media_url = body.get("mediaUrl")
    tmdb_id_raw = body.get("tmdbId")
    tmdb_id = None
    if tmdb_id_raw is not None and not isinstance(tmdb_id_raw, bool):
        try:
            val = int(tmdb_id_raw)
            if val > 0:
                tmdb_id = val
        except (TypeError, ValueError):
            pass
    raw_mt = str(body.get("mediaType") or "").strip().lower()
    if raw_mt == "episode":
        media_type = "episode"
    elif raw_mt == "tv":
        media_type = "tv"
    else:
        media_type = "movie"
    start_pos_ticks = _jellyfin_safe_int(body.get("startPositionTicks"))
    if start_pos_ticks is not None and start_pos_ticks <= 0:
        start_pos_ticks = None
    title = str(body.get("title") or "").strip()
    explicit_sid = str(body.get("sessionId") or "").strip() or None
    target_device, explicit_device = _jellyfin_resolve_target_device(body.get("deviceName"))

    try:
        key = _jellyfin_api_key()
        item_id = explicit_item_id or _jellyfin_extract_item_id(media_url)
        if not item_id and tmdb_id is not None:
            lookup_mt = "tv" if media_type == "tv" else "movie"
            item_id = _jellyfin_lookup_item_by_tmdb(key, tmdb_id, lookup_mt)
        if not item_id:
            return 404, {"ok": False, "error": "Item not found in Jellyfin library."}

        sessions = _jellyfin_get_sessions(key, timeout=10)
        session = _jellyfin_find_tv_session(
            sessions,
            target_device,
            explicit_device=explicit_device,
            session_id=explicit_sid,
        )
        if not session:
            return _jellyfin_tv_offline_response(target_device, explicit_device=explicit_device)

        sid = str(session["Id"])
        action = _jellyfin_send_remote_command(
            key, sid, item_id, media_type, title=title, start_position_ticks=start_pos_ticks
        )
        device_label = str(session.get("DeviceName") or session.get("Client") or target_device)
        return 200, {"ok": True, "device": device_label, "sessionId": sid, "action": action, "itemId": item_id}
    except (OSError, ValueError) as e:
        return 502, {"ok": False, "error": clip(f"Jellyfin play on TV failed: {e}", 240)}


def kbd_lock_status(run_fn=None):
    if KBD_LOCK_UNIT not in own_units():
        return {"available": False, "enabled": False, "active": "missing"}
    run = run_fn or subprocess.run
    p = run(["systemctl", "--user", "is-active", KBD_LOCK_UNIT], capture_output=True, text=True, timeout=10)
    state = (p.stdout or "").strip() or "inactive"
    return {"available": True, "enabled": state == "active", "active": state}


def kbd_lock_set(body, run_fn=None):
    enabled = body.get("enabled")
    if not isinstance(enabled, bool):
        return 400, {"error": "enabled must be true or false"}
    if KBD_LOCK_UNIT not in own_units():
        return 404, {"error": f"{KBD_LOCK_UNIT} is not installed"}
    action = "start" if enabled else "stop"
    run = run_fn or subprocess.run
    p = run(["systemctl", "--user", action, KBD_LOCK_UNIT], capture_output=True, text=True, timeout=15)
    if p.returncode:
        return 500, {"error": p.stderr.strip() or f"{action} failed"}
    return 200, kbd_lock_status(run_fn=run_fn)


SEERR_NATIVE_FILTERS = (
    "all", "pending", "approved", "processing", "available",
    "unavailable", "failed", "deleted", "completed",
)
SEERR_LOCAL_FILTERS = ("partial", "grabbing", "requested", "declined", "blocklisted")
SEERR_ALL_FILTERS = set(SEERR_NATIVE_FILTERS) | set(SEERR_LOCAL_FILTERS)
_SEERR_CERT_COUNTRIES = ("US", "IN", "GB", "CA", "AU")
_SEERR_MOVIE_CREW_JOBS = (
    "Director", "Screenplay", "Writer", "Story",
    "Director of Photography", "Original Music Composer",
)
_SEERR_TV_CREW_JOBS = ("Creator", "Showrunner", "Executive Producer", "Director")
_SEERR_REQ_STATUS_MAP = {
    1: "pending",
    2: "approved",
    3: "declined",
    4: "failed",
    5: "completed",
}


class SeerrClient:
    """Thin authenticated proxy to the local Seerr API + IMDb/TMDB/Letterboxd link resolver."""

    def __init__(self, url=SEERR_URL, settings_file=SEERR_SETTINGS):
        self.url = url.rstrip("/")
        self.settings_file = settings_file
        self._lock = threading.Lock()
        self._meta_cache = {}  # (media_type, tmdb_id) -> {title, year, poster, collection}

    def _api_key(self):
        with open(self.settings_file) as f:
            key = (json.load(f).get("main") or {}).get("apiKey") or ""
        if not key:
            raise ValueError("Seerr API key missing")
        return key

    def _req(self, method, path, body=None, timeout=10):
        key = self._api_key()
        headers = {"X-Api-Key": key, "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(f"{self.url}/api/v1{path}", data=data, method=method, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
        return json.loads(raw) if raw.strip() else {}

    @staticmethod
    def _poster(path, size="w300"):
        if not path or not isinstance(path, str):
            return None
        if path.startswith("http://") or path.startswith("https://"):
            return path
        return f"https://image.tmdb.org/t/p/{size}{path}"

    @staticmethod
    def _media_status(media_info):
        if not isinstance(media_info, dict):
            return "none"
        st = media_info.get("status")
        dl = media_info.get("downloadStatus") or []
        if st == 5:
            return "available"
        if st == 7:
            return "deleted"
        if st == 6:
            return "blocklisted"
        if st == 4:
            return "partial"
        if dl:
            return "grabbing"
        if st in (2, 3):
            return "requested"
        return "none"

    @staticmethod
    def _year(date_str):
        m = re.match(r"^(\d{4})", str(date_str or ""))
        return int(m.group(1)) if m else None

    @staticmethod
    def _vote_avg(val):
        try:
            f = float(val)
            return round(f, 1) if f > 0 else None
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _pos_int(val):
        try:
            n = int(val)
            return n if n > 0 else None
        except (TypeError, ValueError):
            return None

    def _extract_collection(self, d):
        col = d.get("collection") or d.get("belongs_to_collection")
        if not isinstance(col, dict):
            return None
        try:
            cid = int(col.get("id") or 0)
        except (TypeError, ValueError):
            return None
        if cid <= 0:
            return None
        cname = col.get("name") or col.get("title") or ""
        if not cname:
            return None
        return {
            "id": cid,
            "name": clip(cname, 160),
            "poster": self._poster(col.get("posterPath") or col.get("poster_path")),
        }

    def _normalize_item(self, x, forced_type=None):
        mtype = forced_type or x.get("mediaType")
        if mtype == "collection":
            title = x.get("name") or x.get("title") or "Untitled Collection"
            return {
                "id": int(x.get("id") or 0),
                "mediaType": "collection",
                "title": clip(title, 160),
                "year": None,
                "poster": self._poster(x.get("posterPath")),
                "overview": clip(x.get("overview") or "", 240),
                "status": "none",
            }
        if mtype not in ("movie", "tv"):
            return None
        title = x.get("title") or x.get("name") or x.get("originalTitle") or x.get("originalName") or "Untitled"
        year = self._year(x.get("releaseDate") or x.get("firstAirDate"))
        item = {
            "id": int(x.get("id") or 0),
            "mediaType": mtype,
            "title": clip(title, 160),
            "year": year,
            "poster": self._poster(x.get("posterPath")),
            "overview": clip(x.get("overview") or "", 240),
            "status": self._media_status(x.get("mediaInfo")),
        }
        va = self._vote_avg(x.get("voteAverage"))
        if va is not None:
            item["voteAverage"] = va
        return item

    @staticmethod
    def _extract_certification(d, media_type):
        if media_type == "movie":
            results = (d.get("releases") or {}).get("results") or []
            by_country = {}
            for entry in results:
                if not isinstance(entry, dict):
                    continue
                cc = str(entry.get("iso_3166_1") or "").upper()
                rdates = entry.get("release_dates") or []
                theatrical = None
                fallback = None
                for rd in rdates:
                    if not isinstance(rd, dict):
                        continue
                    cert = str(rd.get("certification") or "").strip()
                    if not cert:
                        continue
                    if rd.get("type") == 3 and theatrical is None:
                        theatrical = cert
                    if fallback is None:
                        fallback = cert
                chosen = theatrical or fallback
                if chosen and cc not in by_country:
                    by_country[cc] = chosen
            for cc in _SEERR_CERT_COUNTRIES:
                if cc in by_country:
                    return by_country[cc]
            return next(iter(by_country.values()), None)
        results = (d.get("contentRatings") or {}).get("results") or []
        by_country = {}
        for entry in results:
            if not isinstance(entry, dict):
                continue
            cc = str(entry.get("iso_3166_1") or "").upper()
            rating = str(entry.get("rating") or "").strip()
            if rating and cc not in by_country:
                by_country[cc] = rating
        for cc in _SEERR_CERT_COUNTRIES:
            if cc in by_country:
                return by_country[cc]
        return next(iter(by_country.values()), None)

    def _fetch_remote_ratings(self, media_type, tid):
        if media_type == "movie":
            try:
                return self._req("GET", f"/movie/{tid}/ratingscombined", timeout=6)
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    return None
                try:
                    return self._req("GET", f"/movie/{tid}/ratings", timeout=6)
                except Exception:
                    return None
            except Exception:
                return None
        try:
            return self._req("GET", f"/tv/{tid}/ratings", timeout=6)
        except Exception:
            return None

    def _build_ratings(self, d, remote):
        ratings = {}
        va = self._vote_avg(d.get("voteAverage"))
        vc = self._pos_int(d.get("voteCount"))
        if va is not None:
            ratings["tmdb"] = {"score": va, "count": vc or 0}
        if isinstance(remote, dict):
            rt_raw = remote.get("rt") if isinstance(remote.get("rt"), dict) else (
                remote if ("criticsScore" in remote or "audienceScore" in remote) else None
            )
            if isinstance(rt_raw, dict):
                cs = rt_raw.get("criticsScore")
                aus = rt_raw.get("audienceScore")
                if cs is not None or aus is not None:
                    rt_out = {}
                    if cs is not None:
                        try:
                            rt_out["criticsScore"] = int(round(float(cs)))
                        except (TypeError, ValueError):
                            pass
                    if rt_raw.get("criticsRating"):
                        rt_out["criticsRating"] = str(rt_raw["criticsRating"])
                    if aus is not None:
                        try:
                            rt_out["audienceScore"] = int(round(float(aus)))
                        except (TypeError, ValueError):
                            pass
                    if rt_raw.get("audienceRating"):
                        rt_out["audienceRating"] = str(rt_raw["audienceRating"])
                    if rt_raw.get("url"):
                        rt_out["url"] = str(rt_raw["url"])
                    if rt_out:
                        ratings["rt"] = rt_out
            imdb_raw = remote.get("imdb")
            if isinstance(imdb_raw, dict) and imdb_raw.get("criticsScore") is not None:
                sc = self._vote_avg(imdb_raw.get("criticsScore"))
                if sc is not None:
                    imdb_out = {"score": sc}
                    cnt = self._pos_int(imdb_raw.get("criticsScoreCount"))
                    if cnt is not None:
                        imdb_out["count"] = cnt
                    if imdb_raw.get("url"):
                        imdb_out["url"] = str(imdb_raw["url"])
                    ratings["imdb"] = imdb_out
        return ratings

    def _extract_cast_and_crew(self, d, media_type):
        credits_raw = d.get("credits") if isinstance(d.get("credits"), dict) else {}
        cast = []
        seen_cast = set()
        for c in credits_raw.get("cast") or []:
            if not isinstance(c, dict):
                continue
            pid = self._pos_int(c.get("id"))
            name = str(c.get("name") or "").strip()
            if not pid or not name or pid in seen_cast:
                continue
            seen_cast.add(pid)
            cast.append({
                "id": pid,
                "name": clip(name, 100),
                "character": clip(str(c.get("character") or "").strip(), 120),
                "profilePath": self._poster(c.get("profilePath") or c.get("profile_path"), "w185"),
            })
            if len(cast) >= 20:
                break

        priority_jobs = _SEERR_MOVIE_CREW_JOBS if media_type == "movie" else _SEERR_TV_CREW_JOBS
        job_rank = {job: idx for idx, job in enumerate(priority_jobs)}
        crew_by_id = {}

        def _add_crew(pid, name, job, dept, ppath, rank):
            if not pid or not name:
                return
            if pid not in crew_by_id:
                crew_by_id[pid] = {
                    "id": pid,
                    "name": clip(name, 100),
                    "jobs": [job],
                    "department": dept,
                    "profilePath": self._poster(ppath, "w185"),
                    "rank": rank,
                }
            else:
                entry = crew_by_id[pid]
                if job not in entry["jobs"]:
                    entry["jobs"].append(job)
                if rank < entry["rank"]:
                    entry["rank"] = rank
                if not entry["profilePath"] and ppath:
                    entry["profilePath"] = self._poster(ppath, "w185")

        if media_type == "tv":
            for creator in d.get("createdBy") or []:
                if not isinstance(creator, dict):
                    continue
                _add_crew(
                    self._pos_int(creator.get("id")),
                    str(creator.get("name") or "").strip(),
                    "Creator",
                    "Directing",
                    creator.get("profilePath") or creator.get("profile_path"),
                    0,
                )

        for c in credits_raw.get("crew") or []:
            if not isinstance(c, dict):
                continue
            job = str(c.get("job") or "").strip()
            if job not in job_rank:
                continue
            _add_crew(
                self._pos_int(c.get("id")),
                str(c.get("name") or "").strip(),
                job,
                str(c.get("department") or "").strip(),
                c.get("profilePath") or c.get("profile_path"),
                job_rank[job],
            )

        sorted_crew = sorted(crew_by_id.values(), key=lambda x: x["rank"])[:8]
        crew = [
            {
                "id": entry["id"],
                "name": entry["name"],
                "job": clip(", ".join(sorted(entry["jobs"], key=lambda j: job_rank.get(j, 99))[:3]), 100),
                "department": entry["department"],
                "profilePath": entry["profilePath"],
            }
            for entry in sorted_crew
        ]
        return cast, crew

    def _fetch_recommendations(self, media_type, tid):
        try:
            data = self._req("GET", f"/{media_type}/{tid}/recommendations?page=1", timeout=6)
        except Exception:
            return []
        out = []
        seen = set()
        for x in (data.get("results") or []):
            if not isinstance(x, dict):
                continue
            item = self._normalize_item(x, forced_type=x.get("mediaType") or media_type)
            if not item or not item["id"]:
                continue
            key = (item["mediaType"], item["id"])
            if key in seen:
                continue
            seen.add(key)
            out.append(item)
            if len(out) >= 12:
                break
        return out

    def _discover_collections(self, query, items):
        seen_ids = {item["id"] for item in items if item.get("mediaType") == "collection"}
        top_movies = [item for item in items if item.get("mediaType") == "movie"][:4]
        if not top_movies:
            return []

        def _fetch_col(movie_id):
            try:
                meta = self._meta("movie", movie_id)
                return meta.get("collection") if isinstance(meta, dict) else None
            except Exception:
                return None

        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            cols = list(pool.map(_fetch_col, [m["id"] for m in top_movies]))

        discovered = {}
        for idx, col in enumerate(cols):
            if not isinstance(col, dict):
                continue
            cid = col.get("id")
            if not cid or cid in seen_ids:
                continue
            if cid not in discovered:
                discovered[cid] = {"col": col, "count": 0, "first": idx}
            discovered[cid]["count"] += 1

        if not discovered:
            return []

        q_lower = str(query or "").strip().lower()
        q_tokens = [tok for tok in re.split(r"\s+", q_lower) if len(tok) >= 2]

        def _rank(entry):
            name_lower = str(entry["col"].get("name") or "").lower()
            exact_sub = bool(q_lower and q_lower in name_lower)
            all_tokens = bool(q_tokens and all(tok in name_lower for tok in q_tokens))
            return (1 if exact_sub else 0, 1 if all_tokens else 0, entry["count"], -entry["first"])

        ranked = sorted(discovered.values(), key=_rank, reverse=True)[:2]
        return [
            {
                "id": entry["col"]["id"],
                "mediaType": "collection",
                "title": entry["col"]["name"],
                "year": None,
                "poster": entry["col"].get("poster"),
                "overview": "",
                "status": "none",
            }
            for entry in ranked
        ]

    def search(self, query):
        q = str(query or "").strip()
        if not q:
            return 200, {"results": []}
        try:
            data = self._req("GET", f"/search?query={urllib.parse.quote(q)}&page=1")
        except (OSError, ValueError) as e:
            return 502, {"error": clip(f"Seerr search failed: {e}", 240)}
        out = []
        for x in (data.get("results") or []):
            if not isinstance(x, dict):
                continue
            item = self._normalize_item(x)
            if item and item["id"]:
                out.append(item)
            if len(out) >= 20:
                break
        discovered = self._discover_collections(q, out)
        if discovered:
            out = discovered + out
        return 200, {"results": out}

    def details(self, media_type, tmdb_id):
        if media_type not in ("movie", "tv"):
            return 400, {"error": "type must be movie or tv"}
        try:
            tid = int(tmdb_id)
            if tid <= 0:
                raise ValueError("bad id")
        except (TypeError, ValueError):
            return 400, {"error": "invalid tmdb id"}

        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            f_detail = pool.submit(self._req, "GET", f"/{media_type}/{tid}")
            f_ratings = pool.submit(self._fetch_remote_ratings, media_type, tid)
            f_recs = pool.submit(self._fetch_recommendations, media_type, tid)
            try:
                d = f_detail.result()
            except urllib.error.HTTPError as e:
                return (404 if e.code == 404 else 502), {"error": f"Seerr HTTP {e.code}"}
            except (OSError, ValueError) as e:
                return 502, {"error": clip(f"Seerr details failed: {e}", 240)}
            remote_ratings = f_ratings.result()
            recommendations = f_recs.result()

        if not isinstance(d, dict):
            return 404, {"error": "not found"}
        item = self._normalize_item(d, forced_type=media_type)
        if not item:
            return 404, {"error": "not found"}

        # Full overview + rich metadata
        item["overview"] = clip(d.get("overview") or "", 4000)
        item["backdrop"] = self._poster(d.get("backdropPath"), "w780")
        orig = str(d.get("originalTitle") or d.get("originalName") or "").strip()
        item["originalTitle"] = clip(orig, 160) if orig else None
        tagline = str(d.get("tagline") or "").strip()
        item["tagline"] = clip(tagline, 300) if tagline else None
        item["releaseDate"] = (d.get("releaseDate") or d.get("firstAirDate") or None)
        if media_type == "tv":
            item["lastAirDate"] = d.get("lastAirDate") or None
        if media_type == "movie":
            item["runtime"] = self._pos_int(d.get("runtime"))
        else:
            ert = [self._pos_int(x) for x in (d.get("episodeRunTime") or [])]
            ert = [x for x in ert if x]
            last_ep_rt = self._pos_int((d.get("lastEpisodeToAir") or {}).get("runtime")) if isinstance(d.get("lastEpisodeToAir"), dict) else None
            item["runtime"] = ert[0] if ert else last_ep_rt
        item["mediaStatus"] = str(d.get("status") or "").strip() or None
        item["genres"] = [
            clip(g["name"], 60)
            for g in (d.get("genres") or [])
            if isinstance(g, dict) and g.get("name")
        ]
        item["certification"] = self._extract_certification(d, media_type)
        item["spokenLanguages"] = [
            clip(str(lang.get("english_name") or lang.get("name")), 60)
            for lang in (d.get("spokenLanguages") or [])
            if isinstance(lang, dict) and (lang.get("english_name") or lang.get("name"))
        ][:6]
        item["countries"] = [
            clip(str(c.get("name") or c.get("iso_3166_1")), 60)
            for c in (d.get("productionCountries") or [])
            if isinstance(c, dict) and (c.get("name") or c.get("iso_3166_1"))
        ][:6]
        networks = [
            clip(str(n.get("name")), 80)
            for n in (d.get("networks") or [])
            if isinstance(n, dict) and n.get("name")
        ][:5]
        companies = [
            clip(str(c.get("name")), 80)
            for c in (d.get("productionCompanies") or [])
            if isinstance(c, dict) and c.get("name")
        ][:5]
        item["studios"] = companies if media_type == "movie" else (networks or companies)
        if media_type == "tv":
            item["networks"] = networks
            item["numberOfSeasons"] = self._pos_int(d.get("numberOfSeasons"))
            item["numberOfEpisodes"] = self._pos_int(d.get("numberOfEpisodes"))
        else:
            item["budget"] = self._pos_int(d.get("budget"))
            item["revenue"] = self._pos_int(d.get("revenue"))

        imdb_id = d.get("imdbId") or (d.get("externalIds") or {}).get("imdbId")
        item["imdbId"] = str(imdb_id).strip() if imdb_id else None
        media_info = d.get("mediaInfo") if isinstance(d.get("mediaInfo"), dict) else {}
        item["mediaUrl"] = media_info.get("mediaUrl") or None
        item["voteAverage"] = self._vote_avg(d.get("voteAverage"))
        item["voteCount"] = self._pos_int(d.get("voteCount"))
        item["ratings"] = self._build_ratings(d, remote_ratings)
        cast, crew = self._extract_cast_and_crew(d, media_type)
        item["cast"] = cast
        item["crew"] = crew
        item["recommendations"] = recommendations

        media_reqs = []
        for req in media_info.get("requests") or []:
            if not isinstance(req, dict):
                continue
            req_by = req.get("requestedBy") or {}
            who = req_by.get("displayName") or req_by.get("username") or req_by.get("jellyfinUsername") or ""
            media_reqs.append({
                "id": int(req.get("id") or 0),
                "requestStatus": _SEERR_REQ_STATUS_MAP.get(req.get("status"), "unknown"),
                "requestedBy": clip(who, 60),
                "createdAt": req.get("createdAt") or "",
                "seasons": [
                    s.get("seasonNumber")
                    for s in (req.get("seasons") or [])
                    if isinstance(s, dict) and isinstance(s.get("seasonNumber"), int)
                ],
            })
        if media_reqs:
            item["requests"] = media_reqs

        col = self._extract_collection(d) if media_type == "movie" else None
        if col:
            item["collection"] = col
        with self._lock:
            self._meta_cache[(media_type, tid)] = {
                "title": item["title"], "year": item["year"], "poster": item["poster"], "collection": col,
            }
        if media_type == "tv":
            info_seasons = {
                s.get("seasonNumber"): s.get("status")
                for s in (media_info.get("seasons") or [])
                if isinstance(s, dict)
            }
            seasons = []
            for s in d.get("seasons") or []:
                if not isinstance(s, dict):
                    continue
                sn = s.get("seasonNumber")
                if not isinstance(sn, int) or sn < 1:
                    continue
                st_code = info_seasons.get(sn)
                s_status = (
                    "available" if st_code == 5 else
                    "deleted" if st_code == 7 else
                    "blocklisted" if st_code == 6 else
                    "partial" if st_code == 4 else
                    "requested" if st_code in (2, 3) else
                    "none"
                )
                season_obj = {
                    "seasonNumber": sn,
                    "name": clip(s.get("name") or f"Season {sn}", 80),
                    "episodeCount": int(s.get("episodeCount") or 0),
                    "status": s_status,
                }
                if s.get("airDate"):
                    season_obj["airDate"] = s.get("airDate")
                if s.get("posterPath"):
                    season_obj["poster"] = self._poster(s.get("posterPath"))
                if s.get("overview"):
                    season_obj["overview"] = clip(s.get("overview"), 400)
                seasons.append(season_obj)
            item["seasons"] = seasons
        if item.get("status") in ("available", "partial"):
            try:
                resume_info = _watch_service().get_resume_state(media_type, tid)
                if isinstance(resume_info, dict):
                    item.update(resume_info)
            except Exception:
                pass
        return 200, item

    def person(self, person_id):
        try:
            pid = int(person_id)
            if pid <= 0:
                raise ValueError("bad id")
        except (TypeError, ValueError):
            return 400, {"error": "invalid person id"}

        def _fetch_credits():
            try:
                return self._req("GET", f"/person/{pid}/combined_credits", timeout=10)
            except Exception:
                return {}

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f_person = pool.submit(self._req, "GET", f"/person/{pid}", None, 10)
            f_credits = pool.submit(_fetch_credits)
            try:
                p = f_person.result()
            except urllib.error.HTTPError as e:
                return (404 if e.code == 404 else 502), {"error": f"Seerr HTTP {e.code}"}
            except (OSError, ValueError) as e:
                return 502, {"error": clip(f"Seerr person failed: {e}", 240)}
            raw_credits = f_credits.result()

        if not isinstance(p, dict) or not p.get("id"):
            return 404, {"error": "not found"}

        known_dept = str(p.get("knownForDepartment") or "").strip() or None
        merged = {}

        def _ingest(entries, is_cast):
            for c in entries or []:
                if not isinstance(c, dict):
                    continue
                mtype = c.get("mediaType")
                if mtype not in ("movie", "tv"):
                    continue
                mid = self._pos_int(c.get("id"))
                if not mid:
                    continue
                title = c.get("title") or c.get("name") or c.get("originalTitle") or c.get("originalName") or ""
                if not title:
                    continue
                rel = str(c.get("releaseDate") or c.get("firstAirDate") or "").strip() or None
                key = (mtype, mid)
                entry = merged.get(key)
                if entry is None:
                    entry = {
                        "id": mid,
                        "mediaType": mtype,
                        "title": clip(title, 160),
                        "releaseDate": rel,
                        "year": self._year(rel),
                        "poster": self._poster(c.get("posterPath")),
                        "characters": [],
                        "jobs": [],
                        "isCast": False,
                        "isCrew": False,
                        "voteAverage": self._vote_avg(c.get("voteAverage")),
                        "voteCount": self._pos_int(c.get("voteCount")) or 0,
                        "popularity": float(c.get("popularity") or 0.0),
                        "status": self._media_status(c.get("mediaInfo")),
                        "episodeCount": self._pos_int(c.get("episodeCount")),
                    }
                    merged[key] = entry
                else:
                    if not entry["poster"] and c.get("posterPath"):
                        entry["poster"] = self._poster(c.get("posterPath"))
                    if not entry["releaseDate"] and rel:
                        entry["releaseDate"] = rel
                        entry["year"] = self._year(rel)
                    if entry["status"] == "none" and c.get("mediaInfo"):
                        entry["status"] = self._media_status(c.get("mediaInfo"))
                    vc = self._pos_int(c.get("voteCount")) or 0
                    if vc > entry["voteCount"]:
                        entry["voteCount"] = vc
                        entry["voteAverage"] = self._vote_avg(c.get("voteAverage"))
                    pop = float(c.get("popularity") or 0.0)
                    if pop > entry["popularity"]:
                        entry["popularity"] = pop
                if is_cast:
                    entry["isCast"] = True
                    ch = str(c.get("character") or "").strip()
                    if ch and ch not in entry["characters"]:
                        entry["characters"].append(ch)
                else:
                    entry["isCrew"] = True
                    jb = str(c.get("job") or "").strip()
                    if jb and jb not in entry["jobs"]:
                        entry["jobs"].append(jb)

        if isinstance(raw_credits, dict):
            _ingest(raw_credits.get("cast"), True)
            _ingest(raw_credits.get("crew"), False)

        credits_list = []
        cast_count = 0
        crew_count = 0
        _job_prio = {
            "Director": 0, "Creator": 1, "Showrunner": 2, "Writer": 3,
            "Screenplay": 4, "Story": 5, "Executive Producer": 6, "Producer": 7,
            "Director of Photography": 8, "Original Music Composer": 9,
        }
        _self_re = re.compile(r"^(?:self|himself|herself)(?:\b|\s|[-–(])", re.I)
        for entry in merged.values():
            if entry["isCast"]:
                cast_count += 1
            if entry["isCrew"]:
                crew_count += 1
            sorted_jobs = sorted(entry["jobs"], key=lambda j: (_job_prio.get(j, 50), j))
            character = clip(", ".join(entry["characters"][:2]), 120) or None
            job = clip(", ".join(sorted_jobs[:2]), 120) or None
            if known_dept and known_dept != "Acting" and job:
                role = job
            else:
                role = character or job or ""
            is_self_tv = (
                entry["isCast"]
                and not entry["isCrew"]
                and entry["mediaType"] == "tv"
                and (not entry["characters"] or all(_self_re.match(ch) for ch in entry["characters"]))
            )
            notability = entry["popularity"] + min(entry["voteCount"], 25000) / 250.0
            sort_key = (
                0 if is_self_tv else 1,
                1 if entry["poster"] else 0,
                round(notability, 2),
                entry["releaseDate"] or "",
            )
            item_out = {
                "id": entry["id"],
                "mediaType": entry["mediaType"],
                "title": entry["title"],
                "year": entry["year"],
                "releaseDate": entry["releaseDate"],
                "poster": entry["poster"],
                "character": character,
                "job": job,
                "role": role,
                "isCast": entry["isCast"],
                "isCrew": entry["isCrew"],
                "voteAverage": entry["voteAverage"],
                "voteCount": entry["voteCount"] or None,
                "popularity": round(entry["popularity"], 2),
                "status": entry["status"],
            }
            if entry["episodeCount"]:
                item_out["episodeCount"] = entry["episodeCount"]
            credits_list.append((sort_key, item_out))

        credits_list.sort(key=lambda pair: pair[0], reverse=True)
        credits_out = [item for _, item in credits_list[:120]]

        return 200, {
            "id": int(p.get("id") or pid),
            "name": clip(str(p.get("name") or f"Person #{pid}"), 120),
            "biography": clip(str(p.get("biography") or "").strip(), 6000),
            "birthday": p.get("birthday") or None,
            "deathday": p.get("deathday") or None,
            "placeOfBirth": clip(str(p.get("placeOfBirth") or "").strip(), 160) or None,
            "knownForDepartment": known_dept,
            "profilePath": self._poster(p.get("profilePath") or p.get("profile_path"), "w300"),
            "imdbId": str(p.get("imdbId")).strip() if p.get("imdbId") else None,
            "alsoKnownAs": [clip(str(a), 100) for a in (p.get("alsoKnownAs") or []) if a][:6],
            "credits": credits_out,
            "castCount": cast_count,
            "crewCount": crew_count,
        }

    def collection(self, collection_id):
        try:
            cid = int(collection_id)
            if cid <= 0:
                raise ValueError("bad id")
        except (TypeError, ValueError):
            return 400, {"error": "invalid collection id"}
        try:
            d = self._req("GET", f"/collection/{cid}")
        except urllib.error.HTTPError as e:
            return (404 if e.code == 404 else 502), {"error": f"Seerr HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": clip(f"Seerr collection failed: {e}", 240)}
        if not isinstance(d, dict):
            return 404, {"error": "not found"}
        raw_parts = []
        for idx, p in enumerate(d.get("parts") or []):
            if not isinstance(p, dict):
                continue
            item = self._normalize_item(p, forced_type="movie")
            if not item or not item["id"]:
                continue
            rel = str(p.get("releaseDate") or "").strip()
            has_date = bool(re.match(r"^\d{4}", rel))
            raw_parts.append(((0, rel, idx) if has_date else (1, "", idx), item))
        raw_parts.sort(key=lambda pair: pair[0])
        return 200, {
            "id": int(d.get("id") or cid),
            "name": clip(d.get("name") or f"Collection #{cid}", 160),
            "overview": clip(d.get("overview") or "", 600),
            "poster": self._poster(d.get("posterPath")),
            "backdrop": self._poster(d.get("backdropPath"), "w780"),
            "parts": [item for _, item in raw_parts],
        }

    def _meta(self, media_type, tmdb_id):
        key = (media_type, tmdb_id)
        with self._lock:
            if key in self._meta_cache:
                return self._meta_cache[key]
        try:
            d = self._req("GET", f"/{media_type}/{int(tmdb_id)}", timeout=8)
            item = self._normalize_item(d, forced_type=media_type) if isinstance(d, dict) else None
            if item:
                col = self._extract_collection(d) if media_type == "movie" else None
                meta = {
                    "title": item["title"], "year": item["year"], "poster": item["poster"],
                    "collection": col,
                }
                with self._lock:
                    self._meta_cache[key] = meta
                return meta
        except Exception:
            pass
        return {"title": f"TMDB #{tmdb_id}", "year": None, "poster": None, "collection": None}

    @staticmethod
    def _normalize_request_status(r, media):
        m_st = media.get("status")
        dl = media.get("downloadStatus") or []
        r_st = r.get("status")
        if m_st == 5:
            return "available"
        if m_st == 7:
            return "deleted"
        if m_st == 6:
            return "blocklisted"
        if dl:
            return "grabbing"
        if m_st == 4:
            return "partial"
        if r_st == 3:
            return "declined"
        if r_st == 4:
            return "failed"
        if r_st == 1:
            return "pending"
        return "requested"

    def requests(self, take=50, status_filter="all"):
        flt = str(status_filter or "all").strip().lower() or "all"
        if flt not in SEERR_ALL_FILTERS:
            return 400, {"error": f"invalid filter: {flt}"}
        take = max(1, min(100, int(take or 50)))
        if flt == "all":
            qs = f"take={take}&sort=added"
        elif flt in SEERR_NATIVE_FILTERS:
            qs = f"take={take}&sort=added&filter={urllib.parse.quote(flt)}"
        else:
            qs = f"take={max(take, 100)}&sort=added"
        try:
            data = self._req("GET", f"/request?{qs}")
        except (OSError, ValueError) as e:
            return 502, {"error": clip(f"Seerr requests failed: {e}", 240)}

        raw_results = [r for r in (data.get("results") or []) if isinstance(r, dict)]
        # Warm uncached metadata in parallel so first load stays fast
        to_warm = []
        seen_warm = set()
        with self._lock:
            cached_keys = set(self._meta_cache.keys())
        for r in raw_results:
            media = r.get("media") or {}
            mtype = r.get("type") or media.get("mediaType")
            tid = media.get("tmdbId")
            if mtype in ("movie", "tv") and isinstance(tid, int):
                k = (mtype, tid)
                if k not in cached_keys and k not in seen_warm:
                    seen_warm.add(k)
                    to_warm.append(k)
        if len(to_warm) > 1:
            with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
                list(pool.map(lambda pair: self._meta(pair[0], pair[1]), to_warm))

        out = []
        for r in raw_results:
            media = r.get("media") or {}
            mtype = r.get("type") or media.get("mediaType")
            tid = media.get("tmdbId")
            if mtype not in ("movie", "tv") or not isinstance(tid, int):
                continue
            meta = self._meta(mtype, tid)
            status = self._normalize_request_status(r, media)
            req_by = r.get("requestedBy") or {}
            who = req_by.get("displayName") or req_by.get("username") or req_by.get("jellyfinUsername") or ""
            seasons = [
                s.get("seasonNumber")
                for s in (r.get("seasons") or [])
                if isinstance(s, dict) and isinstance(s.get("seasonNumber"), int)
            ]
            out.append({
                "id": int(r.get("id") or 0),
                "tmdbId": tid,
                "mediaType": mtype,
                "title": meta["title"],
                "year": meta["year"],
                "poster": meta["poster"],
                "status": status,
                "requestStatus": _SEERR_REQ_STATUS_MAP.get(r.get("status"), "unknown"),
                "seasons": seasons,
                "requestedBy": clip(who, 60),
                "createdAt": r.get("createdAt") or "",
            })

        counts = None
        if flt == "all":
            page_info = data.get("pageInfo") if isinstance(data.get("pageInfo"), dict) else {}
            total = int(page_info.get("results")) if isinstance(page_info.get("results"), int) else len(out)
            counts = {
                "all": total,
                "pending": 0,
                "processing": 0,
                "available": 0,
                "partial": 0,
                "failed": 0,
                "deleted": 0,
                "declined": 0,
                "blocklisted": 0,
                "grabbing": 0,
                "requested": 0,
            }
            for item in out:
                st = item["status"]
                if st in counts:
                    counts[st] += 1
                if st in ("requested", "grabbing"):
                    counts["processing"] += 1
        elif flt in SEERR_LOCAL_FILTERS:
            out = [item for item in out if item["status"] == flt]
            total = len(out)
        else:
            page_info = data.get("pageInfo") if isinstance(data.get("pageInfo"), dict) else {}
            total = int(page_info.get("results")) if isinstance(page_info.get("results"), int) else len(out)

        res = {"requests": out, "filter": flt, "total": total}
        if counts is not None:
            res["counts"] = counts
        return 200, res

    def create_request(self, body):
        mtype = str(body.get("mediaType") or "").strip().lower()
        if mtype not in ("movie", "tv"):
            return 400, {"error": "mediaType must be movie or tv"}
        try:
            mid = int(body.get("mediaId"))
            if mid <= 0:
                raise ValueError("bad id")
        except (TypeError, ValueError):
            return 400, {"error": "invalid mediaId"}
        payload = {"mediaType": mtype, "mediaId": mid}
        if mtype == "tv":
            seasons = body.get("seasons")
            if seasons == "all" or seasons is None:
                payload["seasons"] = "all"
            elif isinstance(seasons, list) and all(isinstance(s, int) and s >= 1 for s in seasons) and seasons:
                payload["seasons"] = seasons
            else:
                return 400, {"error": "seasons must be a non-empty list of season numbers or 'all'"}
        try:
            res = self._req("POST", "/request", payload, timeout=15)
        except urllib.error.HTTPError as e:
            raw = ""
            try:
                raw = e.read().decode("utf-8", "replace")
                msg = json.loads(raw).get("message") or raw
            except Exception:
                msg = raw or f"Seerr HTTP {e.code}"
            return (e.code if 400 <= e.code < 500 else 502), {"error": clip(str(msg), 240)}
        except (OSError, ValueError) as e:
            return 502, {"error": clip(f"Seerr request failed: {e}", 240)}
        return 200, {"ok": True, "id": res.get("id"), "status": res.get("status")}

    def _arr_imdb_lookup(self, imdb_id):
        # Try Radarr (movies) first, then Sonarr (TV) using local Arr APIs.
        try:
            _, base, conf = ARR["radarr"]
            with open(conf) as f:
                key = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())[1]
            req = urllib.request.Request(f"{base}/api/v3/movie/lookup/imdb?imdbId={imdb_id}", headers={"X-Api-Key": key})
            with urllib.request.urlopen(req, timeout=8) as r:
                m = json.load(r)
            if isinstance(m, dict) and m.get("tmdbId"):
                return "movie", int(m["tmdbId"])
        except Exception:
            pass
        try:
            _, base, conf = ARR["tv-sonarr"]
            with open(conf) as f:
                key = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())[1]
            req = urllib.request.Request(f"{base}/api/v3/series/lookup?term=imdb:{imdb_id}", headers={"X-Api-Key": key})
            with urllib.request.urlopen(req, timeout=8) as r:
                s = json.load(r)
            if isinstance(s, list) and s and isinstance(s[0], dict) and s[0].get("tmdbId"):
                return "tv", int(s[0]["tmdbId"])
        except Exception:
            pass
        return None, None

    def _resolve_letterboxd(self, url):
        u = urllib.parse.urlsplit(url)
        host = (u.hostname or "").lower()
        if u.scheme not in ("http", "https") or host not in LETTERBOXD_HOSTS:
            return None, None, None
        slug = None
        m_slug = re.search(r"/film/([^/?#]+)", u.path)
        if m_slug:
            slug = m_slug.group(1).replace("-", " ").strip()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; pc-control/1)"})
            with urllib.request.urlopen(req, timeout=6) as r:
                html = r.read(256 * 1024).decode("utf-8", "replace")
            m_tmdb = re.search(r'data-tmdb-id="(\d+)"[^>]*data-tmdb-type="(movie|tv)"', html)
            if m_tmdb:
                return m_tmdb.group(2), int(m_tmdb.group(1)), None
            m_link = re.search(r"themoviedb\.org/(movie|tv)/(\d+)", html)
            if m_link:
                return m_link.group(1), int(m_link.group(2)), None
            m_og = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]+)"', html)
            if m_og:
                title = re.sub(r"\s*\(\d{4}\)\s*$", "", m_og.group(1)).strip()
                if title:
                    return None, None, title
        except Exception:
            pass
        return None, None, slug

    def resolve_url(self, body):
        url = str(body.get("url") or "").strip()
        if not url or len(url) > 1000:
            return 400, {"error": "url required"}
        m_tmdb = re.search(r"themoviedb\.org/(movie|tv)/(\d+)", url, re.I)
        if m_tmdb:
            return self.details(m_tmdb.group(1).lower(), int(m_tmdb.group(2)))
        m_imdb = re.search(r"imdb\.com/title/(tt\d+)", url, re.I)
        if m_imdb:
            mtype, tid = self._arr_imdb_lookup(m_imdb.group(1).lower())
            if mtype and tid:
                return self.details(mtype, tid)
            return 404, {"error": f"Could not resolve IMDb {m_imdb.group(1)}"}
        m_lb = re.search(r"(?:letterboxd\.com/film/|boxd\.it/)", url, re.I)
        if m_lb:
            mtype, tid, query = self._resolve_letterboxd(url)
            if mtype and tid:
                return self.details(mtype, tid)
            if query:
                code, srch = self.search(query)
                if code == 200 and srch.get("results"):
                    top = next((r for r in srch["results"] if r.get("mediaType") in ("movie", "tv")), None)
                    if top:
                        return self.details(top["mediaType"], top["id"])
            return 404, {"error": "Could not resolve Letterboxd link"}
        return 400, {"error": "Unsupported media link (use IMDb, TMDB, or Letterboxd)"}


SEERR = SeerrClient()


def _space_module():
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import space
    return space


def _gdrive_offload_manager():
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import gdrive_offload
    notify_fn = (lambda cat, title, body: NOTIFY.send_async(cat, title, body)) if NOTIFY is not None else None
    return gdrive_offload.get_manager(notify_fn=notify_fn)


def route_round4_get(path, qs):
    if path == "/api/space":
        scope = (qs.get("scope") or ["watched"])[0]
        return _space_module().get_space(scope=scope)
    if path == "/api/space/drive":
        refresh = (qs.get("refresh") or [""])[0] in ("1", "true", "yes")
        return _gdrive_offload_manager().drive_status(force_refresh=refresh)
    if path == "/api/downloads/all":
        return 200, downloads_all()
    if path == "/api/kbd-lock":
        return 200, kbd_lock_status()
    if path == "/api/seerr/search":
        return SEERR.search(qs.get("q", [""])[0])
    if path == "/api/seerr/details":
        return SEERR.details(qs.get("type", [""])[0], qs.get("id", [""])[0])
    if path == "/api/seerr/collection":
        return SEERR.collection(qs.get("id", [""])[0])
    if path == "/api/seerr/person":
        return SEERR.person(qs.get("id", [""])[0])
    if path == "/api/seerr/requests":
        flt = (qs.get("filter") or ["all"])[0]
        take_raw = (qs.get("take") or [""])[0]
        try:
            take = int(take_raw) if take_raw else 50
        except ValueError:
            return 400, {"error": "invalid take"}
        return SEERR.requests(take=take, status_filter=flt)
    if path == "/api/jellyfin/tv/devices":
        return jellyfin_tv_devices()
    if path == "/api/jellyfin/tv/session":
        return jellyfin_tv_session(
            device_name=(qs.get("deviceName") or [None])[0],
            session_id=(qs.get("sessionId") or [None])[0],
        )
    if path == "/api/watch/home":
        refresh = (qs.get("refresh") or [""])[0] in ("1", "true", "yes")
        return _watch_service().home(force_refresh=refresh)
    if path == "/api/watch/discover":
        return _watch_service().discover(
            category=(qs.get("category") or ["all"])[0],
            media_type=(qs.get("mediaType") or [None])[0],
            genre_id=(qs.get("genreId") or [None])[0],
            network_id=(qs.get("networkId") or [None])[0],
            page=(qs.get("page") or ["1"])[0],
            sort=(qs.get("sort") or [None])[0],
        )
    if path == "/api/watch/watchlist":
        return _watch_service().get_watchlist()
    if path == "/api/watch/episodes":
        return _watch_service().episodes(
            tmdb_id_raw=(qs.get("tmdbId") or [None])[0],
            season_raw=(qs.get("season") or [None])[0],
        )
    if path == "/api/watch/downloads":
        return _watch_service().downloads(
            tmdb_id_raw=(qs.get("tmdbId") or [None])[0],
            media_type_raw=(qs.get("mediaType") or [None])[0],
        )
    if path == "/api/watch/download-info":
        return _watch_service().download_info(
            jellyfin_item_id_raw=(qs.get("jellyfinItemId") or [None])[0],
            tmdb_id_raw=(qs.get("tmdbId") or [None])[0],
            media_type_raw=(qs.get("mediaType") or [None])[0],
            season_raw=(qs.get("season") or [None])[0],
            episode_raw=(qs.get("episode") or [None])[0],
        )
    if path == "/api/watch/surprise":
        return _watch_service().surprise_me()
    if path == "/api/watch/collections":
        return _watch_service().list_collections()
    m = re.fullmatch(r"/api/watch/collections/([^/]+)", path)
    if m:
        return _watch_service().collection_items(m.group(1))
    if path == "/api/watch/subtitles/search":
        return _watch_service().search_subtitles(
            jellyfin_item_id_raw=(qs.get("jellyfinItemId") or [None])[0],
            language_raw=(qs.get("language") or ["eng"])[0],
        )
    return None


def route_round4_post(path, b):
    if path == "/api/space/free":
        return _space_module().free_space(b)
    if path == "/api/space/offload":
        return _gdrive_offload_manager().enqueue_offload(b)
    if path == "/api/space/restore":
        return _gdrive_offload_manager().enqueue_restore(b)
    if path == "/api/space/drive/delete":
        return _gdrive_offload_manager().delete_from_drive(b)
    m = re.fullmatch(r"/api/space/jobs/([0-9a-zA-Z_-]+)/cancel", path)
    if m:
        return _gdrive_offload_manager().cancel_job(m.group(1))
    if path == "/api/downloads/pause":
        return downloads_action("pause", b)
    if path == "/api/downloads/resume":
        return downloads_action("resume", b)
    if path == "/api/downloads/delete":
        return downloads_action("delete", b)
    if path == "/api/downloads/alt-speed":
        return downloads_set_alt_speed(b)
    if path == "/api/downloads/add":
        return downloads_add_magnet(b)
    if path == "/api/arr/retry":
        return arr_retry_import(b)
    if path == "/api/jellyfin/refresh":
        return jellyfin_refresh_library()
    if path == "/api/jellyfin/play-on-tv":
        return jellyfin_play_on_tv(b)
    if path == "/api/jellyfin/tv/command":
        return jellyfin_tv_command(b)
    if path == "/api/kbd-lock":
        return kbd_lock_set(b)
    if path == "/api/seerr/request":
        return SEERR.create_request(b)
    if path == "/api/seerr/resolve":
        return SEERR.resolve_url(b)
    if path == "/api/watch/watchlist":
        return _watch_service().update_watchlist(b)
    if path == "/api/watch/playback-info":
        return _watch_service().playback_info(b)
    if path == "/api/watch/progress":
        return _watch_service().report_progress(b)
    if path == "/api/watch/watched":
        return _watch_service().set_watched(b)
    if path == "/api/watch/subtitles/download":
        return _watch_service().download_subtitle(b)
    return None


TAILSCALE_REQUIRED_ERROR = {
    "error": "Video streaming and offline downloads require Tailscale",
    "tailscaleRequired": True,
}


def _is_watch_tailscale_only_path(path):
    if path in ("/pc/api/watch/subtitles/search", "/pc/api/watch/subtitles/download"):
        return False
    return (
        path == "/pc/api/watch/playback-info"
        or path == "/pc/api/watch/download-info"
        or path == "/pc/api/watch/stream"
        or path.startswith("/pc/api/watch/stream/")
        or path == "/pc/api/watch/download"
        or path.startswith("/pc/api/watch/download/")
        or path == "/pc/api/watch/subtitles"
        or path.startswith("/pc/api/watch/subtitles/")
        or path == "/pc/api/watch/trickplay"
        or path.startswith("/pc/api/watch/trickplay/")
    )


def _is_public_api_request(headers):
    """Detect requests arriving via Cloudflare Tunnel / Public API (never allowed for video bytes)."""
    if headers is None:
        return False
    for hdr in ("CF-Access-Client-Id", "CF-Access-Jwt-Assertion", "CF-Connecting-IP", "CF-Ray"):
        if headers.get(hdr):
            return True
    pub_hosts = {"api.neekhar.dev"}
    cfg_url = str(os.environ.get("PCAPI_PUBLIC_API_URL") or CONFIG.get("public_api_url") or DEFAULT_PUBLIC_API_URL).strip()
    parsed_pub = urllib.parse.urlsplit(cfg_url).hostname
    if parsed_pub:
        pub_hosts.add(parsed_pub.lower())
    for hdr in ("X-Forwarded-Host", "Host"):
        raw = str(headers.get(hdr) or "").strip().lower()
        host_only = raw.split(",", 1)[0].strip().split(":", 1)[0]
        if host_only and host_only in pub_hosts:
            return True
    return False


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

    def _is_plain_local(self):
        return bool(
            ALLOW_UNAUTH_LOCAL
            and not self.headers.get("X-Forwarded-For")
            and not self.headers.get("Tailscale-User-Login")
            and self.client_address[0] in ("127.0.0.1", "::1")
        )

    def authorized(self, is_hook=False):
        login = self.headers.get("Tailscale-User-Login")
        if login:
            if is_hook:
                return False
            return login in ALLOWED_LOGINS
        # No tailnet identity: only plain local requests (never proxied ones from tagged/shared nodes).
        if not (ALLOW_UNAUTH_LOCAL and not self.headers.get("X-Forwarded-For") and
                self.client_address[0] in ("127.0.0.1", "::1")):
            return False
        # Webhooks (Radarr/Sonarr/Seerr) arrive as other local users and authenticate with hook_secret().
        if is_hook:
            return True
        return local_token_authorized(self.headers)

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def body(self, limit=64 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit: raise ValueError("body too large")
        data = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(data, dict): raise ValueError("body must be an object")
        return data

    def _proxy_local(self, service_key, method, subpath, query):
        """Reverse-proxy a local auxiliary service under /pc/api/* so it inherits pc-api auth."""
        base = PROXY_UPSTREAMS.get(service_key)
        if not base:
            return self.send_json(404, {"error": "unknown proxy target"})
        if subpath is not None:
            if _has_path_traversal(subpath):
                return self.send_json(400, {"error": "invalid proxy path"})
            clean_sub = subpath.lstrip("/")
            if not clean_sub:
                return self.send_json(404, {"error": "not found"})
            target_url = f"{base}/{clean_sub}"
        else:
            target_url = base
        if query:
            target_url = f"{target_url}?{query}"

        req_body = None
        if method == "POST":
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                return self.send_json(400, {"error": "invalid content-length"})
            if n < 0 or n > PROXY_MAX_REQ_BODY:
                return self.send_json(413, {"error": "body too large"})
            req_body = self.rfile.read(n) if n > 0 else b""

        fwd_headers = {}
        ct = self.headers.get("Content-Type")
        if ct:
            fwd_headers["Content-Type"] = ct
        accept = self.headers.get("Accept")
        if accept:
            fwd_headers["Accept"] = accept

        timeout = PROXY_MUSIC_PLAY_TIMEOUT if (
            service_key == "music" and method == "POST" and (subpath or "").strip("/") == "api/play"
        ) else PROXY_TIMEOUT

        req = urllib.request.Request(target_url, data=req_body, headers=fwd_headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                status = getattr(resp, "status", 200)
                resp_ct = resp.headers.get("Content-Type") or "application/octet-stream"
                resp_cc = resp.headers.get("Cache-Control") or "no-store"
                data = resp.read(PROXY_MAX_RESP_BODY + 1)
        except urllib.error.HTTPError as e:
            status = e.code
            resp_ct = (e.headers.get("Content-Type") if e.headers else None) or "application/octet-stream"
            resp_cc = (e.headers.get("Cache-Control") if e.headers else None) or "no-store"
            try:
                data = e.read(PROXY_MAX_RESP_BODY + 1)
            finally:
                e.close()
        except urllib.error.URLError as e:
            reason = getattr(e, "reason", e)
            code = 503 if isinstance(reason, ConnectionRefusedError) else 502
            return self.send_json(code, {"error": clip(f"upstream {service_key} unreachable: {reason}", 240)})
        except (TimeoutError, socket.timeout):
            return self.send_json(502, {"error": f"upstream {service_key} timed out"})
        except OSError as e:
            code = 503 if isinstance(e, ConnectionRefusedError) else 502
            return self.send_json(code, {"error": clip(f"upstream {service_key} error: {e}", 240)})

        if len(data) > PROXY_MAX_RESP_BODY:
            return self.send_json(502, {"error": f"upstream {service_key} response too large"})

        self.send_response(status)
        self.send_header("Content-Type", resp_ct)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", resp_cc)
        self.end_headers()
        self.wfile.write(data)

    def _proxy_jellyfin_media(
        self,
        upstream_url,
        head_only=False,
        sanitize_playlist=False,
        content_type_override=None,
        cache_control="no-store",
        timeout=60,
    ):
        """Stream Jellyfin video/HLS/subtitle bytes over Tailscale-only pc-api without leaking Jellyfin credentials."""
        try:
            fwd_headers = _watch_service().jellyfin_auth_headers(include_client_meta=True)
        except (OSError, ValueError) as e:
            return self.send_json(502, {"error": clip(f"Jellyfin auth error: {e}", 240)})

        for hdr in ("Range", "If-Range", "Accept"):
            val = self.headers.get(hdr)
            if val:
                fwd_headers[hdr] = val

        req = urllib.request.Request(
            upstream_url,
            headers=fwd_headers,
            method="HEAD" if (head_only and not sanitize_playlist) else "GET",
        )

        resp = None
        try:
            resp = urllib.request.urlopen(req, timeout=timeout)
            status = getattr(resp, "status", 200)
            resp_headers = resp.headers
        except urllib.error.HTTPError as e:
            resp = e
            status = e.code
            resp_headers = e.headers or {}
        except urllib.error.URLError as e:
            reason = getattr(e, "reason", e)
            code = 503 if isinstance(reason, ConnectionRefusedError) else 502
            return self.send_json(code, {"error": clip(f"Jellyfin stream unreachable: {reason}", 240)})
        except (TimeoutError, socket.timeout):
            return self.send_json(504, {"error": "Jellyfin stream timed out"})
        except OSError as e:
            return self.send_json(502, {"error": clip(f"Jellyfin stream error: {e}", 240)})

        try:
            if sanitize_playlist and status == 200:
                raw_bytes = resp.read(2 * 1024 * 1024)
                cleaned = watch_mod.sanitize_m3u8(raw_bytes.decode("utf-8", "replace")).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                self.send_header("Content-Length", str(len(cleaned)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                if not head_only:
                    self.wfile.write(cleaned)
                return

            ct = (
                content_type_override if (content_type_override and status in (200, 206)) else None
            ) or (resp_headers.get("Content-Type") if resp_headers else None) or "application/octet-stream"
            clen = resp_headers.get("Content-Length") if resp_headers else None
            crange = resp_headers.get("Content-Range") if resp_headers else None
            aranges = resp_headers.get("Accept-Ranges") if resp_headers else None
            etag = resp_headers.get("ETag") if resp_headers else None
            lastmod = resp_headers.get("Last-Modified") if resp_headers else None
            eff_cc = cache_control if status in (200, 206) else "no-store"

            self.send_response(status)
            self.send_header("Content-Type", ct)
            if clen is not None and str(clen).isdigit():
                self.send_header("Content-Length", str(clen))
            elif not head_only:
                # Without Content-Length on HTTP/1.1, close connection on EOF so clients know stream ended
                self.send_header("Connection", "close")
                self.close_connection = True
            if crange:
                self.send_header("Content-Range", crange)
            if aranges:
                self.send_header("Accept-Ranges", aranges)
            elif status in (200, 206):
                self.send_header("Accept-Ranges", "bytes")
            if etag:
                self.send_header("ETag", etag)
            if lastmod:
                self.send_header("Last-Modified", lastmod)
            self.send_header("Cache-Control", eff_cc)
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()

            if head_only:
                return

            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            try:
                resp.close()
            except Exception:
                pass

    def route(self, method):
        u = urllib.parse.urlsplit(self.path)
        raw_path = u.path
        path, qs = raw_path.rstrip("/"), urllib.parse.parse_qs(u.query)
        has_traversal = _has_path_traversal(raw_path)

        is_hook = method == "POST" and bool(re.fullmatch(r"/pc/api/hook/(radarr|sonarr|seerr)", path))
        is_chat_push = method == "POST" and path == "/pc/api/chat/push"
        is_ota_trigger = method == "POST" and path == "/pc/api/ota/trigger"
        is_public_config = method == "GET" and path == "/pc/api/public-config"
        is_watch_tailscale_only = _is_watch_tailscale_only_path(path)

        auth_mode = None
        if is_chat_push or is_ota_trigger:
            if not (self._is_plain_local() and local_token_authorized(self.headers)):
                return self.send_json(403, {"error": "local token required"})
            auth_mode = "local"
        elif is_hook:
            if not self._is_plain_local():
                return self.send_json(403, {"error": "localhost required for webhook"})
            auth_mode = "local"
        elif is_public_config:
            login = self.headers.get("Tailscale-User-Login")
            if login and login in ALLOWED_LOGINS:
                auth_mode = "tailscale"
            elif self._is_plain_local() and local_token_authorized(self.headers):
                auth_mode = "local"
            else:
                return self.send_json(403, {"error": "tailscale or local auth required"})
        elif is_watch_tailscale_only:
            # Strict Tailscale-only enforcement: video bytes & streaming metadata never traverse Cloudflare Tunnel.
            if _is_public_api_request(self.headers):
                return self.send_json(403, TAILSCALE_REQUIRED_ERROR)
            login = self.headers.get("Tailscale-User-Login")
            if login and login in ALLOWED_LOGINS:
                auth_mode = "tailscale"
            elif self._is_plain_local() and local_token_authorized(self.headers):
                auth_mode = "local"
            else:
                return self.send_json(403, TAILSCALE_REQUIRED_ERROR)
        else:
            login = self.headers.get("Tailscale-User-Login")
            if login:
                if login in ALLOWED_LOGINS:
                    auth_mode = "tailscale"
            elif self._is_plain_local() and local_token_authorized(self.headers):
                auth_mode = "local"

            if auth_mode is None:
                is_api_path = path == "/pc/api" or path.startswith("/pc/api/")
                is_chat = (path == "/pc/api/chat" or path.startswith("/pc/api/chat/")) and not has_traversal
                if not login and is_api_path and api_token_authorized(self.headers):
                    auth_mode = "api_token"
                elif not login and is_chat and chat_mod.chat_token_authorized(self.headers):
                    auth_mode = "chat_token"

            if auth_mode is None:
                return self.send_json(403, {"error": "not allowed for this tailnet identity"})

        if not path.startswith("/pc"):
            return self.send_json(404, {"error": "not found"})

        path = path[3:]

        # Tailscale-only Watch video streaming, offline file downloads, and subtitle delivery (GET & HEAD)
        if method in ("GET", "HEAD"):
            head_only = method == "HEAD"
            if path.startswith("/api/watch/stream/"):
                if has_traversal:
                    return self.send_json(400, {"error": "invalid stream path"})
                rest = raw_path[len("/pc/api/watch/stream/"):]
                item_id, _, subpath = rest.partition("/")
                upstream = _watch_service().build_stream_upstream_url(item_id, subpath, u.query)
                if not upstream:
                    return self.send_json(400, {"error": "invalid stream target"})
                is_m3u8 = subpath.lower().endswith(".m3u8")
                return self._proxy_jellyfin_media(
                    upstream,
                    head_only=head_only,
                    sanitize_playlist=is_m3u8,
                    cache_control="no-store",
                )
            m = re.fullmatch(r"/api/watch/download/([^/]+)", path)
            if m:
                upstream = _watch_service().build_download_upstream_url(m.group(1), u.query)
                if not upstream:
                    return self.send_json(400, {"error": "invalid download item id"})
                return self._proxy_jellyfin_media(
                    upstream,
                    head_only=head_only,
                    cache_control="no-store",
                    timeout=120,
                )
            m = re.fullmatch(r"/api/watch/subtitles/([^/]+)/([^/]+)/(\d+)\.(vtt|srt)", path, re.IGNORECASE)
            if m:
                ext = m.group(4).lower()
                upstream = _watch_service().build_subtitle_upstream_url(m.group(1), m.group(2), m.group(3), ext)
                if not upstream:
                    return self.send_json(400, {"error": "invalid subtitle target"})
                ctype = "text/vtt; charset=utf-8" if ext == "vtt" else "application/x-subrip; charset=utf-8"
                return self._proxy_jellyfin_media(
                    upstream,
                    head_only=head_only,
                    content_type_override=ctype,
                    cache_control="private, max-age=3600",
                    timeout=30,
                )
            m = re.fullmatch(r"/api/watch/trickplay/([^/]+)/(\d+)/(\d+)\.jpg", path, re.IGNORECASE)
            if m:
                if has_traversal:
                    return self.send_json(400, {"error": "invalid trickplay path"})
                ms_id = (qs.get("mediaSourceId") or [None])[0]
                upstream = _watch_service().build_trickplay_upstream_url(
                    m.group(1), m.group(2), m.group(3), media_source_id=ms_id
                )
                if not upstream:
                    return self.send_json(400, {"error": "invalid trickplay target"})
                return self._proxy_jellyfin_media(
                    upstream,
                    head_only=head_only,
                    content_type_override="image/jpeg",
                    cache_control="public, max-age=86400",
                    timeout=20,
                )

        # Proxied local auxiliary services under /pc/api/* (handled before JSON body parsing).
        if method == "GET" and path == "/api/vitals":
            return self._proxy_local("vitals", "GET", None, u.query)
        for proxy_key in ("detect", "ptz", "music"):
            prefix = f"/pc/api/{proxy_key}/"
            if raw_path.startswith(prefix) and method in ("GET", "POST"):
                return self._proxy_local(proxy_key, method, raw_path[len(prefix):], u.query)

        if method == "GET":
            if path == "/app.apk": return self.send_apk()
            if path == "/api/public-config":
                return self.send_json(200, public_config())
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
                if auth_mode == "api_token":
                    owner = next(iter(sorted(ALLOWED_LOGINS)), None) or "api-token"
                    who_login, who_name = owner, "API Token"
                elif auth_mode == "chat_token":
                    who_login, who_name = "chat-token", "Chat Token"
                else:
                    who_login = self.headers.get("Tailscale-User-Login")
                    who_name = self.headers.get("Tailscale-User-Name")
                return self.send_json(200, {
                    "login": who_login,
                    "name": who_name,
                    "auth": auth_mode,
                    "proxied": bool(self.headers.get("X-Forwarded-For")),
                })
            if path == "/api/routes":
                return self.send_json(200, {"routes": list_routes(qs.get("all", ["0"])[0] == "1")})
            if path == "/api/services": return self.send_json(200, {"services": list_services()})
            if path == "/api/health": return self.send_json(200, health_report())
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
            if path == "/api/space/jobs/stream": return self.sse_space_jobs()
            if path == "/api/crash": return self.send_json(200, {"crashes": CRASHES.list()})
            if path == "/api/downloads":
                return self.send_json(200, {**DOWNLOADS.active(), "enabled": NOTIFY.wants("live")["app"],
                                            "ts": int(time.time())})
            if path == "/api/vpn": return self.send_json(200, VPN.status())
            if path == "/api/agent":
                try:
                    return self.send_json(200, AGENT.status())
                except Exception as e:
                    return self.send_json(502, {"error": clip(f"agent status failed: {e}", 240)})
            if path == "/api/agent/usage":
                refresh = qs.get("refresh", [""])[0] in ("1", "true", "yes")
                return self.send_json(*AGENT_USAGE.get(force_refresh=refresh))
            if path == "/api/agent/settings":
                return self.send_json(200, AGENT_SETTINGS.get())
            if path == "/api/catcam":
                return self.send_json(200, CATCAM.status())
            if path == "/api/catdetect/snaps":
                return self.send_json(
                    *list_catdetect_snaps(
                        cam=(qs.get("cam") or [None])[0],
                        limit=(qs.get("limit") or [None])[0],
                    )
                )
            if path.startswith("/api/catdetect/snaps/"):
                raw_name = path[len("/api/catdetect/snaps/"):]
                code, res = read_catdetect_snap(raw_name)
                if code != 200:
                    return self.send_json(code, res)
                return self.send_bytes(res, "image/jpeg", cache_control="public, max-age=86400")
            if path == "/api/agentview/state":
                return self.send_json(200, AGENTVIEW.status())
            if path == "/api/keyring":
                try:
                    return self.send_json(200, KEYRING.status())
                except Exception as e:
                    return self.send_json(502, {"error": f"keyring status failed: {type(e).__name__}"})
            if path == "/api/ota/trigger":
                return self.send_json(200, OTA_TRIGGER.get())
            if path.startswith("/api/ota/"):
                ota_name = path[len("/api/ota/"):]
                if ota_content_type(ota_name):
                    refresh = qs.get("refresh", [""])[0] in ("1", "true", "yes")
                    return self.send_ota_file(ota_name, refresh=refresh)
            if path == "/api/chat/messages":
                return self.send_json(*_chat_manager().handle_get_messages(qs))
            if path == "/api/chat/events":
                return self.sse_chat()
            if path == "/api/chat/agent":
                return self.send_json(200, AGENT_SETTINGS.get())
            if path == "/api/chat/link-preview":
                return self.send_json(*link_preview_mod.handle_link_preview((qs.get("url") or [""])[0]))
            m = re.fullmatch(r"/api/chat/file/([0-9a-f]{16})", path)
            if m:
                res = _chat_manager().handle_get_file(m.group(1))
                if res is None:
                    return self.send_json(404, {"error": "attachment not found"})
                data, ctype, filename = res
                return self.send_bytes(
                    data,
                    ctype,
                    extra=[
                        ("X-Content-Type-Options", "nosniff"),
                        ("Content-Disposition", f'inline; filename="{filename}"'),
                    ],
                )
            m = re.fullmatch(r"/api/jellyfin/image/([^/]+)", path)
            if m:
                code, data_or_err, ctype, etag = jellyfin_proxy_image(
                    m.group(1),
                    image_type_name=(qs.get("type") or ["Primary"])[0],
                    series_id=(qs.get("seriesId") or [None])[0],
                    max_width=(qs.get("maxWidth") or [None])[0],
                    quality=(qs.get("quality") or [None])[0],
                    include_etag=True,
                    image_index=(qs.get("imageIndex") or [None])[0],
                )
                if code != 200:
                    return self.send_json(code, data_or_err)
                extra_hdrs = [("X-Content-Type-Options", "nosniff")]
                if etag:
                    extra_hdrs.append(("ETag", etag))
                return self.send_bytes(
                    data_or_err,
                    ctype,
                    extra=extra_hdrs,
                    cache_control="public, max-age=86400",
                )
            r4 = route_round4_get(path, qs)
            if r4 is not None:
                return self.send_json(*r4)
        elif method == "POST":
            m = re.fullmatch(r"/api/hook/(radarr|sonarr|seerr)", path)
            if m: return self.hook(m.group(1))
            limit = {
                "/api/notify": 16 * 1024 * 1024,
                "/api/crash": 2 * 1024 * 1024,
                "/api/chat/upload": 22 * 1024 * 1024,
            }.get(path, 64 * 1024)
            try: b = self.body(limit)
            except ValueError as e:
                return self.send_json(400, {"error": "invalid request body" if path == "/api/keyring/unlock" else str(e)})
            m = re.fullmatch(r"/api/services/([^/]+)/(start|stop|restart)", path)
            if m: return self.send_json(*service_action(m.group(1), m.group(2)))
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
            if path == "/api/vpn":
                if not isinstance(b.get("enabled"), bool):
                    return self.send_json(400, {"error": "enabled must be true or false"})
                try:
                    return self.send_json(200, VPN.set_enabled(b["enabled"]))
                except Exception as e:
                    return self.send_json(502, {"error": clip(f"qBittorrent did not take the change: {e}", 240)})
            if path == "/api/agent":
                return self.send_json(*AGENT.switch(b.get("engine")))
            if path == "/api/agent/settings":
                return self.send_json(*AGENT_SETTINGS.update(b))
            if path == "/api/agent/reset":
                return self.send_json(*agent_reset())
            if path == "/api/catcam":
                return self.send_json(*CATCAM.set_enabled(b.get("enabled")))
            if path == "/api/agentview/toggle":
                return self.send_json(*AGENTVIEW.toggle(b.get("enabled")))
            if path == "/api/keyring/unlock":
                pw = b.get("password")
                b.clear()
                return self.send_json(*KEYRING.unlock(pw))
            if path == "/api/enroll":
                return self.send_json(*rootd_or_503({"op": "enroll_request", "name": str(b.get("name", "phone")),
                                                     "pubkey": str(b.get("pubkey", ""))}))
            if path == "/api/ota/trigger":
                return self.send_json(*OTA_TRIGGER.trigger(b))
            if path == "/api/chat/upload":
                return self.send_json(*_chat_manager().handle_upload(b))
            if path == "/api/chat/send":
                return self.send_json(*_chat_manager().handle_send(b))
            if path == "/api/chat/steer":
                return self.send_json(*_chat_manager().handle_steer(b))
            if path == "/api/chat/queued/delete":
                return self.send_json(*_chat_manager().handle_delete_queued(b))
            if path == "/api/chat/push":
                return self.send_json(*_chat_manager().handle_push(b))
            if path == "/api/chat/new":
                return self.send_json(*_chat_manager().handle_new(b))
            if path == "/api/chat/stop":
                return self.send_json(*_chat_manager().handle_stop(b))
            if path == "/api/chat/agent":
                return self.send_json(*AGENT_SETTINGS.update(b))
            if path == "/api/chat/agent/switch":
                return self.send_json(*AGENT.switch(b.get("engine")))
            r4 = route_round4_post(path, b)
            if r4 is not None:
                return self.send_json(*r4)
        return self.send_json(404, {"error": "not found"})

    def send_bytes(self, data, ctype, extra=(), cache_control="no-store"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache_control)
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
        if source in ("radarr", "sonarr") and p.get("eventType") == "Download":
            if BAZARR is not None:
                BAZARR.trigger(source)
            return self.send_json(200, {"ok": True, "bazarr": BAZARR is not None})
        if not n: return self.send_json(200, {"ok": True, "ignored": True})
        img = NOTIFY.fetch_poster(n["image_url"]) if n.get("image_url") else None
        return self.send_json(*NOTIFY.send(n["category"], n["title"], n["body"], None, img, n.get("link")))

    def send_ota_file(self, filename, refresh=False):
        ctype = ota_content_type(filename)
        if not ctype:
            return self.send_json(404, {"error": f"no OTA resource {filename}"})
        if filename in OTA_MANIFEST_FILES:
            code, payload = OTA_PROXY.fetch_manifest(filename, refresh=refresh)
            if code != 200:
                return self.send_json(code, payload)
            return self.send_bytes(payload, ctype)
        code, resp_or_err = OTA_PROXY.open_stream(filename)
        if code != 200:
            return self.send_json(code, resp_or_err)
        try:
            with resp_or_err as resp:
                clen = resp.headers.get("Content-Length") if hasattr(resp, "headers") and resp.headers else None
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                if clen and str(clen).isdigit():
                    self.send_header("Content-Length", str(clen))
                if filename == "apk/pc-control.apk":
                    self.send_header("Content-Disposition", 'attachment; filename="pc-control.apk"')
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                while True:
                    chunk = resp.read(256 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

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
            self.write_event("hello", {"rootd": st, "pending": pending, "ota_trigger": OTA_TRIGGER.get(), "ts": int(time.time())})
            self.wfile.write(b"retry: 3000\n\n"); self.wfile.flush()
            while True:
                try:
                    msg = q.get(timeout=SSE_KEEPALIVE_SEC)
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    continue
                if msg is None: break
                self.write_event(*msg)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            HUB.unsubscribe(q)

    def sse_space_jobs(self):
        mgr = _gdrive_offload_manager()
        q = mgr.subscribe()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.close_connection = True
            self.write_event("snapshot", {"jobs": mgr.snapshot_jobs(), "ts": int(time.time())})
            self.wfile.write(b"retry: 3000\n\n")
            self.wfile.flush()
            while True:
                try:
                    msg = q.get(timeout=SSE_KEEPALIVE_SEC)
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    continue
                if msg is None:
                    break
                self.write_event(*msg)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            mgr.unsubscribe(q)

    def sse_chat(self):
        mgr = _chat_manager()
        q = mgr.subscribe()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.close_connection = True
            self.write_event("snapshot", mgr.snapshot_state())
            self.wfile.write(b"retry: 3000\n\n")
            self.wfile.flush()
            while True:
                try:
                    msg = q.get(timeout=SSE_KEEPALIVE_SEC)
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    continue
                if msg is None:
                    break
                self.write_event(*msg)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            mgr.unsubscribe(q)

    def write_event(self, event, data):
        self.wfile.write(f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode())
        self.wfile.flush()

    def do_GET(self):
        try: self.route("GET")
        except Exception as e: self.fail(e)

    def do_HEAD(self):
        try: self.route("HEAD")
        except Exception as e: self.fail(e)

    def do_POST(self):
        try: self.route("POST")
        except Exception as e: self.fail(e)

    def fail(self, e):
        if self.path.rstrip("/") == "/pc/api/keyring/unlock":
            print(f"pc-api error on {self.command} /pc/api/keyring/unlock: {type(e).__name__}", file=sys.stderr)
        else:
            print(f"pc-api error on {self.command} {self.path}: {type(e).__name__}: {e}", file=sys.stderr)
        try: self.send_json(500, {"error": f"internal: {type(e).__name__}"})
        except Exception: pass


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64


def main():
    global HUB, PUSH, NOTIFY, DOWNLOADS, WATCHER, VPN, USAGE_ALERTS, KEYRING, CHAT, BAZARR
    PUSH = Push()
    NOTIFY = Notify(PUSH)
    CHAT = chat_mod.ChatManager(notify=NOTIFY)
    DOWNLOADS = Downloads()
    BAZARR = bazarr_mod.BazarrTrigger()
    VPN = VpnManager(NOTIFY, qbt=DOWNLOADS).start()
    USAGE_ALERTS = UsageAlertWatcher(NOTIFY, AGENT_USAGE, AGENT_SETTINGS)
    AGENT_USAGE.on_update = USAGE_ALERTS.evaluate
    USAGE_ALERTS.start()
    KEYRING = KeyringManager(NOTIFY)
    hook_secret()
    local_token()
    HUB = Hub()
    WATCHER = ServiceWatcher(NOTIFY).start()
    KEYRING.notify_if_locked_on_startup()
    srv = Server(("127.0.0.1", PORT), H)
    print(f"pc-api on 127.0.0.1:{PORT}, allowed tailnet logins: {sorted(ALLOWED_LOGINS) or 'NONE'}, "
          f"FCM {'configured' if PUSH.configured() else 'NOT configured'}, {len(PUSH.tokens)} push token(s)", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
