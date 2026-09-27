#!/usr/bin/env bash
# GitHub API21 only: this run's pinned-key regression APK -> production APK.
set -euo pipefail
mkdir -p evidence
adb root
adb wait-for-device
adb uninstall com.dycomment.tv.android5 >/dev/null 2>&1 || true
adb install dist-test/douyin-tt-0.1.5.apk > evidence/upgrade-regression.txt
tr -d '\r' < evidence/upgrade-regression.txt | grep -qx 'Success'
# API21's shell lacks printf, and legacy adb shell does not propagate its failure.
printf '%s' 'douyin-tt-upgrade-preserved' > evidence/upgrade-marker.txt
adb push evidence/upgrade-marker.txt /data/data/com.dycomment.tv.android5/upgrade-probe
marker=$(adb shell cat /data/data/com.dycomment.tv.android5/upgrade-probe | tr -d '\r')
test "$marker" = 'douyin-tt-upgrade-preserved'
# Seed synthetic credentials and pairing before replacing the regression package.
# No real account data is used and no app process makes requests with this fixture.
adb shell am force-stop com.dycomment.tv.android5
upgrade_app_uid=$(adb shell dumpsys package com.dycomment.tv.android5 | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -1 | tr -d '\r')
case "$upgrade_app_uid" in ''|*[!0-9]*) exit 1;; esac
cat > evidence/upgrade-auth-before.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="cookie">sessionid=upgrade-fixture; msToken=upgrade-token</string><string name="ms_token">upgrade-token</string><long name="credential_generation" value="47" /></map>
XML
cat > evidence/upgrade-pair-before.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="enabled" value="true" /><string name="pair_key">000102030405060708090a0b0c0d0e0f</string><string name="receiver_id">00112233445566778899aabbccddeeff</string></map>
XML
adb shell mkdir -p /data/data/com.dycomment.tv.android5/shared_prefs
adb push evidence/upgrade-auth-before.xml /data/data/com.dycomment.tv.android5/shared_prefs/dy_config.xml
adb push evidence/upgrade-pair-before.xml /data/data/com.dycomment.tv.android5/shared_prefs/lan_sync.xml
adb shell chown "$upgrade_app_uid:$upgrade_app_uid" /data/data/com.dycomment.tv.android5/shared_prefs /data/data/com.dycomment.tv.android5/shared_prefs/dy_config.xml /data/data/com.dycomment.tv.android5/shared_prefs/lan_sync.xml
adb shell chmod 700 /data/data/com.dycomment.tv.android5/shared_prefs
adb shell chmod 600 /data/data/com.dycomment.tv.android5/shared_prefs/dy_config.xml /data/data/com.dycomment.tv.android5/shared_prefs/lan_sync.xml
adb install -r dist/douyin-tt-0.1.5.apk > evidence/upgrade-production.txt
tr -d '\r' < evidence/upgrade-production.txt | grep -qx 'Success'
marker=$(adb shell cat /data/data/com.dycomment.tv.android5/upgrade-probe | tr -d '\r')
test "$marker" = 'douyin-tt-upgrade-preserved'
adb pull /data/data/com.dycomment.tv.android5/shared_prefs/dy_config.xml evidence/upgrade-auth-after.xml
adb pull /data/data/com.dycomment.tv.android5/shared_prefs/lan_sync.xml evidence/upgrade-pair-after.xml
cmp evidence/upgrade-auth-before.xml evidence/upgrade-auth-after.xml
cmp evidence/upgrade-pair-before.xml evidence/upgrade-pair-after.xml
adb shell rm /data/data/com.dycomment.tv.android5/upgrade-probe
printf '%s\n' 'PASS API21 current pinned-key regression APK -> current production APK install -r; application data preserved' > evidence/upgrade-result.txt
printf '%s\n' 'PASS credentials, generation, pairing key, receiver identity and enabled preference preserved' >> evidence/upgrade-result.txt
cat evidence/upgrade-result.txt
