"""Unit and HTTP tests for server/chat.py and /pc/api/chat/* scope isolation."""

import base64
import json
import os
import queue
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
from pathlib import Path
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
            ("GET", "/pc/api/agent/settings"),
            ("GET", "/pc/api/agent/usage"),
            ("GET", "/pc/api/services"),
            ("GET", "/pc/api/notify/settings"),
            ("GET", "/pc/api/chat-other"),
            ("GET", "/pc/api/chat/../info"),
            ("GET", "/pc/api/chat/../agent/settings"),
            ("POST", "/pc/api/agent"),
            ("POST", "/pc/api/agent/settings"),
            ("POST", "/pc/api/agent/reset"),
            ("POST", "/pc/api/root/approve"),
        ]
        for method, ep in forbidden_endpoints:
            code, _ = self._req(method, ep, {} if method == "POST" else None, headers=cf_headers)
            self.assertEqual(code, 403, f"chat-token must be rejected on {method} {ep}")

    def test_chat_agent_endpoints_accept_chat_token_and_tailnet(self):
        cf_headers = {
            "X-Forwarded-For": "203.0.113.10",
            "Authorization": f"Bearer {self.secret}",
        }
        ts_headers = {"Tailscale-User-Login": "ayush@example.com"}

        class FakeAgentSettings:
            def __init__(self):
                self.updated = []
                self.state = {
                    "claude": {"model": "claude-opus-4-7", "effort": "high", "models": [], "efforts": []},
                    "gemini": {"instance_id": "antigravity", "model": "gemini-4-neon", "thought_level": "medium", "models": [], "thought_levels": []},
                }

            def get(self):
                return self.state

            def update(self, body):
                self.updated.append(body)
                if isinstance(body.get("claude"), dict):
                    self.state["claude"].update(body["claude"])
                return 200, self.state

        class FakeAgent:
            def __init__(self):
                self.switched = []

            def switch(self, engine):
                self.switched.append(engine)
                if engine not in ("claude", "gemini"):
                    return 400, {"error": "invalid engine"}
                return 202, {"engine": engine, "switching": True}

        fake_settings = FakeAgentSettings()
        fake_agent = FakeAgent()
        orig_settings = pcapi.AGENT_SETTINGS
        orig_agent = pcapi.AGENT
        pcapi.AGENT_SETTINGS = fake_settings
        pcapi.AGENT = fake_agent
        try:
            # GET /pc/api/chat/agent with chat bearer token
            code, data = self._req("GET", "/pc/api/chat/agent", headers=cf_headers)
            self.assertEqual(code, 200)
            self.assertEqual(data["claude"]["model"], "claude-opus-4-7")

            # POST /pc/api/chat/agent with chat bearer token
            code, data = self._req(
                "POST",
                "/pc/api/chat/agent",
                {"claude": {"effort": "medium"}},
                headers=cf_headers,
            )
            self.assertEqual(code, 200)
            self.assertEqual(data["claude"]["effort"], "medium")
            self.assertEqual(fake_settings.updated, [{"claude": {"effort": "medium"}}])

            # POST /pc/api/chat/agent/switch with chat bearer token
            code, data = self._req(
                "POST",
                "/pc/api/chat/agent/switch",
                {"engine": "gemini"},
                headers=cf_headers,
            )
            self.assertEqual(code, 202)
            self.assertEqual(data["engine"], "gemini")
            self.assertEqual(fake_agent.switched, ["gemini"])

            # Tailnet auth also works on /pc/api/chat/agent*
            code, data = self._req("GET", "/pc/api/chat/agent", headers=ts_headers)
            self.assertEqual(code, 200)
            self.assertEqual(data["gemini"]["model"], "gemini-4-neon")

            # Wrong bearer token rejected on chat agent endpoints
            bad_headers = {"X-Forwarded-For": "203.0.113.10", "Authorization": "Bearer wrong"}
            for method, ep in (
                ("GET", "/pc/api/chat/agent"),
                ("POST", "/pc/api/chat/agent"),
                ("POST", "/pc/api/chat/agent/switch"),
            ):
                code, _ = self._req(method, ep, {} if method == "POST" else None, headers=bad_headers)
                self.assertEqual(code, 403)

            # Non-chat /pc/api/agent* endpoints still reject chat bearer token
            for method, ep in (
                ("GET", "/pc/api/agent"),
                ("GET", "/pc/api/agent/settings"),
                ("POST", "/pc/api/agent"),
                ("POST", "/pc/api/agent/settings"),
            ):
                code, _ = self._req(method, ep, {} if method == "POST" else None, headers=cf_headers)
                self.assertEqual(code, 403, f"chat-token must be rejected on {method} {ep}")
        finally:
            pcapi.AGENT_SETTINGS = orig_settings
            pcapi.AGENT = orig_agent

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


class ClaudeStreamProgressTests(unittest.TestCase):
    def test_describe_claude_tool_matches_claude_progress_hook(self):
        cases = [
            ("Bash", {"command": "git status"}),
            ("Bash", {"command": "journalctl " + ("x" * 100)}),
            ("Bash", {}),
            ("Read", {"file_path": "/home/u/agent/NOTES.md"}),
            ("Edit", {"file_path": "/home/u/app/ChatScreen.tsx", "new_string": "SECRET"}),
            ("Write", {"file_path": "/tmp/out.txt", "content": "SECRET"}),
            ("Glob", {"pattern": "**/*.ts"}),
            ("Grep", {"pattern": "shouldShowEngineTag"}),
            ("WebSearch", {"query": "anything"}),
            ("WebFetch", {"url": "https://example.com"}),
            ("Agent", {"prompt": "check"}),
            ("TodoWrite", {"todos": []}),
            ("mcp__playwright__browser_snapshot", {}),
            ("CustomTool", {}),
        ]
        self.assertEqual(chat_mod.describe_claude_tool("Bash", {"command": "git status"}), "running `git status`")
        self.assertEqual(chat_mod.describe_claude_tool("Read", {"file_path": "/home/u/NOTES.md"}), "reading NOTES.md")
        self.assertNotIn("SECRET", chat_mod.describe_claude_tool("Edit", {"file_path": "/a/b.py", "new_string": "SECRET"}))
        self.assertTrue(chat_mod.is_telegram_tool("mcp__plugin_telegram_telegram__reply"))
        self.assertFalse(chat_mod.is_telegram_tool("Bash"))

        hook_path = Path.home() / "tools" / "gemini-agent" / "claude-progress-hook"
        if hook_path.exists():
            import importlib.machinery
            import importlib.util

            loader = importlib.machinery.SourceFileLoader("claude_progress_hook_sync_check", str(hook_path))
            spec = importlib.util.spec_from_loader("claude_progress_hook_sync_check", loader)
            assert spec is not None and spec.loader is not None
            hook_mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(hook_mod)
            for name, inp in cases:
                self.assertEqual(
                    chat_mod.describe_claude_tool(name, inp),
                    hook_mod.describe_claude_tool(name, inp),
                    f"Mismatch for {name}({inp})",
                )

    def test_claude_cli_adapter_streams_real_fixture_and_isolates_app_origin(self):
        import io

        fixture = Path(__file__).resolve().parent / "fixtures" / "claude_stream_sample.jsonl"
        lines = fixture.read_text(encoding="utf-8").splitlines(keepends=True)
        seen = {}

        class FakePopen:
            def __init__(self, argv, **kwargs):
                seen["argv"] = argv
                seen["env"] = kwargs.get("env") or {}
                self.stdout = io.StringIO("".join(lines))
                self.stderr = io.StringIO("")
                self.returncode = 0

            def wait(self):
                return self.returncode

            def kill(self):
                self.returncode = -9

        with tempfile.TemporaryDirectory() as tmp:
            adapter = chat_mod.ClaudeCliAdapter(
                workdir=tmp,
                settings_file=os.path.join(tmp, "none.json"),
                oauth_token_file=os.path.join(tmp, "missing-tok"),
                popen_fn=FakePopen,
            )
            steps = []
            reply = adapter.send("run echo", fresh=False, on_step=lambda text, kind: steps.append((kind, text)))
            self.assertEqual(reply, "done")
            self.assertEqual(
                steps,
                [
                    ("tool", "running `echo ok`"),
                    ("text", "Checking the notes file next."),
                    ("tool", "reading NOTES.md"),
                    ("text", "done"),
                ],
            )
            argv = seen["argv"]
            self.assertIn("--output-format", argv)
            self.assertEqual(argv[argv.index("--output-format") + 1], "stream-json")
            self.assertIn("--verbose", argv)
            self.assertIn("--settings", argv)
            settings_override = json.loads(argv[argv.index("--settings") + 1])
            self.assertIs(settings_override["enabledPlugins"]["telegram@claude-plugins-official"], False)
            self.assertEqual(seen["env"].get("PC_CHAT_ORIGIN"), "app")

    def test_claude_cli_adapter_popen_timeout_and_is_error(self):
        import io

        class HangingPopen:
            def __init__(self, argv, **kwargs):
                self._killed = threading.Event()
                self.stderr = io.StringIO("")
                self.returncode = None
                parent = self

                class BlockingStdout:
                    def __iter__(self):
                        parent._killed.wait(timeout=2.0)
                        return iter([])

                    def close(self):
                        pass

                self.stdout = BlockingStdout()

            def kill(self):
                self.returncode = -9
                self._killed.set()

            def wait(self):
                self._killed.wait(timeout=2.0)
                return self.returncode

        with tempfile.TemporaryDirectory() as tmp:
            adapter = chat_mod.ClaudeCliAdapter(
                workdir=tmp,
                settings_file=os.path.join(tmp, "none.json"),
                oauth_token_file=os.path.join(tmp, "missing-tok"),
                timeout=0.08,
                popen_fn=HangingPopen,
            )
            with self.assertRaisesRegex(RuntimeError, "Timed out"):
                adapter.send("slow", fresh=True)

            class ErrorResultPopen:
                def __init__(self, argv, **kwargs):
                    self.stdout = io.StringIO(
                        json.dumps({"type": "result", "is_error": True, "result": "quota exceeded"}) + "\n"
                    )
                    self.stderr = io.StringIO("")
                    self.returncode = 0

                def wait(self):
                    return 0

                def kill(self):
                    pass

            err_adapter = chat_mod.ClaudeCliAdapter(
                workdir=tmp,
                settings_file=os.path.join(tmp, "none.json"),
                oauth_token_file=os.path.join(tmp, "missing-tok"),
                popen_fn=ErrorResultPopen,
            )
            with self.assertRaisesRegex(RuntimeError, "quota exceeded"):
                err_adapter.send("hi", fresh=True)

    def test_chat_manager_live_progress_throttling_and_final_summary(self):
        started_ev = threading.Event()
        release_mid = threading.Event()
        release_end = threading.Event()
        now_box = [1000.0]

        class StreamingFakeAdapter:
            def send(self, text, *, fresh=False, attachments=None, on_step=None):
                started_ev.set()
                if on_step:
                    # Step 1 publishes immediately
                    on_step("running `git status`", "tool")
                    # Steps 2 & 3 arrive immediately inside throttle window -> coalesced via trailing flush
                    on_step("Checking notes", "text")
                    on_step("reading NOTES.md", "tool")
                release_mid.wait(timeout=3.0)
                if on_step:
                    # Trailing final reply text -> stripped from persisted final summary
                    on_step("All done!", "text")
                now_box[0] = 1042.0
                release_end.wait(timeout=3.0)
                return "All done!"

        with tempfile.TemporaryDirectory() as tmp:
            store = chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl"))
            mgr = chat_mod.ChatManager(
                store=store,
                engine_fn=lambda: "claude",
                claude_adapter=StreamingFakeAdapter(),
                clock=lambda: now_box[0],
                last_channel_file=os.path.join(tmp, "last-channel.json"),
                progress_min_interval=0.06,
            )

            code, _ = mgr.handle_send({"text": "check repo", "client_id": "prog-1"})
            self.assertEqual(code, 202)
            self.assertTrue(started_ev.wait(timeout=2.0))

            # Wait past throttle flush interval so steps 1..3 are visible live
            time.sleep(0.12)
            _, mid = mgr.handle_get_messages({})
            self.assertTrue(mid["running"])
            self.assertIsNotNone(mid["progress"])
            self.assertEqual(mid["progress"]["engine"], "claude")
            self.assertEqual(mid["progress"]["started"], 1000.0)
            self.assertEqual(
                [s["text"] for s in mid["progress"]["steps"]],
                ["running `git status`", "Checking notes", "reading NOTES.md"],
            )

            release_mid.set()
            release_end.set()
            self.assertTrue(mgr.wait_idle(timeout=3.0))

            _, done = mgr.handle_get_messages({})
            self.assertFalse(done["running"])
            self.assertIsNone(done["progress"])
            asst = done["messages"][-1]
            self.assertEqual(asst["role"], "assistant")
            self.assertEqual(asst["text"], "All done!")
            self.assertEqual(asst["progress"]["duration_s"], 42)
            self.assertEqual(asst["progress"]["step_count"], 3)
            self.assertEqual(
                [s["text"] for s in asst["progress"]["steps"]],
                ["running `git status`", "Checking notes", "reading NOTES.md"],
            )


class T3ClaudeAndActivityProgressTests(unittest.TestCase):
    def test_build_claude_model_selection_defaults_and_options(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = os.path.join(tmp, "missing.json")
            self.assertEqual(
                chat_mod.build_claude_model_selection(missing),
                {"instanceId": "claudeAgent", "model": "claude-opus-5-5"},
            )

            cfg = os.path.join(tmp, "settings.json")
            with open(cfg, "w", encoding="utf-8") as f:
                json.dump({"model": "claude-sonnet-5-5", "effort": "ultrathink"}, f)
            self.assertEqual(
                chat_mod.build_claude_model_selection(cfg),
                {
                    "instanceId": "claudeAgent",
                    "model": "claude-sonnet-5-5",
                    "options": [{"id": "effort", "value": "ultrathink"}],
                },
            )

            with open(cfg, "w", encoding="utf-8") as f:
                json.dump({"model": "claude-haiku-5-5", "effort": "bogus"}, f)
            self.assertEqual(
                chat_mod.build_claude_model_selection(cfg),
                {"instanceId": "claudeAgent", "model": "claude-haiku-5-5"},
            )

    def test_t3_claude_adapter_creates_reuses_recovers_on_404_and_sends_attachments(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "claude-t3-thread.json")
            cfg = os.path.join(tmp, "claude-settings.json")
            with open(cfg, "w", encoding="utf-8") as f:
                json.dump({"model": "claude-sonnet-5-5", "effort": "xhigh"}, f)
            img = os.path.join(tmp, "screen.png")
            with open(img, "wb") as f:
                f.write(b"\x89PNG\r\n\x1a\nfake")

            calls = []
            launch_count = 0
            fail_next_read = [False]

            class FakeT3Claude:
                def upload_attachment(self, file_path, **kw):
                    return {
                        "type": "image",
                        "id": "att-c-1",
                        "name": "screen.png",
                        "mimeType": "image/png",
                        "sizeBytes": 12,
                    }

                def call_tool(self, name, args=None):
                    nonlocal launch_count
                    args = args or {}
                    calls.append((name, args))
                    if name == "t3_project_list":
                        return {"projects": [{"id": "proj-agent", "workspaceRoot": tmp}]}
                    if name == "t3_thread_launch":
                        launch_count += 1
                        return {
                            "threadId": f"thr-claude-{launch_count}",
                            "runId": f"run-launch-{launch_count}",
                        }
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        if fail_next_read[0]:
                            fail_next_read[0] = False
                            raise RuntimeError("T3 HTTP 404 on t3_thread_read: thread not found")
                        return {"thread": {"latestRunId": "run-prev"}, "items": [{"position": 4}]}
                    if name == "t3_thread_send":
                        return {"runId": "run-send"}
                    if name == "t3_thread_send_attachments":
                        return {"runId": "run-att"}
                    if name == "t3_thread_wait":
                        return {"status": "completed", "runId": args.get("runId")}
                    if name == "t3_thread_read" and args.get("view") == "messages":
                        return {
                            "items": [
                                {
                                    "itemId": "it-c",
                                    "type": "assistant_message",
                                    "runId": "run-att" if any(n == "t3_thread_send_attachments" for n, _ in calls[-4:]) else None,
                                    "text": f"reply #{launch_count}",
                                    "textTruncated": False,
                                }
                            ]
                        }
                    return {}

            adapter = chat_mod.T3ClaudeAdapter(
                client=FakeT3Claude(),
                thread_file=tf,
                settings_file=cfg,
                workdir=tmp,
                poll_wait_ms=50,
                retry_delay=0.0,
            )

            # 1. Fresh launch creates thread with claudeAgent modelSelection + bootstrap + state file.
            r1 = adapter.send("hello claude")
            self.assertEqual(r1, "reply #1")
            launch_call = next(a for n, a in calls if n == "t3_thread_launch")
            self.assertEqual(launch_call["title"], "PC Agent (Claude Chat)")
            self.assertEqual(
                launch_call["modelSelection"],
                {
                    "instanceId": "claudeAgent",
                    "model": "claude-sonnet-5-5",
                    "options": [{"id": "effort", "value": "xhigh"}],
                },
            )
            self.assertIn("PC Control Chat", launch_call["message"])
            self.assertIn("hello claude", launch_call["message"])
            saved = json.loads(Path(tf).read_text(encoding="utf-8"))
            self.assertEqual(saved["thread_id"], "thr-claude-1")
            self.assertEqual(saved["instance_id"], "claudeAgent")

            # 2. Follow-up reuses saved thread without bootstrap.
            calls.clear()
            r2 = adapter.send("follow up")
            self.assertEqual(r2, "reply #1")
            send_call = next(a for n, a in calls if n == "t3_thread_send")
            self.assertEqual(send_call["threadId"], "thr-claude-1")
            self.assertEqual(send_call["message"], "follow up")
            self.assertNotIn("freshContext", send_call)

            # 3. Attachment upload dispatches via t3_thread_send_attachments.
            calls.clear()
            r3 = adapter.send(
                "describe this",
                attachments=[{"path": img, "name": "screen.png", "mime_type": "image/png", "kind": "image"}],
            )
            self.assertEqual(r3, "reply #1")
            att_call = next(a for n, a in calls if n == "t3_thread_send_attachments")
            self.assertEqual(att_call["threadId"], "thr-claude-1")
            self.assertEqual(att_call["attachments"][0]["id"], "att-c-1")

            # 4. 404 on existing thread auto-recreates thread and updates state file.
            calls.clear()
            fail_next_read[0] = True
            r4 = adapter.send("after 404")
            self.assertEqual(r4, "reply #2")
            saved2 = json.loads(Path(tf).read_text(encoding="utf-8"))
            self.assertEqual(saved2["thread_id"], "thr-claude-2")

            # 5. fresh=True passes freshContext=True and includes bootstrap again.
            calls.clear()
            adapter.send("new topic", fresh=True)
            fresh_call = next(a for n, a in calls if n == "t3_thread_send")
            self.assertTrue(fresh_call.get("freshContext"))
            self.assertIn("PC Control Chat", fresh_call["message"])
            self.assertIn("new topic", fresh_call["message"])

    def test_format_t3_activity_item_all_types(self):
        # Ignored types
        self.assertIsNone(chat_mod.format_t3_activity_item({"type": "user_message", "text": "hi"}))
        self.assertIsNone(chat_mod.format_t3_activity_item({"type": "checkpoint", "text": "[{\"path\":\"a\"}]"}))
        self.assertIsNone(chat_mod.format_t3_activity_item({"type": "assistant_message", "text": "final"}))

        # Reasoning: strips markdown, takes first sentence, single line
        self.assertEqual(
            chat_mod.format_t3_activity_item(
                {
                    "type": "reasoning",
                    "text": "## **Checking** `server/chat.py` for adapter logic. Then we will write tests.\nMore lines.",
                }
            ),
            ("reasoning", "Checking server/chat.py for adapter logic."),
        )

        # Command execution: extracts $ cmd and skips workspace boilerplate
        self.assertIsNone(
            chat_mod.format_t3_activity_item(
                {
                    "type": "command_execution",
                    "title": "Workspace ready",
                    "text": "$ Preparing workspace\nWorkspace preparation completed.",
                }
            )
        )
        self.assertEqual(
            chat_mod.format_t3_activity_item(
                {
                    "type": "command_execution",
                    "title": "Ran command",
                    "text": "$ git status -sb\n## main",
                }
            ),
            ("tool", "running `git status -sb`"),
        )

        # File change: basename + count, never diffs
        self.assertEqual(
            chat_mod.format_t3_activity_item(
                {
                    "type": "file_change",
                    "title": "Changed files",
                    "text": "/home/ayushlive/code/pc-control/server/chat.py\n/home/ayushlive/code/pc-control/server/pcapi.py",
                }
            ),
            ("tool", "editing chat.py (+1)"),
        )

        # Dynamic tool: JSON toolName + input
        self.assertEqual(
            chat_mod.format_t3_activity_item(
                {
                    "type": "dynamic_tool",
                    "title": "Read /home/ayushlive/agent/NOTES.md",
                    "text": json.dumps({"toolName": "Read", "input": {"Path": "/home/ayushlive/agent/NOTES.md"}}),
                }
            ),
            ("tool", "reading NOTES.md"),
        )

        # Dynamic tool with truncated JSON falls back cleanly to title (never raw JSON)
        self.assertEqual(
            chat_mod.format_t3_activity_item(
                {
                    "type": "dynamic_tool",
                    "title": "Read /home/ayushlive/agent/CLAUDE.md",
                    "text": '{"toolName": "Read", "input": {"Path": "/home/ayushlive/agent/CLAUDE.md"...',
                }
            ),
            ("tool", "reading CLAUDE.md"),
        )

        # Telegram tools skipped
        self.assertIsNone(
            chat_mod.format_t3_activity_item(
                {
                    "type": "dynamic_tool",
                    "title": "telegram reply",
                    "text": json.dumps({"toolName": "mcp__telegram__reply", "input": {}}),
                }
            )
        )

    def test_t3_activity_progress_live_and_persisted_for_gemini_and_claude(self):
        for engine, adapter_cls in (("gemini", chat_mod.T3GeminiAdapter), ("claude", chat_mod.T3ClaudeAdapter)):
            with self.subTest(engine=engine), tempfile.TemporaryDirectory() as tmp:
                tf = os.path.join(tmp, f"{engine}-thread.json")
                with open(tf, "w", encoding="utf-8") as f:
                    json.dump({"thread_id": f"thr-{engine}", "project_id": "proj-1"}, f)

                wait_step = [0]
                gate = threading.Event()
                mid_seen = threading.Event()
                now_box = [2000.0]
                activity_after_positions = []

                class FakeStreamingT3:
                    def call_tool(self, name, args=None):
                        args = args or {}
                        if name == "t3_thread_read" and args.get("limit") == 1:
                            return {"thread": {"latestRunId": "run-old"}, "items": [{"position": 10}]}
                        if name == "t3_thread_send":
                            return {"runId": "run-cur"}
                        if name == "t3_thread_wait":
                            wait_step[0] += 1
                            if wait_step[0] == 1:
                                return {"timedOut": True, "runId": "run-cur"}
                            mid_seen.set()
                            gate.wait(timeout=3.0)
                            now_box[0] = 2018.0
                            return {"status": "completed", "runId": "run-cur"}
                        if name == "t3_thread_read" and args.get("view") == "activity":
                            activity_after_positions.append(args.get("afterPosition"))
                            if wait_step[0] == 1:
                                return {
                                    "items": [
                                        # Stale run item must be filtered out
                                        {
                                            "position": 10,
                                            "itemId": "old-1",
                                            "runId": "run-old",
                                            "type": "reasoning",
                                            "status": "completed",
                                            "text": "Old run thought",
                                        },
                                        {
                                            "position": 11,
                                            "itemId": "u-1",
                                            "runId": "run-cur",
                                            "type": "user_message",
                                            "status": "completed",
                                            "text": "check status",
                                        },
                                        {
                                            "position": 12,
                                            "itemId": "r-1",
                                            "runId": "run-cur",
                                            "type": "reasoning",
                                            "status": "in_progress",
                                            "text": "Checking service",
                                        },
                                    ],
                                    "hasMore": False,
                                    "nextPosition": 12,
                                }
                            # Second poll: r-1 completed with updated text + command_execution + tool + assistant + checkpoint
                            return {
                                "items": [
                                    {
                                        "position": 12,
                                        "itemId": "r-1",
                                        "runId": "run-cur",
                                        "type": "reasoning",
                                        "status": "completed",
                                        "text": "Checking service status and recent logs.",
                                    },
                                    {
                                        "position": 13,
                                        "itemId": "c-1",
                                        "runId": "run-cur",
                                        "type": "command_execution",
                                        "status": "completed",
                                        "title": "Ran command",
                                        "text": "$ systemctl --user is-active pc-api.service\nactive",
                                    },
                                    {
                                        "position": 14,
                                        "itemId": "t-1",
                                        "runId": "run-cur",
                                        "type": "dynamic_tool",
                                        "status": "completed",
                                        "title": "Read /home/ayushlive/agent/NOTES.md",
                                        "text": json.dumps(
                                            {"toolName": "Read", "input": {"Path": "/home/ayushlive/agent/NOTES.md"}}
                                        ),
                                    },
                                    {
                                        "position": 15,
                                        "itemId": "a-1",
                                        "runId": "run-cur",
                                        "type": "assistant_message",
                                        "status": "completed",
                                        "text": f"{engine} all good!",
                                    },
                                    {
                                        "position": 16,
                                        "itemId": "cp-1",
                                        "runId": "run-cur",
                                        "type": "checkpoint",
                                        "status": "completed",
                                        "text": "[]",
                                    },
                                ],
                                "hasMore": False,
                                "nextPosition": 16,
                            }
                        if name == "t3_thread_read" and args.get("view") == "messages":
                            return {
                                "items": [
                                    {
                                        "itemId": "a-1",
                                        "type": "assistant_message",
                                        "runId": "run-cur",
                                        "text": f"{engine} all good!",
                                        "textTruncated": False,
                                    }
                                ]
                            }
                        return {}

                adapter = adapter_cls(
                    client=FakeStreamingT3(),
                    thread_file=tf,
                    settings_file=os.path.join(tmp, "none.json"),
                    poll_wait_ms=50,
                    retry_delay=0.0,
                )
                store = chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl"))
                mgr = chat_mod.ChatManager(
                    store=store,
                    engine_fn=lambda e=engine: e,
                    gemini_adapter=adapter if engine == "gemini" else None,
                    claude_adapter=adapter if engine == "claude" else None,
                    clock=lambda: now_box[0],
                    last_channel_file=os.path.join(tmp, "last-channel.json"),
                    progress_min_interval=0.01,
                )

                code, _ = mgr.handle_send({"text": "check status", "client_id": f"cid-{engine}"})
                self.assertEqual(code, 202)
                self.assertTrue(mid_seen.wait(timeout=3.0))

                # Live progress shows the in_progress reasoning step before completion.
                _, mid = mgr.handle_get_messages({})
                self.assertTrue(mid["running"])
                self.assertIsNotNone(mid["progress"])
                self.assertEqual(mid["progress"]["engine"], engine)
                self.assertEqual([s["text"] for s in mid["progress"]["steps"]], ["Checking service"])

                gate.set()
                self.assertTrue(mgr.wait_idle(timeout=3.0))

                _, done = mgr.handle_get_messages({})
                self.assertFalse(done["running"])
                self.assertIsNone(done["progress"])
                asst = done["messages"][-1]
                self.assertEqual(asst["role"], "assistant")
                self.assertEqual(asst["engine"], engine)
                self.assertEqual(asst["text"], f"{engine} all good!")
                # r-1 was updated in place (3 steps total, not 4!), old run & user/checkpoint/assistant excluded
                self.assertEqual(activity_after_positions, [10, 11])
                self.assertEqual(asst["progress"]["duration_s"], 18)
                self.assertEqual(asst["progress"]["step_count"], 3)
                self.assertEqual(
                    [s["text"] for s in asst["progress"]["steps"]],
                    [
                        "Checking service status and recent logs.",
                        "running `systemctl --user is-active pc-api.service`",
                        "reading NOTES.md",
                    ],
                )


class ChatSteeringAndSseTests(unittest.TestCase):
    """Tests for queued message tracking, immediate steering (local + T3 queue), races, and SSE events."""

    def _drain_events(self, q):
        events = []
        while True:
            try:
                events.append(q.get_nowait())
            except Exception:
                break
        return events

    def test_queue_two_messages_and_steer_second_via_t3_mode_steer_for_both_engines(self):
        for engine, adapter_cls in (("gemini", chat_mod.T3GeminiAdapter), ("claude", chat_mod.T3ClaudeAdapter)):
            with self.subTest(engine=engine), tempfile.TemporaryDirectory() as tmp:
                tf = os.path.join(tmp, f"{engine}-thread.json")
                with open(tf, "w", encoding="utf-8") as f:
                    json.dump({"thread_id": f"thr-{engine}", "project_id": "proj-1"}, f)

                first_running = threading.Event()
                release_first = threading.Event()
                send_calls = []

                class FakeT3SteerClient:
                    def call_tool(self, name, args=None):
                        args = args or {}
                        if name == "t3_thread_read" and args.get("limit") == 1:
                            return {"thread": {"latestRunId": "run-prev"}, "items": [{"position": 5}]}
                        if name == "t3_thread_send":
                            send_calls.append(dict(args))
                            if args.get("mode") == "steer":
                                return {"ok": True, "steered": True, "runId": "run-1"}
                            return {"runId": "run-1", "status": "running"}
                        if name == "t3_thread_wait":
                            first_running.set()
                            if not release_first.is_set():
                                release_first.wait(timeout=3.0)
                            return {"status": "completed", "runId": "run-1"}
                        if name == "t3_thread_read":
                            return {
                                "items": [
                                    {
                                        "itemId": "asst-1",
                                        "type": "assistant_message",
                                        "runId": "run-1",
                                        "text": f"{engine} combined steered reply",
                                        "textTruncated": False,
                                    }
                                ],
                                "hasMore": False,
                            }
                        return {}

                adapter = adapter_cls(
                    client=FakeT3SteerClient(),
                    thread_file=tf,
                    settings_file=os.path.join(tmp, "none.json"),
                    poll_wait_ms=50,
                    retry_delay=0.0,
                )
                store = chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl"))
                mgr = chat_mod.ChatManager(
                    store=store,
                    engine_fn=lambda e=engine: e,
                    gemini_adapter=adapter if engine == "gemini" else None,
                    claude_adapter=adapter if engine == "claude" else None,
                    last_channel_file=os.path.join(tmp, "last-channel.json"),
                )
                sub = mgr.subscribe()
                try:
                    # 1. Send first message -> actively running, not queued.
                    c1, r1 = mgr.handle_send({"text": "first prompt", "client_id": f"{engine}-1"})
                    self.assertEqual(c1, 202)
                    self.assertFalse(r1["user_message"]["queued"])
                    self.assertTrue(first_running.wait(timeout=3.0))

                    # 2. Send second message while first is running -> queued=True.
                    c2, r2 = mgr.handle_send({"text": "actually do B instead", "client_id": f"{engine}-2"})
                    self.assertEqual(c2, 202)
                    msg2_id = r2["user_message"]["id"]
                    self.assertTrue(r2["user_message"]["queued"])

                    _, mid = mgr.handle_get_messages({})
                    self.assertTrue(mid["running"])
                    self.assertEqual(mid["queued_ids"], [msg2_id])
                    by_id = {m["id"]: m for m in mid["messages"]}
                    self.assertFalse(by_id[r1["user_message"]["id"]]["queued"])
                    self.assertTrue(by_id[msg2_id]["queued"])

                    # 3. Steer second message immediately.
                    cs, rs = mgr.handle_steer({"message_id": msg2_id})
                    self.assertEqual(cs, 200)
                    self.assertTrue(rs["ok"])
                    self.assertEqual(rs["status"], "steered")
                    self.assertTrue(rs["steered"])
                    self.assertFalse(rs["user_message"]["queued"])
                    self.assertTrue(rs["user_message"]["steered"])
                    self.assertTrue(rs["running"])

                    # Verify mode="steer" was called on the active T3 thread.
                    steer_sends = [c for c in send_calls if c.get("mode") == "steer"]
                    self.assertEqual(len(steer_sends), 1)
                    self.assertEqual(steer_sends[0]["threadId"], f"thr-{engine}")
                    self.assertEqual(steer_sends[0]["message"], "actually do B instead")

                    # 4. Release first turn and wait until idle.
                    release_first.set()
                    self.assertTrue(mgr.wait_idle(timeout=3.0))

                    # Crucial: msg 2 must NOT have been re-executed after msg 1 finished!
                    queue_sends = [c for c in send_calls if c.get("mode") == "queue"]
                    self.assertEqual(len(queue_sends), 1)
                    self.assertEqual(queue_sends[0]["message"], "first prompt")

                    _, final = mgr.handle_get_messages({})
                    self.assertFalse(final["running"])
                    self.assertEqual(final["queued_ids"], [])
                    # Exactly 2 user messages + 1 combined assistant reply (no duplicate second turn).
                    self.assertEqual(
                        [(m["role"], m["text"]) for m in final["messages"]],
                        [
                            ("user", "first prompt"),
                            ("user", "actually do B instead"),
                            ("assistant", f"{engine} combined steered reply"),
                        ],
                    )
                    self.assertFalse(final["messages"][1]["queued"])
                    self.assertTrue(final["messages"][1]["steered"])

                    # Check SSE transitions: queued=True -> steered/queued=False -> assistant done (running=False).
                    events = self._drain_events(sub)
                    msg_events = [payload for ev, payload in events if ev == "message"]
                    msg2_events = [p for p in msg_events if p["message"]["id"] == msg2_id]
                    self.assertGreaterEqual(len(msg2_events), 2)
                    self.assertTrue(msg2_events[0]["message"]["queued"])
                    self.assertFalse(msg2_events[-1]["message"]["queued"])
                    self.assertTrue(msg2_events[-1]["message"]["steered"])
                    asst_events = [p for p in msg_events if p["message"]["role"] == "assistant"]
                    self.assertEqual(len(asst_events), 1)
                    self.assertFalse(asst_events[0]["running"])
                finally:
                    mgr.unsubscribe(sub)

    def test_steer_with_attachments_includes_paths_and_clears_paired_pending_placeholder(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "t3-thread.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"thread_id": "thr-att", "project_id": "proj-1"}, f)

            first_running = threading.Event()
            release_first = threading.Event()
            steer_messages = []

            class FakeT3AttSteer:
                def call_tool(self, name, args=None):
                    args = args or {}
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        return {"thread": {"latestRunId": "run-0"}, "items": [{"position": 1}]}
                    if name == "t3_thread_send":
                        if args.get("mode") == "steer":
                            steer_messages.append(args.get("message"))
                            return {"ok": True}
                        return {"runId": "run-1"}
                    if name == "t3_thread_wait":
                        first_running.set()
                        release_first.wait(timeout=3.0)
                        return {"status": "completed", "runId": "run-1"}
                    if name == "t3_thread_read":
                        return {
                            "items": [
                                {"itemId": "a1", "type": "assistant_message", "runId": "run-1", "text": "saw image"}
                            ],
                            "hasMore": False,
                        }
                    return {}

            adapter = chat_mod.T3GeminiAdapter(
                client=FakeT3AttSteer(),
                thread_file=tf,
                settings_file=os.path.join(tmp, "none.json"),
                poll_wait_ms=50,
                retry_delay=0.0,
            )
            store = chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl"))
            mgr = chat_mod.ChatManager(
                store=store,
                engine_fn=lambda: "gemini",
                gemini_adapter=adapter,
                uploads_dir=os.path.join(tmp, "uploads"),
                last_channel_file=os.path.join(tmp, "last-channel.json"),
            )
            sub = mgr.subscribe()
            try:
                up_code, up = mgr.handle_upload(
                    {
                        "name": "shot.png",
                        "mime_type": "image/png",
                        "data_base64": base64.b64encode(b"\x89PNG\r\n\x1a\n1234").decode("ascii"),
                    }
                )
                self.assertEqual(up_code, 200)

                mgr.handle_send({"text": "first"})
                self.assertTrue(first_running.wait(timeout=3.0))

                c2, r2 = mgr.handle_send({"text": "look at this", "attachments": [up["id"]]})
                self.assertEqual(c2, 202)
                msg2_id = r2["user_message"]["id"]

                # Simulate a legacy paired pending assistant placeholder for msg2 to verify cleanup.
                store.append(
                    {
                        "id": "pend-placeholder-2",
                        "role": "assistant",
                        "engine": "gemini",
                        "text": "",
                        "created_at": time.time(),
                        "status": "pending",
                        "reply_to": msg2_id,
                    }
                )

                cs, rs = mgr.handle_steer({"message_id": msg2_id})
                self.assertEqual(cs, 200)
                self.assertTrue(rs["steered"])
                self.assertEqual(len(steer_messages), 1)
                self.assertIn("look at this", steer_messages[0])
                self.assertIn("[attachment: ", steer_messages[0])
                self.assertIn("shot.png]", steer_messages[0])

                # Paired pending placeholder must be removed cleanly (not left behind as a stuck shimmer).
                self.assertIsNone(store.get_by_id("pend-placeholder-2"))
                events = self._drain_events(sub)
                steer_ev = [p for ev, p in events if ev == "message" and p.get("steered")]
                self.assertEqual(steer_ev[-1].get("removed_message_id"), "pend-placeholder-2")

                release_first.set()
                self.assertTrue(mgr.wait_idle(timeout=3.0))
            finally:
                mgr.unsubscribe(sub)

    def test_steer_t3_queued_run_via_t3_queue_promote_to_steer(self):
        with tempfile.TemporaryDirectory() as tmp:
            tf = os.path.join(tmp, "t3-thread.json")
            with open(tf, "w", encoding="utf-8") as f:
                json.dump({"thread_id": "thr-q", "project_id": "proj-1"}, f)

            t3_queued_seen = threading.Event()
            promoted = threading.Event()
            queue_calls = []
            wait_run_ids = []

            class FakeT3QueuedClient:
                def call_tool(self, name, args=None):
                    args = args or {}
                    if name == "t3_thread_read" and args.get("limit") == 1:
                        return {
                            "thread": {"latestRunId": "run-ext-1", "activeRunId": "run-ext-1", "status": "running"},
                            "items": [{"position": 12}],
                        }
                    if name == "t3_thread_send":
                        return {"queuedRunId": "run-q-2", "status": "queued", "activeRunId": "run-ext-1"}
                    if name == "t3_queue":
                        queue_calls.append(dict(args))
                        promoted.set()
                        return {"ok": True, "targetRunId": "run-ext-1"}
                    if name == "t3_thread_wait":
                        rid = args.get("runId")
                        wait_run_ids.append(rid)
                        if rid == "run-q-2":
                            t3_queued_seen.set()
                            promoted.wait(timeout=3.0)
                            # Old queued run is cancelled when promoted to steer run-ext-1
                            return {"status": "cancelled", "runId": "run-q-2"}
                        if rid == "run-ext-1":
                            return {"status": "completed", "runId": "run-ext-1"}
                        return {"timedOut": True}
                    if name == "t3_thread_read":
                        return {
                            "items": [
                                {
                                    "itemId": "it-ext-1",
                                    "type": "assistant_message",
                                    "runId": "run-ext-1",
                                    "text": "Promoted steer handled in run-ext-1",
                                    "textTruncated": False,
                                }
                            ],
                            "hasMore": False,
                        }
                    return {}

            adapter = chat_mod.T3GeminiAdapter(
                client=FakeT3QueuedClient(),
                thread_file=tf,
                settings_file=os.path.join(tmp, "none.json"),
                poll_wait_ms=50,
                retry_delay=0.0,
            )
            store = chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl"))
            mgr = chat_mod.ChatManager(
                store=store,
                engine_fn=lambda: "gemini",
                gemini_adapter=adapter,
                last_channel_file=os.path.join(tmp, "last-channel.json"),
            )
            sub = mgr.subscribe()
            try:
                c1, r1 = mgr.handle_send({"text": "steer external run"})
                self.assertEqual(c1, 202)
                msg_id = r1["user_message"]["id"]
                self.assertTrue(t3_queued_seen.wait(timeout=3.0))

                # Because T3 reported queuedRunId behind run-ext-1, message is now marked queued=True.
                _, mid = mgr.handle_get_messages({})
                self.assertTrue(mid["messages"][0]["queued"])
                self.assertEqual(mid["queued_ids"], [msg_id])

                cs, rs = mgr.handle_steer({"message_id": msg_id})
                self.assertEqual(cs, 200)
                self.assertEqual(rs["status"], "steered")
                self.assertTrue(rs["steered"])
                self.assertFalse(rs["user_message"]["queued"])
                self.assertTrue(rs["user_message"]["steered"])

                self.assertEqual(len(queue_calls), 1)
                self.assertEqual(
                    queue_calls[0],
                    {
                        "action": "promote_to_steer",
                        "threadId": "thr-q",
                        "queuedRunId": "run-q-2",
                        "targetRunId": "run-ext-1",
                    },
                )

                self.assertTrue(mgr.wait_idle(timeout=3.0))
                self.assertIn("run-ext-1", wait_run_ids)

                _, after = mgr.handle_get_messages({})
                self.assertFalse(after["running"])
                self.assertEqual(after["messages"][-1]["status"], "done")
                self.assertEqual(after["messages"][-1]["text"], "Promoted steer handled in run-ext-1")
            finally:
                mgr.unsubscribe(sub)

    def test_steer_race_when_active_turn_finishes_first_does_not_lose_or_duplicate(self):
        with tempfile.TemporaryDirectory() as tmp:
            first_started = threading.Event()
            release_first = threading.Event()
            send_calls = []

            class RaceAdapter:
                def send(self, text, *, fresh=False, attachments=None):
                    send_calls.append(text)
                    if text == "turn 1":
                        first_started.set()
                        release_first.wait(timeout=3.0)
                        return "reply 1"
                    return f"reply for {text}"

                def steer(self, text, *, attachments=None, thread_id=None):
                    # Simulate race: active turn finished in T3 milliseconds before steer reached T3!
                    release_first.set()
                    raise RuntimeError("T3 tool error on t3_thread_send: thread is idle (no active run)")

            store = chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl"))
            mgr = chat_mod.ChatManager(
                store=store,
                engine_fn=lambda: "gemini",
                gemini_adapter=RaceAdapter(),
                last_channel_file=os.path.join(tmp, "last-channel.json"),
            )

            mgr.handle_send({"text": "turn 1"})
            self.assertTrue(first_started.wait(timeout=3.0))

            c2, r2 = mgr.handle_send({"text": "turn 2"})
            self.assertEqual(c2, 202)
            msg2_id = r2["user_message"]["id"]
            self.assertTrue(r2["user_message"]["queued"])

            # Steer hits the race error -> falls back to executing turn 2 normally without losing it.
            cs, rs = mgr.handle_steer({"message_id": msg2_id})
            self.assertEqual(cs, 200)
            self.assertTrue(rs["ok"])
            self.assertEqual(rs["status"], "dispatched")
            self.assertFalse(rs["steered"])

            self.assertTrue(mgr.wait_idle(timeout=3.0))
            self.assertEqual(send_calls, ["turn 1", "turn 2"])

            _, final = mgr.handle_get_messages({})
            self.assertFalse(final["running"])
            self.assertEqual(
                [(m["role"], m["text"]) for m in final["messages"]],
                [
                    ("user", "turn 1"),
                    ("user", "turn 2"),
                    ("assistant", "reply 1"),
                    ("assistant", "reply for turn 2"),
                ],
            )
            self.assertFalse(final["messages"][1]["queued"])

            # Steering an already-completed message is idempotent 200 already_completed.
            c_again, r_again = mgr.handle_steer({"message_id": msg2_id})
            self.assertEqual(c_again, 200)
            self.assertEqual(r_again["status"], "already_completed")

    def test_unsteered_queued_message_transitions_over_sse_and_store_recovers_stale_queued(self):
        with tempfile.TemporaryDirectory() as tmp:
            hist_path = os.path.join(tmp, "hist.jsonl")
            first_started = threading.Event()
            release_first = threading.Event()
            second_started = threading.Event()
            release_second = threading.Event()

            class TwoStepAdapter:
                def send(self, text, *, fresh=False, attachments=None):
                    if text == "msg A":
                        first_started.set()
                        release_first.wait(timeout=3.0)
                        return "reply A"
                    second_started.set()
                    release_second.wait(timeout=3.0)
                    return "reply B"

            store = chat_mod.ChatHistoryStore(hist_path)
            mgr = chat_mod.ChatManager(
                store=store,
                engine_fn=lambda: "claude",
                claude_adapter=TwoStepAdapter(),
                last_channel_file=os.path.join(tmp, "last-channel.json"),
            )
            sub = mgr.subscribe()
            try:
                mgr.handle_send({"text": "msg A"})
                self.assertTrue(first_started.wait(timeout=3.0))
                _, r2 = mgr.handle_send({"text": "msg B"})
                msg2_id = r2["user_message"]["id"]
                self.assertTrue(r2["user_message"]["queued"])

                # Release msg A -> msg B should immediately transition queued=False when worker starts it.
                release_first.set()
                self.assertTrue(second_started.wait(timeout=3.0))
                _, mid = mgr.handle_get_messages({})
                msg_b = next(m for m in mid["messages"] if m["id"] == msg2_id)
                self.assertFalse(msg_b["queued"])
                self.assertTrue(mid["running"])

                # Steering msg B while it is already actively executing returns already_running.
                c_run, r_run = mgr.handle_steer({"message_id": msg2_id})
                self.assertEqual(c_run, 200)
                self.assertEqual(r_run["status"], "already_running")

                release_second.set()
                self.assertTrue(mgr.wait_idle(timeout=3.0))

                events = self._drain_events(sub)
                msg_b_transitions = [
                    p["message"]["queued"]
                    for ev, p in events
                    if ev == "message" and p["message"]["id"] == msg2_id
                ]
                self.assertEqual(msg_b_transitions, [True, False])
            finally:
                mgr.unsubscribe(sub)

            # Startup recovery clears any stale queued=True user message after a crash/restart.
            store.append(
                {
                    "id": "stale-queued-user",
                    "role": "user",
                    "engine": "claude",
                    "text": "interrupted queued msg",
                    "created_at": time.time(),
                    "status": "done",
                    "queued": True,
                }
            )
            reloaded = chat_mod.ChatHistoryStore(hist_path)
            rec = reloaded.get_by_id("stale-queued-user")
            self.assertIsNotNone(rec)
            self.assertFalse(rec["queued"])

    def test_http_steer_and_sse_events_accept_chat_token_and_tailnet(self):
        with tempfile.TemporaryDirectory() as tmp:
            token_path = os.path.join(tmp, "chat-token")
            secret = "steer-secret-token-xyz"
            with open(token_path, "w", encoding="utf-8") as f:
                f.write(secret + "\n")

            first_started = threading.Event()
            release_first = threading.Event()
            steered_texts = []

            class FakeHttpSteerAdapter:
                def send(self, text, *, fresh=False, attachments=None):
                    first_started.set()
                    release_first.wait(timeout=3.0)
                    return "done with steer"

                def steer(self, text, *, attachments=None, thread_id=None):
                    steered_texts.append(text)
                    return {"ok": True}

            mgr = chat_mod.ChatManager(
                store=chat_mod.ChatHistoryStore(os.path.join(tmp, "hist.jsonl")),
                engine_fn=lambda: "gemini",
                gemini_adapter=FakeHttpSteerAdapter(),
                last_channel_file=os.path.join(tmp, "last-channel.json"),
            )

            patches = [
                mock.patch.object(pcapi, "CHAT", mgr),
                mock.patch.object(pcapi, "ALLOWED_LOGINS", {"ayush@example.com"}),
                mock.patch.object(chat_mod, "CHAT_TOKEN_FILE", token_path),
            ]
            for p in patches:
                p.start()
            srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
            port = srv.server_address[1]
            t = threading.Thread(target=srv.serve_forever, daemon=True)
            t.start()
            base = f"http://127.0.0.1:{port}"
            cf_headers = {"X-Forwarded-For": "203.0.113.10", "Authorization": f"Bearer {secret}"}
            unauth_headers = {"X-Forwarded-For": "203.0.113.10"}

            try:
                def req(method, path, body=None, headers=None):
                    h = dict(headers or {})
                    data = None
                    if body is not None:
                        data = json.dumps(body).encode("utf-8")
                        h["Content-Type"] = "application/json"
                    r = urllib.request.Request(base + path, data=data, headers=h, method=method)
                    try:
                        with urllib.request.urlopen(r, timeout=5) as resp:
                            return resp.status, json.loads(resp.read().decode("utf-8"))
                    except urllib.error.HTTPError as e:
                        raw = e.read().decode("utf-8", errors="replace")
                        e.close()
                        return e.code, json.loads(raw)

                # Unauthorized requests rejected on both /steer and /events
                code_unauth, _ = req("POST", "/pc/api/chat/steer", {"message_id": "x"}, headers=unauth_headers)
                self.assertEqual(code_unauth, 403)
                try:
                    urllib.request.urlopen(
                        urllib.request.Request(base + "/pc/api/chat/events", headers=unauth_headers), timeout=3
                    )
                    self.fail("Expected 403 for unauthenticated SSE")
                except urllib.error.HTTPError as e:
                    self.assertEqual(e.code, 403)
                    e.close()

                # Validation: 400 on missing message_id, 404 on unknown message_id
                c400, _ = req("POST", "/pc/api/chat/steer", {}, headers=cf_headers)
                self.assertEqual(c400, 400)
                c404, _ = req("POST", "/pc/api/chat/steer", {"message_id": "nope"}, headers=cf_headers)
                self.assertEqual(c404, 404)

                # Open SSE stream using chat bearer token and read initial snapshot + live steer event
                sse_events = []
                sse_ready = threading.Event()
                sse_done = threading.Event()

                def read_sse():
                    r = urllib.request.Request(base + "/pc/api/chat/events", headers=cf_headers)
                    with urllib.request.urlopen(r, timeout=5) as resp:
                        ev_name = None
                        for raw_line in resp:
                            line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                            if line.startswith("event: "):
                                ev_name = line[7:].strip()
                            elif line.startswith("data: "):
                                payload = json.loads(line[6:])
                                sse_events.append((ev_name, payload))
                                if ev_name == "snapshot":
                                    sse_ready.set()
                                if ev_name == "message" and payload.get("steered"):
                                    sse_done.set()
                                    break

                sse_thread = threading.Thread(target=read_sse, daemon=True)
                sse_thread.start()
                self.assertTrue(sse_ready.wait(timeout=3.0))

                c1, _ = req("POST", "/pc/api/chat/send", {"text": "first"}, headers=cf_headers)
                self.assertEqual(c1, 202)
                self.assertTrue(first_started.wait(timeout=3.0))

                c2, r2 = req("POST", "/pc/api/chat/send", {"text": "steer me"}, headers=cf_headers)
                self.assertEqual(c2, 202)
                self.assertTrue(r2["user_message"]["queued"])

                cs, rs = req("POST", "/pc/api/chat/steer", {"message_id": r2["user_message"]["id"]}, headers=cf_headers)
                self.assertEqual(cs, 200)
                self.assertTrue(rs["steered"])
                self.assertEqual(steered_texts, ["steer me"])

                self.assertTrue(sse_done.wait(timeout=3.0))
                sse_thread.join(timeout=2.0)
                self.assertEqual(sse_events[0][0], "snapshot")
                self.assertTrue(any(ev == "message" and p.get("steered") for ev, p in sse_events))

                release_first.set()
                self.assertTrue(mgr.wait_idle(timeout=3.0))
            finally:
                release_first.set()
                srv.shutdown()
                srv.server_close()
                for p in reversed(patches):
                    p.stop()

    def test_t3_client_allows_concurrent_steer_while_thread_wait_is_blocking(self):
        """T3Client.call_tool must not hold its lock across _post_rpc so steer doesn't stall behind t3_thread_wait."""
        with tempfile.TemporaryDirectory() as td:
            creds = os.path.join(td, "creds.json")
            with open(creds, "w", encoding="utf-8") as f:
                json.dump({"mcpOAuth": {"t3|local": {"accessToken": "tok-1"}}}, f)

            client = chat_mod.T3Client(mcp_url="http://127.0.0.1:3773/mcp", credentials_file=creds)
            client._session_id = "sid-1"

            wait_entered = threading.Event()
            release_wait = threading.Event()
            steer_finished = threading.Event()

            def fake_post_rpc(body, *, token, sid=None):
                name = (body.get("params") or {}).get("name")
                if name == "t3_thread_wait":
                    wait_entered.set()
                    release_wait.wait(timeout=5.0)
                    return {"result": {"content": [{"type": "text", "text": json.dumps({"status": "running"})}]}}, sid
                if name == "t3_thread_send":
                    return {"result": {"content": [{"type": "text", "text": json.dumps({"ok": True, "steered": True})}]}}, sid
                return {"result": {"content": []}}, sid

            client._post_rpc = fake_post_rpc

            wait_result = {}

            def run_wait():
                wait_result["res"] = client.call_tool("t3_thread_wait", {"threadId": "thr-1", "timeoutMs": 2000})

            t = threading.Thread(target=run_wait, daemon=True)
            t.start()
            self.assertTrue(wait_entered.wait(timeout=2.0))

            steer_res = client.call_tool(
                "t3_thread_send",
                {"threadId": "thr-1", "message": "pivot now", "mode": "steer"},
            )
            steer_finished.set()
            self.assertEqual(steer_res, {"ok": True, "steered": True})
            self.assertTrue(t.is_alive(), "t3_thread_wait should still be blocking while steer completed")

            release_wait.set()
            t.join(timeout=2.0)
            self.assertFalse(t.is_alive())
            self.assertEqual(wait_result.get("res"), {"status": "running"})


class ChatStopEndpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tok_file = os.path.join(self.tmp.name, "chat-token")
        self.hist_file = os.path.join(self.tmp.name, "chat-history.jsonl")
        self.gemini_thread_file = os.path.join(self.tmp.name, "gemini-t3-thread.json")
        self.claude_thread_file = os.path.join(self.tmp.name, "claude-t3-thread.json")
        self.last_channel_file = os.path.join(self.tmp.name, "last-channel.json")
        self.secret = "stop-endpoint-chat-token-98765"
        with open(self.tok_file, "w", encoding="utf-8") as f:
            f.write(self.secret + "\n")

        self.engine = "gemini"
        self.notify = FakeNotify()

        class FakeT3StopClient:
            def __init__(self):
                self.calls = []
                self.wait_entered = threading.Event()
                self.interrupted_ev = threading.Event()
                self.interrupt_response = {"interrupted": True}
                self.interrupt_error = None
                self.block_wait = False

            def call_tool(self, name, arguments=None):
                args = dict(arguments or {})
                self.calls.append((name, args))
                if name == "t3_thread_read":
                    return {"thread": {"latestRunId": "run-prev"}, "items": []}
                if name == "t3_thread_send":
                    return {"threadId": args.get("threadId"), "runId": "run-active"}
                if name == "t3_thread_wait":
                    if self.block_wait:
                        self.wait_entered.set()
                        self.interrupted_ev.wait(timeout=5.0)
                        return {
                            "thread": {"threadId": args.get("threadId"), "latestRunId": "run-active"},
                            "run": {"runId": "run-active", "status": "interrupted"},
                            "status": "interrupted",
                            "error": "Stopped by user from PC Control Chat",
                        }
                    return {"status": "completed", "run": {"runId": "run-active", "status": "completed"}}
                if name == "t3_thread_interrupt":
                    if self.interrupt_error is not None:
                        raise self.interrupt_error
                    self.interrupted_ev.set()
                    return dict(self.interrupt_response)
                return {}

        self.gemini_client = FakeT3StopClient()
        self.claude_client = FakeT3StopClient()
        self.gemini_adapter = chat_mod.T3GeminiAdapter(
            client=self.gemini_client,
            thread_file=self.gemini_thread_file,
            poll_wait_ms=50,
            retry_delay=0.01,
        )
        self.claude_adapter = chat_mod.T3ClaudeAdapter(
            client=self.claude_client,
            thread_file=self.claude_thread_file,
            poll_wait_ms=50,
            retry_delay=0.01,
        )
        self.chat_mgr = chat_mod.ChatManager(
            store=chat_mod.ChatHistoryStore(self.hist_file),
            engine_fn=lambda: self.engine,
            gemini_adapter=self.gemini_adapter,
            claude_adapter=self.claude_adapter,
            notify=self.notify,
            last_channel_file=self.last_channel_file,
            progress_min_interval=0.0,
        )

        self.orig_chat_tok = chat_mod.CHAT_TOKEN_FILE
        self.orig_allowed = pcapi.ALLOWED_LOGINS
        self.orig_chat = pcapi.CHAT
        chat_mod.CHAT_TOKEN_FILE = self.tok_file
        pcapi.ALLOWED_LOGINS = {"ayush@example.com"}
        pcapi.CHAT = self.chat_mgr

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        self.thread = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.gemini_client.interrupted_ev.set()
        self.claude_client.interrupted_ev.set()
        self.chat_mgr.wait_idle(timeout=3.0)
        self.srv.shutdown()
        self.srv.server_close()
        chat_mod.CHAT_TOKEN_FILE = self.orig_chat_tok
        pcapi.ALLOWED_LOGINS = self.orig_allowed
        pcapi.CHAT = self.orig_chat
        self.tmp.cleanup()

    def _req(self, method, path, body=None, headers=None):
        url = f"http://127.0.0.1:{self.port}{path}"
        if body is None:
            data = None
        elif isinstance(body, bytes):
            data = body
        else:
            data = json.dumps(body).encode("utf-8")
        h = dict(headers or {})
        if data is not None and "Content-Type" not in h:
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

    def test_stop_requires_auth_and_accepts_chat_token_or_tailnet(self):
        with open(self.gemini_thread_file, "w", encoding="utf-8") as f:
            json.dump({"thread_id": "gem-thr-auth"}, f)

        # Unauthenticated proxied request -> 403
        code, _ = self._req("POST", "/pc/api/chat/stop", {}, headers={"X-Forwarded-For": "203.0.113.5"})
        self.assertEqual(code, 403)

        # Wrong bearer token -> 403
        code, _ = self._req(
            "POST",
            "/pc/api/chat/stop",
            {},
            headers={"X-Forwarded-For": "203.0.113.5", "Authorization": "Bearer wrong-token"},
        )
        self.assertEqual(code, 403)

        # Valid chat bearer token (Cloudflare Tunnel / agent.neekhar.dev) -> 200
        cf_headers = {"X-Forwarded-For": "203.0.113.5", "Authorization": f"Bearer {self.secret}"}
        code, data = self._req("POST", "/pc/api/chat/stop", b"", headers=cf_headers)
        self.assertEqual(code, 200)
        self.assertTrue(data["ok"])
        self.assertTrue(data["interrupted"])
        self.assertEqual(data["engine"], "gemini")
        self.assertEqual(data["threadId"], "gem-thr-auth")

        # Valid Tailscale identity -> 200
        ts_headers = {"X-Forwarded-For": "100.64.0.2", "Tailscale-User-Login": "ayush@example.com"}
        code, data = self._req("POST", "/pc/api/chat/stop", {}, headers=ts_headers)
        self.assertEqual(code, 200)
        self.assertTrue(data["ok"])
        self.assertEqual(data["threadId"], "gem-thr-auth")

    def test_stop_interrupts_active_gemini_turn_and_settles_inflight_state(self):
        with open(self.gemini_thread_file, "w", encoding="utf-8") as f:
            json.dump({"thread_id": "gem-thr-42"}, f)
        self.engine = "gemini"
        self.gemini_client.block_wait = True

        cf_headers = {"X-Forwarded-For": "203.0.113.5", "Authorization": f"Bearer {self.secret}"}
        q = self.chat_mgr.subscribe()
        try:
            # Start an active turn and queue a second message behind it.
            code1, _ = self._req("POST", "/pc/api/chat/send", {"text": "long task", "client_id": "s1"}, headers=cf_headers)
            self.assertEqual(code1, 202)
            self.assertTrue(self.gemini_client.wait_entered.wait(timeout=3.0))

            code2, _ = self._req("POST", "/pc/api/chat/send", {"text": "queued follow-up", "client_id": "s2"}, headers=cf_headers)
            self.assertEqual(code2, 202)
            self.assertTrue(self.chat_mgr.is_running())

            # Stop the active turn.
            code_stop, stop_data = self._req("POST", "/pc/api/chat/stop", {}, headers=cf_headers)
            self.assertEqual(code_stop, 200)
            self.assertEqual(stop_data["ok"], True)
            self.assertEqual(stop_data["interrupted"], True)
            self.assertEqual(stop_data["engine"], "gemini")
            self.assertEqual(stop_data["threadId"], "gem-thr-42")
            self.assertEqual(stop_data["running"], False)

            # Verify t3_thread_interrupt was called with exact threadId and reason.
            interrupt_calls = [args for name, args in self.gemini_client.calls if name == "t3_thread_interrupt"]
            self.assertEqual(
                interrupt_calls,
                [{"threadId": "gem-thr-42", "reason": "Stopped by user from PC Control Chat"}],
            )

            # Shimmer/working state settled immediately and worker unwinds cleanly.
            self.assertFalse(self.chat_mgr.is_running())
            self.assertTrue(self.chat_mgr.wait_idle(timeout=3.0))
            snap = self.chat_mgr.snapshot_state()
            self.assertFalse(snap["running"])
            self.assertIsNone(snap["progress"])
            self.assertEqual(snap["queued_ids"], [])

            # SSE emitted a snapshot with running=False and progress=None.
            events = []
            while True:
                try:
                    events.append(q.get_nowait())
                except queue.Empty:
                    break
            snaps = [payload for ev, payload in events if ev == "snapshot"]
            self.assertTrue(any(s.get("running") is False and s.get("progress") is None for s in snaps))

            # No error assistant bubble or error push notification emitted for user-initiated stop.
            msgs, _ = self.chat_mgr.store.get_messages(limit=20)
            self.assertFalse(any(m.get("status") == "error" for m in msgs))
            self.assertEqual(self.notify.calls, [])
        finally:
            self.chat_mgr.unsubscribe(q)

    def test_stop_interrupts_active_claude_thread(self):
        with open(self.claude_thread_file, "w", encoding="utf-8") as f:
            json.dump({"thread_id": "claude-thr-99"}, f)
        self.engine = "claude"
        self.claude_client.block_wait = True

        cf_headers = {"X-Forwarded-For": "203.0.113.5", "Authorization": f"Bearer {self.secret}"}
        code1, _ = self._req("POST", "/pc/api/chat/send", {"text": "claude task", "client_id": "c1"}, headers=cf_headers)
        self.assertEqual(code1, 202)
        self.assertTrue(self.claude_client.wait_entered.wait(timeout=3.0))

        code_stop, stop_data = self._req("POST", "/pc/api/chat/stop", {}, headers=cf_headers)
        self.assertEqual(code_stop, 200)
        self.assertEqual(stop_data["ok"], True)
        self.assertEqual(stop_data["interrupted"], True)
        self.assertEqual(stop_data["engine"], "claude")
        self.assertEqual(stop_data["threadId"], "claude-thr-99")
        self.assertEqual(stop_data["running"], False)

        interrupt_calls = [args for name, args in self.claude_client.calls if name == "t3_thread_interrupt"]
        self.assertEqual(
            interrupt_calls,
            [{"threadId": "claude-thr-99", "reason": "Stopped by user from PC Control Chat"}],
        )
        self.assertEqual(self.gemini_client.calls, [])
        self.assertTrue(self.chat_mgr.wait_idle(timeout=3.0))

    def test_stop_handles_idle_and_missing_thread_cleanly(self):
        cf_headers = {"X-Forwarded-For": "203.0.113.5", "Authorization": f"Bearer {self.secret}"}

        # 1. Missing thread file -> 200 ok=True, interrupted=False, threadId=None, no T3 call
        self.engine = "gemini"
        code, data = self._req("POST", "/pc/api/chat/stop", {}, headers=cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["ok"], True)
        self.assertEqual(data["interrupted"], False)
        self.assertEqual(data["engine"], "gemini")
        self.assertIsNone(data["threadId"])
        self.assertEqual(self.gemini_client.calls, [])

        # 2. Thread exists, T3 returns interrupted=False (idle)
        with open(self.gemini_thread_file, "w", encoding="utf-8") as f:
            json.dump({"thread_id": "gem-idle-1"}, f)
        self.gemini_client.interrupt_response = {"interrupted": False}
        code, data = self._req("POST", "/pc/api/chat/stop", {}, headers=cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["ok"], True)
        self.assertEqual(data["interrupted"], False)
        self.assertEqual(data["engine"], "gemini")
        self.assertEqual(data["threadId"], "gem-idle-1")

        # 3. Thread exists, T3 raises "no active run" error -> treated cleanly as interrupted=False
        self.engine = "claude"
        with open(self.claude_thread_file, "w", encoding="utf-8") as f:
            json.dump({"thread_id": "claude-idle-2"}, f)
        self.claude_client.interrupt_error = RuntimeError("Thread is idle: no active run to interrupt")
        code, data = self._req("POST", "/pc/api/chat/stop", {}, headers=cf_headers)
        self.assertEqual(code, 200)
        self.assertEqual(data["ok"], True)
        self.assertEqual(data["interrupted"], False)
        self.assertEqual(data["engine"], "claude")
        self.assertEqual(data["threadId"], "claude-idle-2")


class QueuedMessageDeleteTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = chat_mod.ChatHistoryStore(os.path.join(self.tmp.name, "chat.jsonl"))
        self.uploads_dir = os.path.join(self.tmp.name, "uploads")

    def test_delete_from_local_queue_removes_message_and_broadcasts(self):
        first_started = threading.Event()
        release_first = threading.Event()
        seen_texts = []

        class BlockingAdapter:
            def send(self, text, fresh=False, attachments=None):
                seen_texts.append(text)
                if text == "first":
                    first_started.set()
                    release_first.wait(timeout=5.0)
                return f"reply:{text}"

        mgr = chat_mod.ChatManager(
            store=self.store,
            claude_adapter=BlockingAdapter(),
            gemini_adapter=BlockingAdapter(),
            engine_fn=lambda: "claude",
            uploads_dir=self.uploads_dir,
        )
        q = mgr.subscribe()
        self.addCleanup(mgr.unsubscribe, q)

        c1, r1 = mgr.handle_send({"text": "first"})
        self.assertEqual(c1, 202)
        self.assertTrue(first_started.wait(timeout=3.0))

        c2, r2 = mgr.handle_send({"text": "queued-second"})
        self.assertEqual(c2, 202)
        queued_id = r2["user_message"]["id"]
        self.assertTrue(r2["user_message"].get("queued"))

        # Delete the locally queued message before the first turn finishes
        cd, rd = mgr.handle_delete_queued({"id": queued_id})
        self.assertEqual(cd, 200, rd)
        self.assertTrue(rd["ok"])
        self.assertTrue(rd["deleted"])
        self.assertEqual(rd["id"], queued_id)
        self.assertEqual(rd["queued_ids"], [])

        # Message removed from store and persists removal across reload
        self.assertIsNone(self.store.get_by_id(queued_id))
        reloaded = chat_mod.ChatHistoryStore(os.path.join(self.tmp.name, "chat.jsonl"))
        self.assertIsNone(reloaded.get_by_id(queued_id))

        # SSE events include message_deleted and updated state
        events = []
        while True:
            try:
                events.append(q.get_nowait())
            except Exception:
                break
        deleted_evs = [d for ev, d in events if ev == "message_deleted"]
        self.assertTrue(any(d.get("id") == queued_id for d in deleted_evs), events)
        state_evs = [d for ev, d in events if ev == "state" and d.get("queued_ids") == []]
        self.assertTrue(state_evs, events)

        # Release first turn and confirm deleted queued message NEVER runs
        release_first.set()
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        self.assertEqual(seen_texts, ["first"])

    def test_delete_t3_queued_run_cancels_and_suppresses_worker_output(self):
        queued_ready = threading.Event()
        release_send = threading.Event()
        cancel_calls = []

        class FakeT3Adapter:
            def send(self, text, fresh=False, attachments=None, on_t3_state=None):
                if on_t3_state:
                    on_t3_state(
                        {
                            "thread_id": "thr-42",
                            "run_id": "run-q1",
                            "queued_run_id": "run-q1",
                            "active_run_id": "run-active",
                            "queued": True,
                        }
                    )
                queued_ready.set()
                release_send.wait(timeout=5.0)
                raise RuntimeError("Queued run was cancelled")

            def cancel_queued_run(self, *, thread_id, queued_run_id):
                cancel_calls.append((thread_id, queued_run_id))
                return {"cancelled": True}

        mgr = chat_mod.ChatManager(
            store=self.store,
            claude_adapter=FakeT3Adapter(),
            gemini_adapter=FakeT3Adapter(),
            engine_fn=lambda: "claude",
            uploads_dir=self.uploads_dir,
        )
        q = mgr.subscribe()
        self.addCleanup(mgr.unsubscribe, q)

        c1, r1 = mgr.handle_send({"text": "t3-queued-msg"})
        self.assertEqual(c1, 202)
        msg_id = r1["user_message"]["id"]
        self.assertTrue(queued_ready.wait(timeout=3.0))

        cd, rd = mgr.delete_queued(msg_id)
        self.assertEqual(cd, 200, rd)
        self.assertEqual(cancel_calls, [("thr-42", "run-q1")])
        self.assertIsNone(self.store.get_by_id(msg_id))

        release_send.set()
        self.assertTrue(mgr.wait_idle(timeout=3.0))

        # No error or assistant reply should have been stored for the cancelled job
        msgs, _ = self.store.get_messages()
        self.assertEqual(msgs, [])

    def test_delete_t3_queued_already_running_returns_409_and_non_queued_rejected(self):
        queued_ready = threading.Event()
        release_send = threading.Event()

        class FakeT3RaceAdapter:
            def send(self, text, fresh=False, attachments=None, on_t3_state=None):
                if text == "race":
                    if on_t3_state:
                        on_t3_state(
                            {
                                "thread_id": "thr-99",
                                "run_id": "run-q9",
                                "queued_run_id": "run-q9",
                                "active_run_id": "run-a",
                                "queued": True,
                            }
                        )
                    queued_ready.set()
                    release_send.wait(timeout=5.0)
                return "done"

            def cancel_queued_run(self, *, thread_id, queued_run_id):
                raise RuntimeError("Run is no longer queued")

        mgr = chat_mod.ChatManager(
            store=self.store,
            claude_adapter=FakeT3RaceAdapter(),
            gemini_adapter=FakeT3RaceAdapter(),
            engine_fn=lambda: "claude",
            uploads_dir=self.uploads_dir,
        )

        # Missing id -> 400; unknown id -> 404
        self.assertEqual(mgr.handle_delete_queued({})[0], 400)
        self.assertEqual(mgr.handle_delete_queued({"id": "missing"})[0], 404)

        # Already completed message -> 409
        c_done, r_done = mgr.handle_send({"text": "completed"})
        self.assertEqual(c_done, 202)
        self.assertTrue(mgr.wait_idle(timeout=3.0))
        c_rej, _ = mgr.delete_queued(r_done["user_message"]["id"])
        self.assertEqual(c_rej, 409)

        # T3 race (no longer queued) -> 409
        c_race, r_race = mgr.handle_send({"text": "race"})
        self.assertEqual(c_race, 202)
        self.assertTrue(queued_ready.wait(timeout=3.0))
        c_409, d_409 = mgr.delete_queued(r_race["user_message"]["id"])
        self.assertEqual(c_409, 409)
        self.assertIn("already started running", d_409["error"].lower())

        release_send.set()
        self.assertTrue(mgr.wait_idle(timeout=3.0))


if __name__ == "__main__":
    unittest.main()
