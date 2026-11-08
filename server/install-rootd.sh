#!/bin/bash
# Install or upgrade rootd. Run by Ayush:  sudo ~/code/pc-control/server/install-rootd.sh
# Copies rootd into root-owned paths (never runs it from the user-writable repo), installs the systemd
# unit, (re)starts it, then offers phone enrollment. Safe to run again to upgrade.
# Touches only: /usr/local/lib/rootd, /usr/local/sbin/rootd-admin, /etc/systemd/system/rootd.service,
# /etc/rootd, /var/lib/rootd, /var/log/rootd, /run/rootd. No sudoers/PAM/ssh/firewall/Tailscale changes.
set -euo pipefail
umask 022

if [ "$(id -u)" -ne 0 ]; then echo "Run with sudo: sudo $0" >&2; exit 1; fi
SRC="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
TARGET_USER="${ROOTD_USER:-${SUDO_USER:-}}"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = root ]; then
  echo "Can't tell which user the agent runs as. Run via sudo from that user, or set ROOTD_USER=name." >&2
  exit 1
fi
id -u "$TARGET_USER" >/dev/null
/usr/bin/python3 -I -c 'import cryptography.hazmat.primitives.asymmetric.ec' 2>/dev/null || {
  echo "python3-cryptography is missing: apt install python3-cryptography" >&2; exit 1; }
/usr/bin/python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$SRC/rootd.py"   # syntax check, writes nothing

echo "Installing rootd for user '$TARGET_USER' from $SRC"
echo "  rootd.py sha256: $(sha256sum "$SRC/rootd.py" | cut -d' ' -f1)"

install -d -o root -g root -m 0755 /usr/local/lib/rootd
rm -f /usr/local/lib/rootd/rootd.py.new
install -o root -g root -m 0644 "$SRC/rootd.py" /usr/local/lib/rootd/rootd.py.new
mv -f /usr/local/lib/rootd/rootd.py.new /usr/local/lib/rootd/rootd.py       # replaces a symlink too
rm -rf /usr/local/lib/rootd/__pycache__

cat > /usr/local/sbin/rootd-admin.new <<'WRAP'
#!/bin/sh
# rootd admin: sudo rootd-admin enroll | devices | revoke <code> | status
exec /usr/bin/python3 -I /usr/local/lib/rootd/rootd.py "$@"
WRAP
chown root:root /usr/local/sbin/rootd-admin.new; chmod 0755 /usr/local/sbin/rootd-admin.new
mv -f /usr/local/sbin/rootd-admin.new /usr/local/sbin/rootd-admin

install -d -o root -g root -m 0700 /etc/rootd /var/lib/rootd /var/log/rootd
/usr/bin/python3 -I - "$TARGET_USER" <<'PY'
import json, os, sys
p = "/etc/rootd/settings.json"
try: s = json.load(open(p))
except FileNotFoundError: s = {}
s["user"] = sys.argv[1]
fd = os.open(p + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f: json.dump(s, f, indent=1)
os.replace(p + ".tmp", p)
PY

install -o root -g root -m 0644 "$SRC/rootd.service" /etc/systemd/system/rootd.service
systemctl daemon-reload
systemctl enable rootd.service >/dev/null
systemctl restart rootd.service

for _ in $(seq 50); do [ -S /run/rootd/rootd.sock ] && break; sleep 0.1; done
if ! [ -S /run/rootd/rootd.sock ]; then
  echo "rootd did not start:" >&2; systemctl --no-pager status rootd >&2 || true; exit 1
fi
echo "rootd is running ($(systemctl is-active rootd))."

ENROLLED="$(/usr/local/sbin/rootd-admin devices | grep -c -- '-' || true)"
if [ "${1:-}" = "--no-enroll" ]; then exit 0; fi
if [ "$ENROLLED" -gt 0 ] && [ "${1:-}" != "--enroll" ]; then
  echo "$ENROLLED phone(s) already enrolled; skipping enrollment (use --enroll to add another)."
  exit 0
fi
echo
echo "Phone enrollment: open pc-control on your phone > Settings > Root approvals > Set up,"
echo "approve with your fingerprint, and read the code it shows."
/usr/local/sbin/rootd-admin enroll || echo "Not enrolled yet. Later: sudo rootd-admin enroll"
