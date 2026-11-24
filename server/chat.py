"""Agent Chat backend for pc-api (/pc/api/chat/*).

Provides:
- Persistent atomic-append JSONL message log (~/.local/share/pc-control/chat-history.jsonl)
  with bounded compaction/rotation and crash-safe startup recovery.
- Optional chat-only bearer token auth (~/.config/pc-control/chat-token) strictly scoped to
  /pc/api/chat/* routes (for Cloudflare Tunnel + Access without exposing any other endpoint).
- Asynchronous background dispatch to the active engine (Gemini via persistent T3 Code thread,
  Claude via headless `claude -p` in ~/agent without touching live services).
- FCM push notification hook on completed assistant replies with pccontrol://chat deep link.
"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import queue
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable

HOME = os.path.expanduser("~")
CONF_DIR = os.path.join(HOME, ".config", "pc-control")
DATA_DIR = os.environ.get("PCAPI_DATA", os.path.join(HOME, ".local", "share", "pc-control"))
CHAT_TOKEN_FILE = os.environ.get("PCAPI_CHAT_TOKEN", os.path.join(CONF_DIR, "chat-token"))
CHAT_HISTORY_FILE = os.environ.get("PCAPI_CHAT_HISTORY", os.path.join(DATA_DIR, "chat-history.jsonl"))
CHAT_UPLOADS_DIR = os.environ.get("PCAPI_CHAT_UPLOADS", os.path.join(DATA_DIR, "chat-uploads"))
AGENT_ENGINE_FILE = os.environ.get("PCAPI_AGENT_ENGINE_FILE", os.path.join(HOME, "remote-pc", "agent-engine"))

T3_MCP_URL = os.environ.get("GEMINI_AGENT_T3_URL", "http://127.0.0.1:3773/mcp")
T3_CREDENTIALS_FILE = os.environ.get(
    "GEMINI_AGENT_T3_CREDENTIALS_FILE",
    os.path.join(HOME, ".claude", ".credentials.json"),
)
T3_THREAD_FILE = os.environ.get(
    "PCAPI_T3_THREAD_FILE",
    os.path.join(HOME, ".local", "state", "gemini-agent", "t3-thread.json"),
)
LAST_CHANNEL_FILE = os.environ.get(
    "PCAPI_LAST_CHANNEL_FILE",
    os.path.join(HOME, ".local", "state", "gemini-agent", "last-channel.json"),
)
GEMINI_SETTINGS_FILE = os.environ.get(
    "GEMINI_AGENT_SETTINGS_FILE",
    os.path.join(HOME, ".config", "gemini-agent", "settings.json"),
)
CLAUDE_SETTINGS_FILE = os.environ.get(
    "CLAUDE_AGENT_SETTINGS_FILE",
    os.path.join(HOME, ".config", "claude-agent", "settings.json"),
)
CLAUDE_OAUTH_TOKEN_FILE = os.environ.get(
    "CLAUDE_AGENT_OAUTH_TOKEN_FILE",
    os.path.join(HOME, ".config", "claude-agent", "oauth-token"),
)
AGENT_WORKDIR = os.environ.get("PCAPI_AGENT_WORKDIR", os.path.join(HOME, "agent"))

VALID_ENGINES = ("claude", "gemini")
MAX_TEXT_LEN = 32000
MAX_CLIENT_ID_LEN = 128
MAX_REPLY_SNIPPET_LEN = 240
MAX_REPLY_PROMPT_LEN = 1200
MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024
MAX_ATTACHMENTS_PER_MSG = 4
DEFAULT_LIMIT = 50
MAX_LIMIT = 200
DEFAULT_MAX_LINES = 1000
DEFAULT_MAX_BYTES = 2 * 1024 * 1024
DEFAULT_KEEP_MESSAGES = 500
DISPATCH_TIMEOUT = 900.0

_T3_TERMINAL_STATUSES = {"completed", "failed", "cancelled", "interrupted", "rolled_back"}
_ATT_ID_RE = re.compile(r"^[0-9a-f]{16}$")
_MIME_RE = re.compile(r"^[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+_-]*$", re.IGNORECASE)
_IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
_UNSAFE_SERVE_MIMES = {
    "text/html",
    "application/xhtml+xml",
    "image/svg+xml",
    "application/javascript",
    "text/javascript",
    "application/x-javascript",
}


def _clip(text: Any, limit: int = 240) -> str:
    s = " ".join(str(text or "").split())
    return s if len(s) <= limit else s[: limit - 1] + "…"


def _sanitize_filename(raw: Any, mime_type: str = "") -> str:
    base = os.path.basename(str(raw or "").replace("\\", "/").strip())
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", base)
    cleaned = re.sub(r"_+", "_", cleaned).lstrip("._")
    if not cleaned:
        ext = mimetypes.guess_extension(mime_type.split(";", 1)[0].strip().lower()) if mime_type else None
        cleaned = f"attachment{ext or ''}"
    if len(cleaned) > 120:
        stem, ext = os.path.splitext(cleaned)
        ext = ext[:16]
        cleaned = (stem[: 120 - len(ext)] or "attachment") + ext
    return cleaned


def _sanitize_mime(raw: Any, filename: str) -> str:
    cand = str(raw or "").split(";", 1)[0].strip().lower()
    if cand and _MIME_RE.match(cand):
        return cand
    guessed = mimetypes.guess_type(filename)[0]
    if guessed and _MIME_RE.match(guessed):
        return guessed.lower()
    return "application/octet-stream"


def _attachment_kind(mime_type: str, filename: str) -> str:
    mt = mime_type.lower()
    if mt.startswith("image/") and mt != "image/svg+xml":
        return "image"
    if os.path.splitext(filename)[1].lower() in _IMAGE_EXTS:
        return "image"
    return "file"


def _safe_serve_mime(mime_type: str) -> str:
    mt = mime_type.split(";", 1)[0].strip().lower()
    if mt in _UNSAFE_SERVE_MIMES or not _MIME_RE.match(mt):
        return "application/octet-stream"
    return mt


def _append_attachment_paths(text: str, paths: list[str]) -> str:
    lines = [f"[attachment: {p}]" for p in paths if isinstance(p, str) and p.strip()]
    if not lines:
        return text
    block = "\n".join(lines)
    return f"{text}\n\n{block}" if text else block


def _attachment_summary(attachments: Any) -> str:
    if not isinstance(attachments, list) or not attachments:
        return ""
    valid = [a for a in attachments if isinstance(a, dict)]
    if not valid:
        return ""
    if len(valid) == 1:
        a = valid[0]
        if a.get("kind") == "image":
            return "[image]"
        name = str(a.get("name") or "").strip()
        return f"[file: {name}]" if name else "[file]"
    if all(a.get("kind") == "image" for a in valid):
        return f"[{len(valid)} images]"
    return f"[{len(valid)} attachments]"


def _reply_snippet(target: dict[str, Any], limit: int = MAX_REPLY_SNIPPET_LEN) -> str:
    txt = _clip(target.get("text"), limit)
    if txt:
        return txt
    if target.get("status") == "error":
        err = _clip(target.get("error"), limit)
        if err:
            return err
    return _attachment_summary(target.get("attachments"))


def _reply_prompt_quote(target: dict[str, Any], limit: int = MAX_REPLY_PROMPT_LEN) -> str:
    raw = str(target.get("text") or "").strip()
    if raw:
        return raw if len(raw) <= limit else raw[: limit - 1] + "…"
    if target.get("status") == "error":
        err = _clip(target.get("error"), limit)
        if err:
            return err
    return _attachment_summary(target.get("attachments"))


def _build_reply_to(target: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "id": str(target["id"]),
        "role": str(target.get("role") or "user"),
        "text": _reply_snippet(target, MAX_REPLY_SNIPPET_LEN),
    }
    eng = target.get("engine")
    if isinstance(eng, str) and eng:
        out["engine"] = eng
    return out


def _prepend_reply_context(target: dict[str, Any], user_text: str) -> str:
    role = str(target.get("role") or "user").replace('"', "")
    tid = str(target.get("id") or "").replace('"', "")
    quoted = _reply_prompt_quote(target, MAX_REPLY_PROMPT_LEN)
    tag = f'<reply_to role="{role}" id="{tid}">{quoted}</reply_to>'
    return f"{tag}\n{user_text}" if user_text else tag


def _public_message(msg: dict[str, Any]) -> dict[str, Any]:
    out = dict(msg)
    atts = out.get("attachments")
    if isinstance(atts, list):
        out["attachments"] = [
            {k: v for k, v in a.items() if k != "path"}
            for a in atts
            if isinstance(a, dict)
        ]
    return out


# ---------------------------------------------------------------- chat-only auth


def load_chat_token(token_file: str | None = None) -> str | None:
    """Read optional chat bearer token from ~/.config/pc-control/chat-token. Returns None if absent/empty."""
    path = token_file if token_file is not None else CHAT_TOKEN_FILE
    try:
        with open(path, encoding="utf-8") as f:
            tok = f.read().strip()
        return tok if tok else None
    except OSError:
        return None


def chat_token_authorized(headers: Any, token_file: str | None = None) -> bool:
    """Verify `Authorization: Bearer <token>` against the optional chat-only token file."""
    tok = load_chat_token(token_file)
    if not tok:
        return False
    auth = (headers.get("Authorization") or "").strip() if headers is not None else ""
    if not auth.lower().startswith("bearer "):
        return False
    cand = auth[7:].strip()
    return bool(cand) and secrets.compare_digest(cand, tok)


def active_engine(engine_file: str | None = None) -> str:
    """Read active engine from ~/remote-pc/agent-engine ('claude' or 'gemini', default 'claude')."""
    path = engine_file if engine_file is not None else AGENT_ENGINE_FILE
    try:
        val = Path(path).read_text(encoding="utf-8").strip().lower()
        if val in VALID_ENGINES:
            return val
    except OSError:
        pass
    return "claude"


# ---------------------------------------------------------------- persistent JSONL store


class ChatHistoryStore:
    """Thread-safe persistent JSONL message log with atomic append, compaction, and crash recovery."""

    def __init__(
        self,
        path: str | None = None,
        *,
        max_lines: int = DEFAULT_MAX_LINES,
        max_bytes: int = DEFAULT_MAX_BYTES,
        keep_messages: int = DEFAULT_KEEP_MESSAGES,
    ) -> None:
        self.path = os.path.abspath(os.path.expanduser(path or CHAT_HISTORY_FILE))
        self.max_lines = max(10, int(max_lines))
        self.max_bytes = max(4096, int(max_bytes))
        self.keep_messages = max(10, int(keep_messages))
        self._lock = threading.Lock()
        self._messages: list[dict[str, Any]] = []
        self._by_id: dict[str, dict[str, Any]] = {}
        self._by_client_id: dict[str, str] = {}
        self._line_count = 0
        self._load()

    def _load(self) -> None:
        if not os.path.exists(self.path):
            return
        ordered_ids: list[str] = []
        by_id: dict[str, dict[str, Any]] = {}
        line_count = 0
        try:
            with open(self.path, encoding="utf-8", errors="replace") as f:
                for raw in f:
                    line = raw.strip()
                    if not line:
                        continue
                    line_count += 1
                    try:
                        rec = json.loads(line)
                    except ValueError:
                        continue
                    if not isinstance(rec, dict):
                        continue
                    mid = rec.get("id")
                    if not isinstance(mid, str) or not mid:
                        continue
                    if mid not in by_id:
                        ordered_ids.append(mid)
                        by_id[mid] = rec
                    else:
                        merged = dict(by_id[mid])
                        merged.update(rec)
                        by_id[mid] = merged
        except OSError:
            return

        self._messages = [by_id[mid] for mid in ordered_ids]
        self._by_id = by_id
        self._by_client_id = {}
        for m in self._messages:
            cid = m.get("client_id")
            if isinstance(cid, str) and cid and m.get("role") == "user":
                self._by_client_id[cid] = m["id"]
        self._line_count = line_count

        # Recover any leftover pending assistant messages from an earlier crash/restart.
        recovered: list[dict[str, Any]] = []
        for m in self._messages:
            if m.get("role") == "assistant" and m.get("status") == "pending":
                m["status"] = "error"
                m["error"] = "Interrupted by server restart"
                recovered.append(dict(m))
        if recovered:
            for rec in recovered:
                self._append_raw_locked(rec)

    def _append_raw_locked(self, record: dict[str, Any]) -> None:
        parent = os.path.dirname(self.path)
        if parent:
            os.makedirs(parent, mode=0o700, exist_ok=True)
        line = (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.write(fd, line)
            os.fsync(fd)
        finally:
            os.close(fd)
        self._line_count += 1
        self._maybe_compact_locked()

    def _maybe_compact_locked(self) -> None:
        try:
            size = os.path.getsize(self.path)
        except OSError:
            size = 0
        if self._line_count <= self.max_lines and size <= self.max_bytes:
            return
        kept = self._messages[-self.keep_messages :]
        self._messages = kept
        self._by_id = {m["id"]: m for m in kept}
        self._by_client_id = {
            m["client_id"]: m["id"]
            for m in kept
            if isinstance(m.get("client_id"), str) and m.get("client_id") and m.get("role") == "user"
        }
        tmp = f"{self.path}.{os.getpid()}.{threading.get_ident()}.tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                for m in kept:
                    f.write(json.dumps(m, ensure_ascii=False, separators=(",", ":")) + "\n")
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.path)
        except Exception:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise
        self._line_count = len(kept)

    def append(self, message: dict[str, Any]) -> dict[str, Any]:
        rec = dict(message)
        with self._lock:
            self._messages.append(rec)
            self._by_id[rec["id"]] = rec
            cid = rec.get("client_id")
            if isinstance(cid, str) and cid and rec.get("role") == "user":
                self._by_client_id[cid] = rec["id"]
            self._append_raw_locked(rec)
            return dict(rec)

    def update(self, msg_id: str, **fields: Any) -> dict[str, Any] | None:
        with self._lock:
            existing = self._by_id.get(msg_id)
            if existing is None:
                return None
            existing.update(fields)
            snap = dict(existing)
            self._append_raw_locked(snap)
            return snap

    def get_by_id(self, msg_id: str) -> dict[str, Any] | None:
        if not msg_id:
            return None
        with self._lock:
            found = self._by_id.get(msg_id)
            return dict(found) if found is not None else None

    def find_by_client_id(self, client_id: str) -> tuple[dict[str, Any], dict[str, Any] | None] | None:
        if not client_id:
            return None
        with self._lock:
            uid = self._by_client_id.get(client_id)
            if not uid:
                return None
            user_msg = self._by_id.get(uid)
            if not user_msg:
                return None
            asst_msg = None
            for m in reversed(self._messages):
                if m.get("role") == "assistant" and m.get("reply_to") == uid:
                    asst_msg = dict(m)
                    break
            return dict(user_msg), asst_msg

    def get_messages(
        self,
        *,
        before: str | None = None,
        limit: int = DEFAULT_LIMIT,
    ) -> tuple[list[dict[str, Any]], bool]:
        lim = max(1, min(MAX_LIMIT, int(limit)))
        with self._lock:
            if before:
                idx = next((i for i, m in enumerate(self._messages) if m["id"] == before), None)
                if idx is None:
                    return [], False
                window = self._messages[:idx]
            else:
                window = self._messages
            has_more = len(window) > lim
            chunk = window[-lim:]
            return [dict(m) for m in chunk], has_more

    def has_pending(self) -> bool:
        with self._lock:
            return any(m.get("status") == "pending" for m in self._messages)


# ---------------------------------------------------------------- Gemini (T3 Code MCP) adapter


class T3Client:
    """Minimal stdlib MCP-over-HTTP client for T3 Code (matches ~/agent/t3call.py)."""

    def __init__(
        self,
        mcp_url: str = T3_MCP_URL,
        credentials_file: str = T3_CREDENTIALS_FILE,
        timeout: float = 60.0,
    ) -> None:
        self.mcp_url = mcp_url.rstrip("/")
        self.credentials_file = credentials_file
        self.timeout = float(timeout)
        self._lock = threading.Lock()
        self._session_id: str | None = None
        self._token: str = ""
        self._req_id = 1

    def _load_token(self) -> str:
        try:
            data = json.loads(Path(self.credentials_file).read_text(encoding="utf-8"))
        except Exception as exc:
            raise RuntimeError(f"Failed reading T3 credentials: {exc}") from None
        mcp_oauth = data.get("mcpOAuth") if isinstance(data, dict) else None
        if isinstance(mcp_oauth, dict):
            for key, val in mcp_oauth.items():
                if isinstance(key, str) and key.startswith("t3|") and isinstance(val, dict):
                    tok = val.get("accessToken")
                    if isinstance(tok, str) and tok.strip():
                        return tok.strip()
        raise RuntimeError("T3 OAuth accessToken not found in ~/.claude/.credentials.json")

    def _get_token_locked(self, force_reload: bool = False) -> str:
        if force_reload or not self._token:
            self._token = self._load_token()
        return self._token

    def _next_id_locked(self) -> int:
        self._req_id += 1
        return self._req_id

    def _post_rpc(
        self,
        body: dict[str, Any],
        *,
        token: str,
        sid: str | None = None,
    ) -> tuple[dict[str, Any] | None, str | None]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": f"Bearer {token}",
        }
        if sid:
            headers["mcp-session-id"] = sid
        req = urllib.request.Request(
            self.mcp_url,
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                sid2 = resp.headers.get("mcp-session-id")
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as exc:
            raise RuntimeError(f"T3 Code unreachable: {exc}") from None

        payload_txt = raw
        for line in raw.splitlines():
            if line.startswith("data: "):
                payload_txt = line[6:]
        if not payload_txt.strip():
            return None, sid2
        parsed = json.loads(payload_txt)
        return (parsed if isinstance(parsed, dict) else {"value": parsed}), sid2

    def _init_session_locked(self, token: str) -> str:
        _, sid = self._post_rpc(
            {
                "jsonrpc": "2.0",
                "id": self._next_id_locked(),
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "pc-api-chat", "version": "1"},
                },
            },
            token=token,
        )
        self._post_rpc(
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            token=token,
            sid=sid,
        )
        self._session_id = sid or ""
        return self._session_id

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        args = arguments if arguments is not None else {}
        with self._lock:
            token = self._get_token_locked()
            res: dict[str, Any] | None = None
            for attempt in range(2):
                try:
                    sid = self._session_id or self._init_session_locked(token)
                    res, new_sid = self._post_rpc(
                        {
                            "jsonrpc": "2.0",
                            "id": self._next_id_locked(),
                            "method": "tools/call",
                            "params": {"name": name, "arguments": args},
                        },
                        token=token,
                        sid=sid or None,
                    )
                    if new_sid:
                        self._session_id = new_sid
                    break
                except urllib.error.HTTPError as exc:
                    self._session_id = None
                    if exc.code in (401, 403) and attempt == 0:
                        try:
                            token = self._get_token_locked(force_reload=True)
                        except Exception:
                            pass
                    if attempt == 0 and exc.code in (400, 401, 403, 404, 410):
                        try:
                            exc.close()
                        except Exception:
                            pass
                        continue
                    try:
                        err_body = exc.read().decode("utf-8", errors="replace")[:200]
                    finally:
                        exc.close()
                    raise RuntimeError(f"T3 HTTP {exc.code} on {name}: {err_body}") from None

        if not isinstance(res, dict):
            return {}
        if res.get("error") is not None:
            err = res["error"]
            msg = err.get("message") if isinstance(err, dict) else str(err)
            raise RuntimeError(f"T3 RPC error on {name}: {msg}")

        result = res.get("result")
        if not isinstance(result, dict):
            return {}

        text_parts = [
            item["text"]
            for item in (result.get("content") or [])
            if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str)
        ]
        combined = "\n".join(text_parts).strip()
        if result.get("isError"):
            raise RuntimeError(f"T3 tool error on {name}: {combined or 'unknown error'}")
        if combined:
            try:
                parsed = json.loads(combined)
                return parsed if isinstance(parsed, dict) else {"value": parsed}
            except ValueError:
                return {"text": combined}
        if isinstance(result.get("structuredContent"), dict):
            return result["structuredContent"]
        return {}

    def _base_origin(self) -> str:
        u = urllib.parse.urlsplit(self.mcp_url)
        return f"{u.scheme}://{u.netloc}"

    def upload_attachment(
        self,
        file_path: str | Path,
        *,
        name: str | None = None,
        mime_type: str | None = None,
        kind: str | None = None,
    ) -> dict[str, Any]:
        p = Path(file_path)
        upload_name = name or p.name
        resolved_mime = _sanitize_mime(mime_type, upload_name)
        att_type = "image" if (kind == "image" or _attachment_kind(resolved_mime, upload_name) == "image") else "file"
        data = p.read_bytes()
        size_bytes = len(data)

        prep = self.call_tool(
            "t3_attachment_prepare_upload",
            {
                "upload": {
                    "type": att_type,
                    "name": upload_name,
                    "mimeType": resolved_mime,
                    "sizeBytes": size_bytes,
                }
            },
        )
        att_id = prep.get("attachmentId") or prep.get("id")
        upload_url = prep.get("uploadUrl")
        if not upload_url and prep.get("relativeUrl"):
            rel = str(prep["relativeUrl"])
            upload_url = f"{self._base_origin()}{rel if rel.startswith('/') else '/' + rel}"
        if not att_id or not upload_url:
            raise RuntimeError(f"t3_attachment_prepare_upload returned incomplete response: {prep}")

        with self._lock:
            token = self._get_token_locked()
        req = urllib.request.Request(
            str(upload_url),
            data=data,
            headers={
                "Content-Type": resolved_mime,
                "Authorization": f"Bearer {token}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                resp.read()
        except urllib.error.HTTPError as exc:
            try:
                err_body = exc.read().decode("utf-8", errors="replace")[:200]
            finally:
                exc.close()
            raise RuntimeError(f"T3 attachment upload HTTP {exc.code}: {err_body}") from None
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as exc:
            raise RuntimeError(f"T3 attachment upload failed: {exc}") from None

        return {
            "type": att_type,
            "id": str(att_id),
            "name": upload_name,
            "mimeType": resolved_mime,
            "sizeBytes": size_bytes,
        }


class T3GeminiAdapter:
    """Dispatches chat turns to the active persistent Gemini T3 Code thread (shared with Telegram)."""

    _WAIT_RETRY_DELAY = 0.4
    _EMPTY_REPLY_RETRIES = 3

    def __init__(
        self,
        *,
        client: T3Client | None = None,
        thread_file: str = T3_THREAD_FILE,
        settings_file: str = GEMINI_SETTINGS_FILE,
        workdir: str = AGENT_WORKDIR,
        timeout: float = DISPATCH_TIMEOUT,
        poll_wait_ms: int = 30000,
        retry_delay: float | None = None,
    ) -> None:
        self.client = client or T3Client()
        self.thread_file = thread_file
        self.settings_file = settings_file
        self.workdir = workdir
        self.timeout = float(timeout)
        self.poll_wait_ms = int(poll_wait_ms)
        self.retry_delay = self._WAIT_RETRY_DELAY if retry_delay is None else float(retry_delay)

    def _read_thread_state(self) -> dict[str, Any]:
        try:
            d = json.loads(Path(self.thread_file).read_text(encoding="utf-8"))
            return d if isinstance(d, dict) else {}
        except Exception:
            return {}

    def _write_thread_state(self, data: dict[str, Any]) -> None:
        p = Path(self.thread_file)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.parent / f".t3-thread.{os.getpid()}.{threading.get_ident()}.tmp"
        tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, p)

    def _model_selection(self) -> dict[str, Any]:
        instance_id = "antigravity"
        model = "gemini-4-neon"
        thought_level = "medium"
        try:
            cfg = json.loads(Path(self.settings_file).read_text(encoding="utf-8"))
            if isinstance(cfg, dict):
                if isinstance(cfg.get("instance_id"), str) and cfg["instance_id"].strip():
                    instance_id = cfg["instance_id"].strip()
                if isinstance(cfg.get("model"), str) and cfg["model"].strip():
                    model = cfg["model"].strip()
                if isinstance(cfg.get("thought_level"), str) and cfg["thought_level"].strip():
                    thought_level = cfg["thought_level"].strip()
        except Exception:
            pass
        return {
            "instanceId": instance_id,
            "model": model,
            "options": [{"id": "thought_level", "value": thought_level}],
        }

    def _find_or_create_project(self) -> str:
        target_real = os.path.realpath(os.path.expanduser(self.workdir))
        listing = self.client.call_tool("t3_project_list", {"limit": 100})
        for proj in listing.get("projects") or []:
            if not isinstance(proj, dict) or proj.get("deletedAt"):
                continue
            ws = proj.get("workspaceRoot")
            if isinstance(ws, str) and ws and os.path.realpath(ws) == target_real:
                pid = proj.get("id")
                if pid:
                    return str(pid)
        created = self.client.call_tool(
            "t3_project_create",
            {"title": "PC Agent", "workspaceRoot": target_real, "createWorkspaceRootIfMissing": True},
        )
        pid = created.get("id") or (
            created.get("project", {}).get("id") if isinstance(created.get("project"), dict) else None
        )
        if not pid:
            raise RuntimeError("Could not resolve or create T3 project for PC Agent")
        return str(pid)

    def _fetch_reply(self, thread_id: str, run_id: str | None, after_pos: int | None) -> str:
        parts: list[str] = []
        cur_after = after_pos
        for _ in range(20):
            args: dict[str, Any] = {
                "threadId": thread_id,
                "view": "messages",
                "limit": 100,
                "maxCharsPerItem": 20000,
            }
            if cur_after is not None:
                args["afterPosition"] = cur_after
            res = self.client.call_tool("t3_thread_read", args)
            items = res.get("items") if isinstance(res.get("items"), list) else []
            for item in items:
                if not isinstance(item, dict) or item.get("type") != "assistant_message":
                    continue
                if run_id and item.get("runId") and item.get("runId") != run_id:
                    continue
                text = str(item.get("text") or "")
                item_id = item.get("itemId")
                truncated = bool(item.get("textTruncated"))
                next_offset = item.get("nextTextOffset")
                chunks = 0
                while truncated and isinstance(next_offset, int) and item_id and chunks < 50:
                    chunks += 1
                    more = self.client.call_tool(
                        "t3_thread_read",
                        {
                            "threadId": thread_id,
                            "view": "messages",
                            "itemId": str(item_id),
                            "textOffset": next_offset,
                            "maxCharsPerItem": 20000,
                        },
                    )
                    more_items = more.get("items") if isinstance(more.get("items"), list) else []
                    matched = next(
                        (m for m in more_items if isinstance(m, dict) and m.get("itemId") == item_id),
                        more_items[0] if more_items and isinstance(more_items[0], dict) else None,
                    )
                    if not matched:
                        break
                    chunk_text = str(matched.get("text") or "")
                    if not chunk_text:
                        break
                    text += chunk_text
                    truncated = bool(matched.get("textTruncated"))
                    new_offset = matched.get("nextTextOffset")
                    if not isinstance(new_offset, int) or new_offset <= next_offset:
                        break
                    next_offset = new_offset
                if text.strip():
                    parts.append(text.strip())

            has_more = bool(res.get("hasMore"))
            next_pos = res.get("nextPosition")
            if not has_more or not isinstance(next_pos, int):
                break
            if cur_after is not None and next_pos <= cur_after:
                break
            cur_after = next_pos
        return "\n\n".join(parts)

    def _fetch_reply_with_retries(self, thread_id: str, run_id: str | None, after_pos: int | None) -> str:
        reply = self._fetch_reply(thread_id, run_id, after_pos)
        for _ in range(self._EMPTY_REPLY_RETRIES):
            if reply:
                break
            if self.retry_delay > 0:
                time.sleep(self.retry_delay)
            reply = self._fetch_reply(thread_id, run_id, after_pos)
        return reply

    @staticmethod
    def _extract_wait_state(
        waited: dict[str, Any],
        target_run_id: str | None,
        prev_latest_run_id: str | None,
    ) -> tuple[str | None, str, bool, str, bool]:
        """Return (resolved_run_id, status, timed_out, error_detail, is_stale)."""
        run_obj = waited.get("run") if isinstance(waited.get("run"), dict) else {}
        thread_obj = waited.get("thread") if isinstance(waited.get("thread"), dict) else {}

        resolved_run_id = target_run_id
        reported_run_id: str | None = None
        for cand in (
            waited.get("runId"),
            run_obj.get("runId"),
            thread_obj.get("activeRunId"),
            thread_obj.get("latestRunId"),
        ):
            if isinstance(cand, str) and cand.strip():
                cand_s = cand.strip()
                if reported_run_id is None:
                    reported_run_id = cand_s
                if not resolved_run_id and cand_s != prev_latest_run_id:
                    resolved_run_id = cand_s

        err_detail = ""
        for err_val in (
            waited.get("error"),
            waited.get("errorMessage"),
            run_obj.get("error"),
            run_obj.get("errorMessage"),
        ):
            if isinstance(err_val, str) and err_val.strip():
                err_detail = err_val.strip()
                break
            if isinstance(err_val, dict):
                msg = err_val.get("message")
                if isinstance(msg, str) and msg.strip():
                    err_detail = msg.strip()
                    break

        timed_out = bool(waited.get("timedOut") or waited.get("timed_out"))
        raw_status = str(
            run_obj.get("status")
            or waited.get("status")
            or thread_obj.get("status")
            or ""
        ).strip().lower()
        if timed_out or raw_status in ("timeout", "timed_out"):
            return resolved_run_id, "running", True, err_detail, False

        if not resolved_run_id:
            if prev_latest_run_id and reported_run_id == prev_latest_run_id:
                return None, "running", False, "", True
            if raw_status == "idle":
                return None, "running", False, "", True

        if (
            resolved_run_id
            and prev_latest_run_id
            and reported_run_id == prev_latest_run_id
            and resolved_run_id != prev_latest_run_id
        ):
            return resolved_run_id, "running", False, "", True

        recent_runs = waited.get("recentRuns") if isinstance(waited.get("recentRuns"), list) else []
        if resolved_run_id:
            for run in recent_runs:
                if isinstance(run, dict) and run.get("runId") == resolved_run_id:
                    r_status = str(run.get("status") or "").strip().lower()
                    if r_status:
                        return resolved_run_id, r_status, False, err_detail, False

        return resolved_run_id, raw_status, False, err_detail, False

    def _upload_t3_attachments(
        self, attachments: list[dict[str, Any]] | None
    ) -> tuple[list[dict[str, Any]], list[str]]:
        t3_atts: list[dict[str, Any]] = []
        fallback_paths: list[str] = []
        for att in attachments or []:
            if not isinstance(att, dict):
                continue
            path = att.get("path")
            if not isinstance(path, str) or not path:
                continue
            try:
                uploaded = self.client.upload_attachment(
                    path,
                    name=att.get("name") if isinstance(att.get("name"), str) else None,
                    mime_type=att.get("mime_type") if isinstance(att.get("mime_type"), str) else None,
                    kind=att.get("kind") if isinstance(att.get("kind"), str) else None,
                )
                t3_atts.append(uploaded)
            except Exception:
                fallback_paths.append(path)
        return t3_atts, fallback_paths

    def send(
        self,
        text: str,
        *,
        fresh: bool = False,
        attachments: list[dict[str, Any]] | None = None,
    ) -> str:
        state = self._read_thread_state()
        thread_id = state.get("thread_id") if isinstance(state.get("thread_id"), str) else None
        start_pos: int | None = None
        prev_latest_run_id: str | None = None
        run_id: str | None = None

        all_paths = [
            str(a["path"])
            for a in (attachments or [])
            if isinstance(a, dict) and isinstance(a.get("path"), str) and a["path"]
        ]
        t3_attachments, fallback_paths = self._upload_t3_attachments(attachments)
        effective_text = _append_attachment_paths(text, fallback_paths)

        if thread_id:
            try:
                head = self.client.call_tool(
                    "t3_thread_read",
                    {"threadId": thread_id, "view": "messages", "limit": 1},
                )
                head_thread = head.get("thread") if isinstance(head.get("thread"), dict) else {}
                prev_rid = head_thread.get("latestRunId")
                prev_latest_run_id = str(prev_rid).strip() if isinstance(prev_rid, str) and prev_rid.strip() else None
                items = head.get("items") if isinstance(head.get("items"), list) else []
                if items and isinstance(items[0], dict) and isinstance(items[0].get("position"), int):
                    start_pos = int(items[0]["position"])
                elif isinstance(head.get("nextPosition"), int):
                    start_pos = max(-1, int(head["nextPosition"]) - 1)
                elif isinstance(head_thread.get("itemCount"), int):
                    start_pos = int(head_thread["itemCount"]) - 1

                if t3_attachments and not fresh:
                    att_args: dict[str, Any] = {
                        "threadId": thread_id,
                        "attachments": t3_attachments,
                    }
                    if effective_text:
                        att_args["message"] = effective_text
                    try:
                        sent = self.client.call_tool("t3_thread_send_attachments", att_args)
                    except RuntimeError as att_exc:
                        msg_lower = str(att_exc).lower()
                        if "not found" in msg_lower or "does not exist" in msg_lower or "unknown thread" in msg_lower:
                            raise
                        # Fall back cleanly to plain message with [attachment: /abs/path].
                        fallback_msg = _append_attachment_paths(text, all_paths)
                        sent = self.client.call_tool(
                            "t3_thread_send",
                            {"threadId": thread_id, "message": fallback_msg, "mode": "queue"},
                        )
                else:
                    msg_text = _append_attachment_paths(text, all_paths) if (t3_attachments and fresh) else effective_text
                    send_args: dict[str, Any] = {
                        "threadId": thread_id,
                        "message": msg_text,
                        "mode": "queue",
                    }
                    if fresh:
                        send_args["freshContext"] = True
                    sent = self.client.call_tool("t3_thread_send", send_args)

                run_obj = sent.get("run") if isinstance(sent.get("run"), dict) else {}
                rid = sent.get("runId") or sent.get("queuedRunId") or run_obj.get("runId")
                run_id = str(rid) if rid else None
            except RuntimeError as exc:
                msg = str(exc).lower()
                if "not found" in msg or "does not exist" in msg or "unknown thread" in msg:
                    thread_id = None
                    start_pos = None
                    prev_latest_run_id = None
                    run_id = None
                else:
                    raise

        if not thread_id:
            project_id = (
                state.get("project_id")
                if isinstance(state.get("project_id"), str) and state.get("project_id")
                else self._find_or_create_project()
            )
            model_sel = self._model_selection()
            launch_args: dict[str, Any] = {
                "projectId": project_id,
                "title": "PC Agent (App)",
                "message": effective_text or "See attached file(s).",
                "modelSelection": model_sel,
                "runtimeMode": "full-access",
                "interactionMode": "default",
            }
            if t3_attachments:
                launch_args["attachments"] = t3_attachments
            launched = self.client.call_tool("t3_thread_launch", launch_args)
            thread_obj = launched.get("thread") if isinstance(launched.get("thread"), dict) else {}
            tid = launched.get("threadId") or thread_obj.get("threadId")
            if not tid:
                raise RuntimeError("t3_thread_launch returned no threadId")
            thread_id = str(tid)
            run_obj = launched.get("run") if isinstance(launched.get("run"), dict) else {}
            rid = launched.get("runId") or launched.get("queuedRunId") or run_obj.get("runId") or thread_obj.get("latestRunId")
            run_id = str(rid) if rid else None
            start_pos = -1
            prev_latest_run_id = None
            state.update(
                {
                    "thread_id": thread_id,
                    "project_id": project_id,
                    "title": "PC Agent (App)",
                    "instance_id": model_sel.get("instanceId"),
                }
            )
            try:
                self._write_thread_state(state)
            except OSError:
                pass

        deadline = time.monotonic() + self.timeout
        while True:
            remaining_ms = int((deadline - time.monotonic()) * 1000)
            if remaining_ms <= 0:
                raise RuntimeError("Timed out waiting for Gemini reply")
            wait_args: dict[str, Any] = {
                "threadId": thread_id,
                "timeoutMs": min(self.poll_wait_ms, remaining_ms),
            }
            if run_id:
                wait_args["runId"] = run_id
            waited = self.client.call_tool("t3_thread_wait", wait_args)
            run_id, status, timed_out, err_detail, is_stale = self._extract_wait_state(
                waited, run_id, prev_latest_run_id
            )
            if timed_out:
                continue
            if is_stale:
                if self.retry_delay > 0:
                    time.sleep(min(self.retry_delay, max(0.0, deadline - time.monotonic())))
                continue
            if status in ("failed", "cancelled", "interrupted", "rolled_back"):
                raise RuntimeError(f"Gemini run {status}: {err_detail or status}")
            if not status or status in _T3_TERMINAL_STATUSES or status == "idle":
                break

        reply = self._fetch_reply_with_retries(thread_id, run_id, start_pos)
        return reply or "(no response)"


# ---------------------------------------------------------------- Claude CLI adapter


class ClaudeCliAdapter:
    """Dispatches chat turns to Claude Code in headless print mode (`claude -p`) without touching pc-agent.service."""

    def __init__(
        self,
        *,
        claude_bin: str = "claude",
        workdir: str = AGENT_WORKDIR,
        settings_file: str = CLAUDE_SETTINGS_FILE,
        oauth_token_file: str = CLAUDE_OAUTH_TOKEN_FILE,
        timeout: float = DISPATCH_TIMEOUT,
        run_fn: Callable[..., subprocess.CompletedProcess[str]] | None = None,
    ) -> None:
        self.claude_bin = claude_bin
        self.workdir = workdir
        self.settings_file = settings_file
        self.oauth_token_file = oauth_token_file
        self.timeout = float(timeout)
        self.run_fn = run_fn or subprocess.run

    def _build_env(self) -> dict[str, str]:
        env = dict(os.environ)
        env["PATH"] = f"{HOME}/.local/bin:{HOME}/.bun/bin:{HOME}/remote-pc/bin:{env.get('PATH', '')}"
        try:
            tok = Path(self.oauth_token_file).read_text(encoding="utf-8").strip()
            if tok:
                env["CLAUDE_CODE_OAUTH_TOKEN"] = tok
        except OSError:
            pass
        return env

    def _build_argv(self, text: str, *, fresh: bool) -> list[str]:
        argv = [
            self.claude_bin,
            "-p",
            text,
            "--output-format",
            "json",
            "--dangerously-skip-permissions",
        ]
        try:
            cfg = json.loads(Path(self.settings_file).read_text(encoding="utf-8"))
            if isinstance(cfg, dict):
                model = cfg.get("model")
                effort = cfg.get("effort")
                if isinstance(model, str) and model.strip():
                    argv.extend(["--model", model.strip()])
                if isinstance(effort, str) and effort.strip():
                    argv.extend(["--effort", effort.strip()])
        except Exception:
            pass
        if not fresh:
            argv.append("--continue")
        return argv

    def send(
        self,
        text: str,
        *,
        fresh: bool = False,
        attachments: list[dict[str, Any]] | None = None,
    ) -> str:
        paths = [
            str(a["path"])
            for a in (attachments or [])
            if isinstance(a, dict) and isinstance(a.get("path"), str) and a["path"]
        ]
        prompt = _append_attachment_paths(text, paths)
        argv = self._build_argv(prompt, fresh=fresh)
        cwd = self.workdir if os.path.isdir(self.workdir) else HOME
        try:
            res = self.run_fn(
                argv,
                cwd=cwd,
                env=self._build_env(),
                stdin=subprocess.DEVNULL,
                capture_output=True,
                text=True,
                timeout=self.timeout,
            )
        except FileNotFoundError:
            raise RuntimeError("claude CLI not found on PATH") from None
        except subprocess.TimeoutExpired:
            raise RuntimeError("Timed out waiting for Claude reply") from None

        if res.returncode != 0:
            err = (res.stderr or res.stdout or f"exit {res.returncode}").strip()
            raise RuntimeError(_clip(f"Claude failed: {err}", 300))

        raw = (res.stdout or "").strip()
        if not raw:
            return "(no response)"
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                if parsed.get("is_error"):
                    raise RuntimeError(_clip(parsed.get("result") or "Claude returned an error", 300))
                val = parsed.get("result")
                if isinstance(val, str):
                    return val.strip() or "(no response)"
        except ValueError:
            pass
        return raw


# ---------------------------------------------------------------- ChatManager


class ChatManager:
    """Orchestrates chat history, async engine dispatch, session resets, and FCM push notifications."""

    def __init__(
        self,
        *,
        store: ChatHistoryStore | None = None,
        engine_fn: Callable[[], str] | None = None,
        gemini_adapter: Any | None = None,
        claude_adapter: Any | None = None,
        notify: Any | None = None,
        clock: Callable[[], float] | None = None,
        uploads_dir: str | None = None,
        last_channel_file: str | None = None,
    ) -> None:
        self.store = store or ChatHistoryStore()
        self.engine_fn = engine_fn or active_engine
        self.gemini_adapter = gemini_adapter or T3GeminiAdapter()
        self.claude_adapter = claude_adapter or ClaudeCliAdapter()
        self.notify = notify
        self.clock = clock or time.time
        self.uploads_dir = os.path.abspath(os.path.expanduser(uploads_dir or CHAT_UPLOADS_DIR))
        self.last_channel_file = last_channel_file
        self._lock = threading.Lock()
        self._fresh_next: dict[str, bool] = {"claude": False, "gemini": False}
        self._in_flight = 0
        self._queue: queue.Queue[dict[str, Any] | None] = queue.Queue()
        self._worker = threading.Thread(target=self._worker_loop, name="pcapi-chat-worker", daemon=True)
        self._worker.start()

    def _record_last_channel(self, ts: float) -> None:
        path = self.last_channel_file or os.environ.get("PCAPI_LAST_CHANNEL_FILE") or LAST_CHANNEL_FILE
        if not path:
            return
        try:
            target = os.path.abspath(os.path.expanduser(path))
            parent = os.path.dirname(target)
            if parent:
                os.makedirs(parent, mode=0o700, exist_ok=True)
            tmp = f"{target}.{os.getpid()}.{threading.get_ident()}.tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump({"channel": "app", "ts": float(ts)}, f, separators=(",", ":"))
                    f.write("\n")
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, target)
            except Exception:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                raise
        except Exception:
            # Best-effort: never break chat send.
            pass

    def _current_engine(self) -> str:
        eng = self.engine_fn()
        return eng if eng in VALID_ENGINES else "claude"

    def is_running(self) -> bool:
        with self._lock:
            return self._in_flight > 0 or self.store.has_pending()

    def _new_id(self) -> str:
        return secrets.token_hex(8)

    def _worker_loop(self) -> None:
        while True:
            job = self._queue.get()
            if job is None:
                self._queue.task_done()
                return
            try:
                self._execute_job(job)
            finally:
                with self._lock:
                    self._in_flight = max(0, self._in_flight - 1)
                self._queue.task_done()

    def _execute_job(self, job: dict[str, Any]) -> None:
        engine = job["engine"]
        user_id = job["user_id"]
        text = job["text"]
        fresh = bool(job.get("fresh", False))
        attachments = job.get("attachments") or []
        adapter = self.gemini_adapter if engine == "gemini" else self.claude_adapter
        label = "Gemini" if engine == "gemini" else "Claude"
        try:
            if attachments:
                raw_reply = adapter.send(text, fresh=fresh, attachments=attachments)
            else:
                raw_reply = adapter.send(text, fresh=fresh)
            reply = str(raw_reply or "").strip() or "(no response)"
            self.store.append(
                {
                    "id": self._new_id(),
                    "role": "assistant",
                    "engine": engine,
                    "text": reply,
                    "created_at": float(self.clock()),
                    "status": "done",
                    "error": None,
                    "reply_to": user_id,
                }
            )
            self._push_reply(label, reply, is_error=False)
        except Exception as exc:
            err = _clip(str(exc) or type(exc).__name__, 300)
            self.store.append(
                {
                    "id": self._new_id(),
                    "role": "assistant",
                    "engine": engine,
                    "text": "",
                    "created_at": float(self.clock()),
                    "status": "error",
                    "error": err,
                    "reply_to": user_id,
                }
            )
            self._push_reply(f"{label} · Error", err, is_error=True)

    def _push_reply(self, title: str, body: str, *, is_error: bool = False) -> bool:
        if self.notify is None:
            return False
        preview = _clip(body, 240)
        try:
            if hasattr(self.notify, "send_async"):
                self.notify.send_async("chat", title, preview, link="pccontrol://chat")
                return True
            if hasattr(self.notify, "send"):
                self.notify.send("chat", title, preview, link="pccontrol://chat")
                return True
        except Exception as exc:
            print(f"chat notify failed: {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
        return False

    def wait_idle(self, timeout: float = 5.0) -> bool:
        """Test helper: wait until queued jobs finish."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.is_running() and self._queue.empty():
                return True
            time.sleep(0.02)
        return not self.is_running() and self._queue.empty()

    # ---- attachment storage

    def _resolve_uploaded_file(self, att_id: str) -> tuple[str, str] | None:
        if not isinstance(att_id, str) or not _ATT_ID_RE.match(att_id):
            return None
        if not os.path.isdir(self.uploads_dir):
            return None
        prefix = f"{att_id}_"
        root_real = os.path.realpath(self.uploads_dir)
        try:
            for entry in os.listdir(self.uploads_dir):
                if not entry.startswith(prefix):
                    continue
                cand = os.path.realpath(os.path.join(self.uploads_dir, entry))
                if not cand.startswith(root_real + os.sep):
                    continue
                if os.path.isfile(cand):
                    return cand, entry[len(prefix) :]
        except OSError:
            return None
        return None

    def handle_upload(self, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        raw_b64 = body.get("data_base64")
        if not isinstance(raw_b64, str) or not raw_b64.strip():
            return 400, {"error": "data_base64 is required"}
        cleaned_b64 = "".join(raw_b64.split())
        # Quick upper-bound check before allocating decoded bytes.
        if len(cleaned_b64) > ((MAX_ATTACHMENT_BYTES + 2) // 3) * 4 + 4:
            return 413, {"error": "attachment exceeds 15 MB limit"}
        try:
            data = base64.b64decode(cleaned_b64, validate=True)
        except ValueError:
            return 400, {"error": "data_base64 is not valid base64"}
        if not data:
            return 400, {"error": "attachment is empty"}
        if len(data) > MAX_ATTACHMENT_BYTES:
            return 413, {"error": "attachment exceeds 15 MB limit"}

        raw_mime = body.get("mime_type")
        safe_name = _sanitize_filename(body.get("name"), str(raw_mime or ""))
        mime_type = _sanitize_mime(raw_mime, safe_name)
        kind = _attachment_kind(mime_type, safe_name)
        att_id = self._new_id()

        os.makedirs(self.uploads_dir, mode=0o700, exist_ok=True)
        dest = os.path.join(self.uploads_dir, f"{att_id}_{safe_name}")
        fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
        except Exception:
            try:
                os.remove(dest)
            except OSError:
                pass
            raise

        return 200, {
            "id": att_id,
            "name": safe_name,
            "mime_type": mime_type,
            "size_bytes": len(data),
            "kind": kind,
            "url": f"/pc/api/chat/file/{att_id}",
        }

    def handle_get_file(self, att_id: str) -> tuple[bytes, str, str] | None:
        found = self._resolve_uploaded_file(att_id)
        if found is None:
            return None
        abs_path, safe_name = found
        try:
            data = Path(abs_path).read_bytes()
        except OSError:
            return None
        mime_type = _safe_serve_mime(_sanitize_mime(None, safe_name))
        return data, mime_type, safe_name

    # ---- HTTP handlers

    def handle_get_messages(self, qs: dict[str, list[str]]) -> tuple[int, dict[str, Any]]:
        before = (qs.get("before") or [""])[0].strip() or None
        raw_limit = (qs.get("limit") or [str(DEFAULT_LIMIT)])[0]
        try:
            limit = int(raw_limit)
        except ValueError:
            return 400, {"error": "limit must be an integer"}
        messages, has_more = self.store.get_messages(before=before, limit=limit)
        return 200, {
            "messages": [_public_message(m) for m in messages],
            "engine": self._current_engine(),
            "running": self.is_running(),
            "has_more": has_more,
            "ts": int(self.clock()),
        }

    def handle_send(self, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        raw_text = body.get("text")
        if raw_text is None:
            text = ""
        elif isinstance(raw_text, str):
            text = raw_text.strip()
        else:
            return 400, {"error": "text must be a string"}
        if len(text) > MAX_TEXT_LEN:
            return 400, {"error": f"text exceeds {MAX_TEXT_LEN} characters"}

        raw_atts = body.get("attachments")
        resolved_atts: list[dict[str, Any]] = []
        if raw_atts is not None:
            if not isinstance(raw_atts, list):
                return 400, {"error": "attachments must be an array"}
            if len(raw_atts) > MAX_ATTACHMENTS_PER_MSG:
                return 400, {"error": f"at most {MAX_ATTACHMENTS_PER_MSG} attachments allowed"}
            for item in raw_atts:
                if isinstance(item, str):
                    att_id = item.strip()
                    req_name = None
                    req_mime = None
                elif isinstance(item, dict):
                    att_id = str(item.get("id") or "").strip()
                    req_name = item.get("name")
                    req_mime = item.get("mime_type")
                else:
                    return 400, {"error": "invalid attachment entry"}
                found = self._resolve_uploaded_file(att_id)
                if found is None:
                    return 400, {"error": f"unknown attachment id: {att_id or '(empty)'}"}
                abs_path, stored_name = found
                try:
                    size_bytes = os.path.getsize(abs_path)
                except OSError:
                    return 400, {"error": f"attachment unavailable: {att_id}"}
                display_name = _sanitize_filename(req_name, str(req_mime or "")) if isinstance(req_name, str) and req_name.strip() else stored_name
                mime_type = _sanitize_mime(req_mime, stored_name)
                kind = _attachment_kind(mime_type, stored_name)
                resolved_atts.append(
                    {
                        "id": att_id,
                        "name": display_name,
                        "mime_type": mime_type,
                        "size_bytes": size_bytes,
                        "kind": kind,
                        "url": f"/pc/api/chat/file/{att_id}",
                        "path": abs_path,
                    }
                )

        if not text and not resolved_atts:
            return 400, {"error": "text or attachments is required"}

        raw_cid = body.get("client_id")
        client_id: str | None = None
        if raw_cid is not None:
            if not isinstance(raw_cid, str) or len(raw_cid.strip()) > MAX_CLIENT_ID_LEN:
                return 400, {"error": "invalid client_id"}
            client_id = raw_cid.strip() or None

        raw_reply_id = body.get("reply_to_id")
        reply_to_id: str | None = None
        if raw_reply_id is not None:
            if not isinstance(raw_reply_id, str) or len(raw_reply_id.strip()) > MAX_CLIENT_ID_LEN:
                return 400, {"error": "invalid reply_to_id"}
            reply_to_id = raw_reply_id.strip() or None

        with self._lock:
            if client_id:
                existing = self.store.find_by_client_id(client_id)
                if existing is not None:
                    user_msg, asst_msg = existing
                    return 200, {
                        "ok": True,
                        "deduped": True,
                        "user_message": _public_message(user_msg),
                        "assistant_message": _public_message(asst_msg) if asst_msg is not None else None,
                        "engine": self._current_engine(),
                        "running": self._in_flight > 0 or self.store.has_pending(),
                    }

            engine = self._current_engine()
            now = float(self.clock())
            user_id = self._new_id()

            reply_target = self.store.get_by_id(reply_to_id) if reply_to_id else None
            reply_to_obj = _build_reply_to(reply_target) if reply_target is not None else None
            dispatch_text = _prepend_reply_context(reply_target, text) if reply_target is not None else text

            user_msg = self.store.append(
                {
                    "id": user_id,
                    "role": "user",
                    "engine": engine,
                    "text": text,
                    "created_at": now,
                    "status": "done",
                    "error": None,
                    **({"client_id": client_id} if client_id else {}),
                    **({"attachments": resolved_atts} if resolved_atts else {}),
                    **({"reply_to": reply_to_obj} if reply_to_obj is not None else {}),
                }
            )
            fresh = self._fresh_next.get(engine, False)
            if fresh:
                self._fresh_next[engine] = False
            self._in_flight += 1
            self._queue.put(
                {
                    "engine": engine,
                    "text": dispatch_text,
                    "user_id": user_id,
                    "fresh": fresh,
                    **({"attachments": resolved_atts} if resolved_atts else {}),
                }
            )

        self._record_last_channel(now)

        return 202, {
            "ok": True,
            "deduped": False,
            "user_message": _public_message(user_msg),
            "assistant_message": None,
            "engine": engine,
            "running": True,
        }

    def handle_push(self, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        raw_text = body.get("text")
        if not isinstance(raw_text, str):
            return 400, {"error": "text must be a string"}
        text = raw_text.strip()
        if not text:
            return 400, {"error": "text is required"}
        if len(text) > MAX_TEXT_LEN:
            return 400, {"error": f"text exceeds {MAX_TEXT_LEN} characters"}

        raw_engine = body.get("engine")
        if raw_engine is None:
            engine = self._current_engine()
        elif isinstance(raw_engine, str) and raw_engine.strip().lower() in VALID_ENGINES:
            engine = raw_engine.strip().lower()
        else:
            return 400, {"error": "engine must be claude or gemini"}

        msg = self.store.append(
            {
                "id": self._new_id(),
                "role": "assistant",
                "engine": engine,
                "text": text,
                "created_at": float(self.clock()),
                "status": "done",
                "error": None,
            }
        )
        label = "Gemini" if engine == "gemini" else "Claude"
        pushed = self._push_reply(label, text, is_error=False)
        return 200, {
            "ok": True,
            "message": _public_message(msg),
            "pushed": pushed,
        }

    def handle_new(self, _body: dict[str, Any] | None = None) -> tuple[int, dict[str, Any]]:
        engine = self._current_engine()
        with self._lock:
            self._fresh_next[engine] = True
            boundary = self.store.append(
                {
                    "id": self._new_id(),
                    "role": "system",
                    "kind": "session_break",
                    "engine": engine,
                    "text": "New conversation",
                    "created_at": float(self.clock()),
                    "status": "done",
                    "error": None,
                }
            )
            running = self._in_flight > 0 or self.store.has_pending()
        return 200, {
            "ok": True,
            "engine": engine,
            "boundary_message": boundary,
            "running": running,
        }
