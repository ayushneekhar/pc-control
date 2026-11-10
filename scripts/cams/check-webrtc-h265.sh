#!/bin/bash
# The cams' H.265 (cat3hd HD) path needs a libwebrtc that can receive H.265 over RTP (built with rtc_use_h265).
# Without it, offering H.265 makes go2rtc send it, and libwebrtc dies natively on the first packets: that was the
# 1.4.1-1.5.0 "Cat cams crashes the app" bug with org.jitsi:webrtc 155. This checks that CamsWebrtc.LIBWEBRTC_H265
# matches what the packaged libjingle_peerconnection_so actually contains (its H.265 depacketizer / parser
# sources). Run after a build (build-apk.sh does).
#   scripts/cams/check-webrtc-h265.sh [apk]
set -euo pipefail
cd "$(dirname "$0")/../.."
APK="${1:-android/app/build/outputs/apk/release/app-release.apk}"
SRC=modules/pc-native/android/src/main/java/expo/modules/pcnative/CamsWebrtc.kt
[ -f "$APK" ] || { echo "no APK at $APK (build first)" >&2; exit 2; }
WANT=$(grep -oE 'const val LIBWEBRTC_H265 = (true|false)' "$SRC" | awk '{print $NF}')
[ -n "$WANT" ] || { echo "LIBWEBRTC_H265 not found in $SRC" >&2; exit 2; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'lib/arm64-v8a/libjingle_peerconnection_so.so' -d "$TMP" \
  || { echo "no arm64 libjingle_peerconnection_so in $APK" >&2; exit 2; }
SO="$TMP/lib/arm64-v8a/libjingle_peerconnection_so.so"
strings -n 6 "$SO" > "$TMP/strings"   # to a file: grep -q on a pipe would SIGPIPE strings under pipefail
# sanity: the H.264 equivalents must be found, or this check can't see anything
grep -q 'video_rtp_depacketizer_h264\.cc' "$TMP/strings" || { echo "can't read libwebrtc's source paths: check is blind" >&2; exit 2; }
if grep -qE 'video_rtp_depacketizer_h265\.cc|h265_bitstream_parser\.cc' "$TMP/strings"; then HAVE=true; else HAVE=false; fi
echo "libwebrtc H.265 RTP: $HAVE, CamsWebrtc.LIBWEBRTC_H265 = $WANT"
if [ "$WANT" = true ] && [ "$HAVE" = false ]; then
  echo "FAIL: LIBWEBRTC_H265 is true but this libwebrtc can't receive H.265 (opening Cat cams would crash)" >&2; exit 1
fi
[ "$WANT" = false ] && [ "$HAVE" = true ] && echo "note: this libwebrtc can receive H.265: LIBWEBRTC_H265 = true would enable cat3hd HD"
echo "webrtc H.265 check passed"
