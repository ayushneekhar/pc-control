"""rootd tests, run unprivileged:  python3 -I -m unittest discover -s server/tests -v

- canonical payload: Python (rootd) vs the app's own TypeScript (src/lib/rootPayload.ts via Node)
- signature verification: good, wrong key, wrong argv, wrong decision, replay, expired, revoked
- enrollment needs the admin uid (0 in production); the agent can only propose keys
- peer-uid rejection over a real socket, plus a real command run with output cap and timeout kill
"""
import base64, json, os, socket, subprocess, sys, tempfile, threading, time, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.dirname(HERE)
sys.path.insert(0, SERVER)
sys.path.insert(0, HERE)
import rootd  # noqa: E402
from fakephone import FakePhone, js  # noqa: E402

AGENT, ADMIN, STRANGER = 1000, 0, 4242

TRICKY = [
    ["/usr/bin/echo", "plain"],
    ["/bin/sh", "-c", 'echo "quoted" \'single\' $HOME'],
    ["/usr/bin/printf", "back\\slash", "\\n literal", "C:\\path\\"],
    ["/usr/bin/echo", "line1\nline2", "tab\there"],
    ["/usr/bin/echo", "ünïcödé", "日本語", "Ελληνικά", "עברית"],
    ["/usr/bin/echo", "emoji 😀🐈👍🏽🇮🇳", "𝔘𝔫𝔦𝔠𝔬𝔡𝔢", "\U0010fffd"],
    ["/usr/bin/echo", "</script>", "a/b", "{\"json\":[1,2]}", ""],
    ["/usr/bin/echo", "\u00a0nbsp", "\u2003emsp", "\ufffd", "é (e+combining)"],
]
BAD = [
    ["/usr/bin/echo", "nul\x00"], ["/usr/bin/echo", "cr\r"], ["/usr/bin/echo", "esc\x1b[31m"],
    ["/usr/bin/echo", "del\x7f"], ["/usr/bin/echo", "c1\x85"], ["/usr/bin/echo", "rlo\u202e"],
    ["/usr/bin/echo", "zw\u200b"], ["/usr/bin/echo", "ls\u2028"], ["/usr/bin/echo", "bom\ufeff"],
    ["/usr/bin/echo", "lone\ud800"], ["/usr/bin/echo", "lone\udc00"],
    ["/usr/bin/echo", "zwj emoji 🐈\u200d⬛"],   # invisible joiner: rejected on purpose (documented)
]


def setup_tmp_cfg(tc):
    tc.tmp = tempfile.TemporaryDirectory()
    rootd.Cfg.paths(tc.tmp.name)
    for d in (rootd.Cfg.conf, rootd.Cfg.state, rootd.Cfg.logdir, rootd.Cfg.run): os.makedirs(d, exist_ok=True)
    rootd.Cfg.allowed_uid, rootd.Cfg.admin_uid = AGENT, ADMIN
    rootd.requests.clear(); rootd.enroll_pending.clear(); rootd.history.clear()
    rootd.submit_times.clear(); rootd.submit_rl_sent.clear(); rootd.enroll_times.clear(); rootd.wait_counts.clear()


class Canonicalization(unittest.TestCase):
    def test_argv_hash_matches_app_js(self):
        out = js({"argvs": TRICKY})["argvs"]
        for argv, o in zip(TRICKY, out):
            self.assertIsNone(rootd.check_argv(argv), argv)
            self.assertEqual(rootd.canon_argv(argv), o["canon"].encode("utf-8"), argv)
            self.assertEqual(rootd.argv_hash(argv), o["hash"], argv)

    def test_hash_agrees_even_for_rejected_strings(self):
        # canonicalization agreement is independent of the acceptance policy
        sendable = [a for a in BAD if not any(0xD800 <= ord(c) <= 0xDFFF for c in a[1])]
        for argv, o in zip(sendable, js({"argvs": sendable})["argvs"]):
            self.assertEqual(rootd.argv_hash(argv), o["hash"], repr(argv))

    def test_payload_matches_app_js(self):
        req = {"id": "0123456789abcdef", "nonce": "f" * 32, "expires": 1794128000, "timeout": 120,
               "reason": "restart nginx", "snap_sha256": "a" * 64, "argv": TRICKY[5]}
        for d in ("approve", "deny"):
            self.assertEqual(js({"payloads": [{"decision": d, "req": req, "version": 2}]})["payloads"][0],
                             rootd.payload_v2(req, d).decode())
            self.assertEqual(js({"payloads": [{"decision": d, "req": req, "version": 1}]})["payloads"][0],
                             rootd.payload_v1(req, d).decode())

    def test_rejected_chars_agree(self):
        for argv in BAD:
            self.assertIsNotNone(rootd.check_argv(argv), repr(argv))
        # the app refuses to build a payload for them as well (lone surrogates can't cross JSON from Python,
        # so they are checked via the escape form)
        sendable = [a for a in BAD if not any(0xD800 <= ord(c) <= 0xDFFF for c in a[1])]
        self.assertEqual(js({"safe": sendable})["safe"], [False] * len(sendable))
        self.assertEqual(js({"safe": TRICKY})["safe"], [True] * len(TRICKY))
        res = subprocess.run(["node", "-e", "process.stdout.write(JSON.stringify(JSON.stringify(['\\ud800'])))"],
                             capture_output=True, text=True)
        self.assertIn("\\\\ud800", res.stdout)   # JS escapes lone surrogates; Python can't encode them: rejected

    def test_fingerprint_matches_app_js(self):
        phones = [FakePhone() for _ in range(3)]
        out = js({"ders": [p.der_b64 for p in phones]})["ders"]
        for p, fp in zip(phones, out):
            self.assertEqual(rootd.key_fp(p.pem), fp)
            self.assertRegex(fp, r"^[0-9A-F]{4}(-[0-9A-F]{4}){3}$")

    def test_norm_fp(self):
        self.assertEqual(rootd.norm_fp("abcd 1234-ef56 7890"), "ABCD-1234-EF56-7890")
        self.assertIsNone(rootd.norm_fp("ABCD-1234"))

    def test_non_p256_keys_rejected(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec, ed25519
        for k in (ec.generate_private_key(ec.SECP384R1()), ed25519.Ed25519PrivateKey.generate()):
            pem = k.public_key().public_bytes(serialization.Encoding.PEM,
                                              serialization.PublicFormat.SubjectPublicKeyInfo).decode()
            with self.assertRaises(ValueError): rootd.key_fp(pem)


class Protocol(unittest.TestCase):
    def setUp(self):
        setup_tmp_cfg(self)
        self.phone = FakePhone()
        self.enroll(self.phone)

    def tearDown(self):
        time.sleep(0.05)
        self.tmp.cleanup()

    def enroll(self, phone):
        r = rootd.handle({"op": "enroll_request", "name": "Pixel", "pubkey": phone.pem}, AGENT)
        self.assertEqual(r["fingerprint"], phone.fingerprint)
        r = rootd.handle({"op": "enroll_confirm", "fingerprint": phone.fingerprint}, ADMIN)
        self.assertTrue(r.get("ok"), r)

    def submit(self, argv=("echo", "hi")):
        r = rootd.handle({"op": "submit", "argv": list(argv), "reason": "test"}, AGENT)
        self.assertTrue(r.get("ok"), r)
        return r["request"]

    def decide(self, req, op="approve", phone=None, sign_as=None, device=None):
        phone = phone or self.phone
        sig = phone.sign(sign_as or op, req)
        return rootd.handle({"op": op, "id": req["id"], "device": device or phone.fingerprint, "signature": sig},
                            AGENT)

    def wait(self, rid):
        return rootd.handle({"op": "wait", "id": rid, "timeout": 10}, AGENT)["request"]

    def test_good_approval_runs_command(self):
        req = self.submit(["echo", "hello world"])
        self.assertEqual(req["argv"][0], "/usr/bin/echo")      # resolved against the fixed root PATH
        self.assertTrue(self.decide(req).get("ok"))
        done = self.wait(req["id"])
        self.assertEqual((done["status"], done["exit"], done["stdout"]), ("done", 0, "hello world\n"))
        events = [json.loads(l)["event"] for l in open(rootd.Cfg.audit())]
        self.assertEqual(events, ["enroll_requested", "enrolled", "submitted", "approved", "finished"])

    def test_good_deny(self):
        req = self.submit()
        self.assertTrue(self.decide(req, "deny").get("ok"))
        self.assertEqual(self.wait(req["id"])["status"], "denied")

    def test_wrong_key(self):
        req = self.submit()
        other = FakePhone()
        r = self.decide(req, phone=other, device=self.phone.fingerprint)
        self.assertIn("signature", r["error"])
        r = self.decide(req, phone=other)                    # unenrolled device id
        self.assertIn("signature", r["error"])
        self.assertEqual(rootd.requests[req["id"]]["status"], "pending")

    def test_wrong_argv(self):
        req = self.submit(["echo", "harmless"])
        shown = dict(req, argv=["/usr/bin/rm", "-rf", "/"])  # phone saw/signed a different argv
        r = rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint,
                          "signature": self.phone.sign("approve", shown)}, AGENT)
        self.assertIn("signature", r["error"])
        # and the reverse: request swapped behind the screen after the phone signed the shown one
        rootd.requests[req["id"]]["argv"] = ["/usr/bin/id"]
        r = self.decide(dict(req))
        self.assertIn("signature", r["error"])

    def test_wrong_decision(self):
        req = self.submit()
        r = self.decide(req, "approve", sign_as="deny")       # a deny signature can't be used to approve
        self.assertIn("signature", r["error"])
        self.assertEqual(rootd.requests[req["id"]]["status"], "pending")

    def test_wrong_nonce_or_expiry(self):
        req = self.submit()
        for k, v in (("nonce", "0" * 32), ("expires", req["expires"] + 60), ("id", "0" * 16)):
            sig = self.phone.sign("approve", dict(req, **{k: v}))
            r = rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint,
                              "signature": sig}, AGENT)
            self.assertIn("signature", r["error"], k)

    def test_replay(self):
        req = self.submit()
        sig = self.phone.sign("approve", req)
        msg = {"op": "approve", "id": req["id"], "device": self.phone.fingerprint, "signature": sig}
        self.assertTrue(rootd.handle(dict(msg), AGENT).get("ok"))
        self.assertIn("request is", rootd.handle(dict(msg), AGENT)["error"])
        self.wait(req["id"])
        self.assertIn("request is", rootd.handle(dict(msg), AGENT)["error"])
        # same argv submitted again gets a fresh id+nonce: the old signature doesn't fit it
        req2 = self.submit()
        msg2 = dict(msg, id=req2["id"])
        self.assertIn("signature", rootd.handle(msg2, AGENT)["error"])

    def test_expired(self):
        req = self.submit()
        r = rootd.requests[req["id"]]
        r["expires"] = int(time.time()) - 1
        res = self.decide(dict(req, expires=r["expires"]))
        self.assertIn("expired", res["error"])
        self.assertEqual(r["status"], "expired")

    def test_revoked_device(self):
        req = self.submit()
        self.assertFalse(rootd.handle({"op": "revoke", "fingerprint": self.phone.fingerprint}, AGENT).get("ok"))
        self.assertTrue(rootd.handle({"op": "revoke", "fingerprint": self.phone.fingerprint}, ADMIN).get("ok"))
        self.assertIn("signature", self.decide(req)["error"])

    def test_bad_signature_encodings(self):
        req = self.submit()
        for sig in ("", "not base64!!", base64.b64encode(b"\x30\x00").decode(), "A" * 500):
            r = rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint, "signature": sig},
                             AGENT)
            self.assertIn("signature", r["error"])

    def test_max_pending(self):
        for _ in range(rootd.MAX_PENDING): self.submit()
        r = rootd.handle({"op": "submit", "argv": ["true"]}, AGENT)
        self.assertIn("too many pending", r["error"])

    def test_submit_validation(self):
        for argv in BAD + [[], "echo hi", [1], ["./relative"], ["no-such-command-xyz"]]:
            r = rootd.handle({"op": "submit", "argv": argv}, AGENT)
            self.assertIn("error", r, repr(argv))
        r = rootd.handle({"op": "submit", "argv": ["true"], "timeout": 99999}, AGENT)
        self.assertEqual(r["request"]["timeout"], rootd.MAX_TIMEOUT)

    def test_reason_sanitized(self):
        r = rootd.handle({"op": "submit", "argv": ["true"], "reason": "a\x1b[2Jb\u202ec\nd"}, AGENT)
        self.assertEqual(r["request"]["reason"], "a [2Jb c d")

    def test_cancel(self):
        req = self.submit()
        self.assertTrue(rootd.handle({"op": "cancel", "id": req["id"]}, AGENT)["ok"])
        self.assertIn("request is", self.decide(req)["error"])

    def test_history_persists(self):
        req = self.submit(["echo", "persist"])
        self.decide(req); self.wait(req["id"])
        rootd.history.clear()
        rootd.load_history()
        h = rootd.handle({"op": "history", "output": True}, AGENT)["history"]
        self.assertEqual((h[0]["id"], h[0]["stdout"]), (req["id"], "persist\n"))

    def test_lost_marker(self):
        rootd.save_json(rootd.running_marker("00000000000000aa"),
                        {"id": "00000000000000aa", "argv": ["/x"], "status": "running", "created": 1, "expires": 2,
                         "reason": "", "nonce": "n", "timeout": 1, "uid": AGENT})
        rootd.load_history()
        self.assertEqual(rootd.history[-1]["status"], "lost")
        self.assertFalse(os.path.exists(rootd.running_marker("00000000000000aa")))


class Enrollment(unittest.TestCase):
    def setUp(self): setup_tmp_cfg(self)
    def tearDown(self): self.tmp.cleanup()

    def test_agent_cannot_confirm_or_revoke(self):
        p = FakePhone()
        r = rootd.handle({"op": "enroll_request", "name": "evil", "pubkey": p.pem}, AGENT)
        self.assertFalse(r["enrolled"])
        for op in ("enroll_confirm", "enroll_pending", "revoke"):
            r = rootd.handle({"op": op, "fingerprint": p.fingerprint}, AGENT)
            self.assertIn("forbidden", r["error"], op)
        self.assertFalse(rootd.handle({"op": "enroll_status", "fingerprint": p.fingerprint}, AGENT)["enrolled"])
        self.assertFalse(os.path.exists(rootd.Cfg.devices()))

    def test_stranger_gets_nothing(self):
        for op in ("ping", "submit", "list", "enroll_request", "approve", "watch"):
            self.assertEqual(rootd.handle({"op": op}, STRANGER), {"error": "forbidden"})

    def test_admin_confirms_typed_code_only(self):
        p = FakePhone()
        rootd.handle({"op": "enroll_request", "name": "Pixel", "pubkey": p.pem}, AGENT)
        pend = rootd.handle({"op": "enroll_pending"}, ADMIN)["pending"]
        self.assertNotIn("fingerprint", pend[0])          # admin types the code from the phone; not shown here
        self.assertIn("error", rootd.handle({"op": "enroll_confirm", "fingerprint": "0000-0000-0000-0000"}, ADMIN))
        r = rootd.handle({"op": "enroll_confirm", "fingerprint": p.fingerprint.lower().replace("-", " ")}, ADMIN)
        self.assertTrue(r["ok"])
        self.assertTrue(rootd.handle({"op": "enroll_status", "fingerprint": p.fingerprint}, AGENT)["enrolled"])
        self.assertEqual(oct(os.stat(rootd.Cfg.devices()).st_mode & 0o777), "0o600")

    def test_name_sanitized(self):
        p = FakePhone()
        rootd.handle({"op": "enroll_request", "name": "\x1b[1A\x1b[2KCode: 0000-0000", "pubkey": p.pem}, AGENT)
        self.assertNotIn("\x1b", rootd.handle({"op": "enroll_pending"}, ADMIN)["pending"][0]["name"])

    def test_flood_evicts_oldest_but_real_phone_can_repropose(self):
        real = FakePhone()
        # Admin proposes a phone; agent proposing up to per-uid cap must not evict admin's entry.
        rootd.handle({"op": "enroll_request", "pubkey": real.pem}, ADMIN)
        for _ in range(rootd.MAX_ENROLL_PER_UID):
            r = rootd.handle({"op": "enroll_request", "pubkey": FakePhone().pem}, AGENT)
            self.assertTrue(r.get("ok"), r)
        # 5th attempt from AGENT evicts AGENT's oldest, leaving ADMIN's entry intact.
        r = rootd.handle({"op": "enroll_request", "pubkey": FakePhone().pem}, AGENT)
        self.assertTrue(r.get("ok"), r)
        self.assertEqual(sum(1 for e in rootd.enroll_pending.values() if e["uid"] == AGENT), rootd.MAX_ENROLL_PER_UID)
        self.assertIn(real.fingerprint, rootd.enroll_pending)
        # 6th attempt within 1 minute hits the enroll_request rate limit (5/min).
        r = rootd.handle({"op": "enroll_request", "pubkey": FakePhone().pem}, AGENT)
        self.assertIn("rate limit", r["error"])
        self.assertTrue(rootd.handle({"op": "enroll_confirm", "fingerprint": real.fingerprint}, ADMIN)["ok"])


class Execution(unittest.TestCase):
    def test_output_cap(self):
        res = rootd.execute(["/bin/sh", "-c", "head -c 3000000 /dev/zero | tr '\\0' x; echo END"], 30)
        self.assertEqual(res["exit"], 0)
        self.assertEqual(res["out_bytes"], 3000004)
        self.assertLess(len(res["stdout"]), rootd.MAX_OUT + 200)
        self.assertTrue(res["stdout"].endswith("END\n"))
        self.assertIn("bytes omitted", res["stdout"])

    def test_timeout_kills_group(self):
        t0 = time.time()
        res = rootd.execute(["/bin/sh", "-c", "sleep 300 & sleep 300; echo never"], 1)
        self.assertTrue(res.get("timed_out"))
        self.assertLess(time.time() - t0, 10)
        self.assertNotIn("never", res["stdout"])

    def test_daemonized_child_doesnt_hang(self):
        t0 = time.time()
        res = rootd.execute(["/bin/sh", "-c", "setsid sleep 30 & echo started"], 60)
        self.assertEqual((res["exit"], res["stdout"]), (0, "started\n"))
        self.assertLess(time.time() - t0, 10)

    def test_clean_env_cwd_stdin(self):
        os.environ["LEAKME"] = "secret"
        res = rootd.execute(["/bin/sh", "-c", "pwd; env | sort; cat; echo eof"], 10)
        self.assertNotIn("LEAKME", res["stdout"])
        self.assertTrue(res["stdout"].startswith("/\n"))
        self.assertIn("PATH=" + rootd.SAFE_PATH, res["stdout"])
        self.assertTrue(res["stdout"].endswith("eof\n"))

    def test_missing_binary(self):
        self.assertEqual(rootd.execute(["/nonexistent/x"], 5)["exit"], 127)


def start_daemon(tmpdir, allowed_uid, admin_uid):
    env = {"ROOTD_TEST_DIR": tmpdir, "ROOTD_TEST_UID": str(allowed_uid), "ROOTD_TEST_ADMIN_UID": str(admin_uid),
           "PATH": "/usr/bin:/bin"}
    p = subprocess.Popen([sys.executable, "-I", os.path.join(SERVER, "rootd.py"), "serve"], env=env,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    sock = os.path.join(tmpdir, "run", "rootd.sock")
    for _ in range(100):
        if os.path.exists(sock): break
        time.sleep(0.05)
    return p, sock


def raw_call(sock, msg, timeout=10):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); s.settimeout(timeout); s.connect(sock)
    s.sendall((json.dumps(msg) + "\n").encode())
    data = b""
    try:
        while not data.endswith(b"\n"):
            c = s.recv(65536)
            if not c: break
            data += c
    except ConnectionResetError:
        pass
    s.close()
    return json.loads(data) if data else None


class Socket(unittest.TestCase):
    def test_peer_uid_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p, sock = start_daemon(d, allowed_uid=os.getuid() + 7777, admin_uid=os.getuid() + 7778)
            try:
                self.assertIsNone(raw_call(sock, {"op": "ping"}))   # connection closed, no reply
                time.sleep(0.2)
                self.assertIn("rejected_peer", open(os.path.join(d, "log", "audit.jsonl")).read())
                self.assertEqual(oct(os.stat(sock).st_mode & 0o777), "0o600")
            finally:
                p.terminate(); p.wait(5)

    def test_agent_over_socket_and_watch(self):
        with tempfile.TemporaryDirectory() as d:
            p, sock = start_daemon(d, allowed_uid=os.getuid(), admin_uid=os.getuid() + 7778)
            try:
                self.assertTrue(raw_call(sock, {"op": "ping"})["ok"])
                self.assertIn("forbidden", raw_call(sock, {"op": "enroll_confirm", "fingerprint": "x"})["error"])
                w = socket.socket(socket.AF_UNIX); w.connect(sock); w.sendall(b'{"op":"watch"}\n')
                f = w.makefile("rb")
                self.assertEqual(json.loads(f.readline())["type"], "hello")
                r = raw_call(sock, {"op": "submit", "argv": ["true"], "reason": "x"})
                ev = json.loads(f.readline())
                self.assertEqual((ev["type"], ev["request"]["id"]), ("request", r["request"]["id"]))
                # a watcher that never reads must not block rootd
                stuck = socket.socket(socket.AF_UNIX); stuck.connect(sock); stuck.sendall(b'{"op":"watch"}\n')
                for _ in range(4): raw_call(sock, {"op": "submit", "argv": ["/bin/echo", "x" * 7000]})
                t0 = time.time()
                self.assertTrue(raw_call(sock, {"op": "list"}, timeout=5)["requests"])
                self.assertLess(time.time() - t0, 2)
                stuck.close(); w.close()
            finally:
                p.terminate(); p.wait(5)

    def test_refuses_same_uid_for_agent_and_admin(self):
        with tempfile.TemporaryDirectory() as d:
            p, _ = start_daemon(d, allowed_uid=os.getuid(), admin_uid=os.getuid())
            out = p.communicate(timeout=10)[0].decode()
            self.assertIn("must not be the admin uid", out)


class Hardening(unittest.TestCase):
    def setUp(self):
        setup_tmp_cfg(self)
        self.phone = FakePhone()
        rootd.handle({"op": "enroll_request", "name": "Pixel", "pubkey": self.phone.pem}, AGENT)
        rootd.handle({"op": "enroll_confirm", "fingerprint": self.phone.fingerprint}, ADMIN)

    def tearDown(self):
        os.environ.pop("ROOTD_ALLOW_V1", None)
        time.sleep(0.05)
        self.tmp.cleanup()

    def test_v2_signs_timeout_reason_and_snap_and_v1_toggle(self):
        req = rootd.handle({"op": "submit", "argv": ["echo", "ok"], "reason": "r1", "timeout": 30}, AGENT)["request"]
        # Tampered timeout or reason fails v2
        for bad in (dict(req, timeout=60), dict(req, reason="r2"), dict(req, snap_sha256="a" * 64)):
            sig = self.phone.sign("approve", bad, version=2)
            r = rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint, "signature": sig}, AGENT)
            self.assertIn("signature", r["error"])
        # v1 accepted by default
        sig_v1 = self.phone.sign("approve", req, version=1)
        r = rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint, "signature": sig_v1}, AGENT)
        self.assertTrue(r.get("ok"), r)
        rootd.handle({"op": "wait", "id": req["id"], "timeout": 10}, AGENT)

        # Turn v1 off via settings.json
        rootd.save_json(rootd.Cfg.settings(), {"allow_v1": False})
        self.assertFalse(rootd.allow_v1())
        req2 = rootd.handle({"op": "submit", "argv": ["echo", "v2only"], "reason": "r2", "timeout": 45}, AGENT)["request"]
        sig2_v1 = self.phone.sign("approve", req2, version=1)
        r = rootd.handle({"op": "approve", "id": req2["id"], "device": self.phone.fingerprint, "signature": sig2_v1}, AGENT)
        self.assertIn("signature", r["error"])
        # v2 still accepted
        sig2_v2 = self.phone.sign("approve", req2, version=2)
        r = rootd.handle({"op": "approve", "id": req2["id"], "device": self.phone.fingerprint, "signature": sig2_v2}, AGENT)
        self.assertTrue(r.get("ok"), r)
        rootd.handle({"op": "wait", "id": req2["id"], "timeout": 10}, AGENT)

        # Env override ROOTD_ALLOW_V1=0 also disables v1
        rootd.save_json(rootd.Cfg.settings(), {"allow_v1": True})
        os.environ["ROOTD_ALLOW_V1"] = "0"
        self.assertFalse(rootd.allow_v1())

    def test_snapshot_blocks_toctou_for_executable_and_interpreter(self):
        import hashlib
        # 1. Direct user-writable executable in tmpdir
        script = os.path.join(self.tmp.name, "run.sh")
        orig = b"#!/bin/sh\necho ORIGINAL\n"
        with open(script, "wb") as f: f.write(orig)
        os.chmod(script, 0o755)
        req = rootd.handle({"op": "submit", "argv": [script], "reason": "snap test"}, AGENT)["request"]
        self.assertEqual(req["snap_sha256"], hashlib.sha256(orig).hexdigest())
        snap_dir = os.path.join(rootd.Cfg.snap_dir(), req["id"])
        self.assertTrue(os.path.isdir(snap_dir))
        # Mutate the original file BEFORE approving (TOCTOU attack)
        with open(script, "wb") as f: f.write(b"#!/bin/sh\necho EVIL\n")
        sig = self.phone.sign("approve", req, version=2)
        self.assertTrue(rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint, "signature": sig}, AGENT).get("ok"))
        done = rootd.handle({"op": "wait", "id": req["id"], "timeout": 10}, AGENT)["request"]
        self.assertEqual(done["stdout"], "ORIGINAL\n")
        self.assertFalse(os.path.exists(snap_dir))

        # 2. Interpreter + user-writable script arg
        py_script = os.path.join(self.tmp.name, "helper.py")
        py_orig = b"print('PY_ORIGINAL')\n"
        with open(py_script, "wb") as f: f.write(py_orig)
        req2 = rootd.handle({"op": "submit", "argv": ["python3", "-I", py_script], "reason": "py snap"}, AGENT)["request"]
        self.assertEqual(req2["snap_sha256"], hashlib.sha256(py_orig).hexdigest())
        with open(py_script, "wb") as f: f.write(b"print('PY_EVIL')\n")
        sig2 = self.phone.sign("approve", req2, version=2)
        self.assertTrue(rootd.handle({"op": "approve", "id": req2["id"], "device": self.phone.fingerprint, "signature": sig2}, AGENT).get("ok"))
        done2 = rootd.handle({"op": "wait", "id": req2["id"], "timeout": 10}, AGENT)["request"]
        self.assertEqual(done2["stdout"], "PY_ORIGINAL\n")
        self.assertFalse(os.path.exists(os.path.join(rootd.Cfg.snap_dir(), req2["id"])))

        # 3. Cancelled request also deletes its snapshot
        req3 = rootd.handle({"op": "submit", "argv": ["sh", script], "reason": "cancel snap"}, AGENT)["request"]
        self.assertIn("snap_sha256", req3)
        self.assertTrue(os.path.isdir(os.path.join(rootd.Cfg.snap_dir(), req3["id"])))
        rootd.handle({"op": "cancel", "id": req3["id"]}, AGENT)
        self.assertFalse(os.path.exists(os.path.join(rootd.Cfg.snap_dir(), req3["id"])))

        # 4. System binary and sh -c are NOT snapshotted
        req4 = rootd.handle({"op": "submit", "argv": ["/bin/sh", "-c", "echo hi"], "reason": "inline"}, AGENT)["request"]
        self.assertNotIn("snap_sha256", req4)

    def test_submit_rate_limits_and_single_event(self):
        events = []
        orig_emit = rootd.emit
        rootd.emit = lambda ev: events.append(ev)
        try:
            for _ in range(rootd.SUBMIT_LIMIT_MIN):
                r = rootd.handle({"op": "submit", "argv": ["true"]}, AGENT)
                self.assertTrue(r.get("ok"), r)
                rootd.handle({"op": "cancel", "id": r["request"]["id"]}, AGENT)
            # Cancelled requests still count: 11th and 12th must be rejected, with only ONE rate_limited event
            for _ in range(3):
                r = rootd.handle({"op": "submit", "argv": ["true"]}, AGENT)
                self.assertIn("rate limit", r["error"])
            rl_events = [e for e in events if e.get("type") == "rate_limited"]
            self.assertEqual(len(rl_events), 1)
            # Hourly cap (30/h): inject 30 timestamps older than 60s but within 3600s
            now = time.time()
            rootd.submit_times[AGENT] = [now - 120] * rootd.SUBMIT_LIMIT_HOUR
            r = rootd.handle({"op": "submit", "argv": ["true"]}, AGENT)
            self.assertIn("rate limit", r["error"])
        finally:
            rootd.emit = orig_emit

    def test_audit_rotation(self):
        orig_max = rootd.AUDIT_MAX_BYTES
        rootd.AUDIT_MAX_BYTES = 256
        try:
            for i in range(40):
                rootd.audit("rot_test", i=i, pad="x" * 60)
            base = rootd.Cfg.audit()
            self.assertTrue(os.path.exists(base))
            self.assertTrue(os.path.exists(base + ".1"))
            self.assertTrue(os.path.exists(base + ".2"))
            self.assertTrue(os.path.exists(base + ".3"))
            self.assertFalse(os.path.exists(base + ".4"))
            for p in (base, base + ".1", base + ".2", base + ".3"):
                self.assertEqual(oct(os.stat(p).st_mode & 0o777), "0o600")
        finally:
            rootd.AUDIT_MAX_BYTES = orig_max

    def test_history_output_ttl_24h(self):
        req = rootd.handle({"op": "submit", "argv": ["echo", "secret-out"], "reason": "ttl"}, AGENT)["request"]
        sig = self.phone.sign("approve", req, version=2)
        rootd.handle({"op": "approve", "id": req["id"], "device": self.phone.fingerprint, "signature": sig}, AGENT)
        rootd.handle({"op": "wait", "id": req["id"], "timeout": 10}, AGENT)
        # Backdate finished timestamp > 24h ago
        rootd.history[-1]["finished"] = time.time() - (rootd.OUTPUT_TTL + 60)
        rootd._rewrite_history()
        # Reloading or querying strips stdout/stderr while keeping metadata
        rootd.history.clear()
        rootd.load_history()
        h = rootd.handle({"op": "history", "output": True}, AGENT)["history"][0]
        self.assertEqual(h["id"], req["id"])
        self.assertEqual(h["exit"], 0)
        self.assertNotIn("stdout", h)
        self.assertNotIn("stderr", h)
        self.assertTrue(h.get("output_expired"))
        # Disk file was also stripped
        disk = [json.loads(l) for l in open(rootd.Cfg.history())]
        self.assertNotIn("stdout", disk[-1])

    def test_max_argv_bytes_8kb(self):
        r = rootd.handle({"op": "submit", "argv": ["/bin/echo", "a" * (8 * 1024 + 10)]}, AGENT)
        self.assertIn("too long", r["error"])

    def test_watch_and_wait_per_uid_caps(self):
        # Test watch per-uid cap
        s1, s2 = socket.socketpair()
        try:
            fake_watchers = []
            with rootd.lock:
                for _ in range(rootd.MAX_WATCH_PER_UID):
                    w = rootd.Watcher(s1, AGENT)
                    rootd.watchers.add(w)
                    fake_watchers.append(w)
            r = rootd.handle({"op": "watch"}, AGENT, conn=s2)
            self.assertIn("too many watchers", r["error"])
            with rootd.lock:
                for w in fake_watchers:
                    rootd.watchers.discard(w)
        finally:
            s1.close(); s2.close()
        # Test wait per-uid cap
        rootd.wait_counts[AGENT] = rootd.MAX_WAIT_PER_UID
        try:
            r = rootd.handle({"op": "wait", "id": "0" * 16, "timeout": 1}, AGENT)
            self.assertIn("too many concurrent waits", r["error"])
        finally:
            rootd.wait_counts[AGENT] = 0


if __name__ == "__main__":
    unittest.main()
