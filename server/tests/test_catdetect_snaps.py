"""Unit & HTTP tests for /pc/api/catdetect/snaps endpoints."""
import atexit
import datetime
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402

_TEST_TOK_DIR = tempfile.mkdtemp(prefix="pcapi-snaps-tok-")
atexit.register(shutil.rmtree, _TEST_TOK_DIR, True)


class CatdetectSnapsUnitTests(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="catdetect-snaps-")
        self.addCleanup(shutil.rmtree, self.tmpdir, True)

    def _write_snap(self, name, payload=b"\xff\xd8\xff\xe0FAKEJPEG"):
        p = os.path.join(self.tmpdir, name)
        with open(p, "wb") as f:
            f.write(payload)
        return p

    def test_list_newest_first_counts_and_extra_cam(self):
        self._write_snap("cat-20261125-090000.jpg", b"\xff\xd8\xff" + b"a" * 10)
        self._write_snap("cat2-20261126-001024.jpg", b"\xff\xd8\xff" + b"b" * 20)
        self._write_snap("cat3-20261125-235826.jpg", b"\xff\xd8\xff" + b"c" * 30)
        self._write_snap("cat-20261126-084318.jpg", b"\xff\xd8\xff" + b"d" * 40)
        self._write_snap("porch_1-20261125-120000.jpg", b"\xff\xd8\xff" + b"e" * 5)
        # Ignored non-matching entries
        self._write_snap("readme.txt", b"hello")
        self._write_snap("cat-bad.jpg", b"nope")
        os.mkdir(os.path.join(self.tmpdir, "cat-20261126-090000.jpg"))

        code, data = pcapi.list_catdetect_snaps(snaps_dir=self.tmpdir)
        self.assertEqual(code, 200)
        self.assertEqual(data["total"], 5)
        self.assertEqual(
            data["counts"],
            {"all": 5, "cat": 2, "cat2": 1, "cat3": 1, "porch_1": 1},
        )
        names = [s["filename"] for s in data["snaps"]]
        self.assertEqual(
            names,
            [
                "cat-20261126-084318.jpg",
                "cat2-20261126-001024.jpg",
                "cat3-20261125-235826.jpg",
                "porch_1-20261125-120000.jpg",
                "cat-20261125-090000.jpg",
            ],
        )
        first = data["snaps"][0]
        expected_ts = int(datetime.datetime(2026, 11, 26, 8, 43, 18).timestamp() * 1000)
        self.assertEqual(first["cam"], "cat")
        self.assertEqual(first["ts_ms"], expected_ts)
        self.assertEqual(first["size_bytes"], 43)
        self.assertEqual(first["url"], "/pc/api/catdetect/snaps/cat-20261126-084318.jpg")

    def test_cam_filter_and_limit(self):
        self._write_snap("cat-20261125-090000.jpg")
        self._write_snap("cat-20261126-084318.jpg")
        self._write_snap("cat2-20261126-001024.jpg")
        self._write_snap("cat3-20261125-235826.jpg")

        code, data = pcapi.list_catdetect_snaps(cam="cat", snaps_dir=self.tmpdir)
        self.assertEqual(code, 200)
        self.assertEqual(data["total"], 2)
        self.assertEqual([s["filename"] for s in data["snaps"]], [
            "cat-20261126-084318.jpg",
            "cat-20261125-090000.jpg",
        ])
        # counts still reports totals across all cameras
        self.assertEqual(data["counts"], {"all": 4, "cat": 2, "cat2": 1, "cat3": 1})

        code, limited = pcapi.list_catdetect_snaps(limit="2", snaps_dir=self.tmpdir)
        self.assertEqual(code, 200)
        self.assertEqual(limited["total"], 4)
        self.assertEqual(len(limited["snaps"]), 2)

        # Invalid params
        self.assertEqual(pcapi.list_catdetect_snaps(cam="../etc", snaps_dir=self.tmpdir)[0], 400)
        self.assertEqual(pcapi.list_catdetect_snaps(limit="notanint", snaps_dir=self.tmpdir)[0], 400)

    def test_missing_directory_returns_empty(self):
        code, data = pcapi.list_catdetect_snaps(snaps_dir=os.path.join(self.tmpdir, "does-not-exist"))
        self.assertEqual(code, 200)
        self.assertEqual(data, {"snaps": [], "total": 0, "counts": {"all": 0, "cat": 0, "cat2": 0, "cat3": 0}})

    def test_read_snap_validation_and_symlink_escape(self):
        payload = b"\xff\xd8\xff\xe0TESTJPG"
        self._write_snap("cat2-20261126-001024.jpg", payload)

        code, res = pcapi.read_catdetect_snap("cat2-20261126-001024.jpg", snaps_dir=self.tmpdir)
        self.assertEqual(code, 200)
        self.assertEqual(res, payload)

        # Missing valid file -> 404
        code, _ = pcapi.read_catdetect_snap("cat3-20261126-001024.jpg", snaps_dir=self.tmpdir)
        self.assertEqual(code, 404)

        # Traversal and invalid filenames -> 400
        for bad in (
            "../cat2-20261126-001024.jpg",
            "../../etc/passwd",
            "..%2F..%2Fetc%2Fpasswd",
            "sub/cat2-20261126-001024.jpg",
            "cat2-20261126-001024.png",
            "CAT2-20261126-001024.jpg",
            "cat2-20261126-001024.jpg%00",
            "",
        ):
            code, _ = pcapi.read_catdetect_snap(bad, snaps_dir=self.tmpdir)
            self.assertEqual(code, 400, f"expected 400 for {bad!r}")

        # Symlink escaping snaps_dir -> 400
        outside = tempfile.NamedTemporaryFile(delete=False)
        outside.write(b"secret")
        outside.close()
        self.addCleanup(lambda: os.path.exists(outside.name) and os.unlink(outside.name))
        os.symlink(outside.name, os.path.join(self.tmpdir, "cat-20261126-111111.jpg"))
        code, _ = pcapi.read_catdetect_snap("cat-20261126-111111.jpg", snaps_dir=self.tmpdir)
        self.assertEqual(code, 400)


class CatdetectSnapsHttpTests(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="catdetect-http-")
        self.addCleanup(shutil.rmtree, self.tmpdir, True)
        self.jpeg = b"\xff\xd8\xff\xe0HTTP_JPEG_BYTES\xff\xd9"
        with open(os.path.join(self.tmpdir, "cat-20261126-084318.jpg"), "wb") as f:
            f.write(self.jpeg)
        with open(os.path.join(self.tmpdir, "cat3-20261125-235826.jpg"), "wb") as f:
            f.write(b"\xff\xd8\xff\xe0OLDER\xff\xd9")

        self._patches = [
            mock.patch.object(pcapi, "LOCAL_TOKEN_FILE", os.path.join(_TEST_TOK_DIR, "local-token")),
            mock.patch.object(pcapi, "CATDETECT_SNAPS_DIR", self.tmpdir),
            mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", True),
        ]
        for p in self._patches:
            p.start()
            self.addCleanup(p.stop)

        self.srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        self.port = self.srv.server_address[1]
        self.th = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.th.start()
        self.addCleanup(self.srv.server_close)
        self.addCleanup(self.srv.shutdown)

    def _get(self, path):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            headers={"Authorization": f"Bearer {pcapi.local_token()}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, dict(r.headers.items()), r.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, dict(e.headers.items()), e.read()

    def test_http_list_filter_serve_and_traversal(self):
        status, _, body = self._get("/pc/api/catdetect/snaps")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["total"], 2)
        self.assertEqual([s["filename"] for s in data["snaps"]], [
            "cat-20261126-084318.jpg",
            "cat3-20261125-235826.jpg",
        ])

        status, _, body = self._get("/pc/api/catdetect/snaps?cam=cat3&limit=10")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["total"], 1)
        self.assertEqual(data["snaps"][0]["cam"], "cat3")

        status, headers, body = self._get("/pc/api/catdetect/snaps/cat-20261126-084318.jpg")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Type"), "image/jpeg")
        self.assertEqual(headers.get("Cache-Control"), "public, max-age=86400")
        self.assertEqual(body, self.jpeg)

        # Path traversal attempts rejected with 400
        for bad_path in (
            "/pc/api/catdetect/snaps/..%2F..%2Fetc%2Fpasswd",
            "/pc/api/catdetect/snaps/../../etc/passwd",
            "/pc/api/catdetect/snaps/sub/cat-20261126-084318.jpg",
            "/pc/api/catdetect/snaps/not-valid.jpg",
        ):
            status, _, _ = self._get(bad_path)
            self.assertEqual(status, 400, f"expected 400 for {bad_path}")

        # Missing valid file -> 404
        status, _, _ = self._get("/pc/api/catdetect/snaps/cat2-20261126-001024.jpg")
        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
