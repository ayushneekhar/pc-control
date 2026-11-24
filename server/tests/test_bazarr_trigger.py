"""Unit tests for server/bazarr_trigger.py and its pcapi.py webhook integration.

All systemd and HTTP calls are fully mocked — these tests never touch real
systemctl or a real Bazarr instance.
"""
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

SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

import bazarr_trigger as bt  # noqa: E402
import pcapi  # noqa: E402


def _cp(rc=0, stdout="", stderr=""):
    return subprocess.CompletedProcess(["systemctl"], rc, stdout=stdout, stderr=stderr)


class ReadApiKeyTests(unittest.TestCase):
    def test_reads_apikey_from_yaml(self):
        with tempfile.TemporaryDirectory() as d:
            cfg = os.path.join(d, "config.yaml")
            with open(cfg, "w", encoding="utf-8") as f:
                f.write("general:\n  port: 6767\nauth:\n  apikey: abc123secret\n  type: null\n")
            self.assertEqual(bt.read_api_key(cfg), "abc123secret")

    def test_missing_file_or_key_returns_empty(self):
        self.assertEqual(bt.read_api_key("/nonexistent/path/config.yaml"), "")
        with tempfile.TemporaryDirectory() as d:
            cfg = os.path.join(d, "config.yaml")
            with open(cfg, "w", encoding="utf-8") as f:
                f.write("general:\n  port: 6767\n")
            self.assertEqual(bt.read_api_key(cfg), "")

    def test_fallback_parser_handles_invalid_yaml_tag(self):
        with tempfile.TemporaryDirectory() as d:
            cfg = os.path.join(d, "config.yaml")
            with open(cfg, "w", encoding="utf-8") as f:
                f.write("weird: !unknown_tag foo\nauth:\n  apikey: 'fallback_key_99'\n")
            self.assertEqual(bt.read_api_key(cfg), "fallback_key_99")


class BazarrTriggerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg = os.path.join(self.tmp.name, "config.yaml")
        with open(self.cfg, "w", encoding="utf-8") as f:
            f.write("auth:\n  apikey: test-bazarr-key\n")
        self.logs = []

    def tearDown(self):
        self.tmp.cleanup()

    def _make_trigger(self, run_cmd, request_fn, **kwargs):
        defaults = dict(
            config_path=self.cfg,
            ready_timeout=1.0,
            ready_poll_interval=0.01,
            poll_interval=0.01,
            grace_seconds=0.04,
            max_run_seconds=2.0,
            run_cmd=run_cmd,
            request_fn=request_fn,
            log_fn=self.logs.append,
        )
        defaults.update(kwargs)
        return bt.BazarrTrigger(**defaults)

    def test_unknown_source_ignored(self):
        cmds = []
        reqs = []
        t = self._make_trigger(lambda *a, **k: cmds.append(a) or _cp(), lambda *a: reqs.append(a) or (200, {}))
        self.assertFalse(t.trigger("seerr"))
        self.assertFalse(t.trigger(""))
        self.assertFalse(t.trigger(None))
        self.assertEqual(cmds, [])
        self.assertEqual(reqs, [])

    def test_radarr_starts_waits_runs_sync_then_search_and_stops(self):
        cmds = []
        calls = []
        status_attempts = {"n": 0}
        task_polls = {"update_movies": 0, "wanted_search_missing_subtitles_movies": 0}
        active_task = {"id": None}

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            if "is-active" in cmd:
                return _cp(3, stdout="inactive\n")
            return _cp(0)

        def req_fn(method, url, api_key, body, timeout):
            self.assertEqual(api_key, "test-bazarr-key")
            calls.append((method, url, body))
            if url.endswith("/api/system/status"):
                status_attempts["n"] += 1
                if status_attempts["n"] < 3:
                    return 0, None
                return 200, {"data": {"bazarr_version": "1.5"}}
            if method == "POST" and "/api/system/tasks" in url:
                tid = body["taskid"]
                active_task["id"] = tid
                return 204, None
            if method == "GET" and url.endswith("/api/system/tasks"):
                tid = active_task["id"]
                task_polls[tid] += 1
                running = task_polls[tid] < 2
                return 200, {"data": [{"job_id": tid, "job_running": running}]}
            return 404, None

        t = self._make_trigger(run_cmd, req_fn)
        self.assertTrue(t.trigger("radarr"))
        self.assertTrue(t.wait_idle(timeout=5.0))

        self.assertEqual(
            cmds,
            [
                ["systemctl", "--user", "is-active", "bazarr.service"],
                ["systemctl", "--user", "start", "bazarr.service"],
                ["systemctl", "--user", "stop", "bazarr.service"],
            ],
        )
        post_tasks = [b["taskid"] for m, _, b in calls if m == "POST"]
        self.assertEqual(post_tasks, ["update_movies", "wanted_search_missing_subtitles_movies"])
        self.assertEqual(status_attempts["n"], 3)
        self.assertEqual(task_polls["update_movies"], 2)
        self.assertEqual(task_polls["wanted_search_missing_subtitles_movies"], 2)

    def test_sonarr_runs_series_tasks_and_stops(self):
        cmds = []
        post_tasks = []

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            return _cp(3, stdout="inactive\n") if "is-active" in cmd else _cp(0)

        def req_fn(method, url, api_key, body, timeout):
            if url.endswith("/api/system/status"):
                return 200, {"data": {}}
            if method == "POST":
                post_tasks.append(body["taskid"])
                return 204, None
            return 200, {"data": []}

        t = self._make_trigger(run_cmd, req_fn)
        self.assertTrue(t.trigger("sonarr"))
        self.assertTrue(t.wait_idle(timeout=5.0))

        self.assertEqual(post_tasks, ["update_series", "wanted_search_missing_subtitles_series"])
        self.assertEqual([c[2] for c in cmds], ["is-active", "start", "stop"])

    def test_leaves_bazarr_running_if_already_active(self):
        cmds = []
        post_tasks = []

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            if "is-active" in cmd:
                return _cp(0, stdout="active\n")
            return _cp(0)

        def req_fn(method, url, api_key, body, timeout):
            if url.endswith("/api/system/status"):
                return 200, {"data": {}}
            if method == "POST":
                post_tasks.append(body["taskid"])
                return 204, None
            return 200, {"data": []}

        t = self._make_trigger(run_cmd, req_fn)
        self.assertTrue(t.trigger("radarr"))
        self.assertTrue(t.wait_idle(timeout=5.0))

        # Only checked is-active; never started and never stopped!
        self.assertEqual(cmds, [["systemctl", "--user", "is-active", "bazarr.service"]])
        self.assertEqual(post_tasks, ["update_movies", "wanted_search_missing_subtitles_movies"])

    def test_coalesces_concurrent_triggers_and_grace_window_retrigger(self):
        cmds = []
        post_tasks = []
        in_first_search = threading.Event()
        allow_first_search_done = threading.Event()
        second_batch_started = threading.Event()

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            return _cp(3, stdout="inactive\n") if "is-active" in cmd else _cp(0)

        def req_fn(method, url, api_key, body, timeout):
            if url.endswith("/api/system/status"):
                return 200, {"data": {}}
            if method == "POST":
                tid = body["taskid"]
                post_tasks.append(tid)
                if tid == "wanted_search_missing_subtitles_movies":
                    in_first_search.set()
                if tid == "update_series":
                    second_batch_started.set()
                return 204, None
            if method == "GET" and url.endswith("/api/system/tasks"):
                if in_first_search.is_set() and not allow_first_search_done.is_set():
                    return 200, {"data": [{"job_id": "wanted_search_missing_subtitles_movies", "job_running": True}]}
                return 200, {"data": []}
            return 404, None

        t = self._make_trigger(run_cmd, req_fn, grace_seconds=0.3)
        # First trigger: radarr
        t.trigger("radarr")
        self.assertTrue(in_first_search.wait(timeout=3.0))
        # Release search so worker enters grace window, then fire multiple sonarr triggers
        allow_first_search_done.set()
        time.sleep(0.03)
        t.trigger("sonarr")
        t.trigger("sonarr")
        t.trigger("sonarr")
        self.assertTrue(second_batch_started.wait(timeout=3.0))
        self.assertTrue(t.wait_idle(timeout=5.0))

        # Started once, stopped once — both radarr and sonarr handled in one Bazarr lifecycle
        self.assertEqual([c[2] for c in cmds], ["is-active", "start", "stop"])
        self.assertEqual(
            post_tasks,
            [
                "update_movies",
                "wanted_search_missing_subtitles_movies",
                "update_series",
                "wanted_search_missing_subtitles_series",
            ],
        )

    def test_hard_cap_stops_bazarr_when_task_never_finishes(self):
        cmds = []

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            return _cp(3, stdout="inactive\n") if "is-active" in cmd else _cp(0)

        def req_fn(method, url, api_key, body, timeout):
            if url.endswith("/api/system/status"):
                return 200, {"data": {}}
            if method == "POST":
                return 204, None
            # Pretend update_movies stays running forever
            return 200, {"data": [{"job_id": "update_movies", "job_running": True}]}

        t = self._make_trigger(run_cmd, req_fn, max_run_seconds=0.08, poll_interval=0.01)
        t.trigger("radarr")
        self.assertTrue(t.wait_idle(timeout=3.0))
        self.assertEqual([c[2] for c in cmds], ["is-active", "start", "stop"])

    def test_ready_timeout_stops_started_service(self):
        cmds = []
        post_tasks = []

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            return _cp(3, stdout="inactive\n") if "is-active" in cmd else _cp(0)

        def req_fn(method, url, api_key, body, timeout):
            if method == "POST":
                post_tasks.append(body)
            return 0, None

        t = self._make_trigger(run_cmd, req_fn, ready_timeout=0.05, ready_poll_interval=0.01)
        t.trigger("radarr")
        self.assertTrue(t.wait_idle(timeout=3.0))
        self.assertEqual([c[2] for c in cmds], ["is-active", "start", "stop"])
        self.assertEqual(post_tasks, [])

    def test_start_failure_does_not_call_stop(self):
        cmds = []

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            if "is-active" in cmd:
                return _cp(3, stdout="inactive\n")
            return _cp(1, stderr="unit failed")

        t = self._make_trigger(run_cmd, lambda *a: (200, {}))
        t.trigger("radarr")
        self.assertTrue(t.wait_idle(timeout=3.0))
        self.assertEqual([c[2] for c in cmds], ["is-active", "start"])

    def test_missing_api_key_stops_started_service(self):
        cmds = []

        def run_cmd(cmd, **kw):
            cmds.append(cmd)
            return _cp(3, stdout="inactive\n") if "is-active" in cmd else _cp(0)

        t = self._make_trigger(run_cmd, lambda *a: (200, {}), config_path="/nonexistent/config.yaml")
        t.trigger("radarr")
        self.assertTrue(t.wait_idle(timeout=3.0))
        self.assertEqual([c[2] for c in cmds], ["is-active", "start", "stop"])


class FakePush:
    def __init__(self):
        self.queued = []

    def configured(self):
        return True

    def status(self):
        return {"configured": True, "registered": 1, "devices": []}

    def notify_async(self, data, title, body, link=None, image=None):
        self.queued.append((data, title, body))

    def send(self, *a, **k):
        return True, 1, 0, []


class FakeBazarr:
    def __init__(self):
        self.triggered = []

    def trigger(self, source):
        self.triggered.append(source)
        return True


class PcapiWebhookBazarrTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.saved = (
            pcapi.PUSH,
            pcapi.NOTIFY,
            pcapi.BAZARR,
            pcapi.HOOK_SECRET_FILE,
            pcapi.ALLOWED_LOGINS,
        )
        pcapi.PUSH = FakePush()
        pcapi.NOTIFY = pcapi.Notify(pcapi.PUSH, os.path.join(cls.tmp.name, "notify.json"))
        pcapi.NOTIFY.update({"categories": {"grabbed": {"telegram": False}, "available": {"telegram": False}}})
        pcapi.BAZARR = FakeBazarr()
        pcapi.HOOK_SECRET_FILE = os.path.join(cls.tmp.name, "hook-secret")
        with open(pcapi.HOOK_SECRET_FILE, "w", encoding="utf-8") as f:
            f.write("s" * 40)
        pcapi.ALLOWED_LOGINS = {"me@example.com"}
        cls.api = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=cls.api.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.api.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.api.shutdown()
        cls.api.server_close()
        (
            pcapi.PUSH,
            pcapi.NOTIFY,
            pcapi.BAZARR,
            pcapi.HOOK_SECRET_FILE,
            pcapi.ALLOWED_LOGINS,
        ) = cls.saved
        cls.tmp.cleanup()

    def setUp(self):
        pcapi.PUSH.queued.clear()
        pcapi.BAZARR.triggered.clear()

    def _post_hook(self, source, payload, auth=True):
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + "s" * 40
        req = urllib.request.Request(
            f"{self.url}/pc/api/hook/{source}",
            method="POST",
            headers=headers,
            data=json.dumps(payload).encode("utf-8"),
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def test_download_webhook_triggers_bazarr_without_push_notification(self):
        code, body = self._post_hook("radarr", {"eventType": "Download", "movie": {"title": "Dune"}})
        self.assertEqual(code, 200)
        self.assertEqual(body, {"ok": True, "bazarr": True})
        self.assertEqual(pcapi.BAZARR.triggered, ["radarr"])
        self.assertEqual(pcapi.PUSH.queued, [])

        code, body = self._post_hook("sonarr", {"eventType": "Download", "series": {"title": "Severance"}})
        self.assertEqual(code, 200)
        self.assertEqual(body, {"ok": True, "bazarr": True})
        self.assertEqual(pcapi.BAZARR.triggered, ["radarr", "sonarr"])
        self.assertEqual(pcapi.PUSH.queued, [])

    def test_unauthorized_download_webhook_does_not_trigger_bazarr(self):
        code, _ = self._post_hook("radarr", {"eventType": "Download"}, auth=False)
        self.assertEqual(code, 401)
        self.assertEqual(pcapi.BAZARR.triggered, [])

    def test_non_download_and_seerr_hooks_do_not_trigger_bazarr(self):
        code, _ = self._post_hook("radarr", {"eventType": "Test"})
        self.assertEqual(code, 200)
        code, _ = self._post_hook("seerr", {"notification_type": "MEDIA_AVAILABLE", "subject": "Dune"})
        self.assertEqual(code, 200)
        self.assertEqual(pcapi.BAZARR.triggered, [])

    def test_disabled_bazarr_service_does_not_trigger_service_down_alerts(self):
        alerts = []

        class _Notify:
            def send(self, *args):
                alerts.append(args)

        watcher = pcapi.ServiceWatcher(_Notify(), list_fn=lambda: [], interval=999)
        inactive = {"unit": "bazarr.service", "active": "inactive", "enabled": "disabled", "type": "simple", "timer": False}
        active = {"unit": "bazarr.service", "active": "active", "enabled": "disabled", "type": "simple", "timer": False}
        self.assertFalse(pcapi.watched_service(inactive))
        self.assertFalse(pcapi.watched_service(active))
        watcher.check([inactive])
        watcher.check([active])
        watcher.check([inactive])
        watcher.check([inactive])
        watcher.check([inactive])
        self.assertEqual(alerts, [])


if __name__ == "__main__":
    unittest.main()
