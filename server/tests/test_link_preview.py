"""Unit and HTTP tests for server/link_preview.py and GET /pc/api/chat/link-preview."""

import json
import os
import socket
import sys
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import link_preview as lp  # noqa: E402
import pcapi  # noqa: E402


def _fake_resolver_for(ip_map):
    def _resolve(host, port, type=0):
        if host not in ip_map:
            raise socket.gaierror(f"unknown host {host}")
        out = []
        for ip in ip_map[host]:
            fam = socket.AF_INET6 if ":" in ip else socket.AF_INET
            sockaddr = (ip, port, 0, 0) if fam == socket.AF_INET6 else (ip, port)
            out.append((fam, socket.SOCK_STREAM, 6, "", sockaddr))
        return out

    return _resolve


class MetadataExtractionTests(unittest.TestCase):
    def test_open_graph_metadata_and_relative_image(self):
        html = """<!doctype html>
        <html>
          <head>
            <title>Fallback Title</title>
            <meta property="og:title" content="  Hello &amp; Welcome  ">
            <meta property="og:description" content="A   great\narticle about   things.">
            <meta property="og:image" content="/assets/thumb.png">
            <meta property="og:site_name" content="Example Blog">
          </head>
          <body></body>
        </html>"""
        res = lp.extract_metadata(html, "https://www.example.com/posts/hello")
        self.assertEqual(
            res,
            {
                "url": "https://www.example.com/posts/hello",
                "domain": "example.com",
                "title": "Hello & Welcome",
                "description": "A great article about things.",
                "image": "https://www.example.com/assets/thumb.png",
                "site_name": "Example Blog",
            },
        )

    def test_twitter_and_html_fallbacks(self):
        html = """<html><head>
            <title>Page Title</title>
            <meta name="description" content="Plain meta description">
            <meta name="twitter:image" content="images/card.jpg">
        </head></html>"""
        res = lp.extract_metadata(html, "https://docs.example.org/guide/intro.html")
        self.assertEqual(res["title"], "Page Title")
        self.assertEqual(res["description"], "Plain meta description")
        self.assertEqual(res["image"], "https://docs.example.org/guide/images/card.jpg")
        self.assertEqual(res["domain"], "docs.example.org")
        self.assertIsNone(res["site_name"])

    def test_rejects_non_http_image_schemes(self):
        html = """<html><head>
            <meta property="og:title" content="Safe Title">
            <meta property="og:image" content="javascript:alert(1)">
        </head></html>"""
        res = lp.extract_metadata(html, "https://example.com/")
        self.assertEqual(res["title"], "Safe Title")
        self.assertIsNone(res["image"])

        html_data = """<html><head>
            <meta property="og:title" content="Data Img">
            <meta property="og:image" content="data:image/png;base64,AAAA">
        </head></html>"""
        res2 = lp.extract_metadata(html_data, "https://example.com/")
        self.assertIsNone(res2["image"])

    def test_returns_none_when_no_title_or_image(self):
        res = lp.extract_metadata("<html><body>Just text</body></html>", "https://example.com/")
        self.assertIsNone(res)


class SsrfProtectionTests(unittest.TestCase):
    def test_blocks_non_http_schemes(self):
        for bad in [
            "file:///etc/passwd",
            "ftp://example.com/file",
            "t3-thread://v2/abc-123",
            "javascript:alert(1)",
            "gopher://127.0.0.1:70/",
            "http://user:pass@example.com/",
        ]:
            with self.assertRaises(ValueError, msg=f"expected ValueError for {bad}"):
                lp.validate_and_resolve_url(bad, resolver=_fake_resolver_for({"example.com": ["93.184.216.34"]}))

    def test_blocks_private_loopback_cgnat_and_link_local_ips(self):
        blocked_ips = [
            "127.0.0.1",
            "::1",
            "::ffff:127.0.0.1",
            "192.168.1.50",
            "10.0.0.5",
            "172.16.0.10",
            "100.64.0.1",
            "100.100.100.100",
            "100.127.255.254",
            "169.254.169.254",
            "0.0.0.0",
            "224.0.0.1",
            "fc00::1",
            "fe80::1",
        ]
        for ip in blocked_ips:
            resolver = _fake_resolver_for({"target.test": [ip]})
            with self.assertRaises(ValueError, msg=f"expected {ip} to be blocked"):
                lp.validate_and_resolve_url("https://target.test/path", resolver=resolver)

    def test_blocks_if_any_resolved_ip_is_private(self):
        resolver = _fake_resolver_for({"dual.test": ["93.184.216.34", "100.64.1.2"]})
        with self.assertRaises(ValueError):
            lp.validate_and_resolve_url("https://dual.test/", resolver=resolver)

    def test_allows_public_ip(self):
        resolver = _fake_resolver_for({"example.com": ["93.184.216.34"]})
        parsed, ip, port = lp.validate_and_resolve_url("https://example.com/a?b=1", resolver=resolver)
        self.assertEqual(parsed.hostname, "example.com")
        self.assertEqual(ip, "93.184.216.34")
        self.assertEqual(port, 443)

    def test_blocks_redirect_to_private_ip(self):
        resolver = _fake_resolver_for(
            {
                "public.test": ["93.184.216.34"],
                "internal.test": ["192.168.1.1"],
            }
        )
        calls = []

        def requester(parsed, connect_ip, port, timeout=5.0):
            calls.append((parsed.hostname, connect_ip))
            if parsed.hostname == "public.test":
                return 302, "http://internal.test/secret", "text/html", b""
            return 200, None, "text/html", b"<title>Secret</title>"

        fetcher = lp.LinkPreviewFetcher(resolver=resolver, requester=requester)
        self.assertIsNone(fetcher.fetch("https://public.test/start"))
        self.assertEqual(calls, [("public.test", "93.184.216.34")])

    def test_follows_safe_redirects_and_enforces_limit(self):
        resolver = _fake_resolver_for({"example.com": ["93.184.216.34"]})

        def requester_ok(parsed, connect_ip, port, timeout=5.0):
            if parsed.path == "/r1":
                return 301, "/r2", "", b""
            if parsed.path == "/r2":
                return 302, "/final", "", b""
            return 200, None, "text/html; charset=utf-8", b'<meta property="og:title" content="Final"><meta property="og:image" content="pic.png">'

        fetcher = lp.LinkPreviewFetcher(resolver=resolver, requester=requester_ok)
        res = fetcher.fetch("https://example.com/r1")
        self.assertIsNotNone(res)
        self.assertEqual(res["url"], "https://example.com/final")
        self.assertEqual(res["title"], "Final")
        self.assertEqual(res["image"], "https://example.com/pic.png")

        def requester_loop(parsed, connect_ip, port, timeout=5.0):
            return 302, f"{parsed.path}x", "", b""

        fetcher2 = lp.LinkPreviewFetcher(resolver=resolver, requester=requester_loop)
        self.assertIsNone(fetcher2.fetch("https://example.com/loop"))

    def test_rejects_non_html_content_type(self):
        resolver = _fake_resolver_for({"example.com": ["93.184.216.34"]})

        def requester(parsed, connect_ip, port, timeout=5.0):
            return 200, None, "application/json", b'{"title": "not html"}'

        fetcher = lp.LinkPreviewFetcher(resolver=resolver, requester=requester)
        self.assertIsNone(fetcher.fetch("https://example.com/data.json"))


class CachingTests(unittest.TestCase):
    def test_caches_hits_and_misses_with_different_ttls_and_lru_eviction(self):
        now = [1000.0]
        calls = []
        resolver = _fake_resolver_for({"example.com": ["93.184.216.34"]})

        def requester(parsed, connect_ip, port, timeout=5.0):
            calls.append(parsed.path)
            if parsed.path == "/miss":
                return 404, None, "text/html", b""
            return 200, None, "text/html", f"<title>{parsed.path}</title>".encode()

        fetcher = lp.LinkPreviewFetcher(
            resolver=resolver,
            requester=requester,
            clock=lambda: now[0],
            max_entries=2,
            hit_ttl=3600,
            miss_ttl=600,
        )

        # Hit cached
        r1 = fetcher.fetch("https://example.com/a")
        self.assertEqual(r1["title"], "/a")
        r1_again = fetcher.fetch("https://example.com/a")
        self.assertEqual(r1_again["title"], "/a")
        self.assertEqual(calls, ["/a"])

        # Miss cached for miss_ttl
        self.assertIsNone(fetcher.fetch("https://example.com/miss"))
        self.assertIsNone(fetcher.fetch("https://example.com/miss"))
        self.assertEqual(calls, ["/a", "/miss"])

        # After 601s, miss expires while hit (/a) is still valid
        now[0] += 601
        self.assertEqual(fetcher.fetch("https://example.com/a")["title"], "/a")
        self.assertIsNone(fetcher.fetch("https://example.com/miss"))
        self.assertEqual(calls, ["/a", "/miss", "/miss"])

        # LRU eviction (max_entries=2): /a was touched before /miss, so adding /b evicts /a
        fetcher.fetch("https://example.com/b")
        # Now cache holds [/miss, /b]; fetching /a must hit requester again
        fetcher.fetch("https://example.com/a")
        self.assertEqual(calls, ["/a", "/miss", "/miss", "/b", "/a"])


class _QuietHandler(pcapi.H):
    def log_message(self, fmt, *args):
        pass


class LinkPreviewHttpRouteTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _QuietHandler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def _get(self, path, headers=None):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            try:
                return exc.code, json.loads(exc.read().decode("utf-8"))
            finally:
                exc.close()

    def test_requires_auth_and_accepts_chat_token(self):
        with mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", False), \
             mock.patch.object(pcapi.chat_mod, "chat_token_authorized", return_value=False):
            code, _ = self._get("/pc/api/chat/link-preview?url=https%3A%2F%2Fexample.com")
            self.assertEqual(code, 403)

        fake_preview = {
            "url": "https://example.com",
            "domain": "example.com",
            "title": "Example Domain",
            "description": None,
            "image": None,
            "site_name": None,
        }
        with mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", False), \
             mock.patch.object(pcapi.chat_mod, "chat_token_authorized", return_value=True), \
             mock.patch.object(lp._DEFAULT_FETCHER, "fetch", return_value=fake_preview) as m_fetch:
            code, body = self._get(
                "/pc/api/chat/link-preview?url=" + urllib.parse.quote("https://example.com", safe=""),
                headers={"Authorization": "Bearer test-token"},
            )
            self.assertEqual(code, 200)
            self.assertEqual(body, {"ok": True, "preview": fake_preview})
            m_fetch.assert_called_once_with("https://example.com")

    def test_missing_url_returns_400(self):
        with mock.patch.object(pcapi.chat_mod, "chat_token_authorized", return_value=True):
            code, body = self._get("/pc/api/chat/link-preview")
            self.assertEqual(code, 400)
            self.assertFalse(body["ok"])


if __name__ == "__main__":
    unittest.main()
