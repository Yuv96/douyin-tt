"""CI-only desktop lifecycle checks. Browser, pairing and network work are synthetic."""
from pathlib import Path
import queue
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import desktop_service as desktop


class DesktopServiceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.events = queue.Queue()
        self.closed = threading.Event()
        self.release = threading.Event()
        self.records = []
        self.real_load_config = desktop.sync.load_config

        def notify(event):
            self.records.append((threading.get_ident(), dict(event)))
            self.events.put(event)
            if event["state"] == "closed":
                self.closed.set()

        self.service = desktop.DesktopService(self.directory, notify)
        self.ensure = self.mock("ensure_browser")
        self.load = self.mock("load_config")
        self.run = self.mock("run_service")
        self.host = self.mock("private_host", side_effect=lambda value: value)
        patcher = patch.object(desktop, "close_browser")
        self.close_browser = patcher.start()
        self.addCleanup(patcher.stop)

    def mock(self, name, **kwargs):
        patcher = patch.object(desktop.sync, name, **kwargs)
        result = patcher.start()
        self.addCleanup(patcher.stop)
        return result

    def tearDown(self):
        self.release.set()
        self.service.cancel.set()
        self.close_browser.side_effect = None
        if not self.closed.is_set():
            self.service.close()
        self.assertTrue(self.closed.wait(3), "synthetic desktop cleanup did not complete")

    def wait_state(self, state):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                event = self.events.get(timeout=max(0.001, deadline - time.monotonic()))
            except queue.Empty:
                break
            if event["state"] == state:
                return event
        self.fail("missing desktop state: " + state)

    def test_lock_duplicate_start_and_close_wait_for_writer(self):
        entered = threading.Event()
        writer_exited = threading.Event()
        destroyed = threading.Event()
        order = []

        def run(_directory, *, stop_event):
            entered.set()
            stop_event.wait(3)
            self.release.wait(3)
            order.append("writer exited")
            writer_exited.set()

        def destroy(_directory):
            order.append("browser destroyed")
            destroyed.set()

        self.run.side_effect = run
        self.close_browser.side_effect = destroy
        self.assertTrue(self.service.start("tv.example"))
        self.assertTrue(entered.wait(2))
        self.assertFalse(self.service.start("other-tv.example"))
        with self.assertRaises(desktop.sync.SyncError) as failure:
            desktop.sync.claim_lock(self.directory)
        self.assertEqual(failure.exception.code, "already_running")
        self.service.stop()
        self.service.close()
        self.assertFalse(destroyed.wait(0.05))
        self.assertFalse(writer_exited.is_set())
        self.release.set()
        self.wait_state("closed")
        self.assertEqual(order, ["writer exited", "browser destroyed"])
        self.run.assert_called_once()
        lock = desktop.sync.claim_lock(self.directory)
        desktop.sync.release_lock(lock)

    def test_cancel_pairing_does_not_save_candidate_pairing(self):
        original = {"ip": "saved-tv.example", "pairing_key": "b" * 32,
                    "receiver_id": "c" * 32, "generation": 4, "interval": 600}
        desktop.sync.private_write(self.directory / "config.json", original)
        before = (self.directory / "config.json").read_bytes()
        self.load.side_effect = self.real_load_config

        def pair(*, on_compare, stop_event):
            on_compare("123456", "synthetic-pair")
            stop_event.wait(3)
            return {"pairing_key": "a" * 32, "receiver_id": "d" * 32}

        with patch.object(desktop.sync, "PairingClient") as client:
            client.return_value.pair.side_effect = pair
            self.assertTrue(self.service.start("selected-tv.example"))
            self.assertEqual(self.wait_state("pairing_pending")["comparison"], "123456")
            self.service.stop()
            self.wait_state("stopped")
        self.assertEqual((self.directory / "config.json").read_bytes(), before)
        self.run.assert_not_called()
        pairing_threads = [identity for identity, event in self.records if event["state"] == "pairing_pending"]
        self.assertTrue(pairing_threads)
        self.assertNotIn(threading.get_ident(), pairing_threads)

    def test_external_sync_is_not_stopped_and_does_not_block_unused_gui_close(self):
        external_lock = desktop.sync.claim_lock(self.directory)
        marker = self.directory / "stop.json"
        marker.write_text("synthetic external marker")
        try:
            self.assertTrue(self.service.start("tv.example"))
            self.wait_state("error")
            self.service.stop()
            self.service.close()
            self.wait_state("closed")
            self.assertEqual(marker.read_text(), "synthetic external marker")
            self.ensure.assert_not_called()
            self.close_browser.assert_not_called()
            with self.assertRaises(desktop.sync.SyncError):
                desktop.sync.claim_lock(self.directory)
        finally:
            desktop.sync.release_lock(external_lock)

    def test_unpair_preserves_browser_and_non_pairing_configuration(self):
        config = {"ip": "tv.example", "interval": 600, "keep_visible": True,
                  "pairing_key": "a" * 32, "receiver_id": "b" * 32, "generation": 8}
        desktop.sync.private_write(self.directory / "config.json", config)
        session = self.directory / "session.json"
        controller = self.directory / "controller.json"
        session.write_text("synthetic session record")
        controller.write_text("synthetic controller record")
        self.assertTrue(self.service.unpair())
        self.wait_state("unpaired")
        self.assertEqual(desktop.saved_config(self.directory),
                         {"ip": "tv.example", "interval": 600, "keep_visible": True})
        self.assertEqual(session.read_text(), "synthetic session record")
        self.assertEqual(controller.read_text(), "synthetic controller record")
        self.close_browser.assert_not_called()
        self.ensure.assert_not_called()

    def test_close_waits_for_login_operation_and_busy_rejects_start(self):
        showing = threading.Event()
        order = []

        def show():
            showing.set()
            self.release.wait(3)
            order.append("login returned")

        self.ensure.return_value.show.side_effect = show
        self.close_browser.side_effect = lambda _: order.append("browser destroyed")
        self.assertTrue(self.service.open_login())
        self.assertTrue(showing.wait(2))
        self.assertTrue(self.service.snapshot()["busy"])
        self.assertFalse(self.service.start("tv.example"))
        self.service.close()
        self.close_browser.assert_not_called()
        self.release.set()
        self.wait_state("closed")
        self.assertEqual(order, ["login returned", "browser destroyed"])

    def test_failed_close_releases_lock_before_retry_and_hides_exception(self):
        self.service.manages_browser = True
        self.close_browser.side_effect = [OSError("synthetic-private-detail"), None]
        self.service.close()
        event = self.wait_state("error")
        self.assertNotIn("synthetic-private-detail", str(event))
        self.assertFalse(self.service.snapshot()["closing"])
        lock = desktop.sync.claim_lock(self.directory)
        desktop.sync.release_lock(lock)
        self.service.close()
        self.wait_state("closed")
        self.assertEqual(self.close_browser.call_count, 2)

    def test_notification_failure_does_not_abort_cleanup(self):
        notify = self.service.notify

        def broken_once(event):
            if event["state"] == "starting":
                raise RuntimeError("synthetic callback failure")
            notify(event)

        self.service.notify = broken_once
        self.assertTrue(self.service.start("tv.example"))
        self.wait_state("stopped")
        self.run.assert_called_once()
        self.service.close()
        self.wait_state("closed")


class ControllerOwnershipTests(unittest.TestCase):
    def test_foreign_process_or_other_running_session_is_never_terminated(self):
        for foreign_process in (True, False):
            with self.subTest(foreign_process=foreign_process), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary).resolve()
                runtime = directory / "controller.json"
                runtime.write_text("synthetic controller")
                process = Mock()
                process.cmdline.return_value = ["python", "-m", "unrelated" if foreign_process else "agent_webview",
                    "--runtime-file", str(runtime), "--port", str(desktop.sync.CONTROLLER_PORT)]
                psutil = SimpleNamespace(Process=Mock(return_value=process), NoSuchProcess=type("NoSuchProcess", (Exception,), {}))
                record = {"pid": 123, "base_url": "http://127.0.0.1:" + str(desktop.sync.CONTROLLER_PORT)}
                with patch.dict(sys.modules, {"psutil": psutil}), \
                        patch.object(desktop.sync, "private_json", return_value=record), \
                        patch.object(desktop.sync, "SyncWebview") as web:
                    web.return_value.call.return_value = {"sessions": [{"state": "running"}]}
                    with self.assertRaises(desktop.sync.SyncError):
                        desktop.close_browser(directory)
                    process.terminate.assert_not_called()
                    process.wait.assert_not_called()
                    if foreign_process:
                        web.assert_not_called()

    def test_owned_browser_is_deleted_before_controller_termination(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            runtime, session = directory / "controller.json", directory / "session.json"
            runtime.write_text("synthetic controller")
            session.write_text("synthetic session")
            process = Mock()
            process.cmdline.return_value = ["python", "-m", "agent_webview",
                "--runtime-file", str(runtime), "--port", str(desktop.sync.CONTROLLER_PORT)]
            psutil = SimpleNamespace(Process=Mock(return_value=process), NoSuchProcess=type("NoSuchProcess", (Exception,), {}))
            record = {"pid": 123, "base_url": "http://127.0.0.1:" + str(desktop.sync.CONTROLLER_PORT)}
            order = []
            process.terminate.side_effect = lambda: order.append("terminate")
            process.wait.side_effect = lambda **_: order.append("wait")
            with patch.dict(sys.modules, {"psutil": psutil}), \
                    patch.object(desktop.sync, "private_json", return_value=record), \
                    patch.object(desktop.sync, "SyncWebview") as web:
                web.return_value.session.side_effect = lambda **_: order.append("delete session")
                web.return_value.call.side_effect = lambda _: (order.append("read sessions") or {"sessions": []})
                desktop.close_browser(directory)
                web.return_value.session.assert_called_once_with(method="DELETE")
            self.assertEqual(order, ["delete session", "read sessions", "terminate", "wait"])
            self.assertFalse(runtime.exists())
            self.assertFalse(session.exists())


if __name__ == "__main__":
    unittest.main()
