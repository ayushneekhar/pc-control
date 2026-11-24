"""Unit and HTTP tests for server/gdrive_offload.py and space.py scope=all."""
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import gdrive_offload  # noqa: E402
import pcapi  # noqa: E402
import space  # noqa: E402
from test_space import (  # noqa: E402
    FakeAgent,
    FakeEpisodeFile,
    FakeMoviePlan,
    FakeSeasonPlan,
    FakeStatVfs,
    FakeWatched,
)


class FakePopen:
    """Mock subprocess.Popen for rclone copy/check."""

    def __init__(self, cmd, returncode=0, stderr_lines=None, stdout_text="", on_wait=None, block_event=None):
        self.cmd = cmd
        self.returncode = returncode
        self._stderr_lines = list(stderr_lines or [])
        self._stdout_text = stdout_text
        self._on_wait = on_wait
        self._block_event = block_event
        self.terminated = False
        self.killed = False
        self.stdout = io.StringIO(self._stdout_text)
        self.stderr = self._iter_stderr()

    def _iter_stderr(self):
        for line in self._stderr_lines:
            if self.terminated or self.killed:
                break
            yield line
        if self._block_event is not None:
            self._block_event.wait(timeout=5)

    def terminate(self):
        self.terminated = True
        self.returncode = -15
        if self._block_event is not None:
            self._block_event.set()

    def kill(self):
        self.killed = True
        self.returncode = -9
        if self._block_event is not None:
            self._block_event.set()

    def wait(self, timeout=None):
        if self._block_event is not None:
            self._block_event.wait(timeout=timeout or 5)
        if self._on_wait:
            self._on_wait()
        return self.returncode

    def communicate(self, timeout=None):
        if self._block_event is not None:
            self._block_event.wait(timeout=timeout or 5)
        return self._stdout_text, "".join(self._stderr_lines)


def _cp(returncode=0, stdout="", stderr=""):
    return subprocess.CompletedProcess(args=["rclone"], returncode=returncode, stdout=stdout, stderr=stderr)


class PathValidationTests(unittest.TestCase):
    def test_validate_remote_rel_path(self):
        self.assertEqual(
            gdrive_offload.validate_remote_rel_path("Movies/Inception (2010)"),
            "Movies/Inception (2010)",
        )
        self.assertEqual(
            gdrive_offload.validate_remote_rel_path("Shows/Severance/Season 01"),
            "Shows/Severance/Season 01",
        )
        bad_paths = [
            "",
            "   ",
            "Movies",
            "Shows",
            "/Movies/Inception (2010)",
            "Movies/Inception (2010)/",
            "Movies/../etc/passwd",
            "Movies/./Inception",
            "Shows/Severance/../../etc",
            "Movies\\Inception",
            "Torrents/radarr/foo",
            "Movies/foo\x00bar",
            None,
        ]
        for bad in bad_paths:
            with self.assertRaises(ValueError, msg=f"should reject {bad!r}"):
                gdrive_offload.validate_remote_rel_path(bad)

    def test_validate_local_media_path_containment_and_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            hdd = os.path.join(tmp, "hdd")
            outside = os.path.join(tmp, "outside")
            movie_dir = os.path.join(hdd, "Media", "Movies", "Dune (2021)")
            os.makedirs(movie_dir)
            os.makedirs(os.path.join(hdd, "Media", "Shows"))
            os.makedirs(outside)

            escape_link = os.path.join(hdd, "Media", "Movies", "Escape")
            os.symlink(outside, escape_link)

            with mock.patch("os.path.ismount", return_value=False):
                with self.assertRaises(ValueError):
                    gdrive_offload.validate_local_media_path(movie_dir, hdd)

            with mock.patch("os.path.ismount", return_value=True):
                real, rel = gdrive_offload.validate_local_media_path(movie_dir, hdd)
                self.assertEqual(real, os.path.realpath(movie_dir))
                self.assertEqual(rel, "Movies/Dune (2021)")

                # Root Movies dir itself rejected
                with self.assertRaises(ValueError):
                    gdrive_offload.validate_local_media_path(os.path.join(hdd, "Media", "Movies"), hdd)
                # Outside dir rejected
                with self.assertRaises(ValueError):
                    gdrive_offload.validate_local_media_path(outside, hdd)
                # Symlink escaping Media/Movies rejected
                with self.assertRaises(ValueError):
                    gdrive_offload.validate_local_media_path(escape_link, hdd)


class SpaceScopeAllTests(unittest.TestCase):
    def test_scope_all_includes_unwatched_with_flags(self):
        with tempfile.TemporaryDirectory() as hdd:
            m1 = os.path.join(hdd, "Media", "Movies", "Inception (2010)", "Inception.mkv")
            m2 = os.path.join(hdd, "Media", "Movies", "Unwatched (2024)", "Unwatched.mkv")
            s1 = os.path.join(hdd, "Media", "Shows", "Severance", "Season 01", "S01E01.mkv")
            s2 = os.path.join(hdd, "Media", "Shows", "Severance", "Season 02", "S02E01.mkv")

            users = [
                {"Id": "u1", "Name": "Ayush", "Policy": {"IsDisabled": False}},
                {"Id": "u2", "Name": "Amrita", "Policy": {"IsDisabled": False}},
            ]
            played = {
                "u1": [{"Path": m1, "ProviderIds": {"Tmdb": "27205"}}, {"Path": s1}],
                "u2": [{"Path": m1, "ProviderIds": {"Tmdb": "27205"}}],
            }
            movies = [
                {"id": 12, "title": "Inception", "year": 2010, "tmdbId": 27205, "hasFile": True, "monitored": True,
                 "movieFile": {"id": 101, "path": m1, "size": 10 * 1024 ** 3}},
                {"id": 19, "title": "Unwatched", "year": 2024, "tmdbId": 999, "hasFile": True, "monitored": True,
                 "movieFile": {"id": 103, "path": m2, "size": 25 * 1024 ** 3}},
            ]
            series = [
                {
                    "id": 5, "title": "Severance", "year": 2022,
                    "seasons": [
                        {"seasonNumber": 1, "monitored": True, "statistics": {"episodeFileCount": 1}},
                        {"seasonNumber": 2, "monitored": True, "statistics": {"episodeFileCount": 1}},
                    ],
                }
            ]
            ep_files = {
                5: [
                    {"id": 201, "seasonNumber": 1, "path": s1, "size": 6 * 1024 ** 3},
                    {"id": 203, "seasonNumber": 2, "path": s2, "size": 7 * 1024 ** 3},
                ]
            }

            ag = FakeAgent(users, played, [])
            wa = FakeWatched(hdd, movies, series, ep_files)
            with mock.patch("os.path.ismount", return_value=True), \
                    mock.patch("os.statvfs", return_value=FakeStatVfs(1000 * 1024 ** 3, 200 * 1024 ** 3)):
                _, watched_res = space.get_space(watched_mod=wa, agent_mod=ag, mount=hdd, scope="watched")
                _, all_res = space.get_space(watched_mod=wa, agent_mod=ag, mount=hdd, scope="all")

            self.assertEqual([c["id"] for c in watched_res["candidates"]], ["movie:12", "season:5:1"])
            self.assertEqual(
                [c["id"] for c in all_res["candidates"]],
                ["movie:19", "movie:12", "season:5:2", "season:5:1"],
            )
            by_id = {c["id"]: c for c in all_res["candidates"]}
            self.assertTrue(by_id["movie:12"]["watched"])
            self.assertEqual(by_id["movie:12"]["watched_by"], ["Amrita", "Ayush"])
            self.assertFalse(by_id["movie:19"]["watched"])
            self.assertEqual(by_id["movie:19"]["watched_by"], [])
            self.assertTrue(by_id["season:5:1"]["watched"])
            self.assertEqual(by_id["season:5:1"]["watched_by"], ["Ayush"])
            self.assertFalse(by_id["season:5:2"]["watched"])
            self.assertEqual(by_id["season:5:2"]["watched_by"], [])

            # Even if Jellyfin is down, scope=all still lists all local items
            ag_down = FakeAgent(jf_err=OSError("jf down"))
            with mock.patch("os.path.ismount", return_value=True), \
                    mock.patch("os.statvfs", return_value=FakeStatVfs(1000 * 1024 ** 3, 200 * 1024 ** 3)):
                _, all_down = space.get_space(watched_mod=wa, agent_mod=ag_down, mount=hdd, scope="all")
            self.assertEqual(len(all_down["candidates"]), 4)
            self.assertIn("jellyfin", all_down["errors"])


class GDriveOffloadManagerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.hdd = os.path.join(self.tmp.name, "hdd")
        self.mount_pt = os.path.join(self.tmp.name, "gdrive-mount")
        self.meta_file = os.path.join(self.tmp.name, "gdrive-offload.json")
        os.makedirs(os.path.join(self.hdd, "Media", "Movies"), exist_ok=True)
        os.makedirs(os.path.join(self.hdd, "Media", "Shows"), exist_ok=True)
        os.makedirs(self.mount_pt, exist_ok=True)
        self.notifications = []

    def _make_file(self, relpath, content=b"video-bytes"):
        p = os.path.join(self.hdd, relpath)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(content)
        st = os.lstat(p)
        return p, (st.st_dev, st.st_ino), st.st_size

    def _make_manager(self, watched_mod=None, agent_mod=None, start_worker=True):
        mgr = gdrive_offload.GDriveOffloadManager(
            rclone_bin="/usr/bin/rclone",
            mount_point=self.mount_pt,
            hdd_mnt=self.hdd,
            metadata_file=self.meta_file,
            notify_fn=lambda cat, title, body: self.notifications.append((cat, title, body)),
            watched_loader=lambda: watched_mod,
            agent_loader=lambda: agent_mod or FakeAgent(),
            start_worker=start_worker,
        )
        self.addCleanup(mgr.shutdown)
        return mgr

    def _wait_job(self, mgr, job_id, timeout=5.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            jobs = {j["id"]: j for j in mgr.snapshot_jobs()}
            j = jobs.get(job_id)
            if j and j["status"] in gdrive_offload.TERMINAL_STATUSES:
                return j
            time.sleep(0.02)
        self.fail(f"job {job_id} did not finish in {timeout}s")

    def test_drive_status_and_quota_cache(self):
        mgr = self._make_manager(start_worker=False)
        calls = []

        def fake_run(args, timeout=30):
            calls.append(args)
            if args[0] == "listremotes":
                return _cp(0, "gdrive:\n")
            if args[0] == "about":
                return _cp(0, json.dumps({"total": 2000 * 1024 ** 3, "used": 400 * 1024 ** 3, "free": 1600 * 1024 ** 3}))
            if args[0] == "lsjson" and args[-1].endswith("/Movies"):
                return _cp(0, json.dumps([
                    {"Path": "Inception (2010)/Inception.mkv", "Size": 10 * 1024 ** 3},
                    {"Path": "Inception (2010)/Inception.srt", "Size": 50000},
                ]))
            if args[0] == "lsjson" and args[-1].endswith("/Shows"):
                return _cp(0, json.dumps([
                    {"Path": "Severance/Season 01/S01E01.mkv", "Size": 5 * 1024 ** 3},
                    {"Path": "Severance/Season 01/S01E02.mkv", "Size": 5 * 1024 ** 3},
                ]))
            return _cp(0, "")

        with mock.patch.object(mgr, "_run_rclone", side_effect=fake_run), \
                mock.patch("os.path.ismount", side_effect=lambda p: p == self.mount_pt):
            code, st = mgr.drive_status()
            self.assertEqual(code, 200)
            self.assertTrue(st["configured"])
            self.assertTrue(st["mounted"])
            self.assertEqual(st["quota"]["used_bytes"], 400 * 1024 ** 3)
            self.assertEqual(len(st["items"]), 2)
            by_rel = {it["rel_path"]: it for it in st["items"]}
            self.assertEqual(by_rel["Movies/Inception (2010)"]["title"], "Inception")
            self.assertEqual(by_rel["Movies/Inception (2010)"]["year"], 2010)
            self.assertEqual(by_rel["Shows/Severance/Season 01"]["subtitle"], "Season 1")
            self.assertEqual(by_rel["Shows/Severance/Season 01"]["episode_count"], 2)

            # Second call hits 60s quota cache
            about_count_1 = sum(1 for c in calls if c[0] == "about")
            mgr.drive_status()
            about_count_2 = sum(1 for c in calls if c[0] == "about")
            self.assertEqual(about_count_1, 1)
            self.assertEqual(about_count_2, 1)

    def test_offload_happy_path_with_md5_verify(self):
        mp, mkey, _ = self._make_file("Media/Movies/Inception (2010)/Inception.mkv", b"movie-content")
        wa = FakeWatched(
            self.hdd,
            movies=[{"id": 12, "title": "Inception", "year": 2010, "tmdbId": 27205}],
        )
        wa.plans[12] = FakeMoviePlan(12, "Inception (2010)", True, 101, mp, 10 * 1024 ** 3, mkey)
        mgr = self._make_manager(watched_mod=wa)

        popen_cmds = []
        stats_line = json.dumps({
            "stats": {"bytes": 5 * 1024 ** 3, "totalBytes": 10 * 1024 ** 3, "speed": 50_000_000, "eta": 100}
        }) + "\n"

        def fake_popen(cmd, **kwargs):
            popen_cmds.append(cmd)
            if cmd[1] == "copy":
                return FakePopen(cmd, returncode=0, stderr_lines=[stats_line])
            if cmd[1] == "check":
                return FakePopen(cmd, returncode=0)
            raise AssertionError(f"unexpected popen cmd: {cmd}")

        vfs_calls = []

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch("subprocess.Popen", side_effect=fake_popen), \
                mock.patch.object(mgr, "refresh_vfs", side_effect=lambda d=None: vfs_calls.append(d) or True):
            code, res = mgr.enqueue_offload({"items": [{"kind": "movie", "radarr_id": 12}]})
            self.assertEqual(code, 202)
            self.assertEqual(len(res["jobs"]), 1)
            job_id = res["jobs"][0]["id"]
            done = self._wait_job(mgr, job_id)

        self.assertEqual(done["status"], "done")
        self.assertEqual(done["pct"], 100.0)
        # Both rclone copy and rclone check ran
        self.assertEqual([c[1] for c in popen_cmds], ["copy", "check"])
        self.assertIn("--use-json-log", popen_cmds[0])
        self.assertTrue(popen_cmds[0][3].endswith("gdrive:Media/Movies/Inception (2010)"))
        # Local file deleted via watched.py flow after verify
        self.assertFalse(os.path.exists(mp))
        self.assertTrue(any(c[0] == "unmonitor" for c in wa.calls))
        self.assertTrue(any(c[0] == "delete_movie_file" for c in wa.calls))
        self.assertTrue(any(c[0] == "refresh" for c in wa.calls))
        self.assertEqual(vfs_calls, ["Movies"])
        # Metadata saved
        meta = mgr._read_metadata()
        self.assertIn("Movies/Inception (2010)", meta)
        self.assertEqual(meta["Movies/Inception (2010)"]["radarr_id"], 12)

    def test_offload_md5_check_failure_never_deletes_local(self):
        mp, mkey, _ = self._make_file("Media/Movies/Dune (2021)/Dune.mkv", b"movie-content")
        wa = FakeWatched(self.hdd, movies=[{"id": 15, "title": "Dune", "year": 2021, "tmdbId": 438631}])
        wa.plans[15] = FakeMoviePlan(15, "Dune (2021)", True, 102, mp, 18 * 1024 ** 3, mkey)
        mgr = self._make_manager(watched_mod=wa)

        def fake_popen(cmd, **kwargs):
            if cmd[1] == "copy":
                return FakePopen(cmd, returncode=0)
            if cmd[1] == "check":
                return FakePopen(cmd, returncode=1, stderr_lines=["ERROR : Dune.mkv: md5 differ\n"])
            raise AssertionError(f"unexpected popen cmd: {cmd}")

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch("subprocess.Popen", side_effect=fake_popen):
            code, res = mgr.enqueue_offload({"items": [{"kind": "movie", "radarr_id": 15}]})
            self.assertEqual(code, 202)
            done = self._wait_job(mgr, res["jobs"][0]["id"])

        self.assertEqual(done["status"], "failed")
        self.assertIn("md5 differ", done["error"])
        # CRITICAL: local file MUST still exist and delete/unmonitor MUST NOT have been called
        self.assertTrue(os.path.exists(mp))
        self.assertFalse(any(c[0] == "unmonitor" for c in wa.calls))
        self.assertFalse(any(c[0] == "delete_movie_file" for c in wa.calls))

    def test_restore_rejects_insufficient_free_space(self):
        mgr = self._make_manager(start_worker=False)
        item_size = 10 * 1024 ** 3
        # Free = 14 GiB <= 10 GiB + 5 GiB margin -> must reject with 409
        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(100 * 1024 ** 3, 14 * 1024 ** 3)), \
                mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch.object(
                    mgr,
                    "list_drive_items",
                    return_value=([{"rel_path": "Movies/Inception (2010)", "kind": "movie", "size_bytes": item_size, "title": "Inception"}], {}),
                ):
            code, res = mgr.enqueue_restore({"items": [{"rel_path": "Movies/Inception (2010)"}]})
        self.assertEqual(code, 409)
        self.assertIn("Not enough free space", res["error"])

    def test_restore_md5_failure_preserves_remote(self):
        wa = FakeWatched(self.hdd)
        mgr = self._make_manager(watched_mod=wa)
        item_size = 2 * 1024 ** 3
        purge_called = []

        def fake_popen(cmd, **kwargs):
            if cmd[1] == "copy":
                return FakePopen(cmd, returncode=0)
            if cmd[1] == "check":
                return FakePopen(cmd, returncode=1, stderr_lines=["checksum mismatch\n"])
            raise AssertionError(cmd)

        def fake_run(args, timeout=30):
            if args[0] == "purge":
                purge_called.append(args)
            return _cp(0)

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(100 * 1024 ** 3, 50 * 1024 ** 3)), \
                mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch.object(
                    mgr,
                    "list_drive_items",
                    return_value=([{"rel_path": "Movies/Inception (2010)", "kind": "movie", "size_bytes": item_size, "title": "Inception"}], {}),
                ), \
                mock.patch("subprocess.Popen", side_effect=fake_popen), \
                mock.patch.object(mgr, "_run_rclone", side_effect=fake_run):
            code, res = mgr.enqueue_restore({"items": [{"rel_path": "Movies/Inception (2010)"}]})
            self.assertEqual(code, 202)
            done = self._wait_job(mgr, res["jobs"][0]["id"])

        self.assertEqual(done["status"], "failed")
        self.assertEqual(purge_called, [])

    def test_restore_happy_path_purges_remote_and_rescans(self):
        wa = FakeWatched(self.hdd)
        arr_commands = []
        orig_radarr = wa.radarr

        def track_radarr(method, path, data=None):
            if method == "POST" and path == "/command":
                arr_commands.append(data)
                return {"id": 1}
            return orig_radarr(method, path, data)

        wa.radarr = track_radarr
        mgr = self._make_manager(watched_mod=wa)
        item_size = 2 * 1024 ** 3
        purge_called = []

        def fake_popen(cmd, **kwargs):
            return FakePopen(cmd, returncode=0)

        def fake_run(args, timeout=30):
            if args[0] == "purge":
                purge_called.append(args)
            return _cp(0)

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch("os.statvfs", return_value=FakeStatVfs(100 * 1024 ** 3, 50 * 1024 ** 3)), \
                mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch.object(
                    mgr,
                    "list_drive_items",
                    return_value=([{
                        "rel_path": "Movies/Inception (2010)",
                        "kind": "movie",
                        "size_bytes": item_size,
                        "title": "Inception",
                        "radarr_id": 12,
                    }], {}),
                ), \
                mock.patch("subprocess.Popen", side_effect=fake_popen), \
                mock.patch.object(mgr, "_run_rclone", side_effect=fake_run), \
                mock.patch.object(mgr, "refresh_vfs", return_value=True):
            code, res = mgr.enqueue_restore({"items": [{"rel_path": "Movies/Inception (2010)"}]})
            self.assertEqual(code, 202)
            done = self._wait_job(mgr, res["jobs"][0]["id"])

        self.assertEqual(done["status"], "done")
        self.assertEqual(len(purge_called), 1)
        self.assertEqual(purge_called[0], ["purge", "gdrive:Media/Movies/Inception (2010)"])
        self.assertTrue(any(c.get("name") == "RefreshMovie" for c in arr_commands))
        self.assertTrue(any(c[0] == "refresh" for c in wa.calls))

    def test_cancel_active_job_terminates_rclone_and_keeps_files(self):
        mp, mkey, _ = self._make_file("Media/Movies/Arrival (2016)/Arrival.mkv", b"movie-content")
        wa = FakeWatched(self.hdd, movies=[{"id": 21, "title": "Arrival", "year": 2016}])
        wa.plans[21] = FakeMoviePlan(21, "Arrival (2016)", True, 121, mp, 12 * 1024 ** 3, mkey)
        mgr = self._make_manager(watched_mod=wa)

        started = threading.Event()
        block = threading.Event()
        procs = []

        def fake_popen(cmd, **kwargs):
            p = FakePopen(cmd, returncode=0, block_event=block)
            procs.append(p)
            started.set()
            return p

        with mock.patch("os.path.ismount", return_value=True), \
                mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch("subprocess.Popen", side_effect=fake_popen):
            code, res = mgr.enqueue_offload({"items": [{"kind": "movie", "radarr_id": 21}]})
            self.assertEqual(code, 202)
            job_id = res["jobs"][0]["id"]
            self.assertTrue(started.wait(timeout=3))

            ccode, cres = mgr.cancel_job(job_id)
            self.assertEqual(ccode, 200)
            self.assertTrue(cres["ok"])
            done = self._wait_job(mgr, job_id)

        self.assertEqual(done["status"], "cancelled")
        self.assertTrue(procs[0].terminated)
        # Local file MUST remain untouched
        self.assertTrue(os.path.exists(mp))
        self.assertFalse(any(c[0] == "delete_movie_file" for c in wa.calls))

    def test_delete_from_drive_rejects_traversal_and_root(self):
        mgr = self._make_manager(start_worker=False)
        with mock.patch.object(mgr, "is_configured", return_value=True), \
                mock.patch.object(mgr, "_run_rclone") as m_run:
            for bad in ("Movies", "Shows", "../etc", "Movies/../Shows/Foo", "/Movies/Foo"):
                code, res = mgr.delete_from_drive({"items": [{"rel_path": bad}]})
                self.assertEqual(code, 400)
            m_run.assert_not_called()


class GDriveHttpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tok_file = os.path.join(self.tmp.name, "local-token")
        with open(self.tok_file, "w") as f:
            f.write("test-gdrive-token\n")
        self.p_tok = mock.patch.object(pcapi, "LOCAL_TOKEN_FILE", self.tok_file)
        self.p_allow = mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", True)
        self.p_tok.start()
        self.p_allow.start()
        self.addCleanup(self.p_tok.stop)
        self.addCleanup(self.p_allow.stop)

        self.mgr = gdrive_offload.GDriveOffloadManager(
            rclone_bin="/usr/bin/rclone",
            mount_point=os.path.join(self.tmp.name, "mnt"),
            hdd_mnt=os.path.join(self.tmp.name, "hdd"),
            metadata_file=os.path.join(self.tmp.name, "meta.json"),
            start_worker=False,
        )
        self.addCleanup(self.mgr.shutdown)
        self.p_mgr = mock.patch.object(pcapi, "_gdrive_offload_manager", return_value=self.mgr)
        self.p_mgr.start()
        self.addCleanup(self.p_mgr.stop)

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        self.th = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.th.start()
        self.addCleanup(self.srv.shutdown)
        self.addCleanup(self.srv.server_close)

    def _req(self, method, path, body=None, auth=True):
        headers = {"X-PC-Local-Token": "test-gdrive-token"} if auth else {}
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

    def test_routes_auth_and_sse_snapshot(self):
        self.assertEqual(self._req("GET", "/pc/api/space/drive", auth=False)[0], 403)
        self.assertEqual(self._req("POST", "/pc/api/space/offload", {"items": ["movie:1"]}, auth=False)[0], 403)

        with mock.patch.object(self.mgr, "is_configured", return_value=False):
            code, data = self._req("GET", "/pc/api/space/drive")
            self.assertEqual(code, 200)
            self.assertFalse(data["configured"])
            self.assertEqual(data["items"], [])

        # Cancel unknown job -> 404
        code_c, err_c = self._req("POST", "/pc/api/space/jobs/doesnotexist/cancel", {})
        self.assertEqual(code_c, 404)
        self.assertIn("not found", err_c["error"])

        # SSE stream returns initial snapshot event
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/pc/api/space/jobs/stream",
            headers={"X-PC-Local-Token": "test-gdrive-token"},
        )
        with urllib.request.urlopen(req, timeout=5) as r:
            self.assertEqual(r.status, 200)
            self.assertIn("text/event-stream", r.headers.get("Content-Type", ""))
            ev_line = r.readline().decode().strip()
            data_line = r.readline().decode().strip()
            self.assertEqual(ev_line, "event: snapshot")
            self.assertTrue(data_line.startswith("data: "))
            payload = json.loads(data_line[6:])
            self.assertIn("jobs", payload)


if __name__ == "__main__":
    unittest.main()
