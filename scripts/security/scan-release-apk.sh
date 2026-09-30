#!/usr/bin/env bash
set -Eeuo pipefail

APK="${1:-apps/android/build/outputs/apk/release/app-release-unsigned.apk}"

if [ ! -s "$APK" ]; then
  echo "release APK not found: $APK" >&2
  exit 1
fi

APK_ANALYZER="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android/sdk}}/cmdline-tools/latest/bin/apkanalyzer"
if [ ! -x "$APK_ANALYZER" ]; then
  APK_ANALYZER="$(command -v apkanalyzer || true)"
fi
if [ -z "$APK_ANALYZER" ] || [ ! -x "$APK_ANALYZER" ]; then
  echo "apkanalyzer is required for release APK qualification" >&2
  exit 1
fi

debuggable="$("$APK_ANALYZER" manifest debuggable "$APK" | tr -d '\r[:space:]')"
if [ "$debuggable" != "false" ]; then
  echo "release APK is debuggable: $debuggable" >&2
  exit 1
fi

manifest="$("$APK_ANALYZER" manifest print "$APK")"
if ! grep -Eq 'usesCleartextTraffic="false"|android:usesCleartextTraffic="false"' <<<"$manifest"; then
  echo "release APK does not explicitly disable cleartext traffic" >&2
  exit 1
fi

if unzip -Z1 "$APK" | grep -Eqi '\.(pem|key|p12|pfx|jks|keystore)$'; then
  echo "release APK contains private-key or keystore-shaped files" >&2
  exit 1
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
unzip -p "$APK" | strings -a > "$TMP"

if grep -Eqi   'SUPABASE_SERVICE_ROLE_KEY|NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON|NOW_WORKER_TOKEN|NOW_READY_TOKEN|sb_secret_|BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY'   "$TMP"
then
  echo "release APK contains a privileged secret marker" >&2
  exit 1
fi

echo "release APK security surface: clean"
