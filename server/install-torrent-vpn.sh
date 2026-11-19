#!/bin/bash
# Route ONLY qBittorrent through Proton VPN (WireGuard), keeping everything else on the normal connection.
#
# Run by Ayush (never by pc-api):  sudo ~/code/pc-control/server/install-torrent-vpn.sh [proton.conf]
#
# It installs wireguard-tools, turns the user's Proton WireGuard conf into /etc/wireguard/proton.conf with:
#   - the DNS line removed (the system resolver must never change),
#   - WireGuard routes in their own table (51820), never the main table,
#   - policy rules that send only 10.2.0.2 / 2a07:b944::2:2 through that table.
# The interface is named proton0, which is what pc-api and qBittorrent bind to.
#
# Idempotent: safe to re-run after editing the user's conf. The PrivateKey is never printed.
set -euo pipefail

IFACE=proton0
TABLE=51820
SRC_IP4=10.2.0.2
SRC_IP6=2a07:b944::2:2
RULE_PRIO=5000
DST=/etc/wireguard/proton.conf

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo: sudo $0"

SRC="${1:-${PROTON_CONF:-}}"
if [ -z "$SRC" ]; then
  USER_HOME=""
  if [ -n "${SUDO_USER:-}" ]; then USER_HOME="$(getent passwd "$SUDO_USER" | cut -d: -f6)"; fi
  [ -n "$USER_HOME" ] || die "cannot find the user's home (run with sudo, or pass the Proton conf path)"
  SRC="$USER_HOME/tools/wireproxy/proton-sg74.conf"
fi
[ -f "$SRC" ] || die "Proton conf not found: $SRC"
[ -r "$SRC" ] || die "cannot read $SRC"

# Check the important pieces without ever echoing the file (it contains the PrivateKey).
grep -q '^[[:space:]]*\[Interface\]' "$SRC" || die "$SRC has no [Interface] section"
grep -q '^[[:space:]]*PrivateKey[[:space:]]*=' "$SRC" || die "$SRC has no PrivateKey"
grep -q '^[[:space:]]*Address[[:space:]]*=.*10\.2\.0\.2' "$SRC" || die "$SRC does not contain Address 10.2.0.2"
grep -q '^[[:space:]]*Endpoint[[:space:]]*=' "$SRC" || die "$SRC has no Endpoint"

if ! command -v wg-quick >/dev/null 2>&1 || ! command -v wg >/dev/null 2>&1; then
  echo "Installing wireguard-tools…"
  DEBIAN_FRONTEND=noninteractive apt-get install -y wireguard-tools >/dev/null
fi
command -v wg-quick >/dev/null 2>&1 || die "wg-quick is still missing after installing wireguard-tools"
command -v wg >/dev/null 2>&1 || die "wg is still missing after installing wireguard-tools"

install -d -m 0700 /etc/wireguard
TMP="$(mktemp /etc/wireguard/.proton.conf.XXXXXX)"
trap 'rm -f "$TMP"' EXIT

# Build the root config: drop anything that would change DNS, routing or hooks, then add ours.
awk '
  /^[[:space:]]*\[Interface\]/ {
    print
    print "Table = 51820"
    print "PostUp = ip rule add from 10.2.0.2 lookup 51820 priority 5000 2>/dev/null || true"
    print "PostUp = ip -6 rule add from 2a07:b944::2:2 lookup 51820 priority 5000 2>/dev/null || true"
    print "PreDown = ip -6 rule del from 2a07:b944::2:2 lookup 51820 priority 5000 2>/dev/null || true"
    print "PreDown = ip rule del from 10.2.0.2 lookup 51820 priority 5000 2>/dev/null || true"
    next
  }
  /^[[:space:]]*(DNS|Table|PostUp|PreDown|SaveConfig)[[:space:]]*=/ { next }
  { print }
' "$SRC" > "$TMP"

chmod 0600 "$TMP"
chown root:root "$TMP"

CHANGED=0
if [ -f "$DST" ] && cmp -s "$TMP" "$DST"; then
  echo "Config unchanged: $DST"
else
  install -m 0600 -o root -g root "$TMP" "$DST"
  CHANGED=1
  echo "Wrote $DST"
fi

systemctl enable wg-quick@$IFACE >/dev/null
if [ "$CHANGED" -eq 1 ] || ! systemctl is-active --quiet wg-quick@$IFACE; then
  systemctl restart wg-quick@$IFACE
  echo "Started wg-quick@$IFACE"
else
  echo "wg-quick@$IFACE already running"
fi

# The main table must not gain a default route from the tunnel; only table 51820 should.
if ip route show table main | grep -q "^default.*$IFACE"; then
  die "$IFACE added a default route to the main table; stopping. Run: sudo systemctl stop wg-quick@$IFACE"
fi

cat <<EOF

Done. qBittorrent is only routed through Proton when the app's Torrent VPN switch is on.
Verify:
  ip rule | grep -E '10\\.2\\.0\\.2|2a07:b944'
  ip route show table $TABLE
  ip -6 route show table $TABLE
  ip route show table main
  curl -4 --interface $IFACE ifconfig.me; echo
  curl -4 ifconfig.me; echo          # should still be your normal public IP
  sudo wg show $IFACE latest-handshakes
EOF
