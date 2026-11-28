import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import pcapi  # noqa: E402


MOVIE_ID = "0123456789abcdef0123456789abcdef"
SERIES_ID = "fedcba9876543210fedcba9876543210"
SERVER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"


class FakeJellyfinServer:
    def __init__(self):
        self.sessions = []
        self.items_by_query = {}
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def _record(self, method):
                parsed = urllib.parse.urlsplit(self.path)
                qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
                outer.requests.append({
                    "method": method,
                    "path": parsed.path,
                    "query": qs,
                    "raw_query": parsed.query,
                    "auth": self.headers.get("Authorization"),
                })
                return parsed, qs

            def _send_json(self, code, payload):
                body = json.dumps(payload).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                parsed, qs = self._record("GET")
                if parsed.path == "/Sessions":
                    return self._send_json(200, outer.sessions)
                if parsed.path == "/Items":
                    key = (
                        (qs.get("IncludeItemTypes") or [""])[0],
                        (qs.get("AnyProviderIdEquals") or [""])[0],
                    )
                    items = outer.items_by_query.get(key, [])
                    return self._send_json(200, {"Items": items})
                return self._send_json(404, {"error": "not found"})

            def do_POST(self):
                parsed, _ = self._record("POST")
                if parsed.path.startswith("/Sessions/") and (
                    parsed.path.endswith("/Playing") or parsed.path.endswith("/Viewing")
                ):
                    self.send_response(204)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                return self._send_json(404, {"error": "not found"})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class JellyfinPlayOnTvTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.key_file = os.path.join(self.tmp.name, ".jellyfin-key")
        with open(self.key_file, "w") as f:
            f.write("test-jf-secret-key\n")
        self.jf = FakeJellyfinServer()
        self.addCleanup(self.jf.close)

        self.p_url = mock.patch.object(pcapi, "JELLYFIN_URL", self.jf.base_url)
        self.p_key = mock.patch.object(pcapi, "JELLYFIN_KEY_FILE", self.key_file)
        self.p_url.start()
        self.p_key.start()
        self.addCleanup(self.p_url.stop)
        self.addCleanup(self.p_key.stop)

        self.prev_tv_device = pcapi.CONFIG.pop("jellyfin_tv_device", None)
        if self.prev_tv_device is not None:
            self.addCleanup(pcapi.CONFIG.__setitem__, "jellyfin_tv_device", self.prev_tv_device)

    def test_extract_item_id_helpers(self):
        self.assertEqual(
            pcapi._jellyfin_extract_item_id(
                f"https://jf.example/web/#/details?id={MOVIE_ID}&serverId={SERVER_ID}"
            ),
            MOVIE_ID,
        )
        self.assertEqual(
            pcapi._jellyfin_extract_item_id(
                f"https://jf.example/Items/{MOVIE_ID}/PlaybackInfo"
            ),
            MOVIE_ID,
        )
        # Never mistake serverId for itemId
        self.assertIsNone(
            pcapi._jellyfin_extract_item_id(
                f"https://jf.example/web/#/details?serverId={SERVER_ID}"
            )
        )
        self.assertIsNone(pcapi._jellyfin_extract_item_id(None))
        self.assertIsNone(pcapi._jellyfin_extract_item_id(""))

    def test_movie_from_media_url_sends_play_now(self):
        self.jf.sessions = [
            {
                "Id": "sess-phone",
                "DeviceName": "Galaxy S24",
                "Client": "Jellyfin Android",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-tv-1",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.route_round4_post("/api/jellyfin/play-on-tv", {
            "mediaUrl": f"https://jf.example/web/#/details?id={MOVIE_ID}&serverId={SERVER_ID}",
            "tmdbId": 438631,
            "mediaType": "movie",
            "title": "Dune",
        })

        self.assertEqual(code, 200)
        self.assertEqual(body, {
            "ok": True,
            "device": "Lumio Arc 7",
            "sessionId": "sess-tv-1",
            "action": "play",
            "itemId": MOVIE_ID,
        })
        # Should NOT have queried /Items since mediaUrl already contained the 32-hex ID
        paths = [r["path"] for r in self.jf.requests]
        self.assertNotIn("/Items", paths)
        play_req = self.jf.requests[-1]
        self.assertEqual(play_req["method"], "POST")
        self.assertEqual(play_req["path"], "/Sessions/sess-tv-1/Playing")
        self.assertEqual(play_req["query"]["itemIds"], [MOVIE_ID])
        self.assertEqual(play_req["query"]["playCommand"], ["PlayNow"])
        self.assertEqual(play_req["auth"], 'MediaBrowser Token="test-jf-secret-key"')

    def test_tv_tmdb_fallback_sends_viewing(self):
        self.jf.items_by_query[("Series", "tmdb.1396")] = [
            {"Id": SERIES_ID, "Name": "Breaking Bad", "ProviderIds": {"Tmdb": "1396"}},
        ]
        self.jf.sessions = [
            {
                "Id": "sess-tv-2",
                "DeviceName": "Lumio Arc 7 Projector",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.jellyfin_play_on_tv({
            "mediaUrl": None,
            "tmdbId": 1396,
            "mediaType": "tv",
            "title": "Breaking Bad & Co",
        })

        self.assertEqual(code, 200)
        self.assertEqual(body, {
            "ok": True,
            "device": "Lumio Arc 7 Projector",
            "sessionId": "sess-tv-2",
            "action": "view",
            "itemId": SERIES_ID,
        })
        items_req = next(r for r in self.jf.requests if r["path"] == "/Items")
        self.assertEqual(items_req["query"]["Recursive"], ["true"])
        self.assertEqual(items_req["query"]["IncludeItemTypes"], ["Series"])
        self.assertEqual(items_req["query"]["AnyProviderIdEquals"], ["tmdb.1396"])
        self.assertEqual(items_req["query"]["Fields"], ["ProviderIds"])
        self.assertEqual(items_req["auth"], 'MediaBrowser Token="test-jf-secret-key"')

        view_req = self.jf.requests[-1]
        self.assertEqual(view_req["method"], "POST")
        self.assertEqual(view_req["path"], "/Sessions/sess-tv-2/Viewing")
        self.assertEqual(view_req["query"]["itemId"], [SERIES_ID])
        self.assertEqual(view_req["query"]["itemType"], ["Series"])
        self.assertEqual(view_req["query"]["itemName"], ["Breaking Bad & Co"])

    def test_movie_tmdb_fallback_when_media_url_has_no_id(self):
        self.jf.items_by_query[("Movie", "tmdb.693134")] = [
            {"Id": MOVIE_ID, "ProviderIds": {"Tmdb": "693134"}},
        ]
        self.jf.sessions = [
            {
                "Id": "sess-tv-3",
                "DeviceName": "Living Room TV",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.jellyfin_play_on_tv({
            "mediaUrl": f"https://jf.example/web/#/details?serverId={SERVER_ID}",
            "tmdbId": 693134,
            "mediaType": "movie",
            "title": "Dune: Part Two",
        })

        self.assertEqual(code, 200)
        self.assertEqual(body["ok"], True)
        self.assertEqual(body["device"], "Living Room TV")
        self.assertEqual(body["action"], "play")
        self.assertEqual(body["itemId"], MOVIE_ID)

    def test_no_controllable_tv_session_returns_409_tv_offline(self):
        self.jf.sessions = [
            {
                "Id": "sess-uncontrollable",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": False,
            },
            {
                "Id": "sess-web",
                "DeviceName": "Chrome",
                "Client": "Jellyfin Web",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.jellyfin_play_on_tv({
            "mediaUrl": f"https://jf.example/web/#/details?id={MOVIE_ID}",
            "mediaType": "movie",
        })

        self.assertEqual(code, 409)
        self.assertEqual(body["ok"], False)
        self.assertEqual(body["code"], "tv_offline")
        self.assertIn("No TVs are open in Jellyfin", body["error"])

    def test_explicit_device_name_does_not_fallback_to_other_tv(self):
        self.jf.sessions = [
            {
                "Id": "sess-other-tv",
                "DeviceName": "Bedroom TV",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.jellyfin_play_on_tv({
            "mediaUrl": f"https://jf.example/web/#/details?id={MOVIE_ID}",
            "deviceName": "Lumio Arc 7",
        })

        self.assertEqual(code, 409)
        self.assertEqual(body["code"], "tv_offline")

    def test_item_not_found_returns_404(self):
        self.jf.sessions = [
            {
                "Id": "sess-tv-1",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.jellyfin_play_on_tv({
            "tmdbId": 99999999,
            "mediaType": "movie",
        })

        self.assertEqual(code, 404)
        self.assertEqual(body, {"ok": False, "error": "Item not found in Jellyfin library."})

    def test_no_media_url_and_no_tmdb_id_returns_404(self):
        code, body = pcapi.jellyfin_play_on_tv({"mediaType": "movie"})
        self.assertEqual(code, 404)
        self.assertEqual(body, {"ok": False, "error": "Item not found in Jellyfin library."})

    def test_explicit_jellyfin_item_id_for_episode_sends_play_now_with_start_ticks(self):
        ep_id = "11112222333344445555666677778888"
        self.jf.sessions = [
            {
                "Id": "sess-tv-ep",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.jellyfin_play_on_tv({
            "jellyfinItemId": ep_id,
            "mediaType": "episode",
            "title": "Pilot",
            "startPositionTicks": 1234567890,
        })

        self.assertEqual(code, 200)
        self.assertEqual(body, {
            "ok": True,
            "device": "Lumio Arc 7",
            "sessionId": "sess-tv-ep",
            "action": "play",
            "itemId": ep_id,
        })
        paths = [r["path"] for r in self.jf.requests]
        self.assertNotIn("/Items", paths)
        play_req = self.jf.requests[-1]
        self.assertEqual(play_req["path"], "/Sessions/sess-tv-ep/Playing")
        self.assertEqual(play_req["query"]["itemIds"], [ep_id])
        self.assertEqual(play_req["query"]["playCommand"], ["PlayNow"])
        self.assertEqual(play_req["query"]["startPositionTicks"], ["1234567890"])

    def test_session_id_targets_exact_device(self):
        self.jf.sessions = [
            {
                "Id": "sess-lumio",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-lg-oled",
                "DeviceName": "LG OLED C3",
                "Client": "Jellyfin for WebOS",
                "SupportsRemoteControl": True,
            },
        ]
        code, body = pcapi.jellyfin_play_on_tv({
            "mediaUrl": f"https://jf.example/web/#/details?id={MOVIE_ID}",
            "mediaType": "movie",
            "sessionId": "sess-lg-oled",
        })
        self.assertEqual(code, 200)
        self.assertEqual(body["ok"], True)
        self.assertEqual(body["device"], "LG OLED C3")
        self.assertEqual(body["sessionId"], "sess-lg-oled")
        self.assertEqual(self.jf.requests[-1]["path"], "/Sessions/sess-lg-oled/Playing")

    def test_multi_tv_fallback_picks_non_android_tv_when_default_offline(self):
        self.jf.sessions = [
            {
                "Id": "sess-roku",
                "DeviceName": "Bedroom Roku",
                "Client": "Jellyfin Roku",
                "SupportsRemoteControl": True,
            },
        ]
        code, body = pcapi.jellyfin_play_on_tv({
            "mediaUrl": f"https://jf.example/web/#/details?id={MOVIE_ID}",
            "mediaType": "movie",
        })
        self.assertEqual(code, 200)
        self.assertEqual(body["device"], "Bedroom Roku")
        self.assertEqual(body["sessionId"], "sess-roku")

    def test_invalid_jellyfin_item_id_returns_400(self):
        code, body = pcapi.jellyfin_play_on_tv({
            "jellyfinItemId": "not-a-valid-hex-id",
            "mediaType": "episode",
        })
        self.assertEqual(code, 400)
        self.assertEqual(body["ok"], False)

    def test_tmdb_lookup_ignores_unrelated_items_when_jellyfin_ignores_filter(self):
        # Simulate Jellyfin ignoring AnyProviderIdEquals and returning unrelated shows
        self.jf.items_by_query[("Series", "tmdb.60573")] = [
            {"Id": SERIES_ID, "Name": "Insomniacs After School", "ProviderIds": {"Tmdb": "155440"}},
            {"Id": MOVIE_ID, "Name": "No ProviderIds Show"},
        ]
        self.assertIsNone(pcapi._jellyfin_lookup_item_by_tmdb("test-jf-secret-key", 60573, "tv"))

        # When the requested item appears later in the unfiltered list, return only the matching item
        self.jf.items_by_query[("Movie", "tmdb.693134")] = [
            {"Id": SERIES_ID, "Name": "Wrong Movie", "ProviderIds": {"Tmdb": "999999"}},
            {"Id": MOVIE_ID, "Name": "Dune: Part Two", "ProviderIds": {"tmdb": "693134"}},
        ]
        self.assertEqual(
            pcapi._jellyfin_lookup_item_by_tmdb("test-jf-secret-key", 693134, "movie"),
            MOVIE_ID,
        )


if __name__ == "__main__":
    unittest.main()
