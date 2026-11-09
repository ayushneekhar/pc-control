#!/bin/bash
# Guard for the cams player's app-start code (CamsPackage -> CamsWebrtc.configure, Application.onCreate):
# libjingle_peerconnection_so isn't loaded yet then, so that code must not construct or call anything in
# org.webrtc (1.4.0 built SoftwareVideoEncoderFactory there: UnsatisfiedLinkError on the phone, no H.265).
# Checks the compiled classes with javap: configure(), CamsPackage and its listener, and the constructors and
# static initializers of every class configure() instantiates. Run after a build (build-apk.sh does).
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
  local bad
  bad=$(grep -E '(new|invoke[a-z]+|getstatic|putstatic) .*org/webrtc/' || true)   # checkcast runs no code
  if [ -n "$bad" ]; then echo "FAIL $1 touches org.webrtc at app start:"; echo "$bad" | sed 's/^/    /'; fail=1
  else echo "ok   $1"; fi
}

check "CamsWebrtc.configure" < <(method "$P.CamsWebrtc" "public final void configure(")
for c in $(cd "$DIR" && ls $P/CamsPackage*.class | sed 's/\.class$//; s#/#.#g'); do
  check "$c" < <("$JAVAP" -c -p -cp "$DIR" "$c")
done
# classes configure() instantiates (ours): their constructors / static init run at app start too
for c in $(method "$P.CamsWebrtc" "public final void configure(" | grep -oE "new +#[0-9]+ +// class $P/[A-Za-z0-9_\$]+" | awk '{print $NF}' | sort -u); do
  cls=${c//\//.}
  check "$cls.<init>" < <(method "$cls" "$cls("; method "$cls" "static {}")
done
# org.webrtc classes may still be *named* (field types, lazy bodies); they just must not run before the library loads
[ $fail -eq 0 ] && echo "startup JNI check passed" || { echo "startup JNI check FAILED" >&2; exit 1; }
