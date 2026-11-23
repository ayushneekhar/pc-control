"""Unit and HTTP tests for server/space.py (Space Manager backend)."""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402
import space  # noqa: E402


class FakeStatVfs:
    def __init__(self, total, free, bfree=None, frsize=4096):
        self.f_frsize = frsize
        self.f_blocks = total // frsize
        self.f_bavail = free // frsize
        self.f_bfree = (free if bfree is None else bfree) // frsize


class FakeAgent:
    DL_STATES = {"downloading", "metaDL", "forcedMetaDL", "stalledDL", "queuedDL", "pausedDL", "stoppedDL"}

    def __init__(self, users=None, played=None, torrents=None, jf_err=None, qbt_err=None):
        self.users = users or []
        self.played = played or {}
        self.torrents = torrents or []
        self.jf_err = jf_err
        self.qbt_err = qbt_err

    def jellyfin(self, method, path, timeout=30):
        if self.jf_err:
            raise self.jf_err
        if path == "/Users":
            return self.users
        for uid, items in self.played.items():
            if path.startswith(f"/Users/{uid}/Items"):
                return {"Items": items}
        return {"Items": []}

    def qbt(self, endpoint, **params):
        if self.qbt_err:
            raise self.qbt_err
        if endpoint == "torrents/info":
            return self.torrents
        return []


class FakeRefuse(Exception):
    pass


@dataclass
class FakeMoviePlan:
    movie_id: int
    label: str
    monitored: bool
    file_id: int
    file: str
    size: int
    key: tuple
    nlink: int = 1


@dataclass
class FakeEpisodeFile:
    file_id: int
    path: str
    size: int
    key: tuple
    nlink: int = 1


@dataclass
class FakeSeasonPlan:
    series_id: int
    season_num: int
    label: str
    monitored: bool
    series_path: str
    files: list = field(default_factory=list)


class FakeWatched:
    Refuse = FakeRefuse

    def __init__(self, hdd, movies=None, series=None, ep_files=None, radarr_err=None, sonarr_err=None):
        self.HDD = Path(hdd)
        self.SONARR_DL = self.HDD / "Torrents" / "tv-sonarr"
        self._lock = threading.Lock()
        self._movies = movies or []
        self._series = series or []
        self._ep_files = ep_files or {}
        self._radarr_err = radarr_err
        self._sonarr_err = sonarr_err
        self._free_val = 200 * 1024 ** 3
        self.calls = []
        self.plans = {}
        self.season_plans = {}
        self.fail_step = None
        self.keep_hardlink = False
        self._hardlink_keep_paths = []

    def radarr(self, method, path, data=None):
        if self._radarr_err:
            raise self._radarr_err
        if method == "GET" and path == "/movie":
            return self._movies
        return {}

    def sonarr_series(self):
        if self._sonarr_err:
            raise self._sonarr_err
        return self._series

    def sonarr(self, method, path, data=None):
        if self._sonarr_err:
            raise self._sonarr_err
        if method == "GET" and path.startswith("/episodefile?seriesId="):
            sid = int(path.split("=", 1)[1])
            return self._ep_files.get(sid, [])
        return []

    def rel(self, path):
        return str(path)

    def _free(self):
        return self._free_val

    def plan(self, radarr_id):
        self.calls.append(("plan", radarr_id))
        res = self.plans[radarr_id]
        if isinstance(res, Exception):
            raise res
        return res

    def plan_season(self, series_id, season_num):
        self.calls.append(("plan_season", series_id, season_num))
        res = self.season_plans[(series_id, season_num)]
        if isinstance(res, Exception):
            raise res
        return res

    def _unmonitor(self, p):
        self.calls.append(("unmonitor", p.movie_id))
        if self.fail_step == "unmonitor":
            raise RuntimeError("radarr down")
        return "set to unmonitored"

    def _unmonitor_season(self, p):
        self.calls.append(("unmonitor_season", p.series_id, p.season_num))
        if self.fail_step == "unmonitor_season":
            raise RuntimeError("sonarr down")
        return "season set to unmonitored"

    def _remove_copies(self, p, dl_root=None):
        self.calls.append(("remove_copies", dl_root))
        if self.fail_step == "remove_copies":
            raise RuntimeError("qbt timeout")
        return "ok"

    def _delete_movie_file(self, p):
        self.calls.append(("delete_movie_file", p.file_id))
        if self.fail_step == "delete_movie_file":
            raise RuntimeError("delete failed")
        if not self.keep_hardlink:
            os.unlink(p.file)
            self._free_val += p.size
        return "deleted"

    def _delete_season_files(self, p):
        self.calls.append(("delete_season_files", [f.file_id for f in p.files]))
        if self.fail_step == "delete_season_files":
            raise RuntimeError("delete failed")
        for f in p.files:
            if not self.keep_hardlink:
                os.unlink(f.path)
                self._free_val += f.size
        return "deleted"

    def _refresh(self):
        self.calls.append(("refresh",))
        if self.fail_step == "refresh":
            raise RuntimeError("jellyfin down")
        return "library refresh started"

    def _seerr_later(self, folder, label=""):
        self.calls.append(("seerr_later", folder, label))


class SpaceGetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.hdd = self.tmp.name
        self.movie1_path = os.path.join(self.hdd, "Media", "Movies", "Inception (2010)", "Inception.mkv")
        self.movie2_path = os.path.join(self.hdd, "Media", "Movies", "Dune (2021)", "Dune.mkv")
        self.movie3_path = os.path.join(self.hdd, "Media", "Movies", "Unwatched (2024)", "Unwatched.mkv")
        self.s1_e1 = os.path.join(self.hdd, "Media", "Shows", "Severance", "Season 01", "S01E01.mkv")
        self.s1_e2 = os.path.join(self.hdd, "Media", "Shows", "Severance", "Season 01", "S01E02.mkv")
        self.s2_e1 = os.path.join(self.hdd, "Media", "Shows", "Severance", "Season 02", "S02E01.mkv")
        self.s2_e2 = os.path.join(self.hdd, "Media", "Shows", "Severance", "Season 02", "S02E02.mkv")

        self.users = [
            {"Id": "u1", "Name": "Ayush", "Policy": {"IsDisabled": False}},
            {"Id": "u2", "Name": "Amrita", "Policy": {"IsDisabled": False}},
            {"Id": "u3", "Name": "OldGuest", "Policy": {"IsDisabled": True}},
        ]
        self.played = {
            "u1": [
                {"Path": self.movie1_path, "ProviderIds": {"Tmdb": "27205"}},
                {"Path": self.movie2_path, "ProviderIds": {"Tmdb": "438631"}},
                {"Path": self.s1_e1},
                {"Path": self.s1_e2},
                {"Path": self.s2_e1},  # S2 only half watched by Ayush -> excluded
            ],
            "u2": [
                {"Path": self.movie2_path, "ProviderIds": {"Tmdb": "438631"}},
            ],
        }
        self.movies = [
            {"id": 12, "title": "Inception", "year": 2010, "tmdbId": 27205, "hasFile": True, "monitored": True,
             "movieFile": {"id": 101, "path": self.movie1_path, "size": 10 * 1024 ** 3}},
            {"id": 15, "title": "Dune", "year": 2021, "tmdbId": 438631, "hasFile": True, "monitored": False,
             "movieFile": {"id": 102, "path": self.movie2_path, "size": 18 * 1024 ** 3}},
            {"id": 19, "title": "Unwatched", "year": 2024, "tmdbId": 999, "hasFile": True, "monitored": True,
             "movieFile": {"id": 103, "path": self.movie3_path, "size": 25 * 1024 ** 3}},
        ]
        self.series = [
            {
                "id": 5, "title": "Severance", "year": 2022,
                "seasons": [
                    {"seasonNumber": 1, "monitored": True, "statistics": {"episodeFileCount": 2}},
                    {"seasonNumber": 2, "monitored": True, "statistics": {"episodeFileCount": 2}},
                ],
            }
        ]
        self.ep_files = {
            5: [
                {"id": 201, "seasonNumber": 1, "path": self.s1_e1, "size": 6 * 1024 ** 3},
                {"id": 202, "seasonNumber": 1, "path": self.s1_e2, "size": 6 * 1024 ** 3},
                {"id": 203, "seasonNumber": 2, "path": self.s2_e1, "size": 7 * 1024 ** 3},
                {"id": 204, "seasonNumber": 2, "path": self.s2_e2, "size": 7 * 1024 ** 3},
            ]
        }
        self.torrents = [
            {"hash": "h1", "state": "downloading", "progress": 0.4, "size": 20 * 1024 ** 3,
             "amount_left": 12 * 1024 ** 3, "save_path": os.path.join(self.hdd, "Torrents", "radarr")},
            {"hash": "h2", "state": "pausedDL", "progress": 0.2, "size": 10 * 1024 ** 3,
             "amount_left": 8 * 1024 ** 3, "save_path": os.path.join(self.hdd, "Torrents", "radarr")},
            {"hash": "h3", "state": "metaDL", "progress": 0.0, "size": 0,
             "amount_left": 0, "save_path": os.path.join(self.hdd, "Torrents", "qwen")},
            {"hash": "h4", "state": "downloading", "progress": 0.5, "size": 5 * 1024 ** 3,
             "amount_left": 2 * 1024 ** 3, "save_path": "/tmp/other"},
        ]

    def test_full_space_overview(self):
        ag = FakeAgent(self.users, self.played, self.torrents)
        wa = FakeWatched(self.hdd, self.movies, self.series, self.ep_files)
        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(1000 * 1024 ** 3, 200 * 1024 ** 3)):
            code, body = space.get_space(watched_mod=wa, agent_mod=ag, mount=self.hdd)
        self.assertEqual(code, 200)
        self.assertEqual(body["errors"], {})
        self.assertTrue(body["hdd"]["mounted"])
        self.assertEqual(body["hdd"]["free_bytes"], 200 * 1024 ** 3)
        self.assertEqual(body["hdd"]["pending_bytes"], 12 * 1024 ** 3)
        self.assertEqual(body["hdd"]["pending_count"], 2)
        self.assertEqual(body["hdd"]["pending_unknown"], 1)
        self.assertEqual(body["hdd"]["predicted_free_bytes"], 188 * 1024 ** 3)

        ids = [c["id"] for c in body["candidates"]]
        # Sorted largest first: Dune (18G), Severance S1 (12G), Inception (10G)
        self.assertEqual(ids, ["movie:15", "season:5:1", "movie:12"])

        dune = body["candidates"][0]
        self.assertEqual(dune["watched_count"], 2)
        self.assertEqual(dune["total_users"], 2)
        self.assertFalse(dune["warn_unwatched"])
        self.assertEqual(dune["unwatched_users"], [])

        sev = body["candidates"][1]
        self.assertEqual(sev["kind"], "season")
        self.assertEqual(sev["subtitle"], "Season 1")
        self.assertEqual(sev["episode_count"], 2)
        self.assertTrue(sev["warn_unwatched"])
        self.assertEqual(sev["unwatched_users"], ["Amrita"])

        inc = body["candidates"][2]
        self.assertTrue(inc["warn_unwatched"])
        self.assertEqual(inc["unwatched_users"], ["Amrita"])

    def test_unmounted_hdd_returns_200_gracefully(self):
        ag = FakeAgent(self.users, self.played, [])
        wa = FakeWatched(self.hdd, self.movies, self.series, self.ep_files)
        with mock.patch("os.path.ismount", return_value=False):
            code, body = space.get_space(watched_mod=wa, agent_mod=ag, mount=self.hdd)
        self.assertEqual(code, 200)
        self.assertFalse(body["hdd"]["mounted"])
        self.assertIsNone(body["hdd"]["free_bytes"])
        self.assertEqual(body["candidates"], [])
        self.assertIn("hdd", body["errors"])

    def test_partial_upstream_failures(self):
        # Radarr down + qBt down -> Sonarr seasons still returned
        ag = FakeAgent(self.users, self.played, qbt_err=OSError("qbt offline"))
        wa = FakeWatched(self.hdd, self.movies, self.series, self.ep_files, radarr_err=OSError("radarr down"))
        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(500 * 1024 ** 3, 100 * 1024 ** 3)):
            code, body = space.get_space(watched_mod=wa, agent_mod=ag, mount=self.hdd)
        self.assertEqual(code, 200)
        self.assertIn("radarr", body["errors"])
        self.assertIn("qbt", body["errors"])
        self.assertNotIn("sonarr", body["errors"])
        self.assertEqual([c["id"] for c in body["candidates"]], ["season:5:1"])

        # Jellyfin down -> HDD still returned, candidates empty
        ag2 = FakeAgent(jf_err=OSError("jellyfin down"))
        wa2 = FakeWatched(self.hdd, self.movies, self.series, self.ep_files)
        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(500 * 1024 ** 3, 100 * 1024 ** 3)):
            code2, body2 = space.get_space(watched_mod=wa2, agent_mod=ag2, mount=self.hdd)
        self.assertEqual(code2, 200)
        self.assertIn("jellyfin", body2["errors"])
        self.assertEqual(body2["candidates"], [])
        self.assertEqual(body2["hdd"]["free_bytes"], 100 * 1024 ** 3)


class SpaceFreeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.hdd = self.tmp.name

    def _make_file(self, relpath, content=b"video-data"):
        p = os.path.join(self.hdd, relpath)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(content)
        st = os.lstat(p)
        return p, (st.st_dev, st.st_ino), st.st_size

    def test_parse_free_items_validation(self):
        parsed = space.parse_free_items({
            "items": [
                {"kind": "movie", "radarr_id": 12},
                {"id": "movie:12"},  # duplicate
                {"kind": "season", "series_id": 5, "season_number": 2},
                "season:5:0",
            ]
        })
        self.assertEqual(len(parsed), 3)
        self.assertEqual(parsed[0], {"id": "movie:12", "kind": "movie", "radarr_id": 12})
        self.assertEqual(parsed[1], {"id": "season:5:2", "kind": "season", "series_id": 5, "season_number": 2})
        self.assertEqual(parsed[2], {"id": "season:5:0", "kind": "season", "series_id": 5, "season_number": 0})

        for bad in ({}, {"items": []}, {"items": "movie:1"}, {"items": [{"kind": "movie", "radarr_id": True}]},
                    {"items": [{"kind": "season", "series_id": 1, "season_number": -1}]},
                    {"items": [{"id": "bogus:1"}]}):
            with self.assertRaises(ValueError):
                space.parse_free_items(bad)

    def test_free_movie_and_season_success(self):
        mp, mkey, _ = self._make_file("Media/Movies/Inception (2010)/Inception.mkv")
        e1p, e1key, _ = self._make_file("Media/Shows/Severance/Season 01/S01E01.mkv")
        e2p, e2key, _ = self._make_file("Media/Shows/Severance/Season 01/S01E02.mkv")

        wa = FakeWatched(self.hdd)
        wa.plans[12] = FakeMoviePlan(12, "Inception (2010)", True, 101, mp, 10 * 1024 ** 3, mkey)
        wa.season_plans[(5, 1)] = FakeSeasonPlan(
            5, 1, "Severance S01", True, os.path.join(self.hdd, "Media/Shows/Severance"),
            files=[
                FakeEpisodeFile(201, e1p, 4 * 1024 ** 3, e1key),
                FakeEpisodeFile(202, e2p, 4 * 1024 ** 3, e2key),
            ],
        )
        ag = FakeAgent()

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(1000 * 1024 ** 3, 218 * 1024 ** 3)):
            code, body = space.free_space(
                {"items": [{"kind": "movie", "radarr_id": 12}, {"kind": "season", "series_id": 5, "season_number": 1}]},
                watched_mod=wa, agent_mod=ag, mount=self.hdd,
            )

        self.assertEqual(code, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(body["freed_bytes"], 18 * 1024 ** 3)
        self.assertEqual(body["warnings"], [])
        self.assertEqual(len(body["results"]), 2)
        self.assertTrue(all(r["ok"] for r in body["results"]))
        self.assertEqual(body["results"][0]["freed_bytes"], 10 * 1024 ** 3)
        self.assertEqual(body["results"][1]["freed_bytes"], 8 * 1024 ** 3)
        self.assertFalse(os.path.exists(mp))
        self.assertFalse(os.path.exists(e1p))
        self.assertFalse(os.path.exists(e2p))
        # Jellyfin refresh and Seerr sync triggered once after batch
        self.assertEqual(sum(1 for c in wa.calls if c[0] == "refresh"), 1)
        self.assertEqual(sum(1 for c in wa.calls if c[0] == "seerr_later"), 1)

    def test_refuse_and_step_failure_preserve_safety(self):
        mp, mkey, _ = self._make_file("Media/Movies/Inception (2010)/Inception.mkv")
        wa = FakeWatched(self.hdd)
        wa.plans[12] = FakeMoviePlan(12, "Inception (2010)", True, 101, mp, 5 * 1024 ** 3, mkey)
        wa.plans[99] = FakeRefuse("Still downloading")
        wa.fail_step = "remove_copies"
        ag = FakeAgent()

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(1000 * 1024 ** 3, 200 * 1024 ** 3)):
            code, body = space.free_space(
                {"items": [{"id": "movie:99"}, {"id": "movie:12"}]},
                watched_mod=wa, agent_mod=ag, mount=self.hdd,
            )

        self.assertEqual(code, 200)
        self.assertFalse(body["ok"])
        self.assertEqual(body["results"][0]["error"], "Still downloading")
        self.assertIn("Torrent copy", body["results"][1]["error"])
        # Because remove_copies failed, delete_movie_file must NOT have run and file still exists
        self.assertFalse(any(c[0] == "delete_movie_file" for c in wa.calls))
        self.assertTrue(os.path.exists(mp))
        # No refresh/seerr when nothing succeeded
        self.assertFalse(any(c[0] == "refresh" for c in wa.calls))

    def test_remaining_hardlink_adds_warning(self):
        mp, mkey, _ = self._make_file("Media/Movies/Link (2020)/Link.mkv")
        wa = FakeWatched(self.hdd)
        wa.plans[7] = FakeMoviePlan(7, "Link (2020)", True, 107, mp, 2 * 1024 ** 3, mkey)
        wa.keep_hardlink = True
        ag = FakeAgent()

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(1000 * 1024 ** 3, 200 * 1024 ** 3)):
            code, body = space.free_space({"items": ["movie:7"]}, watched_mod=wa, agent_mod=ag, mount=self.hdd)

        self.assertEqual(code, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(len(body["results"][0]["warnings"]), 1)
        self.assertIn("hardlink", body["results"][0]["warnings"][0])


class SpaceHttpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tok_file = os.path.join(self.tmp.name, "local-token")
        with open(self.tok_file, "w") as f:
            f.write("test-space-token\n")
        self.p_tok = mock.patch.object(pcapi, "LOCAL_TOKEN_FILE", self.tok_file)
        self.p_allow = mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", True)
        self.p_tok.start()
        self.p_allow.start()
        self.addCleanup(self.p_tok.stop)
        self.addCleanup(self.p_allow.stop)

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        self.th = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.th.start()
        self.addCleanup(self.srv.shutdown)
        self.addCleanup(self.srv.server_close)

    def _req(self, method, path, body=None, auth=True):
        headers = {"X-PC-Local-Token": "test-space-token"} if auth else {}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_get_and_post_space_endpoints(self):
        self.assertEqual(self._req("GET", "/pc/api/space", auth=False)[0], 403)
        self.assertEqual(self._req("POST", "/pc/api/space/free", {"items": ["movie:1"]}, auth=False)[0], 403)

        fake_overview = {"hdd": {"mounted": True, "free_bytes": 100}, "candidates": [], "errors": {}, "ts": 1}
        fake_free = {"ok": True, "freed_bytes": 50, "results": [], "warnings": [], "hdd": {"mounted": True}}
        with mock.patch.object(space, "get_space", return_value=(200, fake_overview)), \
                mock.patch.object(space, "free_space", return_value=(200, fake_free)) as m_free:
            code, data = self._req("GET", "/pc/api/space")
            self.assertEqual(code, 200)
            self.assertEqual(data, fake_overview)

            code2, data2 = self._req("POST", "/pc/api/space/free", {"items": [{"kind": "movie", "radarr_id": 12}]})
            self.assertEqual(code2, 200)
            self.assertEqual(data2, fake_free)
            m_free.assert_called_once_with({"items": [{"kind": "movie", "radarr_id": 12}]})

        # Real validation 400 passes through
        code3, err3 = self._req("POST", "/pc/api/space/free", {"items": []})
        self.assertEqual(code3, 400)
        self.assertIn("items", err3["error"])


if __name__ == "__main__":
    unittest.main()
