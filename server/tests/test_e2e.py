"""End to end, unprivileged: rootd (test mode) + pc-api (HTTP/SSE) + rootctl + a software-key fake phone.

Phase A plays Ayush: rootd runs with OUR uid as the admin uid, the phone proposes its key through pc-api and
`rootd-admin enroll` gets the code typed in. Phase B plays the agent: same state dir, rootd restarted with our
uid as the (non-admin) agent uid; rootctl submits, the phone approves/denies over HTTP after the SSE event.
"""
import json, os, socket, subprocess, sys, tempfile, threading, time, unittest, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from fakephone import FakePhone, http  # noqa: E402
from test_rootd import start_daemon, raw_call  # noqa: E402


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


class SSE(threading.Thread):
    def __init__(self, url):
        super().__init__(daemon=True)
        self.url, self.events, self.cond = url, [], threading.Condition()
        self.start()

    def run(self):
        while True:
            try:
                with urllib.request.urlopen(self.url, timeout=60) as r:
                    ev = None
                    for raw in r:
                        line = raw.decode().rstrip("\n")
                        if line.startswith("event: "): ev = line[7:]
                        elif line.startswith("data: ") and ev:
                            with self.cond:
                                self.events.append((ev, json.loads(line[6:]))); self.cond.notify_all()
            except Exception:
                time.sleep(0.5)

    def wait_for(self, pred, timeout=15):
        end = time.time() + timeout
        with self.cond:
            while True:
                for e in self.events:
                    if pred(e): return e
                left = end - time.time()
                if left <= 0: raise AssertionError(f"no matching SSE event; got {[e[0] for e in self.events]}")
                self.cond.wait(left)


class EndToEnd(unittest.TestCase):
    def test_full_flow(self):
        me, other = os.getuid(), os.getuid() + 7777
        with tempfile.TemporaryDirectory() as d:
            sock = os.path.join(d, "run", "rootd.sock")
            port = free_port()
            api = f"http://127.0.0.1:{port}"
            env = {**os.environ, "ROOTD_SOCK": sock, "PCAPI_PORT": str(port), "PCAPI_DATA": os.path.join(d, "data"),
                   "ROOTD_INSTALLED_MARK": os.path.join(d, "nope")}
            procs = []
            try:
                # ---- before rootd exists: pc-api works and reports not_installed
                pcapi = subprocess.Popen([sys.executable, "-I", os.path.join(SERVER, "pcapi.py")], env=env,
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                procs.append(pcapi)
                for _ in range(100):
                    try: http(api, "GET", "/pc/api/info"); break
                    except OSError: time.sleep(0.1)
                self.assertEqual(http(api, "GET", "/pc/api/info")[1]["rootd"]["state"], "not_installed")
                self.assertEqual(http(api, "GET", "/pc/api/root/pending")[0], 503)
                self.assertEqual(http(api, "GET", "/pc/api/info", headers={"Tailscale-User-Login": "x@y"})[0], 403)
                self.assertEqual(http(api, "GET", "/pc/api/info", headers={"X-Forwarded-For": "100.64.0.9"})[0], 403)
                sse = SSE(api + "/pc/api/events")
                sse.wait_for(lambda e: e[0] == "hello" and e[1]["rootd"] == "not_installed")

                # ---- phase A: Ayush (admin uid) enrolls the phone
                rootd_a, _ = start_daemon(d, allowed_uid=other, admin_uid=me); procs.append(rootd_a)
                sse.wait_for(lambda e: e[0] == "rootd" and e[1]["state"] == "ok")
                phone = FakePhone(os.path.join(d, "phone.pem"))
                code, res = http(api, "POST", "/pc/api/enroll", {"name": "Pixel 9 (fake)", "pubkey": phone.pem})
                self.assertEqual(code, 200, res)
                self.assertEqual(res["fingerprint"], phone.fingerprint)   # app checks the server's code == its own
                self.assertFalse(http(api, "GET", f"/pc/api/enroll/status?fingerprint={phone.fingerprint}")[1]["enrolled"])
                admin_env = {"ROOTD_TEST_DIR": d, "ROOTD_TEST_UID": str(other), "ROOTD_TEST_ADMIN_UID": str(me),
                             "PATH": "/usr/bin:/bin"}
                adm = subprocess.run([sys.executable, "-I", os.path.join(SERVER, "rootd.py"), "enroll"], env=admin_env,
                                     input=f"0000-0000-0000-0000\n{phone.fingerprint.lower()}\n", capture_output=True,
                                     text=True, timeout=20)
                self.assertIn("Not enrolled", adm.stdout)
                self.assertIn("Enrolled Pixel 9 (fake)", adm.stdout, adm.stdout + adm.stderr)
                self.assertTrue(http(api, "GET", f"/pc/api/enroll/status?fingerprint={phone.fingerprint}")[1]["enrolled"])
                sse.wait_for(lambda e: e[0] == "enrolled")
                rootd_a.terminate(); rootd_a.wait(5)
                sse.wait_for(lambda e: e[0] == "rootd" and e[1]["state"] == "down")

                # ---- phase B: the agent (our uid, not admin) uses rootctl; phone approves via pc-api
                rootd_b, _ = start_daemon(d, allowed_uid=me, admin_uid=other); procs.append(rootd_b)
                n_ok = len([e for e in sse.events if e[0] == "rootd" and e[1]["state"] == "ok"])
                sse.wait_for(lambda e: len([x for x in sse.events if x[0] == "rootd" and x[1]["state"] == "ok"]) > n_ok)
                self.assertIn("forbidden", raw_call(sock, {"op": "enroll_confirm", "fingerprint": phone.fingerprint})["error"])
                # a second key the agent proposes stays unenrolled and can't approve
                evil = FakePhone()
                http(api, "POST", "/pc/api/enroll", {"name": "evil", "pubkey": evil.pem})

                rootctl = [sys.executable, "-I", os.path.join(SERVER, "rootctl")]

                def run_and_decide(argv, decide, reason="e2e"):
                    p = subprocess.Popen(rootctl + ["run", "--reason", reason, "--", *argv], env=env,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                    procs.append(p)
                    ev = sse.wait_for(lambda e: e[0] == "request" and e[1]["reason"] == reason)
                    rid = ev[1]["id"]
                    code, pend = http(api, "GET", "/pc/api/root/pending")
                    self.assertIn(rid, [r["id"] for r in pend["requests"]])
                    req = http(api, "GET", f"/pc/api/root/request/{rid}")[1]["request"]
                    decide(req)
                    out, err = p.communicate(timeout=30)
                    return rid, p.returncode, out, err

                def approve(req, ph=phone):
                    sig = ph.sign("approve", req)
                    code, res = http(api, "POST", "/pc/api/root/approve",
                                     {"id": req["id"], "device": ph.fingerprint, "signature": sig})
                    self.assertEqual(code, 200, res)

                rid, rc, out, err = run_and_decide(["id", "-u"], approve, "whoami")
                self.assertEqual((rc, out.strip()), (0, str(me)), err)
                self.assertIn("waiting for Ayush", err)
                sse.wait_for(lambda e: e[0] == "update" and e[1]["id"] == rid and e[1]["status"] == "done")

                rid, rc, out, err = run_and_decide(["sh", "-c", "echo out; echo err >&2; exit 7"], approve, "codes")
                self.assertEqual((rc, out, err.splitlines()[-1]), (7, "out\n", "err"))

                tricky = ["/usr/bin/printf", "%s|", "quote\"s", "back\\slash", "ünï😀", "tab\there", "nl\nx"]
                rid, rc, out, err = run_and_decide(tricky, approve, "tricky")
                self.assertEqual((rc, out), (0, "quote\"s|back\\slash|ünï😀|tab\there|nl\nx|"))

                def deny(req):
                    code, res = http(api, "POST", "/pc/api/root/deny",
                                     {"id": req["id"], "device": phone.fingerprint, "signature": phone.sign("deny", req)})
                    self.assertEqual(code, 200, res)
                rid, rc, out, err = run_and_decide(["echo", "never"], deny, "deny-me")
                self.assertEqual((rc, out), (126, ""))
                self.assertIn("denied", err)

                def attacks(req):
                    # wrong key, unenrolled key, swapped argv, decision mismatch: all 403, request stays pending
                    for dev, sig in (
                        (phone.fingerprint, evil.sign("approve", req)),
                        (evil.fingerprint, evil.sign("approve", req)),
                        (phone.fingerprint, phone.sign("approve", dict(req, argv=["/usr/bin/true"]))),
                        (phone.fingerprint, phone.sign("deny", req)),
                    ):
                        code, res = http(api, "POST", "/pc/api/root/approve",
                                         {"id": req["id"], "device": dev, "signature": sig})
                        self.assertEqual(code, 403, res)
                    st = http(api, "GET", f"/pc/api/root/request/{req['id']}")[1]["request"]["status"]
                    self.assertEqual(st, "pending")
                    good = phone.sign("approve", req)
                    body = {"id": req["id"], "device": phone.fingerprint, "signature": good}
                    self.assertEqual(http(api, "POST", "/pc/api/root/approve", body)[0], 200)
                    self.assertEqual(http(api, "POST", "/pc/api/root/approve", body)[0], 409)   # replay
                rid, rc, out, err = run_and_decide(["echo", "after attacks"], attacks, "attacks")
                self.assertEqual((rc, out), (0, "after attacks\n"))

                hist = http(api, "GET", "/pc/api/root/history")[1]["history"]
                self.assertEqual([h["status"] for h in hist[:5]], ["done", "denied", "done", "done", "done"])
                self.assertEqual(hist[3]["exit"], 7)
                self.assertNotIn("stdout", hist[0])
                detail = http(api, "GET", f"/pc/api/root/request/{hist[0]['id']}")[1]["request"]
                self.assertEqual(detail["stdout"], "after attacks\n")

                # rootctl submit / list / cancel / wait
                rid = subprocess.run(rootctl + ["submit", "--", "true"], env=env, capture_output=True, text=True,
                                     timeout=10).stdout.strip()
                self.assertIn(rid, subprocess.run(rootctl + ["list"], env=env, capture_output=True, text=True).stdout)
                self.assertEqual(subprocess.run(rootctl + ["cancel", rid], env=env, capture_output=True).returncode, 0)
                self.assertEqual(subprocess.run(rootctl + ["wait", rid], env=env, capture_output=True).returncode, 125)

                audit = [json.loads(l) for l in open(os.path.join(d, "log", "audit.jsonl"))]
                self.assertEqual(sum(a["event"] == "bad_signature" for a in audit), 4)
                self.assertEqual(sum(a["event"] == "enrolled" for a in audit), 1)
            finally:
                for p in procs:
                    if p.poll() is None: p.terminate()
                for p in procs:
                    try: p.wait(5)
                    except subprocess.TimeoutExpired: p.kill()


if __name__ == "__main__":
    unittest.main()
