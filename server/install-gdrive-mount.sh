#!/bin/bash
# User-level install (no sudo): rclone-gdrive VFS mount systemd unit for Jellyfin streaming.
set -euo pipefail
SRC="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
if [ "$(id -u)" -eq 0 ]; then
  echo "Run as your normal user, not root." >&2
  exit 1
fi

RCLONE="$HOME/.local/bin/rclone"
if [ ! -x "$RCLONE" ]; then
  RCLONE="/usr/bin/rclone"
fi
if [ ! -x "$RCLONE" ]; then
  echo "rclone not found at ~/.local/bin/rclone or /usr/bin/rclone." >&2
  exit 1
fi

MOUNT_DIR="$HOME/remote-pc/mounts/gdrive-media"
CACHE_DIR="$HOME/.cache/rclone-vfs"
UNIT_DIR="$HOME/.config/systemd/user"

install -d "$UNIT_DIR" "$MOUNT_DIR" "$CACHE_DIR"
install -m 0644 "$SRC/rclone-gdrive.service" "$UNIT_DIR/rclone-gdrive.service"
systemctl --user daemon-reload

if "$RCLONE" listremotes 2>/dev/null | grep -qx 'gdrive:'; then
  systemctl --user enable rclone-gdrive.service >/dev/null
  systemctl --user restart rclone-gdrive.service
  echo "rclone-gdrive: $(systemctl --user is-active rclone-gdrive.service) (mount: $MOUNT_DIR)"
else
  echo "Installed $UNIT_DIR/rclone-gdrive.service and created $MOUNT_DIR."
  echo "Remote 'gdrive:' is not configured in rclone yet."
  echo "Run '$RCLONE config' to add 'gdrive', then run:"
  echo "  systemctl --user enable --now rclone-gdrive.service"
fi
