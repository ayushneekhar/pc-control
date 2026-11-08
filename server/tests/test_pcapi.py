"""pc-api route classification (API-only routes hidden server-side, overridable via routes.json)."""
import os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import pcapi  # noqa: E402

H = "ayushlive-hp-pavilion.tail65e4a4.ts.net"
STATUS = {"Web": {
    f"{H}:443": {"Handlers": {
        "/": {"Proxy": "http://127.0.0.1:1984"}, "/cams/": {"Path": "/home/x/camsite"},
        "/detect": {"Proxy": "http://127.0.0.1:1986/detect"}, "/music": {"Proxy": "http://127.0.0.1:1988/music"},
        "/ptz": {"Proxy": "http://127.0.0.1:1985/ptz"}, "/rec": {"Proxy": "http://127.0.0.1:1987/rec"},
        "/vitals": {"Proxy": "http://127.0.0.1:1989/vitals"}, "/pc": {"Proxy": "http://127.0.0.1:1990/pc"},
        "/new-api": {"Proxy": "http://127.0.0.1:2001/new-api"}}},
    f"{H}:5055": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:5055"}}},
    f"{H}:6767": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:6767"}}},
    f"{H}:7878": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:7878"}}},
    f"{H}:8989": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8989"}}},
}}


def visible(overrides=None):
    return {r["name"] for r in pcapi.build_routes(STATUS, overrides or {}) if not r["hidden"]}


class Routes(unittest.TestCase):
    def test_defaults_hide_cat_cam_backends(self):
        self.assertEqual(visible(), {"Cat cams", "Vitals", "Cameras", "Seerr", "Bazarr", "Radarr", "Sonarr", "New Api"})
        hidden = {r["path"]: r["kind"] for r in pcapi.build_routes(STATUS, {}) if r["hidden"]}
        self.assertEqual(hidden, {"/detect": "api", "/music": "api", "/ptz": "api", "/rec": "api"})
        self.assertNotIn("/pc", [r["path"] for r in pcapi.build_routes(STATUS, {})])

    def test_config_hides_new_route_and_unhides_builtin(self):
        v = visible({"/new-api": {"kind": "api"}, "/rec": {"hidden": False}, ":5055": {"hidden": True}})
        self.assertNotIn("New Api", v)
        self.assertNotIn("Seerr", v)
        self.assertIn("Recordings", v)
        self.assertNotIn("New Api", visible({"/new-api": {"hidden": True}}))

    def test_bad_override_ignored(self):
        self.assertIn("Vitals", visible({"/vitals": "nonsense"}))


if __name__ == "__main__":
    unittest.main()
