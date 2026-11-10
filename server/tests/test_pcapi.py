"""pc-api route classification (API-only routes hidden server-side, overridable via routes.json)."""
import json, os, sys, tempfile, threading, time, unittest, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402

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
        self.assertEqual(visible(), {"Cat cams", "Vitals", "Cameras", "Seerr", "Bazarr", "Radarr", "Sonarr", "New Api"})
        hidden = {r["path"]: r["kind"] for r in pcapi.build_routes(STATUS, {}) if r["hidden"]}
        self.assertEqual(hidden, {"/detect": "api", "/music": "api", "/ptz": "api", "/rec": "api"})
        self.assertNotIn("/pc", [r["path"] for r in pcapi.build_routes(STATUS, {})])

    def test_config_hides_new_route_and_unhides_builtin(self):
        v = visible({"/new-api": {"kind": "api"}, "/rec": {"hidden": False}, ":5055": {"hidden": True}})
        self.assertNotIn("New Api", v)
        self.assertNotIn("Seerr", v)
        self.assertIn("Recordings", v)
        self.assertNotIn("New Api", visible({"/new-api": {"hidden": True}}))

    def test_bad_override_ignored(self):
        self.assertIn("Vitals", visible({"/vitals": "nonsense"}))


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
        cls.saved = (pcapi.PUSH, pcapi.NOTIFY, pcapi.DOWNLOADS, pcapi.HOOK_SECRET_FILE, pcapi.ALLOWED_LOGINS)
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
        pcapi.PUSH, pcapi.NOTIFY, pcapi.DOWNLOADS, pcapi.HOOK_SECRET_FILE, pcapi.ALLOWED_LOGINS = cls.saved

    def setUp(self):
        FakeQbt.torrents[:] = [torrent(HASH_A, "Film.2001.1080p", "downloading", "radarr")]
        pcapi.DOWNLOADS.cache = (0.0, None)
        pcapi.PUSH.queued.clear()
        pcapi.NOTIFY._live_at = 0

    def call(self, method, path, body=None, headers=None):
        import urllib.request, urllib.error
        req = urllib.request.Request(self.url + path, method=method, headers={"Content-Type": "application/json",
                                     **(headers or {})}, data=json.dumps(body).encode() if body is not None else None)
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
