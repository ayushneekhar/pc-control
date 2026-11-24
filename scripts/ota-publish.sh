#!/bin/bash
# JS-only update over the air: build a signed bundle for the exact native build in app.json and publish it to the
# rolling GitHub release "ota-android" (assets bundle.zip + ota.version.json + optional bsdiff patch-*.zip).
#   scripts/ota-publish.sh ["release notes"]          next OTA version (scripts/ota/ota-bundle-version.json + 1)
#   scripts/ota-publish.sh --force ["notes"]          even if native files changed since the APK's release tag
# Refuses when native code changed since the installed build's tag (v<version>): that needs scripts/release-apk.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(node -p 'require("./app.json").expo.extra.github.repo')
OTA_TAG=$(node -p 'require("./app.json").expo.extra.github.otaTag')
COUNTER=scripts/ota/ota-bundle-version.json
FORCE=0; CHECKS=1; OTA=""; IS_JS_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=1; shift ;;
    --skip-checks) CHECKS=0; shift ;;          # used by release-apk.sh (it already ran them)
    --version) OTA="$2"; shift 2 ;;           # used by release-apk.sh
    *) break ;;
  esac
done
NOTES="${1:-}"
VERSION=$(node -p 'require("./app.json").expo.version'); CODE=$(node -p 'require("./app.json").expo.android.versionCode')

if [ -z "$OTA" ]; then
  IS_JS_ONLY=1
  OTA=$(( $(node -p "require('./$COUNTER').android") + 1 ))
  if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || git fetch -q origin "refs/tags/v$VERSION:refs/tags/v$VERSION" 2>/dev/null; then
    if [ $FORCE = 0 ]; then
      node scripts/ota/check-native-changes.mjs --base "v$VERSION"
    fi
  else
    echo "No release tag v$VERSION: release the APK first (scripts/release-apk.sh)" >&2; exit 1
  fi
fi

if [ $CHECKS = 1 ]; then
  yarn -s typecheck & pid_tc=$!
  yarn -s lint & pid_lint=$!
  tc_rc=0; wait "$pid_tc" || tc_rc=$?
  lint_rc=0; wait "$pid_lint" || lint_rc=$?
  [ "$tc_rc" -eq 0 ] && [ "$lint_rc" -eq 0 ]
fi

# For JS-only OTAs, fetch the previous bundle.zip from ota-android so we can build a tiny bsdiff patch.
PREV_DIR=""
PATCH_ARGS=()
cleanup() { [ -n "$PREV_DIR" ] && rm -rf "$PREV_DIR"; }
trap cleanup EXIT
if [ "$IS_JS_ONLY" = 1 ]; then
  PREV_DIR=$(mktemp -d)
  if gh release download "$OTA_TAG" -R "$REPO" -p ota.version.json -p bundle.zip -D "$PREV_DIR" --clobber 2>/dev/null; then
    PREV_OTA=$(node -e '
      const fs = require("fs");
      const m = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      const target = process.argv[2], nextOta = Number(process.argv[3]);
      const targets = m?.targetVersions?.android;
      const ver = String(m?.version ?? "").trim();
      if (Array.isArray(targets) && targets.includes(target) && /^\d+$/.test(ver) && Number(ver) < nextOta) {
        process.stdout.write(ver);
      }
    ' "$PREV_DIR/ota.version.json" "$VERSION+$CODE" "$OTA" 2>/dev/null || true)
    if [ -n "$PREV_OTA" ] && [ -f "$PREV_DIR/bundle.zip" ]; then
      PATCH_ARGS=(--previous-zip "$PREV_DIR/bundle.zip" --previous-version "$PREV_OTA")
    fi
  fi
fi

node scripts/ota/build-nitro-ota.mjs --ota-version "$OTA" "${PATCH_ARGS[@]}" ${NOTES:+--release-notes "$NOTES"}
D=.artifacts/nitro-ota/android
PATCH_FILE=$(find "$D" -maxdepth 1 -type f -name 'patch-*-to-*.zip' | head -n 1 || true)

# Also mirror locally for pc-api (/pc/api/ota/*) so phones on Tailscale fetch over the tailnet first (patch/zip first, manifest last).
MIRROR_DIR="${PCAPI_OTA_DIR:-$HOME/.local/share/pc-control/ota}"
mkdir -p "$MIRROR_DIR"
if [ -n "$PATCH_FILE" ]; then
  PATCH_NAME=$(basename "$PATCH_FILE")
  cp "$PATCH_FILE" "$MIRROR_DIR/$PATCH_NAME.tmp" && mv -f "$MIRROR_DIR/$PATCH_NAME.tmp" "$MIRROR_DIR/$PATCH_NAME"
  # Keep only the latest 3 patch zips in the local mirror.
  ls -1t "$MIRROR_DIR"/patch-*-to-*.zip 2>/dev/null | tail -n +4 | xargs -r rm -f
fi
cp "$D/bundle.zip" "$MIRROR_DIR/bundle.zip.tmp" && mv -f "$MIRROR_DIR/bundle.zip.tmp" "$MIRROR_DIR/bundle.zip"
cp "$D/ota.version.json" "$MIRROR_DIR/ota.version.json.tmp" && mv -f "$MIRROR_DIR/ota.version.json.tmp" "$MIRROR_DIR/ota.version.json"

gh release view "$OTA_TAG" -R "$REPO" >/dev/null 2>&1 || gh release create "$OTA_TAG" -R "$REPO" --prerelease --latest=false \
  --target "$(git rev-parse origin/HEAD 2>/dev/null || git rev-parse HEAD)" --title "OTA (Android JS bundle)" --notes "Rolling OTA snapshot."
# Patch + zip first, manifest last: a phone that sees the new manifest always gets the new assets.
if [ -n "$PATCH_FILE" ]; then
  gh release upload "$OTA_TAG" -R "$REPO" --clobber "$PATCH_FILE"
fi
gh release upload "$OTA_TAG" -R "$REPO" --clobber "$D/bundle.zip"
gh release upload "$OTA_TAG" -R "$REPO" --clobber "$D/ota.version.json"

# Keep only the latest 3 patch-*.zip assets on the rolling GitHub release.
OLD_PATCHES=$(gh release view "$OTA_TAG" -R "$REPO" --json assets --jq '.assets[].name' 2>/dev/null | node -e '
  const names = require("fs").readFileSync(0, "utf8").trim().split("\n").filter(Boolean);
  const patches = names
    .map((name) => {
      const m = name.match(/^patch-(\d+)-to-(\d+)\.zip$/);
      return m ? { name, from: Number(m[1]), to: Number(m[2]) } : null;
    })
    .filter(Boolean)
    .sort((a, b) => (b.to - a.to) || (b.from - a.from));
  for (const p of patches.slice(3)) console.log(p.name);
' || true)
for asset in $OLD_PATCHES; do
  gh release delete-asset "$OTA_TAG" "$asset" -R "$REPO" -y >/dev/null 2>&1 || true
done

gh release edit "$OTA_TAG" -R "$REPO" --notes "OTA $OTA for $VERSION+$CODE ($(date -u +%Y-%m-%dT%H:%MZ)).${NOTES:+ $NOTES}

Signed with the laptop's OTA key; the app only installs it on build $VERSION+$CODE." >/dev/null
if [ $CHECKS = 1 ]; then
  echo "{\"android\": $OTA}" | node -e 'process.stdout.write(JSON.stringify(JSON.parse(require("fs").readFileSync(0)), null, 2) + "\n")' > "$COUNTER"
  git commit -q -m "chore(ota): android OTA $OTA for $VERSION+$CODE" -- "$COUNTER" && echo "Committed $COUNTER (push it)"
fi
echo "Published OTA $OTA for $VERSION+$CODE -> https://github.com/$REPO/releases/download/$OTA_TAG/ota.version.json"
