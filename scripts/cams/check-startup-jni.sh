#!/bin/bash
# Guard for the app-start code in pc-native (CamsPackage -> CrashReporter.install + CamsWebrtc.onAppStart, run
# from Application.onCreate): libjingle_peerconnection_so isn't loaded yet at that point, so that code must not
# construct or call anything in org.webrtc (1.4.0 built SoftwareVideoEncoderFactory there and got an
# UnsatisfiedLinkError on the phone). Checks the compiled classes with javap: CamsPackage and its listener, all of
# CrashReporter, and the CamsWebrtc methods app start reaches. A method that isn't found fails the check, so a
# rename can't make it pass with nothing checked. Run after a build (build-apk.sh does).
#   scripts/cams/check-startup-jni.sh [classes-dir]
set -euo pipefail
cd "$(dirname "$0")/../.."
DIR="${1:-modules/pc-native/android/build/tmp/kotlin-classes/release}"
JAVAP="${JAVA_HOME:-$HOME/tools/jdk-21}/bin/javap"
P=expo/modules/pcnative
[ -f "$DIR/$P/CamsWebrtc.class" ] || { echo "no compiled CamsWebrtc in $DIR (build first)" >&2; exit 2; }

# bytecode of one method (by javap's header line prefix) of a class
method() { "$JAVAP" -c -p -cp "$DIR" "$1" | awk -v m="$2" '
  index($0, m) && /\(/ { on = 1; next } on && /^  [a-z].*\(.*\);?$/ { on = 0 } on && /^$/ { on = 0 } on { print }'; }

fail=0
check() {   # $1 = label, stdin = bytecode
  local code bad
  code=$(cat)
  if [ -z "$code" ]; then echo "FAIL $1: not found in the compiled classes (renamed? update this script)"; fail=1; return; fi
  bad=$(grep -E '(new|invoke[a-z]+|getstatic|putstatic) .*org/webrtc/' <<<"$code" || true)   # checkcast runs no code
  if [ -n "$bad" ]; then echo "FAIL $1 touches org.webrtc at app start:"; echo "$bad" | sed 's/^/    /'; fail=1
  else echo "ok   $1"; fi
}

for c in $(cd "$DIR" && ls $P/CamsPackage*.class $P/CrashReporter*.class | sed 's/\.class$//; s#/#.#g'); do
  check "$c" < <("$JAVAP" -c -p -cp "$DIR" "$c")
done
for sig in "void onAppStart(" "boolean isOpening(" "void markOpening(" "java.util.Map<java.lang.String, java.lang.Boolean> settings("; do
  check "CamsWebrtc.${sig##* }" < <(method "$P.CamsWebrtc" "public final $sig")
done
check "CamsWebrtc.<clinit>" < <("$JAVAP" -c -p -cp "$DIR" "$P.CamsWebrtc" | awk '/^  static \{\};$/ { on = 1; next } on && /^$/ { on = 0 } on { print }')
# org.webrtc classes may still be *named* (field types, lazy bodies); they just must not run before the library loads
[ $fail -eq 0 ] && echo "startup JNI check passed" || { echo "startup JNI check FAILED" >&2; exit 1; }
