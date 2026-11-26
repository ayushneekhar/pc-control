"""Unit tests for Agentview master toggle endpoints (/pc/api/agentview/*)."""
import json
import os
import shutil
import stat
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402


class _FakeClock:
    def __init__(self, start=100.0):
        self.t = float(start)

    def now(self):
        return self.t

    def sleep(self, secs):
        self.t += float(secs)


class AgentViewEndpointsTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pcapi-agentview-test-")
        self.orig_tok_file = pcapi.LOCAL_TOKEN_FILE
        pcapi.LOCAL_TOKEN_FILE = os.path.join(self.dir, "local-token")
        self.state_file = os.path.join(self.dir, "state", "capture.state")
        self.rules_file = os.path.join(self.dir, "rules.json")
        self.clock = _FakeClock()
        self.calls = []
        self.web_up = False
        self.probe_count = 0
        self.units = {
            "agentview-front.service": "inactive",
            "agentview-agentproxy.service": "inactive",
            "agentview-agentproxy-watch.timer": "inactive",
            "agentview-mitm.service": "inactive",
            "agentview-web.service": "inactive",
        }

        def fake_run(argv, **kw):
            self.calls.append(list(argv))
            if "is-active" in argv:
                idx = argv.index("is-active") + 1
                lines = [self.units.get(u, "inactive") for u in argv[idx:]]
                rc = 0 if all(x == "active" for x in lines) else 3
                return pcapi.subprocess.CompletedProcess(argv, rc, "\n".join(lines) + "\n", "")
            if argv == ["systemctl", "--user", "start", "agentview-web.service"]:
                self.units["agentview-web.service"] = "active"
                self.web_up = True
                return pcapi.subprocess.CompletedProcess(argv, 0, "", "")
            if argv == ["systemctl", "--user", "stop", "agentview-web.service"]:
                self.units["agentview-web.service"] = "inactive"
                self.web_up = False
                return pcapi.subprocess.CompletedProcess(argv, 0, "", "")
            return pcapi.subprocess.CompletedProcess(argv, 1, "", "unexpected cmd")

        def fake_probe(url, timeout):
            self.probe_count += 1
            return self.web_up

        self.fake_run = fake_run
        self.fake_probe = fake_probe
        self.orig_agentview = pcapi.AGENTVIEW
        pcapi.AGENTVIEW = pcapi.AgentViewManager(
            state_file=self.state_file,
            rules_file=self.rules_file,
            web_url="http://127.0.0.1:1991/agentview/api/state",
            run_fn=fake_run,
            probe_fn=fake_probe,
            sleep_fn=self.clock.sleep,
            monotonic_fn=self.clock.now,
            start_timeout=6.0,
            stop_timeout=4.0,
            poll_interval=0.25,
        )
        self.srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        pcapi.AGENTVIEW = self.orig_agentview
        pcapi.LOCAL_TOKEN_FILE = self.orig_tok_file
        shutil.rmtree(self.dir, ignore_errors=True)

    def call(self, method, path, body=None, auth=True):
        data = None if body is None else json.dumps(body).encode("utf-8")
        hdrs = {}
        if data is not None:
            hdrs["Content-Type"] = "application/json"
        if auth:
            hdrs["Authorization"] = f"Bearer {pcapi.local_token()}"
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=data,
            method=method,
            headers=hdrs,
        )
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read())
            finally:
                e.close()

    def test_requires_authentication(self):
        code, body = self.call("GET", "/pc/api/agentview/state", auth=False)
        self.assertEqual(code, 403)
        self.assertIn("error", body)
        code, body = self.call("POST", "/pc/api/agentview/toggle", {"enabled": True}, auth=False)
        self.assertEqual(code, 403)
        self.assertIn("error", body)

    def test_state_defaults_to_off_when_missing(self):
        code, st = self.call("GET", "/pc/api/agentview/state")
        self.assertEqual(code, 200)
        self.assertFalse(st["enabled"])
        self.assertEqual(st["state"], "off")
        self.assertFalse(st["web_reachable"])
        self.assertEqual(st["services"]["agentview-web"], "inactive")
        self.assertEqual(st["services"]["agentview-front"], "inactive")

    def test_state_reads_on_and_web_reachable(self):
        os.makedirs(os.path.dirname(self.state_file), exist_ok=True)
        with open(self.state_file, "w", encoding="utf-8") as f:
            f.write("on\n")
        self.web_up = True
        for k in self.units:
            self.units[k] = "active"

        code, st = self.call("GET", "/pc/api/agentview/state")
        self.assertEqual(code, 200)
        self.assertTrue(st["enabled"])
        self.assertEqual(st["state"], "on")
        self.assertTrue(st["web_reachable"])
        self.assertTrue(all(v == "active" for v in st["services"].values()))

    def test_toggle_validation(self):
        for bad in ("on", 1, None, {}, []):
            code, body = self.call("POST", "/pc/api/agentview/toggle", {"enabled": bad})
            self.assertEqual(code, 400)
            self.assertIn("error", body)

    def test_toggle_on_creates_state_file_syncs_rules_and_starts_web_fallback(self):
        # Seed rules.json with enabled=False and an existing rule.
        with open(self.rules_file, "w", encoding="utf-8") as f:
            json.dump({"enabled": False, "rules": [{"id": "r1", "enabled": True}]}, f)

        code, res = self.call("POST", "/pc/api/agentview/toggle", {"enabled": True})
        self.assertEqual(code, 200)
        self.assertTrue(res["enabled"])
        self.assertEqual(res["state"], "on")
        self.assertTrue(res["web_reachable"])

        # capture.state was atomically written with "on\n" and mode 0600
        with open(self.state_file, "rb") as f:
            self.assertEqual(f.read(), b"on\n")
        mode = stat.S_IMODE(os.lstat(self.state_file).st_mode)
        self.assertEqual(mode, 0o600)

        # rules.json had enabled synced without losing existing rules
        with open(self.rules_file, "r", encoding="utf-8") as f:
            rules_data = json.load(f)
        self.assertTrue(rules_data["enabled"])
        self.assertEqual(rules_data["rules"], [{"id": "r1", "enabled": True}])

        # Fallback systemctl --user start agentview-web.service was triggered once
        self.assertIn(["systemctl", "--user", "start", "agentview-web.service"], self.calls)

    def test_toggle_on_waits_for_capture_sync_without_unnecessary_systemctl_start(self):
        probes = {"n": 0}

        def delayed_up(url, timeout):
            probes["n"] += 1
            # Simulate agentview-capture-sync bringing agentview-web up on 2nd poll (0.25s < 1.0s fallback)
            return probes["n"] >= 2

        pcapi.AGENTVIEW.probe_fn = delayed_up
        code, res = self.call("POST", "/pc/api/agentview/toggle", {"enabled": True})
        self.assertEqual(code, 200)
        self.assertTrue(res["enabled"])
        self.assertTrue(res["web_reachable"])
        self.assertNotIn(["systemctl", "--user", "start", "agentview-web.service"], self.calls)

    def test_toggle_on_returns_web_unreachable_after_timeout(self):
        pcapi.AGENTVIEW.probe_fn = lambda url, timeout: False
        code, res = self.call("POST", "/pc/api/agentview/toggle", {"enabled": True})
        self.assertEqual(code, 200)
        self.assertTrue(res["enabled"])
        self.assertEqual(res["state"], "on")
        self.assertFalse(res["web_reachable"])
        self.assertIn(["systemctl", "--user", "start", "agentview-web.service"], self.calls)

    def test_toggle_off_stops_web_and_writes_off(self):
        os.makedirs(os.path.dirname(self.state_file), exist_ok=True)
        with open(self.state_file, "w", encoding="utf-8") as f:
            f.write("on\n")
        with open(self.rules_file, "w", encoding="utf-8") as f:
            json.dump({"enabled": True, "rules": []}, f)
        self.web_up = True
        self.units["agentview-web.service"] = "active"

        code, res = self.call("POST", "/pc/api/agentview/toggle", {"enabled": False})
        self.assertEqual(code, 200)
        self.assertFalse(res["enabled"])
        self.assertEqual(res["state"], "off")
        self.assertFalse(res["web_reachable"])

        with open(self.state_file, "rb") as f:
            self.assertEqual(f.read(), b"off\n")
        with open(self.rules_file, "r", encoding="utf-8") as f:
            self.assertFalse(json.load(f)["enabled"])
        self.assertIn(["systemctl", "--user", "stop", "agentview-web.service"], self.calls)

    def test_symlink_capture_state_is_refused_and_target_untouched(self):
        os.makedirs(os.path.dirname(self.state_file), exist_ok=True)
        victim = os.path.join(self.dir, "victim.txt")
        with open(victim, "w", encoding="utf-8") as f:
            f.write("on\n")
        os.symlink(victim, self.state_file)

        # GET must not follow the symlink (should safely report off)
        code, st = self.call("GET", "/pc/api/agentview/state")
        self.assertEqual(code, 200)
        self.assertFalse(st["enabled"])
        self.assertEqual(st["state"], "off")

        # POST toggle must refuse and leave victim intact
        code, body = self.call("POST", "/pc/api/agentview/toggle", {"enabled": False})
        self.assertEqual(code, 500)
        self.assertIn("symlink", body["error"].lower())
        with open(victim, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), "on\n")

    def test_symlink_parent_dir_is_refused(self):
        real_dir = os.path.join(self.dir, "real-dir")
        os.makedirs(real_dir)
        os.symlink(real_dir, os.path.dirname(self.state_file))

        code, body = self.call("POST", "/pc/api/agentview/toggle", {"enabled": True})
        self.assertEqual(code, 500)
        self.assertIn("symlink", body["error"].lower())
        self.assertFalse(os.path.exists(os.path.join(real_dir, "capture.state")))

    def test_real_http_probe_against_local_stub(self):
        class _Stub(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/agentview/api/state":
                    body = b'{"enabled":true,"rules":[]}'
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.send_response(404)
                    self.end_headers()

            def log_message(self, *args):
                pass

        stub = ThreadingHTTPServer(("127.0.0.1", 0), _Stub)
        stub_port = stub.server_address[1]
        threading.Thread(target=stub.serve_forever, daemon=True).start()
        try:
            mgr = pcapi.AgentViewManager(
                state_file=self.state_file,
                web_url=f"http://127.0.0.1:{stub_port}/agentview/api/state",
                run_fn=self.fake_run,
            )
            self.assertTrue(mgr._probe_web(timeout=1.0))
        finally:
            stub.shutdown()
            stub.server_close()
        self.assertFalse(mgr._probe_web(timeout=1.0))


if __name__ == "__main__":
    unittest.main()
