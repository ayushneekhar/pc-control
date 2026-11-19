#!/bin/bash
# Install or update the Proton WireGuard watchdog (script + systemd timer).
#
# Run by Ayush (never by pc-api):  sudo ~/code/pc-control/server/install-proton-wg-watchdog.sh
#
# Installs:
#   - /usr/local/sbin/proton-wg-watchdog (0755 root:root)
#   - /etc/systemd/system/proton-wg-watchdog.service (0644 root:root)
#   - /etc/systemd/system/proton-wg-watchdog.timer (0644 root:root)
# Reloads systemd and enables/starts proton-wg-watchdog.timer. Idempotent.
set -euo pipefail
umask 022

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo: sudo $0"

SRC="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
SCRIPT_SRC="$SRC/proton-wg-watchdog"
SERVICE_SRC="$SRC/proton-wg-watchdog.service"
TIMER_SRC="$SRC/proton-wg-watchdog.timer"

SCRIPT_DST=/usr/local/sbin/proton-wg-watchdog
SERVICE_DST=/etc/systemd/system/proton-wg-watchdog.service
TIMER_DST=/etc/systemd/system/proton-wg-watchdog.timer

for f in "$SCRIPT_SRC" "$SERVICE_SRC" "$TIMER_SRC"; do
  [ -f "$f" ] || die "missing file: $f"
done

bash -n "$SCRIPT_SRC" || die "syntax check failed: $SCRIPT_SRC"

install_if_changed() {
  local mode="$1" src="$2" dst="$3"
  local tmp="${dst}.new"
  if [ -f "$dst" ] && [ ! -L "$dst" ] && cmp -s "$src" "$dst"; then
    chmod "$mode" "$dst"
    chown root:root "$dst"
    echo "Unchanged: $dst"
    return 1
  fi
  rm -f "$tmp"
  install -o root -g root -m "$mode" "$src" "$tmp"
  mv -f "$tmp" "$dst"
  echo "Installed: $dst"
  return 0
}

install -d -o root -g root -m 0755 /usr/local/sbin /etc/systemd/system

install_if_changed 0755 "$SCRIPT_SRC" "$SCRIPT_DST" || true
install_if_changed 0644 "$SERVICE_SRC" "$SERVICE_DST" || true

TIMER_CHANGED=0
if install_if_changed 0644 "$TIMER_SRC" "$TIMER_DST"; then
  TIMER_CHANGED=1
fi

WAS_ACTIVE=0
if systemctl is-active --quiet proton-wg-watchdog.timer; then
  WAS_ACTIVE=1
fi

systemctl daemon-reload
systemctl enable --now proton-wg-watchdog.timer >/dev/null
if [ "$TIMER_CHANGED" -eq 1 ] && [ "$WAS_ACTIVE" -eq 1 ]; then
  systemctl restart proton-wg-watchdog.timer
fi

echo "Enabled proton-wg-watchdog.timer ($(systemctl is-active proton-wg-watchdog.timer))."
