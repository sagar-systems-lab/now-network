#!/usr/bin/env bash
set -Eeuo pipefail

# Uses only debug fixtures. No credentials, wallet calls, or live account data.
out="${1:?Usage: capture-visuals.sh OUTPUT_DIRECTORY}"
mkdir -p "$out"
printf '%s\n' "Capture in progress: debug fixtures rendered by production composables. Partial captures remain available if a later check fails." > "$out/capture-context.txt"
package=com.sagarsystemslab.nownetwork
activity="$package/.VisualSnapshotActivity"
# The connected-test runner removes the target package when its suite completes.
adb install -r apps/android/build/outputs/apk/debug/app-debug.apk
{
    printf '%s\n' "Checked-out source commit:"
    git rev-parse HEAD
    printf '%s\n' "Debug APK SHA-256:"
    sha256sum apps/android/build/outputs/apk/debug/app-debug.apk
    printf '%s\n' "Android API and system image:"
    adb shell getprop ro.build.version.sdk
    adb shell getprop ro.build.fingerprint
} >> "$out/capture-context.txt"
screens=(now earn earn-empty activity activity-empty state funding funding-review claim capture verification verified payment paid receipt profile account notifications wallet settings appearance notification-preferences privacy-permissions data-storage language-region security connected-sessions account-recovery payout-preferences help-about browse-areas)

adb shell wm size 780x1688
adb shell wm density 320
adb shell settings put system font_scale 1.0
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

capture() {
    local screen="$1" dark="$2" label="$3"
    adb shell am force-stop "$package"
    adb shell am start -W -n "$activity" --es screen "$screen" --ez dark "$dark" > "$out/$label-launch.txt"
    # Allow map styling and initial text/layout to settle after Activity Displayed.
    sleep 3
    adb shell dumpsys activity activities > "$out/focused-activity.txt"
    grep -Fq 'VisualSnapshotActivity' "$out/focused-activity.txt"
    timeout 15 adb shell uiautomator dump /sdcard/now-snapshot.xml >/dev/null
    adb exec-out cat /sdcard/now-snapshot.xml > "$out/$label.xml"
    adb exec-out screencap -p > "$out/$label.png"
    grep -Fq "snapshot-$screen" "$out/$label.xml"
    test "$(wc -c < "$out/$label.png")" -gt 5000
    if [ "$label" = now-light ]; then
        java scripts/android/VisualPreview.java --validate "$out/$label.png"
    fi
}

for theme in light dark; do
    dark=false
    if [ "$theme" = dark ]; then dark=true; fi
    for screen in "${screens[@]}"; do capture "$screen" "$dark" "$screen-$theme"; done
done

adb shell wm size 720x1560
for screen in now earn-empty funding appearance; do capture "$screen" true "$screen-dark-360dp"; done
adb shell wm size 824x1784
for screen in now claim receipt; do capture "$screen" false "$screen-light-412dp"; done
adb shell settings put system font_scale 2.0
for screen in now earn-empty activity claim profile appearance; do capture "$screen" true "$screen-dark-font200"; done
adb shell settings put system font_scale 1.0

# Semantic scrolling bypasses the fixture's tap guard without invoking actions.
adb shell wm size 780x1688
adb install -r apps/android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
scroll_status=0
timeout 180 adb shell am instrument -w -e class "$package.VisualScrollCaptureInstrumentedTest" \
    -e visualCapture true "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/scroll-capture-test.txt" || scroll_status=$?
adb pull "/sdcard/Android/data/$package/files/visual-scroll" "$out/scroll" || true
test "$scroll_status" -eq 0
grep -Fq 'OK (1 test)' "$out/scroll-capture-test.txt"
java scripts/android/VisualPreview.java --validate "$out"

# Runtime evidence of the pending orbit and one-time verified reveal, on fixtures only.
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell am force-stop "$package"
adb shell run-as "$package" rm -f shared_prefs/now_seen_results.xml
timeout 20 adb shell screenrecord --time-limit 10 /sdcard/now-motion.mp4 > "$out/motion-record.txt" 2>&1 &
record_pid=$!
sleep 1
adb shell am start -W -n "$activity" --es screen verification --ez dark true --ez motion true >/dev/null
sleep 3
adb shell am force-stop "$package"
adb shell am start -W -n "$activity" --es screen verified --ez dark true --ez motion true >/dev/null
wait "$record_pid" || { cat "$out/motion-record.txt"; exit 1; }
adb pull /sdcard/now-motion.mp4 "$out/verification-motion.mp4"
adb shell wm size reset
adb shell wm density reset
printf '%s\n' "Capture completed. Debug fixtures; production composables; 390 dp light/dark; 360/412 dp and 200% font probes; selected scroll pages; verification motion recording." >> "$out/capture-context.txt"
