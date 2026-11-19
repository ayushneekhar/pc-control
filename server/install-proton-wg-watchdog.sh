#!/bin/bash
# Install or update the event-driven Proton WireGuard watchdog
# (script + systemd service/path + tmpfiles.d kick directory).
#
# Run by Ayush (never by pc-api):  sudo ~/code/pc-control/server/install-proton-wg-watchdog.sh
#
# Installs:
#   - /usr/local/sbin/proton-wg-watchdog (0755 root:root)
#   - /etc/systemd/system/proton-wg-watchdog.service (0644 root:root)
#   - /etc/systemd/system/proton-wg-watchdog.path (0644 root:root)
#   - /etc/tmpfiles.d/proton-wg-watchdog.conf (0644 root:root)
# Removes any legacy proton-wg-watchdog.timer, creates /run/proton-wg-kick via
# systemd-tmpfiles, reloads systemd, and enables/starts proton-wg-watchdog.path.
# Idempotent.
set -euo pipefail
umask 022

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo: sudo $0"

SRC="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
SCRIPT_SRC="$SRC/proton-wg-watchdog"
SERVICE_SRC="$SRC/proton-wg-watchdog.service"
PATH_SRC="$SRC/proton-wg-watchdog.path"
TMPFILES_SRC="$SRC/proton-wg-watchdog.tmpfiles.conf"

SCRIPT_DST=/usr/local/sbin/proton-wg-watchdog
SERVICE_DST=/etc/systemd/system/proton-wg-watchdog.service
PATH_DST=/etc/systemd/system/proton-wg-watchdog.path
TMPFILES_DST=/etc/tmpfiles.d/proton-wg-watchdog.conf
OLD_TIMER_DST=/etc/systemd/system/proton-wg-watchdog.timer

for f in "$SCRIPT_SRC" "$SERVICE_SRC" "$PATH_SRC" "$TMPFILES_SRC"; do
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

install -d -o root -g root -m 0755 /usr/local/sbin /etc/systemd/system /etc/tmpfiles.d

# Clean up the legacy polling timer if previously installed.
systemctl disable --now proton-wg-watchdog.timer 2>/dev/null || true
if [ -e "$OLD_TIMER_DST" ] || [ -L "$OLD_TIMER_DST" ]; then
  rm -f "$OLD_TIMER_DST"
  echo "Removed legacy timer: $OLD_TIMER_DST"
fi

install_if_changed 0755 "$SCRIPT_SRC" "$SCRIPT_DST" || true
install_if_changed 0644 "$SERVICE_SRC" "$SERVICE_DST" || true
install_if_changed 0644 "$TMPFILES_SRC" "$TMPFILES_DST" || true

PATH_CHANGED=0
if install_if_changed 0644 "$PATH_SRC" "$PATH_DST"; then
  PATH_CHANGED=1
fi

systemd-tmpfiles --create "$TMPFILES_DST"

WAS_ACTIVE=0
if systemctl is-active --quiet proton-wg-watchdog.path; then
  WAS_ACTIVE=1
fi

systemctl daemon-reload
systemctl enable --now proton-wg-watchdog.path >/dev/null
if [ "$PATH_CHANGED" -eq 1 ] && [ "$WAS_ACTIVE" -eq 1 ]; then
  systemctl restart proton-wg-watchdog.path
fi

echo "Enabled proton-wg-watchdog.path ($(systemctl is-active proton-wg-watchdog.path))."
