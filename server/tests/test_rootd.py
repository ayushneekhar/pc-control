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
        req = {"id": "0123456789abcdef", "nonce": "f" * 32, "expires": 1794128000, "argv": TRICKY[5]}
        for d in ("approve", "deny"):
            self.assertEqual(js({"payloads": [{"decision": d, "req": req}]})["payloads"][0],
                             rootd.payload(req, d).decode())

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
        rootd.handle({"op": "enroll_request", "pubkey": real.pem}, AGENT)
        for _ in range(rootd.MAX_ENROLL_PENDING + 2):
            rootd.handle({"op": "enroll_request", "pubkey": FakePhone().pem}, AGENT)
        self.assertEqual(len(rootd.enroll_pending), rootd.MAX_ENROLL_PENDING)
        rootd.handle({"op": "enroll_request", "pubkey": real.pem}, AGENT)
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
                for _ in range(4): raw_call(sock, {"op": "submit", "argv": ["/bin/echo", "x" * 30000]})
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


if __name__ == "__main__":
    unittest.main()
