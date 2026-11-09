#!/bin/bash
# JS-only update over the air: build a signed bundle for the exact native build in app.json and publish it to the
# rolling GitHub release "ota-android" (assets bundle.zip + ota.version.json; the app reads them anonymously).
#   scripts/ota-publish.sh ["release notes"]          next OTA version (scripts/ota/ota-bundle-version.json + 1)
#   scripts/ota-publish.sh --force ["notes"]          even if native files changed since the APK's release tag
# Refuses when native code changed since the installed build's tag (v<version>): that needs scripts/release-apk.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(node -p 'require("./app.json").expo.extra.github.repo')
OTA_TAG=$(node -p 'require("./app.json").expo.extra.github.otaTag')
COUNTER=scripts/ota/ota-bundle-version.json
FORCE=0; CHECKS=1; OTA=""
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
  OTA=$(( $(node -p "require('./$COUNTER').android") + 1 ))
  NATIVE=(android modules/pc-native/android modules/pc-native/expo-module.config.json plugins app.json app.config.js package.json yarn.lock)
  if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || git fetch -q origin "refs/tags/v$VERSION:refs/tags/v$VERSION" 2>/dev/null; then
    CHANGED=$( (git diff --name-only "v$VERSION" -- "${NATIVE[@]}"; git ls-files --others --exclude-standard -- "${NATIVE[@]}") | sort -u)
    if [ -n "$CHANGED" ] && [ $FORCE = 0 ]; then
      echo "Native files changed since v$VERSION, so this needs a new APK (scripts/release-apk.sh --bump):" >&2
      echo "$CHANGED" | sed 's/^/  /' >&2; exit 1
    fi
  else
    echo "No release tag v$VERSION: release the APK first (scripts/release-apk.sh)" >&2; exit 1
  fi
fi
[ $CHECKS = 0 ] || { yarn -s typecheck; yarn -s lint; }

node scripts/ota/build-nitro-ota.mjs --ota-version "$OTA" ${NOTES:+--release-notes "$NOTES"}
D=.artifacts/nitro-ota/android
gh release view "$OTA_TAG" -R "$REPO" >/dev/null 2>&1 || gh release create "$OTA_TAG" -R "$REPO" --prerelease --latest=false \
  --target "$(git rev-parse origin/HEAD 2>/dev/null || git rev-parse HEAD)" --title "OTA (Android JS bundle)" --notes "Rolling OTA snapshot."
# Zip first, manifest last: a phone that sees the new manifest always gets the new zip. The version the phone stores
# is read from inside the zip, so a phone that grabs the zip early just gets the newer bundle a bit sooner.
gh release upload "$OTA_TAG" -R "$REPO" --clobber "$D/bundle.zip"
gh release upload "$OTA_TAG" -R "$REPO" --clobber "$D/ota.version.json"
gh release edit "$OTA_TAG" -R "$REPO" --notes "OTA $OTA for $VERSION+$CODE ($(date -u +%Y-%m-%dT%H:%MZ)).${NOTES:+ $NOTES}

Signed with the laptop's OTA key; the app only installs it on build $VERSION+$CODE." >/dev/null
if [ $CHECKS = 1 ]; then
  echo "{\"android\": $OTA}" | node -e 'process.stdout.write(JSON.stringify(JSON.parse(require("fs").readFileSync(0)), null, 2) + "\n")' > "$COUNTER"
  git commit -q -m "chore(ota): android OTA $OTA for $VERSION+$CODE" -- "$COUNTER" && echo "Committed $COUNTER (push it)"
fi
echo "Published OTA $OTA for $VERSION+$CODE -> https://github.com/$REPO/releases/download/$OTA_TAG/ota.version.json"
