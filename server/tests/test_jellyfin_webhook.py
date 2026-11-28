"""Unit tests for POST /pc/api/hook/jellyfin and WatchService.invalidate_caches."""

import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import pcapi  # noqa: E402
import watch as watch_mod  # noqa: E402


TEST_SECRET = "j" * 40


class FakeNotify:
    def __init__(self):
        self.sent = []

    def send(self, cat, title, body="", telegram_text=None, image=None, link=None, cam=None, ts=None):
        self.sent.append({
            "category": cat,
            "title": title,
            "body": body,
            "link": link,
        })
        return 200, {"ok": True, "category": cat, "id": "deadbeef12345678", "app": "queued", "telegram": "off"}


class WatchInvalidateCachesTest(unittest.TestCase):
    def setUp(self):
        self.svc = watch_mod.WatchService(
            seerr_client_fn=lambda: None,
            jellyfin_url_fn=lambda: "http://127.0.0.1:8096",
            jellyfin_key_fn=lambda: "fake-key",
            arr_config_fn=lambda: {},
        )
        self.svc._home_cache = (999999.0, {"heroItems": []})
        self.svc._library_index_cache = (999999.0, {"by_tmdb": {("movie", 1): "abc"}, "total": 1})
        self.svc._segments_cache = {("http://127.0.0.1:8096", "a" * 32): (999999.0, [{"type": "Intro"}])}

    def test_default_clears_only_home(self):
        self.svc.invalidate_caches()
        self.assertEqual(self.svc._home_cache, (0.0, None))
        self.assertIsNotNone(self.svc._library_index_cache[1])
        self.assertEqual(len(self.svc._segments_cache), 1)

    def test_clear_library_clears_home_and_library_keeps_segments(self):
        self.svc.invalidate_caches(clear_library=True)
        self.assertEqual(self.svc._home_cache, (0.0, None))
        self.assertEqual(self.svc._library_index_cache, (0.0, None))
        self.assertEqual(len(self.svc._segments_cache), 1)

    def test_clear_library_and_segments_clears_all(self):
        self.svc.invalidate_caches(clear_library=True, clear_segments=True)
        self.assertEqual(self.svc._home_cache, (0.0, None))
        self.assertEqual(self.svc._library_index_cache, (0.0, None))
        self.assertEqual(self.svc._segments_cache, {})


class JellyfinWebhookEndpointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._orig_secret_file = pcapi.HOOK_SECRET_FILE
        cls._orig_notify = pcapi.NOTIFY
        cls._orig_watch = pcapi.WATCH
        cls._orig_allow_local = pcapi.ALLOW_UNAUTH_LOCAL

        cls.tmp_dir = tempfile.TemporaryDirectory()
        cls.secret_path = os.path.join(cls.tmp_dir.name, "hook-secret")
        with open(cls.secret_path, "w") as f:
            f.write(TEST_SECRET + "\n")
        pcapi.HOOK_SECRET_FILE = cls.secret_path
        pcapi.ALLOW_UNAUTH_LOCAL = True

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp_dir.cleanup()
        pcapi.HOOK_SECRET_FILE = cls._orig_secret_file
        pcapi.NOTIFY = cls._orig_notify
        pcapi.WATCH = cls._orig_watch
        pcapi.ALLOW_UNAUTH_LOCAL = cls._orig_allow_local

    def setUp(self):
        self.notify = FakeNotify()
        pcapi.NOTIFY = self.notify
        self.watch = watch_mod.WatchService(
            seerr_client_fn=lambda: None,
            jellyfin_url_fn=lambda: "http://127.0.0.1:8096",
            jellyfin_key_fn=lambda: "fake-key",
            arr_config_fn=lambda: {},
        )
        pcapi.WATCH = self.watch
        self._seed_caches()
        with pcapi._JELLYFIN_ADDED_LOCK:
            pcapi._JELLYFIN_ADDED_SEEN.clear()

    def _seed_caches(self):
        self.watch._home_cache = (999999.0, {"heroItems": [{"id": 1}]})
        self.watch._library_index_cache = (999999.0, {"by_tmdb": {("movie", 438631): "a" * 32}, "total": 1})
        self.watch._segments_cache = {("http://127.0.0.1:8096", "a" * 32): (999999.0, [{"type": "Intro"}])}

    def _post(self, payload, headers=None):
        body = json.dumps(payload).encode("utf-8")
        hdrs = {"Content-Type": "application/json", **(headers or {})}
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request("POST", "/pc/api/hook/jellyfin", body=body, headers=hdrs)
            resp = conn.getresponse()
            raw = resp.read()
            return resp.status, json.loads(raw)
        finally:
            conn.close()

    def test_auth_rejection_without_or_with_bad_secret(self):
        code, data = self._post({"NotificationType": "PlaybackStop"})
        self.assertEqual(code, 401)
        self.assertIn("secret", data.get("error", "").lower())

        code, _ = self._post({"NotificationType": "PlaybackStop"}, {"X-Hook-Secret": "wrong-secret"})
        self.assertEqual(code, 401)

        # Proxied request must be rejected with 403
        code, _ = self._post(
            {"NotificationType": "PlaybackStop"},
            {"X-Hook-Secret": TEST_SECRET, "X-Forwarded-For": "10.0.0.5"},
        )
        self.assertEqual(code, 403)
        # Caches untouched on rejected requests
        self.assertIsNotNone(self.watch._home_cache[1])

    def test_playback_and_userdata_invalidate_home_only_without_push(self):
        for event_name, hdr in (
            ("PlaybackStart", {"X-Hook-Secret": TEST_SECRET}),
            ("PlaybackStop", {"X-Hook-Secret": TEST_SECRET}),
            ("userdatasaved", {"X-PC-Hook-Secret": TEST_SECRET}),
        ):
            self._seed_caches()
            code, data = self._post({"NotificationType": event_name, "Name": "Dune"}, hdr)
            self.assertEqual(code, 200)
            self.assertEqual(data, {"ok": True, "action": "cache_invalidated"})
            self.assertEqual(self.watch._home_cache, (0.0, None))
            self.assertIsNotNone(self.watch._library_index_cache[1])
            self.assertEqual(len(self.watch._segments_cache), 1)
        self.assertEqual(self.notify.sent, [])

    def test_item_deleted_invalidates_all_caches_without_push(self):
        code, data = self._post(
            {"NotificationType": "ItemDeleted", "ItemType": "Movie", "Name": "Old Movie"},
            {"X-Hook-Secret": TEST_SECRET},
        )
        self.assertEqual(code, 200)
        self.assertEqual(data, {"ok": True, "action": "cache_invalidated"})
        self.assertEqual(self.watch._home_cache, (0.0, None))
        self.assertEqual(self.watch._library_index_cache, (0.0, None))
        self.assertEqual(self.watch._segments_cache, {})
        self.assertEqual(self.notify.sent, [])

    def test_item_added_movie_notifies_and_invalidates_library(self):
        payload = {
            "NotificationType": "ItemAdded",
            "ItemType": "Movie",
            "Name": "Dune",
            "Year": 2021,
            "ItemId": "11112222333344445555666677778888",
            "Provider_tmdb": "438631",
        }
        code, data = self._post(payload, {"X-Hook-Secret": TEST_SECRET})
        self.assertEqual(code, 200)
        self.assertTrue(data["ok"])
        self.assertEqual(data["action"], "cache_invalidated")
        self.assertEqual(self.watch._home_cache, (0.0, None))
        self.assertEqual(self.watch._library_index_cache, (0.0, None))
        self.assertEqual(len(self.watch._segments_cache), 1)

        self.assertEqual(len(self.notify.sent), 1)
        msg = self.notify.sent[0]
        self.assertEqual(msg["category"], "available")
        self.assertEqual(msg["title"], "🍿 Ready to watch")
        self.assertEqual(msg["body"], "Dune (2021)")
        self.assertEqual(msg["link"], "pccontrol://watch/movie/438631")

        # Duplicate within 15s is deduped
        code2, data2 = self._post(payload, {"X-Hook-Secret": TEST_SECRET})
        self.assertEqual(code2, 200)
        self.assertTrue(data2.get("deduped"))
        self.assertEqual(len(self.notify.sent), 1)

    def test_item_added_episode_formats_cleanly_and_dedups_bulk_season(self):
        base_ep = {
            "NotificationType": "ItemAdded",
            "ItemType": "Episode",
            "SeriesName": "Severance",
            "SeriesId": "aaaa1111bbbb2222cccc3333dddd4444",
            "SeasonNumber00": "01",
            "EpisodeNumber00": "02",
            "Name": "Half Loop",
            "Provider_tmdb": "95396",
        }
        code, data = self._post(base_ep, {"X-Hook-Secret": TEST_SECRET})
        self.assertEqual(code, 200)
        self.assertEqual(data["action"], "cache_invalidated")
        self.assertEqual(len(self.notify.sent), 1)
        msg = self.notify.sent[0]
        self.assertEqual(msg["category"], "available")
        self.assertEqual(msg["title"], "🍿 Ready to watch")
        self.assertEqual(msg["body"], "Severance · S01E02\nHalf Loop")
        self.assertEqual(msg["link"], "pccontrol://watch/tv/95396")

        # Bulk import: episodes 3 and 4 of same series within 15s should NOT spam
        for ep_no in ("03", "04"):
            self._seed_caches()
            ep = {**base_ep, "EpisodeNumber00": ep_no, "Name": f"Ep {ep_no}"}
            c, d = self._post(ep, {"X-Hook-Secret": TEST_SECRET})
            self.assertEqual(c, 200)
            self.assertTrue(d.get("deduped"))
            # Cache is still invalidated even when notification is deduped
            self.assertEqual(self.watch._library_index_cache, (0.0, None))
        self.assertEqual(len(self.notify.sent), 1)

        # After 15s window expires, another episode notifies again
        with mock.patch("pcapi.time.monotonic", return_value=pcapi.time.monotonic() + 20.0):
            ep5 = {**base_ep, "EpisodeNumber00": "05", "Name": "The Grim Barbarity of Optics and Design"}
            c, d = self._post(ep5, {"X-Hook-Secret": TEST_SECRET})
            self.assertEqual(c, 200)
            self.assertFalse(d.get("deduped", False))
            self.assertEqual(len(self.notify.sent), 2)

    def test_item_added_containers_invalidate_cache_without_push(self):
        for item_type in ("Series", "Season", "Folder", "BoxSet"):
            self._seed_caches()
            code, data = self._post(
                {"NotificationType": "ItemAdded", "ItemType": item_type, "Name": "Something"},
                {"X-Hook-Secret": TEST_SECRET},
            )
            self.assertEqual(code, 200)
            self.assertEqual(data, {"ok": True, "action": "cache_invalidated"})
            self.assertEqual(self.watch._home_cache, (0.0, None))
            self.assertEqual(self.watch._library_index_cache, (0.0, None))
        self.assertEqual(self.notify.sent, [])

    def test_unknown_notification_type_ignored(self):
        code, data = self._post(
            {"NotificationType": "PluginInstalled", "Name": "AniSync"},
            {"X-Hook-Secret": TEST_SECRET},
        )
        self.assertEqual(code, 200)
        self.assertEqual(data, {"ok": True, "ignored": True})
        self.assertIsNotNone(self.watch._home_cache[1])
        self.assertEqual(self.notify.sent, [])


if __name__ == "__main__":
    unittest.main()
