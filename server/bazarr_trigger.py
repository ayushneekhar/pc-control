"""On-demand Bazarr subtitle runner triggered by Radarr/Sonarr Download webhooks.

Starts `bazarr.service` on demand when a movie or episode finishes importing,
waits for Bazarr's HTTP API to come up, runs the relevant sync + wanted subtitle
search tasks, waits through a short grace window to coalesce rapid/multi-episode
imports, and stops `bazarr.service` afterwards — unless Bazarr was already
running before the trigger (e.g. started manually).
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_UNIT = "bazarr.service"
DEFAULT_BASE_URL = "http://127.0.0.1:6767"
DEFAULT_CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".config", "bazarr", "config", "config.yaml")

SOURCE_TASKS = {
    "radarr": ("update_movies", "wanted_search_missing_subtitles_movies"),
    "sonarr": ("update_series", "wanted_search_missing_subtitles_series"),
}


def read_api_key(config_path=DEFAULT_CONFIG_PATH):
    """Read auth.apikey from Bazarr's config.yaml. Returns '' if missing/unreadable."""
    if not config_path or not os.path.isfile(config_path):
        return ""
    try:
        with open(config_path, "r", encoding="utf-8") as f:
            raw = f.read()
    except OSError:
        return ""
    try:
        import yaml
        data = yaml.safe_load(raw) or {}
        if isinstance(data, dict):
            auth = data.get("auth") or {}
            if isinstance(auth, dict) and auth.get("apikey"):
                return str(auth["apikey"]).strip()
    except Exception:
        pass
    in_auth = False
    for line in raw.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line[0].isspace():
            in_auth = line.strip() == "auth:"
            continue
        if in_auth:
            m = re.match(r"^\s+apikey\s*:\s*['\"]?([^'\"\s#]+)['\"]?", line)
            if m:
                return m.group(1).strip()
    return ""


def _default_request(method, url, api_key, body=None, timeout=10.0):
    headers = {"X-API-KEY": api_key}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            if not raw:
                return resp.status, None
            try:
                return resp.status, json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                return resp.status, None
    except urllib.error.HTTPError as e:
        try:
            e.read()
        except Exception:
            pass
        return e.code, None
    except (urllib.error.URLError, OSError, TimeoutError):
        return 0, None


def _any_task_running(tasks_payload, task_ids):
    items = tasks_payload.get("data") if isinstance(tasks_payload, dict) else None
    if not isinstance(items, list):
        return False
    wanted = set(task_ids)
    for item in items:
        if isinstance(item, dict) and item.get("job_id") in wanted and item.get("job_running"):
            return True
    return False


class BazarrTrigger:
    """Thread-safe, coalescing on-demand runner for Bazarr subtitle tasks."""

    def __init__(
        self,
        unit=DEFAULT_UNIT,
        base_url=DEFAULT_BASE_URL,
        config_path=DEFAULT_CONFIG_PATH,
        ready_timeout=30.0,
        ready_poll_interval=1.0,
        poll_interval=5.0,
        grace_seconds=20.0,
        max_run_seconds=300.0,
        http_timeout=10.0,
        systemctl_timeout=15.0,
        run_cmd=None,
        request_fn=None,
        clock=None,
        log_fn=None,
    ):
        self.unit = unit
        self.base_url = base_url.rstrip("/")
        self.config_path = config_path
        self.ready_timeout = float(ready_timeout)
        self.ready_poll_interval = float(ready_poll_interval)
        self.poll_interval = float(poll_interval)
        self.grace_seconds = float(grace_seconds)
        self.max_run_seconds = float(max_run_seconds)
        self.http_timeout = float(http_timeout)
        self.systemctl_timeout = float(systemctl_timeout)
        self._run_cmd = run_cmd or subprocess.run
        self._request = request_fn or _default_request
        self._clock = clock or time.monotonic
        self._log = log_fn or (lambda msg: print(f"bazarr-trigger: {msg}", file=sys.stderr, flush=True))

        self._cond = threading.Condition()
        self._stop_event = threading.Event()
        self._pending_sources = set()
        self._running = False
        self._stop_requested = False
        self._worker = None

    def trigger(self, source):
        """Queue an on-demand Bazarr run for 'radarr' or 'sonarr'. Non-blocking."""
        src = str(source or "").strip().lower()
        if src not in SOURCE_TASKS:
            return False
        with self._cond:
            if self._stop_requested:
                return False
            self._pending_sources.add(src)
            if not self._running:
                self._running = True
                self._worker = threading.Thread(
                    target=self._worker_entry,
                    daemon=True,
                    name="bazarr-trigger",
                )
                self._worker.start()
            self._cond.notify_all()
        return True

    def stop(self, timeout=5.0):
        """Request any active run to exit promptly (mainly for tests/shutdown)."""
        self._stop_event.set()
        with self._cond:
            self._stop_requested = True
            self._cond.notify_all()
            worker = self._worker
        if worker and worker.is_alive():
            worker.join(timeout=timeout)

    def wait_idle(self, timeout=10.0):
        """Block until no run is active. Returns True if idle, False on timeout."""
        end = self._clock() + timeout
        with self._cond:
            while self._running:
                rem = end - self._clock()
                if rem <= 0:
                    return False
                self._cond.wait(timeout=rem)
            return True

    def _systemctl(self, *args):
        cmd = ["systemctl", "--user", *args, self.unit]
        return self._run_cmd(cmd, capture_output=True, text=True, timeout=self.systemctl_timeout)

    def _is_active(self):
        try:
            p = self._systemctl("is-active")
            return p.returncode == 0 and (p.stdout or "").strip() == "active"
        except Exception as e:
            self._log(f"is-active check failed: {type(e).__name__}: {e}")
            return False

    def _start_service(self):
        try:
            p = self._systemctl("start")
            if p.returncode != 0:
                err = (p.stderr or p.stdout or "").strip()
                self._log(f"failed to start {self.unit}: {err or f'exit {p.returncode}'}")
                return False
            return True
        except Exception as e:
            self._log(f"failed to start {self.unit}: {type(e).__name__}: {e}")
            return False

    def _stop_service(self):
        try:
            p = self._systemctl("stop")
            if p.returncode != 0:
                err = (p.stderr or p.stdout or "").strip()
                self._log(f"failed to stop {self.unit}: {err or f'exit {p.returncode}'}")
        except Exception as e:
            self._log(f"failed to stop {self.unit}: {type(e).__name__}: {e}")

    def _sleep(self, seconds):
        if seconds <= 0:
            return
        self._stop_event.wait(timeout=seconds)

    def _wait_ready(self, api_key, deadline):
        ready_end = min(self._clock() + self.ready_timeout, deadline)
        url = f"{self.base_url}/api/system/status"
        while not self._stop_requested:
            code, _ = self._request("GET", url, api_key, None, self.http_timeout)
            if code == 200:
                return True
            rem = ready_end - self._clock()
            if rem <= 0:
                return False
            self._sleep(min(self.ready_poll_interval, rem))
        return False

    def _trigger_task(self, api_key, task_id):
        q = urllib.parse.urlencode({"taskid": task_id})
        url = f"{self.base_url}/api/system/tasks?{q}"
        code, _ = self._request("POST", url, api_key, {"taskid": task_id}, self.http_timeout)
        if code not in (200, 202, 204):
            self._log(f"task {task_id} trigger returned HTTP {code}")
            return False
        return True

    def _wait_tasks_done(self, api_key, task_ids, deadline):
        if not task_ids:
            return
        url = f"{self.base_url}/api/system/tasks"
        consecutive_errors = 0
        while not self._stop_requested:
            rem = deadline - self._clock()
            if rem <= 0:
                self._log(f"hit max run cap ({self.max_run_seconds}s) waiting for {task_ids}")
                return
            self._sleep(min(self.poll_interval, rem))
            if self._stop_requested or self._clock() >= deadline:
                return
            code, payload = self._request("GET", url, api_key, None, self.http_timeout)
            if code != 200 or not isinstance(payload, dict):
                consecutive_errors += 1
                self._log(f"tasks poll failed (HTTP {code}, attempt {consecutive_errors})")
                if consecutive_errors >= 3:
                    return
                continue
            consecutive_errors = 0
            if not _any_task_running(payload, task_ids):
                return

    def _run_once(self):
        """Execute one full start -> sync/search -> grace -> stop cycle.
        Returns True if the cycle ran normally, False on fatal setup error.
        """
        deadline = self._clock() + self.max_run_seconds
        already_active = self._is_active()
        started_by_us = False
        if not already_active:
            if not self._start_service():
                return False
            started_by_us = True

        try:
            api_key = read_api_key(self.config_path)
            if not api_key:
                self._log(f"no Bazarr API key found in {self.config_path}")
                return False

            if not self._wait_ready(api_key, deadline):
                self._log(f"Bazarr did not become ready within {self.ready_timeout}s")
                return False

            while not self._stop_requested and self._clock() < deadline:
                with self._cond:
                    sources = sorted(self._pending_sources)
                    self._pending_sources.clear()
                if not sources:
                    break

                # Phase 1: sync Radarr/Sonarr so Bazarr knows about newly imported media
                sync_tasks = [SOURCE_TASKS[s][0] for s in sources]
                triggered_sync = [t for t in sync_tasks if self._trigger_task(api_key, t)]
                self._wait_tasks_done(api_key, triggered_sync, deadline)
                if self._stop_requested or self._clock() >= deadline:
                    break

                # Phase 2: search missing subtitles for those sources
                search_tasks = [SOURCE_TASKS[s][1] for s in sources]
                triggered_search = [t for t in search_tasks if self._trigger_task(api_key, t)]
                self._wait_tasks_done(api_key, triggered_search, deadline)
                if self._stop_requested or self._clock() >= deadline:
                    break

                # Grace window: wait briefly in case another episode imports right after
                grace_end = min(self._clock() + self.grace_seconds, deadline)
                with self._cond:
                    while not self._pending_sources and not self._stop_requested:
                        rem = grace_end - self._clock()
                        if rem <= 0:
                            break
                        self._cond.wait(timeout=rem)
                    if self._pending_sources and not self._stop_requested and self._clock() < deadline:
                        continue
                    break
            return True
        finally:
            if started_by_us:
                self._stop_service()

    def _worker_entry(self):
        try:
            while True:
                ok = self._run_once()
                with self._cond:
                    if not ok or self._stop_requested or not self._pending_sources:
                        if not ok:
                            self._pending_sources.clear()
                        self._running = False
                        self._cond.notify_all()
                        return
        except Exception as e:
            self._log(f"unexpected error: {type(e).__name__}: {e}")
            with self._cond:
                self._pending_sources.clear()
                self._running = False
                self._cond.notify_all()
