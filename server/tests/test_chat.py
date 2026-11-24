"""Unit and HTTP tests for server/chat.py and /pc/api/chat/* scope isolation."""

import base64
import json
import os
import stat
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

    def send(self, text, *, fresh=False, attachments=None):
        if attachments is not None:
            self.calls.append((text, fresh, attachments))
        else:
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
        self.last_channel_path = os.path.join(self.tmp.name, "state", "last-channel.json")
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
            last_channel_file=self.last_channel_path,
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
        self.assertIsNone(res["assistant_message"])

        # While in flight, no placeholder pending assistant bubble is stored
        _, mid_list = mgr.handle_get_messages({})
        self.assertTrue(mid_list["running"])
        self.assertEqual(len(mid_list["messages"]), 1)
        self.assertEqual(mid_list["messages"][0]["role"], "user")

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
            last_channel_file=self.last_channel_path,
        )
        code, _ = mgr.handle_send({"text": "do thing"})
        self.assertEqual(code, 202)
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        _, listed = mgr.handle_get_messages({})
        asst = listed["messages"][-1]
        self.assertEqual(asst["status"], "error")
        self.assertIn("boom failure", asst["error"])

    def test_back_to_back_sends_append_replies_chronologically_at_end(self):
        replies = ["First reply", "Second reply"]
        calls_started = []
        release_first = threading.Event()

        class SequentialAdapter:
            def send(self, text, *, fresh=False, attachments=None):
                idx = len(calls_started)
                calls_started.append(text)
                if idx == 0:
                    release_first.wait(timeout=2.0)
                return replies[idx]

        mgr = chat_mod.ChatManager(
            store=self.store,
            engine_fn=lambda: "gemini",
            gemini_adapter=SequentialAdapter(),
            notify=self.notify,
            last_channel_file=self.last_channel_path,
        )

        code1, _ = mgr.handle_send({"text": "msg 1", "client_id": "seq-1"})
        code2, _ = mgr.handle_send({"text": "msg 2", "client_id": "seq-2"})
        self.assertEqual((code1, code2), (202, 202))

        # Both user messages stored, zero pending assistant placeholders
        _, during = mgr.handle_get_messages({})
        self.assertTrue(during["running"])
        self.assertEqual([(m["role"], m["text"]) for m in during["messages"]], [("user", "msg 1"), ("user", "msg 2")])

        release_first.set()
        self.assertTrue(mgr.wait_idle(timeout=3.0))

        _, after = mgr.handle_get_messages({})
        self.assertFalse(after["running"])
        self.assertEqual(
            [(m["role"], m["text"], m["status"]) for m in after["messages"]],
            [
                ("user", "msg 1", "done"),
                ("user", "msg 2", "done"),
                ("assistant", "First reply", "done"),
                ("assistant", "Second reply", "done"),
            ],
        )

    def test_handle_push_stores_completed_message_and_triggers_notify(self):
        mgr = chat_mod.ChatManager(
            store=self.store,
            engine_fn=lambda: "gemini",
            notify=self.notify,
            last_channel_file=self.last_channel_path,
        )

        # Validation errors
        self.assertEqual(mgr.handle_push({})[0], 400)
        self.assertEqual(mgr.handle_push({"text": "   "})[0], 400)
        self.assertEqual(mgr.handle_push({"text": "hi", "engine": "unknown"})[0], 400)

        # Default engine (gemini)
        code, res = mgr.handle_push({"text": "Proactive wake update"})
        self.assertEqual(code, 200)
        self.assertTrue(res["ok"])
        self.assertTrue(res["pushed"])
        self.assertEqual(res["message"]["role"], "assistant")
        self.assertEqual(res["message"]["status"], "done")
        self.assertEqual(res["message"]["engine"], "gemini")
        self.assertEqual(res["message"]["text"], "Proactive wake update")

        self.assertEqual(len(self.notify.calls), 1)
        cat, title, body, kw = self.notify.calls[-1]
        self.assertEqual((cat, title), ("chat", "Gemini"))
        self.assertIn("Proactive wake update", body)
        self.assertEqual(kw.get("link"), "pccontrol://chat")

        # Explicit engine (claude)
        code2, res2 = mgr.handle_push({"text": "Claude proactive", "engine": "claude"})
        self.assertEqual(code2, 200)
        self.assertTrue(res2["pushed"])
        self.assertEqual(res2["message"]["engine"], "claude")
        self.assertEqual(self.notify.calls[-1][1], "Claude")

    def test_last_channel_written_atomically_and_best_effort(self):
        now = [1700000123.5]
        gem = FakeAdapter(reply="ok")
        target_file = os.path.join(self.tmp.name, "nested", "dir", "last-channel.json")
        with mock.patch.dict(os.environ, {"PCAPI_LAST_CHANNEL_FILE": target_file}):
            mgr = chat_mod.ChatManager(
                store=self.store,
                engine_fn=lambda: "gemini",
                gemini_adapter=gem,
                notify=self.notify,
                clock=lambda: now[0],
            )
            code, _ = mgr.handle_send({"text": "hello app"})
            self.assertEqual(code, 202)
            self.assertTrue(mgr.wait_idle(timeout=3.0))

        self.assertTrue(os.path.isfile(target_file))
        self.assertEqual(stat.S_IMODE(os.stat(target_file).st_mode), 0o600)
        with open(target_file, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["channel"], "app")
        self.assertAlmostEqual(data["ts"], 1700000123.5)

        # Best-effort: failure writing last-channel file never breaks chat send
        bad_dir = os.path.join(self.tmp.name, "is-a-file")
        with open(bad_dir, "w", encoding="utf-8") as f:
            f.write("block")
        bad_target = os.path.join(bad_dir, "last-channel.json")
        mgr_bad = chat_mod.ChatManager(
            store=self.store,
            engine_fn=lambda: "gemini",
            gemini_adapter=gem,
            notify=self.notify,
            last_channel_file=bad_target,
        )
        code_ok, res_ok = mgr_bad.handle_send({"text": "still succeeds"})
        self.assertEqual(code_ok, 202)
        self.assertTrue(res_ok["ok"])
        self.assertTrue(mgr_bad.wait_idle(timeout=3.0))

    def test_reply_to_id_persists_snippet_and_prepends_prompt_tag(self):
        gem = FakeAdapter(reply="Ack reply")
        mgr = chat_mod.ChatManager(
            store=self.store,
            engine_fn=lambda: "gemini",
            gemini_adapter=gem,
            notify=self.notify,
            last_channel_file=self.last_channel_path,
        )

        # Seed a long assistant message (>240 chars, <1200 chars) and an image-only user message.
        long_text = "A" * 320
        target_asst = self.store.append(
            {
                "id": "asst-long-1",
                "role": "assistant",
                "engine": "gemini",
                "text": long_text,
                "created_at": 100.0,
                "status": "done",
                "error": None,
            }
        )
        img_user = self.store.append(
            {
                "id": "user-img-1",
                "role": "user",
                "engine": "gemini",
                "text": "",
                "created_at": 101.0,
                "status": "done",
                "error": None,
                "attachments": [
                    {
                        "id": "0123456789abcdef",
                        "name": "photo.png",
                        "mime_type": "image/png",
                        "size_bytes": 42,
                        "kind": "image",
                        "url": "/pc/api/chat/file/0123456789abcdef",
                    }
                ],
            }
        )

        # 1. Reply to the long assistant message.
        code, res = mgr.handle_send({"text": "Can you expand on that?", "reply_to_id": target_asst["id"]})
        self.assertEqual(code, 202)
        self.assertTrue(mgr.wait_idle(timeout=3.0))

        u_msg = res["user_message"]
        self.assertEqual(u_msg["text"], "Can you expand on that?")
        self.assertIsInstance(u_msg.get("reply_to"), dict)
        self.assertEqual(u_msg["reply_to"]["id"], "asst-long-1")
        self.assertEqual(u_msg["reply_to"]["role"], "assistant")
        self.assertEqual(u_msg["reply_to"]["engine"], "gemini")
        self.assertEqual(len(u_msg["reply_to"]["text"]), 240)
        self.assertTrue(u_msg["reply_to"]["text"].endswith("…"))

        # Dispatched prompt includes full target text (up to 1200 chars) in <reply_to ...> tag.
        dispatched_text, _ = gem.calls[-1]
        self.assertEqual(
            dispatched_text,
            f'<reply_to role="assistant" id="asst-long-1">{long_text}</reply_to>\nCan you expand on that?',
        )

        # Reloading JSONL store preserves reply_to object.
        reloaded = chat_mod.ChatHistoryStore(self.path)
        persisted = reloaded.get_by_id(u_msg["id"])
        self.assertIsNotNone(persisted)
        self.assertEqual(persisted["reply_to"], u_msg["reply_to"])

        # 2. Reply to an image-only message falls back to [image].
        code2, res2 = mgr.handle_send({"text": "What is in this?", "reply_to_id": img_user["id"]})
        self.assertEqual(code2, 202)
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        self.assertEqual(res2["user_message"]["reply_to"]["text"], "[image]")
        self.assertEqual(
            gem.calls[-1][0],
            '<reply_to role="user" id="user-img-1">[image]</reply_to>\nWhat is in this?',
        )

        # 3. Unknown reply_to_id is gracefully ignored.
        code3, res3 = mgr.handle_send({"text": "No quote", "reply_to_id": "does-not-exist"})
        self.assertEqual(code3, 202)
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        self.assertNotIn("reply_to", res3["user_message"])
        self.assertEqual(gem.calls[-1][0], "No quote")

        # 4. Non-string reply_to_id returns 400.
        code4, _ = mgr.handle_send({"text": "Bad", "reply_to_id": 12345})
        self.assertEqual(code4, 400)


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

            adapter = chat_mod.T3GeminiAdapter(client=FakeT3(), thread_file=tf, retry_delay=0.0)
            reply = adapter.send("ping", fresh=True)
            self.assertEqual(reply, "Hello world")
            send_call = next(a for n, a in calls if n == "t3_thread_send")
            self.assertEqual(send_call["threadId"], "thr-123")
            self.assertTrue(send_call.get("freshContext"))

    def test_t3_gemini_adapter_ignores_stale_prev_run_and_early_idle(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "t3-thread.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"thread_id": "thr-123", "project_id": "proj-1"}, f)

            wait_calls = []
            wait_responses = [
                # Stale previous run reported as completed right after send
                {"status": "completed", "runId": "run-11"},
                # Early idle before the queued run starts
                {"status": "idle"},
                # New run starts
                {"status": "running", "run": {"runId": "run-12", "status": "running"}},
                # New run finishes
                {"status": "completed", "run": {"runId": "run-12", "status": "completed"}},
            ]
            read_after_count = 0

            class FakeT3Race:
                def call_tool(self, name, args=None):
                    nonlocal read_after_count
                    args = args or {}
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        return {
                            "thread": {"latestRunId": "run-11", "itemCount": 12},
                            "items": [{"position": 11}],
                        }
                    if name == "t3_thread_send":
                        # Returns no runId yet (queued)
                        return {"ok": True}
                    if name == "t3_thread_wait":
                        wait_calls.append(dict(args))
                        return wait_responses.pop(0)
                    if name == "t3_thread_read":
                        read_after_count += 1
                        # Simulate assistant_message still flushing on first 2 reads
                        if read_after_count < 3:
                            return {"items": [{"type": "user_message", "text": "are you continuing?"}]}
                        return {
                            "items": [
                                {
                                    "itemId": "it-12",
                                    "type": "assistant_message",
                                    "runId": "run-12",
                                    "text": "Yes, continuing the PC Agent thread!",
                                    "textTruncated": False,
                                }
                            ]
                        }
                    return {}

            adapter = chat_mod.T3GeminiAdapter(client=FakeT3Race(), thread_file=tf, retry_delay=0.0)
            reply = adapter.send("are you continuing?")
            self.assertEqual(reply, "Yes, continuing the PC Agent thread!")
            self.assertEqual(len(wait_calls), 4)
            # First three waits have no runId yet; fourth passes resolved run-12.
            self.assertNotIn("runId", wait_calls[0])
            self.assertNotIn("runId", wait_calls[1])
            self.assertNotIn("runId", wait_calls[2])
            self.assertEqual(wait_calls[3].get("runId"), "run-12")
            self.assertEqual(read_after_count, 3)

    def test_t3_gemini_adapter_queued_run_id_and_paginated_reply(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "t3-thread.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"thread_id": "thr-456", "project_id": "proj-1"}, f)

            wait_calls = []
            read_after_positions = []

            class FakeT3Paginated:
                def call_tool(self, name, args=None):
                    args = args or {}
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        return {
                            "thread": {"latestRunId": "run-5"},
                            "items": [{"position": 10}],
                        }
                    if name == "t3_thread_send":
                        return {"queuedRunId": "run-6"}
                    if name == "t3_thread_wait":
                        wait_calls.append(dict(args))
                        return {"status": "completed", "runId": "run-6"}
                    if name == "t3_thread_read":
                        pos = args.get("afterPosition")
                        read_after_positions.append(pos)
                        if pos == 10:
                            return {
                                "items": [{"type": "user_message", "runId": "run-6", "text": "go"}],
                                "hasMore": True,
                                "nextPosition": 14,
                            }
                        return {
                            "items": [
                                {
                                    "itemId": "it-6",
                                    "type": "assistant_message",
                                    "runId": "run-6",
                                    "text": "Paginated final reply",
                                    "textTruncated": False,
                                }
                            ],
                            "hasMore": False,
                        }
                    return {}

            adapter = chat_mod.T3GeminiAdapter(client=FakeT3Paginated(), thread_file=tf, retry_delay=0.0)
            reply = adapter.send("go")
            self.assertEqual(reply, "Paginated final reply")
            self.assertEqual(wait_calls[0].get("runId"), "run-6")
            self.assertEqual(read_after_positions, [10, 14])

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

    def test_claude_cli_adapter_forwards_attachment_paths(self):
        seen_argvs = []

        def fake_run(argv, **kwargs):
            seen_argvs.append(argv)
            return subprocess.CompletedProcess(
                argv, 0, stdout=json.dumps({"result": "Looked at file"}), stderr=""
            )

        with tempfile.TemporaryDirectory() as tmp:
            adapter = chat_mod.ClaudeCliAdapter(
                workdir=tmp,
                settings_file=os.path.join(tmp, "none.json"),
                oauth_token_file=os.path.join(tmp, "missing-tok"),
                run_fn=fake_run,
            )
            reply = adapter.send(
                "Check this",
                fresh=False,
                attachments=[
                    {"path": "/tmp/a_photo.png"},
                    {"path": "/tmp/b_notes.txt"},
                ],
            )
            self.assertEqual(reply, "Looked at file")
            prompt_arg = seen_argvs[-1][seen_argvs[-1].index("-p") + 1]
            self.assertIn("Check this", prompt_arg)
            self.assertIn("[attachment: /tmp/a_photo.png]", prompt_arg)
            self.assertIn("[attachment: /tmp/b_notes.txt]", prompt_arg)

            # Empty text with attachment only
            adapter.send("", fresh=False, attachments=[{"path": "/tmp/only.png"}])
            prompt_only = seen_argvs[-1][seen_argvs[-1].index("-p") + 1]
            self.assertEqual(prompt_only, "[attachment: /tmp/only.png]")

    def test_t3_gemini_adapter_uploads_and_sends_attachments_and_falls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "t3-thread.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"thread_id": "thr-att", "project_id": "proj-1"}, f)

            img_path = os.path.join(tmp, "pic.png")
            with open(img_path, "wb") as f:
                f.write(b"\x89PNG\r\n\x1a\nfake")

            calls = []
            uploaded_paths = []

            class FakeT3Att:
                def upload_attachment(self, file_path, **kw):
                    uploaded_paths.append((str(file_path), kw))
                    return {
                        "type": "image",
                        "id": "att-t3-1",
                        "name": "pic.png",
                        "mimeType": "image/png",
                        "sizeBytes": 12,
                    }

                def call_tool(self, name, args=None):
                    args = args or {}
                    calls.append((name, args))
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        return {"thread": {"latestRunId": "run-old"}, "items": [{"position": 5}]}
                    if name == "t3_thread_send_attachments":
                        return {"runId": "run-new"}
                    if name == "t3_thread_wait":
                        return {"status": "completed", "runId": "run-new"}
                    if name == "t3_thread_read":
                        return {
                            "items": [
                                {
                                    "itemId": "it-att",
                                    "type": "assistant_message",
                                    "runId": "run-new",
                                    "text": "Nice picture!",
                                    "textTruncated": False,
                                }
                            ]
                        }
                    return {}

            adapter = chat_mod.T3GeminiAdapter(client=FakeT3Att(), thread_file=tf, retry_delay=0.0)
            reply = adapter.send(
                "What is this?",
                attachments=[{"path": img_path, "name": "pic.png", "mime_type": "image/png", "kind": "image"}],
            )
            self.assertEqual(reply, "Nice picture!")
            self.assertEqual(len(uploaded_paths), 1)
            send_att_call = next(a for n, a in calls if n == "t3_thread_send_attachments")
            self.assertEqual(send_att_call["threadId"], "thr-att")
            self.assertEqual(send_att_call["message"], "What is this?")
            self.assertEqual(send_att_call["attachments"][0]["id"], "att-t3-1")

            # Fallback when upload_attachment fails: appends [attachment: /abs/path] to t3_thread_send
            calls.clear()

            class FakeT3FailUpload(FakeT3Att):
                def upload_attachment(self, file_path, **kw):
                    raise RuntimeError("upload endpoint unavailable")

                def call_tool(self, name, args=None):
                    args = args or {}
                    calls.append((name, args))
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        return {"thread": {"latestRunId": "run-new"}, "items": [{"position": 6}]}
                    if name == "t3_thread_send":
                        return {"runId": "run-fb"}
                    if name == "t3_thread_wait":
                        return {"status": "completed", "runId": "run-fb"}
                    if name == "t3_thread_read":
                        return {
                            "items": [
                                {
                                    "itemId": "it-fb",
                                    "type": "assistant_message",
                                    "runId": "run-fb",
                                    "text": "Read via fallback path",
                                    "textTruncated": False,
                                }
                            ]
                        }
                    return {}

            fb_adapter = chat_mod.T3GeminiAdapter(client=FakeT3FailUpload(), thread_file=tf, retry_delay=0.0)
            reply2 = fb_adapter.send(
                "",
                attachments=[{"path": img_path, "name": "pic.png", "mime_type": "image/png", "kind": "image"}],
            )
            self.assertEqual(reply2, "Read via fallback path")
            send_call = next(a for n, a in calls if n == "t3_thread_send")
            self.assertEqual(send_call["message"], f"[attachment: {img_path}]")


class ChatHttpAndScopeIsolationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tok_file = os.path.join(self.tmp.name, "chat-token")
        self.local_tok_file = os.path.join(self.tmp.name, "local-token")
        self.hist_file = os.path.join(self.tmp.name, "chat-history.jsonl")
        self.uploads_dir = os.path.join(self.tmp.name, "chat-uploads")
        self.last_channel_file = os.path.join(self.tmp.name, "last-channel.json")
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
            uploads_dir=self.uploads_dir,
            last_channel_file=self.last_channel_file,
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

    def test_chat_upload_download_size_limit_and_send_with_attachments(self):
        cf_headers = {
            "X-Forwarded-For": "203.0.113.10",
            "Authorization": f"Bearer {self.secret}",
        }
        ts_headers = {"Tailscale-User-Login": "ayush@example.com"}

        # Upload a PNG image via chat bearer token
        png_bytes = b"\x89PNG\r\n\x1a\nhello-image"
        code, up = self._req(
            "POST",
            "/pc/api/chat/upload",
            {
                "name": "../../weird name!.png",
                "mime_type": "image/png",
                "data_base64": base64.b64encode(png_bytes).decode("ascii"),
            },
            headers=cf_headers,
        )
        self.assertEqual(code, 200)
        self.assertEqual(up["kind"], "image")
        self.assertEqual(up["mime_type"], "image/png")
        self.assertEqual(up["size_bytes"], len(png_bytes))
        self.assertTrue(up["url"].startswith("/pc/api/chat/file/"))
        self.assertNotIn("/", up["name"])
        self.assertNotIn("..", up["name"])

        # Verify file mode 0600 on disk
        saved = os.path.join(self.uploads_dir, f"{up['id']}_{up['name']}")
        self.assertTrue(os.path.isfile(saved))
        self.assertEqual(stat.S_IMODE(os.stat(saved).st_mode), 0o600)

        # Download via GET /pc/api/chat/file/<id> with chat bearer token
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{up['url']}", headers=cf_headers)
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            self.assertEqual(resp.read(), png_bytes)
            self.assertEqual(resp.headers.get("Content-Type"), "image/png")
            self.assertEqual(resp.headers.get("X-Content-Type-Options"), "nosniff")

        # Download via Tailscale auth also works
        req2 = urllib.request.Request(f"http://127.0.0.1:{self.port}{up['url']}", headers=ts_headers)
        with urllib.request.urlopen(req2, timeout=5) as resp2:
            self.assertEqual(resp2.status, 200)
            self.assertEqual(resp2.read(), png_bytes)

        # HTML upload is served with safe Content-Type (application/octet-stream) + nosniff
        code_html, up_html = self._req(
            "POST",
            "/pc/api/chat/upload",
            {
                "name": "evil.html",
                "mime_type": "text/html",
                "data_base64": base64.b64encode(b"<script>alert(1)</script>").decode("ascii"),
            },
            headers=ts_headers,
        )
        self.assertEqual(code_html, 200)
        self.assertEqual(up_html["kind"], "file")
        req_html = urllib.request.Request(f"http://127.0.0.1:{self.port}{up_html['url']}", headers=ts_headers)
        with urllib.request.urlopen(req_html, timeout=5) as resp_html:
            self.assertEqual(resp_html.headers.get("Content-Type"), "application/octet-stream")
            self.assertEqual(resp_html.headers.get("X-Content-Type-Options"), "nosniff")

        # Oversized upload (> 15 MB) is rejected
        over_b64 = base64.b64encode(b"a" * (chat_mod.MAX_ATTACHMENT_BYTES + 1)).decode("ascii")
        code_big, _ = self.chat_mgr.handle_upload({"name": "big.bin", "data_base64": over_b64})
        self.assertEqual(code_big, 413)

        # Send message with attachment and empty text
        code_send, sent = self._req(
            "POST",
            "/pc/api/chat/send",
            {"text": "", "client_id": "att-cid-1", "attachments": [up]},
            headers=cf_headers,
        )
        self.assertEqual(code_send, 202)
        self.assertTrue(self.chat_mgr.wait_idle(timeout=3.0))
        # Public API response omits internal filesystem path
        self.assertEqual(len(sent["user_message"]["attachments"]), 1)
        self.assertNotIn("path", sent["user_message"]["attachments"][0])
        self.assertEqual(sent["user_message"]["attachments"][0]["id"], up["id"])

        # Adapter received the resolved attachment with internal path
        self.assertEqual(len(self.adapter.calls), 1)
        call_text, call_fresh, call_atts = self.adapter.calls[0]
        self.assertEqual(call_text, "")
        self.assertFalse(call_fresh)
        self.assertEqual(call_atts[0]["path"], saved)

        # GET /pc/api/chat/messages also omits internal path
        code_list, listed = self._req("GET", "/pc/api/chat/messages", headers=cf_headers)
        self.assertEqual(code_list, 200)
        u_msg = next(m for m in listed["messages"] if m["id"] == sent["user_message"]["id"])
        self.assertEqual(len(u_msg["attachments"]), 1)
        self.assertNotIn("path", u_msg["attachments"][0])

        # Unknown attachment id rejected
        code_bad, _ = self._req(
            "POST",
            "/pc/api/chat/send",
            {"text": "", "attachments": [{"id": "0123456789abcdef"}]},
            headers=cf_headers,
        )
        self.assertEqual(code_bad, 400)

    def test_chat_push_requires_local_token_only(self):
        local_tok = pcapi.local_token()
        cf_headers = {
            "X-Forwarded-For": "203.0.113.10",
            "Authorization": f"Bearer {self.secret}",
        }
        ts_headers = {"Tailscale-User-Login": "ayush@example.com"}

        # Rejected without headers, with chat bearer token, with Tailscale login only, or proxied with local token
        for hdrs in (
            {},
            cf_headers,
            ts_headers,
            {"X-PC-Local-Token": "wrong-token"},
            {"X-Forwarded-For": "203.0.113.10", "X-PC-Local-Token": local_tok},
        ):
            code, _ = self._req("POST", "/pc/api/chat/push", {"text": "blocked"}, headers=hdrs)
            self.assertEqual(code, 403, f"expected 403 for headers={hdrs}")

        # Allowed with valid X-PC-Local-Token from localhost
        code, res = self._req(
            "POST",
            "/pc/api/chat/push",
            {"text": "Background wake message", "engine": "gemini"},
            headers={"X-PC-Local-Token": local_tok},
        )
        self.assertEqual(code, 200)
        self.assertTrue(res["ok"])
        self.assertTrue(res["pushed"])
        self.assertEqual(res["message"]["role"], "assistant")
        self.assertEqual(res["message"]["status"], "done")
        self.assertEqual(res["message"]["text"], "Background wake message")
        self.assertEqual(len(self.notify.calls), 1)

        # Stored in chat history
        code_list, listed = self._req("GET", "/pc/api/chat/messages", headers=ts_headers)
        self.assertEqual(code_list, 200)
        self.assertTrue(any(m["id"] == res["message"]["id"] for m in listed["messages"]))


if __name__ == "__main__":
    unittest.main()
