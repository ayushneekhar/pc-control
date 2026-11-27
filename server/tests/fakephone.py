"""A software stand-in for the phone: EC P-256 key, canonical rootd-v2 payload, SHA256withECDSA (DER).

Usable as a library (tests) or a CLI against a running pc-api:
  python3 -I server/tests/fakephone.py --api http://127.0.0.1:1990 --key /tmp/k.pem enroll
  python3 -I server/tests/fakephone.py --api ... --key ... approve|deny <request-id>
"""
import base64, hashlib, json, os, re, sys, unicodedata, urllib.request

_ID_RE = re.compile(r"^[0-9a-f]{16}$")
_NONCE_RE = re.compile(r"^[0-9a-f]{32}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_BAD_CATS = frozenset({"Cc", "Cf", "Cs", "Zl", "Zp"})


def canonical_argv(argv):
    return json.dumps(list(argv), separators=(",", ":"), ensure_ascii=False)


def argv_hash(argv):
    return hashlib.sha256(canonical_argv(argv).encode("utf-8")).hexdigest()


def argv_looks_safe(argv):
    if not isinstance(argv, (list, tuple)) or len(argv) == 0:
        return False
    for a in argv:
        if not isinstance(a, str):
            return False
        for ch in a:
            if ch in ("\t", "\n"):
                continue
            if unicodedata.category(ch) in _BAD_CATS:
                return False
    return True


def key_fingerprint(spki_der_b64):
    raw = base64.b64decode(spki_der_b64)
    h = hashlib.sha256(raw).hexdigest().upper()
    return "-".join(h[i:i + 4] for i in (0, 4, 8, 12))


def build_payload_v1(decision, req):
    if decision not in ("approve", "deny"):
        raise ValueError("bad decision")
    if not _ID_RE.match(str(req.get("id", ""))) or not _NONCE_RE.match(str(req.get("nonce", ""))):
        raise ValueError("bad request id/nonce")
    expires = req.get("expires")
    if isinstance(expires, bool) or not isinstance(expires, int):
        raise ValueError("bad expiry")
    if not argv_looks_safe(req.get("argv")):
        raise ValueError("argv contains characters that cannot be shown faithfully")
    return f"rootd-v1\n{decision}\n{req['id']}\n{req['nonce']}\n{expires}\n{argv_hash(req['argv'])}"


def build_payload_v2(decision, req):
    if decision not in ("approve", "deny"):
        raise ValueError("bad decision")
    if not _ID_RE.match(str(req.get("id", ""))) or not _NONCE_RE.match(str(req.get("nonce", ""))):
        raise ValueError("bad request id/nonce")
    expires = req.get("expires")
    if isinstance(expires, bool) or not isinstance(expires, int):
        raise ValueError("bad expiry")
    timeout = req.get("timeout", 600)
    if timeout is None:
        timeout = 600
    if isinstance(timeout, bool) or not isinstance(timeout, int) or timeout < 1 or timeout > 3600:
        raise ValueError("bad timeout")
    if not argv_looks_safe(req.get("argv")):
        raise ValueError("argv contains characters that cannot be shown faithfully")
    snap = req.get("snap_sha256") or ""
    if snap != "" and not _SHA256_RE.match(str(snap)):
        raise ValueError("bad snapshot sha256")
    reason_hash = hashlib.sha256((req.get("reason") or "").encode("utf-8")).hexdigest()
    return (
        f"rootd-v2\n{decision}\n{req['id']}\n{req['nonce']}\n{expires}\n"
        f"{timeout}\n{reason_hash}\n{snap}\n{argv_hash(req['argv'])}"
    )


def client_payload(query):
    """Pure-Python phone-side canonicalization helper matching Android RootPayload.kt."""
    payloads = []
    for p in query.get("payloads") or []:
        try:
            fn = build_payload_v1 if p.get("version") == 1 else build_payload_v2
            payloads.append(fn(p["decision"], p["req"]))
        except Exception as e:
            payloads.append({"error": str(e)})
    return {
        "argvs": [{"canon": canonical_argv(a), "hash": argv_hash(a)} for a in (query.get("argvs") or [])],
        "ders": [key_fingerprint(d) for d in (query.get("ders") or [])],
        "payloads": payloads,
        "safe": [argv_looks_safe(a) for a in (query.get("safe") or [])],
    }


class FakePhone:
    def __init__(self, pem_path=None):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        if pem_path and os.path.exists(pem_path):
            self.key = serialization.load_pem_private_key(open(pem_path, "rb").read(), None)
        else:
            self.key = ec.generate_private_key(ec.SECP256R1())
            if pem_path:
                fd = os.open(pem_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "wb") as f:
                    f.write(self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                   serialization.NoEncryption()))
        pub = self.key.public_key()
        self.pem = pub.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        der = pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.der_b64 = base64.b64encode(der).decode()
        self._fp = None

    @property
    def fingerprint(self):
        if self._fp is None:
            self._fp = key_fingerprint(self.der_b64)
        return self._fp

    def payload(self, decision, req, version=2):
        fields = {k: req[k] for k in ("id", "nonce", "expires", "argv")}
        if "timeout" in req: fields["timeout"] = req["timeout"]
        if "reason" in req: fields["reason"] = req["reason"]
        if "snap_sha256" in req: fields["snap_sha256"] = req["snap_sha256"]
        fn = build_payload_v1 if version == 1 else build_payload_v2
        return fn(decision, fields)

    def sign(self, decision, req, version=2):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        sig = self.key.sign(self.payload(decision, req, version=version).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return base64.b64encode(sig).decode()


def _default_local_headers():
    p = os.environ.get("PCAPI_LOCAL_TOKEN", os.path.expanduser("~/.config/pc-control/local-token"))
    try:
        with open(p) as f:
            tok = f.read().strip()
        if tok: return {"Authorization": f"Bearer {tok}"}
    except OSError:
        pass
    return {}


def http(api, method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    hdrs = {"Content-Type": "application/json"}
    if not headers or ("Tailscale-User-Login" not in headers and "Authorization" not in headers):
        hdrs.update(_default_local_headers())
    hdrs.update(headers or {})
    req = urllib.request.Request(api + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main(argv):
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://127.0.0.1:1990")
    ap.add_argument("--key", required=True)
    ap.add_argument("cmd", choices=["enroll", "approve", "deny", "pending"])
    ap.add_argument("id", nargs="?")
    a = ap.parse_args(argv)
    phone = FakePhone(a.key)
    if a.cmd == "enroll":
        code, res = http(a.api, "POST", "/pc/api/enroll", {"name": "fakephone", "pubkey": phone.pem})
        print(code, res, "| phone's own code:", phone.fingerprint)
    elif a.cmd == "pending":
        print(json.dumps(http(a.api, "GET", "/pc/api/root/pending")[1], indent=1, ensure_ascii=False))
    else:
        code, res = http(a.api, "GET", f"/pc/api/root/request/{a.id}")
        req = res["request"]
        print("argv shown:", json.dumps(req["argv"], ensure_ascii=False))
        sig = phone.sign(a.cmd, req)
        print(http(a.api, "POST", f"/pc/api/root/{a.cmd}", {"id": req["id"], "device": phone.fingerprint,
                                                            "signature": sig}))


if __name__ == "__main__":
    main(sys.argv[1:])
