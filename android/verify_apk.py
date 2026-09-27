#!/usr/bin/env python3
"""Check the production APK independently of the regression-only build."""
import os, pathlib, re, subprocess, sys, zipfile
from signing import require_upgrade_signer
root=pathlib.Path(__file__).resolve().parent.parent
apk=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else root/'dist/douyin-tt-0.1.5.apk'
expected_abis=('armeabi-v7a',) if apk.name.endswith('-armv7.apk') else ('armeabi-v7a','x86')
android_tools=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
info=subprocess.check_output([android_tools/'aapt','dump','badging',apk],text=True)
assert "package: name='com.dycomment.tv.android5'" in info
assert "sdkVersion:'21'" in info
assert "versionCode='1012'" in info and "versionName='0.1.5'" in info
assert '抖音抬头版' in info
manifest=subprocess.check_output([android_tools/'aapt','dump','xmltree',apk,'AndroidManifest.xml'],text=True)
assert 'SelfTestActivity' not in manifest and 'SurfaceCoverTestActivity' not in manifest
assert 'QishuiActivity' not in manifest
for name in ('QrLoginActivity', 'GeckoLoginActivity', 'org.mozilla', ':login'):
    assert name not in manifest, 'Legacy login component remains: ' + name
assert 'LanSyncActivity' in manifest and 'LanSyncService' in manifest
with zipfile.ZipFile(apk) as z:
    assert not any('selftest-' in n for n in z.namelist())
    dex=b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    assert b'SelfTestActivity' not in dex
    assert b'TEST_PAIR_CONFIRM' not in dex, 'Test-only pairing approval hook in production'
    assert b'TEST_BROWSER_ACTION' not in dex, 'Test-only browser action trigger in production'
    for fixture in (b'BrowserActionsSelfTest', b'ProfileFeedSelfTest', b'QuickShareSelfTest', b'WireFixture'):
        assert fixture not in dex, 'Test fixture in production'
    assert b'LanPairingSelfTest' not in dex, 'Pairing fixture in production'
    assert b'LanPairingConfirmationReceiver' not in dex and 'LanPairingConfirmationReceiver' not in manifest, 'Test pairing receiver in production'
    assert b'LegacyUiFixture' not in dex and b'LegacyThemeSelfTest' not in dex
    assert b'LegacyGeometrySelfTest' not in dex
    assert b'DEFAULT_MS_TOKEN' not in dex
    for name in (b'QrLoginActivity', b'GeckoLoginActivity', b'RemoteGeckoView', b'QrSession', b'org/mozilla/geckoview'):
        assert name not in dex, 'Legacy login implementation remains'
    for name in z.namelist():
        assert not name.startswith('assets/login-extension/') and not name.endswith(('omni.ja', 'libxul.so', 'libmozglue.so')), 'Browser payload remains'
    assert {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')} == set(expected_abis)
    for abi in expected_abis:
        for library in ('libvlc.so', 'libvlcjni.so'):
            assert 'lib/'+abi+'/'+library in z.namelist()
            assert z.getinfo('lib/'+abi+'/'+library).compress_type == zipfile.ZIP_DEFLATED
subprocess.run([android_tools/'apksigner','verify','--verbose','--min-sdk-version','21',apk],check=True)
subprocess.run([sys.executable,root/'android/verify_branding.py',apk],check=True)
digest=require_upgrade_signer(root/'dist-test/douyin-tt-0.1.5.apk',apk,android_tools/'apksigner')
print('PASS production: API21, version 0.1.5 / 1012, computer sync only, no browser or test hooks; current regression and production use pinned signer '+digest)

if len(sys.argv)==1:
    subprocess.run([sys.executable, __file__, str(root/'dist/douyin-tt-0.1.5-armv7.apk')],check=True)
    with zipfile.ZipFile(apk) as all_arch, zipfile.ZipFile(root/'dist/douyin-tt-0.1.5-armv7.apk') as arm:
        for name in all_arch.namelist():
            if name.endswith('.dex'):
                assert all_arch.read(name)==arm.read(name), 'ABI variants must have identical business code'
