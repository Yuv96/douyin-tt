#!/usr/bin/env bash
# GitHub only: real API21 foreground service, sockets, rotation and Python interoperability.
set -euo pipefail
mkdir -p evidence
adb shell am force-stop com.dycomment.tv.android5
adb logcat -c
adb shell am start -W -n com.dycomment.tv.android5/com.dycomment.tv.LanSyncSelfTestActivity
passed=false
for attempt in $(seq 1 90); do
  sleep 2
  adb logcat -d -s Android5LanSyncTest:I AndroidRuntime:E > evidence/lan-sync-test.txt
  if grep -q 'FAIL\|FATAL EXCEPTION' evidence/lan-sync-test.txt; then cat evidence/lan-sync-test.txt; exit 1; fi
  if grep -q 'PASS API21_LAN_SYNC_PERSISTENT_ROTATION' evidence/lan-sync-test.txt; then passed=true; break; fi
done
cat evidence/lan-sync-test.txt
grep -q 'PASS API21_BROWSER_COMMENTS_READ_ISOLATION' evidence/lan-sync-test.txt
$passed
adb shell am force-stop com.dycomment.tv.android5
adb logcat -c
adb forward tcp:18765 tcp:18765
trap 'adb forward --remove tcp:18765 >/dev/null 2>&1 || true' EXIT
adb shell am start -W -n com.dycomment.tv.android5/com.dycomment.tv.LanSyncSelfTestActivity --ez external_sender true
ready=false
for attempt in $(seq 1 30); do
  sleep 1
  adb logcat -d -s Android5LanSyncTest:I AndroidRuntime:E > evidence/lan-sync-external.txt
  if grep -q 'FAIL\|FATAL EXCEPTION' evidence/lan-sync-external.txt; then cat evidence/lan-sync-external.txt; exit 1; fi
  if grep -q 'LAN_SYNC_EXTERNAL_READY' evidence/lan-sync-external.txt; then ready=true; break; fi
done
$ready
if ! PYTHONDONTWRITEBYTECODE=1 python3 -B companion/tests/lan_android_interop.py > evidence/lan-sync-python.txt 2>&1; then
  adb logcat -d -s Android5LanSyncTest:I AndroidRuntime:E > evidence/lan-sync-external.txt
  cat evidence/lan-sync-python.txt
  cat evidence/lan-sync-external.txt
  exit 1
fi
cat evidence/lan-sync-python.txt
grep -q 'PASS LAN_ANDROID_NO_TYPING_PAIRING' evidence/lan-sync-python.txt
grep -q 'PASS BROWSER_ANDROID_INTEROP' evidence/lan-sync-python.txt
passed=false
for attempt in $(seq 1 45); do
  sleep 2
  adb logcat -d -s Android5LanSyncTest:I AndroidRuntime:E > evidence/lan-sync-external.txt
  if grep -q 'FAIL\|FATAL EXCEPTION' evidence/lan-sync-external.txt; then cat evidence/lan-sync-external.txt; exit 1; fi
  if grep -q 'PASS API21_LAN_SYNC_EXTERNAL_A_B_REJECT_C' evidence/lan-sync-external.txt; then passed=true; break; fi
done
cat evidence/lan-sync-external.txt
grep -q 'PASS API21_LAN_PAIRING_COMPARISON' evidence/lan-sync-external.txt
grep -q 'PASS API21_BROWSER_ACTION_FROM_PYTHON' evidence/lan-sync-external.txt
$passed
