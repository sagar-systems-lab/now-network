#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

test -s apps/android/hosted-runtime.properties
if grep -q 'android.intent.category.LAUNCHER' apps/android/src/debug/AndroidManifest.xml; then
  printf '%s\n' 'Connected build must not expose a debug launcher.' >&2
  exit 1
fi
./gradlew --no-daemon :app:assembleDebug "$@" -PNOW_RUNTIME=hosted
python3 scripts/android/verify-connected-apk.py apps/android/build/outputs/apk/debug/app-debug.apk
printf '%s\n' 'Connected debug APK: apps/android/build/outputs/apk/debug/app-debug.apk'
