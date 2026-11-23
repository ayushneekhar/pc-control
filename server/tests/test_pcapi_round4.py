"""Unit tests for PC Control round 4 backend endpoints and helpers."""
import json, os, sys, tempfile, threading, unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402


class FakeRunResult:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class ServiceActionTests(unittest.TestCase):
    def test_start_stop_restart_and_pcapi_self_stop_guard(self):
        calls = []

        def fake_run(argv, **kw):
            calls.append(argv)
            return FakeRunResult(0)

        with mock.patch.object(pcapi, "own_units", return_value=["pc-api.service", "kbd-lock.service"]), \
                mock.patch.object(pcapi.subprocess, "run", side_effect=fake_run):
            self.assertEqual(pcapi.service_action("kbd-lock.service", "start"), (200, {"ok": True}))
            self.assertEqual(pcapi.service_action("kbd-lock.service", "stop"), (200, {"ok": True}))
            self.assertEqual(pcapi.service_action("pc-api.service", "restart"), (200, {"ok": True}))
            # Never allow stopping pc-api.service
            code, body = pcapi.service_action("pc-api.service", "stop")
            self.assertEqual(code, 403)
            self.assertIn("cannot stop", body["error"])
            # Unknown unit
            self.assertEqual(pcapi.service_action("ssh.service", "stop")[0], 404)
            # Invalid action
            self.assertEqual(pcapi.service_action("kbd-lock.service", "kill")[0], 400)
        self.assertEqual(
            calls,
            [
                ["systemctl", "--user", "start", "--no-block", "kbd-lock.service"],
                ["systemctl", "--user", "stop", "--no-block", "kbd-lock.service"],
                ["systemctl", "--user", "restart", "--no-block", "pc-api.service"],
            ],
        )

    def test_kbd_lock_status_and_toggle(self):
        state = {"active": "inactive"}

        def fake_run(argv, **kw):
            if argv[2] == "is-active":
                return FakeRunResult(0 if state["active"] == "active" else 3, stdout=state["active"] + "\n")
            if argv[2] == "start":
                state["active"] = "active"
                return FakeRunResult(0)
            if argv[2] == "stop":
                state["active"] = "inactive"
                return FakeRunResult(0)
            return FakeRunResult(1, stderr="unexpected")

        with mock.patch.object(pcapi, "own_units", return_value=["kbd-lock.service"]):
            self.assertEqual(pcapi.kbd_lock_status(run_fn=fake_run), {"available": True, "enabled": False, "active": "inactive"})
            self.assertEqual(pcapi.kbd_lock_set({"enabled": True}, run_fn=fake_run),
                             (200, {"available": True, "enabled": True, "active": "active"}))
            self.assertEqual(pcapi.kbd_lock_set({"enabled": False}, run_fn=fake_run),
                             (200, {"available": True, "enabled": False, "active": "inactive"}))
            self.assertEqual(pcapi.kbd_lock_set({"enabled": "yes"}, run_fn=fake_run)[0], 400)
        with mock.patch.object(pcapi, "own_units", return_value=[]):
            self.assertEqual(pcapi.kbd_lock_status(run_fn=fake_run), {"available": False, "enabled": False, "active": "missing"})
            self.assertEqual(pcapi.kbd_lock_set({"enabled": True}, run_fn=fake_run)[0], 404)


class ArrHookTests(unittest.TestCase):
    def test_health_and_import_failure_events(self):
        w = pcapi.map_hook("radarr", {"eventType": "Health", "level": "warning", "message": "Indexer down"})
        self.assertEqual(w["category"], "problems")
        self.assertIn("Radarr health issue", w["title"])
        self.assertIn("Indexer down", w["body"])

        e = pcapi.map_hook("sonarr", {"eventType": "Health", "level": "error", "message": "Disk full"})
        self.assertEqual(e["category"], "problems")
        self.assertTrue(e["title"].startswith("🔴"))

        r = pcapi.map_hook("sonarr", {"eventType": "HealthRestored", "message": "Indexer back"})
        self.assertEqual(r["category"], "problems")
        self.assertIn("health restored", r["title"])

        imp = pcapi.map_hook("radarr", {
            "eventType": "ImportFailed",
            "movie": {"title": "Inception", "year": 2010},
            "downloadStatusMessages": [{"messages": ["Sample file only"]}],
        })
        self.assertEqual(imp["category"], "problems")
        self.assertIn("Inception (2010)", imp["body"])
        self.assertIn("Sample file only", imp["body"])


class FakeDownloads:
    def __init__(self):
        self.calls = []
        self.alt_speed = 0
        self.arr = {}
        self.arr_at = {}
        self.titles = {}
        self.lock = threading.Lock()
        self.cache = (0.0, None)
        self.torrents = [
            {
                "hash": "a" * 40, "name": "Inception.2010.1080p", "category": "radarr",
                "state": "downloading", "progress": 0.5, "dlspeed": 1024, "upspeed": 128,
                "eta": 60, "size": 1000, "completed": 500, "num_seeds": 5, "ratio": 0.1, "added_on": 100,
            },
            {
                "hash": "b" * 40, "name": "Dune.2021.1080p", "category": "qwen",
                "state": "stoppedDL", "progress": 0.25, "dlspeed": 0, "upspeed": 0,
                "eta": 8640000, "size": 2000, "completed": 500, "num_seeds": 2, "ratio": 0.0, "added_on": 200,
            },
        ]

    def qbt(self, method, timeout=8, get=False, **params):
        self.calls.append((method, get, params))
        if method == "torrents/info":
            return self.torrents
        if method == "transfer/speedLimitsMode":
            return self.alt_speed
        if method == "transfer/toggleSpeedLimitsMode":
            self.alt_speed = 0 if self.alt_speed else 1
            return {}
        return {}


class DownloadsControlTests(unittest.TestCase):
    def test_all_torrents_and_controls(self):
        dl = FakeDownloads()
        with mock.patch.object(pcapi, "arr_queue", return_value={"queue": 0, "needs_action": 0, "items": []}):
            res = pcapi.downloads_all(dl=dl)
        self.assertEqual(res["qbt"], "ok")
        self.assertFalse(res["alt_speed"])
        self.assertEqual(len(res["torrents"]), 2)
        self.assertEqual(res["torrents"][0]["state"], "downloading")
        self.assertFalse(res["torrents"][0]["paused"])
        self.assertEqual(res["torrents"][1]["state"], "paused")
        self.assertTrue(res["torrents"][1]["paused"])

        h = "a" * 40
        self.assertEqual(pcapi.downloads_action("pause", {"hash": h}, dl=dl), (200, {"ok": True}))
        self.assertEqual(pcapi.downloads_action("resume", {"hash": h}, dl=dl), (200, {"ok": True}))
        self.assertEqual(pcapi.downloads_action("delete", {"hash": h, "delete_files": True}, dl=dl), (200, {"ok": True}))
        self.assertEqual(pcapi.downloads_action("pause", {"hash": "bad"}, dl=dl)[0], 400)
        self.assertIn(("torrents/stop", False, {"hashes": h}), dl.calls)
        self.assertIn(("torrents/start", False, {"hashes": h}), dl.calls)
        self.assertIn(("torrents/delete", False, {"hashes": h, "deleteFiles": "true"}), dl.calls)

        self.assertEqual(pcapi.downloads_set_alt_speed({"enabled": True}, dl=dl), (200, {"ok": True, "alt_speed": True}))
        self.assertEqual(pcapi.downloads_set_alt_speed({"enabled": True}, dl=dl), (200, {"ok": True, "alt_speed": True}))
        self.assertEqual(pcapi.downloads_set_alt_speed({"enabled": "1"}, dl=dl)[0], 400)

        notify = mock.Mock()
        code, body = pcapi.downloads_add_magnet({"uri": "magnet:?xt=urn:btih:" + h}, dl=dl, notify=notify)
        self.assertEqual((code, body["ok"], body["category"]), (200, True, "qwen"))
        notify.live_kick.assert_called_once_with("app")
        self.assertEqual(pcapi.downloads_add_magnet({"uri": "https://example.com"}, dl=dl)[0], 400)


class FakeSeerrAndT3Handler(BaseHTTPRequestHandler):
    log = []

    def log_message(self, *a):
        pass

    def reply(self, code, obj, headers=()):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        self.log.append(("GET", self.path, self.headers.get("X-Api-Key")))
        if self.path.startswith("/api/v1/search"):
            return self.reply(200, {"results": [
                {"id": 27205, "mediaType": "movie", "title": "Inception", "releaseDate": "2010-07-15",
                 "posterPath": "/inc.jpg", "overview": "Dreams", "mediaInfo": {"status": 5}},
                {"id": 999, "mediaType": "person", "name": "Nolan"},
            ]})
        if self.path == "/api/v1/movie/27205":
            return self.reply(200, {"id": 27205, "title": "Inception", "releaseDate": "2010-07-15",
                                    "posterPath": "/inc.jpg", "overview": "Dreams", "mediaInfo": {"status": 5}})
        if self.path == "/api/v1/tv/1396":
            return self.reply(200, {
                "id": 1396, "name": "Breaking Bad", "firstAirDate": "2008-01-20", "posterPath": "/bb.jpg",
                "seasons": [
                    {"seasonNumber": 0, "name": "Specials", "episodeCount": 5},
                    {"seasonNumber": 1, "name": "Season 1", "episodeCount": 7},
                    {"seasonNumber": 2, "name": "Season 2", "episodeCount": 13},
                ],
                "mediaInfo": {"status": 4, "seasons": [{"seasonNumber": 1, "status": 5}]},
            })
        if self.path.startswith("/api/v1/request"):
            return self.reply(200, {"results": [
                {
                    "id": 10, "status": 2, "type": "movie", "createdAt": "2026-11-23T10:00:00.000Z",
                    "media": {"tmdbId": 27205, "mediaType": "movie", "status": 3, "downloadStatus": [{"title": "x"}]},
                    "seasons": [], "requestedBy": {"displayName": "Ayush"},
                },
            ]})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        body = json.loads(raw) if raw.strip() else {}
        self.log.append(("POST", self.path, body))
        if self.path == "/api/v1/request":
            return self.reply(201, {"id": 42, "status": 2})
        if self.path == "/Library/Refresh":
            return self.reply(204, {})
        if self.path == "/api/v3/command":
            return self.reply(201, {"id": 77})
        if self.path == "/mcp":
            method = body.get("method")
            if method == "initialize":
                return self.reply(200, {"jsonrpc": "2.0", "id": 1, "result": {}}, headers=[("mcp-session-id", "sid-1")])
            if method == "notifications/initialized":
                return self.reply(200, {})
            if method == "tools/call":
                tname = body["params"]["name"]
                if tname == "t3_thread_list":
                    payload = {"threads": [{
                        "threadId": "mcp:t1", "title": "Feature work", "model": "gemini-4-neon",
                        "providerInstanceId": "antigravity-2", "status": "running", "settled": False,
                        "createdAt": "2026-11-23T16:00:00.000Z", "updatedAt": "2026-11-23T16:30:00.000Z",
                        "messages": [{"text": "SECRET PROMPT SHOULD NEVER LEAK"}],
                    }]}
                    return self.reply(200, {"jsonrpc": "2.0", "id": 2,
                                            "result": {"content": [{"type": "text", "text": json.dumps(payload)}]}})
                if tname == "t3_thread_interrupt":
                    return self.reply(200, {"jsonrpc": "2.0", "id": 2,
                                            "result": {"content": [{"type": "text", "text": json.dumps({"ok": True})}]}})
        self.reply(404, {"error": "not found"})


class SeerrArrJellyfinT3Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeSeerrAndT3Handler)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        cls.dir = tempfile.mkdtemp()
        cls.seerr_conf = os.path.join(cls.dir, "seerr.json")
        with open(cls.seerr_conf, "w") as f:
            json.dump({"main": {"apiKey": "seerr-key-1"}}, f)
        cls.creds_file = os.path.join(cls.dir, "creds.json")
        with open(cls.creds_file, "w") as f:
            json.dump({"mcpOAuth": {"t3|local": {"accessToken": "tok-123"}}}, f)
        cls.jf_key = os.path.join(cls.dir, "jf.key")
        with open(cls.jf_key, "w") as f:
            f.write("jf-secret")
        cls.arr_xml = os.path.join(cls.dir, "arr.xml")
        with open(cls.arr_xml, "w") as f:
            f.write("<Config><ApiKey>arrkey123</ApiKey></Config>")

    def setUp(self):
        FakeSeerrAndT3Handler.log.clear()

    def test_seerr_search_details_requests_create_and_resolve(self):
        c = pcapi.SeerrClient(url=self.base, settings_file=self.seerr_conf)
        code, srch = c.search("inception")
        self.assertEqual(code, 200)
        self.assertEqual(len(srch["results"]), 1)
        self.assertEqual(srch["results"][0]["status"], "available")

        code, tv = c.details("tv", 1396)
        self.assertEqual(code, 200)
        self.assertEqual([s["seasonNumber"] for s in tv["seasons"]], [1, 2])
        self.assertEqual(tv["seasons"][0]["status"], "available")

        code, reqs = c.requests()
        self.assertEqual(code, 200)
        self.assertEqual(reqs["requests"][0]["title"], "Inception")
        self.assertEqual(reqs["requests"][0]["status"], "grabbing")

        self.assertEqual(c.create_request({"mediaType": "tv", "mediaId": 1396, "seasons": [2]})[0], 200)
        self.assertEqual(c.create_request({"mediaType": "tv", "mediaId": 1396, "seasons": []})[0], 400)

        code, resolved = c.resolve_url({"url": "https://www.themoviedb.org/movie/27205-inception"})
        self.assertEqual((code, resolved["title"]), (200, "Inception"))

        with mock.patch.object(c, "_arr_imdb_lookup", return_value=("tv", 1396)):
            code, resolved = c.resolve_url({"url": "https://www.imdb.com/title/tt0903747/"})
            self.assertEqual((code, resolved["title"]), (200, "Breaking Bad"))

    def test_arr_retry_and_jellyfin_refresh(self):
        with mock.patch.object(pcapi, "ARR", {"radarr": ("radarr", self.base, self.arr_xml)}):
            code, body = pcapi.arr_retry_import({"app": "radarr"})
            self.assertEqual((code, body["ok"], body["command_id"]), (200, True, 77))
        self.assertEqual(pcapi.arr_retry_import({"app": "other"})[0], 400)

        with mock.patch.object(pcapi, "JELLYFIN_URL", self.base), \
                mock.patch.object(pcapi, "JELLYFIN_KEY_FILE", self.jf_key):
            self.assertEqual(pcapi.jellyfin_refresh_library(), (200, {"ok": True}))

    def test_t3_threads_never_expose_messages_and_interrupt(self):
        t3 = pcapi.T3ThreadsClient(url=self.base + "/mcp", creds_file=self.creds_file)
        code, body = t3.list_threads()
        self.assertEqual(code, 200)
        self.assertEqual(len(body["threads"]), 1)
        th = body["threads"][0]
        self.assertEqual(set(th.keys()), {"id", "title", "model", "instance", "status", "settled", "created_at", "updated_at"})
        self.assertNotIn("SECRET", json.dumps(body))

        code, res = t3.interrupt_thread({"threadId": "mcp:t1"})
        self.assertEqual((code, res["ok"]), (200, True))
        self.assertEqual(t3.interrupt_thread({"threadId": ""})[0], 400)


if __name__ == "__main__":
    unittest.main()
