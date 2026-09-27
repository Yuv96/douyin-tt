"""Inspect packaged launcher resources only on GitHub runners."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt'
badging = subprocess.check_output([aapt, 'dump', 'badging', apk], text=True)
manifest = subprocess.check_output([aapt, 'dump', 'xmltree', apk, 'AndroidManifest.xml'], text=True)
assert 'android.intent.category.LEANBACK_LAUNCHER' in manifest
assert 'android.hardware.touchscreen' in manifest
assert 'ta_tou_launcher' in badging, 'Launcher still selects upstream artwork'
assert 'ta_tou_banner' in badging, 'TV banner is not assigned'
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        matches = [n for n in names if n.startswith('res/mipmap-' + density + '/')
                   or n.startswith('res/mipmap-' + density + '-v')]
        image = next(n for n in matches if n.endswith('/ta_tou_launcher.png'))
        raw = archive.read(image)
        assert raw[:8] == b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II', raw[16:24]) == (size, size)
    banner = next(n for n in names if n.startswith('res/drawable-xhdpi') and n.endswith('/ta_tou_banner.png'))
    assert struct.unpack('>II', archive.read(banner)[16:24]) == (320, 180)
    for version in [26, 33]:
        assert f'res/mipmap-anydpi-v{version}/ta_tou_launcher.xml' in names
print('PASS legacy launcher sizes, adaptive layers, TV entry point and 320x180 banner')
