#!/usr/bin/python3 -I
"""pc-api: the pc-control app's backend (user service, 127.0.0.1:1990, tailnet via `tailscale serve /pc`).

GET  /pc/api/info                     host, rootd status, latest APK info
GET  /pc/api/routes[?all=1]           tailscale serve routes as friendly cards (API-only ones hidden)
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
POST /pc/api/notify                   {title, body}: a plain notification to the phone(s) via FCM
GET  /pc/app.apk                      latest app build

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
                        if PUSH: PUSH.on_root_event(t, ev["request"])
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

    def notify(self, nid, title, body):
        self.enqueue({"kind": "notify", "id": nid, "title": title, "body": body}, True, 3600)


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
                        "target": target, "kind": kind, "hidden": bool(o.get("hidden", kind == "api"))})
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

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 64 * 1024: raise ValueError("body too large")
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
        elif method == "POST":
            try: b = self.body()
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
                title, body = clip(b.get("title"), 120), clip(b.get("body"), 500)
                if not title: return self.send_json(400, {"error": "title required"})
                nid = secrets.token_hex(8)
                PUSH.notify(nid, title, body)
                return self.send_json(200, {"ok": True, "id": nid})
            if path == "/api/enroll":
                return self.send_json(*rootd_or_503({"op": "enroll_request", "name": str(b.get("name", "phone")),
                                                     "pubkey": str(b.get("pubkey", ""))}))
        return self.send_json(404, {"error": "not found"})

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
    global HUB, PUSH
    PUSH = Push()
    HUB = Hub()
    srv = Server(("127.0.0.1", PORT), H)
    print(f"pc-api on 127.0.0.1:{PORT}, allowed tailnet logins: {sorted(ALLOWED_LOGINS) or 'NONE'}, "
          f"FCM {'configured' if PUSH.configured() else 'NOT configured'}, {len(PUSH.tokens)} push token(s)", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
