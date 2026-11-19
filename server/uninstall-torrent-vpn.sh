#!/bin/bash
# Remove the Proton torrent VPN set up by install-torrent-vpn.sh.
#   sudo ~/code/pc-control/server/uninstall-torrent-vpn.sh
# wireguard-tools stays installed: it may be useful elsewhere. No secret is printed.
set -euo pipefail

IFACE=proton0
TABLE=51820
DST=/etc/wireguard/${IFACE}.conf
STRAY=/etc/wireguard/proton.conf

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo: sudo $0"

systemctl disable --now wg-quick@$IFACE 2>/dev/null || true
ip rule del from 10.2.0.2 lookup $TABLE priority 5000 2>/dev/null || true
ip -6 rule del from 2a07:b944::2:2 lookup $TABLE priority 5000 2>/dev/null || true
rm -f "$DST" "$STRAY"

echo "Removed wg-quick@$IFACE and $DST."
echo "The main routing table was never changed; check: ip route show table main"
