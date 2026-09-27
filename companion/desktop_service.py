"""Single-window companion lifecycle. UI notifications contain no account data."""
from __future__ import annotations

import os
from pathlib import Path
from types import SimpleNamespace
import threading
import time
from urllib.parse import urlsplit

import agent_webview_sync as sync


STATUS = {
    "starting": ("starting", "正在连接…"),
    "healthy": ("running", "正在同步…"),
    "updated": ("connected", "已连接"),
    "unchanged": ("connected", "已连接"),
    "waiting_for_login": ("waiting_login", "请在网页完成登录"),
    "login_required": ("waiting_login", "请在网页完成登录"),
    "browser_unresponsive": ("waiting_login", "请查看登录网页"),
    "network_error": ("running", "网络暂不可用"),
    "sync_unavailable": ("running", "电视暂不可达"),
    "startup_failed": ("error", "连接失败，请重试"),
}


def saved_config(directory):
    path = Path(directory) / "config.json"
    return sync.private_json(path) if path.exists() else {}


def forget_pairing(directory):
    """Caller owns the sync lock; erase only this computer's pairing credential."""
    directory = Path(directory)
    config = saved_config(directory)
    for key in ("pairing_key", "receiver_id", "generation"):
        config.pop(key, None)
    sync.private_write(directory / "config.json", config)
    sync.private_write(directory / "status.json", {"state": "unpaired", "paired": False})
    sync.private_write(directory / "browser-actions-status.json", {"state": "stopped", "capabilities": []})


def close_browser(directory):
    """Stop only the controller recorded for this private directory, never another app."""
    directory = Path(directory).resolve()
    runtime = directory / "controller.json"
    if not runtime.exists():
        return
    record = sync.private_json(runtime)
    parsed = urlsplit(record.get("base_url", ""))
    if parsed.hostname != "127.0.0.1" or parsed.port != sync.CONTROLLER_PORT:
        raise sync.SyncError("controller_unavailable")
    import psutil
    try:
        process = psutil.Process(record["pid"])
        args = process.cmdline()
        same_file = "--runtime-file" in args and os.path.normcase(os.path.abspath(
            args[args.index("--runtime-file") + 1])) == os.path.normcase(str(runtime))
        same_port = "--port" in args and args[args.index("--port") + 1] == str(sync.CONTROLLER_PORT)
        native_controller = "-m" in args and args[args.index("-m") + 1] == "agent_webview"
        if not (same_file and same_port and native_controller):
            raise sync.SyncError("controller_unavailable")
    except psutil.NoSuchProcess:
        (directory / "session.json").unlink(missing_ok=True)
        return
    web = sync.SyncWebview(directory)
    session = directory / "session.json"
    if session.exists():
        try:
            web.session(method="DELETE")
        except sync.DebugError:
            # A closed worker can already be absent; never terminate a live foreign session.
            pass
    sessions = web.call("/sessions").get("sessions")
    if not isinstance(sessions, list) or any(not isinstance(item, dict) for item in sessions):
        raise sync.SyncError("controller_unavailable")
    if any(item.get("state") == "running" for item in sessions):
        raise sync.SyncError("controller_busy")
    process.terminate()
    process.wait(timeout=15)
    session.unlink(missing_ok=True)
    runtime.unlink(missing_ok=True)


class DesktopService:
    def __init__(self, directory, notify):
        self.directory = sync.private_directory(Path(directory).resolve())
        self.notify = notify
        self.mutex = threading.RLock()
        self.cancel = threading.Event()
        self.runner = None
        self.operation = None
        self.closing = False
        self.state = "stopped"
        self.message = "已停止"
        self.manages_browser = False

    def snapshot(self):
        with self.mutex:
            try:
                config = saved_config(self.directory)
            except (sync.DebugError, OSError, ValueError):
                config = {}
            return {"running": self.runner is not None and self.runner.is_alive(),
                    "busy": self.operation is not None and self.operation.is_alive(),
                    "closing": self.closing,
                    "paired": bool(config.get("pairing_key")), "ip": config.get("ip", ""),
                    "state": self.state}

    def emit(self, state, message, **values):
        with self.mutex:
            self.state = state
            self.message = message
            self._notify(dict(state=state, message=message, **values))

    def _notify(self, event):
        try:
            self.notify(event)
        except Exception:
            # A UI callback failure must not interrupt worker or browser cleanup.
            pass

    def start(self, ip):
        with self.mutex:
            if self.closing or self.snapshot()["running"] or self.snapshot()["busy"]:
                return False
            self.cancel = threading.Event()
            self.runner = threading.Thread(target=self._run, args=(ip.strip(),), name="desktop-sync")
            self.runner.start()
        return True

    def _run(self, ip):
        lock = None
        monitor_done = threading.Event()
        monitor = None
        try:
            sync.private_host(ip)
            lock = sync.claim_lock(self.directory)
            (self.directory / "stop.json").unlink(missing_ok=True)
            self.emit("starting", "正在连接…")
            if self.cancel.is_set():
                return
            self.manages_browser = True
            sync.ensure_browser(self.directory)
            args = SimpleNamespace(ip=ip, interval=600, pair=False, read_only=False, keep_visible=False)
            sync.load_config(self.directory, args, on_compare=lambda code, _:
                self.emit("pairing_pending", "核对电视上的数字并允许", comparison=code),
                stop_event=self.cancel)
            if self.cancel.is_set():
                return
            self.emit("running", "正在同步…")
            started = time.time()
            monitor = threading.Thread(target=self._monitor, args=(monitor_done, started), daemon=True)
            monitor.start()
            sync.run_service(self.directory, stop_event=self.cancel)
        except sync.SyncError as error:
            if error.code != "cancelled" and not self.cancel.is_set():
                message = {"already_running": "同步已在运行", "invalid_target": "请填写电视局域网 IP",
                           "pair_closed": "请在电视开启电脑同步", "pair_rejected": "配对未获允许",
                           "pair_expired": "配对已超时"}.get(error.code, "连接失败，请重试")
                self.emit("error", message)
        except Exception:
            if not self.cancel.is_set():
                self.emit("error", "连接失败，请重试")
        finally:
            monitor_done.set()
            if monitor is not None:
                monitor.join()
            if lock is not None:
                sync.release_lock(lock)
            with self.mutex:
                self.runner = None
                closing = self.closing
                if not closing:
                    if self.state == "error":
                        self._notify({"state": self.state, "message": self.message})
                    else:
                        self.emit("stopped", "已停止")

    def _monitor(self, done, started):
        previous = None
        path = self.directory / "status.json"
        while not done.wait(0.5):
            if self.cancel.is_set():
                continue
            try:
                if path.stat().st_mtime < started:
                    continue
                value = sync.private_json(path).get("state")
                if value != previous and value in STATUS:
                    previous = value
                    self.emit(*STATUS[value])
            except (OSError, sync.DebugError, ValueError):
                pass

    def stop(self):
        with self.mutex:
            self.cancel.set()
            active = self.runner is not None and self.runner.is_alive()
        self.emit("stopping" if active else "stopped", "正在停止…" if active else "已停止")

    def _operation(self, task):
        with self.mutex:
            if self.closing or self.snapshot()["busy"]:
                return False
            def work():
                try:
                    task()
                except Exception:
                    self.emit("error", "操作未完成，请重试")
                finally:
                    with self.mutex:
                        self.operation = None
                        self._notify({"state": self.state, "message": self.message})
            self.operation = threading.Thread(target=work, name="desktop-operation")
            self.operation.start()
        return True

    def open_login(self):
        def open_page():
            if self.snapshot()["running"]:
                sync.ensure_browser(self.directory, launch=False).show()
            else:
                lock = sync.claim_lock(self.directory)
                try:
                    self.manages_browser = True
                    sync.ensure_browser(self.directory).show()
                finally:
                    sync.release_lock(lock)
            self.emit("login_open", "请在网页完成登录")
        return self._operation(open_page)

    def unpair(self):
        def remove():
            self.stop()
            with self.mutex:
                running = self.runner
            if running is not None:
                running.join()
            lock = sync.claim_lock(self.directory)
            try:
                forget_pairing(self.directory)
            finally:
                sync.release_lock(lock)
            self.emit("unpaired", "已解除本机配对")
        return self._operation(remove)

    def close(self):
        with self.mutex:
            if self.closing:
                return
            self.closing = True
            self.cancel.set()
            runner, operation = self.runner, self.operation
        self.emit("closing", "正在退出…")
        def finish():
            for thread in (runner, operation):
                if thread is not None:
                    thread.join()
            lock = None
            failed = False
            try:
                if self.manages_browser:
                    lock = sync.claim_lock(self.directory)
                    close_browser(self.directory)
                    self.manages_browser = False
            except Exception:
                failed = True
            finally:
                if lock is not None:
                    try:
                        sync.release_lock(lock)
                    except Exception:
                        failed = True
            if failed:
                # Release ownership before offering retry, including reentrant UI callbacks.
                with self.mutex:
                    self.closing = False
                    self.emit("error", "退出未完成，请重试")
                return
            self.emit("closed", "已退出")
        threading.Thread(target=finish, name="desktop-close").start()
