#!/usr/bin/env bash
# Called only by GitHub Actions after the API-21 regression APK is installed.
set -euo pipefail
mkdir -p evidence
original_scale=$(adb shell settings get system font_scale | tr -d '\r')
if [ "$original_scale" = null ] || [ -z "$original_scale" ]; then original_scale=1.0; fi
active_scale=$original_scale
font_changed=false
reload_font_configuration() {
  # API21 Settings uses updatePersistentConfiguration, not a font_scale observer.
  # Restarting reloads the persisted value through ActivityManager.retrieveSettings.
  adb reboot
  timeout 120 adb wait-for-device
  local booted=false
  for attempt in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then booted=true; break; fi
    sleep 1
  done
  if ! $booted; then echo 'API21 did not reboot after changing font scale'; return 1; fi
  adb shell input keyevent 224
  adb shell input keyevent 82
}
restore_font() {
  if $font_changed; then
    adb shell settings put system font_scale "$original_scale"
    reload_font_configuration
  fi
}
trap restore_font EXIT
capture_page() {
  local page=$1 label=$2 scale=$3 self=$4
  adb shell am force-stop com.dycomment.tv.android5
  adb logcat -c
  adb shell am start -W -n "com.dycomment.tv.android5/com.dycomment.tv.$page" \
    --ez legacy_ui_fixture true --ez is_self "$self" --ef expected_font_scale "$scale"
  # API21 lacks logcat --pid. Force-stop above gives each scenario a fresh
  # process; filter threadtime's PID column rather than trusting buffer clearing.
  local process_pid
  process_pid=$(adb shell ps | tr -d '\r' | awk '$NF == "com.dycomment.tv.android5" {print $2}')
  if [[ ! "$process_pid" =~ ^[0-9]+$ ]]; then
    echo "Unable to identify the current $page fixture process" >&2
    exit 1
  fi
  local ready=false
  for attempt in $(seq 1 30); do
    adb logcat -d -v threadtime -s Android5LegacyUiTest:I AndroidRuntime:E \
      | awk -v pid="$process_pid" '$3 == pid' > "evidence/legacy-$label.txt"
    if grep -q 'FAIL\|FATAL EXCEPTION' "evidence/legacy-$label.txt"; then cat "evidence/legacy-$label.txt"; exit 1; fi
    if grep -q "PASS $page populated fixture" "evidence/legacy-$label.txt"; then ready=true; break; fi
    sleep 1
  done
  cat "evidence/legacy-$label.txt"
  $ready
  grep -q "PASS $page populated fixture" "evidence/legacy-$label.txt"
  grep -q "GEOMETRY_OK $page" "evidence/legacy-$label.txt"
  if [ "$page" = ProfileActivity ]; then
    grep -q 'PROFILE_ADAPTIVE_GRID_OK columns=6 initial=18' "evidence/legacy-$label.txt"
    grep -q 'PROFILE_WINDOW_BITMAP_DPAD_OK' "evidence/legacy-$label.txt"
  fi
  if grep -q 'FAIL\|FATAL EXCEPTION' "evidence/legacy-$label.txt"; then exit 1; fi
  adb exec-out screencap -p > "evidence/legacy-$label.png"
  adb shell input keyevent 22
  adb shell input keyevent 20
  sleep 1
  adb exec-out screencap -p > "evidence/legacy-$label-focus.png"
}
for scale in 1.0 1.3; do
  if [ "$scale" != "$active_scale" ]; then
    font_changed=true
    adb shell settings put system font_scale "$scale"
    reload_font_configuration
    active_scale=$scale
  fi
  {
    adb shell settings get system font_scale
    adb shell dumpsys activity | sed -n '/mConfiguration=/p'
  } > "evidence/legacy-font-configuration-$scale.txt"
  suffix=
  if [ "$scale" = 1.3 ]; then suffix=-large; fi
  for page in ProfileActivity FeaturedActivity SearchActivity; do
    capture_page "$page" "$page$suffix" "$scale" false
  done
  capture_page ProfileActivity "self-profile$suffix" "$scale" true
done
adb shell am force-stop com.dycomment.tv.android5
