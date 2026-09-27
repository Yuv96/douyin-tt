"""GitHub-only regression for refusing incompatible production signing."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import signing


class SigningTests(unittest.TestCase):
    def rejected_build(self, configure, expected):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            # A leftover regression key must never make production silently succeed.
            (work / 'test-only.p12').write_bytes(b'synthetic-unused-key')
            env = dict(os.environ, BUILD_WORK=str(work), SELF_TEST='0')
            for name in ['ANDROID5_KEYSTORE', 'ANDROID5_KEYSTORE_PASSWORD', 'ANDROID_HOME']:
                env.pop(name, None)
            configure(env, work)
            result = subprocess.run([sys.executable, '-B', str(ROOT / 'build.py')],
                                    env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(expected, result.stderr)
            self.assertEqual((work / 'test-only.p12').read_bytes(), b'synthetic-unused-key')
            self.assertFalse((work / 'original.apk').exists())

    def test_production_does_not_reuse_development_key(self):
        self.rejected_build(lambda env, work: None, 'Production build requires')

    def test_missing_configured_key_is_not_regenerated(self):
        self.rejected_build(lambda env, work: env.update(ANDROID5_KEYSTORE=str(work / 'missing.p12')),
                            'Configured signing keystore does not exist')

    def test_production_cannot_guess_password(self):
        self.rejected_build(lambda env, work: env.update(ANDROID5_KEYSTORE=str(work / 'test-only.p12')),
                            'requires ANDROID5_KEYSTORE_PASSWORD')

    def test_valid_signature_from_another_key_is_rejected(self):
        with patch.object(signing, 'certificate', return_value='0' * 64):
            with self.assertRaisesRegex(RuntimeError, 'differs from the pinned certificate'):
                signing.require_pinned_signer('unused.apk', 'unused-signer')

    def test_pinned_certificate_fingerprint_cannot_drift(self):
        self.assertEqual(signing.EXPECTED_SHA256,
                         'a9a53928e2d288aba0ed134d1c92bf1e8ba29886f22b23578f56cd34ef1d32e6')

    def test_current_upgrade_baseline_must_exist_and_use_pinned_signer(self):
        with tempfile.TemporaryDirectory() as directory:
            baseline = Path(directory) / 'regression.apk'
            with self.assertRaisesRegex(RuntimeError, 'Current regression APK missing'):
                signing.require_upgrade_signer(baseline, 'production.apk', 'signer')
            baseline.write_bytes(b'synthetic-unused-apk')
            with patch.object(signing, 'certificate', return_value=signing.EXPECTED_SHA256) as read:
                self.assertEqual(signing.require_upgrade_signer(baseline, 'production.apk', 'signer'),
                                 signing.EXPECTED_SHA256)
                self.assertEqual(read.call_count, 2)
            for certificates in [('0' * 64, signing.EXPECTED_SHA256),
                                 (signing.EXPECTED_SHA256, '0' * 64)]:
                with patch.object(signing, 'certificate', side_effect=certificates):
                    with self.assertRaisesRegex(RuntimeError, 'differs from the pinned certificate'):
                        signing.require_upgrade_signer(baseline, 'production.apk', 'signer')


if __name__ == '__main__':
    unittest.main()
