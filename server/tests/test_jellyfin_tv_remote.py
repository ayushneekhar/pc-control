import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import pcapi  # noqa: E402


MOVIE_ID = "0123456789abcdef0123456789abcdef"
EPISODE_ID = "11112222333344445555666677778888"
SERIES_ID = "fedcba9876543210fedcba9876543210"
FAKE_JPEG = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9"
FAKE_PNG = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"


class FakeJellyfinTvServer:
    def __init__(self):
        self.sessions = []
        self.images = {}  # (item_id, image_type) -> (bytes, content_type)
        self.requests = []
        self.missing_sessions = set()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def _record(self, method, body_bytes=b""):
                parsed = urllib.parse.urlsplit(self.path)
                qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
                body_json = None
                if body_bytes:
                    try:
                        body_json = json.loads(body_bytes.decode("utf-8"))
                    except Exception:
                        body_json = None
                outer.requests.append({
                    "method": method,
                    "path": parsed.path,
                    "query": qs,
                    "raw_query": parsed.query,
                    "auth": self.headers.get("Authorization"),
                    "body": body_json,
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
                parsed, _ = self._record("GET")
                if parsed.path == "/Sessions":
                    return self._send_json(200, outer.sessions)
                parts = parsed.path.strip("/").split("/")
                if len(parts) == 4 and parts[0] == "Items" and parts[2] == "Images":
                    item_id, img_type = parts[1], parts[3]
                    img = outer.images.get((item_id, img_type))
                    if img is None:
                        return self._send_json(404, {"error": "not found"})
                    raw, ctype = img
                    self.send_response(200)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                    return
                return self._send_json(404, {"error": "not found"})

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n) if n > 0 else b""
                parsed, _ = self._record("POST", raw)
                parts = parsed.path.strip("/").split("/")
                if len(parts) >= 3 and parts[0] == "Sessions":
                    sid = urllib.parse.unquote(parts[1])
                    if sid in outer.missing_sessions:
                        return self._send_json(404, {"error": "session gone"})
                    if (len(parts) == 4 and parts[2] == "Playing") or (
                        len(parts) == 3 and parts[2] == "Command"
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


class JellyfinTvRemoteTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.key_file = os.path.join(self.tmp.name, ".jellyfin-key")
        with open(self.key_file, "w") as f:
            f.write("secret-jf-api-key\n")
        self.jf = FakeJellyfinTvServer()
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

    # --- GET /api/jellyfin/tv/session ---

    def test_session_offline(self):
        self.jf.sessions = [
            {
                "Id": "sess-web",
                "DeviceName": "Chrome",
                "Client": "Jellyfin Web",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-tv-uncontrollable",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": False,
            },
        ]
        code, body = pcapi.route_round4_get("/api/jellyfin/tv/session", {})
        self.assertEqual(code, 200)
        self.assertEqual(body["connected"], False)
        self.assertIsNone(body["sessionId"])
        self.assertEqual(body["deviceName"], "Lumio Arc 7")
        self.assertEqual(body["isPlaying"], False)
        self.assertIsNone(body["nowPlaying"])
        self.assertEqual(body["audioTracks"], [])
        self.assertEqual(body["subtitleTracks"], [])

    def test_session_idle_connected(self):
        self.jf.sessions = [
            {
                "Id": "sess-tv-idle",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "ApplicationVersion": "0.19.10",
                "SupportsRemoteControl": True,
                "PlayState": {"IsPaused": False, "IsMuted": True, "VolumeLevel": 40},
                "SupportedCommands": ["VolumeUp", "VolumeDown", "ToggleMute", "SetAudioStreamIndex"],
            }
        ]
        code, body = pcapi.jellyfin_tv_session()
        self.assertEqual(code, 200)
        self.assertEqual(body["connected"], True)
        self.assertEqual(body["sessionId"], "sess-tv-idle")
        self.assertEqual(body["deviceName"], "Lumio Arc 7")
        self.assertEqual(body["client"], "Android TV")
        self.assertEqual(body["appVersion"], "0.19.10")
        self.assertEqual(body["isPlaying"], False)
        self.assertEqual(body["isPaused"], False)
        self.assertEqual(body["isMuted"], True)
        self.assertEqual(body["volumeLevel"], 40)
        self.assertEqual(body["canSeek"], False)
        self.assertIsNone(body["nowPlaying"])
        self.assertEqual(body["supportedCommands"], ["VolumeUp", "VolumeDown", "ToggleMute", "SetAudioStreamIndex"])

    def test_session_active_movie_with_tracks(self):
        self.jf.sessions = [
            {
                "Id": "sess-stale-idle",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-movie",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "ApplicationVersion": "0.19.10",
                "SupportsRemoteControl": True,
                "PlayState": {
                    "PositionTicks": 12_345_000_000,  # 1234.5s
                    "CanSeek": True,
                    "IsPaused": True,
                    "IsMuted": False,
                    "VolumeLevel": 75,
                    "AudioStreamIndex": 1,
                    "SubtitleStreamIndex": 3,
                },
                "NowPlayingItem": {
                    "Id": MOVIE_ID,
                    "Name": "Dune: Part Two",
                    "Type": "Movie",
                    "ProductionYear": 2024,
                    "RunTimeTicks": 99_600_000_000,  # 9960s
                    "BackdropImageTags": ["abc123"],
                    "MediaStreams": [
                        {"Type": "Video", "Index": 0, "Codec": "hevc"},
                        {
                            "Type": "Audio",
                            "Index": 1,
                            "DisplayTitle": "English - EAC3 5.1",
                            "Language": "eng",
                            "Codec": "eac3",
                            "Channels": 6,
                            "IsDefault": True,
                        },
                        {
                            "Type": "Audio",
                            "Index": 2,
                            "DisplayTitle": "Hindi - AAC Stereo",
                            "Language": "hin",
                            "Codec": "aac",
                            "Channels": 2,
                            "IsDefault": False,
                        },
                        {
                            "Type": "Subtitle",
                            "Index": 3,
                            "DisplayTitle": "English - SRT",
                            "Language": "eng",
                            "Codec": "subrip",
                            "IsExternal": True,
                            "IsDefault": False,
                        },
                    ],
                },
                "SupportedCommands": ["VolumeUp", "VolumeDown", "ToggleMute", "SetAudioStreamIndex", "SetSubtitleStreamIndex"],
            },
        ]

        code, body = pcapi.jellyfin_tv_session()
        self.assertEqual(code, 200)
        self.assertEqual(body["connected"], True)
        self.assertEqual(body["sessionId"], "sess-movie")
        self.assertEqual(body["isPlaying"], True)
        self.assertEqual(body["isPaused"], True)
        self.assertEqual(body["isMuted"], False)
        self.assertEqual(body["volumeLevel"], 75)
        self.assertEqual(body["positionTicks"], 12_345_000_000)
        self.assertEqual(body["runTimeTicks"], 99_600_000_000)
        self.assertEqual(body["positionSeconds"], 1234.5)
        self.assertEqual(body["durationSeconds"], 9960.0)
        self.assertEqual(body["canSeek"], True)
        self.assertEqual(body["audioStreamIndex"], 1)
        self.assertEqual(body["subtitleStreamIndex"], 3)

        np = body["nowPlaying"]
        self.assertEqual(np["itemId"], MOVIE_ID)
        self.assertEqual(np["name"], "Dune: Part Two")
        self.assertEqual(np["type"], "Movie")
        self.assertEqual(np["year"], 2024)
        self.assertIsNone(np["seriesName"])
        self.assertEqual(np["posterUrl"], f"/pc/api/jellyfin/image/{MOVIE_ID}?type=Primary")
        self.assertEqual(np["backdropUrl"], f"/pc/api/jellyfin/image/{MOVIE_ID}?type=Backdrop")

        self.assertEqual(len(body["audioTracks"]), 2)
        self.assertEqual(body["audioTracks"][0], {
            "index": 1,
            "displayTitle": "English - EAC3 5.1",
            "language": "eng",
            "codec": "eac3",
            "channels": 6,
            "isDefault": True,
        })
        self.assertEqual(len(body["subtitleTracks"]), 1)
        self.assertEqual(body["subtitleTracks"][0], {
            "index": 3,
            "displayTitle": "English - SRT",
            "language": "eng",
            "codec": "subrip",
            "isExternal": True,
            "isDefault": False,
        })

    def test_session_active_episode_includes_series_fallback_urls(self):
        self.jf.sessions = [
            {
                "Id": "sess-ep",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
                "PlayState": {
                    "PositionTicks": 600_000_000,
                    "CanSeek": True,
                    "IsPaused": False,
                    "IsMuted": False,
                    "AudioStreamIndex": 1,
                    "SubtitleStreamIndex": -1,
                },
                "NowPlayingItem": {
                    "Id": EPISODE_ID,
                    "Name": "Ozymandias",
                    "Type": "Episode",
                    "SeriesId": SERIES_ID,
                    "SeriesName": "Breaking Bad",
                    "SeasonName": "Season 5",
                    "ParentIndexNumber": 5,
                    "IndexNumber": 14,
                    "ProductionYear": 2013,
                    "ParentBackdropItemId": SERIES_ID,
                    "RunTimeTicks": 28_800_000_000,
                    "MediaStreams": [
                        {"Type": "Audio", "Index": 1, "DisplayTitle": "English", "Language": "eng"},
                        {"Type": "Subtitle", "Index": 2, "DisplayTitle": "English SDH", "Language": "eng"},
                    ],
                },
            }
        ]
        code, body = pcapi.jellyfin_tv_session()
        self.assertEqual(code, 200)
        np = body["nowPlaying"]
        self.assertEqual(np["seriesName"], "Breaking Bad")
        self.assertEqual(np["seasonName"], "Season 5")
        self.assertEqual(np["seasonNumber"], 5)
        self.assertEqual(np["episodeNumber"], 14)
        self.assertEqual(body["subtitleStreamIndex"], -1)
        self.assertEqual(
            np["posterUrl"],
            f"/pc/api/jellyfin/image/{EPISODE_ID}?type=Primary&seriesId={SERIES_ID}",
        )
        self.assertEqual(
            np["backdropUrl"],
            f"/pc/api/jellyfin/image/{SERIES_ID}?type=Backdrop",
        )

    # --- POST /api/jellyfin/tv/command ---

    def _seed_active_session(self, pos_ticks=1_000_000_000, runtime_ticks=3_000_000_000):
        self.jf.sessions = [
            {
                "Id": "sess-tv-1",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
                "PlayState": {
                    "PositionTicks": pos_ticks,
                    "CanSeek": True,
                    "IsPaused": False,
                },
                "NowPlayingItem": {
                    "Id": MOVIE_ID,
                    "Name": "Arrival",
                    "Type": "Movie",
                    "RunTimeTicks": runtime_ticks,
                },
            }
        ]

    def test_playstate_commands(self):
        self._seed_active_session()
        for cmd in ("PlayPause", "Pause", "Unpause", "Stop", "NextTrack", "PreviousTrack", "Rewind", "FastForward"):
            code, body = pcapi.route_round4_post("/api/jellyfin/tv/command", {"command": cmd})
            self.assertEqual(code, 200)
            self.assertEqual(body, {"ok": True, "command": cmd, "sessionId": "sess-tv-1"})
            req = self.jf.requests[-1]
            self.assertEqual(req["method"], "POST")
            self.assertEqual(req["path"], f"/Sessions/sess-tv-1/Playing/{cmd}")
            self.assertEqual(req["auth"], 'MediaBrowser Token="secret-jf-api-key"')

    def test_seek_by_ticks_and_seconds(self):
        self._seed_active_session()
        code, body = pcapi.jellyfin_tv_command({
            "sessionId": "sess-tv-1",
            "command": "Seek",
            "seekPositionTicks": 5_000_000_000,
        })
        self.assertEqual(code, 200)
        self.assertEqual(body["positionTicks"], 5_000_000_000)
        self.assertEqual(body["positionSeconds"], 500.0)
        self.assertEqual(self.jf.requests[-1]["path"], "/Sessions/sess-tv-1/Playing/Seek")
        self.assertEqual(self.jf.requests[-1]["query"]["SeekPositionTicks"], ["5000000000"])

        code2, body2 = pcapi.jellyfin_tv_command({
            "command": "Seek",
            "positionSeconds": 90.5,
        })
        self.assertEqual(code2, 200)
        self.assertEqual(body2["positionTicks"], 905_000_000)
        self.assertEqual(self.jf.requests[-1]["query"]["SeekPositionTicks"], ["905000000"])

    def test_seek_relative_clamping(self):
        # Current: 100s (1_000_000_000 ticks), Duration: 300s (3_000_000_000 ticks)
        self._seed_active_session(pos_ticks=1_000_000_000, runtime_ticks=3_000_000_000)

        # +30s -> 130s
        code, body = pcapi.jellyfin_tv_command({"command": "SeekRelative", "deltaSeconds": 30})
        self.assertEqual(code, 200)
        self.assertEqual(body["command"], "SeekRelative")
        self.assertEqual(body["positionTicks"], 1_300_000_000)
        self.assertEqual(self.jf.requests[-1]["query"]["SeekPositionTicks"], ["1300000000"])

        # -250s -> clamped to 0
        code, body = pcapi.jellyfin_tv_command({"command": "SeekRelative", "deltaSeconds": -250})
        self.assertEqual(code, 200)
        self.assertEqual(body["positionTicks"], 0)
        self.assertEqual(self.jf.requests[-1]["query"]["SeekPositionTicks"], ["0"])

        # +999s -> clamped to RunTimeTicks (3_000_000_000)
        code, body = pcapi.jellyfin_tv_command({"command": "SeekRelative", "deltaSeconds": 999})
        self.assertEqual(code, 200)
        self.assertEqual(body["positionTicks"], 3_000_000_000)
        self.assertEqual(self.jf.requests[-1]["query"]["SeekPositionTicks"], ["3000000000"])

    def test_track_switching_commands(self):
        self._seed_active_session()

        code, body = pcapi.jellyfin_tv_command({
            "sessionId": "sess-tv-1",
            "command": "SetAudioStreamIndex",
            "index": 2,
        })
        self.assertEqual(code, 200)
        self.assertEqual(body, {"ok": True, "command": "SetAudioStreamIndex", "sessionId": "sess-tv-1"})
        req = self.jf.requests[-1]
        self.assertEqual(req["path"], "/Sessions/sess-tv-1/Command")
        self.assertEqual(req["body"], {"Name": "SetAudioStreamIndex", "Arguments": {"Index": "2"}})

        # Subtitles on (index 4)
        code, body = pcapi.jellyfin_tv_command({
            "command": "SetSubtitleStreamIndex",
            "index": 4,
        })
        self.assertEqual(code, 200)
        self.assertEqual(self.jf.requests[-1]["body"], {"Name": "SetSubtitleStreamIndex", "Arguments": {"Index": "4"}})

        # Subtitles off (index -1)
        code, body = pcapi.jellyfin_tv_command({
            "command": "SetSubtitleStreamIndex",
            "index": -1,
        })
        self.assertEqual(code, 200)
        self.assertEqual(self.jf.requests[-1]["body"], {"Name": "SetSubtitleStreamIndex", "Arguments": {"Index": "-1"}})

    def test_volume_commands(self):
        self._seed_active_session()
        for cmd in ("VolumeUp", "VolumeDown", "ToggleMute"):
            code, body = pcapi.jellyfin_tv_command({"command": cmd})
            self.assertEqual(code, 200)
            self.assertEqual(body["command"], cmd)
            self.assertEqual(self.jf.requests[-1]["path"], "/Sessions/sess-tv-1/Command")
            self.assertEqual(self.jf.requests[-1]["body"], {"Name": cmd, "Arguments": {}})

        code, body = pcapi.jellyfin_tv_command({"command": "SetVolume", "volume": 120})
        self.assertEqual(code, 200)
        self.assertEqual(self.jf.requests[-1]["body"], {"Name": "SetVolume", "Arguments": {"Volume": "100"}})

    def test_command_409_tv_offline(self):
        self.jf.sessions = []
        code, body = pcapi.jellyfin_tv_command({"command": "PlayPause"})
        self.assertEqual(code, 409)
        self.assertEqual(body["ok"], False)
        self.assertEqual(body["code"], "tv_offline")

        # Explicit sessionId gone (404 from Jellyfin) -> 409 tv_offline
        self.jf.missing_sessions.add("sess-expired")
        code2, body2 = pcapi.jellyfin_tv_command({"sessionId": "sess-expired", "command": "Pause"})
        self.assertEqual(code2, 409)
        self.assertEqual(body2["code"], "tv_offline")

    def test_command_validation_errors(self):
        self._seed_active_session()
        self.assertEqual(pcapi.jellyfin_tv_command({})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "SelfDestruct"})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "Seek"})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "Seek", "positionSeconds": -5})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "SeekRelative"})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "SetAudioStreamIndex", "index": -1})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "SetSubtitleStreamIndex", "index": -2})[0], 400)
        self.assertEqual(pcapi.jellyfin_tv_command({"command": "SetVolume"})[0], 400)

    # --- GET /api/jellyfin/image/<itemId> ---

    def test_image_proxy_primary_and_series_fallback(self):
        self.jf.images[(MOVIE_ID, "Primary")] = (FAKE_JPEG, "image/jpeg")
        self.jf.images[(SERIES_ID, "Primary")] = (FAKE_PNG, "image/png")

        code, data, ctype = pcapi.jellyfin_proxy_image(MOVIE_ID, "Primary")
        self.assertEqual(code, 200)
        self.assertEqual(data, FAKE_JPEG)
        self.assertEqual(ctype, "image/jpeg")
        req = self.jf.requests[-1]
        self.assertEqual(req["path"], f"/Items/{MOVIE_ID}/Images/Primary")
        self.assertEqual(req["query"]["maxWidth"], ["600"])
        self.assertEqual(req["query"]["quality"], ["85"])
        self.assertEqual(req["auth"], 'MediaBrowser Token="secret-jf-api-key"')

        # Episode has no Primary image -> falls back to seriesId
        code2, data2, ctype2 = pcapi.jellyfin_proxy_image(EPISODE_ID, "Primary", series_id=SERIES_ID)
        self.assertEqual(code2, 200)
        self.assertEqual(data2, FAKE_PNG)
        self.assertEqual(ctype2, "image/png")
        self.assertEqual(self.jf.requests[-2]["path"], f"/Items/{EPISODE_ID}/Images/Primary")
        self.assertEqual(self.jf.requests[-1]["path"], f"/Items/{SERIES_ID}/Images/Primary")

        # Missing image with no fallback -> 404
        code3, err3, ctype3 = pcapi.jellyfin_proxy_image(EPISODE_ID, "Backdrop")
        self.assertEqual(code3, 404)
        self.assertIsNone(ctype3)

        # Validation
        self.assertEqual(pcapi.jellyfin_proxy_image("not-a-hex-id", "Primary")[0], 400)
        self.assertEqual(pcapi.jellyfin_proxy_image(MOVIE_ID, "WeirdType")[0], 400)

    def test_image_proxy_http_route_requires_auth_and_sets_headers(self):
        self.jf.images[(MOVIE_ID, "Primary")] = (FAKE_JPEG, "image/jpeg")
        srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        base = f"http://127.0.0.1:{srv.server_port}"

        # Unauthenticated request rejected with 403
        with mock.patch.object(pcapi, "ALLOWED_LOGINS", {"ayush@example.com"}), \
                mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", False):
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(f"{base}/pc/api/jellyfin/image/{MOVIE_ID}?type=Primary", timeout=5)
            self.assertEqual(ctx.exception.code, 403)
            ctx.exception.close()

            # Authenticated Tailscale request succeeds with proper Content-Type & Cache-Control
            req = urllib.request.Request(
                f"{base}/pc/api/jellyfin/image/{MOVIE_ID}?type=Primary",
                headers={"Tailscale-User-Login": "ayush@example.com"},
            )
            with urllib.request.urlopen(req, timeout=5) as r:
                self.assertEqual(r.status, 200)
                self.assertEqual(r.headers.get("Content-Type"), "image/jpeg")
                self.assertIn("max-age=", r.headers.get("Cache-Control") or "")
                self.assertEqual(r.read(), FAKE_JPEG)

    def test_tv_devices_lists_filters_and_sorts_sessions(self):
        self.jf.sessions = [
            {
                "Id": "sess-uncontrollable",
                "DeviceName": "Ghost TV",
                "Client": "Android TV",
                "SupportsRemoteControl": False,
            },
            {
                "Id": "sess-web",
                "DeviceName": "Chrome Desktop",
                "Client": "Jellyfin Web",
                "UserName": "ayush",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-appletv",
                "DeviceName": "Living Room Apple TV",
                "Client": "Infuse Apple TV",
                "UserName": "ayush",
                "SupportsRemoteControl": True,
                "NowPlayingItem": {"Name": "The Matrix", "Type": "Movie"},
            },
            {
                "Id": "sess-lumio",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "UserName": "ayush",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-alpha-tv",
                "DeviceName": "Alpha Shield",
                "Client": "Android TV",
                "UserName": "ayush",
                "SupportsRemoteControl": True,
            },
        ]

        code, body = pcapi.route_round4_get("/api/jellyfin/tv/devices", {})
        self.assertEqual(code, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(body["defaultDevice"], "Lumio Arc 7")
        devices = body["devices"]
        # Uncontrollable session must be excluded
        self.assertEqual([d["sessionId"] for d in devices], [
            "sess-appletv",   # TV + playing
            "sess-lumio",     # TV + default device
            "sess-alpha-tv",  # TV + alphabetical
            "sess-web",       # non-TV last
        ])
        self.assertTrue(devices[0]["isTv"])
        self.assertTrue(devices[0]["isPlaying"])
        self.assertEqual(devices[0]["nowPlayingTitle"], "The Matrix")
        self.assertFalse(devices[3]["isTv"])

    def test_tv_session_targets_explicit_session_id(self):
        self.jf.sessions = [
            {
                "Id": "sess-lumio",
                "DeviceName": "Lumio Arc 7",
                "Client": "Android TV",
                "SupportsRemoteControl": True,
            },
            {
                "Id": "sess-bedroom",
                "DeviceName": "Bedroom Fire TV",
                "Client": "Jellyfin Fire TV",
                "SupportsRemoteControl": True,
                "NowPlayingItem": {"Id": MOVIE_ID, "Name": "Arrival", "Type": "Movie"},
                "PlayState": {"IsPaused": False, "PositionTicks": 100_000_000},
            },
        ]

        code, body = pcapi.route_round4_get("/api/jellyfin/tv/session", {"sessionId": ["sess-bedroom"]})
        self.assertEqual(code, 200)
        self.assertTrue(body["connected"])
        self.assertEqual(body["sessionId"], "sess-bedroom")
        self.assertEqual(body["deviceName"], "Bedroom Fire TV")
        self.assertTrue(body["isPlaying"])
        self.assertEqual(body["nowPlaying"]["name"], "Arrival")


if __name__ == "__main__":
    unittest.main()
