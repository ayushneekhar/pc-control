"""Unit tests for server/proton-wg-watchdog and its install/unit files."""
import json
import os
import stat
import subprocess
import tempfile
import unittest

SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WATCHDOG = os.path.join(SERVER_DIR, "proton-wg-watchdog")
FAKE_PEER_KEY = "FAKE_PROTON_PEER_PUBLIC_KEY_DO_NOT_LOG_987654321="
FAKE_PEER_KEY_2 = "SECOND_FAKE_PEER_KEY_NEVER_PRINT_1234567890="


def _write_exe(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    os.chmod(path, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)


FAKE_WG_SCRIPT = """#!/usr/bin/env python3
import json, os, sys

with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as f:
    f.write(json.dumps({"cmd": "wg", "argv": sys.argv[1:]}) + "\\n")

if sys.argv[1:] != ["show", os.environ.get("EXPECT_IFACE", "proton0"), "latest-handshakes"]:
    sys.stderr.write(f"unexpected wg args: {sys.argv[1:]}\\n")
    sys.exit(2)

rc = int(os.environ.get("FAKE_WG_RC", "0"))
out = os.environ.get("FAKE_WG_OUTPUT", "")
if out:
    sys.stdout.write(out)
    if not out.endswith("\\n"):
        sys.stdout.write("\\n")
sys.exit(rc)
"""

FAKE_SYSTEMCTL_SCRIPT = """#!/usr/bin/env python3
import json, os, sys

argv = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as f:
    f.write(json.dumps({"cmd": "systemctl", "argv": argv}) + "\\n")

unit = os.environ.get("EXPECT_UNIT", "wg-quick@proton0")
active = os.environ.get("FAKE_ACTIVE", "1") == "1"

if argv == ["is-active", "--quiet", unit]:
    sys.exit(0 if active else 3)

if argv == ["show", "--timestamp=unix", "-p", "ActiveEnterTimestamp", "--value", unit]:
    val = os.environ.get("FAKE_ACTIVE_ENTER", "")
    sys.stdout.write(val + "\\n")
    sys.exit(0)

if argv == ["restart", unit]:
    sys.exit(int(os.environ.get("FAKE_RESTART_RC", "0")))

sys.stderr.write(f"unexpected systemctl args: {argv}\\n")
sys.exit(2)
"""

FAKE_LOGGER_SCRIPT = """#!/usr/bin/env python3
import json, os, sys

with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as f:
    f.write(json.dumps({"cmd": "logger", "argv": sys.argv[1:]}) + "\\n")
sys.exit(0)
"""


class ProtonWgWatchdogTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self.bin_dir = os.path.join(self.root, "bin")
        self.state_dir = os.path.join(self.root, "state")
        self.log_path = os.path.join(self.root, "calls.jsonl")
        os.makedirs(self.bin_dir, exist_ok=True)

        self.wg_bin = os.path.join(self.bin_dir, "wg")
        self.systemctl_bin = os.path.join(self.bin_dir, "systemctl")
        self.logger_bin = os.path.join(self.bin_dir, "logger")

        _write_exe(self.wg_bin, FAKE_WG_SCRIPT)
        _write_exe(self.systemctl_bin, FAKE_SYSTEMCTL_SCRIPT)
        _write_exe(self.logger_bin, FAKE_LOGGER_SCRIPT)

    def tearDown(self):
        self._tmp.cleanup()

    def _clear_log(self):
        if os.path.exists(self.log_path):
            os.remove(self.log_path)

    def _read_calls(self):
        if not os.path.exists(self.log_path):
            return []
        with open(self.log_path, "r", encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()]

    def _run_watchdog(
        self,
        *,
        now=1_700_000_000,
        active=True,
        wg_output="",
        wg_rc=0,
        active_enter="",
        restart_rc=0,
        use_path_override=False,
    ):
        self._clear_log()
        env = os.environ.copy()
        env.update(
            {
                "STATE_DIR": self.state_dir,
                "NOW_EPOCH": str(now),
                "FAKE_LOG": self.log_path,
                "FAKE_ACTIVE": "1" if active else "0",
                "FAKE_WG_OUTPUT": wg_output,
                "FAKE_WG_RC": str(wg_rc),
                "FAKE_ACTIVE_ENTER": active_enter,
                "FAKE_RESTART_RC": str(restart_rc),
            }
        )
        if use_path_override:
            env["PATH"] = self.bin_dir + os.pathsep + env.get("PATH", "")
            for k in ("WG_BIN", "SYSTEMCTL_BIN", "LOGGER_BIN"):
                env.pop(k, None)
        else:
            env["WG_BIN"] = self.wg_bin
            env["SYSTEMCTL_BIN"] = self.systemctl_bin
            env["LOGGER_BIN"] = self.logger_bin

        proc = subprocess.run(
            [WATCHDOG],
            env=env,
            capture_output=True,
            text=True,
            timeout=15,
        )
        calls = self._read_calls()

        # WireGuard keys must never appear anywhere in stdout, stderr, or logger calls.
        combined = proc.stdout + "\n" + proc.stderr + "\n" + json.dumps(calls)
        self.assertNotIn(FAKE_PEER_KEY, combined)
        self.assertNotIn(FAKE_PEER_KEY_2, combined)

        return proc, calls

    def _restarts(self, calls):
        return [c for c in calls if c["cmd"] == "systemctl" and c["argv"][:1] == ["restart"]]

    def _logs(self, calls):
        return [c for c in calls if c["cmd"] == "logger"]

    def test_fresh_handshake_no_restart(self):
        now = 1_700_000_500
        # 45s old -> fresh
        proc, calls = self._run_watchdog(now=now, wg_output=f"{FAKE_PEER_KEY}\t{now - 45}")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(self._logs(calls), [])
        self.assertFalse(os.path.exists(os.path.join(self.state_dir, "last-restart")))

        # Boundary: exactly 180s old -> still not older than 180s
        proc, calls = self._run_watchdog(now=now, wg_output=f"{FAKE_PEER_KEY}\t{now - 180}")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(self._logs(calls), [])

    def test_stale_handshake_restarts_and_logs(self):
        now = 1_700_000_500
        proc, calls = self._run_watchdog(now=now, wg_output=f"{FAKE_PEER_KEY}\t{now - 181}")
        self.assertEqual(proc.returncode, 0, proc.stderr)

        restarts = self._restarts(calls)
        self.assertEqual(len(restarts), 1)
        self.assertEqual(restarts[0]["argv"], ["restart", "wg-quick@proton0"])

        logs = self._logs(calls)
        self.assertEqual(len(logs), 1)
        self.assertEqual(logs[0]["argv"][:2], ["-t", "proton-wg-watchdog"])
        self.assertIn("restarting wg-quick@proton0", logs[0]["argv"][-1])

        state_file = os.path.join(self.state_dir, "last-restart")
        with open(state_file, "r", encoding="utf-8") as f:
            self.assertEqual(f.read().strip(), str(now))

    def test_rate_limit_blocks_restarts_within_5_minutes(self):
        t0 = 1_700_001_000
        # First stale run restarts
        proc, calls = self._run_watchdog(now=t0, wg_output=f"{FAKE_PEER_KEY}\t{t0 - 300}")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(len(self._restarts(calls)), 1)

        # 60s later -> rate-limited
        proc, calls = self._run_watchdog(now=t0 + 60, wg_output=f"{FAKE_PEER_KEY}\t{t0 - 300}")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(len(self._logs(calls)), 1)
        self.assertIn("rate-limited", self._logs(calls)[0]["argv"][-1])

        # 299s later -> still rate-limited
        proc, calls = self._run_watchdog(now=t0 + 299, wg_output=f"{FAKE_PEER_KEY}\t{t0 - 300}")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])

        # 300s (5 minutes) later -> allowed again
        proc, calls = self._run_watchdog(now=t0 + 300, wg_output=f"{FAKE_PEER_KEY}\t{t0 - 300}")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(len(self._restarts(calls)), 1)

    def test_unit_inactive_does_nothing(self):
        now = 1_700_002_000
        proc, calls = self._run_watchdog(
            now=now,
            active=False,
            wg_output=f"{FAKE_PEER_KEY}\t{now - 999}",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(self._logs(calls), [])
        self.assertEqual([c for c in calls if c["cmd"] == "wg"], [])
        self.assertFalse(os.path.exists(self.state_dir))

    def test_never_handshaked_recently_started_does_nothing(self):
        now = 1_700_003_000
        # Active for 60s -> do nothing
        proc, calls = self._run_watchdog(
            now=now,
            wg_output=f"{FAKE_PEER_KEY}\t0",
            active_enter=f"@{now - 60}",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(self._logs(calls), [])

        # Boundary: active for exactly 180s -> still do nothing
        proc, calls = self._run_watchdog(
            now=now,
            wg_output=f"{FAKE_PEER_KEY}\t0",
            active_enter=f"@{now - 180}",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(self._logs(calls), [])

    def test_never_handshaked_active_over_180s_restarts(self):
        now = 1_700_004_000
        proc, calls = self._run_watchdog(
            now=now,
            wg_output=f"{FAKE_PEER_KEY}\t0",
            active_enter=f"@{now - 181}",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(len(self._restarts(calls)), 1)
        self.assertEqual(len(self._logs(calls)), 1)
        self.assertIn("no handshake", self._logs(calls)[0]["argv"][-1])

    def test_multiple_peers_uses_newest_handshake(self):
        now = 1_700_005_000
        # One peer stale, one peer fresh -> newest is fresh -> no restart
        wg_out = f"{FAKE_PEER_KEY}\t{now - 500}\n{FAKE_PEER_KEY_2}\t{now - 30}\n"
        proc, calls = self._run_watchdog(now=now, wg_output=wg_out)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])

        # Both peers stale -> restart
        wg_out = f"{FAKE_PEER_KEY}\t0\n{FAKE_PEER_KEY_2}\t{now - 250}\n"
        proc, calls = self._run_watchdog(now=now, wg_output=wg_out)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(len(self._restarts(calls)), 1)

    def test_path_override_works_without_bin_env_vars(self):
        now = 1_700_006_000
        proc, calls = self._run_watchdog(
            now=now,
            wg_output=f"{FAKE_PEER_KEY}\t{now - 400}",
            use_path_override=True,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(len(self._restarts(calls)), 1)

    def test_wg_failure_logs_and_exits_nonzero_without_restarting(self):
        now = 1_700_007_000
        proc, calls = self._run_watchdog(now=now, wg_rc=1)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(self._restarts(calls), [])
        self.assertEqual(len(self._logs(calls)), 1)

    def test_restart_failure_still_rate_limits_next_attempt(self):
        now = 1_700_008_000
        proc, calls = self._run_watchdog(
            now=now,
            wg_output=f"{FAKE_PEER_KEY}\t{now - 400}",
            restart_rc=1,
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(len(self._restarts(calls)), 1)
        self.assertEqual(len(self._logs(calls)), 2)

        # Immediate retry within 5 min must be rate-limited
        proc, calls = self._run_watchdog(
            now=now + 60,
            wg_output=f"{FAKE_PEER_KEY}\t{now - 400}",
            restart_rc=0,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self._restarts(calls), [])

    def test_scripts_syntax_and_systemd_units(self):
        for name in (
            "proton-wg-watchdog",
            "install-proton-wg-watchdog.sh",
            "install-torrent-vpn.sh",
            "uninstall-torrent-vpn.sh",
        ):
            path = os.path.join(SERVER_DIR, name)
            self.assertTrue(os.access(path, os.X_OK), f"{name} should be executable")
            p = subprocess.run(["bash", "-n", path], capture_output=True, text=True, timeout=10)
            self.assertEqual(p.returncode, 0, f"bash -n failed for {name}: {p.stderr}")

        with open(os.path.join(SERVER_DIR, "proton-wg-watchdog.service"), "r", encoding="utf-8") as f:
            svc = f.read()
        for line in (
            "Type=oneshot",
            "ExecStart=/usr/local/sbin/proton-wg-watchdog",
            "RuntimeDirectory=proton-wg-watchdog",
            "RuntimeDirectoryPreserve=yes",
            "ProtectHome=yes",
            "PrivateTmp=yes",
            "NoNewPrivileges=yes",
        ):
            self.assertIn(line, svc)

        with open(os.path.join(SERVER_DIR, "proton-wg-watchdog.timer"), "r", encoding="utf-8") as f:
            timer = f.read()
        for line in (
            "OnBootSec=2min",
            "OnUnitActiveSec=60s",
            "WantedBy=timers.target",
        ):
            self.assertIn(line, timer)


if __name__ == "__main__":
    unittest.main()
