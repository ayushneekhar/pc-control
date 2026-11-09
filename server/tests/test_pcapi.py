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


if __name__ == "__main__":
    unittest.main()
