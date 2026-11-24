#!/bin/bash
# User-level uninstall: stop/disable rclone-gdrive VFS mount unit and unmount.
set -euo pipefail
if [ "$(id -u)" -eq 0 ]; then
  echo "Run as your normal user, not root." >&2
  exit 1
fi

MOUNT_DIR="$HOME/remote-pc/mounts/gdrive-media"
UNIT_FILE="$HOME/.config/systemd/user/rclone-gdrive.service"

systemctl --user disable --now rclone-gdrive.service 2>/dev/null || true
if mountpoint -q "$MOUNT_DIR" 2>/dev/null; then
  fusermount3 -u "$MOUNT_DIR" 2>/dev/null || fusermount -u "$MOUNT_DIR" 2>/dev/null || true
fi
rm -f "$UNIT_FILE"
systemctl --user daemon-reload
echo "Uninstalled rclone-gdrive.service."
