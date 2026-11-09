#!/bin/bash
# Native release (anything that touches android/, modules/*/android, plugins/, app.json, package.json/yarn.lock):
# build + sign locally, publish to GitHub Releases, and publish the matching OTA snapshot for the new build.
#   scripts/release-apk.sh [--bump] ["release notes"]
# Needs: gh (logged in), HEAD pushed to origin, ~/.config/pc-control/{keystore.env,ota-signing-key.pem}.
# The release gets pc-control.apk + version.json (the app's update check reads releases/latest/download/version.json).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(node -p 'require("./app.json").expo.extra.github.repo')
OTA_TAG=$(node -p 'require("./app.json").expo.extra.github.otaTag')
BUMP=(); [ "${1:-}" = "--bump" ] && { BUMP=(--bump); shift; }
NOTES="${1:-}"

COUNTER=scripts/ota/ota-bundle-version.json
OTA=$(( $(node -p "require('./$COUNTER').android") + 1 ))
export EXPO_PUBLIC_BUNDLED_OTA_VERSION=$OTA
scripts/build-apk.sh "${BUMP[@]}"
[ ${#BUMP[@]} -eq 0 ] || { echo "app.json was bumped: commit + push it, then run this again without --bump" >&2; exit 1; }

VERSION=$(node -p 'require("./app.json").expo.version'); CODE=$(node -p 'require("./app.json").expo.android.versionCode')
TAG="v$VERSION"
git fetch -q origin
git branch -r --contains HEAD | grep -q . || { echo "HEAD is not pushed to origin; push first" >&2; exit 1; }
gh release view "$TAG" -R "$REPO" >/dev/null 2>&1 && { echo "release $TAG already exists (bump the version)" >&2; exit 1; }

OUT=.artifacts/release; rm -rf "$OUT"; mkdir -p "$OUT"
cp android/app/build/outputs/apk/release/app-release.apk "$OUT/pc-control.apk"
node -e '
  const fs = require("fs"), crypto = require("crypto"); const j = require("./app.json").expo;
  const buf = fs.readFileSync(process.argv[1]);
  fs.writeFileSync(process.argv[2], JSON.stringify({ versionName: j.version, versionCode: j.android.versionCode,
    otaVersion: Number(process.argv[3]), sha256: crypto.createHash("sha256").update(buf).digest("hex"),
    size: buf.length, built: Math.floor(Date.now() / 1000) }, null, 1) + "\n");
' "$OUT/pc-control.apk" "$OUT/version.json" "$OTA"
gh release create "$TAG" -R "$REPO" --target "$(git rev-parse HEAD)" --latest \
  --title "PC Control $VERSION ($CODE)" --notes "${NOTES:-Native release $VERSION ($CODE).}

APK: \`pc-control.apk\` (signed with the release key; SHA-256 in \`version.json\`). Embedded JS = OTA $OTA." \
  "$OUT/pc-control.apk" "$OUT/version.json"
echo "{\"android\": $OTA}" | node -e 'process.stdout.write(JSON.stringify(JSON.parse(require("fs").readFileSync(0)), null, 2) + "\n")' > "$COUNTER"

# Matching OTA for the new build (same version as the embedded JS, so it isn't offered; it just replaces the
# snapshot that targeted the previous build), like Apex's APK release workflow.
scripts/ota-publish.sh --version "$OTA" --skip-checks "Matches the $VERSION ($CODE) APK"
git commit -q -m "chore(release): $VERSION ($CODE), OTA $OTA" -- "$COUNTER" && echo "Committed $COUNTER (push it)"
echo "Released $TAG: https://github.com/$REPO/releases/tag/$TAG"
