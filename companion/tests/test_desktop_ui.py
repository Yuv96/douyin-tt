"""CI-only Tk event-loop checks with a fake backend; no login or network."""
from pathlib import Path
import queue
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    import tkinter as tk
    from desktop_app import DesktopApp
except ImportError:
    tk = None


class FakeService:
    def __init__(self, directory, notify):
        self.directory = directory
        self.notify = notify
        self.data = {"running": False, "paired": True, "ip": "tv.example", "state": "idle",
                     "busy": False, "closing": False}
        self.calls = []
        self.fail_login = False
        self.fail_close = False

    def snapshot(self):
        return dict(self.data)

    def emit(self, state, **fields):
        self.data["state"] = state
        self.notify({"state": state, **fields})

    def start(self, ip):
        self.calls.append(("start", ip, threading.get_ident()))
        self.data.update(running=True, ip=ip)
        self.emit("connecting")
        return True

    def stop(self):
        self.calls.append(("stop",))
        self.data["running"] = False
        self.emit("stopped")

    def open_login(self):
        self.calls.append(("login",))
        if self.fail_login:
            raise RuntimeError("synthetic-private-value")
        self.emit("login_required")

    def unpair(self):
        self.calls.append(("unpair",))
        self.data.update(running=False, paired=False)
        self.emit("unpaired")

    def close(self):
        self.calls.append(("close",))
        if self.fail_close:
            raise RuntimeError("synthetic-private-value")
        self.data["closing"] = True
        # The test releases cleanup explicitly; the UI must wait for this acknowledgement.


@unittest.skipIf(tk is None, "Tk runtime required on desktop CI")
class DesktopUiTests(unittest.TestCase):
    def setUp(self):
        try:
            self.root = tk.Tk()
        except tk.TclError:
            self.skipTest("Tk display required on desktop CI")
        self.root.withdraw()
        self.directory = tempfile.TemporaryDirectory()
        self.app = DesktopApp(self.root, self.directory.name, service_factory=FakeService)
        self.service = self.app.service
        self.tk_errors = []
        self.root.report_callback_exception = lambda kind, error, trace: self.tk_errors.append(error)

    def tearDown(self):
        if not self.app.closed:
            self.service.fail_close = False
            self.app.request_close()
            self.root.after(10, lambda: self.service.emit("closed"))
            self.wait_for_close()
        self.directory.cleanup()
        self.assertEqual(self.tk_errors, [])

    def pump(self):
        self.root.after(120, self.root.quit)
        self.root.mainloop()

    def wait_for_close(self):
        timeout = self.root.after(1000, self.root.quit)
        try:
            self.root.mainloop()
        finally:
            try:
                self.root.after_cancel(timeout)
            except tk.TclError:
                pass
        self.assertTrue(self.app.closed)

    def test_start_is_explicit_and_uses_entry_on_tk_thread(self):
        self.pump()
        self.assertEqual(self.service.calls, [])
        self.assertEqual(self.app.ip.get(), "tv.example")
        self.app.ip.set("  selected-tv.example  ")
        self.app.connect_button.invoke()
        self.pump()
        self.assertEqual(self.service.calls, [("start", "selected-tv.example", threading.get_ident())])
        self.assertEqual(self.app.connect_button.cget("text"), "停止")
        self.app.connect_button.invoke()
        self.pump()
        self.assertEqual(self.service.calls[-1], ("stop",))
        self.assertEqual(self.app.connect_button.cget("text"), "连接")

    def test_worker_pairing_event_only_updates_tk_on_main_thread(self):
        main_thread = threading.get_ident()
        writes = []
        errors = queue.Queue()
        self.app.comparison.trace_add("write", lambda *_: writes.append(threading.get_ident()))

        def send():
            try:
                self.service.emit("pending", comparison="123456")
            except Exception as error:
                errors.put(error)

        worker = threading.Thread(target=send)
        worker.start()
        worker.join(timeout=1)
        self.assertFalse(worker.is_alive())
        self.assertTrue(errors.empty())
        self.assertEqual(self.app.comparison.get(), "")
        self.pump()
        self.assertEqual(self.app.comparison.get(), "配对码  123456")
        self.assertEqual(set(writes), {main_thread})
        self.assertEqual(self.app.comparison_label.winfo_manager(), "grid")
        self.service.emit("ready", comparison="123456")
        self.pump()
        self.assertEqual(self.app.comparison.get(), "")
        self.assertEqual(self.app.comparison_label.winfo_manager(), "")

    def test_malformed_comparison_is_never_displayed(self):
        for value in ("12345", "１２３４５６", "1234567", "123\n456", None):
            self.service.emit("pending", comparison=value)
            self.pump()
            self.assertEqual(self.app.comparison.get(), "")
            self.assertEqual(self.app.comparison_label.winfo_manager(), "")

    def test_close_waits_for_backend_and_ignores_late_running_events(self):
        self.app.connect_button.invoke()
        self.root.tk.call(self.root.protocol("WM_DELETE_WINDOW"))
        self.app.request_close()
        self.service.emit("pending", comparison="123456")
        self.pump()
        self.assertFalse(self.app.closed)
        self.assertEqual(self.service.calls.count(("close",)), 1)
        self.assertEqual(self.app.comparison.get(), "")
        self.assertTrue(self.app.connect_button.instate(["disabled"]))
        self.root.after(10, lambda: self.service.emit("closed"))
        self.wait_for_close()

    def test_commands_and_failures_do_not_display_exception_details(self):
        self.service.fail_login = True
        self.app.login_button.invoke()
        self.assertNotIn("synthetic-private-value", self.app.status.get())
        self.app.unpair_button.invoke()
        self.pump()
        self.assertEqual(self.service.calls[-1], ("unpair",))
        self.assertTrue(self.app.unpair_button.instate(["disabled"]))
        self.service.fail_close = True
        self.app.request_close()
        self.assertFalse(self.app.closed)
        self.assertFalse(self.app.closing)
        self.assertNotIn("synthetic-private-value", self.app.status.get())
        self.assertTrue(self.app.exit_button.instate(["!disabled"]))

    def test_async_close_failure_allows_retry_after_backend_releases_closing(self):
        self.app.request_close()
        self.service.emit("error", message="其他操作未完成")
        self.pump()
        self.assertTrue(self.app.closing)
        self.service.data["closing"] = False
        self.service.emit("error", message="退出未完成，请重试")
        self.pump()
        self.assertFalse(self.app.closing)
        self.assertTrue(self.app.exit_button.instate(["!disabled"]))
        self.app.exit_button.invoke()
        self.assertEqual(self.service.calls.count(("close",)), 2)
        self.assertTrue(self.app.closing)

    def test_busy_operations_disable_duplicate_commands_but_allow_exit(self):
        self.service.data["busy"] = True
        self.service.emit("login_open")
        self.pump()
        self.assertTrue(self.app.login_button.instate(["disabled"]))
        self.assertTrue(self.app.connect_button.instate(["disabled"]))
        self.assertTrue(self.app.unpair_button.instate(["disabled"]))
        self.assertTrue(self.app.exit_button.instate(["!disabled"]))
        self.service.data["busy"] = False
        self.service.emit("login_open")
        self.pump()
        self.assertTrue(self.app.connect_button.instate(["!disabled"]))

    def test_second_launch_signal_focuses_existing_window_without_reading_content(self):
        signal = Path(self.directory.name) / "desktop-focus.json"
        signal.write_bytes(b"not-json")
        with patch.object(self.root, "deiconify") as deiconify, \
                patch.object(self.root, "lift") as lift, \
                patch.object(self.root, "focus_force") as focus:
            self.pump()
            deiconify.assert_called_once()
            lift.assert_called_once()
            focus.assert_called_once()
        self.assertFalse(signal.exists())
        self.assertEqual(self.service.calls, [])


if __name__ == "__main__":
    unittest.main()
