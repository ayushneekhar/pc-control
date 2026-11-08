#!/bin/bash
# Remove rootd. Run by Ayush:  sudo ~/code/pc-control/server/uninstall-rootd.sh [--purge]
# --purge also deletes enrolled phones (/etc/rootd), history (/var/lib/rootd) and the audit log (/var/log/rootd).
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then echo "Run with sudo: sudo $0" >&2; exit 1; fi
systemctl disable --now rootd.service 2>/dev/null || true
rm -f /etc/systemd/system/rootd.service /usr/local/sbin/rootd-admin
rm -rf /usr/local/lib/rootd /run/rootd
systemctl daemon-reload
if [ "${1:-}" = "--purge" ]; then
  rm -rf /etc/rootd /var/lib/rootd /var/log/rootd
  echo "rootd removed, including enrolled phones, history and audit log."
else
  echo "rootd removed. Kept /etc/rootd (enrolled phones) and /var/log/rootd/audit.jsonl; --purge removes them."
fi
