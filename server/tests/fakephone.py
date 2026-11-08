"""A software stand-in for the phone: EC P-256 key, payload built by the app's own JS, SHA256withECDSA (DER).

Usable as a library (tests) or a CLI against a running pc-api:
  python3 -I server/tests/fakephone.py --api http://127.0.0.1:1990 --key /tmp/k.pem enroll
  python3 -I server/tests/fakephone.py --api ... --key ... approve|deny <request-id>
"""
import base64, json, os, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def js(query):
    p = subprocess.run(["node", os.path.join(HERE, "js_bridge.mjs")], input=json.dumps(query), capture_output=True,
                       text=True, timeout=30)
    if p.returncode: raise RuntimeError(p.stderr)
    return json.loads(p.stdout)


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
        # computed the way the app does (src/lib/rootPayload.ts keyFingerprint over the SPKI DER)
        if self._fp is None: self._fp = js({"ders": [self.der_b64]})["ders"][0]
        return self._fp

    def payload(self, decision, req):
        out = js({"payloads": [{"decision": decision, "req": {k: req[k] for k in ("id", "nonce", "expires", "argv")}}]})
        p = out["payloads"][0]
        if isinstance(p, dict): raise ValueError(p["error"])
        return p

    def sign(self, decision, req):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        sig = self.key.sign(self.payload(decision, req).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return base64.b64encode(sig).decode()


def http(api, method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(api + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
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
