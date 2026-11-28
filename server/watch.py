"""Watch (Netflix-style media hub) backend endpoints for pc-api.

Aggregates live Jellyfin playback/library state, Seerr/TMDB discovery rails &
episode metadata, Radarr/Sonarr active download progress, and a persisted
My List / Watchlist store.

All upstream calls run concurrently with graceful per-section degradation so a
slow or offline upstream never breaks the Watch experience.
"""

import concurrent.futures
import json
import os
import re
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


TICKS_PER_SEC = 10_000_000
HOME_CACHE_TTL_SEC = 45.0
LIBRARY_INDEX_TTL_SEC = 45.0
GENRES_CACHE_TTL_SEC = 3600.0
USER_ID_CACHE_TTL_SEC = 300.0

JELLYFIN_ITEM_ID_RE = re.compile(r"^[0-9a-fA-F]{32}$")
TMDB_IMG_BASE = "https://image.tmdb.org/t/p"
TMDB_ORIG_RE = re.compile(r"^https?://image\.tmdb\.org/t/p/original(/.*)$")

# Curated Netflix-style discovery rails powered by Seerr's local TMDB proxy
HOME_RAIL_SPECS = (
    {
        "id": "trending",
        "title": "Trending now",
        "path": "/discover/trending?page=1",
        "category": "all",
        "mediaType": None,
        "forcedType": None,
    },
    {
        "id": "popular_movies",
        "title": "Popular movies",
        "path": "/discover/movies?page=1",
        "category": "movies",
        "mediaType": "movie",
        "forcedType": "movie",
    },
    {
        "id": "popular_tv",
        "title": "Popular TV shows",
        "path": "/discover/tv?page=1",
        "category": "tv",
        "mediaType": "tv",
        "forcedType": "tv",
    },
    {
        "id": "upcoming_movies",
        "title": "Coming soon",
        "path": "/discover/movies/upcoming?page=1",
        "category": "movies",
        "mediaType": "movie",
        "forcedType": "movie",
    },
    {
        "id": "genre_scifi",
        "title": "Sci-Fi & Thriller",
        "path": "/discover/movies/genre/878?page=1",
        "category": "genre",
        "mediaType": "movie",
        "genreId": 878,
        "forcedType": "movie",
    },
    {
        "id": "genre_comedy",
        "title": "Comedy",
        "path": "/discover/movies/genre/35?page=1",
        "category": "genre",
        "mediaType": "movie",
        "genreId": 35,
        "forcedType": "movie",
    },
    {
        "id": "genre_anime",
        "title": "Anime",
        "path": "/discover/tv?sortBy=popularity.desc&genre=16&language=ja&page=1",
        "category": "genre",
        "mediaType": "tv",
        "genreId": 16,
        "forcedType": "tv",
    },
    {
        "id": "network_netflix",
        "title": "On Netflix",
        "path": "/discover/tv/network/213?page=1",
        "category": "network",
        "mediaType": "tv",
        "networkId": 213,
        "forcedType": "tv",
    },
    {
        "id": "network_hbo",
        "title": "On HBO",
        "path": "/discover/tv/network/49?page=1",
        "category": "network",
        "mediaType": "tv",
        "networkId": 49,
        "forcedType": "tv",
    },
    {
        "id": "network_apple",
        "title": "On Apple TV+",
        "path": "/discover/tv/network/2552?page=1",
        "category": "network",
        "mediaType": "tv",
        "networkId": 2552,
        "forcedType": "tv",
    },
)


def _clip(text, limit=240):
    s = str(text or "").strip()
    if len(s) <= limit:
        return s
    return s[: max(0, limit - 1)].rstrip() + "…"


def _safe_int(val):
    if val is None or isinstance(val, bool):
        return None
    try:
        return int(val)
    except (TypeError, ValueError):
        return None


def _safe_float(val):
    if val is None or isinstance(val, bool):
        return None
    try:
        return float(val)
    except (TypeError, ValueError):
        return None


def _year(date_str):
    m = re.match(r"^(\d{4})", str(date_str or ""))
    return int(m.group(1)) if m else None


def _vote_avg(val):
    f = _safe_float(val)
    if f is None or f <= 0:
        return None
    return round(f, 1)


def _tmdb_img(path, size="w300"):
    if not path or not isinstance(path, str):
        return None
    if path.startswith("http://") or path.startswith("https://"):
        m = TMDB_ORIG_RE.match(path)
        if m:
            return f"{TMDB_IMG_BASE}/{size}{m.group(1)}"
        return path
    return f"{TMDB_IMG_BASE}/{size}{path}"


def _jf_img_url(item_id, image_type="Primary", series_id=None, max_width=None):
    if not isinstance(item_id, str) or not JELLYFIN_ITEM_ID_RE.match(item_id):
        return None
    params = {"type": image_type}
    if isinstance(series_id, str) and JELLYFIN_ITEM_ID_RE.match(series_id) and series_id != item_id:
        params["seriesId"] = series_id
    if isinstance(max_width, int) and max_width > 0:
        params["maxWidth"] = str(max_width)
    return f"/pc/api/jellyfin/image/{item_id}?{urllib.parse.urlencode(params)}"


def _parse_provider_tmdb(provider_ids):
    if not isinstance(provider_ids, dict):
        return None
    for k in ("Tmdb", "tmdb", "TMDB"):
        raw = provider_ids.get(k)
        val = _safe_int(raw)
        if val is not None and val > 0:
            return val
    return None


def _parse_timeleft_seconds(raw):
    """Parse Radarr/Sonarr timeleft like '00:48:20' or '1.02:15:00' into integer seconds."""
    if not raw or not isinstance(raw, str):
        return None
    s = raw.strip()
    days = 0
    if "." in s:
        day_part, _, rest = s.partition(".")
        if day_part.isdigit() and ":" in rest:
            days = int(day_part)
            s = rest
    # Strip fractional seconds if present (e.g. 00:12:34.5000000)
    s = s.split(".", 1)[0]
    parts = s.split(":")
    if len(parts) != 3:
        return None
    try:
        h, m, sec = int(parts[0]), int(parts[1]), int(parts[2])
    except ValueError:
        return None
    total = days * 86400 + h * 3600 + m * 60 + sec
    return total if total > 0 else None


def _format_eta(seconds):
    if seconds is None or seconds <= 0:
        return None
    if seconds < 60:
        return "<1m"
    mins = int(round(seconds / 60.0))
    if mins < 60:
        return f"{mins}m"
    hours = mins // 60
    rem_mins = mins % 60
    if hours < 24:
        return f"{hours}h {rem_mins}m" if rem_mins else f"{hours}h"
    days = hours // 24
    rem_hours = hours % 24
    return f"{days}d {rem_hours}h" if rem_hours else f"{days}d"


def _arr_image_url(images, cover_type, size):
    if not isinstance(images, list):
        return None
    for img in images:
        if not isinstance(img, dict):
            continue
        if str(img.get("coverType") or "").lower() == cover_type:
            remote = img.get("remoteUrl")
            if isinstance(remote, str) and remote.startswith(("http://", "https://")):
                return _tmdb_img(remote, size=size)
    return None


def _seerr_media_status(media_info):
    if not isinstance(media_info, dict):
        return "none"
    st = media_info.get("status")
    dl = media_info.get("downloadStatus") or []
    if st == 5:
        return "available"
    if st == 7:
        return "deleted"
    if st == 6:
        return "blocklisted"
    if st == 4:
        return "partial"
    if dl:
        return "grabbing"
    if st in (2, 3):
        return "requested"
    return "none"


def _watchlist_key(media_type, tmdb_id):
    mt = "tv" if str(media_type).strip().lower() == "tv" else "movie"
    return f"{mt}:{int(tmdb_id)}"


class WatchlistStore:
    """Thread-safe JSON persistence for My List / Watchlist items."""

    def __init__(self, path=None):
        home = os.path.expanduser("~")
        self.path = path or os.environ.get(
            "PCAPI_WATCHLIST_FILE",
            os.path.join(home, ".local", "state", "pc-control", "watchlist.json"),
        )
        self._lock = threading.Lock()

    def _read_locked(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                data = json.load(f)
            items = data.get("items") if isinstance(data, dict) else []
            return [x for x in items if isinstance(x, dict) and _safe_int(x.get("id"))]
        except (FileNotFoundError, ValueError, OSError):
            return []

    def _write_locked(self, items):
        parent = os.path.dirname(self.path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        payload = json.dumps({"items": items}, ensure_ascii=False, indent=2).encode("utf-8")
        fd, tmp_path = tempfile.mkstemp(prefix=".watchlist.", suffix=".tmp", dir=parent or None)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(payload)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, self.path)
        except Exception:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise

    def list_items(self):
        with self._lock:
            items = self._read_locked()
        items.sort(key=lambda x: int(x.get("addedAt") or 0), reverse=True)
        return items

    def id_set(self):
        with self._lock:
            items = self._read_locked()
        return {_watchlist_key(x.get("mediaType"), x["id"]) for x in items}

    def mutate(self, tmdb_id, media_type, action="toggle", meta=None):
        mt = "tv" if str(media_type).strip().lower() == "tv" else "movie"
        key = _watchlist_key(mt, tmdb_id)
        meta = meta if isinstance(meta, dict) else {}

        with self._lock:
            items = self._read_locked()
            existing_idx = next(
                (i for i, x in enumerate(items) if _watchlist_key(x.get("mediaType"), x["id"]) == key),
                None,
            )
            in_list = existing_idx is not None

            if action == "remove" or (action == "toggle" and in_list):
                if existing_idx is not None:
                    items.pop(existing_idx)
                    self._write_locked(items)
                new_state = False
            else:
                entry = {
                    "id": int(tmdb_id),
                    "mediaType": mt,
                    "title": _clip(meta.get("title") or "Untitled", 160),
                    "year": _safe_int(meta.get("year")),
                    "poster": meta.get("poster") or None,
                    "backdrop": meta.get("backdrop") or None,
                    "overview": _clip(meta.get("overview") or "", 240),
                    "voteAverage": _vote_avg(meta.get("voteAverage")),
                    "addedAt": int(time.time()),
                }
                if existing_idx is not None:
                    # Preserve original metadata if new call omitted optional fields
                    prev = items[existing_idx]
                    for field in ("title", "year", "poster", "backdrop", "overview", "voteAverage"):
                        if not entry.get(field) and prev.get(field):
                            entry[field] = prev[field]
                    items[existing_idx] = entry
                else:
                    items.insert(0, entry)
                self._write_locked(items)
                new_state = True

            ids = sorted({_watchlist_key(x.get("mediaType"), x["id"]) for x in items})
            return new_state, len(items), ids


class WatchService:
    """Netflix-style Watch hub service backed by Jellyfin, Seerr, Radarr, and Sonarr."""

    def __init__(
        self,
        seerr_client_fn,
        jellyfin_url_fn,
        jellyfin_key_fn,
        arr_config_fn,
        downloads_fn=None,
        preferred_user_fn=None,
        watchlist_store=None,
    ):
        self._seerr_client_fn = seerr_client_fn
        self._jellyfin_url_fn = jellyfin_url_fn
        self._jellyfin_key_fn = jellyfin_key_fn
        self._arr_config_fn = arr_config_fn
        self._downloads_fn = downloads_fn
        self._preferred_user_fn = preferred_user_fn or (lambda: "ayush")
        self.watchlist = watchlist_store or WatchlistStore()

        self._lock = threading.Lock()
        self._user_id_cache = (0.0, None)
        self._home_cache = (0.0, None)
        self._library_index_cache = (0.0, None)
        self._genres_cache = {}  # "movie"|"tv" -> (ts, list)

    def invalidate_caches(self):
        with self._lock:
            self._home_cache = (0.0, None)
            self._library_index_cache = (0.0, None)

    # ------------------------------------------------------------------ HTTP helpers

    def _jf_get(self, path, timeout=8):
        key = self._jellyfin_key_fn()
        base = self._jellyfin_url_fn().rstrip("/")
        req = urllib.request.Request(
            f"{base}{path}",
            headers={"Authorization": f'MediaBrowser Token="{key}"', "Accept": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
        return json.loads(raw) if raw.strip() else {}

    def _jf_user_id(self, timeout=6):
        now = time.time()
        with self._lock:
            ts, cached = self._user_id_cache
            if cached and (now - ts) < USER_ID_CACHE_TTL_SEC:
                return cached
        users = self._jf_get("/Users", timeout=timeout)
        if not isinstance(users, list) or not users:
            raise ValueError("No Jellyfin users found")
        pref = str(self._preferred_user_fn() or "ayush").strip().lower()
        chosen = None
        for u in users:
            if isinstance(u, dict) and str(u.get("Name") or "").strip().lower() == pref and u.get("Id"):
                chosen = str(u["Id"])
                break
        if not chosen:
            for u in users:
                if isinstance(u, dict) and u.get("Id"):
                    chosen = str(u["Id"])
                    break
        if not chosen:
            raise ValueError("No valid Jellyfin user ID")
        with self._lock:
            self._user_id_cache = (time.time(), chosen)
        return chosen

    def _seerr_get(self, path, timeout=8):
        return self._seerr_client_fn()._req("GET", path, timeout=timeout)

    def _library_index(self, force_refresh=False):
        """Returns dict: by_tmdb {(mediaType, tmdbId): jfId}, series_tmdb {jfSeriesId: tmdbId}, total int."""
        now = time.time()
        if not force_refresh:
            with self._lock:
                ts, cached = self._library_index_cache
                if cached is not None and (now - ts) < LIBRARY_INDEX_TTL_SEC:
                    return cached

        uid = self._jf_user_id(timeout=5)
        qs = urllib.parse.urlencode({
            "IncludeItemTypes": "Movie,Series",
            "Recursive": "true",
            "Fields": "ProviderIds",
            "Limit": "1000",
        })
        data = self._jf_get(f"/Users/{uid}/Items?{qs}", timeout=8)
        items = data.get("Items") if isinstance(data, dict) else []
        by_tmdb = {}
        series_tmdb = {}
        for it in items if isinstance(items, list) else []:
            if not isinstance(it, dict):
                continue
            jf_id = str(it.get("Id") or "").strip()
            if not JELLYFIN_ITEM_ID_RE.match(jf_id):
                continue
            itype = str(it.get("Type") or "")
            mtype = "tv" if itype == "Series" else "movie" if itype == "Movie" else None
            if not mtype:
                continue
            tid = _parse_provider_tmdb(it.get("ProviderIds"))
            if tid:
                by_tmdb[(mtype, tid)] = jf_id
                if mtype == "tv":
                    series_tmdb[jf_id] = tid

        index = {
            "by_tmdb": by_tmdb,
            "series_tmdb": series_tmdb,
            "total": int(data.get("TotalRecordCount") or len(items or [])),
        }
        with self._lock:
            self._library_index_cache = (time.time(), index)
        return index

    def _get_genres(self, media_type):
        mt = "tv" if media_type == "tv" else "movie"
        now = time.time()
        with self._lock:
            cached = self._genres_cache.get(mt)
            if cached and (now - cached[0]) < GENRES_CACHE_TTL_SEC:
                return cached[1]
        try:
            raw = self._seerr_get(f"/genres/{mt}", timeout=6)
            out = []
            for g in raw if isinstance(raw, list) else []:
                if isinstance(g, dict) and _safe_int(g.get("id")) and g.get("name"):
                    out.append({"id": int(g["id"]), "name": str(g["name"]).strip()})
            if out:
                with self._lock:
                    self._genres_cache[mt] = (time.time(), out)
            return out
        except Exception:
            return []

    # ------------------------------------------------------------------ Normalizers

    def _normalize_seerr_item(self, x, forced_type=None, lib_index=None, watchlist_ids=None, hero_quality=False):
        if not isinstance(x, dict):
            return None
        mtype = forced_type or x.get("mediaType")
        if mtype not in ("movie", "tv"):
            return None
        tid = _safe_int(x.get("id"))
        if not tid or tid <= 0:
            return None
        title = x.get("title") or x.get("name") or x.get("originalTitle") or x.get("originalName") or "Untitled"
        status = _seerr_media_status(x.get("mediaInfo"))
        jf_id = None
        if lib_index:
            jf_id = lib_index.get("by_tmdb", {}).get((mtype, tid))
            if jf_id and status in ("none", "deleted", "requested", "partial"):
                status = "available"

        key = _watchlist_key(mtype, tid)
        return {
            "id": tid,
            "mediaType": mtype,
            "title": _clip(title, 160),
            "year": _year(x.get("releaseDate") or x.get("firstAirDate")),
            "overview": _clip(x.get("overview") or "", 280),
            "poster": _tmdb_img(x.get("posterPath"), size="w500" if hero_quality else "w300"),
            "backdrop": _tmdb_img(x.get("backdropPath"), size="w1280" if hero_quality else "w780"),
            "voteAverage": _vote_avg(x.get("voteAverage")),
            "status": status,
            "jellyfinItemId": jf_id,
            "inWatchlist": key in watchlist_ids if watchlist_ids is not None else False,
            "genres": [],
        }

    def _normalize_jf_library_item(self, it, watchlist_ids=None, hero_quality=False):
        if not isinstance(it, dict):
            return None
        jf_id = str(it.get("Id") or "").strip()
        if not JELLYFIN_ITEM_ID_RE.match(jf_id):
            return None
        itype = str(it.get("Type") or "")
        if itype == "Series":
            mtype = "tv"
        elif itype == "Movie":
            mtype = "movie"
        else:
            return None

        tid = _parse_provider_tmdb(it.get("ProviderIds")) or 0
        has_backdrop = bool(it.get("BackdropImageTags"))
        backdrop_w = 1280 if hero_quality else 780
        poster_w = 500 if hero_quality else 400
        poster_url = _jf_img_url(jf_id, "Primary", max_width=poster_w)
        backdrop_url = _jf_img_url(jf_id, "Backdrop", max_width=backdrop_w) if has_backdrop else None
        genres = [str(g) for g in (it.get("Genres") or []) if isinstance(g, str) and g.strip()][:5]

        key = _watchlist_key(mtype, tid) if tid > 0 else ""
        return {
            "id": tid,
            "mediaType": mtype,
            "title": _clip(it.get("Name") or "Untitled", 160),
            "year": _safe_int(it.get("ProductionYear")),
            "overview": _clip(it.get("Overview") or "", 280),
            "poster": poster_url,
            "backdrop": backdrop_url,
            "voteAverage": _vote_avg(it.get("CommunityRating")),
            "status": "available",
            "jellyfinItemId": jf_id,
            "inWatchlist": bool(key and watchlist_ids is not None and key in watchlist_ids),
            "genres": genres,
        }

    def _normalize_continue_item(self, it, series_tmdb, is_next_up=False):
        if not isinstance(it, dict):
            return None
        jf_id = str(it.get("Id") or "").strip()
        if not JELLYFIN_ITEM_ID_RE.match(jf_id):
            return None
        itype = str(it.get("Type") or "")
        ud = it.get("UserData") if isinstance(it.get("UserData"), dict) else {}
        pos_ticks = max(0, _safe_int(ud.get("PlaybackPositionTicks")) or 0)
        run_ticks = max(0, _safe_int(it.get("RunTimeTicks")) or 0)
        played_pct = _safe_float(ud.get("PlayedPercentage"))
        if played_pct is None:
            played_pct = (pos_ticks / run_ticks * 100.0) if (run_ticks > 0 and pos_ticks > 0) else 0.0
        progress_pct = round(max(0.0, min(100.0, played_pct)), 1)

        rem_min = None
        if run_ticks > 0 and run_ticks > pos_ticks:
            rem_min = max(1, int(round((run_ticks - pos_ticks) / TICKS_PER_SEC / 60.0)))

        if itype == "Episode":
            series_id_raw = str(it.get("SeriesId") or "").strip()
            series_id = series_id_raw if JELLYFIN_ITEM_ID_RE.match(series_id_raw) else None
            parent_backdrop_raw = str(it.get("ParentBackdropItemId") or "").strip()
            parent_backdrop_id = parent_backdrop_raw if JELLYFIN_ITEM_ID_RE.match(parent_backdrop_raw) else None
            tmdb_id = series_tmdb.get(series_id) if series_id else None
            sn = _safe_int(it.get("ParentIndexNumber"))
            en = _safe_int(it.get("IndexNumber"))
            if sn is not None and en is not None:
                ep_label = f"S{sn} · E{en}"
            elif en is not None:
                ep_label = f"E{en}"
            else:
                ep_label = None

            # Episode Primary in Jellyfin is a 16:9 still; fall back to Series Backdrop
            img_tags = it.get("ImageTags") if isinstance(it.get("ImageTags"), dict) else {}
            if img_tags.get("Primary"):
                backdrop_url = _jf_img_url(jf_id, "Primary", series_id=series_id, max_width=720)
            else:
                bd_target = parent_backdrop_id or series_id or jf_id
                backdrop_url = _jf_img_url(bd_target, "Backdrop", series_id=series_id, max_width=720)
            poster_url = _jf_img_url(series_id or jf_id, "Primary", max_width=400)

            return {
                "id": int(tmdb_id or 0),
                "mediaType": "tv",
                "title": _clip(it.get("SeriesName") or it.get("Name") or "Untitled", 160),
                "year": _safe_int(it.get("ProductionYear")),
                "status": "available",
                "jellyfinItemId": jf_id,
                "seriesId": series_id,
                "episodeLabel": ep_label,
                "episodeTitle": _clip(it.get("Name") or "", 140) or None,
                "seasonNumber": sn,
                "episodeNumber": en,
                "progressPct": progress_pct,
                "positionTicks": pos_ticks,
                "runtimeTicks": run_ticks,
                "remainingMinutes": rem_min,
                "isNextUp": bool(is_next_up),
                "backdrop": backdrop_url,
                "poster": poster_url,
            }

        if itype == "Movie":
            tmdb_id = _parse_provider_tmdb(it.get("ProviderIds")) or 0
            has_bd = bool(it.get("BackdropImageTags"))
            backdrop_url = _jf_img_url(jf_id, "Backdrop" if has_bd else "Primary", max_width=720)
            poster_url = _jf_img_url(jf_id, "Primary", max_width=400)
            return {
                "id": int(tmdb_id),
                "mediaType": "movie",
                "title": _clip(it.get("Name") or "Untitled", 160),
                "year": _safe_int(it.get("ProductionYear")),
                "status": "available",
                "jellyfinItemId": jf_id,
                "seriesId": None,
                "episodeLabel": None,
                "episodeTitle": None,
                "seasonNumber": None,
                "episodeNumber": None,
                "progressPct": progress_pct,
                "positionTicks": pos_ticks,
                "runtimeTicks": run_ticks,
                "remainingMinutes": rem_min,
                "isNextUp": False,
                "backdrop": backdrop_url,
                "poster": poster_url,
            }
        return None

    # ------------------------------------------------------------------ Sub-fetchers

    def _fetch_continue_watching(self, uid, series_tmdb):
        fields = "ProviderIds,Overview,CommunityRating,ProductionYear,RunTimeTicks,UserData,ImageTags"
        resume_items = []
        nextup_items = []

        def _get_resume():
            qs = urllib.parse.urlencode({"Limit": "15", "Fields": fields, "MediaTypes": "Video"})
            d = self._jf_get(f"/Users/{uid}/Items/Resume?{qs}", timeout=6)
            return d.get("Items") if isinstance(d, dict) else []

        def _get_nextup():
            qs = urllib.parse.urlencode({"UserId": uid, "Limit": "15", "Fields": fields})
            d = self._jf_get(f"/Shows/NextUp?{qs}", timeout=6)
            return d.get("Items") if isinstance(d, dict) else []

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f_res = pool.submit(_get_resume)
            f_nxt = pool.submit(_get_nextup)
            try:
                resume_items = f_res.result() or []
            except Exception:
                resume_items = []
            try:
                nextup_items = f_nxt.result() or []
            except Exception:
                nextup_items = []

        out = []
        seen_items = set()
        seen_series = set()

        for raw in resume_items:
            norm = self._normalize_continue_item(raw, series_tmdb, is_next_up=False)
            if not norm:
                continue
            seen_items.add(norm["jellyfinItemId"])
            if norm.get("seriesId"):
                seen_series.add(norm["seriesId"])
            out.append(norm)

        for raw in nextup_items:
            norm = self._normalize_continue_item(raw, series_tmdb, is_next_up=True)
            if not norm:
                continue
            if norm["jellyfinItemId"] in seen_items:
                continue
            if norm.get("seriesId") and norm["seriesId"] in seen_series:
                continue
            seen_items.add(norm["jellyfinItemId"])
            if norm.get("seriesId"):
                seen_series.add(norm["seriesId"])
            out.append(norm)

        return out[:20]

    def _fetch_recently_added(self, uid, watchlist_ids):
        qs = urllib.parse.urlencode({
            "Limit": "18",
            "Fields": "ProviderIds,Overview,Genres,CommunityRating,ProductionYear,RunTimeTicks,UserData",
            "IncludeItemTypes": "Movie,Series",
        })
        data = self._jf_get(f"/Users/{uid}/Items/Latest?{qs}", timeout=6)
        raw_items = data if isinstance(data, list) else (data.get("Items") if isinstance(data, dict) else [])
        out = []
        for raw in raw_items or []:
            norm = self._normalize_jf_library_item(raw, watchlist_ids=watchlist_ids)
            if norm:
                out.append(norm)
        return out

    def _fetch_arr_queue_raw(self, cat, timeout=4):
        arr = self._arr_config_fn()
        if cat not in arr:
            return []
        app, base, conf = arr[cat]
        with open(conf, "r", encoding="utf-8") as f:
            m = re.search(r"<ApiKey>(\w+)</ApiKey>", f.read())
        if not m:
            return []
        key = m.group(1)
        q = "includeMovie=true" if app == "radarr" else "includeSeries=true&includeEpisode=true"
        req = urllib.request.Request(
            f"{base}/api/v3/queue?pageSize=200&{q}",
            headers={"X-Api-Key": key, "Accept": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(r.read() or b"{}")
        recs = data.get("records") if isinstance(data, dict) else []
        return recs if isinstance(recs, list) else []

    def _fetch_active_downloads(self, filter_tmdb=None, filter_media_type=None):
        radarr_recs = []
        sonarr_recs = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f_rad = pool.submit(self._fetch_arr_queue_raw, "radarr")
            f_son = pool.submit(self._fetch_arr_queue_raw, "tv-sonarr")
            try:
                radarr_recs = f_rad.result()
            except Exception:
                radarr_recs = []
            try:
                sonarr_recs = f_son.result()
            except Exception:
                sonarr_recs = []

        # Aggregate by (mediaType, tmdbId) so TV season packs show a clean unified card
        grouped = {}
        for source, recs in (("radarr", radarr_recs), ("sonarr", sonarr_recs)):
            for rec in recs:
                if not isinstance(rec, dict):
                    continue
                if source == "radarr":
                    media = rec.get("movie") if isinstance(rec.get("movie"), dict) else {}
                    mtype = "movie"
                    tid = _safe_int(media.get("tmdbId"))
                    title = media.get("title") or rec.get("title") or "Movie"
                    year = _safe_int(media.get("year"))
                    ep_label = None
                else:
                    media = rec.get("series") if isinstance(rec.get("series"), dict) else {}
                    ep = rec.get("episode") if isinstance(rec.get("episode"), dict) else {}
                    mtype = "tv"
                    tid = _safe_int(media.get("tmdbId"))
                    title = media.get("title") or rec.get("title") or "TV Show"
                    year = _safe_int(media.get("year"))
                    sn = _safe_int(ep.get("seasonNumber"))
                    en = _safe_int(ep.get("episodeNumber"))
                    ep_label = f"S{sn} · E{en}" if (sn is not None and en is not None) else None

                if not tid or tid <= 0:
                    continue
                if filter_tmdb is not None and tid != filter_tmdb:
                    continue
                if filter_media_type and mtype != filter_media_type:
                    continue

                size = max(0, _safe_int(rec.get("size")) or 0)
                size_left = max(0, _safe_int(rec.get("sizeleft")) or 0)
                eta_sec = _parse_timeleft_seconds(rec.get("timeleft"))
                raw_state = str(rec.get("trackedDownloadState") or rec.get("status") or "downloading").strip().lower()
                raw_status = str(rec.get("status") or "").strip().lower()
                if raw_state in ("importpending", "importing"):
                    status = "importing"
                elif raw_status in ("queued", "delay"):
                    status = "queued"
                elif raw_status in ("paused",):
                    status = "paused"
                elif raw_status in ("warning", "failed"):
                    status = "warning"
                else:
                    status = "downloading"

                images = media.get("images")
                poster = _arr_image_url(images, "poster", "w300")
                backdrop = _arr_image_url(images, "fanart", "w780")

                gkey = (mtype, tid)
                if gkey not in grouped:
                    grouped[gkey] = {
                        "tmdbId": tid,
                        "mediaType": mtype,
                        "title": _clip(title, 160),
                        "year": year,
                        "size": size,
                        "sizeLeft": size_left,
                        "etaSeconds": eta_sec,
                        "status": status,
                        "episodeLabels": [ep_label] if ep_label else [],
                        "count": 1,
                        "poster": poster,
                        "backdrop": backdrop,
                        "source": source,
                    }
                else:
                    g = grouped[gkey]
                    g["size"] += size
                    g["sizeLeft"] += size_left
                    g["count"] += 1
                    if ep_label and ep_label not in g["episodeLabels"]:
                        g["episodeLabels"].append(ep_label)
                    if eta_sec is not None:
                        g["etaSeconds"] = max(g["etaSeconds"] or 0, eta_sec)
                    if status == "downloading":
                        g["status"] = "downloading"

        items = []
        for g in grouped.values():
            size = g["size"]
            size_left = min(g["sizeLeft"], size) if size > 0 else g["sizeLeft"]
            pct = round(((size - size_left) / size) * 100.0, 1) if size > 0 else 0.0
            pct = max(0.0, min(100.0, pct))
            ep_labels = g["episodeLabels"]
            if len(ep_labels) == 1:
                ep_label = ep_labels[0]
            elif len(ep_labels) > 1 or g["count"] > 1:
                ep_label = f"{g['count']} episodes" if g["mediaType"] == "tv" else None
            else:
                ep_label = None
            items.append({
                "tmdbId": g["tmdbId"],
                "mediaType": g["mediaType"],
                "title": g["title"],
                "year": g["year"],
                "percent": pct,
                "status": g["status"],
                "eta": _format_eta(g["etaSeconds"]),
                "etaSeconds": g["etaSeconds"],
                "sizeLeft": size_left,
                "size": size,
                "episodeLabel": ep_label,
                "poster": g["poster"],
                "backdrop": g["backdrop"],
                "source": g["source"],
            })

        items.sort(key=lambda x: (0 if x["status"] == "downloading" else 1, -x["percent"], x["title"].lower()))
        return items

    def _fetch_rail(self, spec, lib_index, watchlist_ids):
        data = self._seerr_get(spec["path"], timeout=7)
        results = data.get("results") if isinstance(data, dict) else []
        items = []
        for raw in results if isinstance(results, list) else []:
            norm = self._normalize_seerr_item(
                raw,
                forced_type=spec.get("forcedType"),
                lib_index=lib_index,
                watchlist_ids=watchlist_ids,
            )
            if norm:
                items.append(norm)
        if not items:
            return None
        rail = {
            "id": spec["id"],
            "title": spec["title"],
            "category": spec["category"],
            "items": items[:18],
        }
        if spec.get("mediaType"):
            rail["mediaType"] = spec["mediaType"]
        if spec.get("genreId") is not None:
            rail["genreId"] = spec["genreId"]
        if spec.get("networkId") is not None:
            rail["networkId"] = spec["networkId"]
        return rail

    def _pick_hero(self, recently_added, rails, continue_watching, watchlist_ids):
        # 1. Prefer an available Jellyfin item with a crisp backdrop & overview
        for item in recently_added:
            if item.get("id", 0) > 0 and item.get("backdrop") and item.get("overview"):
                hero = dict(item)
                if hero.get("jellyfinItemId"):
                    hero["backdrop"] = _jf_img_url(hero["jellyfinItemId"], "Backdrop", max_width=1280)
                return hero
        # 2. Check trending items that are already available in Jellyfin
        trending = next((r["items"] for r in rails if r["id"] == "trending"), [])
        for item in trending:
            if item.get("status") == "available" and item.get("backdrop"):
                hero = dict(item)
                hero["backdrop"] = _tmdb_img(hero["backdrop"].replace("/w780/", "/original/"), size="w1280")
                return hero
        # 3. Top trending item with a backdrop
        for item in trending:
            if item.get("backdrop"):
                hero = dict(item)
                hero["backdrop"] = _tmdb_img(hero["backdrop"].replace("/w780/", "/original/"), size="w1280")
                return hero
        # 4. Any recently added item
        if recently_added:
            return dict(recently_added[0])
        # 5. Any rail item
        for r in rails:
            if r.get("items"):
                return dict(r["items"][0])
        # 6. Continue watching fallback
        if continue_watching:
            cw = continue_watching[0]
            key = _watchlist_key(cw["mediaType"], cw["id"]) if cw.get("id", 0) > 0 else ""
            return {
                "id": cw["id"],
                "mediaType": cw["mediaType"],
                "title": cw["title"],
                "year": cw.get("year"),
                "overview": cw.get("episodeTitle") or "",
                "poster": cw.get("poster"),
                "backdrop": cw.get("backdrop"),
                "voteAverage": None,
                "status": "available",
                "jellyfinItemId": cw.get("jellyfinItemId"),
                "inWatchlist": key in watchlist_ids,
                "genres": [],
            }
        return None

    # ------------------------------------------------------------------ Public API handlers

    def home(self, force_refresh=False):
        """GET /pc/api/watch/home"""
        now = time.time()
        if not force_refresh:
            with self._lock:
                ts, cached = self._home_cache
                if cached is not None and (now - ts) < HOME_CACHE_TTL_SEC:
                    return 200, cached

        watchlist_ids = self.watchlist.id_set()
        degraded = []

        # Step 1: fetch Jellyfin user + library index (fast, ~15-30ms) so rails & continueWatching can enrich accurately
        uid = None
        lib_index = {"by_tmdb": {}, "series_tmdb": {}, "total": 0}
        try:
            uid = self._jf_user_id(timeout=5)
            lib_index = self._library_index(force_refresh=force_refresh)
        except Exception:
            degraded.append("jellyfin")

        # Step 2: concurrently fetch Continue Watching, Recently Added, Active Downloads, and all Seerr Discovery Rails
        continue_watching = []
        recently_added = []
        downloads = []
        rails_by_idx = {}

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            f_cw = pool.submit(self._fetch_continue_watching, uid, lib_index["series_tmdb"]) if uid else None
            f_latest = pool.submit(self._fetch_recently_added, uid, watchlist_ids) if uid else None
            f_dl = pool.submit(self._fetch_active_downloads)
            rail_futures = {
                idx: pool.submit(self._fetch_rail, spec, lib_index, watchlist_ids)
                for idx, spec in enumerate(HOME_RAIL_SPECS)
            }

            if f_cw is not None:
                try:
                    continue_watching = f_cw.result()
                except Exception:
                    degraded.append("continue_watching")

            if f_latest is not None:
                try:
                    recently_added = f_latest.result()
                except Exception:
                    degraded.append("recently_added")

            try:
                downloads = f_dl.result()
            except Exception:
                degraded.append("downloads")

            for idx, fut in rail_futures.items():
                try:
                    rail = fut.result()
                    if rail:
                        rails_by_idx[idx] = rail
                except Exception:
                    degraded.append(HOME_RAIL_SPECS[idx]["id"])

        rails = [rails_by_idx[i] for i in sorted(rails_by_idx.keys())]
        hero = self._pick_hero(recently_added, rails, continue_watching, watchlist_ids)

        payload = {
            "hero": hero,
            "continueWatching": continue_watching,
            "recentlyAdded": recently_added,
            "downloads": downloads,
            "rails": rails,
            "watchlistIds": sorted(watchlist_ids),
            "libraryCount": lib_index.get("total", 0),
            "ts": int(time.time()),
            "degraded": degraded,
        }
        with self._lock:
            self._home_cache = (time.time(), payload)
        return 200, payload

    def discover(self, category="all", media_type=None, genre_id=None, network_id=None, page=1, sort=None):
        """GET /pc/api/watch/discover"""
        cat = str(category or "all").strip().lower()
        if cat not in ("all", "movies", "tv", "library", "watchlist", "genre", "network"):
            return 400, {"error": f"Unsupported discover category: {cat}"}

        page_num = max(1, min(50, _safe_int(page) or 1))
        mtype_param = str(media_type or "").strip().lower()
        mtype = mtype_param if mtype_param in ("movie", "tv") else None
        gid = _safe_int(genre_id)
        nid = _safe_int(network_id)
        sort_key = str(sort or "").strip().lower()

        watchlist_ids = self.watchlist.id_set()
        try:
            lib_index = self._library_index()
        except Exception:
            lib_index = {"by_tmdb": {}, "series_tmdb": {}, "total": 0}

        if cat == "library":
            return self._discover_library(mtype=mtype, page=page_num, sort=sort_key or "recent", watchlist_ids=watchlist_ids)

        if cat == "watchlist":
            return self._discover_watchlist(lib_index=lib_index, watchlist_ids=watchlist_ids)

        # Seerr-backed discovery
        seerr_sort = {
            "popular": "popularity.desc",
            "rating": "vote_average.desc",
            "recent": "first_air_date.desc" if (cat == "tv" or mtype == "tv") else "primary_release_date.desc",
        }.get(sort_key)

        params = {"page": str(page_num)}
        forced_type = None
        genres_type = None

        if cat == "all":
            path = f"/discover/trending?{urllib.parse.urlencode(params)}"
        elif cat == "movies":
            forced_type = "movie"
            genres_type = "movie"
            if gid and gid > 0:
                params["genre"] = str(gid)
            if seerr_sort:
                params["sortBy"] = seerr_sort
            path = f"/discover/movies?{urllib.parse.urlencode(params)}"
        elif cat == "tv":
            forced_type = "tv"
            genres_type = "tv"
            if gid and gid > 0:
                params["genre"] = str(gid)
            if nid and nid > 0:
                params["network"] = str(nid)
            if seerr_sort:
                params["sortBy"] = seerr_sort
            path = f"/discover/tv?{urllib.parse.urlencode(params)}"
        elif cat == "genre":
            if not gid or gid <= 0:
                return 400, {"error": "genreId required for category=genre"}
            target_mt = mtype or "movie"
            forced_type = target_mt
            genres_type = target_mt
            if target_mt == "tv" and gid == 16:
                params["sortBy"] = seerr_sort or "popularity.desc"
                params["genre"] = "16"
                params["language"] = "ja"
                path = f"/discover/tv?{urllib.parse.urlencode(params)}"
            else:
                seg = "tv" if target_mt == "tv" else "movies"
                path = f"/discover/{seg}/genre/{gid}?{urllib.parse.urlencode(params)}"
        else:  # network
            if not nid or nid <= 0:
                return 400, {"error": "networkId required for category=network"}
            forced_type = "tv"
            genres_type = "tv"
            path = f"/discover/tv/network/{nid}?{urllib.parse.urlencode(params)}"

        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                f_data = pool.submit(self._seerr_get, path, 10)
                f_genres = pool.submit(self._get_genres, genres_type) if genres_type else None
                data = f_data.result()
                genres = f_genres.result() if f_genres else []
        except urllib.error.HTTPError as e:
            return 502, {"error": f"Seerr discover failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Seerr discover failed: {e}", 240)}

        raw_results = data.get("results") if isinstance(data, dict) else []
        items = []
        for raw in raw_results if isinstance(raw_results, list) else []:
            norm = self._normalize_seerr_item(
                raw,
                forced_type=forced_type,
                lib_index=lib_index,
                watchlist_ids=watchlist_ids,
            )
            if norm:
                items.append(norm)

        return 200, {
            "category": cat,
            "page": _safe_int(data.get("page")) or page_num,
            "totalPages": _safe_int(data.get("totalPages")) or 1,
            "totalResults": _safe_int(data.get("totalResults")) or len(items),
            "items": items,
            "genres": genres,
        }

    def _discover_library(self, mtype=None, page=1, sort="recent", watchlist_ids=None):
        page_size = 30
        start_index = (page - 1) * page_size
        include_types = "Movie" if mtype == "movie" else ("Series" if mtype == "tv" else "Movie,Series")
        if sort == "title":
            sort_by, sort_order = "SortName", "Ascending"
        elif sort == "rating":
            sort_by, sort_order = "CommunityRating,SortName", "Descending"
        else:
            sort_by, sort_order = "DateCreated,SortName", "Descending"

        try:
            uid = self._jf_user_id(timeout=6)
            qs = urllib.parse.urlencode({
                "IncludeItemTypes": include_types,
                "Recursive": "true",
                "Fields": "ProviderIds,Overview,Genres,CommunityRating,ProductionYear,DateCreated",
                "SortBy": sort_by,
                "SortOrder": sort_order,
                "StartIndex": str(start_index),
                "Limit": str(page_size),
            })
            data = self._jf_get(f"/Users/{uid}/Items?{qs}", timeout=8)
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin library failed: {e}", 240)}

        raw_items = data.get("Items") if isinstance(data, dict) else []
        total = _safe_int(data.get("TotalRecordCount")) or len(raw_items or [])
        total_pages = max(1, (total + page_size - 1) // page_size)
        items = []
        for raw in raw_items or []:
            norm = self._normalize_jf_library_item(raw, watchlist_ids=watchlist_ids)
            if norm:
                items.append(norm)
        return 200, {
            "category": "library",
            "page": page,
            "totalPages": total_pages,
            "totalResults": total,
            "items": items,
            "genres": [],
        }

    def _discover_watchlist(self, lib_index, watchlist_ids):
        saved = self.watchlist.list_items()
        items = []
        for entry in saved:
            tid = _safe_int(entry.get("id"))
            if not tid:
                continue
            mtype = "tv" if entry.get("mediaType") == "tv" else "movie"
            jf_id = lib_index.get("by_tmdb", {}).get((mtype, tid))
            status = "available" if jf_id else "none"
            items.append({
                "id": tid,
                "mediaType": mtype,
                "title": entry.get("title") or "Untitled",
                "year": _safe_int(entry.get("year")),
                "overview": entry.get("overview") or "",
                "poster": entry.get("poster") or (_jf_img_url(jf_id, "Primary", max_width=400) if jf_id else None),
                "backdrop": entry.get("backdrop") or (_jf_img_url(jf_id, "Backdrop", max_width=780) if jf_id else None),
                "voteAverage": _vote_avg(entry.get("voteAverage")),
                "status": status,
                "jellyfinItemId": jf_id,
                "inWatchlist": True,
                "genres": [],
            })
        return 200, {
            "category": "watchlist",
            "page": 1,
            "totalPages": 1,
            "totalResults": len(items),
            "items": items,
            "genres": [],
        }

    def get_watchlist(self):
        """GET /pc/api/watch/watchlist"""
        watchlist_ids = self.watchlist.id_set()
        try:
            lib_index = self._library_index()
        except Exception:
            lib_index = {"by_tmdb": {}, "series_tmdb": {}, "total": 0}
        code, res = self._discover_watchlist(lib_index=lib_index, watchlist_ids=watchlist_ids)
        if code != 200:
            return code, res
        return 200, {
            "items": res["items"],
            "ids": sorted(watchlist_ids),
            "count": len(res["items"]),
        }

    def update_watchlist(self, body):
        """POST /pc/api/watch/watchlist"""
        tid = _safe_int(body.get("id") if body.get("id") is not None else body.get("tmdbId"))
        if not tid or tid <= 0:
            return 400, {"error": "Valid id (tmdbId) is required"}
        raw_mt = str(body.get("mediaType") or "movie").strip().lower()
        if raw_mt not in ("movie", "tv"):
            return 400, {"error": "mediaType must be movie or tv"}
        action = str(body.get("action") or "toggle").strip().lower()
        if action not in ("toggle", "add", "remove"):
            return 400, {"error": "action must be toggle, add, or remove"}

        meta = {
            "title": body.get("title"),
            "year": body.get("year"),
            "poster": body.get("poster"),
            "backdrop": body.get("backdrop"),
            "overview": body.get("overview"),
            "voteAverage": body.get("voteAverage"),
        }
        # Best-effort metadata enrichment from Seerr when adding without a title
        if action != "remove" and not str(meta.get("title") or "").strip():
            try:
                code, detail = self._seerr_client_fn().details(raw_mt, tid)
                if code == 200 and isinstance(detail, dict):
                    meta["title"] = detail.get("title")
                    meta["year"] = detail.get("year")
                    meta["poster"] = detail.get("poster")
                    meta["backdrop"] = detail.get("backdrop")
                    meta["overview"] = detail.get("overview")
                    meta["voteAverage"] = detail.get("voteAverage")
            except Exception:
                pass

        in_list, count, ids = self.watchlist.mutate(tid, raw_mt, action=action, meta=meta)
        self.invalidate_caches()
        return 200, {"ok": True, "inWatchlist": in_list, "count": count, "ids": ids}

    def episodes(self, tmdb_id_raw, season_raw):
        """GET /pc/api/watch/episodes?tmdbId=<id>&season=<n>"""
        tmdb_id = _safe_int(tmdb_id_raw)
        season_num = _safe_int(season_raw)
        if not tmdb_id or tmdb_id <= 0:
            return 400, {"error": "Valid tmdbId is required"}
        if season_num is None or season_num < 0:
            return 400, {"error": "Valid season number is required"}

        def _fetch_seerr_season():
            return self._seerr_get(f"/tv/{tmdb_id}/season/{season_num}", timeout=10)

        def _fetch_jf_episodes():
            uid = self._jf_user_id(timeout=5)
            lib_index = self._library_index()
            series_id = lib_index.get("by_tmdb", {}).get(("tv", tmdb_id))
            if not series_id:
                # Direct Jellyfin lookup in case the show was added within the index cache TTL
                qs_s = urllib.parse.urlencode({
                    "Recursive": "true",
                    "IncludeItemTypes": "Series",
                    "AnyProviderIdEquals": f"tmdb.{tmdb_id}",
                })
                sdata = self._jf_get(f"/Items?{qs_s}", timeout=6)
                sitems = sdata.get("Items") if isinstance(sdata, dict) else []
                if isinstance(sitems, list) and sitems and sitems[0].get("Id"):
                    series_id = str(sitems[0]["Id"])
            if not series_id:
                return None, []
            qs_e = urllib.parse.urlencode({
                "UserId": uid,
                "Season": str(season_num),
                "Fields": "Overview,UserData,RunTimeTicks,ProviderIds,ImageTags",
            })
            edata = self._jf_get(f"/Shows/{series_id}/Episodes?{qs_e}", timeout=8)
            eps = edata.get("Items") if isinstance(edata, dict) else []
            return series_id, (eps if isinstance(eps, list) else [])

        seerr_data = None
        seerr_err = None
        series_jf_id = None
        jf_eps_raw = []

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            f_seerr = pool.submit(_fetch_seerr_season)
            f_jf = pool.submit(_fetch_jf_episodes)
            try:
                seerr_data = f_seerr.result()
            except Exception as e:
                seerr_err = e
            try:
                series_jf_id, jf_eps_raw = f_jf.result()
            except Exception:
                series_jf_id, jf_eps_raw = None, []

        if seerr_data is None and not jf_eps_raw:
            msg = f"Failed to load episodes: {seerr_err}" if seerr_err else "Season not found"
            return 502 if seerr_err else 404, {"error": _clip(msg, 240)}

        jf_by_ep = {}
        for ep in jf_eps_raw:
            if not isinstance(ep, dict):
                continue
            en = _safe_int(ep.get("IndexNumber"))
            if en is None:
                continue
            jf_by_ep[en] = ep

        episodes_out = []
        seen_ep_nums = set()

        seerr_eps = seerr_data.get("episodes") if isinstance(seerr_data, dict) else []
        for raw in seerr_eps if isinstance(seerr_eps, list) else []:
            if not isinstance(raw, dict):
                continue
            en = _safe_int(raw.get("episodeNumber"))
            if en is None:
                continue
            seen_ep_nums.add(en)
            jf_ep = jf_by_ep.get(en)
            episodes_out.append(self._merge_episode(season_num, en, raw, jf_ep, series_jf_id))

        # Include any Jellyfin-only episodes that TMDB/Seerr didn't return
        for en in sorted(jf_by_ep.keys()):
            if en in seen_ep_nums:
                continue
            episodes_out.append(self._merge_episode(season_num, en, None, jf_by_ep[en], series_jf_id))

        episodes_out.sort(key=lambda e: e["episodeNumber"])
        available_count = sum(1 for e in episodes_out if e["available"])
        season_name = (
            (seerr_data.get("name") if isinstance(seerr_data, dict) else None)
            or (f"Specials" if season_num == 0 else f"Season {season_num}")
        )
        return 200, {
            "tmdbId": tmdb_id,
            "seasonNumber": season_num,
            "name": _clip(season_name, 120),
            "overview": _clip((seerr_data.get("overview") if isinstance(seerr_data, dict) else "") or "", 400),
            "seriesJellyfinId": series_jf_id,
            "availableCount": available_count,
            "totalCount": len(episodes_out),
            "episodes": episodes_out,
        }

    def _merge_episode(self, season_num, ep_num, seerr_ep, jf_ep, series_jf_id):
        seerr_ep = seerr_ep if isinstance(seerr_ep, dict) else {}
        jf_ep = jf_ep if isinstance(jf_ep, dict) else None

        jf_id = None
        played = False
        progress_pct = 0.0
        pos_ticks = 0
        run_ticks = 0
        jf_runtime_min = None

        if jf_ep is not None:
            raw_id = str(jf_ep.get("Id") or "").strip()
            if JELLYFIN_ITEM_ID_RE.match(raw_id):
                jf_id = raw_id
            ud = jf_ep.get("UserData") if isinstance(jf_ep.get("UserData"), dict) else {}
            played = bool(ud.get("Played"))
            pos_ticks = max(0, _safe_int(ud.get("PlaybackPositionTicks")) or 0)
            run_ticks = max(0, _safe_int(jf_ep.get("RunTimeTicks")) or 0)
            pct = _safe_float(ud.get("PlayedPercentage"))
            if pct is None and run_ticks > 0 and pos_ticks > 0:
                pct = (pos_ticks / run_ticks) * 100.0
            progress_pct = round(max(0.0, min(100.0, pct or 0.0)), 1)
            if run_ticks > 0:
                jf_runtime_min = max(1, int(round(run_ticks / TICKS_PER_SEC / 60.0)))

        still = _tmdb_img(seerr_ep.get("stillPath"), size="w500")
        if not still and jf_id:
            still = _jf_img_url(jf_id, "Primary", series_id=series_jf_id, max_width=500)

        runtime_min = _safe_int(seerr_ep.get("runtime")) or jf_runtime_min
        name = seerr_ep.get("name") or (jf_ep.get("Name") if jf_ep else None) or f"Episode {ep_num}"
        overview = seerr_ep.get("overview") or (jf_ep.get("Overview") if jf_ep else None) or ""
        air_date = seerr_ep.get("airDate") or None

        return {
            "episodeNumber": ep_num,
            "seasonNumber": season_num,
            "name": _clip(name, 160),
            "overview": _clip(overview, 320),
            "airDate": air_date,
            "still": still,
            "runtimeMinutes": runtime_min,
            "voteAverage": _vote_avg(seerr_ep.get("voteAverage")),
            "available": jf_id is not None,
            "jellyfinItemId": jf_id,
            "played": played,
            "progressPct": progress_pct,
            "positionTicks": pos_ticks,
            "runtimeTicks": run_ticks,
        }

    def downloads(self, tmdb_id_raw=None, media_type_raw=None):
        """GET /pc/api/watch/downloads"""
        tid = _safe_int(tmdb_id_raw) if tmdb_id_raw else None
        mt_raw = str(media_type_raw or "").strip().lower()
        mt = mt_raw if mt_raw in ("movie", "tv") else None
        items = self._fetch_active_downloads(filter_tmdb=tid, filter_media_type=mt)
        return 200, {"items": items, "ts": int(time.time())}
