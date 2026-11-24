"""Google Drive media offloading backend for Space Manager.

Supports:
- Discovering offloaded media on `gdrive:Media/{Movies,Shows}` with local metadata cache
- Serialized background transfer queue (1 active rclone job at a time):
  - Offload: rclone copy -> mandatory rclone check (MD5) -> unmonitor + safe HDD/torrent cleanup -> VFS & Jellyfin refresh
  - Restore: HDD free-space check (> size + 5 GiB) -> rclone copy -> mandatory rclone check (MD5) -> rclone purge -> rescan
- Direct Drive deletion (`rclone purge`) with strict relative path validation
- Safe job cancellation (terminates rclone subprocess, never deletes local or remote files)
- Live SSE pub/sub stream for real-time transfer progress
"""
import collections
import json
import os
import posixpath
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import space as space_mod

HOME = os.path.expanduser("~")
DEFAULT_REMOTE = os.environ.get("PCAPI_GDRIVE_REMOTE", "gdrive").rstrip(":")
DEFAULT_REMOTE_SUBPATH = os.environ.get("PCAPI_GDRIVE_MEDIA_DIR", "Media").strip("/")
DEFAULT_MOUNT_POINT = os.environ.get(
    "PCAPI_GDRIVE_MOUNT",
    os.path.join(HOME, "remote-pc", "mounts", "gdrive-media"),
)
DEFAULT_HDD_MNT = os.environ.get("PCAPI_HDD_MNT", "/run/media/ayushlive/New Volume")
DEFAULT_RC_URL = os.environ.get("PCAPI_RCLONE_RC_URL", "http://127.0.0.1:5572").rstrip("/")
DEFAULT_METADATA_FILE = os.environ.get(
    "PCAPI_GDRIVE_METADATA_FILE",
    os.path.join(HOME, ".local", "share", "pc-control", "gdrive-offload.json"),
)

QUOTA_TTL = 60.0
ITEMS_TTL = 30.0
RESTORE_FREE_MARGIN_BYTES = 5 * 1024 ** 3
MAX_BATCH_ITEMS = 50
MAX_JOB_HISTORY = 30
ACTIVE_STATUSES = {"queued", "copying", "verifying", "cleaning"}
TERMINAL_STATUSES = {"done", "failed", "cancelled"}
VIDEO_EXTS = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm", ".wmv", ".mpg", ".mpeg"}


def _clip(text, limit=240):
    s = str(text or "").strip()
    return s if len(s) <= limit else s[: limit - 1] + "…"


def find_rclone_bin():
    """Return ~/.local/bin/rclone if executable, else /usr/bin/rclone, else PATH lookup."""
    env_bin = os.environ.get("PCAPI_RCLONE_BIN")
    if env_bin and os.access(env_bin, os.X_OK):
        return env_bin
    local_bin = os.path.join(HOME, ".local", "bin", "rclone")
    if os.access(local_bin, os.X_OK):
        return local_bin
    if os.access("/usr/bin/rclone", os.X_OK):
        return "/usr/bin/rclone"
    return shutil.which("rclone") or local_bin


def validate_local_media_path(path, hdd_mnt=DEFAULT_HDD_MNT):
    """Validate that `path` is strictly inside `<hdd_mnt>/Media/{Movies,Shows}` after realpath.

    Returns (real_path, rel_path_under_Media). Raises ValueError if invalid or HDD not mounted.
    """
    if not path or not isinstance(path, (str, os.PathLike)):
        raise ValueError("media path is required")
    mnt = str(hdd_mnt)
    if not os.path.ismount(mnt):
        raise ValueError("HDD is not mounted")

    media_root = os.path.realpath(os.path.join(mnt, "Media"))
    movies_root = os.path.realpath(os.path.join(media_root, "Movies"))
    shows_root = os.path.realpath(os.path.join(media_root, "Shows"))
    real = os.path.realpath(str(path))

    in_movies = real.startswith(movies_root + os.sep)
    in_shows = real.startswith(shows_root + os.sep)
    if not (in_movies or in_shows):
        raise ValueError("path must be strictly inside Media/Movies or Media/Shows on the HDD")

    rel = os.path.relpath(real, media_root).replace(os.sep, "/")
    rel = validate_remote_rel_path(rel)
    return real, rel


def validate_remote_rel_path(rel_path):
    """Validate a relative path under remote `Media/` (strictly inside Movies/ or Shows/).

    Rejects empty paths, leading/trailing slashes, backslashes, null bytes, `.` or `..` segments,
    and root `Movies` or `Shows` directories themselves.
    """
    if not isinstance(rel_path, str):
        raise ValueError("rel_path must be a string")
    raw = rel_path.strip()
    if not raw or "\x00" in raw or "\\" in raw:
        raise ValueError("invalid rel_path")
    if raw.startswith("/") or raw.endswith("/"):
        raise ValueError("rel_path must be relative without leading or trailing slash")
    parts = raw.split("/")
    if any(p in ("", ".", "..") for p in parts):
        raise ValueError("rel_path contains invalid path traversal segment")
    if parts[0] not in ("Movies", "Shows"):
        raise ValueError("rel_path must start with Movies/ or Shows/")
    if len(parts) < 2:
        raise ValueError("cannot target root Movies or Shows directory")
    norm = posixpath.join(*parts)
    if norm != raw:
        raise ValueError("rel_path is not normalized")
    return norm


def _parse_folder_title_year(folder_name):
    m = re.match(r"^(.*?)\s*\((\d{4})\)\s*$", folder_name.strip())
    if m:
        return m.group(1).strip(), int(m.group(2))
    return folder_name.strip(), None


def _parse_season_number(season_folder):
    low = season_folder.strip().lower()
    if low == "specials":
        return 0
    m = re.search(r"season\s*(\d+)", low)
    if m:
        return int(m.group(1))
    m = re.fullmatch(r"s(\d+)", low)
    if m:
        return int(m.group(1))
    return None


def _parse_rclone_stats_line(line):
    """Parse one `--use-json-log` line from rclone and extract progress stats if present."""
    line = (line or "").strip()
    if not line or not line.startswith("{"):
        return None
    try:
        obj = json.loads(line)
    except (ValueError, TypeError):
        return None
    stats = obj.get("stats")
    if not isinstance(stats, dict):
        return None
    bytes_done = int(stats.get("bytes") or 0)
    bytes_total = int(stats.get("totalBytes") or 0)
    speed_bps = float(stats.get("speed") or 0.0)
    eta = stats.get("eta")
    eta_s = int(round(eta)) if isinstance(eta, (int, float)) and eta >= 0 else None
    pct = 0.0
    if bytes_total > 0:
        pct = min(100.0, max(0.0, round((bytes_done / bytes_total) * 100.0, 1)))
    return {
        "bytes_done": bytes_done,
        "bytes_total": bytes_total,
        "pct": pct,
        "speed_bps": int(round(speed_bps)),
        "eta_s": eta_s,
    }


def _default_notify(category, title, body):
    """Best-effort notification via localagent pcnotify module when running standalone."""
    try:
        localagent_dir = space_mod.LOCALAGENT_DIR
        if localagent_dir and localagent_dir not in os.sys.path:
            os.sys.path.insert(0, localagent_dir)
        import pcnotify  # type: ignore

        pcnotify.notify(category, title, body)
    except Exception:
        pass


class GDriveOffloadManager:
    def __init__(
        self,
        rclone_bin=None,
        remote=DEFAULT_REMOTE,
        remote_subpath=DEFAULT_REMOTE_SUBPATH,
        mount_point=DEFAULT_MOUNT_POINT,
        hdd_mnt=DEFAULT_HDD_MNT,
        rc_url=DEFAULT_RC_URL,
        metadata_file=DEFAULT_METADATA_FILE,
        notify_fn=None,
        watched_loader=None,
        agent_loader=None,
        start_worker=True,
    ):
        self.rclone_bin = rclone_bin or find_rclone_bin()
        self.remote = remote.rstrip(":")
        self.remote_subpath = remote_subpath.strip("/")
        self.mount_point = str(mount_point)
        self.hdd_mnt = str(hdd_mnt)
        self.rc_url = str(rc_url).rstrip("/")
        self.metadata_file = str(metadata_file)
        self._notify_fn = notify_fn or _default_notify
        self._watched_loader = watched_loader
        self._agent_loader = agent_loader

        self._lock = threading.RLock()
        self._cond = threading.Condition(self._lock)
        self._queue = collections.deque()  # deque of job_ids
        self._jobs = collections.OrderedDict()  # job_id -> job dict
        self._active_job_id = None
        self._active_proc = None
        self._cancelled_ids = set()
        self._subscribers = set()
        self._stop = False

        self._quota_cache = None
        self._quota_ts = 0.0
        self._items_cache = None
        self._items_ts = 0.0
        self._configured_cache = None
        self._configured_ts = 0.0

        self._batch_done = 0
        self._batch_failed = 0
        self._batch_ops = set()

        self._worker = None
        if start_worker:
            self._worker = threading.Thread(target=self._worker_loop, name="gdrive-offload-worker", daemon=True)
            self._worker.start()

    def _remote_root(self):
        return f"{self.remote}:{self.remote_subpath}"

    def _remote_path(self, rel_path):
        rel = validate_remote_rel_path(rel_path)
        return f"{self._remote_root()}/{rel}"

    def _load_localagent(self):
        if self._watched_loader is not None or self._agent_loader is not None:
            w = self._watched_loader() if callable(self._watched_loader) else self._watched_loader
            a = self._agent_loader() if callable(self._agent_loader) else self._agent_loader
            return w, a
        return space_mod._load_localagent()

    def _notify(self, title, body, category="system"):
        try:
            self._notify_fn(category, title, body)
        except Exception:
            pass

    # ---- metadata cache ----

    def _read_metadata(self):
        try:
            with open(self.metadata_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, dict) else {}
        except FileNotFoundError:
            return {}
        except Exception:
            return {}

    def _write_metadata(self, data):
        parent = os.path.dirname(self.metadata_file)
        if parent:
            os.makedirs(parent, exist_ok=True)
        tmp = f"{self.metadata_file}.{os.getpid()}.{threading.get_ident()}.tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, sort_keys=True)
        os.replace(tmp, self.metadata_file)

    def _save_item_metadata(self, rel_path, entry):
        with self._lock:
            meta = self._read_metadata()
            meta[rel_path] = entry
            try:
                self._write_metadata(meta)
            except OSError:
                pass
            self._items_cache = None
            self._quota_cache = None

    def _delete_item_metadata(self, rel_path):
        with self._lock:
            meta = self._read_metadata()
            if rel_path in meta:
                del meta[rel_path]
                try:
                    self._write_metadata(meta)
                except OSError:
                    pass
            self._items_cache = None
            self._quota_cache = None

    # ---- rclone helpers ----

    def _run_rclone(self, args, timeout=30):
        cmd = [self.rclone_bin] + list(args)
        return subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            check=False,
        )

    def is_configured(self, force_refresh=False):
        now = time.monotonic()
        with self._lock:
            if not force_refresh and self._configured_cache is not None and (now - self._configured_ts) < 30.0:
                return self._configured_cache
        try:
            res = self._run_rclone(["listremotes"], timeout=10)
            configured = res.returncode == 0 and any(
                line.strip() == f"{self.remote}:" for line in res.stdout.splitlines()
            )
        except Exception:
            configured = False
        with self._lock:
            self._configured_cache = configured
            self._configured_ts = now
        return configured

    def is_mounted(self):
        return bool(os.path.ismount(self.mount_point))

    def get_quota(self, force_refresh=False):
        now = time.monotonic()
        with self._lock:
            if not force_refresh and self._quota_cache is not None and (now - self._quota_ts) < QUOTA_TTL:
                return dict(self._quota_cache), None
        try:
            res = self._run_rclone(["about", f"{self.remote}:", "--json"], timeout=20)
            if res.returncode != 0:
                return None, _clip(res.stderr or f"rclone about exited {res.returncode}")
            raw = json.loads(res.stdout or "{}")
            quota = {
                "total_bytes": int(raw["total"]) if raw.get("total") is not None else None,
                "used_bytes": int(raw["used"]) if raw.get("used") is not None else None,
                "free_bytes": int(raw["free"]) if raw.get("free") is not None else None,
                "trashed_bytes": int(raw["trashed"]) if raw.get("trashed") is not None else None,
            }
            with self._lock:
                self._quota_cache = quota
                self._quota_ts = now
            return dict(quota), None
        except Exception as e:
            return None, _clip(f"Drive quota failed: {e}")

    def _lsjson_subdir(self, subdir):
        target = f"{self._remote_root()}/{subdir}"
        res = self._run_rclone(["lsjson", "-R", "--files-only", target], timeout=45)
        if res.returncode != 0:
            stderr = (res.stderr or "").lower()
            # rclone exit code 3 = directory not found
            if res.returncode == 3 or "directory not found" in stderr or "not found" in stderr:
                return [], None
            return None, _clip(res.stderr or f"rclone lsjson exited {res.returncode}")
        try:
            parsed = json.loads(res.stdout or "[]")
            return parsed if isinstance(parsed, list) else [], None
        except ValueError as e:
            return None, _clip(f"invalid lsjson output: {e}")

    def list_drive_items(self, force_refresh=False):
        now = time.monotonic()
        with self._lock:
            if not force_refresh and self._items_cache is not None and (now - self._items_ts) < ITEMS_TTL:
                return [dict(it) for it in self._items_cache], {}

        errors = {}
        meta = self._read_metadata()
        grouped = {}

        movies_files, err_m = self._lsjson_subdir("Movies")
        if err_m:
            errors["movies"] = err_m
        for entry in movies_files or []:
            p = str(entry.get("Path") or "").strip("/")
            if not p or "/" not in p:
                continue
            folder = p.split("/", 1)[0]
            rel_path = f"Movies/{folder}"
            rec = grouped.setdefault(rel_path, {"kind": "movie", "size": 0, "files": 0, "video_files": 0})
            rec["size"] += int(entry.get("Size") or 0)
            rec["files"] += 1
            if Path(p).suffix.lower() in VIDEO_EXTS:
                rec["video_files"] += 1

        shows_files, err_s = self._lsjson_subdir("Shows")
        if err_s:
            errors["shows"] = err_s
        for entry in shows_files or []:
            p = str(entry.get("Path") or "").strip("/")
            if not p:
                continue
            parts = p.split("/")
            if len(parts) >= 3:
                rel_path = f"Shows/{parts[0]}/{parts[1]}"
            elif len(parts) == 2:
                rel_path = f"Shows/{parts[0]}"
            else:
                continue
            rec = grouped.setdefault(rel_path, {"kind": "season", "size": 0, "files": 0, "video_files": 0})
            rec["size"] += int(entry.get("Size") or 0)
            rec["files"] += 1
            if Path(p).suffix.lower() in VIDEO_EXTS:
                rec["video_files"] += 1

        items = []
        for rel_path, rec in grouped.items():
            cached = meta.get(rel_path) or {}
            parts = rel_path.split("/")
            if rec["kind"] == "movie":
                folder = parts[1] if len(parts) > 1 else rel_path
                parsed_title, parsed_year = _parse_folder_title_year(folder)
                title = cached.get("title") or parsed_title
                year = cached.get("year") if cached.get("year") is not None else parsed_year
                subtitle = None
                episode_count = None
                season_number = None
            else:
                show_folder = parts[1] if len(parts) > 1 else rel_path
                season_folder = parts[2] if len(parts) > 2 else ""
                parsed_title, parsed_year = _parse_folder_title_year(show_folder)
                title = cached.get("title") or parsed_title
                year = cached.get("year") if cached.get("year") is not None else parsed_year
                sn = cached.get("season_number")
                if sn is None and season_folder:
                    sn = _parse_season_number(season_folder)
                season_number = sn
                if cached.get("subtitle"):
                    subtitle = cached["subtitle"]
                elif sn is not None:
                    subtitle = f"Season {sn}" if sn > 0 else "Specials"
                else:
                    subtitle = season_folder or None
                episode_count = cached.get("episode_count") or rec["video_files"] or rec["files"]

            items.append({
                "id": f"drive:{rel_path}",
                "kind": rec["kind"],
                "rel_path": rel_path,
                "title": str(title),
                "subtitle": subtitle,
                "year": year,
                "episode_count": episode_count,
                "size_bytes": int(rec["size"]),
                "radarr_id": cached.get("radarr_id"),
                "series_id": cached.get("series_id"),
                "season_number": season_number,
                "tmdb_id": cached.get("tmdb_id"),
                "offloaded_at": cached.get("offloaded_at"),
            })

        items.sort(key=lambda it: (-it["size_bytes"], it["title"].lower(), it["rel_path"]))
        if not errors:
            with self._lock:
                self._items_cache = [dict(it) for it in items]
                self._items_ts = now
        return items, errors

    def drive_status(self, force_refresh=False):
        configured = self.is_configured(force_refresh=force_refresh)
        mounted = self.is_mounted()
        errors = {}
        quota = None
        items = []

        if configured:
            quota, q_err = self.get_quota(force_refresh=force_refresh)
            if q_err:
                errors["quota"] = q_err
            items, i_errs = self.list_drive_items(force_refresh=force_refresh)
            errors.update(i_errs)

        return 200, {
            "configured": configured,
            "mounted": mounted,
            "mount_point": self.mount_point,
            "quota": quota,
            "items": items,
            "jobs": self.snapshot_jobs(),
            "errors": errors,
            "ts": int(time.time()),
        }

    # ---- VFS & Jellyfin / Arr refresh helpers ----

    def refresh_vfs(self, rel_dir=None):
        """Best-effort rclone RC /vfs/refresh call if the mount is up."""
        if not self.is_mounted():
            return False
        payload = {"recursive": "true"}
        if rel_dir:
            payload["dir"] = rel_dir
        try:
            req = urllib.request.Request(
                f"{self.rc_url}/vfs/refresh",
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                resp.read()
            return True
        except Exception:
            return False

    def _trigger_arr_rescan(self, watched_mod, kind, radarr_id=None, series_id=None):
        try:
            if kind == "movie" and radarr_id:
                watched_mod.radarr("POST", "/command", {"name": "RefreshMovie", "movieIds": [int(radarr_id)]})
            elif kind == "season" and series_id:
                watched_mod.sonarr("POST", "/command", {"name": "RescanSeries", "seriesId": int(series_id)})
        except Exception:
            pass

    # ---- SSE pub/sub ----

    def snapshot_jobs(self):
        with self._lock:
            return [self._public_job(j) for j in self._jobs.values()]

    @staticmethod
    def _public_job(job):
        return {
            "id": job["id"],
            "op": job["op"],
            "item_id": job["item_id"],
            "kind": job["kind"],
            "title": job["title"],
            "subtitle": job.get("subtitle"),
            "rel_path": job["rel_path"],
            "size_bytes": job["size_bytes"],
            "status": job["status"],
            "bytes_done": job["bytes_done"],
            "bytes_total": job["bytes_total"],
            "pct": job["pct"],
            "speed_bps": job["speed_bps"],
            "eta_s": job["eta_s"],
            "error": job.get("error"),
            "warnings": list(job.get("warnings") or []),
            "created_at": job["created_at"],
            "started_at": job.get("started_at"),
            "finished_at": job.get("finished_at"),
        }

    def subscribe(self):
        q = queue.Queue(maxsize=64)
        with self._lock:
            self._subscribers.add(q)
        return q

    def unsubscribe(self, q):
        with self._lock:
            self._subscribers.discard(q)

    def _broadcast_jobs(self):
        with self._lock:
            payload = {"jobs": [self._public_job(j) for j in self._jobs.values()], "ts": int(time.time())}
            subs = list(self._subscribers)
        for q in subs:
            try:
                q.put_nowait(("jobs", payload))
            except queue.Full:
                try:
                    q.get_nowait()
                    q.put_nowait(("jobs", payload))
                except Exception:
                    pass

    def _trim_history(self):
        finished = [jid for jid, j in self._jobs.items() if j["status"] in TERMINAL_STATUSES]
        while len(finished) > MAX_JOB_HISTORY:
            old_id = finished.pop(0)
            self._jobs.pop(old_id, None)

    def _is_cancelled(self, job_id):
        with self._lock:
            return job_id in self._cancelled_ids

    # ---- enqueue endpoints ----

    def enqueue_offload(self, body):
        """POST /api/space/offload"""
        try:
            items = space_mod.parse_free_items(body)
        except ValueError as e:
            return 400, {"error": str(e)}

        if not self.is_configured():
            return 503, {"error": "Google Drive remote 'gdrive' is not configured in rclone"}
        if not os.path.ismount(self.hdd_mnt):
            return 409, {"error": "HDD is not mounted"}

        try:
            watched_mod, _ = self._load_localagent()
        except Exception as e:
            return 503, {"error": _clip(f"localagent unavailable: {e}")}

        # Resolve local paths and metadata up front so bad paths fail immediately with 400/409
        planned = []
        errors = []
        movies_by_id = {}
        series_by_id = {}
        try:
            for m in (watched_mod.radarr("GET", "/movie") or []):
                if m.get("id") is not None:
                    movies_by_id[int(m["id"])] = m
        except Exception:
            pass
        try:
            for s in (watched_mod.sonarr_series() or []):
                if s.get("id") is not None:
                    series_by_id[int(s["id"])] = s
        except Exception:
            pass

        for it in items:
            try:
                if it["kind"] == "movie":
                    rid = it["radarr_id"]
                    p = watched_mod.plan(rid)
                    movie_folder = str(Path(p.file).parent)
                    real_dir, rel_path = validate_local_media_path(movie_folder, self.hdd_mnt)
                    m_meta = movies_by_id.get(rid) or {}
                    title = str(m_meta.get("title") or p.label)
                    year = m_meta.get("year")
                    tmdb_id = m_meta.get("tmdbId")
                    planned.append({
                        "op": "offload",
                        "item_id": it["id"],
                        "kind": "movie",
                        "radarr_id": rid,
                        "series_id": None,
                        "season_number": None,
                        "tmdb_id": tmdb_id,
                        "title": title,
                        "subtitle": None,
                        "year": year,
                        "episode_count": None,
                        "local_dir": real_dir,
                        "rel_path": rel_path,
                        "size_bytes": int(p.size or 0),
                    })
                else:
                    sid = it["series_id"]
                    sn = it["season_number"]
                    p = watched_mod.plan_season(sid, sn)
                    if not p.files:
                        raise ValueError("season has no files on disk")
                    parents = {os.path.realpath(str(Path(f.path).parent)) for f in p.files}
                    if len(parents) != 1:
                        raise ValueError("season episode files span multiple folders")
                    season_dir = next(iter(parents))
                    real_dir, rel_path = validate_local_media_path(season_dir, self.hdd_mnt)
                    s_meta = series_by_id.get(sid) or {}
                    title = str(s_meta.get("title") or p.label)
                    year = s_meta.get("year")
                    tmdb_id = s_meta.get("tmdbId")
                    subtitle = f"Season {sn}" if sn > 0 else "Specials"
                    planned.append({
                        "op": "offload",
                        "item_id": it["id"],
                        "kind": "season",
                        "radarr_id": None,
                        "series_id": sid,
                        "season_number": sn,
                        "tmdb_id": tmdb_id,
                        "title": title,
                        "subtitle": subtitle,
                        "year": year,
                        "episode_count": len(p.files),
                        "local_dir": real_dir,
                        "rel_path": rel_path,
                        "size_bytes": int(sum(int(f.size or 0) for f in p.files)),
                    })
            except Exception as e:
                errors.append({"id": it["id"], "error": _clip(e)})

        if not planned:
            msg = errors[0]["error"] if errors else "no valid items to offload"
            return 400, {"error": msg, "errors": errors}

        created = self._enqueue_jobs(planned)
        return 202, {
            "ok": len(errors) == 0,
            "jobs": [self._public_job(j) for j in created],
            "errors": errors,
        }

    def enqueue_restore(self, body):
        """POST /api/space/restore"""
        if not isinstance(body, dict):
            return 400, {"error": "request body must be an object"}
        raw = body.get("items")
        if not isinstance(raw, list) or not raw:
            return 400, {"error": "items must be a non-empty list"}
        if len(raw) > MAX_BATCH_ITEMS:
            return 400, {"error": f"too many items (max {MAX_BATCH_ITEMS})"}

        if not self.is_configured():
            return 503, {"error": "Google Drive remote 'gdrive' is not configured in rclone"}
        if not os.path.ismount(self.hdd_mnt):
            return 409, {"error": "HDD is not mounted"}

        hdd = space_mod.hdd_stats(self.hdd_mnt)
        free_bytes = int(hdd.get("free_bytes") or 0)

        drive_items, _ = self.list_drive_items()
        by_rel = {it["rel_path"]: it for it in drive_items}
        meta = self._read_metadata()

        planned = []
        seen = set()
        total_needed = 0
        for entry in raw:
            if isinstance(entry, str):
                raw_rel = entry
            elif isinstance(entry, dict):
                raw_rel = entry.get("rel_path") or ""
            else:
                return 400, {"error": "each item must be an object with rel_path or a string"}
            try:
                rel_path = validate_remote_rel_path(raw_rel)
            except ValueError as e:
                return 400, {"error": str(e)}
            if rel_path in seen:
                continue
            seen.add(rel_path)

            info = by_rel.get(rel_path) or {}
            cached = meta.get(rel_path) or {}
            size_bytes = int(
                info.get("size_bytes")
                or (entry.get("size_bytes") if isinstance(entry, dict) else 0)
                or cached.get("size_bytes")
                or 0
            )
            total_needed += size_bytes
            kind = info.get("kind") or cached.get("kind") or ("movie" if rel_path.startswith("Movies/") else "season")
            local_dir = os.path.join(self.hdd_mnt, "Media", *rel_path.split("/"))
            # Verify destination stays inside Media/{Movies,Shows}
            media_root = os.path.realpath(os.path.join(self.hdd_mnt, "Media"))
            target_parent = os.path.realpath(os.path.dirname(local_dir))
            if not (target_parent == media_root or target_parent.startswith(media_root + os.sep)):
                return 400, {"error": f"invalid restore destination for {rel_path}"}

            parts = rel_path.split("/")
            fallback_title, fallback_year = _parse_folder_title_year(parts[1])
            planned.append({
                "op": "restore",
                "item_id": f"drive:{rel_path}",
                "kind": kind,
                "radarr_id": info.get("radarr_id") or cached.get("radarr_id"),
                "series_id": info.get("series_id") or cached.get("series_id"),
                "season_number": info.get("season_number") if info.get("season_number") is not None else cached.get("season_number"),
                "tmdb_id": info.get("tmdb_id") or cached.get("tmdb_id"),
                "title": info.get("title") or cached.get("title") or fallback_title,
                "subtitle": info.get("subtitle") or cached.get("subtitle") or (parts[2] if len(parts) > 2 else None),
                "year": info.get("year") if info.get("year") is not None else fallback_year,
                "episode_count": info.get("episode_count") or cached.get("episode_count"),
                "local_dir": local_dir,
                "rel_path": rel_path,
                "size_bytes": size_bytes,
            })

        if free_bytes <= total_needed + RESTORE_FREE_MARGIN_BYTES:
            need_gb = (total_needed + RESTORE_FREE_MARGIN_BYTES) / (1024 ** 3)
            free_gb = free_bytes / (1024 ** 3)
            return 409, {
                "error": f"Not enough free space on HDD ({free_gb:.1f} GiB free, need > {need_gb:.1f} GiB including 5 GiB margin)"
            }

        created = self._enqueue_jobs(planned)
        return 202, {"ok": True, "jobs": [self._public_job(j) for j in created]}

    def _enqueue_jobs(self, planned):
        created = []
        now = int(time.time())
        with self._cond:
            active_rels = {
                j["rel_path"]
                for j in self._jobs.values()
                if j["status"] in ACTIVE_STATUSES
            }
            for item in planned:
                if item["rel_path"] in active_rels:
                    continue
                job_id = uuid.uuid4().hex[:12]
                job = {
                    **item,
                    "id": job_id,
                    "status": "queued",
                    "bytes_done": 0,
                    "bytes_total": int(item.get("size_bytes") or 0),
                    "pct": 0.0,
                    "speed_bps": 0,
                    "eta_s": None,
                    "error": None,
                    "warnings": [],
                    "created_at": now,
                    "started_at": None,
                    "finished_at": None,
                }
                self._jobs[job_id] = job
                self._queue.append(job_id)
                active_rels.add(item["rel_path"])
                created.append(job)
            self._trim_history()
            self._cond.notify_all()
        if created:
            self._broadcast_jobs()
        return created

    def delete_from_drive(self, body):
        """POST /api/space/drive/delete"""
        if not isinstance(body, dict):
            return 400, {"error": "request body must be an object"}
        raw_items = body.get("items")
        if raw_items is None and isinstance(body.get("rel_path"), str):
            raw_items = [{"rel_path": body["rel_path"]}]
        if not isinstance(raw_items, list) or not raw_items:
            return 400, {"error": "items must be a non-empty list"}
        if len(raw_items) > MAX_BATCH_ITEMS:
            return 400, {"error": f"too many items (max {MAX_BATCH_ITEMS})"}

        rel_paths = []
        seen = set()
        for entry in raw_items:
            raw_rel = entry if isinstance(entry, str) else (entry.get("rel_path") if isinstance(entry, dict) else None)
            try:
                rel = validate_remote_rel_path(raw_rel)
            except ValueError as e:
                return 400, {"error": str(e)}
            if rel not in seen:
                seen.add(rel)
                rel_paths.append(rel)

        if not self.is_configured():
            return 503, {"error": "Google Drive remote 'gdrive' is not configured in rclone"}

        results = []
        warnings = []
        any_ok = False
        for rel in rel_paths:
            remote_target = self._remote_path(rel)
            try:
                res = self._run_rclone(["purge", remote_target], timeout=120)
                if res.returncode == 0:
                    any_ok = True
                    self._delete_item_metadata(rel)
                    results.append({"rel_path": rel, "ok": True, "error": None})
                else:
                    results.append({
                        "rel_path": rel,
                        "ok": False,
                        "error": _clip(res.stderr or f"rclone purge exited {res.returncode}"),
                    })
            except Exception as e:
                results.append({"rel_path": rel, "ok": False, "error": _clip(e)})

        if any_ok:
            self.refresh_vfs()
            try:
                watched_mod, _ = self._load_localagent()
                watched_mod._refresh()
            except Exception as e:
                warnings.append(_clip(f"Jellyfin refresh failed: {e}"))

        return 200, {
            "ok": all(r["ok"] for r in results),
            "results": results,
            "warnings": warnings,
        }

    def cancel_job(self, job_id):
        """POST /api/space/jobs/<id>/cancel"""
        proc_to_stop = None
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return 404, {"error": "job not found"}
            if job["status"] in TERMINAL_STATUSES:
                return 200, {"ok": True, "job": self._public_job(job)}
            if job["status"] == "cleaning":
                return 409, {"error": "transfer already verified; local cleanup in progress"}

            self._cancelled_ids.add(job_id)
            if job["status"] == "queued":
                try:
                    self._queue.remove(job_id)
                except ValueError:
                    pass
                job["status"] = "cancelled"
                job["finished_at"] = int(time.time())
                job["speed_bps"] = 0
                job["eta_s"] = None
            elif self._active_job_id == job_id:
                proc_to_stop = self._active_proc

        if proc_to_stop is not None:
            try:
                proc_to_stop.terminate()
                try:
                    proc_to_stop.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc_to_stop.kill()
            except Exception:
                pass

        self._broadcast_jobs()
        with self._lock:
            return 200, {"ok": True, "job": self._public_job(self._jobs[job_id])}

    # ---- worker loop ----

    def _worker_loop(self):
        while True:
            with self._cond:
                while not self._queue and not self._stop:
                    # If a batch just finished, send summary notification
                    if self._batch_done or self._batch_failed:
                        done_n = self._batch_done
                        fail_n = self._batch_failed
                        ops = set(self._batch_ops)
                        self._batch_done = 0
                        self._batch_failed = 0
                        self._batch_ops.clear()
                        self._cond.release()
                        try:
                            self._send_batch_notification(ops, done_n, fail_n)
                        finally:
                            self._cond.acquire()
                    self._cond.wait(timeout=5.0)
                if self._stop:
                    return
                if not self._queue:
                    continue
                job_id = self._queue.popleft()
                job = self._jobs.get(job_id)
                if not job or job["status"] != "queued":
                    continue
                self._active_job_id = job_id

            try:
                self._execute_job(job_id)
            finally:
                with self._lock:
                    self._active_job_id = None
                    self._active_proc = None
                    self._trim_history()
                self._broadcast_jobs()

    def _send_batch_notification(self, ops, done_n, fail_n):
        if not done_n and not fail_n:
            return
        if ops == {"offload"}:
            verb = "Offload"
        elif ops == {"restore"}:
            verb = "Restore"
        else:
            verb = "Drive transfer"
        if fail_n == 0:
            title = f"{verb} complete"
            body = f"{done_n} item{'s' if done_n != 1 else ''} finished and verified."
        elif done_n == 0:
            title = f"{verb} failed"
            body = f"{fail_n} item{'s' if fail_n != 1 else ''} failed. Original files were kept."
        else:
            title = f"{verb} finished with errors"
            body = f"{done_n} succeeded, {fail_n} failed."
        self._notify(title, body, category="system")

    def _run_rclone_copy_with_progress(self, job, src, dst):
        cmd = [
            self.rclone_bin,
            "copy",
            src,
            dst,
            "--stats",
            "1s",
            "--use-json-log",
            "--transfers",
            "2",
            "--drive-chunk-size",
            "64M",
        ]
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        with self._lock:
            self._active_proc = proc
        if self._is_cancelled(job["id"]):
            try:
                proc.terminate()
            except Exception:
                pass

        last_err_lines = collections.deque(maxlen=8)
        last_emit = 0.0
        assert proc.stderr is not None
        for line in proc.stderr:
            stats = _parse_rclone_stats_line(line)
            if stats is not None:
                with self._lock:
                    job["bytes_done"] = stats["bytes_done"]
                    if stats["bytes_total"] > 0:
                        job["bytes_total"] = stats["bytes_total"]
                    job["pct"] = stats["pct"]
                    job["speed_bps"] = stats["speed_bps"]
                    job["eta_s"] = stats["eta_s"]
                now = time.monotonic()
                if now - last_emit >= 0.4:
                    last_emit = now
                    self._broadcast_jobs()
            else:
                txt = line.strip()
                if txt:
                    last_err_lines.append(txt)

        rc = proc.wait()
        with self._lock:
            self._active_proc = None
        if self._is_cancelled(job["id"]):
            return False, "cancelled"
        if rc != 0:
            detail = " ".join(last_err_lines) or f"rclone copy exited {rc}"
            return False, _clip(detail)
        with self._lock:
            if job["bytes_total"] > 0:
                job["bytes_done"] = job["bytes_total"]
            job["pct"] = 100.0
            job["speed_bps"] = 0
            job["eta_s"] = 0
        self._broadcast_jobs()
        return True, None

    def _run_rclone_check(self, job, src, dst):
        cmd = [self.rclone_bin, "check", src, dst]
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        with self._lock:
            self._active_proc = proc
        if self._is_cancelled(job["id"]):
            try:
                proc.terminate()
            except Exception:
                pass
        _, stderr = proc.communicate()
        rc = proc.returncode
        with self._lock:
            self._active_proc = None
        if self._is_cancelled(job["id"]):
            return False, "cancelled"
        if rc != 0:
            return False, _clip(stderr or f"MD5 verification failed (rclone check exited {rc})")
        return True, None

    def _execute_job(self, job_id):
        with self._lock:
            job = self._jobs[job_id]
            job["status"] = "copying"
            job["started_at"] = int(time.time())
            op = job["op"]
        self._broadcast_jobs()

        try:
            if op == "offload":
                self._run_offload_job(job)
            elif op == "restore":
                self._run_restore_job(job)
            else:
                raise ValueError(f"unsupported op: {op}")
        except Exception as e:
            with self._lock:
                if self._is_cancelled(job_id):
                    job["status"] = "cancelled"
                    job["error"] = None
                else:
                    job["status"] = "failed"
                    job["error"] = _clip(e)
                    self._batch_failed += 1
                    self._batch_ops.add(op)
                job["speed_bps"] = 0
                job["eta_s"] = None
                job["finished_at"] = int(time.time())

    def _run_offload_job(self, job):
        real_dir, rel_path = validate_local_media_path(job["local_dir"], self.hdd_mnt)
        remote_dst = self._remote_path(rel_path)

        ok, err = self._run_rclone_copy_with_progress(job, real_dir, remote_dst)
        if not ok:
            self._finish_job_abort(job, err)
            return

        with self._lock:
            job["status"] = "verifying"
            job["speed_bps"] = 0
            job["eta_s"] = None
        self._broadcast_jobs()

        # Mandatory MD5 verification gate before touching local files
        ok_check, check_err = self._run_rclone_check(job, real_dir, remote_dst)
        if not ok_check:
            self._finish_job_abort(job, check_err)
            return

        if self._is_cancelled(job["id"]):
            self._finish_job_abort(job, "cancelled")
            return

        with self._lock:
            job["status"] = "cleaning"
        self._broadcast_jobs()

        # Save metadata cache entry before deleting local copy
        self._save_item_metadata(rel_path, {
            "kind": job["kind"],
            "title": job["title"],
            "subtitle": job.get("subtitle"),
            "year": job.get("year"),
            "episode_count": job.get("episode_count"),
            "size_bytes": job["size_bytes"],
            "radarr_id": job.get("radarr_id"),
            "series_id": job.get("series_id"),
            "season_number": job.get("season_number"),
            "tmdb_id": job.get("tmdb_id"),
            "offloaded_at": int(time.time()),
        })

        # Unmonitor + safe local delete reusing space.py / watched.py invariants
        watched_mod, _ = self._load_localagent()
        with watched_mod._lock:
            if job["kind"] == "movie":
                res, _ = space_mod._free_movie(watched_mod, job["radarr_id"])
            else:
                res, _ = space_mod._free_season(watched_mod, job["series_id"], job["season_number"])

        if not res.get("ok"):
            raise RuntimeError(f"Copied & verified on Drive, but local cleanup failed: {res.get('error')}")

        warnings = list(res.get("warnings") or [])
        parent_dir = rel_path.split("/", 1)[0]
        self.refresh_vfs(parent_dir)
        try:
            watched_mod._refresh()
        except Exception as e:
            warnings.append(_clip(f"Jellyfin refresh failed: {e}"))

        with self._lock:
            job["status"] = "done"
            job["pct"] = 100.0
            job["warnings"] = warnings
            job["finished_at"] = int(time.time())
            self._batch_done += 1
            self._batch_ops.add("offload")

    def _run_restore_job(self, job):
        rel_path = validate_remote_rel_path(job["rel_path"])
        if not os.path.ismount(self.hdd_mnt):
            raise RuntimeError("HDD is not mounted")

        hdd = space_mod.hdd_stats(self.hdd_mnt)
        free_bytes = int(hdd.get("free_bytes") or 0)
        if free_bytes <= int(job["size_bytes"]) + RESTORE_FREE_MARGIN_BYTES:
            raise RuntimeError("Not enough free space on HDD (need item size + 5 GiB margin)")

        local_dst = job["local_dir"]
        os.makedirs(local_dst, exist_ok=True)
        real_dst, _ = validate_local_media_path(local_dst, self.hdd_mnt)
        remote_src = self._remote_path(rel_path)

        ok, err = self._run_rclone_copy_with_progress(job, remote_src, real_dst)
        if not ok:
            self._finish_job_abort(job, err)
            return

        with self._lock:
            job["status"] = "verifying"
            job["speed_bps"] = 0
            job["eta_s"] = None
        self._broadcast_jobs()

        # Mandatory MD5 verification gate before purging remote copy
        ok_check, check_err = self._run_rclone_check(job, remote_src, real_dst)
        if not ok_check:
            self._finish_job_abort(job, check_err)
            return

        if self._is_cancelled(job["id"]):
            self._finish_job_abort(job, "cancelled")
            return

        with self._lock:
            job["status"] = "cleaning"
        self._broadcast_jobs()

        purge_res = self._run_rclone(["purge", remote_src], timeout=120)
        if purge_res.returncode != 0:
            raise RuntimeError(
                f"Restored & verified locally, but remote purge failed: {_clip(purge_res.stderr or purge_res.returncode)}"
            )

        self._delete_item_metadata(rel_path)
        parent_dir = rel_path.split("/", 1)[0]
        self.refresh_vfs(parent_dir)

        warnings = []
        try:
            watched_mod, _ = self._load_localagent()
            self._trigger_arr_rescan(watched_mod, job["kind"], job.get("radarr_id"), job.get("series_id"))
            watched_mod._refresh()
        except Exception as e:
            warnings.append(_clip(f"Library rescan warning: {e}"))

        with self._lock:
            job["status"] = "done"
            job["pct"] = 100.0
            job["warnings"] = warnings
            job["finished_at"] = int(time.time())
            self._batch_done += 1
            self._batch_ops.add("restore")

    def _finish_job_abort(self, job, err):
        with self._lock:
            if err == "cancelled" or self._is_cancelled(job["id"]):
                job["status"] = "cancelled"
                job["error"] = None
            else:
                job["status"] = "failed"
                job["error"] = _clip(err)
                self._batch_failed += 1
                self._batch_ops.add(job["op"])
            job["speed_bps"] = 0
            job["eta_s"] = None
            job["finished_at"] = int(time.time())

    def shutdown(self):
        with self._cond:
            self._stop = True
            self._cond.notify_all()


_MANAGER = None
_MANAGER_LOCK = threading.Lock()


def get_manager(notify_fn=None):
    global _MANAGER
    with _MANAGER_LOCK:
        if _MANAGER is None:
            _MANAGER = GDriveOffloadManager(notify_fn=notify_fn)
        elif notify_fn is not None:
            _MANAGER._notify_fn = notify_fn
        return _MANAGER
