#!/bin/bash
# Build the signed release APK and publish it at /pc/app.apk.
#   scripts/build-apk.sh [--bump]     --bump increments versionCode (and patch version) in app.json first
# Keystore + passwords live OUTSIDE the repo: ~/.config/pc-control/{release.jks,keystore.env}.
set -euo pipefail
cd "$(dirname "$0")/.."
ENVF="$HOME/.config/pc-control/keystore.env"
[ -f "$ENVF" ] || { echo "missing $ENVF (see README: release keystore)" >&2; exit 1; }
set -a; . "$ENVF"; set +a
export JAVA_HOME="$HOME/tools/jdk-21" ANDROID_HOME="$HOME/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME" PATH="$JAVA_HOME/bin:$PATH" NODE_ENV=production

if [ "${1:-}" = "--bump" ]; then
  node -e '
    const fs = require("fs"); const p = "app.json"; const j = JSON.parse(fs.readFileSync(p));
    j.expo.android.versionCode += 1;
    const v = j.expo.version.split(".").map(Number); v[2] += 1; j.expo.version = v.join(".");
    fs.writeFileSync(p, JSON.stringify(j, null, 2) + "\n");
    console.log("version", j.expo.version, "code", j.expo.android.versionCode);'
fi

yarn -s typecheck
yarn -s lint
npx expo prebuild -p android --clean --no-install
# Optional: use an already-cached Gradle when the wrapper's download is unavailable, e.g.
#   GRADLE_DIST_URL=https://services.gradle.org/distributions/gradle-9.8.0-bin.zip scripts/build-apk.sh
if [ -n "${GRADLE_DIST_URL:-}" ]; then
  sed -i "s|^distributionUrl=.*|distributionUrl=${GRADLE_DIST_URL//:/\\:}|" android/gradle/wrapper/gradle-wrapper.properties
fi
sed -i 's|^networkTimeout=.*|networkTimeout=120000|' android/gradle/wrapper/gradle-wrapper.properties
# RN's gradle plugins need a JDK 17 toolchain; point Gradle at a local one instead of auto-downloading.
JDK17="${JDK17:-$HOME/tools/jdk-17-deb/usr/lib/jvm/java-17-openjdk-amd64}"
TOOLCHAIN=()
[ -x "$JDK17/bin/javac" ] && TOOLCHAIN=(-Porg.gradle.java.installations.paths="$JDK17")
(cd android && ./gradlew --no-daemon "${TOOLCHAIN[@]}" -PreactNativeArchitectures=arm64-v8a assembleRelease)

APK=android/app/build/outputs/apk/release/app-release.apk
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
"$BT/apksigner" verify --print-certs "$APK" | grep -i "SHA-256" | head -1
WANT=$(keytool -list -keystore "$PCC_KEYSTORE" -storepass "$PCC_STORE_PASSWORD" -alias "$PCC_KEY_ALIAS" | grep -o 'SHA-256): .*' | cut -d' ' -f2 | tr -d ':' | tr 'A-F' 'a-f')
GOT=$("$BT/apksigner" verify --print-certs "$APK" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')
[ "$WANT" = "$GOT" ] || { echo "APK is not signed with the release key ($GOT != $WANT)" >&2; exit 1; }

OUT="$HOME/.local/share/pc-control"
mkdir -p "$OUT"
cp "$APK" "$OUT/app.apk.new" && mv "$OUT/app.apk.new" "$OUT/app.apk"
node -e '
  const fs = require("fs"), crypto = require("crypto");
  const j = JSON.parse(fs.readFileSync("app.json")).expo; const apk = process.argv[1];
  const buf = fs.readFileSync(apk);
  fs.writeFileSync(process.argv[2], JSON.stringify({ versionCode: j.android.versionCode, versionName: j.version,
    sha256: crypto.createHash("sha256").update(buf).digest("hex"), size: buf.length, built: Math.floor(Date.now() / 1000) }, null, 1));
' "$OUT/app.apk" "$OUT/app.json"
echo "Published $(cat "$OUT/app.json" | tr -d '\n') -> /pc/app.apk"
