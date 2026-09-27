"""GitHub-only guards for manual workflows and opt-in APK artifacts; no product build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WorkflowPolicyTests(unittest.TestCase):
    def test_every_workflow_is_manual_and_has_no_publishing_path(self):
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            with self.subTest(workflow=path.name):
                text = path.read_text()
                triggers = re.search(r'(?ms)^on:\n(.*?)(?=^\S|\Z)', text)
                self.assertIsNotNone(triggers)
                self.assertEqual(re.findall(r'^  ([A-Za-z_]+):', triggers.group(1), re.M),
                                 ['workflow_dispatch'])
                self.assertNotRegex(text, r'(?mi)^\s*publish:|contents:\s*write|gh release\s|action-gh-release')

    def test_product_build_requires_explicit_opt_in(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertRegex(text, r'(?s)build_apk:\n.*?type: boolean\n\s+default: false')
        self.assertIn('  build-test:\n    if: inputs.build_apk == true\n    needs: source-checks', text)
        checks = text.split('  source-checks:\n', 1)[1].split('  build-test:\n', 1)[0]
        self.assertIn('unittest discover', checks)
        self.assertNotIn('build.py', checks)
        self.assertNotIn('upload-artifact', checks)
        self.assertNotIn('secrets.', checks)

    def test_upgrade_uses_only_current_build_artifacts(self):
        for name in ('signing.py', 'verify_apk.py', 'upgrade_smoke.sh'):
            text = (ROOT / 'android' / name).read_text()
            self.assertNotRegex(text, r'https?://')
        script = (ROOT / 'android/upgrade_smoke.sh').read_text()
        self.assertIn('adb install dist-test/', script)
        self.assertIn('adb install -r dist/', script)
        self.assertIn('cmp evidence/upgrade-auth-before.xml evidence/upgrade-auth-after.xml', script)
        self.assertIn('cmp evidence/upgrade-pair-before.xml evidence/upgrade-pair-after.xml', script)

    def test_directory_migration_preserves_android_component_and_data_paths(self):
        scripts = ['android/smoke.sh', 'android/legacy_smoke.sh', 'android/lan_sync_smoke.sh',
                   'android/upgrade_smoke.sh', 'companion/tests/lan_android_interop.py']
        for name in scripts:
            with self.subTest(script=name):
                text = (ROOT / name).read_text()
                self.assertNotIn('com.dycomment.tv.android/', text)
                self.assertIn('com.dycomment.tv.android5/', text)
        for name in ('build.py', 'signing.py', 'verify_apk.py', 'verify_branding.py'):
            with self.subTest(script=name):
                text = (ROOT / 'android' / name).read_text()
                self.assertIn('build-tools', text)
                self.assertNotIn('build-companion', text)


if __name__ == '__main__':
    unittest.main()
