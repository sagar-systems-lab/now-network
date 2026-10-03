#!/usr/bin/env bash
set -Eeuo pipefail

# Uses only debug fixtures. No credentials, wallet calls, or live account data.
out="${1:?Usage: capture-visuals.sh OUTPUT_DIRECTORY}"
mkdir -p "$out"
package=com.sagarsystemslab.nownetwork
activity="$package/.VisualSnapshotActivity"
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
    adb shell am start -W -n "$activity" --es screen "$screen" --ez dark "$dark" >/dev/null
    # Allow map styling and initial text/layout to settle after Activity Displayed.
    sleep 3
    adb shell dumpsys activity activities > "$out/focused-activity.txt"
    grep -Fq 'VisualSnapshotActivity' "$out/focused-activity.txt"
    timeout 15 adb shell uiautomator dump /sdcard/now-snapshot.xml >/dev/null
    adb exec-out cat /sdcard/now-snapshot.xml > "$out/$label.xml"
    grep -Fq "snapshot-$screen" "$out/$label.xml"
    adb exec-out screencap -p > "$out/$label.png"
    test "$(wc -c < "$out/$label.png")" -gt 5000
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
for screen in now claim appearance; do capture "$screen" true "$screen-dark-font200"; done
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset
printf '%s\n' "Debug fixtures; production composables; 390 dp light/dark; 360/412 dp and 200% font probes." > "$out/capture-context.txt"
