#!/bin/bash
# User-level install (no sudo): rootctl in ~/.local/bin, pc-api user service, tailnet route /pc.
set -euo pipefail
SRC="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
if [ "$(id -u)" -eq 0 ]; then echo "Run as your normal user, not root." >&2; exit 1; fi
install -d "$HOME/.local/bin" "$HOME/.config/systemd/user" "$HOME/.local/share/pc-control"
install -m 0755 "$SRC/rootctl" "$HOME/.local/bin/rootctl"
install -m 0644 "$SRC/pc-api.service" "$HOME/.config/systemd/user/pc-api.service"
systemctl --user daemon-reload
systemctl --user enable pc-api.service >/dev/null
systemctl --user restart pc-api.service
tailscale serve --bg --set-path /pc http://127.0.0.1:1990/pc >/dev/null
echo "pc-api: $(systemctl --user is-active pc-api.service); route: https://$(tailscale status --json | python3 -I -c 'import json,sys;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')/pc"
