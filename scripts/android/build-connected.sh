#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

for name in NOW_API_BASE_URL NOW_SUPABASE_URL NOW_SUPABASE_PUBLISHABLE_KEY; do
    if [ -z "${!name:-}" ]; then
        printf 'Missing %s. Set the public Android runtime configuration before building the connected app.\n' "$name" >&2
        exit 1
    fi
done
if [[ "$NOW_SUPABASE_PUBLISHABLE_KEY" == sb_secret_* ]]; then
    printf 'Android requires a publishable client key, never a server secret.\n' >&2
    exit 1
fi
./gradlew --no-daemon :app:assembleDebug "$@"
printf '%s\n' 'Connected debug APK: apps/android/build/outputs/apk/debug/app-debug.apk'
