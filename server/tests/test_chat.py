"""Unit and HTTP tests for server/chat.py and /pc/api/chat/* scope isolation."""

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
from http.server import ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import chat as chat_mod  # noqa: E402
import pcapi  # noqa: E402


class FakeNotify:
    def __init__(self):
        self.calls = []
        self.event = threading.Event()

    def send_async(self, cat, title, body="", **kw):
        self.calls.append((cat, title, body, kw))
        self.event.set()


class FakeAdapter:
    def __init__(self, reply="Hello from agent", delay=0.0, error=None):
        self.reply = reply
        self.delay = delay
        self.error = error
        self.calls = []
        self.started = threading.Event()
        self.release = threading.Event()
        if delay == 0.0:
            self.release.set()

    def send(self, text, *, fresh=False):
        self.calls.append((text, fresh))
        self.started.set()
        if self.delay > 0:
            self.release.wait(timeout=self.delay)
        if self.error:
            raise RuntimeError(self.error)
        return self.reply


class ChatHistoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "chat-history.jsonl")

    def tearDown(self):
        self.tmp.cleanup()

    def test_append_update_persist_and_corrupt_line_recovery(self):
        store = chat_mod.ChatHistoryStore(self.path)
        u = store.append(
            {
                "id": "u1",
                "role": "user",
                "engine": "gemini",
                "text": "hi",
                "created_at": 100.0,
                "status": "done",
                "client_id": "cid-1",
            }
        )
        a = store.append(
            {
                "id": "a1",
                "role": "assistant",
                "engine": "gemini",
                "text": "",
                "created_at": 100.1,
                "status": "pending",
                "reply_to": "u1",
            }
        )
        self.assertEqual(u["id"], "u1")
        self.assertEqual(a["status"], "pending")
        store.update("a1", text="hello!", status="done", error=None)

        # Append a pending message and a corrupt trailing line to simulate a crash.
        store.append(
            {
                "id": "a2",
                "role": "assistant",
                "engine": "gemini",
                "text": "",
                "created_at": 101.0,
                "status": "pending",
                "reply_to": "u1",
            }
        )
        with open(self.path, "a", encoding="utf-8") as f:
            f.write("{corrupt partial json\n")

        reloaded = chat_mod.ChatHistoryStore(self.path)
        msgs, has_more = reloaded.get_messages(limit=50)
        self.assertFalse(has_more)
        self.assertEqual([m["id"] for m in msgs], ["u1", "a1", "a2"])
        self.assertEqual(msgs[1]["text"], "hello!")
        self.assertEqual(msgs[1]["status"], "done")
        # Leftover pending message recovered as error on restart.
        self.assertEqual(msgs[2]["status"], "error")
        self.assertIn("restart", msgs[2]["error"].lower())
        # Client id lookup survived reload.
        found = reloaded.find_by_client_id("cid-1")
        self.assertIsNotNone(found)
        self.assertEqual(found[0]["id"], "u1")

    def test_pagination_and_compaction(self):
        store = chat_mod.ChatHistoryStore(self.path, max_lines=20, keep_messages=12)
        for i in range(25):
            store.append(
                {
                    "id": f"m{i}",
                    "role": "user",
                    "engine": "claude",
                    "text": f"msg {i}",
                    "created_at": float(i),
                    "status": "done",
                }
            )
        # Compaction ran, keeping recent messages bounded.
        msgs, has_more = store.get_messages(limit=5)
        self.assertTrue(has_more)
        self.assertEqual([m["id"] for m in msgs], ["m20", "m21", "m22", "m23", "m24"])

        older, has_more2 = store.get_messages(before="m20", limit=5)
        self.assertEqual([m["id"] for m in older], ["m15", "m16", "m17", "m18", "m19"])
        self.assertTrue(has_more2 or len(older) == 5)

        with open(self.path, encoding="utf-8") as f:
            lines = [ln for ln in f if ln.strip()]
        self.assertLessEqual(len(lines), 20)


class ChatManagerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "chat-history.jsonl")
        self.store = chat_mod.ChatHistoryStore(self.path)
        self.notify = FakeNotify()

    def tearDown(self):
        self.tmp.cleanup()

    def test_async_send_dedup_notify_and_new_session(self):
        gem = FakeAdapter(reply="Gemini reply text", delay=2.0)
        cla = FakeAdapter(reply="Claude reply text")
        eng = ["gemini"]
        mgr = chat_mod.ChatManager(
            store=self.store,
            engine_fn=lambda: eng[0],
            gemini_adapter=gem,
            claude_adapter=cla,
            notify=self.notify,
        )

        # Send returns 202 immediately without blocking on adapter.
        t0 = time.monotonic()
        code, res = mgr.handle_send({"text": "Check status", "client_id": "c-100"})
        elapsed = time.monotonic() - t0
        self.assertEqual(code, 202)
        self.assertLess(elapsed, 0.5)
        self.assertFalse(res["deduped"])
        self.assertTrue(res["running"])
        self.assertEqual(res["engine"], "gemini")
        self.assertEqual(res["assistant_message"]["status"], "pending")

        # Duplicate client_id returns 200 without re-dispatching.
        code2, res2 = mgr.handle_send({"text": "Check status", "client_id": "c-100"})
        self.assertEqual(code2, 200)
        self.assertTrue(res2["deduped"])
        self.assertEqual(res2["user_message"]["id"], res["user_message"]["id"])

        # Release adapter and wait for completion.
        self.assertTrue(gem.started.wait(timeout=2.0))
        gem.release.set()
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        self.assertEqual(len(gem.calls), 1)
        self.assertEqual(gem.calls[0], ("Check status", False))

        # Check messages state and FCM push notification.
        code3, listed = mgr.handle_get_messages({})
        self.assertEqual(code3, 200)
        self.assertFalse(listed["running"])
        self.assertEqual(len(listed["messages"]), 2)
        self.assertEqual(listed["messages"][1]["status"], "done")
        self.assertEqual(listed["messages"][1]["text"], "Gemini reply text")

        self.assertTrue(self.notify.event.wait(timeout=2.0))
        cat, title, body, kw = self.notify.calls[-1]
        self.assertEqual(cat, "chat")
        self.assertEqual(title, "Gemini")
        self.assertIn("Gemini reply text", body)
        self.assertEqual(kw.get("link"), "pccontrol://chat")

        # POST /pc/api/chat/new sets boundary and fresh=True on next turn only.
        code4, new_res = mgr.handle_new({})
        self.assertEqual(code4, 200)
        self.assertEqual(new_res["boundary_message"]["role"], "system")

        gem.delay = 0.0
        gem.release.set()
        mgr.handle_send({"text": "Fresh prompt"})
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        self.assertEqual(gem.calls[-1], ("Fresh prompt", True))

        mgr.handle_send({"text": "Follow-up"})
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        self.assertEqual(gem.calls[-1], ("Follow-up", False))

    def test_adapter_error_marks_message_error(self):
        cla = FakeAdapter(error="boom failure")
        mgr = chat_mod.ChatManager(
            store=self.store,
            engine_fn=lambda: "claude",
            claude_adapter=cla,
            notify=self.notify,
        )
        code, _ = mgr.handle_send({"text": "do thing"})
        self.assertEqual(code, 202)
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        _, listed = mgr.handle_get_messages({})
        asst = listed["messages"][-1]
        self.assertEqual(asst["status"], "error")
        self.assertIn("boom failure", asst["error"])


class AdapterTests(unittest.TestCase):
    def test_t3_gemini_adapter_reuses_thread_and_reads_reply(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "t3-thread.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"thread_id": "thr-123", "project_id": "proj-1"}, f)

            calls = []

            class FakeT3:
                def call_tool(self, name, args=None):
                    calls.append((name, args or {}))
                    if name == "t3_thread_read" and (args or {}).get("limit") == 1:
                        return {"items": [{"position": 10}]}
                    if name == "t3_thread_send":
                        return {"runId": "run-9"}
                    if name == "t3_thread_wait":
                        return {"status": "completed"}
                    if name == "t3_thread_read" and (args or {}).get("itemId"):
                        return {
                            "items": [
                                {
                                    "itemId": "it-1",
                                    "type": "assistant_message",
                                    "text": " world",
                                    "textTruncated": False,
                                }
                            ]
                        }
                    if name == "t3_thread_read":
                        return {
                            "items": [
                                {
                                    "itemId": "it-1",
                                    "type": "assistant_message",
                                    "runId": "run-9",
                                    "text": "Hello",
                                    "textTruncated": True,
                                    "nextTextOffset": 5,
                                }
                            ]
                        }
                    return {}

            adapter = chat_mod.T3GeminiAdapter(client=FakeT3(), thread_file=tf)
            reply = adapter.send("ping", fresh=True)
            self.assertEqual(reply, "Hello world")
            send_call = next(a for n, a in calls if n == "t3_thread_send")
            self.assertEqual(send_call["threadId"], "thr-123")
            self.assertTrue(send_call.get("freshContext"))

    def test_claude_cli_adapter_continue_and_fresh(self):
        seen_argvs = []

        def fake_run(argv, **kwargs):
            seen_argvs.append(argv)
            return subprocess.CompletedProcess(
                argv, 0, stdout=json.dumps({"result": "Claude says hi"}), stderr=""
            )

        with tempfile.TemporaryDirectory() as tmp:
            cfg = os.path.join(tmp, "settings.json")
            with open(cfg, "w", encoding="utf-8") as f:
                json.dump({"model": "claude-opus-4-6", "effort": "high"}, f)
            adapter = chat_mod.ClaudeCliAdapter(
                workdir=tmp,
                settings_file=cfg,
                oauth_token_file=os.path.join(tmp, "missing-tok"),
                run_fn=fake_run,
            )
            self.assertEqual(adapter.send("hello", fresh=False), "Claude says hi")
            self.assertIn("--continue", seen_argvs[-1])
            self.assertIn("--model", seen_argvs[-1])

            self.assertEqual(adapter.send("reset", fresh=True), "Claude says hi")
            self.assertNotIn("--continue", seen_argvs[-1])


class ChatHttpAndScopeIsolationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tok_file = os.path.join(self.tmp.name, "chat-token")
        self.local_tok_file = os.path.join(self.tmp.name, "local-token")
        self.hist_file = os.path.join(self.tmp.name, "chat-history.jsonl")
        self.secret = "super-secret-chat-only-token-12345"
        with open(self.tok_file, "w", encoding="utf-8") as f:
            f.write(self.secret + "\n")

        self.notify = FakeNotify()
        self.adapter = FakeAdapter(reply="ok-from-http")
        self.chat_mgr = chat_mod.ChatManager(
            store=chat_mod.ChatHistoryStore(self.hist_file),
            engine_fn=lambda: "gemini",
            gemini_adapter=self.adapter,
            claude_adapter=self.adapter,
            notify=self.notify,
        )

        self.orig_chat_tok = chat_mod.CHAT_TOKEN_FILE
        self.orig_local_tok = pcapi.LOCAL_TOKEN_FILE
        self.orig_allowed = pcapi.ALLOWED_LOGINS
        self.orig_chat = pcapi.CHAT
        self.orig_notify = pcapi.NOTIFY
        self.orig_push = pcapi.PUSH

        chat_mod.CHAT_TOKEN_FILE = self.tok_file
        pcapi.LOCAL_TOKEN_FILE = self.local_tok_file
        pcapi.ALLOWED_LOGINS = {"ayush@example.com"}
        pcapi.CHAT = self.chat_mgr
        pcapi.PUSH = pcapi.Push(data_dir=self.tmp.name, sa_path=os.path.join(self.tmp.name, "no-sa.json"))
        pcapi.NOTIFY = pcapi.Notify(pcapi.PUSH, data_dir=self.tmp.name)

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        self.thread = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        chat_mod.CHAT_TOKEN_FILE = self.orig_chat_tok
        pcapi.LOCAL_TOKEN_FILE = self.orig_local_tok
        pcapi.ALLOWED_LOGINS = self.orig_allowed
        pcapi.CHAT = self.orig_chat
        pcapi.NOTIFY = self.orig_notify
        pcapi.PUSH = self.orig_push
        self.tmp.cleanup()

    def _req(self, method, path, body=None, headers=None):
        url = f"http://127.0.0.1:{self.port}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        h = dict(headers or {})
        if data is not None:
            h["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            e.close()
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, {"raw": raw}

    def test_chat_token_allows_only_chat_endpoints(self):
        # Simulate Cloudflare Tunnel traffic: X-Forwarded-For present, NO Tailscale-User-Login.
        cf_headers = {
            "X-Forwarded-For": "203.0.113.10",
            "Authorization": f"Bearer {self.secret}",
        }

        # Allowed: /pc/api/chat/*
        code, data = self._req("GET", "/pc/api/chat/messages", headers=cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["engine"], "gemini")

        code, data = self._req("POST", "/pc/api/chat/send", {"text": "hello via cf", "client_id": "cf1"}, headers=cf_headers)
        self.assertEqual(code, 202)
        self.assertTrue(self.chat_mgr.wait_idle(timeout=3.0))

        code, data = self._req("POST", "/pc/api/chat/new", {}, headers=cf_headers)
        self.assertEqual(code, 200)

        # STRICT SCOPE ISOLATION: chat-token MUST NOT access any non-chat endpoint!
        forbidden_endpoints = [
            ("GET", "/pc/api/root/pending"),
            ("GET", "/pc/api/root/history"),
            ("GET", "/pc/api/info"),
            ("GET", "/pc/api/agent"),
            ("GET", "/pc/api/services"),
            ("GET", "/pc/api/notify/settings"),
            ("GET", "/pc/api/chat-other"),
            ("GET", "/pc/api/chat/../info"),
            ("POST", "/pc/api/agent"),
            ("POST", "/pc/api/root/approve"),
        ]
        for method, ep in forbidden_endpoints:
            code, _ = self._req(method, ep, {} if method == "POST" else None, headers=cf_headers)
            self.assertEqual(code, 403, f"chat-token must be rejected on {method} {ep}")

    def test_wrong_or_missing_chat_token_rejected_and_tailnet_still_works(self):
        # Wrong bearer token via CF -> 403
        code, _ = self._req(
            "GET",
            "/pc/api/chat/messages",
            headers={"X-Forwarded-For": "203.0.113.10", "Authorization": "Bearer wrong-token"},
        )
        self.assertEqual(code, 403)

        # Missing token file -> 403
        os.remove(self.tok_file)
        code, _ = self._req(
            "GET",
            "/pc/api/chat/messages",
            headers={"X-Forwarded-For": "203.0.113.10", "Authorization": f"Bearer {self.secret}"},
        )
        self.assertEqual(code, 403)

        # Tailnet auth still works without chat-token
        code, data = self._req(
            "GET",
            "/pc/api/chat/messages",
            headers={"Tailscale-User-Login": "ayush@example.com"},
        )
        self.assertEqual(code, 200)
        self.assertIn("messages", data)

    def test_chat_notification_category_defaults_and_pccontrol_link(self):
        s = pcapi.NOTIFY.settings()
        row = next(c for c in s["categories"] if c["id"] == "chat")
        self.assertEqual((row["telegram"], row["app"]), (False, True))
        # Verify pccontrol://chat link is accepted by Notify.send
        code, res = pcapi.NOTIFY.send("chat", "Gemini", "Reply preview", link="pccontrol://chat")
        self.assertEqual(code, 200)
        self.assertEqual(res["telegram"], "off")


if __name__ == "__main__":
    unittest.main()
