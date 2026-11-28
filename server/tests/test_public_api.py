"""Unit tests for public API dual-auth, /pc/api/public-config, auxiliary local proxies, and SSE keepalives."""
import json
import os
import queue
import socket
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import chat as chat_mod  # noqa: E402
import pcapi  # noqa: E402


class _FakePush:
    def configured(self):
        return False

    def status(self, token=None):
        return {"configured": False, "devices": 0}


class _EchoUpstreamHandler(BaseHTTPRequestHandler):
    """Records every proxied request and echoes details or returns custom status/content-type."""

    recorded = []

    def log_message(self, *a):
        pass

    def _handle(self, method):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n > 0 else b""
        self.recorded.append(
            {
                "method": method,
                "path": self.path,
                "content_type": self.headers.get("Content-Type"),
                "authorization": self.headers.get("Authorization"),
                "cf_id": self.headers.get("CF-Access-Client-Id"),
                "body": body.decode("utf-8", errors="replace"),
            }
        )
        if "status=418" in self.path:
            payload = b"teapot-text"
            self.send_response(418)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if "binary=1" in self.path:
            payload = b"\x89PNG\r\n\x1a\n1234"
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        payload = json.dumps(
            {
                "ok": True,
                "method": method,
                "path": self.path,
                "body": body.decode("utf-8", errors="replace"),
                "content_type": self.headers.get("Content-Type"),
            }
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


class PublicApiAuthAndProxyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

        self.local_tok_file = os.path.join(self.tmp.name, "local-token")
        self.api_tok_file = os.path.join(self.tmp.name, "api-token")
        self.cf_tok_file = os.path.join(self.tmp.name, "cf-access-token.json")
        self.chat_tok_file = os.path.join(self.tmp.name, "chat-token")
        self.hook_file = os.path.join(self.tmp.name, "hook-secret")

        self.api_token = "api-secret-" + "a" * 32
        self.chat_token = "chat-secret-" + "c" * 32
        self.hook_secret = "hook-secret-" + "h" * 32
        with open(self.api_tok_file, "w", encoding="utf-8") as f:
            f.write(self.api_token + "\n")
        with open(self.cf_tok_file, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "client_id": "cf-id-123.access",
                    "client_secret": "cf-secret-456",
                    "hostname": "api.neekhar.dev",
                },
                f,
            )
        with open(self.chat_tok_file, "w", encoding="utf-8") as f:
            f.write(self.chat_token + "\n")
        with open(self.hook_file, "w", encoding="utf-8") as f:
            f.write(self.hook_secret + "\n")

        _EchoUpstreamHandler.recorded.clear()
        self.upstream = ThreadingHTTPServer(("127.0.0.1", 0), _EchoUpstreamHandler)
        threading.Thread(target=self.upstream.serve_forever, daemon=True).start()
        self.addCleanup(self.upstream.server_close)
        self.addCleanup(self.upstream.shutdown)
        up_base = f"http://127.0.0.1:{self.upstream.server_address[1]}"

        self.fake_push = _FakePush()
        self.fake_notify = pcapi.Notify(self.fake_push, data_dir=self.tmp.name, token_file="/nonexistent", chat="")
        self.fake_notify.update({"categories": {"grabbed": {"telegram": False}}})

        self.srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.addCleanup(self.srv.server_close)
        self.addCleanup(self.srv.shutdown)
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

        self.patches = [
            mock.patch.object(pcapi, "LOCAL_TOKEN_FILE", self.local_tok_file),
            mock.patch.object(pcapi, "API_TOKEN_FILE", self.api_tok_file),
            mock.patch.object(pcapi, "CF_ACCESS_TOKEN_FILE", self.cf_tok_file),
            mock.patch.object(pcapi, "HOOK_SECRET_FILE", self.hook_file),
            mock.patch.object(chat_mod, "CHAT_TOKEN_FILE", self.chat_tok_file),
            mock.patch.object(pcapi, "PUSH", self.fake_push),
            mock.patch.object(pcapi, "NOTIFY", self.fake_notify),
            mock.patch.object(pcapi, "ALLOWED_LOGINS", {"owner@example.com"}),
            mock.patch.dict(
                pcapi.PROXY_UPSTREAMS,
                {
                    "vitals": f"{up_base}/vitals/api",
                    "detect": f"{up_base}/detect",
                    "ptz": f"{up_base}/ptz",
                    "music": f"{up_base}/music",
                },
                clear=True,
            ),
            mock.patch.dict(os.environ, {}, clear=False),
        ]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)
        os.environ.pop("PCAPI_API_TOKEN", None)
        os.environ.pop("PCAPI_CF_ACCESS_TOKEN", None)
        os.environ.pop("PCAPI_PUBLIC_API_URL", None)

        self.local_tok = pcapi.local_token()
        self.cf_headers = {
            "X-Forwarded-For": "203.0.113.42",
            "Authorization": f"Bearer {self.api_token}",
        }
        self.ts_headers = {
            "Tailscale-User-Login": "owner@example.com",
            "Tailscale-User-Name": "Owner Name",
            "X-Forwarded-For": "100.64.0.2",
        }

    def _req(self, method, path, body=None, headers=None, raw_body=None, parse_json=True):
        hdrs = dict(headers or {})
        if raw_body is not None:
            data = raw_body
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
        else:
            data = None
        req = urllib.request.Request(self.base + path, data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                raw = resp.read()
                ct = resp.headers.get("Content-Type", "")
                return resp.status, (json.loads(raw) if parse_json else raw), ct
        except urllib.error.HTTPError as e:
            raw = e.read()
            ct = e.headers.get("Content-Type", "") if e.headers else ""
            e.close()
            if parse_json:
                try:
                    return e.code, json.loads(raw), ct
                except ValueError:
                    return e.code, raw.decode("utf-8", errors="replace"), ct
            return e.code, raw, ct

    def test_api_token_accepted_on_general_endpoints_and_whoami(self):
        # /pc/api/info
        code, data, _ = self._req("GET", "/pc/api/info", headers=self.cf_headers)
        self.assertEqual(code, 200)
        self.assertIn("host", data)

        # /pc/api/whoami -> auth = api_token + non-null login/name
        code, who, _ = self._req("GET", "/pc/api/whoami", headers=self.cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(who["auth"], "api_token")
        self.assertTrue(isinstance(who["login"], str) and who["login"])
        self.assertTrue(isinstance(who["name"], str) and who["name"])
        self.assertTrue(who["proxied"])

        # /pc/api/whoami with Tailscale auth -> auth = tailscale
        code, who_ts, _ = self._req("GET", "/pc/api/whoami", headers=self.ts_headers)
        self.assertEqual(code, 200)
        self.assertEqual(who_ts["auth"], "tailscale")
        self.assertEqual(who_ts["login"], "owner@example.com")
        self.assertEqual(who_ts["name"], "Owner Name")

        # /pc/api/whoami with local token -> auth = local
        code, who_loc, _ = self._req(
            "GET", "/pc/api/whoami", headers={"Authorization": f"Bearer {self.local_tok}"}
        )
        self.assertEqual(code, 200)
        self.assertEqual(who_loc["auth"], "local")

        # /pc/api/services
        with mock.patch.object(pcapi, "list_services", return_value=[{"name": "pc-api.service"}]):
            code, svcs, _ = self._req("GET", "/pc/api/services", headers=self.cf_headers)
            self.assertEqual(code, 200)
            self.assertEqual(svcs["services"][0]["name"], "pc-api.service")

        # /pc/api/root/pending
        with mock.patch.object(pcapi, "rootd_or_503", return_value=(200, {"requests": []})):
            code, pend, _ = self._req("GET", "/pc/api/root/pending", headers=self.cf_headers)
            self.assertEqual(code, 200)
            self.assertEqual(pend, {"requests": []})

        # Invalid api token rejected
        code, _, _ = self._req(
            "GET",
            "/pc/api/info",
            headers={"X-Forwarded-For": "203.0.113.42", "Authorization": "Bearer wrong-token"},
        )
        self.assertEqual(code, 403)

    def test_api_token_rejected_on_restricted_endpoints(self):
        # POST /pc/api/chat/push -> strictly localhost + local_token
        code, _, _ = self._req("POST", "/pc/api/chat/push", {"text": "hi"}, headers=self.cf_headers)
        self.assertEqual(code, 403)
        code, _, _ = self._req(
            "POST",
            "/pc/api/chat/push",
            {"text": "hi"},
            headers={"Authorization": f"Bearer {self.api_token}"},
        )
        self.assertEqual(code, 403)

        # POST /pc/api/ota/trigger -> strictly localhost + local_token
        code, _, _ = self._req("POST", "/pc/api/ota/trigger", {"version": "1.0.0"}, headers=self.cf_headers)
        self.assertEqual(code, 403)
        code, _, _ = self._req(
            "POST",
            "/pc/api/ota/trigger",
            {"version": "1.0.0"},
            headers={"Authorization": f"Bearer {self.api_token}"},
        )
        self.assertEqual(code, 403)

        # POST /pc/api/hook/radarr -> strictly localhost + hook_secret
        code, _, _ = self._req("POST", "/pc/api/hook/radarr", {"eventType": "Test"}, headers=self.cf_headers)
        self.assertEqual(code, 403)
        # Even with valid hook_secret, proxied request (X-Forwarded-For) is rejected
        code, _, _ = self._req(
            "POST",
            "/pc/api/hook/radarr",
            {"eventType": "Test"},
            headers={"X-Forwarded-For": "203.0.113.42", "Authorization": f"Bearer {self.hook_secret}"},
        )
        self.assertEqual(code, 403)
        # Localhost with api_token instead of hook_secret -> 401
        code, _, _ = self._req(
            "POST",
            "/pc/api/hook/radarr",
            {"eventType": "Test"},
            headers={"Authorization": f"Bearer {self.api_token}"},
        )
        self.assertEqual(code, 401)
        # Localhost with valid hook_secret -> 200
        code, hook_res, _ = self._req(
            "POST",
            "/pc/api/hook/radarr",
            {"eventType": "Test"},
            headers={"Authorization": f"Bearer {self.hook_secret}"},
        )
        self.assertEqual(code, 200)
        self.assertTrue(hook_res.get("ok"))

        # GET /pc/api/public-config -> strictly Tailscale or local_token, NEVER Bearer api_token or chat_token
        code, _, _ = self._req("GET", "/pc/api/public-config", headers=self.cf_headers)
        self.assertEqual(code, 403)
        code, _, _ = self._req(
            "GET",
            "/pc/api/public-config",
            headers={"Authorization": f"Bearer {self.api_token}"},
        )
        self.assertEqual(code, 403)
        code, _, _ = self._req(
            "GET",
            "/pc/api/public-config",
            headers={"X-Forwarded-For": "203.0.113.42", "Authorization": f"Bearer {self.chat_token}"},
        )
        self.assertEqual(code, 403)

    def test_public_config_succeeds_for_tailscale_and_local_and_env_overrides(self):
        # Tailscale auth
        code, cfg, _ = self._req("GET", "/pc/api/public-config", headers=self.ts_headers)
        self.assertEqual(code, 200)
        self.assertEqual(
            cfg,
            {
                "api_url": "https://api.neekhar.dev",
                "api_token": self.api_token,
                "cf_access_client_id": "cf-id-123.access",
                "cf_access_client_secret": "cf-secret-456",
            },
        )

        # Localhost + local_token
        code, cfg_loc, _ = self._req(
            "GET", "/pc/api/public-config", headers={"Authorization": f"Bearer {self.local_tok}"}
        )
        self.assertEqual(code, 200)
        self.assertEqual(cfg_loc["api_token"], self.api_token)

        # Env var overrides (literal token + JSON string for CF access token)
        with mock.patch.dict(
            os.environ,
            {
                "PCAPI_API_TOKEN": "env-override-token",
                "PCAPI_CF_ACCESS_TOKEN": json.dumps(
                    {
                        "client_id": "env-cf-id",
                        "client_secret": "env-cf-secret",
                        "hostname": "custom.neekhar.dev",
                    }
                ),
            },
        ):
            code, cfg_env, _ = self._req("GET", "/pc/api/public-config", headers=self.ts_headers)
            self.assertEqual(code, 200)
            self.assertEqual(
                cfg_env,
                {
                    "api_url": "https://custom.neekhar.dev",
                    "api_token": "env-override-token",
                    "cf_access_client_id": "env-cf-id",
                    "cf_access_client_secret": "env-cf-secret",
                },
            )
            # Env override token works for Bearer auth too
            code, info, _ = self._req(
                "GET",
                "/pc/api/info",
                headers={"X-Forwarded-For": "203.0.113.42", "Authorization": "Bearer env-override-token"},
            )
            self.assertEqual(code, 200)
            self.assertIn("host", info)

    def test_chat_token_backward_compatibility_scoped_to_chat(self):
        chat_hdrs = {"X-Forwarded-For": "203.0.113.42", "Authorization": f"Bearer {self.chat_token}"}
        # Rejected on non-chat endpoint
        code, _, _ = self._req("GET", "/pc/api/info", headers=chat_hdrs)
        self.assertEqual(code, 403)
        # Accepted on chat endpoint
        with mock.patch.object(pcapi, "AGENT_SETTINGS") as mock_as:
            mock_as.get.return_value = {"engine": "claude"}
            code, data, _ = self._req("GET", "/pc/api/chat/agent", headers=chat_hdrs)
            self.assertEqual(code, 200)
            self.assertEqual(data, {"engine": "claude"})

    def test_proxy_endpoints_forward_method_query_body_headers_and_block_traversal(self):
        # GET /pc/api/vitals?procs=1 -> /vitals/api?procs=1
        code, data, _ = self._req("GET", "/pc/api/vitals?procs=1&full=1", headers=self.cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["method"], "GET")
        self.assertEqual(data["path"], "/vitals/api?procs=1&full=1")
        # Auth headers MUST NOT be forwarded to local upstream
        self.assertIsNone(_EchoUpstreamHandler.recorded[-1]["authorization"])
        self.assertIsNone(_EchoUpstreamHandler.recorded[-1]["cf_id"])

        # GET /pc/api/detect/state
        code, data, _ = self._req("GET", "/pc/api/detect/state", headers=self.cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["path"], "/detect/state")

        # POST /pc/api/detect/toggle?on=1 with body and Content-Type
        code, data, _ = self._req(
            "POST",
            "/pc/api/detect/toggle?on=1",
            raw_body=b'{"reason":"test"}',
            headers={**self.cf_headers, "Content-Type": "application/json; charset=utf-8"},
        )
        self.assertEqual(code, 200)
        self.assertEqual(data["method"], "POST")
        self.assertEqual(data["path"], "/detect/toggle?on=1")
        self.assertEqual(data["body"], '{"reason":"test"}')
        self.assertIn("application/json", data["content_type"])

        # POST /pc/api/ptz/move?dir=left
        code, data, _ = self._req("POST", "/pc/api/ptz/move?dir=left", raw_body=b"{}", headers=self.ts_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["method"], "POST")
        self.assertEqual(data["path"], "/ptz/move?dir=left")

        # POST /pc/api/music/api/play
        code, data, _ = self._req(
            "POST",
            "/pc/api/music/api/play",
            body={"url": "https://youtu.be/abc"},
            headers=self.cf_headers,
        )
        self.assertEqual(code, 200)
        self.assertEqual(data["path"], "/music/api/play")
        self.assertEqual(json.loads(data["body"]), {"url": "https://youtu.be/abc"})

        # Upstream non-200 status & Content-Type preserved
        code, text, ct = self._req("GET", "/pc/api/detect/check?status=418", headers=self.cf_headers, parse_json=False)
        self.assertEqual(code, 418)
        self.assertEqual(text, b"teapot-text")
        self.assertIn("text/plain", ct)

        # Binary Content-Type preserved
        code, raw, ct = self._req("GET", "/pc/api/detect/snap?binary=1", headers=self.cf_headers, parse_json=False)
        self.assertEqual(code, 200)
        self.assertTrue(raw.startswith(b"\x89PNG"))
        self.assertEqual(ct, "image/png")

        # Path traversal blocked (both raw '..' and percent-encoded '%2e%2e')
        for bad_path in (
            "/pc/api/detect/../secret",
            "/pc/api/ptz/foo/../../etc/passwd",
            "/pc/api/music/%2e%2e/secret",
            "/pc/api/detect/..",
        ):
            code, err, _ = self._req("GET", bad_path, headers=self.cf_headers)
            self.assertEqual(code, 400, f"expected 400 for {bad_path}, got {code}: {err}")

        # Unauthenticated proxy request -> 403
        code, _, _ = self._req("GET", "/pc/api/vitals", headers={"X-Forwarded-For": "203.0.113.42"})
        self.assertEqual(code, 403)

    def test_proxy_upstream_down_returns_502_or_503_json(self):
        # Find an unused localhost port that refuses connections immediately.
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        dead_port = s.getsockname()[1]
        s.close()

        with mock.patch.dict(pcapi.PROXY_UPSTREAMS, {"vitals": f"http://127.0.0.1:{dead_port}/vitals/api"}):
            code, err, ct = self._req("GET", "/pc/api/vitals", headers=self.cf_headers)
            self.assertIn(code, (502, 503))
            self.assertIn("application/json", ct)
            self.assertIn("error", err)

    def test_sse_streams_emit_keepalive_comments_when_idle(self):
        class _DummySub:
            def __init__(self):
                self.q = queue.Queue()

            def subscribe(self):
                return self.q

            def unsubscribe(self, q):
                pass

            def snapshot_jobs(self):
                return []

            def snapshot_state(self):
                return {"messages": [], "running": False, "engine": "claude", "queued_ids": []}

        dummy = _DummySub()

        with mock.patch.object(pcapi, "SSE_KEEPALIVE_SEC", 0.05), \
             mock.patch.object(pcapi, "HUB", dummy), \
             mock.patch.object(pcapi, "rootd_state", return_value={"state": "down"}), \
             mock.patch.object(pcapi, "_gdrive_offload_manager", return_value=dummy), \
             mock.patch.object(pcapi, "_chat_manager", return_value=dummy):
            for endpoint in ("/pc/api/events", "/pc/api/space/jobs/stream", "/pc/api/chat/events"):
                req = urllib.request.Request(self.base + endpoint, headers=self.cf_headers)
                found_keepalive = False
                with urllib.request.urlopen(req, timeout=3) as resp:
                    self.assertEqual(resp.status, 200)
                    for _ in range(25):
                        line = resp.readline().decode("utf-8", errors="replace")
                        if line.strip() == ": keepalive":
                            found_keepalive = True
                            break
                self.assertTrue(found_keepalive, f"{endpoint} did not emit ': keepalive'")


if __name__ == "__main__":
    unittest.main()
