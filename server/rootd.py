#!/usr/bin/python3 -I
"""rootd: runs root commands only after a fingerprint-signed approval from an enrolled phone.

Runs as root (installed to /usr/local/lib/rootd/rootd.py by install-rootd.sh).
Unix socket /run/rootd/rootd.sock, newline-delimited JSON, one request per connection
(except "watch", which streams events).
Clients: the configured user (the agent, rootctl and pc-api) and uid 0 (admin: enrollment, revocation).

Security model:
- The agent can submit a command, but only a signature from an enrolled phone key releases it.
- The phone key is an Android Keystore EC P-256 key that needs a strong biometric for every use.
- The phone signs a payload it builds from the argv it shows, so a request can't be swapped
  behind the screen: rootd checks the signature against the argv it stored itself.
- Approvals are single-use (state machine pending -> running/denied), bound to a random nonce, and expire.
- Enrolling a phone key requires uid 0: Ayush runs `sudo rootd-admin enroll` and types the code the
  phone computed from its own key. The agent can only propose keys, never confirm them.

Test mode: when NOT running as root, ROOTD_TEST_DIR / ROOTD_TEST_UID / ROOTD_TEST_ADMIN_UID override the
paths and the allowed uids. Running as root with any ROOTD_TEST_* variable set is refused.
"""
import base64, hashlib, json, os, pwd, queue, re, secrets, selectors, shutil, signal, socket, struct
import subprocess, sys, threading, time, unicodedata

VERSION = 2
EXPIRY = 180            # seconds a request waits for approval
MAX_PENDING = 5
MAX_ENROLL_PENDING = 8
MAX_ARGV_BYTES = 32 * 1024
MAX_REASON = 500
MAX_OUT = 256 * 1024    # bytes kept per stream (head + tail)
DEFAULT_TIMEOUT = 600
MAX_TIMEOUT = 3600
MAX_CLIENTS = 64
HISTORY_KEEP = 200
SAFE_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
SAFE_ENV = {"PATH": SAFE_PATH, "HOME": "/root", "USER": "root", "LOGNAME": "root", "SHELL": "/bin/sh",
            "LANG": "C.UTF-8", "DEBIAN_FRONTEND": "noninteractive", "TERM": "dumb"}
TERMINAL = ("done", "denied", "expired", "cancelled", "lost")


# ---------------------------------------------------------------- configuration

class Cfg:
    conf = "/etc/rootd"
    state = "/var/lib/rootd"
    run = "/run/rootd"
    logdir = "/var/log/rootd"
    admin_uid = 0
    allowed_uid = None   # set at startup from settings.json ("user")
    test = False

    @classmethod
    def paths(cls, base=None):
        if base:
            cls.conf, cls.state, cls.run, cls.logdir = (os.path.join(base, d) for d in ("etc", "state", "run", "log"))

    @classmethod
    def sock(cls): return os.path.join(cls.run, "rootd.sock")
    @classmethod
    def devices(cls): return os.path.join(cls.conf, "devices.json")
    @classmethod
    def settings(cls): return os.path.join(cls.conf, "settings.json")
    @classmethod
    def audit(cls): return os.path.join(cls.logdir, "audit.jsonl")
    @classmethod
    def history(cls): return os.path.join(cls.state, "history.jsonl")


def configure_from_env():
    test_vars = {k: v for k, v in os.environ.items() if k.startswith("ROOTD_TEST_")}
    if os.geteuid() == 0:
        if test_vars:
            sys.exit("rootd: refusing ROOTD_TEST_* overrides when running as root")
        return
    if "ROOTD_TEST_DIR" not in test_vars:
        return
    Cfg.test = True
    Cfg.paths(test_vars["ROOTD_TEST_DIR"])
    if "ROOTD_TEST_UID" in test_vars: Cfg.allowed_uid = int(test_vars["ROOTD_TEST_UID"])
    if "ROOTD_TEST_ADMIN_UID" in test_vars: Cfg.admin_uid = int(test_vars["ROOTD_TEST_ADMIN_UID"])


def load_json(p, default):
    try:
        with open(p) as f: return json.load(f)
    except FileNotFoundError:
        return default


def save_json(p, data):
    tmp = p + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=1); f.flush(); os.fsync(f.fileno())
    os.replace(tmp, p)


audit_lock = threading.Lock()


def audit(event, **kw):
    rec = {"ts": round(time.time(), 3), "event": event, **kw}
    with audit_lock:
        fd = os.open(Cfg.audit(), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a") as f: f.write(json.dumps(rec, ensure_ascii=False) + "\n")


# ---------------------------------------------------------------- canonical payload

# Characters rejected in argv. JSON.stringify and json.dumps(ensure_ascii=False) agree on every other
# code point (they only differ on control characters and lone surrogates). Format characters (bidi
# overrides, zero-width chars) are rejected too, so the command the phone shows is the command that runs.
def bad_char(ch):
    if ch in "\t\n": return False
    cat = unicodedata.category(ch)
    return cat in ("Cc", "Cf", "Cs", "Zl", "Zp")


def check_argv(argv):
    if not (isinstance(argv, list) and argv and all(isinstance(a, str) for a in argv)):
        return "argv must be a non-empty list of strings"
    if len(argv) > 512:
        return "argv has too many elements"
    for a in argv:
        if any(bad_char(ch) for ch in a):
            return "argv contains a control/format character (only tab and newline are allowed)"
    if len(canon_argv(argv)) > MAX_ARGV_BYTES:
        return "argv too long"
    if not argv[0]:
        return "empty command"
    return None


def canon_argv(argv):
    # Must equal the app's new TextEncoder().encode(JSON.stringify(argv)).
    return json.dumps(argv, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def argv_hash(argv):
    return hashlib.sha256(canon_argv(argv)).hexdigest()


def payload(req, decision):
    assert decision in ("approve", "deny")
    return (f"rootd-v1\n{decision}\n{req['id']}\n{req['nonce']}\n{req['expires']}\n"
            f"{argv_hash(req['argv'])}").encode()


def load_key(pem):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    k = serialization.load_pem_public_key(pem.encode())
    if not isinstance(k, ec.EllipticCurvePublicKey) or k.curve.name != "secp256r1":
        raise ValueError("key must be EC P-256")
    return k


def key_fp(pem):
    """Short code shown in the app and typed into `rootd-admin enroll`: first 64 bits of SHA-256(DER SPKI)."""
    from cryptography.hazmat.primitives import serialization
    der = load_key(pem).public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    h = hashlib.sha256(der).hexdigest().upper()
    return "-".join(h[i:i + 4] for i in range(0, 16, 4))


def norm_fp(s):
    h = re.sub(r"[^0-9A-F]", "", str(s).upper())
    return "-".join(h[i:i + 4] for i in range(0, 16, 4)) if len(h) == 16 else None


def verify(pem, sig_b64, data):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    try:
        load_key(pem).verify(base64.b64decode(sig_b64, validate=True), data, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def printable(s, n):
    """Sanitize agent-supplied text that a human will read (names, reasons): no control/format chars."""
    s = "".join(ch if not bad_char(ch) or ch == " " else " " for ch in str(s).replace("\t", " ").replace("\n", " "))
    return s.strip()[:n]


# ---------------------------------------------------------------- state

lock = threading.Condition()
requests = {}         # id -> request dict (pending, running, and recent finished)
enroll_pending = {}   # fp -> {name, pubkey, created}
watchers = set()      # Watcher objects
history = []          # finished requests (public + output), newest last, persisted


class Watcher:
    """Event stream to one client. Never blocks the caller: a slow client gets dropped."""
    def __init__(self, conn):
        self.conn, self.q = conn, queue.Queue(maxsize=500)
        threading.Thread(target=self.pump, daemon=True).start()

    def push(self, line):
        try: self.q.put_nowait(line)
        except queue.Full: self.close()

    def pump(self):
        try:
            self.conn.settimeout(30)
            while True:
                line = self.q.get()
                if line is None: break
                self.conn.sendall(line)
        except Exception:
            pass
        finally:
            self.close()

    def close(self):
        with lock: watchers.discard(self)
        try: self.q.put_nowait(None)
        except queue.Full: pass
        try: self.conn.shutdown(socket.SHUT_RDWR)
        except Exception: pass
        try: self.conn.close()
        except Exception: pass


def emit(ev):
    line = (json.dumps(ev, ensure_ascii=False) + "\n").encode()
    for w in list(watchers): w.push(line)


def public(req):
    r = {k: req[k] for k in ("id", "argv", "reason", "nonce", "created", "expires", "status", "timeout", "uid")}
    r["argv_sha256"] = argv_hash(req["argv"])
    for k in ("exit", "device", "decided", "started", "finished", "timed_out", "out_bytes", "err_bytes"):
        if k in req: r[k] = req[k]
    return r


def full(req):
    r = public(req)
    for k in ("stdout", "stderr"):
        if k in req: r[k] = req[k]
    return r


def record_history(req):
    rec = full(req)
    history.append(rec)
    del history[:-HISTORY_KEEP]
    try:
        fd = os.open(Cfg.history(), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a") as f: f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        if os.path.getsize(Cfg.history()) > 32 * 1024 * 1024:   # compact
            tmp = Cfg.history() + ".tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                for h in history: f.write(json.dumps(h, ensure_ascii=False) + "\n")
            os.replace(tmp, Cfg.history())
    except OSError as e:
        print(f"rootd: history write failed: {e}", file=sys.stderr)


def running_marker(rid):
    return os.path.join(Cfg.state, f"running-{rid}.json")


def load_history():
    try:
        with open(Cfg.history()) as f:
            for line in f:
                try: history.append(json.loads(line))
                except ValueError: pass
    except FileNotFoundError:
        pass
    del history[:-HISTORY_KEEP]
    # A request that was running when rootd stopped was killed with it (systemd KillMode=control-group).
    for name in os.listdir(Cfg.state):
        if name.startswith("running-") and name.endswith(".json"):
            path = os.path.join(Cfg.state, name)
            try:
                req = load_json(path, None)
                if req:
                    req.update(status="lost", finished=time.time(), stderr="[rootd: stopped while this was running]")
                    record_history(req)
                    audit("lost", id=req["id"])
            finally:
                os.unlink(path)


def finish(req, status):
    """Move a request to a terminal state. Caller holds lock."""
    req["status"] = status
    req.setdefault("finished", time.time())
    record_history(req)
    try: os.unlink(running_marker(req["id"]))
    except FileNotFoundError: pass
    emit({"type": "update", "request": public(req)})
    lock.notify_all()


def housekeeping_loop():
    n = 0
    while True:
        time.sleep(1)
        n += 1
        now = time.time()
        with lock:
            for r in list(requests.values()):
                if r["status"] == "pending" and now > r["expires"]:
                    audit("expired", id=r["id"])
                    finish(r, "expired")
            for rid in [k for k, r in requests.items() if r["status"] in TERMINAL and now - r["finished"] > 600]:
                del requests[rid]
            for fp in [f for f, e in enroll_pending.items() if now - e["created"] > 900]:
                del enroll_pending[fp]
            if n % 20 == 0:
                emit({"type": "ping", "ts": int(now)})


# ---------------------------------------------------------------- running a command

class Capped:
    """Keeps the first and last MAX_OUT/2 bytes of a stream."""
    def __init__(self):
        self.head, self.tail, self.total = bytearray(), bytearray(), 0

    def add(self, b):
        self.total += len(b)
        half = MAX_OUT // 2
        room = half - len(self.head)
        if room > 0:
            self.head += b[:room]; b = b[room:]
        if b:
            self.tail += b
            if len(self.tail) > half: del self.tail[:-half]

    def text(self):
        omitted = self.total - len(self.head) - len(self.tail)
        mid = f"\n[rootd: {omitted} bytes omitted]\n".encode() if omitted > 0 else b""
        return (bytes(self.head) + mid + bytes(self.tail)).decode("utf-8", errors="replace")


def execute(argv, timeout):
    """Run argv as root with a clean env, cwd /, stdin /dev/null; kill the whole process group on timeout."""
    out, err = Capped(), Capped()
    try:
        p = subprocess.Popen(argv, cwd="/", env=SAFE_ENV, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, close_fds=True, start_new_session=True, umask=0o022)
    except Exception as e:
        return {"exit": 127, "stdout": "", "stderr": f"[rootd: failed to start: {e}]", "out_bytes": 0, "err_bytes": 0}
    sel = selectors.DefaultSelector()
    for f, buf in ((p.stdout, out), (p.stderr, err)):
        os.set_blocking(f.fileno(), False)
        sel.register(f, selectors.EVENT_READ, buf)
    deadline, timed_out, exited_at, killed = time.monotonic() + timeout, False, None, 0

    def kill(sig):
        try: os.killpg(p.pid, sig)
        except ProcessLookupError: pass

    while sel.get_map():
        for key, _ in sel.select(timeout=0.5):
            try: chunk = os.read(key.fileobj.fileno(), 65536)
            except BlockingIOError: continue
            if chunk: key.data.add(chunk)
            else: sel.unregister(key.fileobj); key.fileobj.close()
        now = time.monotonic()
        if p.poll() is not None and exited_at is None:
            exited_at = now
        if exited_at is not None and now - exited_at > 3:
            break        # main process is gone but something (a daemonized child) still holds the pipes
        if now > deadline and not timed_out:
            timed_out = True; kill(signal.SIGTERM); killed = now
        if timed_out and killed and now - killed > 5:
            kill(signal.SIGKILL); killed = None
    for key in list(sel.get_map().values()):
        sel.unregister(key.fileobj); key.fileobj.close()
    if timed_out: kill(signal.SIGKILL)
    code = p.wait()
    res = {"exit": code, "stdout": out.text(), "stderr": err.text(), "out_bytes": out.total, "err_bytes": err.total}
    if timed_out:
        res["timed_out"] = True
        res["stderr"] += f"\n[rootd: timed out after {timeout}s, killed]"
    return res


def run_request(req):
    t0 = time.time()
    res = execute(req["argv"], req["timeout"])
    with lock:
        req.update(res)
        audit("finished", id=req["id"], exit=res["exit"], secs=round(time.time() - t0, 1),
              timed_out=res.get("timed_out", False), out_bytes=res["out_bytes"], err_bytes=res["err_bytes"])
        finish(req, "done")


# ---------------------------------------------------------------- protocol

def resolve_argv(argv):
    cmd = argv[0]
    if "/" in cmd:
        if not cmd.startswith("/"):
            return None, "relative command paths are not allowed; use an absolute path or a bare name"
        return list(argv), None
    path = shutil.which(cmd, path=SAFE_PATH)
    if not path:
        return None, f"command not found in root PATH: {cmd}"
    return [path, *argv[1:]], None


def handle(msg, uid, conn=None):
    if not isinstance(msg, dict):
        return {"error": "bad message"}
    op = msg.get("op")
    agent = uid == Cfg.allowed_uid
    admin = uid == Cfg.admin_uid
    if not (agent or admin):
        return {"error": "forbidden"}

    if op == "ping":
        return {"ok": True, "version": VERSION, "admin": admin, "devices": len(load_json(Cfg.devices(), {})),
                "test": Cfg.test}

    if op == "submit":
        argv = msg.get("argv")
        e = check_argv(argv)
        if e: return {"error": e}
        argv, e = resolve_argv(argv)
        if e: return {"error": e}
        e = check_argv(argv)
        if e: return {"error": e}
        reason = printable(msg.get("reason", ""), MAX_REASON)
        try: timeout = max(1, min(int(msg.get("timeout", DEFAULT_TIMEOUT)), MAX_TIMEOUT))
        except (TypeError, ValueError): return {"error": "bad timeout"}
        with lock:
            if sum(r["status"] == "pending" for r in requests.values()) >= MAX_PENDING:
                return {"error": f"too many pending requests (max {MAX_PENDING})"}
            now = int(time.time())
            req = {"id": secrets.token_hex(8), "nonce": secrets.token_hex(16), "argv": argv, "reason": reason,
                   "created": now, "expires": now + EXPIRY, "status": "pending", "timeout": timeout, "uid": uid}
            requests[req["id"]] = req
            audit("submitted", id=req["id"], argv=argv, reason=reason, uid=uid, timeout=timeout)
            emit({"type": "request", "request": public(req)})
            return {"ok": True, "request": public(req)}

    if op == "list":
        with lock:
            return {"requests": sorted((public(r) for r in requests.values()), key=lambda r: -r["created"])}

    if op == "history":
        n = max(1, min(int(msg.get("limit", 50)), HISTORY_KEEP))
        with lock:
            latest = {}
            for h in history: latest[h["id"]] = h
            items = sorted(latest.values(), key=lambda r: -(r.get("finished") or r["created"]))[:n]
            if not msg.get("output"):
                items = [{k: v for k, v in h.items() if k not in ("stdout", "stderr")} for h in items]
            return {"history": items}

    if op in ("get", "wait"):
        rid = str(msg.get("id"))
        try: wait_for = min(max(float(msg.get("timeout", 0)), 0), 3600) if op == "wait" else 0
        except (TypeError, ValueError): return {"error": "bad timeout"}
        deadline = time.time() + wait_for
        with lock:
            while True:
                r = requests.get(rid)
                if not r:
                    for h in reversed(history):
                        if h["id"] == rid: return {"request": h}
                    return {"error": "unknown request"}
                if op == "get" or r["status"] in TERMINAL or time.time() >= deadline:
                    return {"request": full(r)}
                lock.wait(max(0.05, deadline - time.time()))

    if op == "cancel":
        with lock:
            r = requests.get(str(msg.get("id")))
            if not r: return {"error": "unknown request"}
            if r["status"] != "pending": return {"error": f"request is {r['status']}"}
            audit("cancelled", id=r["id"], uid=uid)
            finish(r, "cancelled")
            return {"ok": True}

    if op in ("approve", "deny"):
        # pc-api relays the phone's signature; only the signature counts, not who relays it.
        devs = load_json(Cfg.devices(), {})
        fp = str(msg.get("device"))
        dev = devs.get(fp)
        sig = str(msg.get("signature", ""))[:200]
        with lock:
            r = requests.get(str(msg.get("id")))
            if not r: return {"error": "unknown request"}
            if r["status"] != "pending": return {"error": f"request is {r['status']}"}
            if time.time() > r["expires"]:
                audit("expired", id=r["id"]); finish(r, "expired")
                return {"error": "request expired"}
            if not dev or not verify(dev["pubkey"], sig, payload(r, op)):
                audit("bad_signature", id=r["id"], device=fp, op=op, uid=uid)
                return {"error": "signature check failed"}
            r["device"], r["decided"] = dev["name"], time.time()
            if op == "deny":
                audit("denied", id=r["id"], device=dev["name"], fingerprint=fp, signature=sig)
                finish(r, "denied")
                return {"ok": True, "request": public(r)}
            r["status"], r["started"] = "running", time.time()
            save_json(running_marker(r["id"]), public(r))
            audit("approved", id=r["id"], device=dev["name"], fingerprint=fp, argv=r["argv"], signature=sig)
            emit({"type": "update", "request": public(r)}); lock.notify_all()
            threading.Thread(target=run_request, args=(r,), daemon=True).start()
            return {"ok": True, "request": public(r)}

    if op == "watch":
        if conn is None: return {"error": "watch needs a connection"}
        if len(watchers) >= 16: return {"error": "too many watchers"}
        w = Watcher(conn)
        with lock:
            watchers.add(w)
            w.push((json.dumps({"type": "hello", "version": VERSION,
                                "pending": [public(r) for r in requests.values() if r["status"] == "pending"]})
                    + "\n").encode())
        return "keep"

    if op == "enroll_request":
        # Anyone allowed may PROPOSE a key; only uid 0 can confirm it (enroll_confirm).
        name = printable(msg.get("name", "phone"), 60) or "phone"
        pem = str(msg.get("pubkey", ""))[:4096]
        try: fp = key_fp(pem)
        except Exception: return {"error": "bad public key (need EC P-256 SPKI PEM)"}
        if fp in load_json(Cfg.devices(), {}):
            return {"ok": True, "fingerprint": fp, "enrolled": True}
        with lock:
            if fp not in enroll_pending:
                while len(enroll_pending) >= MAX_ENROLL_PENDING:   # evict oldest; the app re-proposes while waiting
                    del enroll_pending[min(enroll_pending, key=lambda f: enroll_pending[f]["created"])]
                audit("enroll_requested", fingerprint=fp, name=name, uid=uid)
            enroll_pending[fp] = {"name": name, "pubkey": pem, "created": time.time()}
        return {"ok": True, "fingerprint": fp, "enrolled": False}

    if op == "enroll_status":
        fp = norm_fp(msg.get("fingerprint", ""))
        return {"enrolled": bool(fp) and fp in load_json(Cfg.devices(), {})}

    if op == "devices":
        return {"devices": [{"fingerprint": k, "name": v["name"], "added": v["added"]}
                            for k, v in load_json(Cfg.devices(), {}).items()]}

    # ---- admin only (uid 0) ----
    if not admin:
        return {"error": "unknown or forbidden op"}

    if op == "enroll_pending":
        with lock:
            return {"pending": [{"name": v["name"], "created": v["created"]} for v in enroll_pending.values()]}

    if op == "enroll_confirm":
        fp = norm_fp(msg.get("fingerprint", ""))
        with lock:
            e = enroll_pending.pop(fp, None) if fp else None
        if not e: return {"error": "no pending phone has that code"}
        name = printable(msg.get("name") or e["name"], 60) or "phone"
        devs = load_json(Cfg.devices(), {})
        devs[fp] = {"name": name, "pubkey": e["pubkey"], "added": time.time()}
        save_json(Cfg.devices(), devs)
        audit("enrolled", fingerprint=fp, name=name, uid=uid)
        emit({"type": "enrolled", "fingerprint": fp})
        return {"ok": True, "fingerprint": fp, "name": name}

    if op == "revoke":
        fp = norm_fp(msg.get("fingerprint", ""))
        devs = load_json(Cfg.devices(), {})
        d = devs.pop(fp, None) if fp else None
        if d:
            save_json(Cfg.devices(), devs)
            audit("revoked", fingerprint=fp, name=d["name"], uid=uid)
        return {"ok": bool(d)}

    return {"error": "unknown or forbidden op"}


def peer_uid(conn):
    cred = conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i"))
    _, uid, _ = struct.unpack("3i", cred)
    return uid


clients = threading.BoundedSemaphore(MAX_CLIENTS)


def client(conn, uid, slot):
    keep = False
    try:
        conn.settimeout(10)
        buf = b""
        while b"\n" not in buf:
            chunk = conn.recv(65536)
            if not chunk: return
            buf += chunk
            if len(buf) > 128 * 1024: return
        msg = json.loads(buf.split(b"\n", 1)[0])
        if isinstance(msg, dict) and msg.get("op") == "wait": conn.settimeout(None)
        res = handle(msg, uid, conn)
        if res == "keep":
            keep = True
            return
        conn.settimeout(30)
        conn.sendall((json.dumps(res, ensure_ascii=False) + "\n").encode())
    except Exception as e:
        try: conn.sendall((json.dumps({"error": f"internal: {type(e).__name__}"}) + "\n").encode())
        except Exception: pass
    finally:
        if not keep: conn.close()
        if slot: clients.release()


def serve():
    configure_from_env()
    if os.geteuid() != 0 and not Cfg.test:
        sys.exit("rootd must run as root (or with ROOTD_TEST_DIR for tests)")
    for d, mode in ((Cfg.conf, 0o700), (Cfg.state, 0o700), (Cfg.logdir, 0o700), (Cfg.run, 0o755)):
        os.makedirs(d, mode=mode, exist_ok=True)
    if Cfg.allowed_uid is None:
        Cfg.allowed_uid = pwd.getpwnam(load_json(Cfg.settings(), {}).get("user", "ayushlive")).pw_uid
    if Cfg.allowed_uid == Cfg.admin_uid:
        sys.exit("rootd: allowed user must not be the admin uid")
    load_history()
    sock = Cfg.sock()
    if os.path.exists(sock): os.unlink(sock)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    old = os.umask(0o177); s.bind(sock); os.umask(old)
    if not Cfg.test: os.chown(sock, Cfg.allowed_uid, 0)
    os.chmod(sock, 0o600)   # filesystem gate; SO_PEERCRED is checked on every connection as well
    s.listen(32)
    threading.Thread(target=housekeeping_loop, daemon=True).start()
    audit("started", version=VERSION, allowed_uid=Cfg.allowed_uid, test=Cfg.test)
    print(f"rootd v{VERSION} listening on {sock} (user uid {Cfg.allowed_uid})", flush=True)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    while True:
        c, _ = s.accept()
        try: uid = peer_uid(c)
        except OSError: c.close(); continue
        if uid not in (Cfg.admin_uid, Cfg.allowed_uid):
            audit("rejected_peer", uid=uid); c.close(); continue
        slot = uid != Cfg.admin_uid       # admin connections are never starved by agent connections
        if slot and not clients.acquire(blocking=False):
            c.close(); continue
        threading.Thread(target=client, args=(c, uid, slot), daemon=True).start()


# ---------------------------------------------------------------- rootd-admin (run by root)

def call(msg):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try: s.connect(Cfg.sock())
    except OSError as e: sys.exit(f"rootd is not reachable ({e}). Is it running? systemctl status rootd")
    s.sendall((json.dumps(msg) + "\n").encode())
    data = b""
    while not data.endswith(b"\n"):
        c = s.recv(65536)
        if not c: break
        data += c
    return json.loads(data or b'{"error":"no reply"}')


def admin_enroll():
    pend = call({"op": "enroll_pending"})
    if "error" in pend: sys.exit(pend["error"])
    print("On your phone: pc-control > Settings > Root approvals > Set up.")
    print("The app shows a 16-character code computed from the phone's own key.")
    if not pend["pending"]:
        print("(No phone is waiting yet. Tap Set up in the app first; you can still type the code once it is.)")
    else:
        print(f"{len(pend['pending'])} phone(s) waiting: " + ", ".join(p["name"] for p in pend["pending"]))
    for _ in range(3):
        try: typed = input("\nType the code shown in the app (or press Enter to skip): ")
        except EOFError: typed = ""
        if not typed.strip():
            print("Skipped. Run `sudo rootd-admin enroll` any time later.")
            return 1
        fp = norm_fp(typed)
        if not fp:
            print("That is not a 16-character code (like ABCD-1234-EF56-7890)."); continue
        res = call({"op": "enroll_confirm", "fingerprint": fp})
        if res.get("ok"):
            print(f"Enrolled {res['name']} ({res['fingerprint']}). Root approvals are now active.")
            return 0
        print(f"Not enrolled: {res.get('error')}. Check the code, or tap Set up again in the app.")
    return 1


def admin_cli(args):
    configure_from_env()
    if os.geteuid() != 0 and not Cfg.test: sys.exit("run with sudo: sudo rootd-admin " + " ".join(args))
    cmd = args[0] if args else ""
    if cmd == "enroll":
        sys.exit(admin_enroll())
    elif cmd == "devices":
        devs = call({"op": "devices"}).get("devices", [])
        if not devs: print("No enrolled phones.")
        for d in devs: print(f"{d['fingerprint']}  {d['name']}  (added {time.ctime(d['added'])})")
    elif cmd == "revoke" and len(args) == 2:
        res = call({"op": "revoke", "fingerprint": args[1]})
        print("Revoked." if res.get("ok") else "No enrolled phone has that code.")
    elif cmd == "status":
        print(json.dumps(call({"op": "ping"})))
        for r in call({"op": "list"}).get("requests", []):
            print(r["id"], r["status"], json.dumps(r["argv"], ensure_ascii=False))
    else:
        print("usage: sudo rootd-admin enroll | devices | revoke <code> | status")
        sys.exit(2)


if __name__ == "__main__":
    if sys.argv[1:2] == ["serve"]: serve()
    else: admin_cli(sys.argv[1:])
