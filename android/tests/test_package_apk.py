"""Synthetic APK packaging regressions; run only in GitHub Actions."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from package_apk import package_variant


class PackageVariantTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.work = Path(directory.name)
        self.base = self.work / 'base.apk'
        self.dex = self.work / 'dex'
        self.dex.mkdir()
        (self.dex / 'classes.dex').write_bytes(b'new-primary-dex')
        (self.dex / 'classes2.dex').write_bytes(b'new-secondary-dex')
        with zipfile.ZipFile(self.base, 'w') as archive:
            archive.writestr('AndroidManifest.xml', b'synthetic-manifest')
            archive.writestr('resources.arsc', b'synthetic-resources', compress_type=zipfile.ZIP_STORED)
            archive.writestr('assets/LIBVLC-LICENSE.txt', b'VLC license fixture')
            archive.writestr('classes.dex', b'old-dex')
            archive.writestr('classes3.dex', b'old-secondary-dex')
            for name in ('MANIFEST.MF', 'OLD.SF', 'OLD.RSA', 'SIG-OLD'):
                archive.writestr('META-INF/' + name, b'old-signature')
            archive.writestr('META-INF/services/synthetic.Service', b'synthetic.Provider')
            for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
                for name in ('libvlc.so', 'libvlcjni.so'):
                    archive.writestr('lib/' + abi + '/' + name, b'native-' + abi.encode())
        environment = patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'})
        environment.start()
        self.addCleanup(environment.stop)

    def test_selected_vlc_abis_dex_and_resources_survive_without_browser_bundle(self):
        for abis in (('armeabi-v7a', 'x86'), ('armeabi-v7a',), ('x86',)):
            with self.subTest(abis=abis):
                target = self.work / ('-'.join(abis) + '.apk')
                package_variant(self.base, self.dex, target, abis)
                with zipfile.ZipFile(target) as archive:
                    names = set(archive.namelist())
                    native = {name for name in names if name.startswith('lib/')}
                    self.assertEqual(native, {'lib/' + abi + '/' + name
                        for abi in abis for name in ('libvlc.so', 'libvlcjni.so')})
                    self.assertTrue(all(archive.getinfo(name).compress_type == zipfile.ZIP_DEFLATED
                                        for name in native))
                    self.assertEqual(archive.read('classes.dex'), b'new-primary-dex')
                    self.assertEqual(archive.read('classes2.dex'), b'new-secondary-dex')
                    self.assertNotIn('classes3.dex', names)
                    self.assertEqual(archive.read('AndroidManifest.xml'), b'synthetic-manifest')
                    self.assertEqual(archive.read('resources.arsc'), b'synthetic-resources')
                    self.assertEqual(archive.getinfo('resources.arsc').compress_type, zipfile.ZIP_STORED)
                    self.assertEqual(archive.read('assets/LIBVLC-LICENSE.txt'), b'VLC license fixture')
                    self.assertEqual(archive.read('META-INF/services/synthetic.Service'), b'synthetic.Provider')
                    self.assertFalse(any(name.endswith(('.RSA', '.SF')) or name == 'META-INF/MANIFEST.MF'
                                         or name.startswith('META-INF/SIG-') for name in names))

    def test_missing_vlc_library_removes_partial_output(self):
        incomplete = self.work / 'incomplete.apk'
        with zipfile.ZipFile(self.base) as source, zipfile.ZipFile(incomplete, 'w') as output:
            for member in source.infolist():
                if member.filename != 'lib/x86/libvlcjni.so':
                    output.writestr(member, source.read(member))
        target = self.work / 'failed.apk'
        with self.assertRaisesRegex(RuntimeError, 'missing VLC'):
            package_variant(incomplete, self.dex, target, ('x86',))
        self.assertFalse(target.exists())

    def test_invalid_abi_dex_gap_and_source_overwrite_are_rejected(self):
        target = self.work / 'invalid.apk'
        for abis in ((), ('unknown',), 'x86'):
            with self.subTest(abis=abis), self.assertRaises((TypeError, ValueError)):
                package_variant(self.base, self.dex, target, abis)
        before = self.base.read_bytes()
        with self.assertRaises(ValueError):
            package_variant(self.base, self.dex, self.base, ('x86',))
        self.assertEqual(self.base.read_bytes(), before)
        (self.dex / 'classes2.dex').rename(self.dex / 'classes3.dex')
        with self.assertRaisesRegex(RuntimeError, 'contiguous'):
            package_variant(self.base, self.dex, target, ('x86',))
        self.assertFalse(target.exists())


if __name__ == '__main__':
    unittest.main()
