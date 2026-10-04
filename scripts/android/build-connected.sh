#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

test -s apps/android/hosted-runtime.properties
./gradlew --no-daemon :app:assembleDebug "$@" -PNOW_RUNTIME=hosted
printf '%s\n' 'Connected debug APK: apps/android/build/outputs/apk/debug/app-debug.apk'
