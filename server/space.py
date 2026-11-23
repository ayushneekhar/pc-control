"""Space Manager backend for pc-api: watched media discovery + safe disk freeing.

Reuses ~/tools/localagent/watched.py's safety invariants (mount check, path containment,
hardlink/inode verification, unmonitor-before-delete, torrent copy cleanup, Jellyfin refresh,
and Seerr availability sync) so deleting from the phone app has the exact same guarantees as
Telegram /watched.
"""
import concurrent.futures
import os
import re
import sys
import time
from pathlib import Path

HOME = os.path.expanduser("~")
LOCALAGENT_DIR = os.environ.get("PCAPI_LOCALAGENT_DIR", os.path.join(HOME, "tools", "localagent"))
HDD_MNT = os.environ.get("PCAPI_HDD_MNT", "/run/media/ayushlive/New Volume")
WATCHER = os.environ.get("PCAPI_SPACE_WATCHER", "ayush").lower()
THRESHOLD_BYTES = 150 * 1024 ** 3
MAX_FREE_ITEMS = 50


def _clip(text, limit=240):
    s = str(text or "").strip()
    return s if len(s) <= limit else s[: limit - 1] + "…"


def _load_localagent():
    """Lazily import ~/tools/localagent/watched.py and agent.py (works under python3 -I)."""
    if LOCALAGENT_DIR and LOCALAGENT_DIR not in sys.path:
        sys.path.insert(0, LOCALAGENT_DIR)
    import agent  # type: ignore
    import watched  # type: ignore

    return watched, agent


def _inside(path, root):
    real = os.path.realpath(path)
    return real.startswith(os.path.realpath(root) + os.sep)


def hdd_stats(mount=None, pending=None):
    """Disk usage + predicted free bytes for the media HDD."""
    mnt = str(mount or HDD_MNT)
    pend = pending or {"left": 0, "n": 0, "unknown": 0}
    pending_bytes = int(pend.get("left") or 0)
    pending_count = int(pend.get("n") or 0)
    pending_unknown = int(pend.get("unknown") or 0)
    if not os.path.ismount(mnt):
        return {
            "mount": mnt,
            "mounted": False,
            "total_bytes": None,
            "used_bytes": None,
            "free_bytes": None,
            "pending_bytes": pending_bytes,
            "pending_count": pending_count,
            "pending_unknown": pending_unknown,
            "predicted_free_bytes": None,
            "threshold_bytes": THRESHOLD_BYTES,
        }
    v = os.statvfs(mnt)
    total = int(v.f_blocks * v.f_frsize)
    free = int(v.f_bavail * v.f_frsize)
    used = max(0, int(total - v.f_bfree * v.f_frsize))
    return {
        "mount": mnt,
        "mounted": True,
        "total_bytes": total,
        "used_bytes": used,
        "free_bytes": free,
        "pending_bytes": pending_bytes,
        "pending_count": pending_count,
        "pending_unknown": pending_unknown,
        "predicted_free_bytes": free - pending_bytes,
        "threshold_bytes": THRESHOLD_BYTES,
    }


def _pending_downloads(agent_mod, mount=None):
    """Active qBittorrent downloads targeting the HDD and their remaining bytes."""
    mnt = str(mount or HDD_MNT)
    out = {"left": 0, "n": 0, "unknown": 0}
    active = agent_mod.DL_STATES - {"pausedDL", "stoppedDL"}
    meta = {"metaDL", "forcedMetaDL"}
    ts = agent_mod.qbt("torrents/info")
    for t in ts or []:
        if (t.get("progress") or 0) >= 1:
            continue
        if t.get("state") not in active:
            continue
        if not _inside(t.get("save_path", ""), mnt):
            continue
        out["n"] += 1
        if t.get("state") in meta or (t.get("size") or 0) <= 0:
            out["unknown"] += 1
        else:
            out["left"] += int(t.get("amount_left") or 0)
    return out


def _jellyfin_played_by_user(agent_mod):
    """Return {lower_name: {"name": display_name, "paths": set, "tmdb": set}} for enabled Jellyfin users."""
    users = [
        u for u in (agent_mod.jellyfin("GET", "/Users") or [])
        if not (u.get("Policy") or {}).get("IsDisabled") and u.get("Name") and u.get("Id")
    ]
    out = {}
    for u in users:
        res = agent_mod.jellyfin(
            "GET",
            f"/Users/{u['Id']}/Items?Recursive=true&IncludeItemTypes=Movie,Episode"
            "&Filters=IsPlayed&Fields=Path,ProviderIds",
            timeout=60,
        )
        items = (res or {}).get("Items") or []
        paths = {os.path.normpath(i["Path"]) for i in items if i.get("Path")}
        tmdb = {
            str(i["ProviderIds"]["Tmdb"])
            for i in items
            if (i.get("ProviderIds") or {}).get("Tmdb")
        }
        out[u["Name"].lower()] = {"name": u["Name"], "paths": paths, "tmdb": tmdb}
    return out


def _movie_watched(movie, user_data):
    mf = movie.get("movieFile") or {}
    mine = {os.path.normpath(p) for p in (mf.get("path"), movie.get("path")) if p}
    if mine & user_data["paths"]:
        return True
    tmdb_id = movie.get("tmdbId")
    return bool(tmdb_id and str(tmdb_id) in user_data["tmdb"])


def _season_watched(ep_paths, user_data):
    return bool(ep_paths) and all(os.path.normpath(p) in user_data["paths"] for p in ep_paths)


def _build_movie_candidates(movies, everyone, watcher=WATCHER):
    out = []
    total_users = len(everyone)
    watcher_data = everyone[watcher]
    for m in movies or []:
        if not m.get("hasFile") or not m.get("movieFile"):
            continue
        if not _movie_watched(m, watcher_data):
            continue
        watched_users = []
        unwatched_users = []
        for udata in everyone.values():
            if _movie_watched(m, udata):
                watched_users.append(udata["name"])
            else:
                unwatched_users.append(udata["name"])
        watched_users.sort(key=str.lower)
        unwatched_users.sort(key=str.lower)
        out.append({
            "id": f"movie:{int(m['id'])}",
            "kind": "movie",
            "radarr_id": int(m["id"]),
            "title": str(m.get("title") or ""),
            "subtitle": None,
            "year": m.get("year"),
            "size_bytes": int((m.get("movieFile") or {}).get("size") or 0),
            "watched_count": len(watched_users),
            "total_users": total_users,
            "watched_users": watched_users,
            "unwatched_users": unwatched_users,
            "warn_unwatched": len(unwatched_users) > 0,
            "monitored": bool(m.get("monitored")),
        })
    return out


def _build_season_candidates(series_list, episode_files_by_series, everyone, watcher=WATCHER):
    out = []
    total_users = len(everyone)
    watcher_data = everyone[watcher]
    for s in series_list or []:
        sid = s.get("id")
        if sid is None:
            continue
        all_efs = episode_files_by_series.get(sid) or []
        if not all_efs:
            continue
        for se in s.get("seasons") or []:
            sn = se.get("seasonNumber")
            if sn is None:
                continue
            if ((se.get("statistics") or {}).get("episodeFileCount") or 0) <= 0:
                continue
            efs = [ef for ef in all_efs if ef.get("seasonNumber") == sn and ef.get("path")]
            if not efs:
                continue
            ep_paths = [ef["path"] for ef in efs]
            if not _season_watched(ep_paths, watcher_data):
                continue
            watched_users = []
            unwatched_users = []
            for udata in everyone.values():
                if _season_watched(ep_paths, udata):
                    watched_users.append(udata["name"])
                else:
                    unwatched_users.append(udata["name"])
            watched_users.sort(key=str.lower)
            unwatched_users.sort(key=str.lower)
            sn_int = int(sn)
            out.append({
                "id": f"season:{int(sid)}:{sn_int}",
                "kind": "season",
                "series_id": int(sid),
                "season_number": sn_int,
                "title": str(s.get("title") or ""),
                "subtitle": f"Season {sn_int}" if sn_int > 0 else "Specials",
                "year": s.get("year"),
                "episode_count": len(efs),
                "size_bytes": int(sum(ef.get("size") or 0 for ef in efs)),
                "watched_count": len(watched_users),
                "total_users": total_users,
                "watched_users": watched_users,
                "unwatched_users": unwatched_users,
                "warn_unwatched": len(unwatched_users) > 0,
                "monitored": bool(se.get("monitored")),
            })
    return out


def _fetch_sonarr_all(watched_mod):
    """Fetch Sonarr series and episode files for all series that have files on disk."""
    series_list = watched_mod.sonarr_series() or []
    active_ids = []
    for s in series_list:
        if s.get("id") is None:
            continue
        has_any = any(
            se.get("seasonNumber") is not None
            and ((se.get("statistics") or {}).get("episodeFileCount") or 0) > 0
            for se in (s.get("seasons") or [])
        )
        if has_any:
            active_ids.append(s["id"])
    ep_files = {}
    if active_ids:
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(6, len(active_ids))) as pool:
            futs = {pool.submit(watched_mod.sonarr, "GET", f"/episodefile?seriesId={sid}"): sid for sid in active_ids}
            for fut, sid in futs.items():
                try:
                    ep_files[sid] = fut.result() or []
                except Exception:
                    ep_files[sid] = []
    return series_list, ep_files


def get_space(watched_mod=None, agent_mod=None, mount=None, watcher=WATCHER):
    """GET /pc/api/space handler. Always returns (200, payload) with graceful partial errors."""
    errors = {}
    if watched_mod is None or agent_mod is None:
        try:
            w, a = _load_localagent()
            watched_mod = watched_mod or w
            agent_mod = agent_mod or a
        except Exception as e:
            mnt = str(mount or HDD_MNT)
            return 200, {
                "hdd": hdd_stats(mnt),
                "candidates": [],
                "errors": {"backend": _clip(f"localagent unavailable: {e}")},
                "ts": int(time.time()),
            }

    mnt = str(mount or getattr(watched_mod, "HDD", HDD_MNT))
    mounted = os.path.ismount(mnt)
    if not mounted:
        errors["hdd"] = "HDD not mounted"

    pend = {"left": 0, "n": 0, "unknown": 0}
    everyone = None
    movies = None
    sonarr_data = None

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        f_qbt = pool.submit(_pending_downloads, agent_mod, mnt)
        f_jf = pool.submit(_jellyfin_played_by_user, agent_mod) if mounted else None
        f_radarr = pool.submit(watched_mod.radarr, "GET", "/movie") if mounted else None
        f_sonarr = pool.submit(_fetch_sonarr_all, watched_mod) if mounted else None

        try:
            pend = f_qbt.result()
        except Exception as e:
            errors["qbt"] = _clip(f"qBittorrent unavailable: {e}")

        if f_jf is not None:
            try:
                everyone = f_jf.result()
                if watcher not in everyone:
                    errors["jellyfin"] = f"Jellyfin has no enabled user '{watcher}'"
                    everyone = None
            except Exception as e:
                errors["jellyfin"] = _clip(f"Jellyfin unavailable: {e}")

        if f_radarr is not None:
            try:
                movies = f_radarr.result()
            except Exception as e:
                errors["radarr"] = _clip(f"Radarr unavailable: {e}")

        if f_sonarr is not None:
            try:
                sonarr_data = f_sonarr.result()
            except Exception as e:
                errors["sonarr"] = _clip(f"Sonarr unavailable: {e}")

    candidates = []
    if mounted and everyone is not None:
        if movies is not None:
            candidates.extend(_build_movie_candidates(movies, everyone, watcher=watcher))
        if sonarr_data is not None:
            series_list, ep_files = sonarr_data
            candidates.extend(_build_season_candidates(series_list, ep_files, everyone, watcher=watcher))
        candidates.sort(key=lambda c: (-c["size_bytes"], c["title"].lower(), c["id"]))

    return 200, {
        "hdd": hdd_stats(mnt, pend),
        "candidates": candidates,
        "errors": errors,
        "ts": int(time.time()),
    }


def parse_free_items(body):
    """Validate and normalize POST /pc/api/space/free items. Raises ValueError on bad input."""
    if not isinstance(body, dict):
        raise ValueError("request body must be an object")
    raw = body.get("items")
    if not isinstance(raw, list) or not raw:
        raise ValueError("items must be a non-empty list")
    if len(raw) > MAX_FREE_ITEMS:
        raise ValueError(f"too many items (max {MAX_FREE_ITEMS})")

    parsed = []
    seen = set()
    for entry in raw:
        item = _parse_one_item(entry)
        if item["id"] not in seen:
            seen.add(item["id"])
            parsed.append(item)
    return parsed


def _parse_id_string(s):
    m = re.fullmatch(r"movie:(\d+)", s)
    if m:
        rid = int(m.group(1))
        return {"id": f"movie:{rid}", "kind": "movie", "radarr_id": rid}
    m = re.fullmatch(r"(?:season|tv):(\d+):(\d+)", s)
    if m:
        sid, sn = int(m.group(1)), int(m.group(2))
        return {"id": f"season:{sid}:{sn}", "kind": "season", "series_id": sid, "season_number": sn}
    raise ValueError(f"invalid item id: {s!r}")


def _parse_one_item(entry):
    if isinstance(entry, str):
        return _parse_id_string(entry.strip())
    if not isinstance(entry, dict):
        raise ValueError("each item must be an object or id string")
    if isinstance(entry.get("id"), str) and entry["id"].strip() and not entry.get("kind"):
        return _parse_id_string(entry["id"].strip())
    kind = str(entry.get("kind") or "").strip().lower()
    if kind == "movie":
        rid = entry.get("radarr_id")
        if isinstance(rid, bool) or not isinstance(rid, int) or rid <= 0:
            raise ValueError("movie item requires positive integer radarr_id")
        return {"id": f"movie:{rid}", "kind": "movie", "radarr_id": rid}
    if kind == "season":
        sid = entry.get("series_id")
        sn = entry.get("season_number")
        if isinstance(sid, bool) or not isinstance(sid, int) or sid <= 0:
            raise ValueError("season item requires positive integer series_id")
        if isinstance(sn, bool) or not isinstance(sn, int) or sn < 0:
            raise ValueError("season item requires non-negative integer season_number")
        return {"id": f"season:{sid}:{sn}", "kind": "season", "series_id": sid, "season_number": sn}
    raise ValueError(f"unsupported item kind: {kind!r}")


def _free_movie(watched_mod, radarr_id):
    """Execute watched.py's safe deletion flow for one Radarr movie (steps 1-3 + inode verify)."""
    item_id = f"movie:{radarr_id}"
    try:
        p = watched_mod.plan(radarr_id)
    except watched_mod.Refuse as e:
        return {"id": item_id, "kind": "movie", "label": item_id, "ok": False,
                "freed_bytes": 0, "error": _clip(e), "warnings": []}, None
    except Exception as e:
        return {"id": item_id, "kind": "movie", "label": item_id, "ok": False,
                "freed_bytes": 0, "error": _clip(f"plan failed: {e}"), "warnings": []}, None

    warnings = []
    free_before = watched_mod._free()
    fd = None
    try:
        fd = os.open(p.file, os.O_PATH | os.O_NOFOLLOW)
        st = os.fstat(fd)
        if (st.st_dev, st.st_ino) != p.key:
            os.close(fd)
            fd = None
            return {"id": item_id, "kind": "movie", "label": p.label, "ok": False,
                    "freed_bytes": 0, "error": "The file changed while checking.", "warnings": []}, None
    except Exception as e:
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
        return {"id": item_id, "kind": "movie", "label": p.label, "ok": False,
                "freed_bytes": 0, "error": _clip(e), "warnings": []}, None

    err = None
    chain = [
        ("Radarr unmonitor", lambda: watched_mod._unmonitor(p)),
        ("Torrent copy", lambda: watched_mod._remove_copies(p)),
        ("Radarr delete", lambda: watched_mod._delete_movie_file(p)),
    ]
    for step_name, fn in chain:
        try:
            fn()
        except Exception as e:
            err = _clip(f"{step_name}: {e}")
            break

    nlink_after = None
    try:
        st_after = os.fstat(fd)
        nlink_after = st_after.st_nlink
    finally:
        try:
            os.close(fd)
        except OSError:
            pass

    free_after = watched_mod._free()
    freed = max(0, int(free_after - free_before))
    if err is None and nlink_after and nlink_after > 0:
        warnings.append(f"{nlink_after} hardlink(s) still remain; disk space may not be freed")

    folder = str(Path(p.file).parent) if err is None else None
    return {
        "id": item_id,
        "kind": "movie",
        "label": p.label,
        "ok": err is None,
        "freed_bytes": freed,
        "error": err,
        "warnings": warnings,
    }, folder


def _free_season(watched_mod, series_id, season_number):
    """Execute watched.py's safe deletion flow for one Sonarr season (steps 1-3 + inode verify)."""
    item_id = f"season:{series_id}:{season_number}"
    try:
        p = watched_mod.plan_season(series_id, season_number)
    except watched_mod.Refuse as e:
        return {"id": item_id, "kind": "season", "label": item_id, "ok": False,
                "freed_bytes": 0, "error": _clip(e), "warnings": []}, None
    except Exception as e:
        return {"id": item_id, "kind": "season", "label": item_id, "ok": False,
                "freed_bytes": 0, "error": _clip(f"plan failed: {e}"), "warnings": []}, None

    warnings = []
    free_before = watched_mod._free()
    fds = []
    try:
        for ef in p.files:
            fd = os.open(ef.path, os.O_PATH | os.O_NOFOLLOW)
            fds.append((ef, fd))
            st = os.fstat(fd)
            if (st.st_dev, st.st_ino) != ef.key:
                raise watched_mod.Refuse(f"{watched_mod.rel(ef.path)} changed while checking.")
    except Exception as e:
        for _, fd in fds:
            try:
                os.close(fd)
            except OSError:
                pass
        return {"id": item_id, "kind": "season", "label": p.label, "ok": False,
                "freed_bytes": 0, "error": _clip(e), "warnings": []}, None

    err = None
    chain = [
        ("Sonarr unmonitor", lambda: watched_mod._unmonitor_season(p)),
        ("Torrent copy", lambda: watched_mod._remove_copies(p, dl_root=watched_mod.SONARR_DL)),
        ("Sonarr delete", lambda: watched_mod._delete_season_files(p)),
    ]
    for step_name, fn in chain:
        try:
            fn()
        except Exception as e:
            err = _clip(f"{step_name}: {e}")
            break

    remaining = 0
    try:
        for _, fd in fds:
            if os.fstat(fd).st_nlink > 0:
                remaining += 1
    finally:
        for _, fd in fds:
            try:
                os.close(fd)
            except OSError:
                pass

    free_after = watched_mod._free()
    freed = max(0, int(free_after - free_before))
    if err is None and remaining > 0:
        warnings.append(f"{remaining}/{len(p.files)} file(s) still hardlinked; some space may not be freed")

    folder = str(Path(p.files[0].path).parent) if (err is None and p.files) else None
    return {
        "id": item_id,
        "kind": "season",
        "label": p.label,
        "ok": err is None,
        "freed_bytes": freed,
        "error": err,
        "warnings": warnings,
    }, folder


def free_space(body, watched_mod=None, agent_mod=None, mount=None):
    """POST /pc/api/space/free handler."""
    try:
        items = parse_free_items(body)
    except ValueError as e:
        return 400, {"error": str(e)}

    if watched_mod is None or agent_mod is None:
        try:
            w, a = _load_localagent()
            watched_mod = watched_mod or w
            agent_mod = agent_mod or a
        except Exception as e:
            return 503, {"error": _clip(f"localagent unavailable: {e}")}

    mnt = str(mount or getattr(watched_mod, "HDD", HDD_MNT))
    results = []
    warnings = []
    deleted_folders = []
    succeeded_labels = []

    with watched_mod._lock:
        for it in items:
            if it["kind"] == "movie":
                res, folder = _free_movie(watched_mod, it["radarr_id"])
            else:
                res, folder = _free_season(watched_mod, it["series_id"], it["season_number"])
            results.append(res)
            if res["ok"]:
                succeeded_labels.append(res["label"])
                if folder:
                    deleted_folders.append(folder)

    if succeeded_labels:
        try:
            watched_mod._refresh()
        except Exception as e:
            warnings.append(_clip(f"Jellyfin refresh failed: {e}"))
        try:
            last_folder = deleted_folders[-1] if deleted_folders else None
            watched_mod._seerr_later(last_folder, ", ".join(succeeded_labels[:3]))
        except Exception as e:
            warnings.append(_clip(f"Seerr sync failed: {e}"))

    pend = {"left": 0, "n": 0, "unknown": 0}
    try:
        pend = _pending_downloads(agent_mod, mnt)
    except Exception:
        pass

    total_freed = sum(int(r.get("freed_bytes") or 0) for r in results)
    return 200, {
        "ok": all(r["ok"] for r in results),
        "freed_bytes": total_freed,
        "results": results,
        "warnings": warnings,
        "hdd": hdd_stats(mnt, pend),
    }
