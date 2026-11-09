"""The app's OTA/APK manifest validation (src/lib/otaManifest.ts), run in Node: HTML error pages, non-2xx and
malformed JSON must never be reported as an update."""
import os, subprocess, unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class OtaManifest(unittest.TestCase):
    def test_validation(self):
        p = subprocess.run(["node", os.path.join(HERE, "ota_manifest_check.mjs")], capture_output=True, text=True,
                           timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr[-3000:])
        self.assertIn("ota manifest checks ok", p.stdout)


if __name__ == "__main__":
    unittest.main()
