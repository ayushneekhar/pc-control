"""Watch (Netflix-style media hub) backend endpoints for pc-api.

Aggregates live Jellyfin playback/library state, Seerr/TMDB discovery rails &
episode metadata, Radarr/Sonarr active download progress, and a persisted
My List / Watchlist store.

All upstream calls run concurrently with graceful per-section degradation so a
slow or offline upstream never breaks the Watch experience.
"""

import concurrent.futures
import json
import mimetypes
import os
import random
import re
import secrets
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
SEGMENTS_CACHE_TTL_SEC = 300.0
SEGMENTS_CACHE_MAX_ITEMS = 256

MEDIA_SEGMENT_TYPES_BY_LOWER = {
    "intro": "Intro",
    "outro": "Outro",
    "recap": "Recap",
    "preview": "Preview",
    "commercial": "Commercial",
}

JELLYFIN_ITEM_ID_RE = re.compile(r"^[0-9a-fA-F]{32}$")
JELLYFIN_MEDIA_SOURCE_ID_RE = re.compile(r"^[0-9a-zA-Z_-]{1,128}$")
API_KEY_PARAM_RE = re.compile(r"([?&])ApiKey=[^&\"'\s]+(&?)", re.IGNORECASE)
TMDB_IMG_BASE = "https://image.tmdb.org/t/p"
TMDB_ORIG_RE = re.compile(r"^https?://image\.tmdb\.org/t/p/original(/.*)$")

PLAYER_DEVICE_ID = "pc-control-watch"
PLAYER_CLIENT_NAME = "PC Control"
PLAYER_CLIENT_VERSION = "2.5.0"
MAX_HERO_ITEMS = 6
USERDATA_PROGRESS_THROTTLE_SEC = 2.0

TEXT_SUBTITLE_CODECS = frozenset({
    "subrip", "srt", "vtt", "webvtt", "ass", "ssa",
    "mov_text", "tx3g", "text", "ttml", "smi", "sami",
})
DIRECT_PLAY_CONTAINERS = frozenset({"mp4", "m4v", "mkv", "webm", "mov"})
DIRECT_PLAY_VIDEO_CODECS = frozenset({"h264", "avc", "hevc", "h265", "vp8", "vp9", "av1"})
DIRECT_PLAY_AUDIO_CODECS = frozenset({
    "aac", "mp3", "opus", "flac", "vorbis", "ac3", "eac3", "pcm", "alac",
    "truehd", "dts", "dca", "dtshd", "mlp",
})

QUALITY_PRESETS = (
    {"id": "original", "label": "Original (Direct)", "maxBitrate": 0, "maxWidth": None, "audioBitrate": 0},
    {"id": "1080p", "label": "1080p (8 Mbps)", "maxBitrate": 8_000_000, "maxWidth": 1920, "audioBitrate": 256_000},
    {"id": "720p", "label": "720p (4 Mbps)", "maxBitrate": 4_000_000, "maxWidth": 1280, "audioBitrate": 192_000},
    {"id": "480p", "label": "480p (1.5 Mbps)", "maxBitrate": 1_500_000, "maxWidth": 854, "audioBitrate": 128_000},
)

CONTAINER_MIME_TYPES = {
    "mp4": "video/mp4",
    "m4v": "video/mp4",
    "mkv": "video/x-matroska",
    "webm": "video/webm",
    "mov": "video/quicktime",
    "avi": "video/x-msvideo",
    "ts": "video/mp2t",
    "m2ts": "video/mp2t",
}

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


def _jf_img_url(item_id, image_type="Primary", series_id=None, max_width=None, image_index=None):
    if not isinstance(item_id, str) or not JELLYFIN_ITEM_ID_RE.match(item_id):
        return None
    params = {"type": image_type}
    if isinstance(series_id, str) and JELLYFIN_ITEM_ID_RE.match(series_id) and series_id != item_id:
        params["seriesId"] = series_id
    if isinstance(image_index, int) and not isinstance(image_index, bool) and image_index >= 0:
        params["imageIndex"] = str(image_index)
    if isinstance(max_width, int) and max_width > 0:
        params["maxWidth"] = str(max_width)
    return f"/pc/api/jellyfin/image/{item_id}?{urllib.parse.urlencode(params)}"


def _logo_url_from_item(item, jf_id):
    if not isinstance(item, dict) or not jf_id:
        return None
    img_tags = item.get("ImageTags")
    if isinstance(img_tags, dict) and img_tags.get("Logo"):
        return _jf_img_url(jf_id, "Logo", max_width=600)
    return None


def _format_media_size(size_bytes):
    sz = _safe_int(size_bytes)
    if not sz or sz <= 0:
        return None
    gb = 1024.0 ** 3
    mb = 1024.0 ** 2
    if sz >= gb:
        return f"{sz / gb:.1f} GB"
    if sz >= mb:
        return f"{int(round(sz / mb))} MB"
    return f"{max(1, sz // 1024)} KB"


def _normalize_resolution(width, height):
    w = _safe_int(width) or 0
    h = _safe_int(height) or 0
    if w >= 3800 or h >= 2100:
        return "4K"
    if w >= 2500 or h >= 1400:
        return "1440p"
    if w >= 1800 or h >= 1000:
        return "1080p"
    if w >= 1200 or h >= 700:
        return "720p"
    if w >= 800 or h >= 480:
        return "480p"
    if h > 0:
        return f"{h}p"
    return None


def _normalize_video_codec(codec):
    c = str(codec or "").strip().lower()
    if not c:
        return None
    return {
        "hevc": "HEVC",
        "h265": "HEVC",
        "h264": "H.264",
        "avc": "H.264",
        "av1": "AV1",
        "vp9": "VP9",
        "vp8": "VP8",
        "mpeg4": "MPEG-4",
        "mpeg2video": "MPEG-2",
        "vc1": "VC-1",
    }.get(c, c.upper())


def _normalize_hdr(stream):
    if not isinstance(stream, dict):
        return None
    vrange = str(stream.get("VideoRange") or "").strip().lower()
    vtype = str(stream.get("VideoRangeType") or "").strip().lower()
    dovi = str(stream.get("VideoDoViTitle") or "").strip().lower()
    disp = str(stream.get("DisplayTitle") or "").strip().lower()
    combined = " ".join(p for p in (vtype, dovi, disp) if p)
    if "dovi" in combined or "dolby vision" in combined:
        return "Dolby Vision"
    if "hdr10+" in combined or "hdr10plus" in combined:
        return "HDR10+"
    if "hdr10" in combined:
        return "HDR10"
    if "hlg" in combined:
        return "HLG"
    if vrange == "hdr":
        return "HDR"
    return None


def _normalize_audio_badge(stream):
    if not isinstance(stream, dict):
        return None
    raw_codec = str(stream.get("Codec") or "").strip().lower()
    profile = str(stream.get("Profile") or "").strip().lower()
    disp = str(stream.get("DisplayTitle") or "").strip().lower()
    if not raw_codec:
        return None
    if raw_codec == "truehd":
        codec_lbl = "TrueHD"
    elif raw_codec == "dtshd" or (raw_codec in ("dts", "dca") and ("hd" in profile or "dts-hd" in disp)):
        codec_lbl = "DTS-HD"
    elif raw_codec in ("dts", "dca"):
        codec_lbl = "DTS"
    elif raw_codec in ("eac3", "ec3"):
        codec_lbl = "EAC3"
    elif raw_codec == "ac3":
        codec_lbl = "AC3"
    elif raw_codec == "aac":
        codec_lbl = "AAC"
    elif raw_codec == "opus":
        codec_lbl = "Opus"
    elif raw_codec == "flac":
        codec_lbl = "FLAC"
    elif raw_codec == "alac":
        codec_lbl = "ALAC"
    elif raw_codec == "mp3":
        codec_lbl = "MP3"
    elif raw_codec == "vorbis":
        codec_lbl = "Vorbis"
    elif raw_codec.startswith("pcm"):
        codec_lbl = "PCM"
    else:
        codec_lbl = raw_codec.upper()

    layout = str(stream.get("ChannelLayout") or "").strip().lower()
    channels = _safe_int(stream.get("Channels"))
    ch_lbl = None
    if layout:
        m = re.match(r"^(\d+\.\d+)", layout)
        if m:
            ch_lbl = m.group(1)
        elif layout == "stereo":
            ch_lbl = "2.0"
        elif layout == "mono":
            ch_lbl = "1.0"
    if not ch_lbl and channels:
        ch_lbl = {8: "7.1", 6: "5.1", 2: "2.0", 1: "1.0"}.get(channels)

    if ch_lbl and codec_lbl != "Opus":
        return f"{codec_lbl} {ch_lbl}"
    if ch_lbl and codec_lbl == "Opus" and ch_lbl not in ("2.0", "1.0"):
        return f"{codec_lbl} {ch_lbl}"
    return codec_lbl


def extract_media_badges(media_source, include_size=True):
    """Build a concise mediaBadges summary dict from a Jellyfin MediaSource."""
    if not isinstance(media_source, dict):
        return None
    streams = media_source.get("MediaStreams")
    if not isinstance(streams, list):
        streams = []

    video_stream = None
    audio_stream = None
    for st in streams:
        if not isinstance(st, dict):
            continue
        stype = str(st.get("Type") or "")
        if stype == "Video" and video_stream is None:
            video_stream = st
        elif stype == "Audio":
            if audio_stream is None or (bool(st.get("IsDefault")) and not bool(audio_stream.get("IsDefault"))):
                audio_stream = st

    resolution = _normalize_resolution(
        video_stream.get("Width") if video_stream else None,
        video_stream.get("Height") if video_stream else None,
    )
    video_codec = _normalize_video_codec(video_stream.get("Codec") if video_stream else None)
    hdr = _normalize_hdr(video_stream)
    audio_codec = _normalize_audio_badge(audio_stream)
    raw_size = _safe_int(media_source.get("Size")) if include_size else None
    size_bytes = raw_size if (raw_size and raw_size > 0) else None
    size_label = _format_media_size(size_bytes) if size_bytes else None

    if not any((resolution, video_codec, hdr, audio_codec, size_bytes)):
        return None
    return {
        "resolution": resolution,
        "videoCodec": video_codec,
        "hdr": hdr,
        "audioCodec": audio_codec,
        "sizeBytes": size_bytes,
        "sizeLabel": size_label,
    }


def _parse_provider_tmdb(provider_ids):
    if not isinstance(provider_ids, dict):
        return None
    for k in ("Tmdb", "tmdb", "TMDB"):
        raw = provider_ids.get(k)
        val = _safe_int(raw)
        if val is not None and val > 0:
            return val
    return None


def _match_item_by_tmdb(items, tmdb_id):
    """Return the first Jellyfin item whose ProviderIds TMDB entry matches tmdb_id.

    Jellyfin can ignore ``AnyProviderIdEquals`` on ``/Items`` and return every item
    in the library, so callers must request ``Fields=ProviderIds`` and verify the
    returned item's ``ProviderIds`` matches ``str(tmdb_id)``.
    """
    if not isinstance(items, list):
        return None
    target = str(tmdb_id).strip()
    if not target:
        return None
    for item in items:
        if not isinstance(item, dict) or not item.get("Id"):
            continue
        provider_ids = item.get("ProviderIds")
        if not isinstance(provider_ids, dict):
            continue
        for k, v in provider_ids.items():
            if isinstance(k, str) and k.lower() == "tmdb" and v is not None:
                if str(v).strip() == target:
                    return item
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
        self._segments_cache = {}  # (jf_base_url, item_id) -> (ts, list)
        self._userdata_last_write = {}  # item_id -> (ts, pos_ticks)

    def invalidate_caches(self):
        with self._lock:
            self._home_cache = (0.0, None)
            self._library_index_cache = (0.0, None)

    def get_media_segments(self, item_id, uid=None, use_cache=True):
        """Fetch Jellyfin /MediaSegments/{itemId} with 5-minute in-memory TTL caching and graceful fallback."""
        norm_id = normalize_jf_id(item_id)
        if not norm_id:
            return []
        base_key = self._jellyfin_url_fn().rstrip("/")
        cache_key = (base_key, norm_id)
        now = time.time()
        if use_cache:
            with self._lock:
                cached = self._segments_cache.get(cache_key)
                if cached is not None and (now - cached[0]) < SEGMENTS_CACHE_TTL_SEC:
                    return list(cached[1])

        eff_uid = str(uid or "").strip() or None
        if not eff_uid:
            try:
                eff_uid = self._jf_user_id(timeout=4)
            except Exception:
                eff_uid = None

        path = f"/MediaSegments/{norm_id}"
        if eff_uid:
            path = f"{path}?{urllib.parse.urlencode({'userId': eff_uid})}"

        try:
            raw = self._jf_get(path, timeout=5)
            segments = parse_media_segments(raw)
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):
                segments = []
            else:
                return []
        except Exception:
            return []

        with self._lock:
            if len(self._segments_cache) >= SEGMENTS_CACHE_MAX_ITEMS:
                cutoff = time.time() - SEGMENTS_CACHE_TTL_SEC
                expired = [k for k, (ts, _) in self._segments_cache.items() if ts < cutoff]
                for k in expired:
                    self._segments_cache.pop(k, None)
                if len(self._segments_cache) >= SEGMENTS_CACHE_MAX_ITEMS:
                    oldest = min(self._segments_cache.items(), key=lambda kv: kv[1][0])[0]
                    self._segments_cache.pop(oldest, None)
            self._segments_cache[cache_key] = (time.time(), segments)
        return list(segments)

    # ------------------------------------------------------------------ HTTP helpers

    def jellyfin_auth_headers(self, include_client_meta=False):
        key = self._jellyfin_key_fn()
        if include_client_meta:
            auth = (
                f'MediaBrowser Client="{PLAYER_CLIENT_NAME}", '
                f'Device="Android", '
                f'DeviceId="{PLAYER_DEVICE_ID}", '
                f'Version="{PLAYER_CLIENT_VERSION}", '
                f'Token="{key}"'
            )
        else:
            auth = f'MediaBrowser Token="{key}"'
        return {"Authorization": auth}

    def _jf_request(self, method, path, body=None, timeout=8, include_client_meta=False):
        base = self._jellyfin_url_fn().rstrip("/")
        headers = self.jellyfin_auth_headers(include_client_meta=include_client_meta)
        headers["Accept"] = "application/json"
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif method in ("POST", "PUT"):
            data = b""
        req = urllib.request.Request(f"{base}{path}", data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
        return json.loads(raw) if raw.strip() else {}

    def _jf_get(self, path, timeout=8):
        return self._jf_request("GET", path, timeout=timeout)

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
        """Returns dict: by_tmdb {(mediaType, tmdbId): jfId}, series_tmdb {jfSeriesId: tmdbId}, has_logo set, total int."""
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
            "Fields": "ProviderIds,ImageTags",
            "Limit": "1000",
        })
        data = self._jf_get(f"/Users/{uid}/Items?{qs}", timeout=8)
        items = data.get("Items") if isinstance(data, dict) else []
        by_tmdb = {}
        series_tmdb = {}
        has_logo = set()
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
            img_tags = it.get("ImageTags")
            if isinstance(img_tags, dict) and img_tags.get("Logo"):
                has_logo.add(jf_id)
            tid = _parse_provider_tmdb(it.get("ProviderIds"))
            if tid:
                by_tmdb[(mtype, tid)] = jf_id
                if mtype == "tv":
                    series_tmdb[jf_id] = tid

        index = {
            "by_tmdb": by_tmdb,
            "series_tmdb": series_tmdb,
            "has_logo": has_logo,
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
        logo_url = None
        if lib_index:
            jf_id = lib_index.get("by_tmdb", {}).get((mtype, tid))
            if jf_id and status in ("none", "deleted", "requested", "partial"):
                status = "available"
            if jf_id and jf_id in (lib_index.get("has_logo") or ()):
                logo_url = _jf_img_url(jf_id, "Logo", max_width=600)

        key = _watchlist_key(mtype, tid)
        return {
            "id": tid,
            "mediaType": mtype,
            "title": _clip(title, 160),
            "year": _year(x.get("releaseDate") or x.get("firstAirDate")),
            "overview": _clip(x.get("overview") or "", 280),
            "poster": _tmdb_img(x.get("posterPath"), size="w500" if hero_quality else "w300"),
            "backdrop": _tmdb_img(x.get("backdropPath"), size="w1280" if hero_quality else "w780"),
            "logoUrl": logo_url,
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
        logo_url = _logo_url_from_item(it, jf_id)
        genres = [str(g) for g in (it.get("Genres") or []) if isinstance(g, str) and g.strip()][:5]

        key = _watchlist_key(mtype, tid) if tid > 0 else ""
        ud = it.get("UserData") if isinstance(it.get("UserData"), dict) else {}
        played = bool(ud.get("Played"))
        pos_ticks = 0 if played else max(0, _safe_int(ud.get("PlaybackPositionTicks")) or 0)
        run_ticks = max(0, _safe_int(it.get("RunTimeTicks")) or 0)
        return {
            "id": tid,
            "mediaType": mtype,
            "title": _clip(it.get("Name") or "Untitled", 160),
            "year": _safe_int(it.get("ProductionYear")),
            "overview": _clip(it.get("Overview") or "", 280),
            "poster": poster_url,
            "backdrop": backdrop_url,
            "logoUrl": logo_url,
            "voteAverage": _vote_avg(it.get("CommunityRating")),
            "status": "available",
            "jellyfinItemId": jf_id,
            "played": played,
            "inWatchlist": bool(key and watchlist_ids is not None and key in watchlist_ids),
            "genres": genres,
            "positionTicks": pos_ticks,
            "runtimeTicks": run_ticks,
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
            "Fields": "ProviderIds,Overview,Genres,CommunityRating,ProductionYear,RunTimeTicks,UserData,ImageTags",
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

    def _upgrade_hero_item(self, item, cw_by_tmdb, cw_by_jf):
        hero = dict(item)
        bd = hero.get("backdrop")
        if hero.get("jellyfinItemId") and isinstance(bd, str) and bd.startswith("/pc/api/jellyfin/image/"):
            hero["backdrop"] = _jf_img_url(hero["jellyfinItemId"], "Backdrop", max_width=1280)
        elif isinstance(bd, str) and "/w780/" in bd:
            hero["backdrop"] = _tmdb_img(bd.replace("/w780/", "/original/"), size="w1280")

        # Enrich with live Continue Watching resume ticks if available
        mtype = hero.get("mediaType")
        tid = _safe_int(hero.get("id")) or 0
        jf_id = hero.get("jellyfinItemId")
        cw = None
        if tid > 0 and (mtype, tid) in cw_by_tmdb:
            cw = cw_by_tmdb[(mtype, tid)]
        elif jf_id and jf_id in cw_by_jf:
            cw = cw_by_jf[jf_id]

        if cw is not None:
            cw_pos = max(0, _safe_int(cw.get("positionTicks")) or 0)
            cw_run = max(0, _safe_int(cw.get("runtimeTicks")) or 0)
            if cw_pos > 0:
                hero["positionTicks"] = cw_pos
                if cw_run > 0:
                    hero["runtimeTicks"] = cw_run
                if cw.get("episodeLabel"):
                    hero["resumeEpisodeLabel"] = cw["episodeLabel"]
                if cw.get("jellyfinItemId"):
                    hero["resumeJellyfinItemId"] = cw["jellyfinItemId"]
        return hero

    def _pick_hero_items(self, recently_added, rails, continue_watching, watchlist_ids, limit=MAX_HERO_ITEMS):
        cw_by_tmdb = {}
        cw_by_jf = {}
        for cw in continue_watching or []:
            if not isinstance(cw, dict):
                continue
            pos = max(0, _safe_int(cw.get("positionTicks")) or 0)
            if pos <= 0:
                continue
            tid = _safe_int(cw.get("id")) or 0
            mt = cw.get("mediaType")
            if tid > 0 and mt in ("movie", "tv") and (mt, tid) not in cw_by_tmdb:
                cw_by_tmdb[(mt, tid)] = cw
            for key_id in (cw.get("seriesId"), cw.get("jellyfinItemId")):
                if key_id and key_id not in cw_by_jf:
                    cw_by_jf[key_id] = cw

        picked = []
        seen = set()

        def _dedupe_key(it):
            mt = it.get("mediaType") or "movie"
            tid = _safe_int(it.get("id")) or 0
            if tid > 0:
                return (mt, tid)
            jf_id = it.get("jellyfinItemId")
            if jf_id:
                return ("jf", jf_id)
            return ("title", mt, str(it.get("title") or "").strip().lower())

        def _try_add(it):
            if len(picked) >= limit or not isinstance(it, dict):
                return False
            k = _dedupe_key(it)
            if k in seen:
                return False
            seen.add(k)
            picked.append(self._upgrade_hero_item(it, cw_by_tmdb, cw_by_jf))
            return True

        trending = next((r["items"] for r in rails if r.get("id") == "trending"), [])

        # 1. Up to 3 best available Jellyfin library highlights with crisp backdrops & overview
        lib_added = 0
        for item in recently_added:
            if lib_added >= 3 or len(picked) >= limit:
                break
            if item.get("id", 0) > 0 and item.get("backdrop") and item.get("overview"):
                if _try_add(item):
                    lib_added += 1

        # 2. Trending items that are already available in Jellyfin with a backdrop
        for item in trending:
            if len(picked) >= limit:
                break
            if item.get("status") == "available" and item.get("backdrop"):
                _try_add(item)

        # 3. Top trending items with a backdrop & overview
        for item in trending:
            if len(picked) >= limit:
                break
            if item.get("backdrop") and item.get("overview"):
                _try_add(item)

        # 4. Any trending item with a backdrop
        for item in trending:
            if len(picked) >= limit:
                break
            if item.get("backdrop"):
                _try_add(item)

        # 5. Remaining recently added items with a backdrop
        for item in recently_added:
            if len(picked) >= limit:
                break
            if item.get("backdrop"):
                _try_add(item)

        # 6. Other rail items with a backdrop
        for r in rails:
            if len(picked) >= limit:
                break
            if r.get("id") == "trending":
                continue
            for item in r.get("items") or []:
                if len(picked) >= limit:
                    break
                if item.get("backdrop"):
                    _try_add(item)

        # 7. Fallbacks without strict backdrop requirement
        if not picked:
            for item in recently_added:
                if _try_add(item) and len(picked) >= limit:
                    break
        if not picked:
            for r in rails:
                for item in r.get("items") or []:
                    if _try_add(item) and len(picked) >= limit:
                        break
                if len(picked) >= limit:
                    break
        if not picked and continue_watching:
            for cw in continue_watching:
                if len(picked) >= limit:
                    break
                key = _watchlist_key(cw["mediaType"], cw["id"]) if cw.get("id", 0) > 0 else ""
                _try_add({
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
                    "positionTicks": max(0, _safe_int(cw.get("positionTicks")) or 0),
                    "runtimeTicks": max(0, _safe_int(cw.get("runtimeTicks")) or 0),
                    "resumeEpisodeLabel": cw.get("episodeLabel"),
                    "resumeJellyfinItemId": cw.get("jellyfinItemId"),
                })

        return picked[:limit]

    def _pick_hero(self, recently_added, rails, continue_watching, watchlist_ids):
        items = self._pick_hero_items(recently_added, rails, continue_watching, watchlist_ids, limit=1)
        return items[0] if items else None

    def get_resume_state(self, media_type, tmdb_id):
        """Best-effort live Jellyfin enrichment for detail screens (resume, ClearLogo, mediaBadges, watched state)."""
        mt = "tv" if str(media_type or "").strip().lower() == "tv" else "movie"
        tid = _safe_int(tmdb_id)
        if not tid or tid <= 0:
            return None
        try:
            uid = self._jf_user_id(timeout=3)
            lib_index = self._library_index()
            jf_id = lib_index.get("by_tmdb", {}).get((mt, tid))
            if not jf_id:
                return None

            if mt == "movie":
                it = self._jf_get(
                    f"/Users/{uid}/Items/{jf_id}?Fields=UserData,RunTimeTicks,ImageTags,MediaSources,MediaStreams",
                    timeout=4,
                )
                if not isinstance(it, dict):
                    return None
                ud = it.get("UserData") if isinstance(it.get("UserData"), dict) else {}
                played = bool(ud.get("Played"))
                pos_ticks = 0 if played else max(0, _safe_int(ud.get("PlaybackPositionTicks")) or 0)
                run_ticks = max(0, _safe_int(it.get("RunTimeTicks")) or 0)
                ms_list = it.get("MediaSources") if isinstance(it.get("MediaSources"), list) else []
                ms = ms_list[0] if ms_list and isinstance(ms_list[0], dict) else None
                return {
                    "jellyfinItemId": jf_id,
                    "played": played,
                    "logoUrl": _logo_url_from_item(it, jf_id),
                    "mediaBadges": extract_media_badges(ms, include_size=True),
                    "resumePositionTicks": pos_ticks,
                    "resumeRuntimeTicks": run_ticks,
                    "resumeJellyfinItemId": jf_id,
                    "resumeEpisodeLabel": None,
                    "resumeSeasonNumber": None,
                    "resumeEpisodeNumber": None,
                }

            # TV show: fetch series metadata (ClearLogo + watched) alongside Resume/NextUp episode
            def _fetch_series_meta():
                try:
                    return self._jf_get(f"/Users/{uid}/Items/{jf_id}?Fields=UserData,ImageTags", timeout=4)
                except Exception:
                    return None

            def _fetch_tv_episode():
                qs_res = urllib.parse.urlencode({
                    "ParentId": jf_id,
                    "IncludeItemTypes": "Episode",
                    "Recursive": "true",
                    "Limit": "1",
                    "Fields": "UserData,RunTimeTicks,MediaSources,MediaStreams",
                })
                rdata = self._jf_get(f"/Users/{uid}/Items/Resume?{qs_res}", timeout=4)
                ritems = rdata.get("Items") if isinstance(rdata, dict) else []
                ep = ritems[0] if isinstance(ritems, list) and ritems and isinstance(ritems[0], dict) else None
                if ep is None:
                    qs_nu = urllib.parse.urlencode({
                        "UserId": uid,
                        "SeriesId": jf_id,
                        "Limit": "1",
                        "Fields": "UserData,RunTimeTicks,MediaSources,MediaStreams",
                    })
                    ndata = self._jf_get(f"/Shows/NextUp?{qs_nu}", timeout=4)
                    nitems = ndata.get("Items") if isinstance(ndata, dict) else []
                    ep = nitems[0] if isinstance(nitems, list) and nitems and isinstance(nitems[0], dict) else None
                if ep is None:
                    qs_first = urllib.parse.urlencode({
                        "UserId": uid,
                        "Limit": "1",
                        "Fields": "UserData,RunTimeTicks,MediaSources,MediaStreams",
                    })
                    fdata = self._jf_get(f"/Shows/{jf_id}/Episodes?{qs_first}", timeout=4)
                    fitems = fdata.get("Items") if isinstance(fdata, dict) else []
                    ep = fitems[0] if isinstance(fitems, list) and fitems and isinstance(fitems[0], dict) else None
                    if ep is not None:
                        return ep, False
                return ep, True

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                f_series = pool.submit(_fetch_series_meta)
                f_ep = pool.submit(_fetch_tv_episode)
                series_meta = f_series.result()
                try:
                    ep, is_resume_or_nextup = f_ep.result()
                except Exception:
                    ep, is_resume_or_nextup = None, False

            series_ud = series_meta.get("UserData") if isinstance(series_meta, dict) and isinstance(series_meta.get("UserData"), dict) else {}
            logo_url = _logo_url_from_item(series_meta, jf_id) if isinstance(series_meta, dict) else (
                _jf_img_url(jf_id, "Logo", max_width=600) if jf_id in (lib_index.get("has_logo") or ()) else None
            )
            ms_list = ep.get("MediaSources") if isinstance(ep, dict) and isinstance(ep.get("MediaSources"), list) else []
            ms = ms_list[0] if ms_list and isinstance(ms_list[0], dict) else None
            media_badges = extract_media_badges(ms, include_size=False)

            out = {
                "jellyfinItemId": jf_id,
                "played": bool(series_ud.get("Played")),
                "logoUrl": logo_url,
                "mediaBadges": media_badges,
            }
            if ep is not None and is_resume_or_nextup:
                ep_id = normalize_jf_id(ep.get("Id"))
                ud = ep.get("UserData") if isinstance(ep.get("UserData"), dict) else {}
                played = bool(ud.get("Played"))
                pos_ticks = 0 if played else max(0, _safe_int(ud.get("PlaybackPositionTicks")) or 0)
                run_ticks = max(0, _safe_int(ep.get("RunTimeTicks")) or 0)
                sn = _safe_int(ep.get("ParentIndexNumber"))
                en = _safe_int(ep.get("IndexNumber"))
                if sn is not None and en is not None:
                    ep_label = f"S{sn} · E{en}"
                elif en is not None:
                    ep_label = f"E{en}"
                else:
                    ep_label = None
                out.update({
                    "resumePositionTicks": pos_ticks,
                    "resumeRuntimeTicks": run_ticks,
                    "resumeJellyfinItemId": ep_id,
                    "resumeEpisodeLabel": ep_label,
                    "resumeSeasonNumber": sn,
                    "resumeEpisodeNumber": en,
                })
            return out
        except Exception:
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
        hero_items = self._pick_hero_items(recently_added, rails, continue_watching, watchlist_ids)
        hero = hero_items[0] if hero_items else None

        payload = {
            "hero": hero,
            "heroItems": hero_items,
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

    def _normalize_box_set(self, it):
        if not isinstance(it, dict):
            return None
        jf_id = normalize_jf_id(it.get("Id"))
        if not jf_id:
            return None
        img_tags = it.get("ImageTags") if isinstance(it.get("ImageTags"), dict) else {}
        has_bd = bool(it.get("BackdropImageTags"))
        return {
            "id": jf_id,
            "name": _clip(it.get("Name") or "Collection", 160),
            "overview": _clip(it.get("Overview") or "", 400),
            "itemCount": max(0, _safe_int(it.get("ChildCount")) or 0),
            "poster": _jf_img_url(jf_id, "Primary", max_width=400) if img_tags.get("Primary") else None,
            "backdrop": _jf_img_url(jf_id, "Backdrop", max_width=780) if has_bd else None,
            "logoUrl": _jf_img_url(jf_id, "Logo", max_width=600) if img_tags.get("Logo") else None,
        }

    def _fetch_box_sets(self, uid):
        qs = urllib.parse.urlencode({
            "Recursive": "true",
            "IncludeItemTypes": "BoxSet",
            "Fields": "Overview,ChildCount,ImageTags,BackdropImageTags",
            "SortBy": "SortName",
            "SortOrder": "Ascending",
            "Limit": "100",
        })
        data = self._jf_get(f"/Users/{uid}/Items?{qs}", timeout=8)
        raw_items = data.get("Items") if isinstance(data, dict) else []
        out = []
        for raw in raw_items if isinstance(raw_items, list) else []:
            norm = self._normalize_box_set(raw)
            if norm:
                out.append(norm)
        return out

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

        collections = []
        try:
            uid = self._jf_user_id(timeout=6)
            qs = urllib.parse.urlencode({
                "IncludeItemTypes": include_types,
                "Recursive": "true",
                "Fields": "ProviderIds,Overview,Genres,CommunityRating,ProductionYear,DateCreated,ImageTags,UserData,RunTimeTicks",
                "SortBy": sort_by,
                "SortOrder": sort_order,
                "StartIndex": str(start_index),
                "Limit": str(page_size),
            })
            if page == 1 and mtype != "tv":
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    f_items = pool.submit(self._jf_get, f"/Users/{uid}/Items?{qs}", 8)
                    f_cols = pool.submit(self._fetch_box_sets, uid)
                    data = f_items.result()
                    try:
                        collections = f_cols.result()
                    except Exception:
                        collections = []
            else:
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
            "collections": collections,
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
                    "Fields": "ProviderIds",
                })
                sdata = self._jf_get(f"/Items?{qs_s}", timeout=6)
                sitems = sdata.get("Items") if isinstance(sdata, dict) else []
                matched = _match_item_by_tmdb(sitems, tmdb_id)
                if matched:
                    series_id = str(matched["Id"])
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

    # ------------------------------------------------------------------ Power Pack (v2.5.0)

    def set_watched(self, body):
        """POST /pc/api/watch/watched"""
        if not isinstance(body, dict):
            return 400, {"error": "JSON object required"}
        item_id = normalize_jf_id(body.get("jellyfinItemId"))
        if not item_id:
            return 400, {"error": "Valid jellyfinItemId is required"}
        played = body.get("played")
        if not isinstance(played, bool):
            return 400, {"error": "played must be true or false"}

        try:
            uid = self._jf_user_id(timeout=5)
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin user lookup failed: {e}", 240)}

        method = "POST" if played else "DELETE"
        try:
            self._jf_request(
                method,
                f"/Users/{uid}/PlayedItems/{item_id}",
                body=None,
                timeout=8,
                include_client_meta=True,
            )
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {"error": "Jellyfin item not found"}
            return 502, {"error": f"Jellyfin watched update failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin watched update failed: {e}", 240)}

        self.invalidate_caches()
        return 200, {"ok": True, "jellyfinItemId": item_id, "played": played}

    def surprise_me(self):
        """GET /pc/api/watch/surprise"""
        watchlist_ids = self.watchlist.id_set()
        try:
            uid = self._jf_user_id(timeout=5)
            qs = urllib.parse.urlencode({
                "IncludeItemTypes": "Movie",
                "Recursive": "true",
                "Fields": "ProviderIds,Overview,Genres,CommunityRating,ProductionYear,RunTimeTicks,UserData,ImageTags",
                "Limit": "500",
            })
            data = self._jf_get(f"/Users/{uid}/Items?{qs}", timeout=8)
        except urllib.error.HTTPError as e:
            return 502, {"error": f"Jellyfin surprise lookup failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin surprise lookup failed: {e}", 240)}

        raw_items = data.get("Items") if isinstance(data, dict) else []
        movies = []
        for raw in raw_items if isinstance(raw_items, list) else []:
            norm = self._normalize_jf_library_item(raw, watchlist_ids=watchlist_ids, hero_quality=True)
            if norm and norm.get("mediaType") == "movie":
                movies.append(norm)

        if not movies:
            return 404, {"error": "No movies found in Jellyfin library"}

        unwatched = [m for m in movies if not m.get("played")]
        # Prefer items with valid TMDB ID so tapping opens rich Detail screen seamlessly
        unwatched_with_tmdb = [m for m in unwatched if (m.get("id") or 0) > 0]
        all_with_tmdb = [m for m in movies if (m.get("id") or 0) > 0]
        pool = unwatched_with_tmdb or unwatched or all_with_tmdb or movies
        chosen = random.choice(pool)
        return 200, {
            "item": chosen,
            "unwatchedCount": len(unwatched),
            "totalMovies": len(movies),
        }

    def list_collections(self):
        """GET /pc/api/watch/collections"""
        try:
            uid = self._jf_user_id(timeout=5)
            cols = self._fetch_box_sets(uid)
        except urllib.error.HTTPError as e:
            return 502, {"error": f"Jellyfin collections failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin collections failed: {e}", 240)}
        return 200, {"collections": cols, "count": len(cols)}

    def collection_items(self, box_set_id_raw):
        """GET /pc/api/watch/collections/{boxSetId}"""
        box_id = normalize_jf_id(box_set_id_raw)
        if not box_id:
            return 400, {"error": "Valid collection ID is required"}
        watchlist_ids = self.watchlist.id_set()
        try:
            uid = self._jf_user_id(timeout=5)
            qs_children = urllib.parse.urlencode({
                "ParentId": box_id,
                "Fields": "ProviderIds,Overview,Genres,CommunityRating,ProductionYear,RunTimeTicks,UserData,ImageTags",
                "SortBy": "PremiereDate,ProductionYear,SortName",
                "SortOrder": "Ascending",
                "Limit": "200",
            })
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                f_meta = pool.submit(
                    self._jf_get,
                    f"/Users/{uid}/Items/{box_id}?Fields=Overview,ChildCount,ImageTags,BackdropImageTags",
                    8,
                )
                f_kids = pool.submit(self._jf_get, f"/Users/{uid}/Items?{qs_children}", 8)
                meta_raw = f_meta.result()
                kids_raw = f_kids.result()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {"error": "Collection not found"}
            return 502, {"error": f"Jellyfin collection lookup failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin collection lookup failed: {e}", 240)}

        col_meta = self._normalize_box_set(meta_raw)
        if not col_meta:
            return 404, {"error": "Collection not found"}

        raw_kids = kids_raw.get("Items") if isinstance(kids_raw, dict) else []
        items = []
        for raw in raw_kids if isinstance(raw_kids, list) else []:
            norm = self._normalize_jf_library_item(raw, watchlist_ids=watchlist_ids)
            if norm:
                items.append(norm)
        if col_meta["itemCount"] == 0 and items:
            col_meta["itemCount"] = len(items)
        return 200, {"collection": col_meta, "items": items}

    def search_subtitles(self, jellyfin_item_id_raw, language_raw="eng"):
        """GET /pc/api/watch/subtitles/search?jellyfinItemId=<id>&language=eng"""
        item_id = normalize_jf_id(jellyfin_item_id_raw)
        if not item_id:
            return 400, {"error": "Valid jellyfinItemId is required"}
        lang = str(language_raw or "eng").strip().lower()
        if not re.fullmatch(r"[a-z]{2,3}", lang):
            lang = "eng"

        try:
            raw_results = self._jf_get(f"/Items/{item_id}/RemoteSearch/Subtitles/{lang}", timeout=15)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {"error": "Jellyfin item not found"}
            return 502, {"error": f"Jellyfin subtitle search failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin subtitle search failed: {e}", 240)}

        results = []
        for entry in raw_results if isinstance(raw_results, list) else []:
            if not isinstance(entry, dict):
                continue
            sid = str(entry.get("Id") or "").strip()
            if not sid:
                continue
            results.append({
                "id": sid,
                "name": _clip(entry.get("Name") or "Subtitle", 180),
                "format": str(entry.get("Format") or "").strip().lower(),
                "language": str(entry.get("ThreeLetterISOLanguageName") or entry.get("Language") or lang).strip().lower(),
                "downloadCount": _safe_int(entry.get("DownloadCount")),
                "communityRating": _vote_avg(entry.get("CommunityRating")),
                "isHashMatch": bool(entry.get("IsHashMatch")),
                "providerName": _clip(entry.get("ProviderName") or "", 60),
            })

        results.sort(
            key=lambda s: (
                0 if s["isHashMatch"] else 1,
                -(s["downloadCount"] or 0),
                -(s["communityRating"] or 0.0),
                s["name"].lower(),
            )
        )
        return 200, {
            "jellyfinItemId": item_id,
            "language": lang,
            "results": results[:25],
        }

    def download_subtitle(self, body):
        """POST /pc/api/watch/subtitles/download"""
        if not isinstance(body, dict):
            return 400, {"error": "JSON object required"}
        item_id = normalize_jf_id(body.get("jellyfinItemId"))
        if not item_id:
            return 400, {"error": "Valid jellyfinItemId is required"}
        sub_id = str(body.get("subtitleId") or "").strip()
        if not sub_id or len(sub_id) > 512:
            return 400, {"error": "Valid subtitleId is required"}

        try:
            uid = self._jf_user_id(timeout=5)
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin user lookup failed: {e}", 240)}

        quoted_sub = urllib.parse.quote(sub_id, safe="")
        try:
            self._jf_request(
                "POST",
                f"/Items/{item_id}/RemoteSearch/Subtitles/{quoted_sub}",
                body=None,
                timeout=15,
                include_client_meta=True,
            )
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {"error": "Subtitle or Jellyfin item not found"}
            return 502, {"error": f"Jellyfin subtitle download failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin subtitle download failed: {e}", 240)}

        # Re-read updated subtitleTracks so the player can immediately attach the newly downloaded subtitle
        subtitle_tracks = []
        try:
            fields = "MediaSources,MediaStreams,Overview,ProviderIds,ImageTags,ParentBackdropItemId,SeriesId"
            item = self._jf_get(f"/Users/{uid}/Items/{item_id}?Fields={fields}", timeout=8)
            if isinstance(item, dict) and item.get("Id"):
                media_sources = item.get("MediaSources") if isinstance(item.get("MediaSources"), list) else []
                ms = media_sources[0] if media_sources and isinstance(media_sources[0], dict) else {}
                ctx = self._extract_item_media_context(uid, item, ms)
                subtitle_tracks = ctx["subtitleTracks"]
        except Exception:
            subtitle_tracks = []

        return 200, {
            "ok": True,
            "jellyfinItemId": item_id,
            "subtitleId": sub_id,
            "subtitleTracks": subtitle_tracks,
        }

    # ------------------------------------------------------------------ Phase 2: Native Playback & Offline Downloads

    def _resolve_playable_item_id(self, uid, jellyfin_item_id=None, tmdb_id=None, media_type=None, season=None, episode=None):
        """Resolve a playable Jellyfin Movie or Episode item ID."""
        raw_id = normalize_jf_id(jellyfin_item_id)
        if raw_id:
            # Verify whether the provided ID is a Series; if so, pick NextUp or first Episode
            try:
                it = self._jf_get(f"/Users/{uid}/Items/{raw_id}", timeout=6)
                if isinstance(it, dict) and str(it.get("Type") or "") == "Series":
                    ep_id = self._resolve_series_episode_id(uid, raw_id, season=season, episode=episode)
                    if ep_id:
                        return ep_id
            except Exception:
                pass
            return raw_id

        tid = _safe_int(tmdb_id)
        if not tid or tid <= 0:
            return None

        mt = "tv" if str(media_type or "").strip().lower() in ("tv", "series", "episode") else "movie"
        lib_index = {}
        try:
            lib_index = self._library_index()
        except Exception:
            lib_index = {"by_tmdb": {}, "series_tmdb": {}, "total": 0}

        jf_id = lib_index.get("by_tmdb", {}).get((mt, tid))
        if not jf_id:
            include_type = "Series" if mt == "tv" else "Movie"
            qs = urllib.parse.urlencode({
                "Recursive": "true",
                "IncludeItemTypes": include_type,
                "AnyProviderIdEquals": f"tmdb.{tid}",
                "Fields": "ProviderIds",
            })
            data = self._jf_get(f"/Items?{qs}", timeout=6)
            items = data.get("Items") if isinstance(data, dict) else []
            matched = _match_item_by_tmdb(items, tid)
            if matched:
                jf_id = normalize_jf_id(matched["Id"])

        if not jf_id:
            return None
        if mt == "tv":
            return self._resolve_series_episode_id(uid, jf_id, season=season, episode=episode)
        return jf_id

    def _resolve_series_episode_id(self, uid, series_id, season=None, episode=None):
        sn = _safe_int(season)
        en = _safe_int(episode)
        if sn is not None and en is not None:
            qs = urllib.parse.urlencode({"UserId": uid, "Season": str(sn)})
            edata = self._jf_get(f"/Shows/{series_id}/Episodes?{qs}", timeout=6)
            for ep in (edata.get("Items") if isinstance(edata, dict) else []) or []:
                if isinstance(ep, dict) and _safe_int(ep.get("IndexNumber")) == en:
                    eid = normalize_jf_id(ep.get("Id"))
                    if eid:
                        return eid

        # Try NextUp for this series first
        try:
            qs_nu = urllib.parse.urlencode({"UserId": uid, "SeriesId": series_id, "Limit": "1"})
            nu = self._jf_get(f"/Shows/NextUp?{qs_nu}", timeout=6)
            nu_items = nu.get("Items") if isinstance(nu, dict) else []
            if isinstance(nu_items, list) and nu_items:
                eid = normalize_jf_id(nu_items[0].get("Id"))
                if eid:
                    return eid
        except Exception:
            pass

        # Fallback: first episode of the series
        qs_all = urllib.parse.urlencode({"UserId": uid, "Limit": "50"})
        all_eps = self._jf_get(f"/Shows/{series_id}/Episodes?{qs_all}", timeout=6)
        ep_items = all_eps.get("Items") if isinstance(all_eps, dict) else []
        for ep in ep_items if isinstance(ep_items, list) else []:
            if not isinstance(ep, dict):
                continue
            eid = normalize_jf_id(ep.get("Id"))
            if eid:
                return eid
        return None

    def _build_hls_url(self, item_id, media_source_id, play_session_id, max_bitrate, max_width=None, audio_bitrate=192_000, audio_stream_index=None):
        eff_audio = min(int(audio_bitrate or 192_000), max(64_000, int(max_bitrate) // 4))
        video_bitrate = max(300_000, int(max_bitrate) - eff_audio)
        params = {
            "MediaSourceId": media_source_id,
            "VideoCodec": "h264",
            "AudioCodec": "aac",
            "VideoBitrate": str(video_bitrate),
            "AudioBitrate": str(eff_audio),
            "SegmentContainer": "ts",
            "SegmentLength": "6",
            "MinSegments": "2",
            "PlaySessionId": play_session_id,
            "DeviceId": PLAYER_DEVICE_ID,
            "TranscodingMaxAudioChannels": "2",
            "RequireAvc": "false",
            "EnableAudioVbrEncoding": "true",
        }
        if max_width:
            params["MaxWidth"] = str(int(max_width))
        if audio_stream_index is not None:
            params["AudioStreamIndex"] = str(int(audio_stream_index))
        return f"/pc/api/watch/stream/{item_id}/master.m3u8?{urllib.parse.urlencode(params)}"

    def _find_next_episode(self, uid, series_id, current_item_id, current_season, current_episode):
        if not series_id:
            return None
        try:
            qs = urllib.parse.urlencode({
                "UserId": uid,
                "Fields": "ImageTags,Overview,RunTimeTicks",
            })
            data = self._jf_get(f"/Shows/{series_id}/Episodes?{qs}", timeout=6)
            raw_eps = data.get("Items") if isinstance(data, dict) else []
            if not isinstance(raw_eps, list) or not raw_eps:
                return None

            # Sort regular seasons before specials (or keep Jellyfin order)
            def _sort_key(ep):
                sn = _safe_int(ep.get("ParentIndexNumber"))
                en = _safe_int(ep.get("IndexNumber"))
                return (1 if sn == 0 else 0, sn if sn is not None else 9999, en if en is not None else 9999)

            eps = [ep for ep in raw_eps if isinstance(ep, dict) and normalize_jf_id(ep.get("Id"))]
            eps.sort(key=_sort_key)

            next_ep = None
            for idx, ep in enumerate(eps):
                eid = normalize_jf_id(ep.get("Id"))
                if eid == current_item_id:
                    if idx + 1 < len(eps):
                        next_ep = eps[idx + 1]
                    break
            if next_ep is None and current_season is not None and current_episode is not None:
                for ep in eps:
                    sn = _safe_int(ep.get("ParentIndexNumber"))
                    en = _safe_int(ep.get("IndexNumber"))
                    if sn is None or en is None or sn == 0:
                        continue
                    if (sn == current_season and en > current_episode) or sn > current_season:
                        next_ep = ep
                        break

            if next_ep is None:
                return None
            nid = normalize_jf_id(next_ep.get("Id"))
            img_tags = next_ep.get("ImageTags") if isinstance(next_ep.get("ImageTags"), dict) else {}
            still_url = (
                _jf_img_url(nid, "Primary", series_id=series_id, max_width=720)
                if img_tags.get("Primary")
                else _jf_img_url(series_id, "Backdrop", max_width=720)
            )
            return {
                "jellyfinItemId": nid,
                "title": _clip(next_ep.get("Name") or "Next episode", 160),
                "seasonNumber": _safe_int(next_ep.get("ParentIndexNumber")),
                "episodeNumber": _safe_int(next_ep.get("IndexNumber")),
                "stillUrl": still_url,
            }
        except Exception:
            return None

    def _extract_item_media_context(self, uid, item, media_source):
        item_id = normalize_jf_id(item.get("Id"))
        itype = str(item.get("Type") or "")
        is_episode = itype == "Episode"
        series_id = normalize_jf_id(item.get("SeriesId")) if is_episode else None
        sn = _safe_int(item.get("ParentIndexNumber")) if is_episode else None
        en = _safe_int(item.get("IndexNumber")) if is_episode else None

        if is_episode:
            title = _clip(item.get("SeriesName") or item.get("Name") or "Untitled", 160)
            ep_name = _clip(item.get("Name") or "", 140)
            parts = []
            if sn is not None and en is not None:
                parts.append(f"S{sn} · E{en}")
            elif en is not None:
                parts.append(f"E{en}")
            if ep_name:
                parts.append(ep_name)
            subtitle = " · ".join(parts) if parts else None
            media_type = "episode"
            img_tags = item.get("ImageTags") if isinstance(item.get("ImageTags"), dict) else {}
            parent_bd = normalize_jf_id(item.get("ParentBackdropItemId"))
            still_url = (
                _jf_img_url(item_id, "Primary", series_id=series_id, max_width=720)
                if img_tags.get("Primary")
                else _jf_img_url(parent_bd or series_id or item_id, "Backdrop", series_id=series_id, max_width=720)
            )
            backdrop_url = _jf_img_url(parent_bd or series_id or item_id, "Backdrop", max_width=1280)
            poster_url = _jf_img_url(series_id or item_id, "Primary", max_width=400)
        else:
            title = _clip(item.get("Name") or "Untitled", 160)
            yr = _safe_int(item.get("ProductionYear"))
            subtitle = str(yr) if yr else None
            media_type = "movie"
            has_bd = bool(item.get("BackdropImageTags"))
            backdrop_url = _jf_img_url(item_id, "Backdrop" if has_bd else "Primary", max_width=1280)
            still_url = _jf_img_url(item_id, "Backdrop" if has_bd else "Primary", max_width=720)
            poster_url = _jf_img_url(item_id, "Primary", max_width=400)

        tmdb_id = None
        if is_episode and series_id:
            try:
                tmdb_id = self._library_index().get("series_tmdb", {}).get(series_id)
            except Exception:
                tmdb_id = None
        if not tmdb_id:
            tmdb_id = _parse_provider_tmdb(item.get("ProviderIds"))

        ms_id = str((media_source or {}).get("Id") or item_id).strip()
        if not JELLYFIN_MEDIA_SOURCE_ID_RE.match(ms_id):
            ms_id = item_id

        streams = (media_source or {}).get("MediaStreams")
        if not isinstance(streams, list):
            streams = item.get("MediaStreams") if isinstance(item.get("MediaStreams"), list) else []

        audio_tracks = []
        subtitle_tracks = []
        video_codec = ""
        default_audio_codec = ""
        found_default_audio = False
        for st in streams:
            if not isinstance(st, dict):
                continue
            stype = str(st.get("Type") or "")
            idx = _safe_int(st.get("Index"))
            if idx is None or idx < 0:
                continue
            codec = str(st.get("Codec") or "").strip().lower()
            if stype == "Video" and not video_codec:
                video_codec = codec
            elif stype == "Audio":
                is_def = bool(st.get("IsDefault"))
                if (is_def and not found_default_audio) or not default_audio_codec:
                    default_audio_codec = codec
                    if is_def:
                        found_default_audio = True
                lang = str(st.get("Language") or "").strip()
                disp = str(st.get("DisplayTitle") or st.get("Title") or "").strip()
                if not disp:
                    disp = " · ".join(p for p in (lang.upper() if lang else "Audio", codec.upper() if codec else "") if p)
                audio_tracks.append({
                    "index": idx,
                    "language": lang,
                    "displayTitle": _clip(disp, 120),
                    "codec": codec,
                    "channels": _safe_int(st.get("Channels")),
                    "isDefault": is_def,
                })
            elif stype == "Subtitle":
                is_text = bool(st.get("IsTextSubtitleStream")) or codec in TEXT_SUBTITLE_CODECS
                if not is_text:
                    continue
                lang = str(st.get("Language") or "").strip()
                disp = str(st.get("DisplayTitle") or st.get("Title") or "").strip()
                if not disp:
                    disp = lang.upper() if lang else f"Subtitle {idx}"
                subtitle_tracks.append({
                    "index": idx,
                    "language": lang,
                    "displayTitle": _clip(disp, 120),
                    "codec": codec,
                    "isExternal": bool(st.get("IsExternal")),
                    "isDefault": bool(st.get("IsDefault")),
                    "isForced": bool(st.get("IsForced")),
                    "deliveryUrl": f"/pc/api/watch/subtitles/{item_id}/{ms_id}/{idx}.vtt",
                })

        return {
            "jellyfinItemId": item_id,
            "mediaSourceId": ms_id,
            "title": title,
            "subtitle": subtitle,
            "mediaType": media_type,
            "tmdbId": tmdb_id,
            "seriesId": series_id,
            "seasonNumber": sn,
            "episodeNumber": en,
            "backdropUrl": backdrop_url,
            "stillUrl": still_url,
            "posterUrl": poster_url,
            "audioTracks": audio_tracks,
            "subtitleTracks": subtitle_tracks,
            "videoCodec": video_codec,
            "defaultAudioCodec": default_audio_codec,
        }

    def playback_info(self, body):
        """POST /pc/api/watch/playback-info (Tailscale-only)"""
        if not isinstance(body, dict):
            return 400, {"error": "JSON object required"}

        try:
            uid = self._jf_user_id(timeout=5)
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin user lookup failed: {e}", 240)}

        try:
            item_id = self._resolve_playable_item_id(
                uid,
                jellyfin_item_id=body.get("jellyfinItemId"),
                tmdb_id=body.get("tmdbId"),
                media_type=body.get("mediaType"),
                season=body.get("season") if body.get("season") is not None else body.get("seasonNumber"),
                episode=body.get("episode") if body.get("episode") is not None else body.get("episodeNumber"),
            )
        except urllib.error.HTTPError as e:
            return 502, {"error": f"Jellyfin lookup failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin lookup failed: {e}", 240)}

        if not item_id:
            return 404, {"error": "Playable item not found in Jellyfin library"}

        raw_bitrate = _safe_int(body.get("maxBitrate"))
        requested_bitrate = raw_bitrate if (raw_bitrate is not None and raw_bitrate > 0) else 8_000_000
        req_audio_idx = _safe_int(body.get("audioStreamIndex"))

        pb_payload = {
            "UserId": uid,
            "MaxStreamingBitrate": requested_bitrate,
            "DeviceProfile": _build_device_profile(requested_bitrate),
        }
        if req_audio_idx is not None and req_audio_idx >= 0:
            pb_payload["AudioStreamIndex"] = req_audio_idx

        fields = "MediaSources,MediaStreams,Chapters,Overview,ProviderIds,ImageTags,ParentBackdropItemId,SeriesId,UserData,RunTimeTicks,Trickplay"
        segments = []
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                f_item = pool.submit(self._jf_get, f"/Users/{uid}/Items/{item_id}?Fields={fields}", 8)
                f_pb = pool.submit(
                    self._jf_request,
                    "POST",
                    f"/Items/{item_id}/PlaybackInfo?UserId={uid}",
                    pb_payload,
                    10,
                    True,
                )
                f_segs = pool.submit(self.get_media_segments, item_id, uid, True)
                item = f_item.result()
                pb = f_pb.result()
                try:
                    segments = f_segs.result() or []
                except Exception:
                    segments = []
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {"error": "Jellyfin item not found"}
            return 502, {"error": f"Jellyfin playback info failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin playback info failed: {e}", 240)}

        if not isinstance(item, dict) or not item.get("Id"):
            return 404, {"error": "Jellyfin item not found"}

        media_sources = pb.get("MediaSources") if isinstance(pb, dict) else None
        if not isinstance(media_sources, list) or not media_sources:
            media_sources = item.get("MediaSources") if isinstance(item.get("MediaSources"), list) else []
        ms = media_sources[0] if media_sources and isinstance(media_sources[0], dict) else {}

        play_session_id = str((pb.get("PlaySessionId") if isinstance(pb, dict) else None) or secrets.token_hex(16))
        ctx = self._extract_item_media_context(uid, item, ms)

        audio_tracks = ctx["audioTracks"]
        selected_audio_idx = req_audio_idx
        if selected_audio_idx is None:
            default_audio = next((a for a in audio_tracks if a["isDefault"]), audio_tracks[0] if audio_tracks else None)
            if default_audio is not None:
                selected_audio_idx = default_audio["index"]

        direct_url = f"/pc/api/watch/stream/{item_id}/direct?{urllib.parse.urlencode({'mediaSourceId': ctx['mediaSourceId']})}"
        quality_options = []
        hls_by_bitrate = {}
        for preset in QUALITY_PRESETS:
            if preset["id"] == "original":
                quality_options.append({
                    "id": "original",
                    "label": preset["label"],
                    "maxBitrate": 0,
                    "maxWidth": None,
                    "streamUrl": direct_url,
                    "isHls": False,
                })
            else:
                hls_u = self._build_hls_url(
                    item_id=item_id,
                    media_source_id=ctx["mediaSourceId"],
                    play_session_id=play_session_id,
                    max_bitrate=preset["maxBitrate"],
                    max_width=preset["maxWidth"],
                    audio_bitrate=preset["audioBitrate"],
                    audio_stream_index=selected_audio_idx,
                )
                hls_by_bitrate[preset["maxBitrate"]] = hls_u
                quality_options.append({
                    "id": preset["id"],
                    "label": preset["label"],
                    "maxBitrate": preset["maxBitrate"],
                    "maxWidth": preset["maxWidth"],
                    "streamUrl": hls_u,
                    "isHls": True,
                })

        # Primary hlsUrl matches requested_bitrate (or 8 Mbps 1080p default)
        if requested_bitrate in hls_by_bitrate:
            hls_url = hls_by_bitrate[requested_bitrate]
        else:
            max_w = 1920 if requested_bitrate >= 6_000_000 else (1280 if requested_bitrate >= 2_500_000 else 854)
            hls_url = self._build_hls_url(
                item_id=item_id,
                media_source_id=ctx["mediaSourceId"],
                play_session_id=play_session_id,
                max_bitrate=requested_bitrate,
                max_width=max_w,
                audio_stream_index=selected_audio_idx,
            )

        container = str(ms.get("Container") or "").strip().lower().split(",")[0]
        can_direct_play = (
            (not container or container in DIRECT_PLAY_CONTAINERS)
            and (not ctx["videoCodec"] or ctx["videoCodec"] in DIRECT_PLAY_VIDEO_CODECS)
            and (not ctx["defaultAudioCodec"] or ctx["defaultAudioCodec"] in DIRECT_PLAY_AUDIO_CODECS)
        )
        recommended_quality = "original" if (raw_bitrate in (None, 0) and can_direct_play) else (
            "480p" if requested_bitrate <= 1_500_000
            else ("720p" if requested_bitrate <= 4_000_000 else ("original" if can_direct_play else "1080p"))
        )

        ud = item.get("UserData") if isinstance(item.get("UserData"), dict) else {}
        runtime_ticks = max(0, _safe_int(ms.get("RunTimeTicks")) or _safe_int(item.get("RunTimeTicks")) or 0)
        resume_ticks = max(0, _safe_int(ud.get("PlaybackPositionTicks")) or 0)

        chapters = []
        raw_chapters = item.get("Chapters") if isinstance(item.get("Chapters"), list) else []
        for raw_idx, ch in enumerate(raw_chapters):
            if not isinstance(ch, dict):
                continue
            st_ticks = _safe_int(ch.get("StartPositionTicks"))
            if st_ticks is None or st_ticks < 0:
                continue
            img_tag = ch.get("ImageTag")
            ch_img = (
                _jf_img_url(item_id, "Chapter", max_width=320, image_index=raw_idx)
                if isinstance(img_tag, str) and img_tag.strip()
                else None
            )
            chapters.append({
                "name": _clip(ch.get("Name") or f"Chapter {len(chapters) + 1}", 120),
                "startPositionTicks": st_ticks,
                "imageUrl": ch_img,
            })

        next_ep = None
        if ctx["mediaType"] == "episode" and ctx["seriesId"]:
            next_ep = self._find_next_episode(
                uid=uid,
                series_id=ctx["seriesId"],
                current_item_id=item_id,
                current_season=ctx["seasonNumber"],
                current_episode=ctx["episodeNumber"],
            )

        trickplay = parse_trickplay_info(item, item_id, media_source_id=ctx["mediaSourceId"])

        return 200, {
            "jellyfinItemId": item_id,
            "mediaSourceId": ctx["mediaSourceId"],
            "playSessionId": play_session_id,
            "title": ctx["title"],
            "subtitle": ctx["subtitle"],
            "mediaType": ctx["mediaType"],
            "tmdbId": ctx["tmdbId"],
            "seriesId": ctx["seriesId"],
            "seasonNumber": ctx["seasonNumber"],
            "episodeNumber": ctx["episodeNumber"],
            "backdropUrl": ctx["backdropUrl"],
            "posterUrl": ctx["posterUrl"],
            "runtimeTicks": runtime_ticks,
            "resumePositionTicks": resume_ticks,
            "hlsUrl": hls_url,
            "directStreamUrl": direct_url,
            "recommendedQualityId": recommended_quality,
            "qualityOptions": quality_options,
            "audioTracks": audio_tracks,
            "subtitleTracks": ctx["subtitleTracks"],
            "chapters": chapters,
            "nextEpisode": next_ep,
            "segments": segments,
            "trickplay": trickplay,
        }

    def report_progress(self, body):
        """POST /pc/api/watch/progress"""
        if not isinstance(body, dict):
            return 400, {"error": "JSON object required"}

        item_id = normalize_jf_id(body.get("jellyfinItemId"))
        if not item_id:
            return 400, {"error": "Valid jellyfinItemId is required"}

        event = str(body.get("event") or "progress").strip().lower()
        if event not in ("start", "progress", "stop"):
            return 400, {"error": "event must be start, progress, or stop"}

        pos_ticks = max(0, _safe_int(body.get("positionTicks")) or 0)
        runtime_ticks = max(0, _safe_int(body.get("runtimeTicks")) or 0)
        is_paused = bool(body.get("isPaused", False))
        media_source_id = str(body.get("mediaSourceId") or item_id).strip()
        if not JELLYFIN_MEDIA_SOURCE_ID_RE.match(media_source_id):
            media_source_id = item_id
        play_session_id = str(body.get("playSessionId") or "").strip()[:128]

        try:
            uid = self._jf_user_id(timeout=5)
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin user lookup failed: {e}", 240)}

        jf_payload = {
            "ItemId": item_id,
            "MediaSourceId": media_source_id,
            "PositionTicks": pos_ticks,
            "IsPaused": is_paused,
            "CanSeek": True,
            "PlayMethod": "DirectStream",
        }
        if play_session_id:
            jf_payload["PlaySessionId"] = play_session_id

        endpoint = {
            "start": "/Sessions/Playing",
            "progress": "/Sessions/Playing/Progress",
            "stop": "/Sessions/Playing/Stopped",
        }[event]
        if event == "progress":
            jf_payload["EventName"] = "pause" if is_paused else "timeupdate"

        session_ok = False
        session_err = None
        try:
            self._jf_request("POST", endpoint, body=jf_payload, timeout=6, include_client_meta=True)
            session_ok = True
        except Exception as e:
            session_err = e

        marked_played = False
        is_complete = runtime_ticks > 0 and (pos_ticks / float(runtime_ticks)) >= 0.90

        # Persist UserData directly for user `ayush` so Continue Watching & Resume timestamps
        # always stick even if Jellyfin ignores /Sessions/Playing/* updates.
        userdata_ok = False
        should_write_userdata = event == "stop" or (event == "progress" and is_paused)
        if event == "progress" and not should_write_userdata:
            now_ts = time.time()
            with self._lock:
                prev_ts, prev_pos = self._userdata_last_write.get(item_id, (0.0, -1))
                if (now_ts - prev_ts) >= USERDATA_PROGRESS_THROTTLE_SEC or abs(pos_ticks - prev_pos) >= (5 * TICKS_PER_SEC):
                    should_write_userdata = True

        if should_write_userdata:
            try:
                if is_complete:
                    self._jf_request(
                        "POST",
                        f"/Users/{uid}/Items/{item_id}/UserData",
                        body={"Played": True, "PlaybackPositionTicks": 0},
                        timeout=6,
                        include_client_meta=True,
                    )
                    try:
                        self._jf_request(
                            "POST",
                            f"/Users/{uid}/PlayedItems/{item_id}",
                            body=None,
                            timeout=6,
                            include_client_meta=True,
                        )
                    except Exception:
                        pass
                    marked_played = True
                    userdata_ok = True
                else:
                    self._jf_request(
                        "POST",
                        f"/Users/{uid}/Items/{item_id}/UserData",
                        body={"Played": False, "PlaybackPositionTicks": pos_ticks},
                        timeout=6,
                        include_client_meta=True,
                    )
                    userdata_ok = True
                if userdata_ok:
                    with self._lock:
                        self._userdata_last_write[item_id] = (time.time(), 0 if is_complete else pos_ticks)
            except Exception:
                pass

        if event == "stop":
            if play_session_id:
                try:
                    qs = urllib.parse.urlencode({
                        "DeviceId": PLAYER_DEVICE_ID,
                        "PlaySessionId": play_session_id,
                    })
                    self._jf_request("DELETE", f"/Videos/ActiveEncodings?{qs}", timeout=4, include_client_meta=True)
                except Exception:
                    pass
            self.invalidate_caches()
        elif userdata_ok:
            # Ensure Watch home reflects fresh resume timestamps on return
            with self._lock:
                self._home_cache = (0.0, None)

        if not session_ok and not userdata_ok and session_err is not None:
            return 502, {"error": _clip(f"Jellyfin progress report failed: {session_err}", 240)}

        return 200, {
            "ok": True,
            "event": event,
            "jellyfinItemId": item_id,
            "positionTicks": 0 if marked_played else pos_ticks,
            "markedPlayed": marked_played,
            "userdataPersisted": userdata_ok,
        }

    def download_info(self, jellyfin_item_id_raw=None, tmdb_id_raw=None, media_type_raw=None, season_raw=None, episode_raw=None):
        """GET /pc/api/watch/download-info?jellyfinItemId=<id> (Tailscale-only)"""
        try:
            uid = self._jf_user_id(timeout=5)
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin user lookup failed: {e}", 240)}

        try:
            item_id = self._resolve_playable_item_id(
                uid,
                jellyfin_item_id=jellyfin_item_id_raw,
                tmdb_id=tmdb_id_raw,
                media_type=media_type_raw,
                season=season_raw,
                episode=episode_raw,
            )
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin lookup failed: {e}", 240)}

        if not item_id:
            return 404, {"error": "Downloadable item not found in Jellyfin library"}

        fields = "MediaSources,MediaStreams,Path,Overview,ProviderIds,ImageTags,ParentBackdropItemId,SeriesId,RunTimeTicks"
        try:
            item = self._jf_get(f"/Users/{uid}/Items/{item_id}?Fields={fields}", timeout=8)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {"error": "Jellyfin item not found"}
            return 502, {"error": f"Jellyfin download info failed: HTTP {e.code}"}
        except (OSError, ValueError) as e:
            return 502, {"error": _clip(f"Jellyfin download info failed: {e}", 240)}

        if not isinstance(item, dict) or not item.get("Id"):
            return 404, {"error": "Jellyfin item not found"}

        media_sources = item.get("MediaSources") if isinstance(item.get("MediaSources"), list) else []
        ms = media_sources[0] if media_sources and isinstance(media_sources[0], dict) else {}
        ctx = self._extract_item_media_context(uid, item, ms)

        raw_path = str(ms.get("Path") or item.get("Path") or "").strip()
        container = str(ms.get("Container") or "").strip().lower().split(",")[0]
        if not container and raw_path:
            _, ext = os.path.splitext(raw_path)
            container = ext.lstrip(".").lower()
        if not container:
            container = "mp4"

        if raw_path:
            file_name = _sanitize_filename(os.path.basename(raw_path), default_ext=container)
        else:
            base_label = f"{ctx['title']} {ctx['subtitle']}" if ctx["subtitle"] else ctx["title"]
            file_name = _sanitize_filename(f"{base_label}.{container}", default_ext=container)

        size_bytes = max(0, _safe_int(ms.get("Size")) or 0)
        if size_bytes == 0 and raw_path and os.path.isfile(raw_path):
            try:
                size_bytes = os.path.getsize(raw_path)
            except OSError:
                size_bytes = 0

        mime_type = _mime_for_container(container, file_name)
        download_url = f"/pc/api/watch/download/{item_id}?{urllib.parse.urlencode({'mediaSourceId': ctx['mediaSourceId']})}"

        subtitles = []
        for sub in ctx["subtitleTracks"]:
            lang_slug = re.sub(r"[^a-zA-Z0-9_-]+", "", sub.get("language") or "und") or "und"
            subtitles.append({
                "index": sub["index"],
                "language": sub["language"],
                "displayTitle": sub["displayTitle"],
                "codec": sub["codec"],
                "isDefault": sub["isDefault"],
                "isForced": sub["isForced"],
                "format": "vtt",
                "fileName": f"sub_{sub['index']}_{lang_slug}.vtt",
                "downloadUrl": sub["deliveryUrl"],
            })

        runtime_ticks = max(0, _safe_int(ms.get("RunTimeTicks")) or _safe_int(item.get("RunTimeTicks")) or 0)

        return 200, {
            "jellyfinItemId": item_id,
            "mediaSourceId": ctx["mediaSourceId"],
            "title": ctx["title"],
            "subtitle": ctx["subtitle"],
            "mediaType": ctx["mediaType"],
            "tmdbId": ctx["tmdbId"],
            "seriesId": ctx["seriesId"],
            "seasonNumber": ctx["seasonNumber"],
            "episodeNumber": ctx["episodeNumber"],
            "runtimeTicks": runtime_ticks,
            "fileName": file_name,
            "sizeBytes": size_bytes,
            "mimeType": mime_type,
            "downloadUrl": download_url,
            "posterUrl": ctx["posterUrl"],
            "stillUrl": ctx["stillUrl"],
            "subtitles": subtitles,
        }

    # ------------------------------------------------------------------ Upstream URL builders for proxy

    def build_stream_upstream_url(self, item_id, subpath, query_str=""):
        norm_id = normalize_jf_id(item_id)
        if not norm_id:
            return None
        clean_sub = (subpath or "").lstrip("/")
        if not clean_sub:
            return None
        base = self._jellyfin_url_fn().rstrip("/")
        clean_q = sanitize_upstream_query(query_str)
        if clean_sub == "direct":
            params = urllib.parse.parse_qsl(clean_q, keep_blank_values=False)
            filtered = [(k, v) for k, v in params if k.lower() != "static"]
            filtered.append(("static", "true"))
            return f"{base}/Videos/{norm_id}/stream?{urllib.parse.urlencode(filtered)}"
        url = f"{base}/videos/{norm_id}/{clean_sub}"
        if clean_q:
            url = f"{url}?{clean_q}"
        return url

    def build_download_upstream_url(self, item_id, query_str=""):
        norm_id = normalize_jf_id(item_id)
        if not norm_id:
            return None
        base = self._jellyfin_url_fn().rstrip("/")
        clean_q = sanitize_upstream_query(query_str)
        params = urllib.parse.parse_qsl(clean_q, keep_blank_values=False)
        filtered = [(k, v) for k, v in params if k.lower() != "static"]
        filtered.append(("static", "true"))
        return f"{base}/Videos/{norm_id}/stream?{urllib.parse.urlencode(filtered)}"

    def build_subtitle_upstream_url(self, item_id, media_source_id, index_raw, ext_raw):
        norm_id = normalize_jf_id(item_id)
        if not norm_id:
            return None
        ms_id = str(media_source_id or "").strip()
        if not JELLYFIN_MEDIA_SOURCE_ID_RE.match(ms_id):
            return None
        idx = _safe_int(index_raw)
        if idx is None or idx < 0:
            return None
        ext = str(ext_raw or "").strip().lower()
        if ext not in ("vtt", "srt"):
            return None
        base = self._jellyfin_url_fn().rstrip("/")
        return f"{base}/Videos/{norm_id}/{ms_id}/Subtitles/{idx}/0/Stream.{ext}"

    def build_trickplay_upstream_url(self, item_id, width_raw, tile_index_raw, media_source_id=None):
        norm_id = normalize_jf_id(item_id)
        if not norm_id:
            return None
        width = _safe_int(width_raw)
        if width is None or width <= 0 or width > 4096:
            return None
        tile_idx = _safe_int(tile_index_raw)
        if tile_idx is None or tile_idx < 0 or tile_idx > 100_000:
            return None
        base = self._jellyfin_url_fn().rstrip("/")
        url = f"{base}/Videos/{norm_id}/Trickplay/{width}/{tile_idx}.jpg"
        if media_source_id is not None:
            ms_id = str(media_source_id).strip()
            if ms_id:
                if not JELLYFIN_MEDIA_SOURCE_ID_RE.match(ms_id):
                    return None
                url = f"{url}?{urllib.parse.urlencode({'mediaSourceId': ms_id})}"
        return url


def parse_media_segments(raw):
    """Normalize Jellyfin /MediaSegments/{itemId} response into valid sorted segment dicts."""
    if isinstance(raw, dict):
        items = raw.get("Items")
    elif isinstance(raw, list):
        items = raw
    else:
        items = []
    if not isinstance(items, list):
        return []
    out = []
    for entry in items:
        if not isinstance(entry, dict):
            continue
        raw_type = str(entry.get("Type") or entry.get("type") or "").strip().lower()
        seg_type = MEDIA_SEGMENT_TYPES_BY_LOWER.get(raw_type)
        if not seg_type:
            continue
        start_ticks = _safe_int(entry.get("StartTicks") if "StartTicks" in entry else entry.get("startTicks"))
        end_ticks = _safe_int(entry.get("EndTicks") if "EndTicks" in entry else entry.get("endTicks"))
        if start_ticks is None or end_ticks is None or start_ticks < 0 or end_ticks <= start_ticks:
            continue
        out.append({
            "type": seg_type,
            "startTicks": start_ticks,
            "endTicks": end_ticks,
        })
    out.sort(key=lambda s: (s["startTicks"], s["endTicks"]))
    return out


def parse_trickplay_info(item, item_id, media_source_id=None, preferred_width=320):
    """Parse Jellyfin item Trickplay metadata, choosing the width closest to preferred_width (default 320)."""
    norm_id = normalize_jf_id(item_id)
    if not norm_id or not isinstance(item, dict):
        return None
    tp_root = item.get("Trickplay")
    if not isinstance(tp_root, dict) or not tp_root:
        return None

    chosen_ms = None
    widths_map = None
    for candidate_key in (media_source_id, norm_id):
        if candidate_key and isinstance(tp_root.get(candidate_key), dict) and tp_root[candidate_key]:
            chosen_ms = str(candidate_key).strip()
            widths_map = tp_root[candidate_key]
            break
    if widths_map is None:
        for k, v in tp_root.items():
            if isinstance(k, str) and k.strip() and isinstance(v, dict) and v:
                chosen_ms = k.strip()
                widths_map = v
                break
    if not isinstance(widths_map, dict) or not widths_map:
        return None

    candidates = []
    for k, meta in widths_map.items():
        if not isinstance(meta, dict):
            continue
        w = _safe_int(meta.get("Width")) or _safe_int(k)
        h = _safe_int(meta.get("Height"))
        tw = _safe_int(meta.get("TileWidth"))
        th = _safe_int(meta.get("TileHeight"))
        cnt = _safe_int(meta.get("ThumbnailCount"))
        interval = _safe_int(meta.get("Interval"))
        if not (w and w > 0 and h and h > 0 and tw and tw > 0 and th and th > 0 and cnt and cnt > 0 and interval and interval > 0):
            continue
        candidates.append({
            "width": w,
            "height": h,
            "tileWidth": tw,
            "tileHeight": th,
            "thumbnailCount": cnt,
            "intervalMs": interval,
        })
    if not candidates:
        return None

    best = min(candidates, key=lambda c: (abs(c["width"] - preferred_width), -c["width"]))
    norm_ms = chosen_ms if (chosen_ms and JELLYFIN_MEDIA_SOURCE_ID_RE.match(chosen_ms)) else norm_id
    best["tileUrlTemplate"] = (
        f"/pc/api/watch/trickplay/{norm_id}/{best['width']}/{{tileIndex}}.jpg"
        f"?mediaSourceId={urllib.parse.quote(norm_ms, safe='')}"
    )
    return best


def normalize_jf_id(raw):
    if not isinstance(raw, str):
        return None
    s = raw.strip().replace("-", "")
    return s if JELLYFIN_ITEM_ID_RE.match(s) else None


def sanitize_upstream_query(query_str):
    if not query_str:
        return ""
    pairs = urllib.parse.parse_qsl(query_str, keep_blank_values=True)
    filtered = [(k, v) for k, v in pairs if k.lower() not in ("apikey", "api_key")]
    return urllib.parse.urlencode(filtered)


def sanitize_m3u8(text):
    """Strip any Jellyfin ApiKey query parameters from proxied HLS playlists."""
    if not text:
        return text

    def _repl(match):
        lead, trail = match.group(1), match.group(2)
        if lead == "?" and trail == "&":
            return "?"
        if lead == "&" and trail == "&":
            return "&"
        return ""

    return API_KEY_PARAM_RE.sub(_repl, text)


def _sanitize_filename(name, default_ext="mp4"):
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', " ", str(name or "")).strip()
    cleaned = re.sub(r"\s+", " ", cleaned).strip(". ")
    if not cleaned:
        return f"video.{default_ext}"
    if "." not in cleaned:
        cleaned = f"{cleaned}.{default_ext}"
    return cleaned[:180]


def _mime_for_container(container, file_name=""):
    c = str(container or "").strip().lower()
    if c in CONTAINER_MIME_TYPES:
        return CONTAINER_MIME_TYPES[c]
    guessed, _ = mimetypes.guess_type(file_name or f"file.{c}")
    return guessed or "video/mp4"


def _build_device_profile(max_bitrate):
    return {
        "MaxStreamingBitrate": int(max_bitrate),
        "DirectPlayProfiles": [
            {
                "Container": "mp4,m4v,mkv,webm,mov",
                "Type": "Video",
                "VideoCodec": "h264,hevc,vp8,vp9,av1",
                "AudioCodec": "aac,mp3,opus,flac,vorbis,ac3,eac3,truehd,dts,dca",
            }
        ],
        "TranscodingProfiles": [
            {
                "Container": "ts",
                "Type": "Video",
                "VideoCodec": "h264",
                "AudioCodec": "aac",
                "Protocol": "hls",
                "Context": "Streaming",
                "MaxAudioChannels": "2",
                "SegmentLength": 6,
                "MinSegments": 2,
            }
        ],
        "SubtitleProfiles": [
            {"Format": "vtt", "Method": "External"},
            {"Format": "srt", "Method": "External"},
            {"Format": "subrip", "Method": "External"},
            {"Format": "ass", "Method": "External"},
            {"Format": "ssa", "Method": "External"},
        ],
    }
