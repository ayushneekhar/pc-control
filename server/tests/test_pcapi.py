"""pc-api route classification (API-only routes hidden server-side, overridable via routes.json)."""
import atexit, json, os, shutil, struct, sys, tempfile, threading, time, unittest, urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402

_TEST_TOK_DIR = tempfile.mkdtemp(prefix="pcapi-test-tok-")
atexit.register(shutil.rmtree, _TEST_TOK_DIR, True)
pcapi.LOCAL_TOKEN_FILE = os.path.join(_TEST_TOK_DIR, "local-token")


def _with_local_auth(headers=None, path=""):
    h = dict(headers or {})
    if ("Tailscale-User-Login" not in h and "Authorization" not in h and "X-PC-Local-Token" not in h
            and not path.startswith("/pc/api/hook/")):
        h["Authorization"] = f"Bearer {pcapi.local_token()}"
    return h

H = "ayushlive-hp-pavilion.tail65e4a4.ts.net"
STATUS = {"Web": {
    f"{H}:443": {"Handlers": {
        "/": {"Proxy": "http://127.0.0.1:1984"}, "/cams/": {"Path": "/home/x/camsite"},
        "/detect": {"Proxy": "http://127.0.0.1:1986/detect"}, "/music": {"Proxy": "http://127.0.0.1:1988/music"},
        "/ptz": {"Proxy": "http://127.0.0.1:1985/ptz"}, "/rec": {"Proxy": "http://127.0.0.1:1987/rec"},
        "/vitals": {"Proxy": "http://127.0.0.1:1989/vitals"}, "/pc": {"Proxy": "http://127.0.0.1:1990/pc"},
        "/new-api": {"Proxy": "http://127.0.0.1:2001/new-api"}}},
    f"{H}:5055": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:5055"}}},
    f"{H}:6767": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:6767"}}},
    f"{H}:7878": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:7878"}}},
    f"{H}:8989": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8989"}}},
}}


def visible(overrides=None):
    return {r["name"] for r in pcapi.build_routes(STATUS, overrides or {}) if not r["hidden"]}


class Routes(unittest.TestCase):
    def test_defaults_hide_cat_cam_backends(self):
        self.assertEqual(visible(), {"Cat cams", "Jellyfin", "New Api"})
        hidden = {r["id"]: r["kind"] for r in pcapi.build_routes(STATUS, {}) if r["hidden"]}
        self.assertEqual(hidden, {"443/detect": "api", "443/music": "api", "443/ptz": "api", "443/rec": "api",
                                  "443/vitals": "api", "443/": "page", "5055/": "page", "6767/": "page",
                                  "7878/": "page", "8989/": "page"})
        self.assertNotIn("/pc", [r["path"] for r in pcapi.build_routes(STATUS, {})])

    def test_config_hides_new_route_and_unhides_builtin(self):
        v = visible({"/new-api": {"kind": "api"}, "/rec": {"hidden": False}, ":5055": {"hidden": True}})
        self.assertNotIn("New Api", v)
        self.assertNotIn("Seerr", v)
        self.assertIn("Recordings", v)
        self.assertNotIn("New Api", visible({"/new-api": {"hidden": True}}))

    def test_unhide_default_hidden_page(self):
        self.assertIn("Radarr", visible({":7878": {"hidden": False}}))
        self.assertIn("Cameras", visible({"/": {"hidden": False}}))

    def test_jellyfin_app_tile(self):
        j = next(r for r in pcapi.build_routes(STATUS, {}) if r["id"] == "jellyfin")
        self.assertEqual((j["app"], j["url"], j["kind"]), ("org.jellyfin.mobile", f"http://{H}:8096/", "app"))
        self.assertNotIn("Jellyfin", visible({"jellyfin": {"hidden": True}}))
        self.assertEqual(pcapi.build_routes({}, {}), [])

    def test_bad_override_ignored(self):
        self.assertNotIn("Vitals", visible({"/vitals": "nonsense"}))
        self.assertIn("Vitals", visible({"/vitals": {"hidden": False}}))


class FakeGoogle(BaseHTTPRequestHandler):
    """OAuth token endpoint + FCM v1 send endpoint. Records what pc-api sent."""
    log, tokens_issued, reject_once = [], [0], [False]

    def log_message(self, *a): pass

    def reply(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path == "/token":
            f = urllib.parse.parse_qs(body.decode())
            jwt = f["assertion"][0].split(".")
            assert len(jwt) == 3 and f["grant_type"][0].endswith("jwt-bearer")
            self.tokens_issued[0] += 1
            return self.reply(200, {"access_token": f"at{self.tokens_issued[0]}", "expires_in": 3599})
        m = json.loads(body)["message"]
        self.log.append((self.headers["Authorization"], m))
        if self.reject_once[0]:
            self.reject_once[0] = False
            return self.reply(401, {"error": {"status": "UNAUTHENTICATED"}})
        if m["token"].startswith("dead"):
            return self.reply(404, {"error": {"status": "NOT_FOUND", "details": [
                {"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": "UNREGISTERED"}]}})
        if m["token"].startswith("flaky"):
            return self.reply(503, {"error": {"status": "UNAVAILABLE"}})
        self.reply(200, {"name": "projects/p/messages/1"})


class PushTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeGoogle)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        cls.dir = tempfile.mkdtemp()
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048).private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
        cls.sa = os.path.join(cls.dir, "sa.json")
        with open(cls.sa, "w") as f:
            json.dump({"type": "service_account", "project_id": "p", "private_key_id": "k1", "private_key": key,
                       "client_email": "x@p.iam.gserviceaccount.com", "token_uri": base + "/token"}, f)
        cls.base = base

    def setUp(self):
        FakeGoogle.log.clear()
        self.data = tempfile.mkdtemp(dir=self.dir)
        self.push = pcapi.Push(sa_path=self.sa, data_dir=self.data, api=self.base)
        self.good = "good:" + "a" * 40

    def test_register_validates_and_persists_0600(self):
        self.assertEqual(self.push.register("bad token!", "x")[0], 400)
        self.assertEqual(self.push.register(self.good, "Pixel")[0], 200)
        f = os.path.join(self.data, "push-tokens.json")
        self.assertEqual(os.stat(f).st_mode & 0o777, 0o600)
        self.assertTrue(pcapi.Push(sa_path=self.sa, data_dir=self.data, api=self.base).status(self.good)["registered"])
        for i in range(pcapi.MAX_TOKENS + 3): self.push.register(f"t{i:03d}:" + "b" * 30, "p")
        self.assertEqual(self.push.status()["devices"], pcapi.MAX_TOKENS)

    def test_send_drops_dead_tokens_keeps_others_and_caches_access_token(self):
        dead, flaky = "dead:" + "c" * 40, "flaky:" + "d" * 40
        for t in (self.good, dead, flaky): self.push.register(t, "p")
        issued = FakeGoogle.tokens_issued[0]
        res = {r["token"]: r for r in self.push.send_all({"kind": "notify", "id": "1", "title": "t", "body": "b"})}
        self.assertTrue(res[pcapi.short(self.good)]["ok"])
        self.assertEqual(res[pcapi.short(dead)]["error"], "UNREGISTERED")
        self.assertTrue(res[pcapi.short(dead)]["dropped"])
        self.assertEqual(res[pcapi.short(flaky)]["error"], "UNAVAILABLE")
        self.assertFalse(res[pcapi.short(flaky)]["dropped"])
        self.assertEqual(self.push.status()["devices"], 2)
        self.assertEqual(FakeGoogle.tokens_issued[0], issued + 1)        # one OAuth exchange for 3 sends
        auth, m = FakeGoogle.log[0]
        self.assertTrue(auth.startswith("Bearer at"))
        self.assertEqual(m["android"]["priority"], "HIGH")
        self.assertNotIn("notification", m)                             # data-only: the app displays it

    def test_401_refreshes_access_token_once(self):
        self.push.register(self.good, "p")
        self.push.send_all({"kind": "notify", "id": "1", "title": "t", "body": "b"})
        FakeGoogle.reject_once[0] = True
        before = FakeGoogle.tokens_issued[0]
        self.assertTrue(self.push.send_all({"kind": "notify", "id": "2", "title": "t", "body": "b"})[0]["ok"])
        self.assertEqual(FakeGoogle.tokens_issued[0], before + 1)

    def test_root_events_never_carry_the_command(self):
        self.push.register(self.good, "p")
        argv = ["/usr/bin/apt-get", "install", "-y", "SECRET-PKG"]
        req = {"id": "ab" * 8, "argv": argv, "reason": "install ffmpeg", "status": "pending",
               "expires": int(time.time()) + 180, "nonce": "NONCE123"}
        self.push.on_root_event("request", req)
        self.push.on_root_event("update", {**req, "status": "done", "exit": 0, "stdout": "OUTPUT"})
        self.push.on_root_event("update", {**req, "status": "denied"})
        for _ in range(100):
            if len(FakeGoogle.log) >= 3: break
            time.sleep(0.05)
        msgs = [m for _, m in FakeGoogle.log]
        self.assertEqual([m["data"]["kind"] for m in msgs], ["request", "result", "dismiss"])
        self.assertEqual(msgs[0]["data"]["title"], "Root approval needed")
        self.assertEqual(msgs[0]["data"]["body"], "install ffmpeg")
        self.assertEqual(msgs[2]["android"]["priority"], "NORMAL")
        blob = json.dumps(msgs)
        for secret in ("SECRET-PKG", "apt-get", "NONCE123", "OUTPUT"): self.assertNotIn(secret, blob)

    def test_unconfigured_is_quiet(self):
        p = pcapi.Push(sa_path=os.path.join(self.dir, "missing.json"), data_dir=self.data, api=self.base)
        p.register(self.good, "p")
        self.assertFalse(p.status()["configured"])
        p.notify("x", "t", "b")   # no-op, no exception
        self.assertEqual(p.q.qsize(), 0)


class FakeTelegram(BaseHTTPRequestHandler):
    """Bot API sendMessage/sendPhoto. Records (method, fields, has_photo)."""
    log, fail = [], [0]

    def log_message(self, *a): pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        method = self.path.rsplit("/", 1)[1]
        fields, photo = {}, None
        for part in body.split(b"--" + self.headers["Content-Type"].split("boundary=")[1].encode()):
            head, _, val = part.partition(b"\r\n\r\n")
            if b'name="' not in head: continue
            name = head.split(b'name="')[1].split(b'"')[0].decode()
            val = val[:-2]
            if name == "photo": photo = val
            else: fields[name] = val.decode()
        self.log.append((method, fields, photo, self.path.split("/")[1]))
        if self.fail[0]:
            self.fail[0] -= 1
            b = b'{"ok":false,"description":"Bad Gateway"}'
            self.send_response(502)
        else:
            b = b'{"ok":true,"result":{}}'
            self.send_response(200)
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)


JPEG = b"\xff\xd8\xff\xe0" + b"0" * 100


class RouterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tg = ThreadingHTTPServer(("127.0.0.1", 0), FakeTelegram)
        threading.Thread(target=cls.tg.serve_forever, daemon=True).start()
        cls.dir = tempfile.mkdtemp()
        cls.tok = os.path.join(cls.dir, "tok")
        with open(cls.tok, "w") as f: f.write("123456:ABCDEFGHIJKLMNOPQRSTUVWXYZ_abc\n")

    def setUp(self):
        FakeTelegram.log.clear(); FakeTelegram.fail[0] = 0
        self.data = tempfile.mkdtemp(dir=self.dir)
        self.push = FakePush()
        self.n = pcapi.Notify(self.push, data_dir=self.data, tg_api=f"http://127.0.0.1:{self.tg.server_address[1]}",
                              token_file=self.tok, chat="42")

    def test_defaults_match_previous_behaviour(self):
        s = self.n.settings()
        self.assertEqual(s["masters"], {"telegram": True, "app": True})
        d = {c["id"]: (c["telegram"], c["app"]) for c in s["categories"]}
        for cat in ("cats", "grabbed", "available", "problems", "qwen_done", "system"): self.assertTrue(d[cat][0], cat)
        for cat in ("root", "results", "other"): self.assertEqual(d[cat], (False, True), cat)
        self.assertTrue(s["channels"]["telegram"]["configured"])

    def test_routes_per_category_and_masters(self):
        code, r = self.n.send("system", "Backup failed", "disk full")
        self.assertEqual((code, r["telegram"], r["app"]), (200, "sent", "queued"))
        self.assertEqual(FakeTelegram.log[-1][:2], ("sendMessage", {"chat_id": "42", "text": "Backup failed\ndisk full"}))
        self.assertEqual(self.push.sent[-1][1:3], ("Backup failed", "disk full"))
        self.assertEqual(self.push.sent[-1][3]["cat"], "system")
        # per-category toggle
        self.assertEqual(self.n.update({"categories": {"system": {"telegram": False}}})[0], 200)
        code, r = self.n.send("system", "x")
        self.assertEqual((r["telegram"], r["app"]), ("off", "queued"))
        # master off beats category on
        self.n.update({"masters": {"app": False}})
        self.assertEqual(self.n.send("problems", "x")[1]["app"], "off")
        # persisted
        n2 = pcapi.Notify(self.push, data_dir=self.data, token_file=self.tok, chat="42")
        self.assertEqual(n2.wants("system"), {"telegram": False, "app": False})
        self.assertEqual(n2.wants("cats"), {"telegram": True, "app": False})

    def test_validation(self):
        self.assertEqual(self.n.update({"categories": {"nope": {"app": True}}})[0], 400)
        self.assertEqual(self.n.update({"categories": {"cats": {"app": "yes"}}})[0], 400)
        self.assertEqual(self.n.update({"masters": {"sms": True}})[0], 400)
        self.assertEqual(self.n.send("nope", "t")[0], 400)
        self.assertEqual(self.n.send("cats", "")[0], 400)
        self.assertEqual(self.n.send("cats", "t", image=b"not an image")[0], 400)
        self.assertEqual(self.n.send("cats", "t", link="javascript:alert(1)")[0], 400)

    def test_photo_goes_to_telegram_as_photo_and_app_gets_image_url(self):
        self.n.update({"categories": {"cats": {"app": True}}})
        code, r = self.n.send("cats", "🐱 Cat at Hall", telegram_text="🐱 Cat at Hall (91%)", image=JPEG)
        self.assertEqual(code, 200)
        method, fields, photo, _ = FakeTelegram.log[-1]
        self.assertEqual((method, fields["caption"], photo), ("sendPhoto", "🐱 Cat at Hall (91%)", JPEG))
        extra = self.push.sent[-1][3]
        self.assertEqual(extra["image"], f"/pc/api/notify/image/{r['id']}")
        self.assertEqual(self.n.image(r["id"]), JPEG)
        self.assertIsNone(self.n.image("../../etc/passwd"))

    def test_sighting_cam_and_ts_ride_in_the_app_push_only(self):
        self.n.update({"categories": {"cats": {"app": True}}})
        code, r = self.n.send("cats", "🐱 Cat at Bowl", "Confidence 91%", image=JPEG, cam="cat3", ts=1700000000123)
        self.assertEqual(code, 200)
        extra = self.push.sent[-1][3]
        self.assertEqual((extra["cat"], extra["cam"], extra["ts"]), ("cats", "cat3", 1700000000123))
        # Telegram is unchanged: the caption never carries the replay metadata
        self.assertEqual(FakeTelegram.log[-1][1]["caption"], "🐱 Cat at Bowl\nConfidence 91%")

    def test_bad_sighting_extras_are_dropped_without_failing_the_alert(self):
        self.n.update({"categories": {"cats": {"app": True}}})
        code, _ = self.n.send("cats", "Cat", cam="../etc/passwd", ts="not a time")
        self.assertEqual(code, 200)
        extra = self.push.sent[-1][3]
        self.assertNotIn("cam", extra)
        self.assertNotIn("ts", extra)
        self.assertEqual(self.n.send("cats", "Cat", ts=-5)[0], 200)
        self.assertNotIn("ts", self.push.sent[-1][3])

    def test_telegram_failure_is_502_and_token_never_leaks(self):
        FakeTelegram.fail[0] = 2
        code, r = self.n.send("system", "Backup failed")
        self.assertEqual(code, 502)
        self.assertFalse(r["ok"])
        self.assertNotIn("ABCDEFGHIJ", json.dumps(r))
        self.assertEqual(r["app"], "queued")            # the app push still went out

    def test_missing_token_is_502(self):
        n = pcapi.Notify(self.push, data_dir=self.data, token_file=os.path.join(self.dir, "missing"), chat="42")
        self.assertEqual(n.send("system", "x")[0], 502)
        self.assertFalse(n.settings()["channels"]["telegram"]["configured"])

    def test_root_events_respect_categories_and_never_carry_the_command(self):
        req = {"id": "ab" * 8, "argv": ["/usr/bin/apt", "SECRET-PKG"], "reason": "install ffmpeg",
               "status": "pending", "expires": int(time.time()) + 180}
        self.n.on_root_event("request", req)
        self.assertEqual(self.push.root[-1][0], "request")
        time.sleep(0.2)
        self.assertEqual(FakeTelegram.log, [])          # Telegram is off for root approvals by default
        self.n.update({"categories": {"root": {"telegram": True, "app": False}}})
        self.push.root.clear()
        self.n.on_root_event("request", req)
        for _ in range(50):
            if FakeTelegram.log: break
            time.sleep(0.05)
        self.assertEqual(self.push.root, [])
        text = FakeTelegram.log[-1][1]["text"]
        self.assertIn("install ffmpeg", text)
        self.assertNotIn("SECRET-PKG", text)
        self.n.on_root_event("update", {**req, "status": "denied"})     # dismissals still reach the app
        self.assertEqual(self.push.root[-1][0], "update")


class FakePush:
    def __init__(self): self.sent, self.root, self.tokens, self.queued = [], [], {"t": {}}, []
    def configured(self): return True
    def status(self, token=None): return {"configured": True, "devices": 1}
    def enqueue(self, data, high=True, ttl=3600): self.queued.append((data, high, ttl))
    def notify(self, nid, title, body, extra=None): self.sent.append((nid, title, body, extra or {}))
    def on_root_event(self, t, r): self.root.append((t, r))


class HookTests(unittest.TestCase):
    def test_secret_forms(self):
        import base64
        ok = lambda h: pcapi.hook_authorized(h, "s" * 40)
        self.assertTrue(ok({"Authorization": "Bearer " + "s" * 40}))
        self.assertTrue(ok({"Authorization": "s" * 40}))
        self.assertTrue(ok({"Authorization": "Basic " + base64.b64encode(b"pcapi:" + b"s" * 40).decode()}))
        self.assertTrue(ok({"X-PC-Hook-Secret": "s" * 40}))
        self.assertFalse(ok({}))
        self.assertFalse(ok({"Authorization": "Bearer wrong"}))
        self.assertFalse(ok({"Authorization": "Basic " + base64.b64encode(b"pcapi:wrong").decode()}))

    def test_radarr_sonarr_mapping(self):
        g = pcapi.map_hook("radarr", {"eventType": "Grab", "movie": {"title": "Dune", "year": 2021},
                                      "release": {"quality": "Bluray-1080p", "size": 12 * 1024 ** 3}})
        self.assertEqual(g["category"], "grabbed")
        self.assertIn("Dune (2021)", g["body"]); self.assertIn("12.0 GB", g["body"])
        s = pcapi.map_hook("sonarr", {"eventType": "Grab", "series": {"title": "Severance"},
                                      "episodes": [{"seasonNumber": 2, "episodeNumber": 3, "title": "Who Is Alive?"}],
                                      "release": {"quality": "WEBDL-1080p"}})
        self.assertIn("Severance S02E03 · Who Is Alive?", s["body"])
        m = pcapi.map_hook("sonarr", {"eventType": "ManualInteractionRequired", "series": {"title": "X"},
                                      "downloadStatusMessages": [{"title": "a", "messages": ["No files found"]}]})
        self.assertEqual(m["category"], "problems"); self.assertIn("No files found", m["body"])
        self.assertEqual(pcapi.map_hook("radarr", {"eventType": "Test"})["category"], "grabbed")
        self.assertIsNone(pcapi.map_hook("radarr", {"eventType": "Download"}))

    def test_seerr_mapping(self):
        pcapi.CONFIG["seerr_url"] = "https://seerr.example"
        self.addCleanup(pcapi.CONFIG.pop, "seerr_url")
        a = pcapi.map_hook("seerr", {"notification_type": "MEDIA_AVAILABLE", "event": "Movie Request Now Available",
                                     "subject": "Dune (2021)", "message": "A desert.",
                                     "image": "https://image.tmdb.org/t/p/w600/x.jpg",
                                     "media": {"media_type": "movie", "tmdbId": "438631"}})
        self.assertEqual(a["category"], "available")
        self.assertEqual(a["link"], "https://seerr.example/movie/438631")
        self.assertEqual(pcapi.map_hook("seerr", {"notification_type": "MEDIA_FAILED"})["category"], "problems")
        self.assertIsNone(pcapi.map_hook("seerr", {"notification_type": "MEDIA_PENDING"}))
        self.assertIsNone(pcapi.Notify.fetch_poster("https://evil.example/x.jpg"))
        self.assertIsNone(pcapi.Notify.fetch_poster("http://image.tmdb.org/x.jpg"))

    def test_seerr_jellyfin_link(self):
        pcapi.CONFIG["seerr_url"] = "https://seerr.example"
        pcapi.CONFIG["jellyfin_public_url"] = "http://host.ts.net:8096/"
        self.addCleanup(pcapi.CONFIG.pop, "seerr_url")
        self.addCleanup(pcapi.CONFIG.pop, "jellyfin_public_url", None)
        pcapi.CONFIG.pop("jellyfin_server_id", None)
        jid = "0123456789abcdef0123456789abcdef"
        sid = "fedcba9876543210fedcba9876543210"
        p = {"notification_type": "MEDIA_AVAILABLE", "event": "Movie Request Now Available",
             "media": {"media_type": "movie", "tmdbId": "438631", "jellyfinId": jid}}
        self.assertEqual(pcapi.map_hook("seerr", p)["link"],
                         f"http://host.ts.net:8096/web/#/details?id={jid}")
        pcapi.CONFIG["jellyfin_server_id"] = sid
        self.addCleanup(pcapi.CONFIG.pop, "jellyfin_server_id")
        self.assertEqual(pcapi.map_hook("seerr", p)["link"],
                         f"http://host.ts.net:8096/web/#/details?id={jid}&serverId={sid}")
        pcapi.CONFIG["jellyfin_server_id"] = "not-hex"
        self.assertEqual(pcapi.map_hook("seerr", p)["link"],
                         f"http://host.ts.net:8096/web/#/details?id={jid}")
        for bad in ("", "xyz", "a" * 31, 123):
            q = dict(p, media=dict(p["media"], jellyfinId=bad))
            self.assertEqual(pcapi.map_hook("seerr", q)["link"], "https://seerr.example/movie/438631")
        f = dict(p, notification_type="MEDIA_FAILED")
        self.assertEqual(pcapi.map_hook("seerr", f)["link"], "https://seerr.example/movie/438631")
        pcapi.CONFIG.pop("jellyfin_public_url")
        self.assertEqual(pcapi.map_hook("seerr", p)["link"], "https://seerr.example/movie/438631")


# ---------------------------------------------------------------- downloads + live progress

HASH_A, HASH_B, HASH_Q, HASH_S = "A" * 40, "B" * 40, "C" * 40, "D" * 40


class FakeQbt(BaseHTTPRequestHandler):
    """qBittorrent WebUI (login + torrents/info) and a Radarr queue endpoint."""
    torrents, logins, radarr_calls, expire_session = [], [0], [0], [False]

    def log_message(self, *a): pass

    def reply(self, code, obj, headers=()):
        b = json.dumps(obj).encode() if not isinstance(obj, bytes) else obj
        self.send_response(code)
        for k, v in headers: self.send_header(k, v)
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_POST(self):
        body = urllib.parse.parse_qs(self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode())
        if self.path == "/api/v2/auth/login":
            if body.get("password") != ["pw"]: return self.reply(200, b"Fails.")
            self.logins[0] += 1
            return self.reply(200, b"Ok.", [("Set-Cookie", f"QBT_SID_8080=s{self.logins[0]}; path=/")])
        if self.path == "/api/v2/torrents/info":
            if "QBT_SID_8080" not in (self.headers.get("Cookie") or "") or self.expire_session[0]:
                self.expire_session[0] = False
                return self.reply(403, b"Forbidden")
            return self.reply(200, self.torrents)
        self.reply(404, {})

    def do_GET(self):
        if self.path.startswith("/api/v3/queue"):
            if self.headers.get("X-Api-Key") != "k" * 32: return self.reply(401, {})
            self.radarr_calls[0] += 1
            return self.reply(200, {"records": [{"downloadId": HASH_B, "movie": {"title": "Dune", "year": 2021}}]})
        self.reply(404, {})


def torrent(h, name, state, cat, size=1000, done=250, speed=5000, eta=60):
    return {"hash": h.lower(), "name": name, "state": state, "category": cat, "size": size, "completed": done,
            "progress": done / size if size else 0, "dlspeed": speed, "eta": eta, "num_seeds": 3, "added_on": 1}


class DownloadsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeQbt)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        cls.dir = tempfile.mkdtemp()
        cls.pw = os.path.join(cls.dir, "pw")
        with open(cls.pw, "w") as f: f.write("pw\n")
        cls.radarr_conf = os.path.join(cls.dir, "config.xml")
        with open(cls.radarr_conf, "w") as f: f.write(f"<Config><ApiKey>{'k' * 32}</ApiKey></Config>")

    def setUp(self):
        FakeQbt.radarr_calls[0] = 0
        FakeQbt.torrents[:] = [
            torrent(HASH_A, "Severance.S02E03.1080p.WEB.h264", "downloading", "tv-sonarr", 1000, 500, 4000, 120),
            torrent(HASH_B, "Dune.2021.2160p.UHD.BluRay.x265", "stalledDL", "radarr", 3000, 0, 0, 8640000),
            torrent(HASH_Q, "www.Site.org - Some.Film.1999.1080p", "queuedDL", "qwen", 0, 0, 0, 0),
            torrent(HASH_S, "Done.Already.2020", "stalledUP", "radarr"),        # seeding: not a download
            torrent("E" * 40, "Paused.2020", "stoppedDL", "qwen"),               # paused: doesn't keep it alive
        ]
        self.data = tempfile.mkdtemp(dir=self.dir)
        self.d = pcapi.Downloads(self.base, "admin", self.pw, {
            "radarr": ("radarr", self.base, self.radarr_conf),
            "tv-sonarr": ("sonarr", "http://127.0.0.1:1", os.path.join(self.dir, "missing.xml"))}, self.data)

    def test_active_lists_only_downloads_with_titles_and_totals(self):
        r = self.d.active()
        self.assertEqual(r["qbt"], "ok")
        names = [(x["name"], x["state"]) for x in r["downloads"]]
        self.assertEqual(names, [("Severance S02E03", "downloading"), ("Dune (2021)", "stalled"),
                                 ("Some Film (1999)", "queued")])
        dune = r["downloads"][1]
        self.assertEqual((dune["id"], dune["eta"], dune["progress"]), ("b" * 16, None, 0))
        self.assertEqual(FakeQbt.radarr_calls[0], 1)                 # Radarr title from its queue
        t = r["total"]
        self.assertEqual((t["count"], t["downloading"], t["speed"], t["eta"]), (3, 1, 4000, 120))
        self.assertEqual(t["progress"], round(500 / 4000, 4))        # the size-less (metadata) one is left out
        # cached briefly, then Radarr isn't asked again for a title it already gave
        self.d.cache = (0.0, None)
        self.d.active()
        self.assertEqual(FakeQbt.radarr_calls[0], 1)
        self.assertEqual(json.load(open(os.path.join(self.data, "download-titles.json"))), {HASH_B: "Dune (2021)"})

    def test_webhook_title_wins_and_bad_hashes_ignored(self):
        self.assertTrue(self.d.remember(HASH_A.lower(), "Severance S02E03 · Who Is Alive?"))
        self.assertFalse(self.d.remember("../x", "nope"))
        self.assertFalse(self.d.remember(HASH_A, ""))
        self.assertEqual(self.d.active()["downloads"][0]["name"], "Severance S02E03 · Who Is Alive?")

    def test_session_expiry_relogs_and_bad_password_is_auth(self):
        self.d.active()
        FakeQbt.expire_session[0] = True
        self.d.cache = (0.0, None)
        self.assertEqual(self.d.active()["qbt"], "ok")
        bad = os.path.join(self.dir, "bad")
        with open(bad, "w") as f: f.write("wrong")
        r = pcapi.Downloads(self.base, "admin", bad, {}, self.data).active()
        self.assertEqual((r["qbt"], r["downloads"]), ("auth", []))
        r = pcapi.Downloads("http://127.0.0.1:1", "admin", self.pw, {}, self.data).active()
        self.assertEqual(r["qbt"], "down")

    def test_release_titles(self):
        self.assertEqual(pcapi.release_title("Dallas.Buyers.Club.2013.REPACK.1080p"), "Dallas Buyers Club (2013)")
        self.assertEqual(pcapi.release_title("Some Show S03 Complete 1080p"), "Some Show S03")
        self.assertEqual(pcapi.release_title("Plain Name"), "Plain Name")


class LiveCategoryTests(unittest.TestCase):
    def setUp(self):
        self.push = FakePush()
        self.n = pcapi.Notify(self.push, data_dir=tempfile.mkdtemp(), token_file="/nonexistent", chat="")

    def test_app_only_default_on(self):
        c = next(x for x in self.n.settings()["categories"] if x["id"] == "live")
        self.assertEqual((c["telegram"], c["app"], c["channels"]), (False, True, ["app"]))
        self.assertEqual(self.n.update({"categories": {"live": {"telegram": True}}})[0], 400)
        self.assertEqual(self.n.send("live", "x")[0], 400)               # not a message category

    def test_kick_sends_one_high_priority_data_push_without_names_and_respects_switches(self):
        self.assertEqual(self.n.live_kick("radarr"), "queued")
        data, high, ttl = self.push.queued[-1]
        self.assertEqual((data, high, ttl), ({"kind": "downloads", "id": "live", "why": "radarr"}, True, 300))
        self.assertEqual(self.n.live_kick("radarr"), "throttled")       # a season pack = one push
        self.assertEqual(len(self.push.queued), 1)
        self.n._live_at = 0
        self.n.update({"categories": {"live": {"app": False}}})
        self.assertEqual(self.n.live_kick(), "off")
        self.n.update({"categories": {"live": {"app": True}}, "masters": {"app": False}})
        self.assertEqual(self.n.live_kick(), "off")
        self.assertEqual(len(self.push.queued), 1)


class DownloadsHttpTests(unittest.TestCase):
    """GET /pc/api/downloads, POST /pc/api/downloads/started and the Radarr Grab hook through pc-api's handler."""

    @classmethod
    def setUpClass(cls):
        cls.qbt = ThreadingHTTPServer(("127.0.0.1", 0), FakeQbt)
        threading.Thread(target=cls.qbt.serve_forever, daemon=True).start()
        cls.dir = tempfile.mkdtemp()
        pw = os.path.join(cls.dir, "pw")
        with open(pw, "w") as f: f.write("pw")
        cls.saved = (pcapi.PUSH, pcapi.NOTIFY, pcapi.DOWNLOADS, pcapi.HOOK_SECRET_FILE, pcapi.ALLOWED_LOGINS,
                     pcapi.LOWSPACE)
        pcapi.LOWSPACE = os.path.join(cls.dir, "none.py")   # setUp writes a stand-in per test
        pcapi.PUSH = FakePush()
        pcapi.NOTIFY = pcapi.Notify(pcapi.PUSH, data_dir=cls.dir, token_file="/nonexistent", chat="")
        pcapi.DOWNLOADS = pcapi.Downloads(f"http://127.0.0.1:{cls.qbt.server_address[1]}", "admin", pw, {}, cls.dir)
        pcapi.NOTIFY.update({"categories": {"grabbed": {"telegram": False}}})   # no Telegram in these tests
        pcapi.HOOK_SECRET_FILE = os.path.join(cls.dir, "hook-secret")
        with open(pcapi.HOOK_SECRET_FILE, "w") as f: f.write("s" * 40)
        pcapi.ALLOWED_LOGINS = {"me@example.com"}
        cls.api = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown(); cls.qbt.shutdown()
        (pcapi.PUSH, pcapi.NOTIFY, pcapi.DOWNLOADS, pcapi.HOOK_SECRET_FILE, pcapi.ALLOWED_LOGINS,
         pcapi.LOWSPACE) = cls.saved

    def setUp(self):
        # a stand-in for the Qwen bot's lowspace.py that records its argv, so no test reaches the real one;
        # one per test, so a check a previous test started can't land in this test's log
        n = getattr(DownloadsHttpTests, "_n", 0) + 1
        DownloadsHttpTests._n = n
        self.space_log = os.path.join(self.dir, f"lowspace{n}.argv")
        pcapi.LOWSPACE = os.path.join(self.dir, f"lowspace{n}.py")
        with open(pcapi.LOWSPACE, "w") as f:
            f.write(f"import json, sys\nopen({self.space_log!r}, 'a').write(json.dumps(sys.argv[1:]) + '\\n')\n")
        FakeQbt.torrents[:] = [torrent(HASH_A, "Film.2001.1080p", "downloading", "radarr")]
        pcapi.DOWNLOADS.cache = (0.0, None)
        pcapi.PUSH.queued.clear()
        pcapi.NOTIFY._live_at = 0

    def call(self, method, path, body=None, headers=None):
        import urllib.request, urllib.error
        req = urllib.request.Request(self.url + path, method=method,
                                     headers={"Content-Type": "application/json", **_with_local_auth(headers, path)},
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=10) as r: return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_get_downloads_same_auth_as_other_endpoints(self):
        code, r = self.call("GET", "/pc/api/downloads")
        self.assertEqual(code, 200)
        self.assertTrue(r["enabled"])
        self.assertEqual([d["name"] for d in r["downloads"]], ["Film (2001)"])
        self.assertEqual(self.call("GET", "/pc/api/downloads", headers={"Tailscale-User-Login": "me@example.com"})[0], 200)
        self.assertEqual(self.call("GET", "/pc/api/downloads", headers={"Tailscale-User-Login": "x@evil.com"})[0], 403)
        self.assertEqual(self.call("GET", "/pc/api/downloads", headers={"X-Forwarded-For": "100.64.0.9"})[0], 403)
        self.assertEqual(self.call("POST", "/pc/api/downloads/started", {},
                                   headers={"Tailscale-User-Login": "x@evil.com"})[0], 403)

    def test_started_wakes_the_phone_and_records_title(self):
        code, r = self.call("POST", "/pc/api/downloads/started", {"hash": HASH_A, "title": "Qwen pick", "source": "qwen"})
        self.assertEqual((code, r["app"]), (200, "queued"))
        self.assertEqual(pcapi.PUSH.queued[-1][0], {"kind": "downloads", "id": "live", "why": "qwen"})
        self.assertEqual(self.call("GET", "/pc/api/downloads")[1]["downloads"][0]["name"], "Qwen pick")

    def test_radarr_grab_hook_triggers_live_push_and_maps_title(self):
        hook = {"eventType": "Grab", "downloadId": HASH_A, "movie": {"title": "Film", "year": 2001},
                "release": {"quality": "Bluray-1080p"}}
        self.assertEqual(self.call("POST", "/pc/api/hook/radarr", hook)[0], 401)          # needs the secret
        self.assertEqual(pcapi.PUSH.queued, [])
        code, r = self.call("POST", "/pc/api/hook/radarr", hook, {"Authorization": "Bearer " + "s" * 40})
        self.assertEqual(code, 200)
        self.assertEqual(pcapi.PUSH.queued[-1][0]["kind"], "downloads")
        self.assertEqual(pcapi.DOWNLOADS.titles[HASH_A], "Film (2001)")
        # a non-Grab event doesn't wake the phone
        pcapi.PUSH.queued.clear(); pcapi.NOTIFY._live_at = 0
        self.call("POST", "/pc/api/hook/radarr", {"eventType": "Download"}, {"Authorization": "Bearer " + "s" * 40})
        self.assertEqual(pcapi.PUSH.queued, [])

    def space_runs(self, n=1):
        end = time.time() + 10
        while time.time() < end:
            if os.path.exists(self.space_log):
                lines = open(self.space_log).read().splitlines()
                if len(lines) >= n: return [json.loads(x) for x in lines]
            time.sleep(0.05)
        return [json.loads(x) for x in open(self.space_log).read().splitlines()] if os.path.exists(self.space_log) else []

    def test_grab_starts_the_space_check_with_hash_and_size(self):
        hook = {"eventType": "Grab", "downloadId": HASH_A.lower(), "movie": {"title": "Film", "year": 2001},
                "release": {"quality": "Bluray-1080p", "size": 5000000000}}
        self.assertEqual(self.call("POST", "/pc/api/hook/radarr", hook)[0], 401)
        time.sleep(0.3)
        self.assertFalse(os.path.exists(self.space_log), "no secret, no check")
        self.assertEqual(self.call("POST", "/pc/api/hook/radarr", hook, {"Authorization": "Bearer " + "s" * 40})[0], 200)
        self.assertEqual(self.space_runs(), [["event", "--source", "radarr", "--hash", HASH_A.upper(),
                                              "--size", "5000000000"]])

    def test_space_check_only_on_grab_and_odd_fields_dropped(self):
        auth = {"Authorization": "Bearer " + "s" * 40}
        self.call("POST", "/pc/api/hook/sonarr", {"eventType": "Download"}, auth)
        self.call("POST", "/pc/api/hook/sonarr", {"eventType": "Grab", "downloadId": "x; rm -rf ~",
                                                  "release": {"size": "big"}, "series": {"title": "S"}}, auth)
        self.assertEqual(self.space_runs(), [["event", "--source", "sonarr"]])

    def test_space_check_failure_never_breaks_the_grab(self):
        auth = {"Authorization": "Bearer " + "s" * 40}
        hook = {"eventType": "Grab", "downloadId": HASH_A, "movie": {"title": "Film"}}
        saved = pcapi.LOWSPACE
        try:
            pcapi.LOWSPACE = os.path.join(self.dir, "missing.py")
            self.assertEqual(self.call("POST", "/pc/api/hook/radarr", hook, auth)[0], 200)
            pcapi.LOWSPACE = saved
            real = pcapi.subprocess.Popen
            pcapi.subprocess.Popen = lambda *a, **k: (_ for _ in ()).throw(OSError("no fork"))
            try:
                pcapi.NOTIFY._live_at = 0
                code, _ = self.call("POST", "/pc/api/hook/radarr", hook, auth)
                self.assertEqual(code, 200)
                self.assertEqual(pcapi.PUSH.queued[-1][0]["kind"], "downloads")
                self.assertEqual(pcapi.space_check("radarr", hook), "failed")
            finally:
                pcapi.subprocess.Popen = real
        finally:
            pcapi.LOWSPACE = saved

    def test_disabled_category_reported_to_the_app(self):
        pcapi.NOTIFY.update({"categories": {"live": {"app": False}}})
        self.addCleanup(pcapi.NOTIFY.update, {"categories": {"live": {"app": True}}})
        self.assertFalse(self.call("GET", "/pc/api/downloads")[1]["enabled"])
        self.assertEqual(self.call("POST", "/pc/api/downloads/started", {})[1]["app"], "off")


class CrashTests(unittest.TestCase):
    """POST/GET /pc/api/crash: the app's crash reports, stored under DATA_DIR/crashes."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        cls.saved = (pcapi.CRASHES, pcapi.ALLOWED_LOGINS)
        pcapi.CRASHES = pcapi.Crashes(cls.dir)
        pcapi.ALLOWED_LOGINS = {"me@example.com"}
        cls.api = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()
        pcapi.CRASHES, pcapi.ALLOWED_LOGINS = cls.saved

    call = DownloadsHttpTests.call

    def test_store_list_and_trace(self):
        import base64
        code, r = self.call("POST", "/pc/api/crash", {"kind": "native", "ts": 1794000000, "app": "1.5.0+8",
                            "reason": "CRASH_NATIVE signal 6", "where": "cams-open", "text": "Abort message: x\nline2",
                            "trace_b64": base64.b64encode(b"\x08\x01tombstone").decode()})
        self.assertEqual(code, 200)
        d = os.path.join(self.dir, "crashes")
        self.assertTrue(os.path.exists(os.path.join(d, r["id"] + ".trace")))
        self.assertEqual(os.stat(os.path.join(d, r["id"] + ".json")).st_mode & 0o777, 0o600)
        code, lst = self.call("GET", "/pc/api/crash")
        self.assertEqual(code, 200)
        c = next(x for x in lst["crashes"] if x["id"] == r["id"])
        self.assertEqual((c["kind"], c["where"], c["trace_bytes"], c["head"][0]), ("native", "cams-open", 11, "Abort message: x"))

    def test_bad_input_and_auth(self):
        self.assertEqual(self.call("POST", "/pc/api/crash", {"kind": "java", "trace_b64": "!!"})[0], 400)
        code, r = self.call("POST", "/pc/api/crash", {"kind": "weird", "text": "x"})
        self.assertEqual((code, r["id"].split("-")[2]), (200, "other"))
        self.assertEqual(self.call("POST", "/pc/api/crash", {"text": "x"}, headers={"Tailscale-User-Login": "x@evil.com"})[0], 403)

    def test_keeps_newest(self):
        c = pcapi.Crashes(tempfile.mkdtemp())
        c.KEEP = 3
        for i in range(5): c.store({"kind": "js", "ts": 1794000000 + i * 60, "text": str(i)})
        self.assertEqual([x["head"][0] for x in c.list()], ["4", "3", "2"])


if __name__ == "__main__":
    unittest.main()


# ---------------------------------------------------------------- health overview + service down watcher

def svc(unit, active="active", enabled="enabled", type="simple", timer=False, description=""):
    return {"unit": unit, "description": description, "active": active, "sub": "running", "enabled": enabled,
            "since": "", "pid": 1, "memory": None, "restarts": 0, "type": type, "result": "success", "timer": timer}


class HealthTests(unittest.TestCase):
    def sources(self, **over):
        base = {
            "services": lambda: {"units": [svc("ok.service"), svc("bad.service", active="failed")]},
            "downloads": lambda: {"qbt": "ok", "total": {"count": 1, "downloading": 1, "speed": 100,
                                                         "upspeed": 20, "progress": .5, "eta": 10, "size": 10,
                                                         "done": 5},
                                  "items": [{"name": "Dune", "state": "stalled", "progress": .1}],
                                  "stalled": 1, "stalled_items": ["Dune"]},
            "radarr": lambda: {"queue": 2, "needs_action": 1,
                               "items": [{"title": "Dune (2021)", "reason": "No files found", "state": "importBlocked"}]},
            "sonarr": lambda: {"queue": 0, "needs_action": 0, "items": []},
            "jellyfin": lambda: {"streams": [{"user": "Ayush", "title": "Dune", "method": "direct"}]},
            "disks": lambda: {"disks": [
                {"mount": "/", "kind": "SSD", "used": 50, "total": 100, "pct": 50.0},
                {"mount": "/hdd", "kind": "HDD", "used": 95, "total": 100, "pct": 95.0}]},
        }
        base.update(over)
        return base

    def test_problems_are_aggregated_and_sections_are_complete(self):
        r = pcapi.health_report(sources=self.sources(), timeout=1)
        self.assertTrue(r["services"]["available"])
        self.assertEqual(r["services"]["counts"], {"running": 1, "failed": 1, "inactive": 0, "other": 0})
        kinds = {p["kind"] for p in r["problems"]}
        self.assertEqual(kinds, {"service", "stalled", "arr", "disk"})
        self.assertTrue(any(p["title"] == "Radarr: 1 needs manual action" for p in r["problems"]))
        self.assertTrue(any(p["kind"] == "disk" and "under 10% free" in p["title"] for p in r["problems"]))
        self.assertEqual(r["downloads"]["total"]["upspeed"], 20)
        self.assertEqual(r["jellyfin"]["streams"][0]["method"], "direct")

    def test_partial_failure_marks_only_that_section_unavailable(self):
        def boom(): raise OSError("radarr down")
        def slow(): time.sleep(0.6); return {"streams": []}
        r = pcapi.health_report(sources=self.sources(radarr=boom, jellyfin=slow), timeout=0.2)
        self.assertEqual((r["radarr"]["available"], r["radarr"]["error"]), (False, "OSError"))
        self.assertEqual((r["jellyfin"]["available"], r["jellyfin"]["error"]), (False, "timeout"))
        self.assertTrue(r["services"]["available"])
        self.assertTrue(r["sonarr"]["available"])
        self.assertTrue(r["disks"]["available"])
        titles = {p["title"] for p in r["problems"]}
        self.assertIn("Radarr unavailable", titles)
        self.assertIn("Jellyfin unavailable", titles)

    def test_category_is_available_and_appears_in_settings(self):
        n = pcapi.Notify(FakePush(), data_dir=tempfile.mkdtemp(), token_file="/nonexistent", chat="")
        c = next(x for x in n.settings()["categories"] if x["id"] == "service_down")
        self.assertEqual((c["telegram"], c["app"]), (True, True))
        n.update({"categories": {"service_down": {"telegram": False}}})
        self.assertEqual(n.send("service_down", "x")[0], 200)


class FakeNotifier:
    def __init__(self): self.calls = []

    def send(self, *a): self.calls.append(a)


class ServiceWatcherTests(unittest.TestCase):
    def setUp(self):
        self.n = FakeNotifier()
        self.w = pcapi.ServiceWatcher(self.n, list_fn=lambda: [], interval=999)

    def test_two_bad_checks_one_alert_per_outage_and_recovery(self):
        active = svc("camrec.service")
        down = svc("camrec.service", active="inactive")
        failed = svc("camrec.service", active="failed")
        self.w.check([active])
        self.w.check([down])
        self.assertEqual(self.n.calls, [])                       # first bad check is only a suspicion
        self.w.check([down])
        self.assertEqual(len(self.n.calls), 1)
        self.assertEqual(self.n.calls[0][0], "service_down")
        self.assertEqual(self.n.calls[0][1], "camrec is inactive")
        self.w.check([down])
        self.assertEqual(len(self.n.calls), 1)                   # one alert per outage
        self.w.check([active])
        self.assertEqual(len(self.n.calls), 2)
        self.assertEqual(self.n.calls[1][1], "camrec recovered")
        self.w.check([active])
        self.assertEqual(len(self.n.calls), 2)                   # recovery is sent once
        self.w.check([failed])
        self.w.check([failed])
        self.assertEqual(len(self.n.calls), 3)
        self.assertEqual(self.n.calls[2][1], "camrec is failed")

    def test_ignores_oneshot_timer_static_disabled_and_never_active_units(self):
        ignored = [svc("config-backup.service", active="inactive", enabled="static", type="oneshot"),
                   svc("import-watch.service", active="inactive", enabled="static", type="oneshot", timer=True),
                   svc("kbd-lock.service", active="inactive", enabled="static"),
                   svc("ollama.service", active="inactive", enabled="disabled")]
        for _ in range(3): self.w.check(ignored)
        self.assertEqual(self.n.calls, [])                       # none of these is watched
        # an enabled unit that was already down when pc-api started is not alerted until it has been active
        never = svc("vitals.service", active="inactive")
        self.w.check([never]); self.w.check([never])
        self.assertEqual(self.n.calls, [])
        self.w.check([svc("vitals.service")])
        self.w.check([never]); self.w.check([never])
        self.assertEqual(len(self.n.calls), 1)

    def test_bad_list_never_kills_the_loop(self):
        w = pcapi.ServiceWatcher(self.n, list_fn=lambda: (_ for _ in ()).throw(OSError("systemctl down")))
        with self.assertRaises(OSError):
            w.check()


# ---------------------------------------------------------------- torrent VPN (qBittorrent -> Proton)

class FakeVpnQbt:
    def __init__(self, listen=28655):
        self.prefs = {"listen_port": listen, "current_network_interface": "", "upnp": True}
        self.calls = []

    def preferences(self): return dict(self.prefs)

    def set_preferences(self, p):
        self.calls.append(dict(p))
        self.prefs.update(p)
        return {}


class NatPmpTests(unittest.TestCase):
    def test_mapping_request_uses_the_rfc6886_wire_format(self):
        sent = []

        class Sock:
            def __init__(self, *a): pass
            def bind(self, addr): pass
            def settimeout(self, timeout): pass
            def sendto(self, data, addr): sent.append((data, addr))
            def recvfrom(self, n):
                return struct.pack("!BBHIHHI", 0, 129, 0, 0, 43124, 43124, 60), ("10.2.0.1", 5351)
            def close(self): pass

        with mock.patch.object(pcapi.socket, "socket", Sock):
            r = pcapi.NatPmp().request(1, 0, 1, 60)
        self.assertEqual(sent[0][0], struct.pack("!BBHHHI", 0, 1, 0, 0, 1, 60))
        self.assertEqual(sent[0][1], ("10.2.0.1", 5351))
        self.assertEqual(r, {"internal": 43124, "external": 43124, "lifetime": 60})
        with self.assertRaises(ValueError):
            pcapi.NatPmp().request(0, 28655)


class FakeNatPmp:
    def __init__(self, external=None, fail=False, result=None, udp=None, tcp=None):
        self.calls, self.external, self.fail, self.result = [], external, fail, result
        self.udp, self.tcp = udp, tcp

    def request(self, opcode, internal, suggested=0, lifetime=60):
        self.calls.append((opcode, internal, suggested, lifetime))
        if self.fail: raise OSError("no NAT-PMP gateway")
        if self.result is not None: raise pcapi.NatPmpError(self.result)
        external = self.external
        if external is None: external = self.udp if opcode == 1 else self.tcp
        self.assert_lifetime = lifetime
        return {"internal": internal, "external": external or 28655, "lifetime": lifetime}


class VpnTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.conf = os.path.join(self.dir, "pcapi.json")
        self.kick_dir = os.path.join(self.dir, "kick")
        os.makedirs(self.kick_dir)
        self.kick = os.path.join(self.kick_dir, "kick")
        self.notif = FakeNotifier()
        self.qbt = FakeVpnQbt()
        self.net = FakeNatPmp()
        self.ip_calls = []
        self.v = pcapi.VpnManager(self.notif, qbt=self.qbt, conf_file=self.conf, net=self.net,
                                  ip_fetcher=lambda: (self.ip_calls.append(1), "203.0.113.9")[1],
                                  kick_file=self.kick)
        self.v._iface_exists = lambda: True
        self.v._handshake_age = lambda: 5

    def test_enable_sets_interface_keeps_natpmp_for_both_protocols_and_persists(self):
        st = self.v.set_enabled(True)
        self.assertTrue(st["enabled"])
        self.assertEqual(self.qbt.prefs["current_network_interface"], "proton0")
        self.assertFalse(self.qbt.prefs["upnp"])
        self.assertEqual({c[0] for c in self.net.calls}, {1, 2})
        self.assertEqual({(c[1], c[2]) for c in self.net.calls}, {(0, 1)})   # Proton: internal 0, suggest 1
        self.assertEqual({c[3] for c in self.net.calls}, {60})
        self.assertEqual((st["forwarded_port"], st["qbt_interface"]), (28655, "proton0"))
        with open(self.conf) as f: self.assertTrue(json.load(f)["torrent_vpn_enabled"])

    def test_public_port_change_moves_qbt_and_disable_restores_previous_port(self):
        self.net.external = 40000
        st = self.v.set_enabled(True)
        self.assertEqual((st["forwarded_port"], self.qbt.prefs["listen_port"]), (40000, 40000))
        self.assertTrue(all(c[1] == 0 and c[2] == 1 for c in self.net.calls))
        st = self.v.set_enabled(False)
        self.assertEqual(self.qbt.prefs["current_network_interface"], "")
        self.assertEqual(self.qbt.prefs["listen_port"], 28655)
        self.assertEqual((st["forwarded_port"], st["qbt_interface"], st["enabled"]), (None, "", False))
        with open(self.conf) as f: self.assertFalse(json.load(f)["torrent_vpn_enabled"])

    def test_restart_while_enabled_keeps_the_real_previous_port(self):
        self.net.external = 40000
        self.v.set_enabled(True)
        self.assertEqual(self.qbt.prefs["listen_port"], 40000)
        v2 = pcapi.VpnManager(self.notif, qbt=self.qbt, conf_file=self.conf, net=self.net, kick_file=self.kick)
        v2._iface_exists = lambda: True
        v2._handshake_age = lambda: 5
        v2._enable_locked(persist=False)
        self.assertEqual(v2.previous_port, 28655)                       # not the forwarded port
        v2.set_enabled(False)
        self.assertEqual(self.qbt.prefs["listen_port"], 28655)

    def test_udp_and_tcp_mismatch_uses_the_udp_port(self):
        self.v.net = FakeNatPmp(udp=50000, tcp=50001)
        st = self.v.set_enabled(True)
        self.assertEqual(st["forwarded_port"], 50000)
        self.assertEqual(self.qbt.prefs["listen_port"], 50000)

    def test_natpmp_result_4_backs_off_for_sixty_seconds(self):
        self.net.result = 4
        self.v.set_enabled(True)
        self.assertFalse(self.v.natpmp_ok)
        self.assertIn("4", self.v.last_error)
        self.assertGreaterEqual(self.v._next_renew, time.time() + 55)

    def test_natpmp_failure_is_reported_but_enabling_still_binds_the_kill_switch(self):
        self.net.fail = True
        self.v._handshake_age = lambda: None       # `wg` unreadable: fall back to NAT-PMP health
        st = self.v.set_enabled(True)
        self.assertTrue(st["enabled"])
        self.assertFalse(st["tunnel_up"])
        self.assertIn("NAT-PMP", st["last_error"])
        self.assertEqual(self.qbt.prefs["current_network_interface"], "proton0")

    def test_public_ip_is_cached_for_ten_minutes_and_only_from_the_tunnel(self):
        self.v.set_enabled(True)
        self.v._refresh_public_ip_locked()
        self.v._refresh_public_ip_locked()
        self.assertEqual(len(self.ip_calls), 1)
        self.assertEqual(self.v.status()["public_ip"], "203.0.113.9")
        self.v._ip_at = 0
        self.v._refresh_public_ip_locked()
        self.assertEqual(len(self.ip_calls), 2)
        self.v._iface_exists = lambda: False
        self.v._ip_at = 0
        self.v._refresh_public_ip_locked()
        self.assertEqual(len(self.ip_calls), 2)

    def test_public_ip_retries_until_natpmp_and_the_fetch_succeed(self):
        self.net.fail = True
        self.v.set_enabled(True)
        self.v._refresh_public_ip_locked()
        self.assertEqual((self.v.public_ip, self.v._ip_at, len(self.ip_calls)), (None, 0, 0))

        attempts = []
        def flaky():
            attempts.append(1)
            if len(attempts) == 1: raise OSError("temporary")
            return "203.0.113.9"

        self.v.natpmp_ok = True
        self.v.ip_fetcher = flaky
        self.v._refresh_public_ip_locked()
        self.assertEqual((self.v.public_ip, self.v._ip_at, len(attempts)), (None, 0, 1))
        self.v._refresh_public_ip_locked()
        self.assertEqual((self.v.public_ip, len(attempts)), ("203.0.113.9", 2))

    def test_one_down_alert_after_three_minutes_and_one_recovery(self):
        self.v.set_enabled(True)
        self.v._iface_exists = lambda: False
        self.v.natpmp_ok = False
        self.v._unhealthy_since = time.time() - pcapi.VPN_ALERT_AFTER - 1
        self.v._status_check_locked()
        self.assertEqual(len(self.notif.calls), 1)
        self.assertEqual(self.notif.calls[0][0], "vpn_down")
        self.assertIn("down", self.notif.calls[0][1].lower())
        self.v._status_check_locked()
        self.assertEqual(len(self.notif.calls), 1)                       # one alert per outage
        self.v._iface_exists = lambda: True
        self.v._handshake_age = lambda: 5
        self.v.natpmp_ok = True
        self.v._status_check_locked()
        self.assertEqual(len(self.notif.calls), 2)
        self.assertIn("back up", self.notif.calls[1][1].lower())
        self.v._status_check_locked()
        self.assertEqual(len(self.notif.calls), 2)                       # recovery is sent once

    def test_health_exposes_vpn_and_reports_it_down(self):
        class FakeVpn:
            def status(self):
                return {"enabled": True, "tunnel_up": False, "forwarded_port": None,
                        "qbt_interface": "proton0", "public_ip": None, "last_error": "no NAT-PMP gateway"}
        saved, pcapi.VPN = pcapi.VPN, FakeVpn()
        try:
            sources = HealthTests().sources()
            sources["vpn"] = pcapi.vpn_health
            r = pcapi.health_report(sources=sources, timeout=1)
        finally:
            pcapi.VPN = saved
        self.assertTrue(r["vpn"]["available"])
        problem = next(p for p in r["problems"] if p["kind"] == "vpn")
        self.assertEqual(problem["title"], "Torrent VPN is down")
        self.assertIn("NAT-PMP", problem["detail"])

    def test_failed_natpmp_renewal_writes_kick_file_and_success_does_not(self):
        kick_dir = os.path.join(self.dir, "env-kick")
        os.makedirs(kick_dir)
        kick_path = os.path.join(kick_dir, "kick")
        with mock.patch.dict(os.environ, {"PCAPI_WG_KICK": kick_path}):
            v = pcapi.VpnManager(self.notif, qbt=self.qbt, conf_file=self.conf, net=self.net)
            v._iface_exists = lambda: True
            v._handshake_age = lambda: 5

            # Successful renewal must not create the kick file.
            v.set_enabled(True)
            self.assertTrue(v.natpmp_ok)
            self.assertFalse(os.path.exists(kick_path))

            # Failed/timed-out renewal while enabled writes current unix time atomically.
            self.net.fail = True
            before = int(time.time())
            v._renew_locked()
            after = int(time.time())
            self.assertFalse(v.natpmp_ok)
            self.assertTrue(os.path.exists(kick_path))
            self.assertFalse(os.path.exists(kick_path + ".tmp"))
            with open(kick_path, "r", encoding="utf-8") as f:
                ts = int(f.read().strip())
            self.assertGreaterEqual(ts, before)
            self.assertLessEqual(ts, after)

            # A subsequent successful renewal must not write or recreate the kick file.
            os.remove(kick_path)
            self.net.fail = False
            v._renew_locked()
            self.assertTrue(v.natpmp_ok)
            self.assertFalse(os.path.exists(kick_path))

            # A failed renewal when VPN is disabled must not write the kick file.
            v.set_enabled(False)
            self.net.fail = True
            v._renew_locked()
            self.assertFalse(os.path.exists(kick_path))

    def test_missing_kick_dir_never_raises_and_logs_once(self):
        import io
        missing_kick = os.path.join(self.dir, "does-not-exist", "kick")
        with mock.patch.dict(os.environ, {"PCAPI_WG_KICK": missing_kick}):
            v = pcapi.VpnManager(self.notif, qbt=self.qbt, conf_file=self.conf, net=self.net)
            v._iface_exists = lambda: True
            v._handshake_age = lambda: 5
            v.set_enabled(True)
            self.net.fail = True
            err = io.StringIO()
            with mock.patch.object(sys, "stderr", err):
                v._renew_locked()
                v._renew_locked()
            lines = [line for line in err.getvalue().splitlines() if "watchdog kick" in line]
            self.assertEqual(len(lines), 1)
            self.assertFalse(os.path.exists(missing_kick))


class VpnHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.saved = pcapi.ALLOWED_LOGINS, pcapi.VPN
        pcapi.ALLOWED_LOGINS = {"me@example.com"}
        cls.api = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()
        pcapi.ALLOWED_LOGINS, pcapi.VPN = cls.saved

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.qbt = FakeVpnQbt()
        self.v = pcapi.VpnManager(FakeNotifier(), qbt=self.qbt, conf_file=os.path.join(self.dir, "pcapi.json"),
                                  net=FakeNatPmp(), kick_file=os.path.join(self.dir, "kick"))
        self.v._iface_exists = lambda: True
        self.v._handshake_age = lambda: 5
        pcapi.VPN = self.v

    def call(self, method, path, body=None, headers=None):
        import urllib.request, urllib.error
        req = urllib.request.Request(self.url + path, method=method,
                                     headers={"Content-Type": "application/json", **_with_local_auth(headers, path)},
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=10) as r: return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_get_and_post_same_auth_as_other_write_endpoints(self):
        self.assertEqual(self.call("GET", "/pc/api/vpn")[0], 200)
        self.assertEqual(self.call("POST", "/pc/api/vpn", {"enabled": True},
                                   {"Tailscale-User-Login": "x@evil.com"})[0], 403)
        self.assertEqual(self.call("POST", "/pc/api/vpn", {"enabled": "yes"})[0], 400)
        self.assertEqual(self.call("POST", "/pc/api/vpn", {"enabled": True})[0], 200)
        self.assertTrue(self.call("GET", "/pc/api/vpn")[1]["enabled"])
        self.assertEqual(self.call("POST", "/pc/api/vpn", {"enabled": False})[0], 200)
        self.assertFalse(self.call("GET", "/pc/api/vpn")[1]["enabled"])


class AgentTests(unittest.TestCase):
    def _make_script(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "agent-switch")
        with open(p, "w") as f:
            f.write("#!/bin/sh\nexit 0\n")
        os.chmod(p, 0o755)
        return p

    def test_missing_script_reports_unavailable_claude_and_rejects_switch(self):
        missing = os.path.join(tempfile.mkdtemp(), "agent-switch")
        calls = []

        def fake_run(argv, **kw):
            calls.append(argv)
            return pcapi.subprocess.CompletedProcess(argv, 0, "", "")

        mgr = pcapi.AgentManager(script=missing, run_fn=fake_run)
        st = mgr.status()
        self.assertEqual(st["engine"], "claude")
        self.assertFalse(st["claude_active"])
        self.assertFalse(st["gemini_active"])
        self.assertFalse(st["available"])
        self.assertFalse(st["switching"])
        self.assertEqual(st["options"], [
            {"id": "claude", "label": "Claude", "model": "Opus 5.5"},
            {"id": "gemini", "label": "Gemini", "model": "Gemini 4 Neon"},
        ])
        code, body = mgr.switch("gemini")
        self.assertEqual(code, 503)
        self.assertIn("error", body)
        self.assertEqual(calls, [])

    def test_status_and_detached_switch_lifecycle(self):
        script = self._make_script()
        now = [100.0]
        state = {"engine": "claude", "claude_active": True, "gemini_active": False}
        calls = []

        def fake_run(argv, **kw):
            calls.append((argv, kw))
            if argv[-1] == "status":
                return pcapi.subprocess.CompletedProcess(argv, 0, json.dumps(state), "")
            return pcapi.subprocess.CompletedProcess(argv, 0, "", "")

        mgr = pcapi.AgentManager(script=script, run_fn=fake_run, clock=lambda: now[0], switch_ttl=30.0)
        st = mgr.status()
        self.assertEqual((st["engine"], st["claude_active"], st["gemini_active"], st["available"], st["switching"]),
                         ("claude", True, False, True, False))
        self.assertEqual(calls[-1][0], [os.path.abspath(script), "status"])

        code, res = mgr.switch("gemini")
        self.assertEqual(code, 202)
        self.assertEqual((res["engine"], res["available"], res["switching"]), ("gemini", True, True))
        self.assertEqual(calls[-1][0], ["systemd-run", "--user", "--collect", "--quiet", os.path.abspath(script), "gemini"])

        # Still transitioning: target unit not active yet
        self.assertTrue(mgr.status()["switching"])

        # Target unit active and old unit stopped -> switching clears
        state.update({"engine": "gemini", "claude_active": False, "gemini_active": True})
        done = mgr.status()
        self.assertEqual((done["engine"], done["claude_active"], done["gemini_active"], done["switching"]),
                         ("gemini", False, True, False))

        # Switching flag expires after TTL even if unit never flips
        code, _ = mgr.switch("claude")
        self.assertEqual(code, 202)
        self.assertTrue(mgr.status()["switching"])
        now[0] += 31.0
        self.assertFalse(mgr.status()["switching"])

    def test_rejects_unknown_engines_and_reports_subprocess_errors(self):
        script = self._make_script()
        calls = []

        def fake_run(argv, **kw):
            calls.append(argv)
            return pcapi.subprocess.CompletedProcess(argv, 1, "", "systemd bus down")

        mgr = pcapi.AgentManager(script=script, run_fn=fake_run)
        for bad in ("gpt", "", None, 42, ["claude"]):
            code, body = mgr.switch(bad)
            self.assertEqual(code, 400)
            self.assertIn("error", body)
        self.assertEqual(calls, [])

        code, body = mgr.switch("claude")
        self.assertEqual(code, 502)
        self.assertIn("systemd bus down", body["error"])
        with self.assertRaises(RuntimeError):
            mgr.status()

    def test_health_includes_agent_section(self):
        script = self._make_script()
        mgr = pcapi.AgentManager(
            script=script,
            run_fn=lambda argv, **kw: pcapi.subprocess.CompletedProcess(
                argv, 0, json.dumps({"engine": "gemini", "claude_active": False, "gemini_active": True}), ""),
        )
        saved, pcapi.AGENT = pcapi.AGENT, mgr
        try:
            sources = HealthTests().sources()
            sources["agent"] = pcapi.agent_health
            r = pcapi.health_report(sources=sources, timeout=1)
        finally:
            pcapi.AGENT = saved
        self.assertTrue(r["agent"]["available"])
        self.assertEqual(r["agent"]["engine"], "gemini")
        self.assertTrue(r["agent"]["gemini_active"])
        self.assertFalse(any(p["title"] == "Agent unavailable" for p in r["problems"]))

        # Missing script should not raise a health problem
        missing = os.path.join(tempfile.mkdtemp(), "agent-switch")
        saved, pcapi.AGENT = pcapi.AGENT, pcapi.AgentManager(script=missing)
        try:
            sources = HealthTests().sources()
            sources["agent"] = pcapi.agent_health
            r2 = pcapi.health_report(sources=sources, timeout=1)
        finally:
            pcapi.AGENT = saved
        self.assertFalse(r2["agent"]["available"])
        self.assertEqual(r2["agent"]["engine"], "claude")
        self.assertFalse(any(p["title"] == "Agent unavailable" for p in r2["problems"]))

    def test_uses_subprocess_run_with_detached_systemd_run_args(self):
        script = self._make_script()
        mgr = pcapi.AgentManager(script=script)
        with mock.patch.object(pcapi.subprocess, "run") as mrun:
            mrun.return_value = pcapi.subprocess.CompletedProcess([], 0, "", "")
            code, res = mgr.switch("claude")
        self.assertEqual((code, res["engine"], res["switching"]), (202, "claude", True))
        mrun.assert_called_once_with(
            ["systemd-run", "--user", "--collect", "--quiet", os.path.abspath(script), "claude"],
            stdin=pcapi.subprocess.DEVNULL, capture_output=True, text=True, timeout=10,
        )


class AgentHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.saved = pcapi.ALLOWED_LOGINS, pcapi.AGENT
        pcapi.ALLOWED_LOGINS = {"me@example.com"}
        cls.api = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()
        cls.api.server_close()
        pcapi.ALLOWED_LOGINS, pcapi.AGENT = cls.saved

    def setUp(self):
        d = tempfile.mkdtemp()
        self.script = os.path.join(d, "agent-switch")
        with open(self.script, "w") as f:
            f.write("#!/bin/sh\nexit 0\n")
        os.chmod(self.script, 0o755)
        self.calls = []
        self.state = {"engine": "claude", "claude_active": True, "gemini_active": False}

        def fake_run(argv, **kw):
            self.calls.append(argv)
            if argv[-1] == "status":
                return pcapi.subprocess.CompletedProcess(argv, 0, json.dumps(self.state), "")
            return pcapi.subprocess.CompletedProcess(argv, 0, "", "")

        pcapi.AGENT = pcapi.AgentManager(script=self.script, run_fn=fake_run)

    def call(self, method, path, body=None, headers=None):
        import urllib.request, urllib.error
        req = urllib.request.Request(self.url + path, method=method,
                                     headers={"Content-Type": "application/json", **_with_local_auth(headers, path)},
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=10) as r: return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_get_and_post_auth_validation_and_detached_switch(self):
        # Auth checks mirror /pc/api/vpn
        self.assertEqual(self.call("GET", "/pc/api/agent", headers={"Tailscale-User-Login": "x@evil.com"})[0], 403)
        self.assertEqual(self.call("POST", "/pc/api/agent", {"engine": "gemini"},
                                   {"Tailscale-User-Login": "x@evil.com"})[0], 403)
        self.assertEqual(self.call("POST", "/pc/api/agent", {"engine": "gemini"},
                                   {"X-Forwarded-For": "100.64.0.9"})[0], 403)

        # GET returns full status + options
        code, r = self.call("GET", "/pc/api/agent", headers={"Tailscale-User-Login": "me@example.com"})
        self.assertEqual(code, 200)
        self.assertEqual((r["engine"], r["claude_active"], r["gemini_active"], r["available"], r["switching"]),
                         ("claude", True, False, True, False))
        self.assertEqual([o["id"] for o in r["options"]], ["claude", "gemini"])

        # Unknown engine rejected with 400
        self.assertEqual(self.call("POST", "/pc/api/agent", {"engine": "unknown"})[0], 400)
        self.assertEqual(self.call("POST", "/pc/api/agent", {})[0], 400)

        # Valid POST returns 202 and invokes detached systemd-run
        code, r = self.call("POST", "/pc/api/agent", {"engine": "gemini"})
        self.assertEqual(code, 202)
        self.assertEqual((r["engine"], r["switching"], r["available"]), ("gemini", True, True))
        self.assertEqual(self.calls[-1],
                         ["systemd-run", "--user", "--collect", "--quiet", os.path.abspath(self.script), "gemini"])

        # Subsequent GET reflects switching until gemini_active flips True
        code, r = self.call("GET", "/pc/api/agent")
        self.assertEqual((code, r["switching"]), (200, True))
        self.state.update({"engine": "gemini", "claude_active": False, "gemini_active": True})
        code, r = self.call("GET", "/pc/api/agent")
        self.assertEqual((code, r["engine"], r["gemini_active"], r["switching"]), (200, "gemini", True, False))


class AgentUsageAndSettingsHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.saved = (
            pcapi.ALLOWED_LOGINS,
            pcapi.AGENT_USAGE,
            pcapi.AGENT_SETTINGS,
            pcapi.AGENT_RESET_BIN,
        )
        pcapi.ALLOWED_LOGINS = {"me@example.com"}
        cls.api = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()
        (
            pcapi.ALLOWED_LOGINS,
            pcapi.AGENT_USAGE,
            pcapi.AGENT_SETTINGS,
            pcapi.AGENT_RESET_BIN,
        ) = cls.saved

    def setUp(self):
        import hashlib

        self.dir = tempfile.mkdtemp()
        self.usage_script = os.path.join(self.dir, "ai-usage")
        with open(self.usage_script, "w") as f:
            f.write("#!/bin/sh\nexit 0\n")
        os.chmod(self.usage_script, 0o755)

        self.reset_script = os.path.join(self.dir, "agent-reset")
        with open(self.reset_script, "w") as f:
            f.write("#!/bin/sh\necho 'fresh started'\n")
        os.chmod(self.reset_script, 0o755)
        pcapi.AGENT_RESET_BIN = self.reset_script

        self.t3_settings = os.path.join(self.dir, "t3-settings.json")
        self.t3_providers = os.path.join(self.dir, "t3-providers")
        with open(self.t3_settings, "w") as f:
            json.dump({
                "providerInstances": {
                    "opencode": {"driver": "opencode", "displayName": "OpenCode", "enabled": True},
                    "antigravity": {"driver": "antigravity", "displayName": "Antigravity 1", "enabled": True},
                    "antigravity-2": {"driver": "antigravity", "displayName": "Antigravity 2", "enabled": True},
                }
            }, f)
        h2 = hashlib.sha256(b"antigravity-2").hexdigest()
        tok2 = os.path.join(self.t3_providers, h2, "antigravity-acp", "antigravity-oauth-token")
        os.makedirs(os.path.dirname(tok2), exist_ok=True)
        with open(tok2, "w") as f:
            f.write("secret-token-never-returned")

        self.claude_cfg = os.path.join(self.dir, "claude.json")
        with open(self.claude_cfg, "w") as f:
            json.dump({"oauthAccount": {"emailAddress": "claude@example.com"}}, f)

        self.now = [1000.0]
        self.usage_calls = 0
        self.usage_report = {
            "antigravity_accounts": [
                {
                    "instance_id": "antigravity",
                    "display_name": "Antigravity 1",
                    "signed_in": False,
                    "status": "not signed in",
                    "account": None,
                    "sections": {},
                },
                {
                    "instance_id": "antigravity-2",
                    "display_name": "Antigravity 2",
                    "signed_in": True,
                    "account": "gem2@gmail.com",
                    "sections": {
                        "gemini": {
                            "five_hour": {"remaining_pct": 88.0, "used_pct": 12.0, "refreshes_in": "4h 10m"},
                            "weekly": {"remaining_pct": 95.0, "used_pct": 5.0, "refreshes_in": "160h"},
                        }
                    },
                },
            ],
            "claude": {
                "account": "claude@example.com",
                "signed_in": True,
                "five_hour": {
                    "remaining_pct": 70.0,
                    "used_pct": 30.0,
                    "refreshes_in": "2h",
                    "resets_at": "2026-11-23T08:30:00Z",
                },
                "weekly": {
                    "remaining_pct": 60.0,
                    "used_pct": 40.0,
                    "refreshes_in": "5d",
                    "resets_at": "2026-11-28T06:30:00Z",
                },
            },
            "opencode": {
                "deepseek": {
                    "is_available": True,
                    "currency": "USD",
                    "total_balance": 3.38,
                    "granted_balance": 0.0,
                    "topped_up_balance": 3.38,
                },
                "zen": {
                    "status": "ok",
                    "days": 7,
                    "sessions": 8,
                    "messages": 929,
                    "total_cost": "$0.62",
                    "zen_messages": 607,
                    "models": [
                        {"model": "opencode/deepseek-v4.1-flash-free", "messages": 607, "cost": "$0.0000"},
                        {"model": "deepseek/deepseek-flash", "messages": 276, "cost": "$0.6153"},
                    ],
                },
            },
        }

        def fake_usage_run(argv, **kw):
            self.usage_calls += 1
            return pcapi.subprocess.CompletedProcess(argv, 0, json.dumps(self.usage_report), "")

        self.usage_cache_file = os.path.join(self.dir, "agent-usage-cache.json")
        pcapi.AGENT_USAGE = pcapi.AgentUsageManager(
            script=self.usage_script,
            run_fn=fake_usage_run,
            clock=lambda: self.now[0],
            ttl=60.0,
            claude_config_path=self.claude_cfg,
            cache_path=self.usage_cache_file,
        )
        self.claude_agent_cfg = os.path.join(self.dir, "claude-agent.json")
        self.gemini_agent_cfg = os.path.join(self.dir, "gemini-agent.json")
        self.fresh_flag = os.path.join(self.dir, ".fresh-gemini")
        pcapi.AGENT_SETTINGS = pcapi.AgentSettingsManager(
            claude_path=self.claude_agent_cfg,
            gemini_path=self.gemini_agent_cfg,
            gemini_fresh_flag=self.fresh_flag,
            t3_settings_path=self.t3_settings,
            t3_providers_dir=self.t3_providers,
            claude_config_path=self.claude_cfg,
            usage_mgr=pcapi.AGENT_USAGE,
        )

    def call(self, method, path, body=None, headers=None):
        import urllib.request, urllib.error
        req = urllib.request.Request(self.url + path, method=method,
                                     headers={"Content-Type": "application/json", **_with_local_auth(headers, path)},
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=10) as r: return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_usage_endpoint_caching_and_normalization(self):
        code, r = self.call("GET", "/pc/api/agent/usage")
        self.assertEqual(code, 200)
        self.assertFalse(r["cached"])
        self.assertFalse(r["stale"])
        self.assertEqual(self.usage_calls, 1)
        self.assertEqual(len(r["accounts"]), 4)
        self.assertEqual(r["accounts"][0]["status"], "not signed in")
        self.assertFalse(r["accounts"][0]["signed_in"])
        self.assertEqual(r["accounts"][1]["email"], "gem2@gmail.com")
        self.assertEqual(r["accounts"][1]["five_hour"]["remaining_pct"], 88.0)
        # Duration-only window still gets an absolute UTC resets_at
        self.assertIsNotNone(r["accounts"][1]["five_hour"]["resets_at"])
        self.assertTrue(r["accounts"][1]["five_hour"]["resets_at"].endswith("Z"))
        self.assertEqual(r["accounts"][2]["harness"], "claude")
        self.assertEqual(r["accounts"][2]["email"], "claude@example.com")
        self.assertTrue(r["accounts"][2]["signed_in"])
        self.assertEqual(r["accounts"][2]["five_hour"]["resets_at"], "2026-11-23T08:30:00Z")

        # OpenCode / DeepSeek normalization
        oc = r["accounts"][3]
        self.assertEqual(oc["id"], "opencode")
        self.assertEqual(oc["harness"], "opencode")
        self.assertEqual(oc["label"], "OpenCode / DeepSeek")
        self.assertIsNone(oc["five_hour"])
        self.assertIsNone(oc["weekly"])
        self.assertEqual(oc["deepseek"]["formatted"], "$3.38")
        self.assertAlmostEqual(oc["deepseek"]["total_balance"], 3.38)
        self.assertEqual(oc["zen"]["days"], 7)
        self.assertEqual(oc["zen"]["messages"], 929)
        self.assertEqual(oc["zen"]["total_cost"], "$0.62")
        self.assertEqual(len(oc["zen"]["models"]), 2)
        self.assertEqual(oc["zen"]["models"][0]["messages"], 607)

        # Second call within 60 s hits fresh cache
        self.now[0] += 30.0
        code, r2 = self.call("GET", "/pc/api/agent/usage")
        self.assertEqual(code, 200)
        self.assertTrue(r2["cached"])
        self.assertFalse(r2["stale"])
        self.assertEqual(self.usage_calls, 1)

        # ?refresh=1 bypasses cache synchronously
        code, r3 = self.call("GET", "/pc/api/agent/usage?refresh=1")
        self.assertEqual(code, 200)
        self.assertFalse(r3["cached"])
        self.assertFalse(r3["stale"])
        self.assertEqual(self.usage_calls, 2)

        # TTL expiry serves stale-while-revalidate immediately & refreshes in background
        self.now[0] += 61.0
        code, r4 = self.call("GET", "/pc/api/agent/usage")
        self.assertEqual(code, 200)
        self.assertTrue(r4["cached"])
        self.assertTrue(r4["stale"])
        # Wait briefly for background refresh thread to finish
        deadline = time.monotonic() + 3.0
        while self.usage_calls < 3 and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(self.usage_calls, 3)
        code, r5 = self.call("GET", "/pc/api/agent/usage")
        self.assertEqual(code, 200)
        self.assertTrue(r5["cached"])
        self.assertFalse(r5["stale"])

    def test_usage_disk_cache_survives_restart_without_blocking(self):
        # Prime disk cache
        code, _ = self.call("GET", "/pc/api/agent/usage")
        self.assertEqual(code, 200)
        self.assertTrue(os.path.isfile(self.usage_cache_file))

        # Simulate restart with a slow/blocking ai-usage
        unblock = threading.Event()
        bg_started = threading.Event()

        def slow_run(argv, **kw):
            bg_started.set()
            unblock.wait(timeout=5.0)
            self.usage_calls += 1
            return pcapi.subprocess.CompletedProcess(argv, 0, json.dumps(self.usage_report), "")

        pcapi.AGENT_USAGE = pcapi.AgentUsageManager(
            script=self.usage_script,
            run_fn=slow_run,
            clock=lambda: self.now[0],
            ttl=60.0,
            claude_config_path=self.claude_cfg,
            cache_path=self.usage_cache_file,
        )
        t0 = time.monotonic()
        code, r = self.call("GET", "/pc/api/agent/usage")
        elapsed = time.monotonic() - t0
        self.assertEqual(code, 200)
        self.assertTrue(r["cached"])
        self.assertTrue(r["stale"])
        self.assertEqual(len(r["accounts"]), 4)
        self.assertLess(elapsed, 1.0)
        self.assertTrue(bg_started.wait(timeout=2.0))
        unblock.set()

    def test_settings_get_update_validation_and_fresh_flag(self):
        # Populate cached emails first
        self.assertEqual(self.call("GET", "/pc/api/agent/usage")[0], 200)

        code, s = self.call("GET", "/pc/api/agent/settings")
        self.assertEqual(code, 200)
        self.assertEqual(s["claude"]["account"], "claude@example.com")
        self.assertEqual(s["claude"]["model"], "claude-opus-5-5")
        self.assertEqual(s["claude"]["effort"], "high")
        self.assertEqual(s["gemini"]["instance_id"], "antigravity-2")
        self.assertEqual(s["gemini"]["instances"][1]["email"], "gem2@gmail.com")
        self.assertFalse(s["gemini"]["auto_rotate"])

        # Reject invalid model / effort / instance
        self.assertEqual(self.call("POST", "/pc/api/agent/settings", {"claude": {"model": "nope"}})[0], 400)
        self.assertEqual(self.call("POST", "/pc/api/agent/settings", {"claude": {"effort": "ultra"}})[0], 400)
        self.assertEqual(self.call("POST", "/pc/api/agent/settings", {"gemini": {"instance_id": "unknown"}})[0], 400)
        self.assertEqual(self.call("POST", "/pc/api/agent/settings", {"gemini": {"thought_level": "max"}})[0], 400)
        self.assertEqual(self.call("POST", "/pc/api/agent/settings", {"gemini": {"auto_rotate": "yes"}})[0], 400)

        # Valid update persists both configs and touches .fresh-gemini on instance change
        code, u = self.call("POST", "/pc/api/agent/settings", {
            "claude": {"model": "claude-sonnet-5-5", "effort": "xhigh"},
            "gemini": {
                "instance_id": "antigravity",
                "model": "gemini-4-argon",
                "thought_level": "high",
                "auto_rotate": True,
            },
        })
        self.assertEqual(code, 200)
        self.assertEqual(u["claude"]["model"], "claude-sonnet-5-5")
        self.assertEqual(u["claude"]["effort"], "xhigh")
        self.assertEqual(u["gemini"]["instance_id"], "antigravity")
        self.assertEqual(u["gemini"]["model"], "gemini-4-argon")
        self.assertEqual(u["gemini"]["thought_level"], "high")
        self.assertTrue(u["gemini"]["auto_rotate"])
        self.assertTrue(os.path.exists(self.fresh_flag))

        # Verify files on disk
        with open(self.claude_agent_cfg) as f:
            self.assertEqual(json.load(f), {"model": "claude-sonnet-5-5", "effort": "xhigh"})
        with open(self.gemini_agent_cfg) as f:
            self.assertEqual(json.load(f), {
                "instance_id": "antigravity",
                "model": "gemini-4-argon",
                "thought_level": "high",
                "auto_rotate": True,
            })

    def test_reset_endpoint(self):
        code, r = self.call("POST", "/pc/api/agent/reset", {})
        self.assertEqual(code, 200)
        self.assertTrue(r["ok"])
        self.assertIn("fresh started", r["message"])

        pcapi.AGENT_RESET_BIN = os.path.join(self.dir, "missing-reset")
        code, r2 = self.call("POST", "/pc/api/agent/reset", {})
        self.assertEqual(code, 503)
        self.assertIn("error", r2)


class OtaMirrorTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.ota_dir = os.path.join(self.dir, "ota")
        self.art_dir = os.path.join(self.dir, "artifacts")
        os.makedirs(self.ota_dir)
        os.makedirs(self.art_dir)
        self.orig = (pcapi.OTA_DIR, pcapi.OTA_ARTIFACTS_DIR)
        pcapi.OTA_DIR = self.ota_dir
        pcapi.OTA_ARTIFACTS_DIR = self.art_dir
        self.srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        pcapi.OTA_DIR, pcapi.OTA_ARTIFACTS_DIR = self.orig
        shutil.rmtree(self.dir)

    def fetch(self, path):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", headers=_with_local_auth(path=path))
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.headers.get("Content-Type", ""), r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Content-Type", ""), e.read()

    def test_404_when_missing_and_fallback_to_artifacts_then_override(self):
        code, _, _ = self.fetch("/pc/api/ota/ota.version.json")
        self.assertEqual(code, 404)
        code, _, _ = self.fetch("/pc/api/ota/bundle.zip")
        self.assertEqual(code, 404)

        # Fallback to artifacts dir when ota_dir is empty
        with open(os.path.join(self.art_dir, "ota.version.json"), "w") as f:
            json.dump({"version": "27"}, f)
        with open(os.path.join(self.art_dir, "bundle.zip"), "wb") as f:
            f.write(b"PK-art")

        code, ctype, body = self.fetch("/pc/api/ota/ota.version.json")
        self.assertEqual(code, 200)
        self.assertIn("application/json", ctype)
        self.assertEqual(json.loads(body)["version"], "27")

        code, ctype, body = self.fetch("/pc/api/ota/bundle.zip")
        self.assertEqual(code, 200)
        self.assertEqual(ctype, "application/zip")
        self.assertEqual(body, b"PK-art")

        # ota_dir takes precedence over artifacts dir
        with open(os.path.join(self.ota_dir, "ota.version.json"), "w") as f:
            json.dump({"version": "28"}, f)
        code, _, body = self.fetch("/pc/api/ota/ota.version.json")
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body)["version"], "28")


class CatCamTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.unit_dir = os.path.join(self.dir, "systemd")
        os.makedirs(self.unit_dir)
        # Create 4 of the 5 managed units; leave cam-music missing to test skipping.
        for u in ("catcam", "cam-ptz", "camrec", "catdetect"):
            with open(os.path.join(self.unit_dir, f"{u}.service"), "w") as f:
                f.write("[Unit]\nDescription=test\n")
        self.state_file = os.path.join(self.dir, "catcam-state.json")
        self.calls = []
        self.active = {u: "active" for u in ("catcam", "cam-ptz", "camrec", "catdetect")}

        def fake_run(argv, **kw):
            self.calls.append(list(argv))
            if argv[:3] == ["systemctl", "--user", "is-active"]:
                lines = [self.active.get(u.removesuffix(".service"), "inactive") for u in argv[3:]]
                rc = 0 if all(x == "active" for x in lines) else 3
                return pcapi.subprocess.CompletedProcess(argv, rc, "\n".join(lines) + "\n", "")
            if argv[:4] == ["systemctl", "--user", "disable", "--now"]:
                for u in argv[4:]:
                    self.active[u.removesuffix(".service")] = "inactive"
                return pcapi.subprocess.CompletedProcess(argv, 0, "", "")
            if argv[:4] == ["systemctl", "--user", "enable", "--now"]:
                for u in argv[4:]:
                    self.active[u.removesuffix(".service")] = "active"
                return pcapi.subprocess.CompletedProcess(argv, 0, "", "")
            return pcapi.subprocess.CompletedProcess(argv, 1, "", "unexpected cmd")

        self.fake_run = fake_run
        self.orig_catcam = pcapi.CATCAM
        pcapi.CATCAM = pcapi.CatCamManager(unit_dir=self.unit_dir, state_file=self.state_file, run_fn=fake_run)
        self.srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        pcapi.CATCAM = self.orig_catcam
        shutil.rmtree(self.dir)

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        hdrs = _with_local_auth({"Content-Type": "application/json"} if data is not None else {}, path)
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}", data=data, method=method,
            headers=hdrs,
        )
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_toggle_off_and_on_skips_missing_units_and_persists(self):
        code, st = self.call("GET", "/pc/api/catcam")
        self.assertEqual(code, 200)
        self.assertTrue(st["enabled"])
        self.assertEqual(set(st["units"].keys()), {"catcam", "cam-ptz", "camrec", "catdetect"})
        self.assertTrue(all(v == "active" for v in st["units"].values()))

        # Validation
        self.assertEqual(self.call("POST", "/pc/api/catcam", {"enabled": "no"})[0], 400)

        # Turn off -> disable --now only existing units
        code, st = self.call("POST", "/pc/api/catcam", {"enabled": False})
        self.assertEqual(code, 200)
        self.assertFalse(st["enabled"])
        self.assertTrue(all(v == "inactive" for v in st["units"].values()))
        self.assertIn(
            ["systemctl", "--user", "disable", "--now",
             "catcam.service", "cam-ptz.service", "camrec.service", "catdetect.service"],
            self.calls,
        )

        # Persists across a fresh manager instance
        mgr2 = pcapi.CatCamManager(unit_dir=self.unit_dir, state_file=self.state_file, run_fn=self.fake_run)
        self.assertFalse(mgr2.status()["enabled"])

        # Turn back on -> enable --now
        code, st = self.call("POST", "/pc/api/catcam", {"enabled": True})
        self.assertEqual(code, 200)
        self.assertTrue(st["enabled"])
        self.assertTrue(all(v == "active" for v in st["units"].values()))
        self.assertIn(
            ["systemctl", "--user", "enable", "--now",
             "catcam.service", "cam-ptz.service", "camrec.service", "catdetect.service"],
            self.calls,
        )

    def test_kbd_lock_never_touched_by_status_off_or_on(self):
        for u in ("cam-music", "kbd-lock"):
            with open(os.path.join(self.unit_dir, f"{u}.service"), "w") as f:
                f.write("[Unit]\nDescription=test\n")
        self.active["cam-music"] = "active"
        self.active["kbd-lock"] = "active"

        # Status reports only the 5 managed units, excluding kbd-lock.
        code, st = self.call("GET", "/pc/api/catcam")
        self.assertEqual(code, 200)
        self.assertEqual(set(st["units"].keys()), {"catcam", "cam-ptz", "camrec", "catdetect", "cam-music"})
        self.assertNotIn("kbd-lock", st["units"])
        self.assertTrue(all(v == "active" for v in st["units"].values()))

        # Switch OFF: never mentions kbd-lock and leaves its state untouched.
        self.calls.clear()
        code, st = self.call("POST", "/pc/api/catcam", {"enabled": False})
        self.assertEqual(code, 200)
        self.assertFalse(st["enabled"])
        self.assertNotIn("kbd-lock", st["units"])
        self.assertIn(
            ["systemctl", "--user", "disable", "--now",
             "catcam.service", "cam-ptz.service", "camrec.service", "catdetect.service", "cam-music.service"],
            self.calls,
        )
        for argv in self.calls:
            self.assertFalse(any("kbd-lock" in arg for arg in argv), f"OFF should never mention kbd-lock: {argv}")
        self.assertEqual(self.active["kbd-lock"], "active")

        # Switch ON: never mentions kbd-lock and leaves its state untouched.
        self.active["kbd-lock"] = "inactive"
        self.calls.clear()
        code, st = self.call("POST", "/pc/api/catcam", {"enabled": True})
        self.assertEqual(code, 200)
        self.assertTrue(st["enabled"])
        self.assertNotIn("kbd-lock", st["units"])
        self.assertIn(
            ["systemctl", "--user", "enable", "--now",
             "catcam.service", "cam-ptz.service", "camrec.service", "catdetect.service", "cam-music.service"],
            self.calls,
        )
        for argv in self.calls:
            self.assertFalse(any("kbd-lock" in arg for arg in argv), f"ON should never mention kbd-lock: {argv}")
        self.assertEqual(self.active["kbd-lock"], "inactive")


class UsageAlertTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.sent = []

        class FakeNotify:
            def __init__(self, out):
                self.out = out

            def send(self, cat, title, body=""):
                self.out.append((cat, title, body))
                return 200, {"ok": True}

        self.notify = FakeNotify(self.sent)
        self.settings_path = os.path.join(self.dir, "usage-alerts.json")
        self.state_path = os.path.join(self.dir, "usage-alerts-state.json")
        self.settings = pcapi.AgentSettingsManager(
            claude_path=os.path.join(self.dir, "claude.json"),
            gemini_path=os.path.join(self.dir, "gemini.json"),
            usage_alerts_path=self.settings_path,
        )

    def tearDown(self):
        shutil.rmtree(self.dir)

    def _acc(self, aid="claude", email="me@example.com", fh=50, fh_reset="2026-11-23T18:00:00Z",
             wk=50, wk_reset="2026-11-29T00:00:00Z"):
        return {
            "id": aid,
            "harness": "claude",
            "label": "Claude",
            "email": email,
            "signed_in": True,
            "status": None,
            "error": None,
            "five_hour": {"remaining_pct": fh, "used_pct": 100 - fh, "resets_at": fh_reset},
            "weekly": {"remaining_pct": wk, "used_pct": 100 - wk, "resets_at": wk_reset},
        }

    def test_thresholds_dedupe_and_window_reset(self):
        w = pcapi.UsageAlertWatcher(self.notify, None, self.settings, state_file=self.state_path)

        # Above thresholds -> no alert
        self.assertEqual(w.evaluate([self._acc(fh=50, wk=50)]), [])
        self.assertEqual(len(self.sent), 0)

        # 5h crosses <= 20% -> sends once
        res = w.evaluate([self._acc(fh=20, wk=50, fh_reset="2026-11-23T18:00:00Z")])
        self.assertEqual(len(res), 1)
        self.assertEqual(res[0][1], "five_hour_low")
        self.assertEqual(self.sent[-1][0], "usage_limits")

        # Same window -> deduped (no second alert even as remaining drops to 12%)
        self.assertEqual(w.evaluate([self._acc(fh=12, wk=50, fh_reset="2026-11-23T18:00:00Z")]), [])

        # Hits 0% -> sends five_hour_zero once
        res = w.evaluate([self._acc(fh=0, wk=50, fh_reset="2026-11-23T18:00:00Z")])
        self.assertEqual([r[1] for r in res], ["five_hour_zero"])
        self.assertEqual(w.evaluate([self._acc(fh=0, wk=50, fh_reset="2026-11-23T18:00:00Z")]), [])

        # New resets_at window -> dedupe resets and alerts fire again
        res = w.evaluate([self._acc(fh=15, wk=50, fh_reset="2026-11-23T23:00:00Z")])
        self.assertEqual([r[1] for r in res], ["five_hour_low"])

        # Weekly <= 10% -> sends weekly_low once per weekly resets_at
        res = w.evaluate([self._acc(fh=15, wk=10, fh_reset="2026-11-23T23:00:00Z", wk_reset="2026-11-29T00:00:00Z")])
        self.assertEqual([r[1] for r in res], ["weekly_low"])
        self.assertEqual(
            w.evaluate([self._acc(fh=15, wk=8, fh_reset="2026-11-23T23:00:00Z", wk_reset="2026-11-29T00:00:00Z")]),
            [],
        )
        res = w.evaluate([self._acc(fh=15, wk=9, fh_reset="2026-11-23T23:00:00Z", wk_reset="2026-12-06T00:00:00Z")])
        self.assertEqual([r[1] for r in res], ["weekly_low"])

        # Persists dedupe across restart
        w2 = pcapi.UsageAlertWatcher(self.notify, None, self.settings, state_file=self.state_path)
        self.assertEqual(
            w2.evaluate([self._acc(fh=15, wk=9, fh_reset="2026-11-23T23:00:00Z", wk_reset="2026-12-06T00:00:00Z")]),
            [],
        )

    def test_configurable_thresholds_and_disable(self):
        code, s = self.settings.update({"usage_alerts": {"enabled": True, "five_hour_pct": 30, "weekly_pct": 15}})
        self.assertEqual(code, 200)
        self.assertEqual(s["usage_alerts"], {"enabled": True, "five_hour_pct": 30, "weekly_pct": 15})
        self.assertEqual(self.settings.update({"usage_alerts": {"five_hour_pct": 0}})[0], 400)

        w = pcapi.UsageAlertWatcher(self.notify, None, self.settings, state_file=self.state_path)
        res = w.evaluate([self._acc(fh=28, wk=14)])
        self.assertEqual({r[1] for r in res}, {"five_hour_low", "weekly_low"})

        # Disabled -> no alerts even at 0%
        self.settings.update({"usage_alerts": {"enabled": False}})
        self.assertEqual(w.evaluate([self._acc(fh=0, wk=0, fh_reset="new", wk_reset="new")]), [])

    def test_activity_triggered_no_polling_and_5min_throttle(self):
        watch_dir = os.path.join(self.dir, "logs")
        os.makedirs(watch_dir)
        log_file = os.path.join(watch_dir, "acp.log")
        with open(log_file, "w") as f:
            f.write("init\n")

        now = [1000.0]
        refreshes = []

        class FakeUsageMgr:
            on_update = None

            def get(self, force_refresh=False):
                refreshes.append(force_refresh)
                return 200, {"accounts": []}

        w = pcapi.UsageAlertWatcher(
            self.notify, FakeUsageMgr(), self.settings,
            state_file=self.state_path, watch_paths=[(watch_dir, 1)],
            min_interval=300.0, clock=lambda: now[0],
        )

        # Baseline scan -> zero network calls
        self.assertFalse(w.check_activity())
        self.assertEqual(len(refreshes), 0)

        # Idle checks -> zero network calls even hours later
        now[0] += 3600.0
        self.assertFalse(w.check_activity())
        self.assertEqual(len(refreshes), 0)

        # Local file touched -> triggers one refresh
        os.utime(log_file, ns=(time.time_ns() + 10_000_000, time.time_ns() + 10_000_000))
        self.assertTrue(w.check_activity())
        self.assertEqual(refreshes, [True])

        # Another write 60 s later -> throttled (< 5 min)
        now[0] += 60.0
        os.utime(log_file, ns=(time.time_ns() + 20_000_000, time.time_ns() + 20_000_000))
        self.assertFalse(w.check_activity())
        self.assertEqual(len(refreshes), 1)

        # After 5 min -> pending activity triggers exactly one refresh, then quiet again
        now[0] += 240.0
        self.assertTrue(w.check_activity())
        self.assertEqual(len(refreshes), 2)
        now[0] += 600.0
        self.assertFalse(w.check_activity())
        self.assertEqual(len(refreshes), 2)


class KeyringTests(unittest.TestCase):
    def _proc(self, rc=0, stdout="", stderr=""):
        return mock.Mock(returncode=rc, stdout=stdout, stderr=stderr)

    def test_status_locked_unlocked_and_dbus_error(self):
        state = {"locked": True, "rdp": "inactive"}

        def fake_run(argv, **kw):
            if argv[0] == "busctl":
                return self._proc(0, "b true\n" if state["locked"] else "b false\n")
            if argv[0] == "systemctl" and "is-active" in argv:
                return self._proc(0 if state["rdp"] == "active" else 3, state["rdp"] + "\n")
            raise AssertionError(f"unexpected cmd: {argv}")

        km = pcapi.KeyringManager(run_fn=fake_run)
        self.assertEqual(km.status(), {"locked": True, "rdp": "inactive"})

        state["locked"] = False
        state["rdp"] = "active"
        self.assertEqual(km.status(), {"locked": False, "rdp": "active"})

        km_err = pcapi.KeyringManager(run_fn=lambda argv, **kw: self._proc(1, "", "bus error"))
        with self.assertRaises(RuntimeError):
            km_err.status()

    def test_unlock_success_uses_stdin_restarts_rdp_and_never_leaks_secret(self):
        secret = "Sup3rS3cret!Pass#42"
        calls = []
        state = {"locked": True, "rdp": "inactive"}

        def fake_run(argv, **kw):
            calls.append((list(argv), dict(kw)))
            if argv[0].endswith("keyring-unlock"):
                # Verify secret is ONLY in stdin, never in argv or env
                for a in argv:
                    self.assertNotIn(secret, a)
                for k, v in (kw.get("env") or {}).items():
                    self.assertNotIn(secret, str(k))
                    self.assertNotIn(secret, str(v))
                self.assertEqual(kw.get("input"), secret + "\n")
                state["locked"] = False
                return self._proc(0, "Unlocked\n")
            if argv[0] == "systemctl" and "restart" in argv:
                self.assertEqual(argv, ["systemctl", "--user", "restart", "gnome-remote-desktop"])
                state["rdp"] = "active"
                return self._proc(0, "")
            if argv[0] == "busctl":
                return self._proc(0, "b true\n" if state["locked"] else "b false\n")
            if argv[0] == "systemctl" and "is-active" in argv:
                return self._proc(0 if state["rdp"] == "active" else 3, state["rdp"] + "\n")
            raise AssertionError(f"unexpected cmd: {argv}")

        km = pcapi.KeyringManager(unlock_bin="/fake/keyring-unlock", run_fn=fake_run)
        code, body = km.unlock(secret)
        self.assertEqual(code, 200)
        self.assertEqual(body, {"ok": True, "locked": False, "rdp": "active"})
        self.assertNotIn(secret, json.dumps(body))
        self.assertEqual(calls[0][0], ["/fake/keyring-unlock", "--stdin"])
        self.assertIn(["systemctl", "--user", "restart", "gnome-remote-desktop"], [c[0] for c in calls])

    def test_unlock_failure_returns_401_and_rate_limits_after_5_in_10_minutes(self):
        secret = "WrongPass-9999"
        now = [1000.0]
        restarts = []

        def fake_run(argv, **kw):
            if argv[0].endswith("keyring-unlock"):
                return self._proc(1, "", f"Failed: wrong password ({secret})")
            if argv[0] == "systemctl" and "restart" in argv:
                restarts.append(argv)
                return self._proc(0, "")
            raise AssertionError(f"unexpected cmd: {argv}")

        km = pcapi.KeyringManager(unlock_bin="/fake/keyring-unlock", run_fn=fake_run, clock=lambda: now[0])
        for i in range(5):
            code, body = km.unlock(secret)
            self.assertEqual(code, 401, f"attempt {i + 1}")
            self.assertFalse(body["ok"])
            self.assertNotIn(secret, json.dumps(body))
            now[0] += 10.0

        # 6th attempt within 10 minutes -> 429
        code, body = km.unlock(secret)
        self.assertEqual(code, 429)
        self.assertFalse(body["ok"])
        self.assertGreater(body["retry_after"], 0)
        self.assertEqual(restarts, [])

        # Advance past 10 minutes from the first failure -> allowed again
        now[0] = 1601.0
        code, _ = km.unlock(secret)
        self.assertEqual(code, 401)

    def test_http_endpoints_auth_and_no_secret_in_logs_or_errors(self):
        import io
        secret = "TopSecretHTTP#77"
        state = {"locked": True, "rdp": "inactive"}

        def fake_run(argv, **kw):
            if argv[0] == "busctl":
                return self._proc(0, "b true\n" if state["locked"] else "b false\n")
            if argv[0] == "systemctl" and "is-active" in argv:
                return self._proc(3 if state["rdp"] != "active" else 0, state["rdp"] + "\n")
            if argv[0].endswith("keyring-unlock"):
                if kw.get("input") == secret + "\n":
                    state["locked"] = False
                    return self._proc(0, "Unlocked\n")
                return self._proc(1, "", "Failed")
            if argv[0] == "systemctl" and "restart" in argv:
                state["rdp"] = "active"
                return self._proc(0, "")
            raise AssertionError(f"unexpected cmd: {argv}")

        km = pcapi.KeyringManager(unlock_bin="/fake/keyring-unlock", run_fn=fake_run)
        srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{srv.server_address[1]}"

        def req(method, path, payload=None, raw=None, headers=None):
            data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
            hdrs = {"Content-Type": "application/json"} if data is not None else {}
            if headers:
                hdrs.update(headers)
            r = urllib.request.Request(base + path, data=data, headers=hdrs, method=method)
            try:
                with urllib.request.urlopen(r, timeout=5) as resp:
                    return resp.status, json.loads(resp.read())
            except urllib.error.HTTPError as e:
                return e.code, json.loads(e.read())

        stderr_buf = io.StringIO()
        try:
            with mock.patch.object(pcapi, "KEYRING", km), \
                 mock.patch.object(pcapi, "ALLOWED_LOGINS", {"ayush@example.com"}), \
                 mock.patch.object(sys, "stderr", stderr_buf):
                # Forbidden tailnet login rejected on both GET and POST
                code, _ = req("GET", "/pc/api/keyring", headers={"Tailscale-User-Login": "other@example.com"})
                self.assertEqual(code, 403)
                code, _ = req("POST", "/pc/api/keyring/unlock", {"password": secret},
                              headers={"Tailscale-User-Login": "other@example.com"})
                self.assertEqual(code, 403)

                # Malformed JSON containing secret must not echo or log secret
                code, body = req("POST", "/pc/api/keyring/unlock", raw=f'{{"password": "{secret}'.encode(),
                                 headers={"Tailscale-User-Login": "ayush@example.com"})
                self.assertEqual(code, 400)
                self.assertNotIn(secret, json.dumps(body))

                # GET locked status
                code, body = req("GET", "/pc/api/keyring", headers={"Tailscale-User-Login": "ayush@example.com"})
                self.assertEqual((code, body), (200, {"locked": True, "rdp": "inactive"}))

                # POST unlock success
                code, body = req("POST", "/pc/api/keyring/unlock", {"password": secret},
                                 headers={"Tailscale-User-Login": "ayush@example.com"})
                self.assertEqual((code, body), (200, {"ok": True, "locked": False, "rdp": "active"}))
                self.assertNotIn(secret, json.dumps(body))

                # Unexpected exception inside unlock must not log secret to stderr
                with mock.patch.object(km, "unlock", side_effect=RuntimeError(f"boom {secret}")):
                    code, body = req("POST", "/pc/api/keyring/unlock", {"password": secret},
                                     headers={"Tailscale-User-Login": "ayush@example.com"})
                    self.assertEqual(code, 500)
                    self.assertNotIn(secret, json.dumps(body))

                self.assertNotIn(secret, stderr_buf.getvalue())
        finally:
            srv.shutdown()
            srv.server_close()

    def test_startup_notification_only_when_locked(self):
        sent = []

        class FakeNotify:
            def send_async(self, cat, title, body, *a, **kw):
                sent.append((cat, title, body))

        def run_locked(argv, **kw):
            if argv[0] == "busctl":
                return self._proc(0, "b true\n")
            return self._proc(3, "inactive\n")

        def run_unlocked(argv, **kw):
            if argv[0] == "busctl":
                return self._proc(0, "b false\n")
            return self._proc(0, "active\n")

        km_locked = pcapi.KeyringManager(notify=FakeNotify(), run_fn=run_locked)
        self.assertTrue(km_locked.notify_if_locked_on_startup())
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0][0], "keyring")
        self.assertIn("Keyring locked after reboot: tap to unlock RDP", sent[0][2])

        sent.clear()
        km_unlocked = pcapi.KeyringManager(notify=FakeNotify(), run_fn=run_unlocked)
        self.assertFalse(km_unlocked.notify_if_locked_on_startup())
        self.assertEqual(sent, [])

        # D-Bus unavailable at startup -> returns False without raising
        km_err = pcapi.KeyringManager(notify=FakeNotify(), run_fn=lambda argv, **kw: self._proc(1, "", "err"))
        self.assertFalse(km_err.notify_if_locked_on_startup())
        self.assertEqual(sent, [])


class LocalTokenTests(unittest.TestCase):
    """Localhost requests without tailscale-serve headers require ~/.config/pc-control/local-token (0600),
    except /pc/api/hook/* which uses its own shared hook-secret."""

    def test_local_token_required_and_webhooks_work_without_it(self):
        with tempfile.TemporaryDirectory() as d:
            tok_file = os.path.join(d, "local-token")
            hook_file = os.path.join(d, "hook-secret")
            with open(hook_file, "w") as f:
                f.write("h" * 40 + "\n")
            fake_push = FakePush()
            fake_notify = pcapi.Notify(fake_push, data_dir=d, token_file="/nonexistent", chat="")
            fake_notify.update({"categories": {"grabbed": {"telegram": False}}})
            srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            base = f"http://127.0.0.1:{srv.server_address[1]}"

            def raw_req(method, path, body=None, headers=None):
                data = json.dumps(body).encode() if body is not None else None
                hdrs = {"Content-Type": "application/json", **(headers or {})}
                r = urllib.request.Request(base + path, data=data, method=method, headers=hdrs)
                try:
                    with urllib.request.urlopen(r, timeout=5) as resp:
                        return resp.status, json.loads(resp.read())
                except urllib.error.HTTPError as e:
                    return e.code, json.loads(e.read())

            try:
                with mock.patch.object(pcapi, "LOCAL_TOKEN_FILE", tok_file), \
                     mock.patch.object(pcapi, "HOOK_SECRET_FILE", hook_file), \
                     mock.patch.object(pcapi, "PUSH", fake_push), \
                     mock.patch.object(pcapi, "NOTIFY", fake_notify), \
                     mock.patch.object(pcapi, "ALLOWED_LOGINS", {"me@example.com"}):
                    tok = pcapi.local_token()
                    self.assertTrue(os.path.exists(tok_file))
                    self.assertEqual(oct(os.stat(tok_file).st_mode & 0o777), "0o600")

                    # Localhost without local-token -> 403
                    self.assertEqual(raw_req("GET", "/pc/api/notify/settings")[0], 403)
                    # Wrong bearer token -> 403
                    self.assertEqual(raw_req("GET", "/pc/api/notify/settings",
                                             headers={"Authorization": "Bearer wrong"})[0], 403)
                    # Valid bearer token -> 200
                    self.assertEqual(raw_req("GET", "/pc/api/notify/settings",
                                             headers={"Authorization": f"Bearer {tok}"})[0], 200)
                    # Proxied request (X-Forwarded-For) with valid local token -> 403
                    self.assertEqual(raw_req("GET", "/pc/api/notify/settings",
                                             headers={"Authorization": f"Bearer {tok}",
                                                      "X-Forwarded-For": "100.64.0.9"})[0], 403)
                    # Webhook from localhost WITHOUT local-token: missing hook-secret -> 401
                    self.assertEqual(raw_req("POST", "/pc/api/hook/radarr", {"eventType": "Test"})[0], 401)
                    # Webhook from localhost WITHOUT local-token, WITH hook-secret -> 200
                    code, res = raw_req("POST", "/pc/api/hook/radarr", {"eventType": "Test"},
                                        headers={"Authorization": "Bearer " + "h" * 40})
                    self.assertEqual(code, 200, res)
                    self.assertTrue(res.get("ok"))
            finally:
                srv.shutdown()
                srv.server_close()



