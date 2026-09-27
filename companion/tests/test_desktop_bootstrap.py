"""CI-only launcher setup tests. All dependency installation is synthetic."""
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import run_douyin_tt as boot


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        (self.root / "companion").mkdir()
        (self.root / "companion" / "requirements-sync.txt").write_text("fixture==1.0\n")
        self.cancel = threading.Event()

    def tearDown(self):
        self.temp.cleanup()

    def installer(self, command, cancel):
        if "venv" in command:
            python = boot.interpreter(self.root)
            python.parent.mkdir(parents=True)
            python.write_text("synthetic interpreter")

    def test_prepares_only_private_wheel_environment_and_reuses_it(self):
        run = mock.Mock(side_effect=self.installer)
        boot.prepare(self.root, self.cancel, run)
        self.assertTrue(boot.ready(self.root))
        install = run.call_args_list[-1].args[0]
        self.assertIn("--only-binary=:all:", install)
        self.assertIn("--no-compile", install)
        self.assertEqual(install[0], str(boot.interpreter(self.root)))
        boot.prepare(self.root, self.cancel, run)
        self.assertEqual(run.call_count, 2)

    def test_failed_or_cancelled_install_never_marks_ready(self):
        for exception in (RuntimeError("synthetic"), boot.Cancelled()):
            with self.assertRaises(type(exception)):
                boot.prepare(self.root, self.cancel, mock.Mock(side_effect=exception))
            self.assertFalse(boot.ready(self.root))

    def test_cancel_after_install_does_not_commit_ready(self):
        def cancelled(command, event):
            self.installer(command, event)
            event.set()
        with self.assertRaises(boot.Cancelled):
            boot.prepare(self.root, self.cancel, cancelled)
        self.assertFalse(boot.ready(self.root))

    def test_does_not_modify_an_unknown_environment(self):
        env = self.root / ".runtime" / "venv"
        env.mkdir(parents=True)
        marker = env / "keep.txt"
        marker.write_text("keep")
        run = mock.Mock()
        with self.assertRaises(RuntimeError):
            boot.prepare(self.root, self.cancel, run)
        run.assert_not_called()
        self.assertEqual(marker.read_text(), "keep")

    def test_second_launch_focuses_existing_instance_and_releases_lock(self):
        directory = self.root / ".local-debug" / "sync"
        first = boot.acquire_desktop(directory)
        self.assertIsNotNone(first)
        try:
            self.assertIsNone(boot.acquire_desktop(directory))
            self.assertEqual(json.loads((directory / "desktop-focus.json").read_text()), {"focus": True})
        finally:
            boot.sync.release_lock(first)
        again = boot.acquire_desktop(directory)
        self.assertIsNotNone(again)
        boot.sync.release_lock(again)

    def test_environment_change_requires_setup(self):
        boot.prepare(self.root, self.cancel, self.installer)
        (self.root / "companion" / "requirements-sync.txt").write_text("fixture==2.0\n")
        self.assertFalse(boot.ready(self.root))

    def test_linked_runtime_is_rejected_without_writing(self):
        target = self.root / "elsewhere"
        target.mkdir()
        try:
            (self.root / ".runtime").symlink_to(target, target_is_directory=True)
        except OSError:
            self.skipTest("symlink creation unavailable")
        with self.assertRaises(RuntimeError):
            boot.prepare(self.root, self.cancel, mock.Mock())
        self.assertEqual(list(target.iterdir()), [])

    def test_launcher_failure_releases_single_instance_lock(self):
        with mock.patch.object(boot, "ready", side_effect=RuntimeError("synthetic")):
            with self.assertRaises(RuntimeError):
                boot.main(self.root)
        lock = boot.acquire_desktop(self.root / ".local-debug" / "sync")
        self.assertIsNotNone(lock)
        boot.sync.release_lock(lock)


if __name__ == "__main__":
    unittest.main()
