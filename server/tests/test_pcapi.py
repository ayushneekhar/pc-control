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
    def __init__(self): self.sent, self.root, self.tokens = [], [], {"t": {}}
    def configured(self): return True
    def status(self, token=None): return {"configured": True, "devices": 1}
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


if __name__ == "__main__":
    unittest.main()
