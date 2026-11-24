"""SSRF-safe Open Graph / HTML link preview fetcher with bounded in-memory LRU cache."""

from collections import OrderedDict
from html.parser import HTMLParser
import http.client
import ipaddress
import re
import socket
import ssl
import threading
import time
import urllib.parse

MAX_URL_LENGTH = 2048
MAX_REDIRECTS = 3
FETCH_TIMEOUT_SEC = 5.0
MAX_HTML_BYTES = 256 * 1024
MAX_CACHE_ENTRIES = 256
HIT_TTL_SEC = 6 * 3600
MISS_TTL_SEC = 10 * 60

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

_ALLOWED_CONTENT_TYPES = frozenset({"text/html", "application/xhtml+xml"})
_CGNAT_NET = ipaddress.ip_network("100.64.0.0/10")
_WS_RE = re.compile(r"\s+")


def _is_disallowed_ip(addr_str):
    """Return True if the IP is loopback, private, link-local, multicast, unspecified, reserved, or CGNAT."""
    try:
        ip = ipaddress.ip_address(addr_str)
    except ValueError:
        return True

    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        if _is_disallowed_ip(str(ip.ipv4_mapped)):
            return True

    if (
        ip.is_loopback
        or ip.is_private
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_unspecified
        or ip.is_reserved
    ):
        return True

    if isinstance(ip, ipaddress.IPv4Address) and ip in _CGNAT_NET:
        return True

    if not ip.is_global:
        return True

    return False


def validate_and_resolve_url(url, resolver=socket.getaddrinfo):
    """Validate scheme/host and resolve via DNS, rejecting any non-public IPs.

    Returns (parsed_url, resolved_ip, port) or raises ValueError.
    """
    if not isinstance(url, str):
        raise ValueError("url must be a string")
    raw = url.strip()
    if not raw or len(raw) > MAX_URL_LENGTH:
        raise ValueError("invalid url length")

    parsed = urllib.parse.urlsplit(raw)
    scheme = (parsed.scheme or "").lower()
    if scheme not in ("http", "https"):
        raise ValueError(f"unsupported scheme: {scheme!r}")
    if parsed.username or parsed.password:
        raise ValueError("credentials in url are not allowed")
    hostname = parsed.hostname
    if not hostname:
        raise ValueError("missing hostname")

    try:
        port = parsed.port or (443 if scheme == "https" else 80)
    except ValueError as exc:
        raise ValueError("invalid port") from exc
    if port < 1 or port > 65535:
        raise ValueError("invalid port")

    try:
        infos = resolver(hostname, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError(f"dns resolution failed: {exc}") from exc

    if not infos:
        raise ValueError("dns resolution returned no addresses")

    resolved_ips = []
    for info in infos:
        sockaddr = info[4]
        if not sockaddr:
            continue
        ip_str = sockaddr[0]
        if _is_disallowed_ip(ip_str):
            raise ValueError(f"blocked destination ip: {ip_str}")
        resolved_ips.append(ip_str)

    if not resolved_ips:
        raise ValueError("no usable addresses resolved")

    return parsed, resolved_ips[0], port


class _PinnedHTTPConnection(http.client.HTTPConnection):
    def __init__(self, connect_ip, port, timeout=FETCH_TIMEOUT_SEC):
        super().__init__(connect_ip, port=port, timeout=timeout)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, connect_ip, port, server_hostname, timeout=FETCH_TIMEOUT_SEC, context=None):
        super().__init__(connect_ip, port=port, timeout=timeout, context=context)
        self._server_hostname = server_hostname

    def connect(self):
        sock = socket.create_connection((self.host, self.port), self.timeout, self.source_address)
        self.sock = self._context.wrap_socket(sock, server_hostname=self._server_hostname)


def _format_host_header(hostname, scheme, port):
    host_part = f"[{hostname}]" if ":" in hostname else hostname
    default_port = 443 if scheme == "https" else 80
    if port != default_port:
        return f"{host_part}:{port}"
    return host_part


def _fetch_html_once(parsed, connect_ip, port, timeout=FETCH_TIMEOUT_SEC):
    """Perform a single HTTP(S) GET request to a pre-validated IP."""
    scheme = parsed.scheme.lower()
    hostname = parsed.hostname
    target = parsed.path or "/"
    if parsed.query:
        target = f"{target}?{parsed.query}"

    if scheme == "https":
        ctx = ssl.create_default_context()
        conn = _PinnedHTTPSConnection(connect_ip, port, hostname, timeout=timeout, context=ctx)
    else:
        conn = _PinnedHTTPConnection(connect_ip, port, timeout=timeout)

    headers = {
        "Host": _format_host_header(hostname, scheme, port),
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "identity",
        "Connection": "close",
    }
    try:
        conn.request("GET", target, headers=headers)
        resp = conn.getresponse()
        status = resp.status
        location = resp.getheader("Location")
        content_type = resp.getheader("Content-Type") or ""
        if status in (301, 302, 303, 307, 308) and location:
            return status, location, content_type, b""
        if status < 200 or status >= 300:
            return status, None, content_type, b""
        mime = content_type.split(";", 1)[0].strip().lower()
        if mime not in _ALLOWED_CONTENT_TYPES:
            return status, None, content_type, b""
        data = resp.read(MAX_HTML_BYTES)
        return status, None, content_type, data
    finally:
        try:
            conn.close()
        except Exception:
            pass


def _decode_html(raw_bytes, content_type):
    charset = "utf-8"
    m = re.search(r"charset=([^\s;\"']+)", content_type or "", re.IGNORECASE)
    if m:
        candidate = m.group(1).strip()
        try:
            raw_bytes[:1].decode(candidate)
            charset = candidate
        except LookupError:
            charset = "utf-8"
    return raw_bytes.decode(charset, errors="replace")


def _clean_text(val, max_len):
    if not val:
        return None
    cleaned = _WS_RE.sub(" ", val).strip()
    if not cleaned:
        return None
    if len(cleaned) > max_len:
        cleaned = cleaned[: max_len - 1].rstrip() + "…"
    return cleaned


class _MetadataParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.meta = {}
        self._in_title = False
        self._title_parts = []

    def handle_starttag(self, tag, attrs):
        t = tag.lower()
        if t == "title":
            self._in_title = True
            return
        if t != "meta":
            return
        attr_map = {}
        for k, v in attrs:
            if k and v is not None:
                attr_map[k.lower()] = v
        key = (attr_map.get("property") or attr_map.get("name") or "").strip().lower()
        content = attr_map.get("content")
        if key and content is not None and key not in self.meta:
            self.meta[key] = content

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title and data:
            self._title_parts.append(data)

    @property
    def title_tag(self):
        return "".join(self._title_parts)


def extract_metadata(html_text, final_url):
    """Extract Open Graph / Twitter Card / HTML metadata from HTML text."""
    parser = _MetadataParser()
    try:
        parser.feed(html_text)
        parser.close()
    except Exception:
        pass

    meta = parser.meta
    title = _clean_text(
        meta.get("og:title") or meta.get("twitter:title") or parser.title_tag,
        200,
    )
    description = _clean_text(
        meta.get("og:description") or meta.get("twitter:description") or meta.get("description"),
        320,
    )
    site_name = _clean_text(meta.get("og:site_name"), 80)

    raw_image = (
        meta.get("og:image")
        or meta.get("og:image:secure_url")
        or meta.get("twitter:image")
        or meta.get("twitter:image:src")
    )
    image = None
    if raw_image and raw_image.strip():
        resolved = urllib.parse.urljoin(final_url, raw_image.strip())
        img_parsed = urllib.parse.urlsplit(resolved)
        if img_parsed.scheme.lower() in ("http", "https") and img_parsed.hostname:
            image = resolved

    if not title and not image:
        return None

    domain = (urllib.parse.urlsplit(final_url).hostname or "").lower()
    if domain.startswith("www."):
        domain = domain[4:]

    return {
        "url": final_url,
        "domain": domain,
        "title": title,
        "description": description,
        "image": image,
        "site_name": site_name,
    }


class LinkPreviewFetcher:
    """SSRF-safe link preview fetcher with thread-safe bounded LRU cache."""

    def __init__(
        self,
        *,
        resolver=socket.getaddrinfo,
        requester=_fetch_html_once,
        clock=time.monotonic,
        max_entries=MAX_CACHE_ENTRIES,
        hit_ttl=HIT_TTL_SEC,
        miss_ttl=MISS_TTL_SEC,
    ):
        self._resolver = resolver
        self._requester = requester
        self._clock = clock
        self._max_entries = max_entries
        self._hit_ttl = hit_ttl
        self._miss_ttl = miss_ttl
        self._lock = threading.Lock()
        self._cache = OrderedDict()

    def _cache_get(self, key):
        now = self._clock()
        with self._lock:
            entry = self._cache.get(key)
            if entry is None:
                return False, None
            preview, expires_at = entry
            if now >= expires_at:
                del self._cache[key]
                return False, None
            self._cache.move_to_end(key)
            return True, preview

    def _cache_set(self, key, preview):
        ttl = self._hit_ttl if preview is not None else self._miss_ttl
        expires_at = self._clock() + ttl
        with self._lock:
            self._cache[key] = (preview, expires_at)
            self._cache.move_to_end(key)
            while len(self._cache) > self._max_entries:
                self._cache.popitem(last=False)

    def fetch(self, url):
        """Return preview dict or None, using the in-memory cache."""
        if not isinstance(url, str):
            return None
        key = url.strip()
        if not key:
            return None

        hit, cached = self._cache_get(key)
        if hit:
            return cached

        preview = self._fetch_uncached(key)
        self._cache_set(key, preview)
        return preview

    def _fetch_uncached(self, url):
        current_url = url
        for _ in range(MAX_REDIRECTS + 1):
            try:
                parsed, connect_ip, port = validate_and_resolve_url(current_url, resolver=self._resolver)
            except ValueError:
                return None

            try:
                status, location, content_type, body = self._requester(
                    parsed, connect_ip, port, timeout=FETCH_TIMEOUT_SEC
                )
            except Exception:
                return None

            if status in (301, 302, 303, 307, 308) and location:
                current_url = urllib.parse.urljoin(current_url, location.strip())
                continue

            if status < 200 or status >= 300 or not body:
                return None

            mime = content_type.split(";", 1)[0].strip().lower()
            if mime not in _ALLOWED_CONTENT_TYPES:
                return None

            html_text = _decode_html(body, content_type)
            return extract_metadata(html_text, current_url)

        return None


_DEFAULT_FETCHER = LinkPreviewFetcher()


def handle_link_preview(raw_url, fetcher=None):
    """HTTP route helper for GET /pc/api/chat/link-preview?url=..."""
    url = (raw_url or "").strip() if isinstance(raw_url, str) else ""
    if not url:
        return 400, {"ok": False, "error": "url parameter is required"}
    active = fetcher if fetcher is not None else _DEFAULT_FETCHER
    preview = active.fetch(url)
    return 200, {"ok": True, "preview": preview}
