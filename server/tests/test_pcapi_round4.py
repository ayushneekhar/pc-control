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
            if "ice%20age" in self.path.lower():
                return self.reply(200, {"results": [
                    {"id": 425, "mediaType": "movie", "title": "Ice Age", "releaseDate": "2002-03-10",
                     "posterPath": "/ia1.jpg", "overview": "Mammoth", "mediaInfo": {"status": 5}},
                    {"id": 950, "mediaType": "movie", "title": "Ice Age: The Meltdown", "releaseDate": "2006-03-23",
                     "posterPath": "/ia2.jpg", "overview": "Meltdown", "mediaInfo": {"status": 7}},
                    {"id": 8355, "mediaType": "movie", "title": "Ice Age: Dawn of the Dinosaurs", "releaseDate": "2009-06-29",
                     "posterPath": "/ia3.jpg", "overview": "Dinos"},
                    {"id": 57800, "mediaType": "movie", "title": "Ice Age: Continental Drift", "releaseDate": "2012-06-26",
                     "posterPath": "/ia4.jpg", "overview": "Drift"},
                ]})
            if "directcol" in self.path.lower():
                return self.reply(200, {"results": [
                    {"id": 8354, "mediaType": "collection", "name": "Ice Age Collection", "posterPath": "/iacol.jpg"},
                    {"id": 425, "mediaType": "movie", "title": "Ice Age", "releaseDate": "2002-03-10",
                     "posterPath": "/ia1.jpg", "overview": "Mammoth", "mediaInfo": {"status": 5}},
                ]})
            return self.reply(200, {"results": [
                {"id": 27205, "mediaType": "movie", "title": "Inception", "releaseDate": "2010-07-15",
                 "posterPath": "/inc.jpg", "overview": "Dreams", "mediaInfo": {"status": 5}},
                {"id": 999, "mediaType": "person", "name": "Nolan"},
            ]})
        if self.path == "/api/v1/movie/27205":
            return self.reply(200, {"id": 27205, "title": "Inception", "releaseDate": "2010-07-15",
                                    "posterPath": "/inc.jpg", "overview": "Dreams", "mediaInfo": {"status": 5}})
        if self.path in ("/api/v1/movie/425", "/api/v1/movie/950", "/api/v1/movie/8355", "/api/v1/movie/57800"):
            mid = int(self.path.rsplit("/", 1)[1])
            titles = {
                425: ("Ice Age", "2002-03-10", "/ia1.jpg", 5),
                950: ("Ice Age: The Meltdown", "2006-03-23", "/ia2.jpg", 7),
                8355: ("Ice Age: Dawn of the Dinosaurs", "2009-06-29", "/ia3.jpg", 1),
                57800: ("Ice Age: Continental Drift", "2012-06-26", "/ia4.jpg", 1),
            }
            title, rdate, poster, st = titles[mid]
            return self.reply(200, {
                "id": mid, "title": title, "releaseDate": rdate, "posterPath": poster, "overview": "Ice Age movie",
                "mediaInfo": {"status": st},
                "collection": {"id": 8354, "name": "Ice Age Collection", "posterPath": "/iacol.jpg"},
            })
        if self.path == "/api/v1/movie/4553":
            return self.reply(200, {"id": 4553, "title": "The Machinist", "releaseDate": "2004-10-22",
                                    "posterPath": "/mach.jpg", "overview": "Insomnia", "mediaInfo": {"status": 7}})
        if self.path == "/api/v1/collection/8354":
            return self.reply(200, {
                "id": 8354,
                "name": "Ice Age Collection",
                "overview": "Prehistoric adventures",
                "posterPath": "/iacol.jpg",
                "backdropPath": "/iabg.jpg",
                "parts": [
                    {"id": 99999, "title": "Ice Age: Boiling Point", "releaseDate": "", "posterPath": "/ia6.jpg"},
                    {"id": 8355, "title": "Ice Age: Dawn of the Dinosaurs", "releaseDate": "2009-06-29",
                     "posterPath": "/ia3.jpg", "mediaInfo": {"status": 6}},
                    {"id": 425, "title": "Ice Age", "releaseDate": "2002-03-10",
                     "posterPath": "/ia1.jpg", "mediaInfo": {"status": 5}},
                    {"id": 950, "title": "Ice Age: The Meltdown", "releaseDate": "2006-03-23",
                     "posterPath": "/ia2.jpg", "mediaInfo": {"status": 7}},
                ],
            })
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
                {
                    "id": 11, "status": 2, "type": "movie", "createdAt": "2026-11-22T10:00:00.000Z",
                    "media": {"tmdbId": 4553, "mediaType": "movie", "status": 7, "downloadStatus": []},
                    "seasons": [], "requestedBy": {"displayName": "Ayush"},
                },
                {
                    "id": 12, "status": 2, "type": "movie", "createdAt": "2026-11-21T10:00:00.000Z",
                    "media": {"tmdbId": 425, "mediaType": "movie", "status": 6, "downloadStatus": []},
                    "seasons": [], "requestedBy": {"displayName": "Ayush"},
                },
                {
                    "id": 13, "status": 4, "type": "movie", "createdAt": "2026-11-20T10:00:00.000Z",
                    "media": {"tmdbId": 950, "mediaType": "movie", "status": 2, "downloadStatus": []},
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
        self.reply(404, {"error": "not found"})


class SeerrArrJellyfinTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeSeerrAndT3Handler)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        cls.dir = tempfile.mkdtemp()
        cls.seerr_conf = os.path.join(cls.dir, "seerr.json")
        with open(cls.seerr_conf, "w") as f:
            json.dump({"main": {"apiKey": "seerr-key-1"}}, f)
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
        self.assertEqual(reqs["requests"][1]["title"], "The Machinist")
        self.assertEqual(reqs["requests"][1]["status"], "deleted")
        self.assertEqual(reqs["requests"][2]["status"], "blocklisted")
        self.assertEqual(reqs["requests"][3]["status"], "failed")

        self.assertEqual(c.create_request({"mediaType": "tv", "mediaId": 1396, "seasons": [2]})[0], 200)
        self.assertEqual(c.create_request({"mediaType": "tv", "mediaId": 1396, "seasons": []})[0], 400)

        code, resolved = c.resolve_url({"url": "https://www.themoviedb.org/movie/27205-inception"})
        self.assertEqual((code, resolved["title"]), (200, "Inception"))

        with mock.patch.object(c, "_arr_imdb_lookup", return_value=("tv", 1396)):
            code, resolved = c.resolve_url({"url": "https://www.imdb.com/title/tt0903747/"})
            self.assertEqual((code, resolved["title"]), (200, "Breaking Bad"))

    def test_seerr_media_status_deleted_blocklisted_and_collections(self):
        c = pcapi.SeerrClient(url=self.base, settings_file=self.seerr_conf)

        # Direct _media_status mapping
        self.assertEqual(c._media_status({"status": 5}), "available")
        self.assertEqual(c._media_status({"status": 7}), "deleted")
        self.assertEqual(c._media_status({"status": 6}), "blocklisted")
        self.assertEqual(c._media_status({"status": 4}), "partial")
        self.assertEqual(c._media_status({"status": 3, "downloadStatus": [{"title": "x"}]}), "grabbing")
        self.assertEqual(c._media_status({"status": 2}), "requested")
        self.assertEqual(c._media_status({"status": 1}), "none")
        self.assertEqual(c._media_status(None), "none")

        # Movie details includes collection metadata and caches it in _meta_cache
        code, det = c.details("movie", 425)
        self.assertEqual(code, 200)
        self.assertEqual(det["collection"], {
            "id": 8354,
            "name": "Ice Age Collection",
            "poster": "https://image.tmdb.org/t/p/w300/iacol.jpg",
        })
        self.assertEqual(c._meta_cache[("movie", 425)]["collection"]["id"], 8354)

        # Collection endpoint sorts parts chronologically with undated at the end
        code, col = c.collection(8354)
        self.assertEqual(code, 200)
        self.assertEqual(col["id"], 8354)
        self.assertEqual(col["name"], "Ice Age Collection")
        self.assertEqual(col["backdrop"], "https://image.tmdb.org/t/p/w780/iabg.jpg")
        self.assertEqual([p["id"] for p in col["parts"]], [425, 950, 8355, 99999])
        self.assertEqual([p["status"] for p in col["parts"]], ["available", "deleted", "blocklisted", "none"])
        self.assertEqual(c.collection("bad")[0], 400)
        self.assertEqual(c.collection(999999)[0], 404)

        # Search discovers collection from top movie results and prepends it
        FakeSeerrAndT3Handler.log.clear()
        code, srch = c.search("Ice Age")
        self.assertEqual(code, 200)
        self.assertEqual(srch["results"][0]["mediaType"], "collection")
        self.assertEqual(srch["results"][0]["id"], 8354)
        self.assertEqual(srch["results"][0]["title"], "Ice Age Collection")
        self.assertEqual(srch["results"][2]["status"], "deleted")
        # Movie 425 was already in _meta_cache so it should not be re-fetched
        fetched_paths = [p for _, p, _ in FakeSeerrAndT3Handler.log]
        self.assertNotIn("/api/v1/movie/425", fetched_paths)

        # Repeated search hits _meta_cache for all 4 top movies (no extra /movie/{id} fetches)
        FakeSeerrAndT3Handler.log.clear()
        code, srch2 = c.search("Ice Age")
        self.assertEqual(code, 200)
        self.assertEqual(srch2["results"][0]["mediaType"], "collection")
        self.assertFalse(any(p.startswith("/api/v1/movie/") for _, p, _ in FakeSeerrAndT3Handler.log))

        # Direct collection from Seerr search is accepted and not duplicated
        code, direct = c.search("directcol")
        self.assertEqual(code, 200)
        col_items = [x for x in direct["results"] if x["mediaType"] == "collection"]
        self.assertEqual(len(col_items), 1)
        self.assertEqual(col_items[0]["id"], 8354)

        # Route wiring for /api/seerr/collection
        with mock.patch.object(pcapi, "SEERR", c):
            code, routed = pcapi.route_round4_get("/api/seerr/collection", {"id": ["8354"]})
            self.assertEqual((code, routed["id"]), (200, 8354))

    def test_arr_retry_and_jellyfin_refresh(self):
        with mock.patch.object(pcapi, "ARR", {"radarr": ("radarr", self.base, self.arr_xml)}):
            code, body = pcapi.arr_retry_import({"app": "radarr"})
            self.assertEqual((code, body["ok"], body["command_id"]), (200, True, 77))
        self.assertEqual(pcapi.arr_retry_import({"app": "other"})[0], 400)

        with mock.patch.object(pcapi, "JELLYFIN_URL", self.base), \
                mock.patch.object(pcapi, "JELLYFIN_KEY_FILE", self.jf_key):
            self.assertEqual(pcapi.jellyfin_refresh_library(), (200, {"ok": True}))


if __name__ == "__main__":
    unittest.main()
