"""Tk desktop controls; browser and network work belong to DesktopService."""
from __future__ import annotations

from pathlib import Path
import queue
import tkinter as tk
from tkinter import ttk


STATE_TEXT = {
    "idle": "未连接",
    "stopped": "已停止",
    "starting": "正在连接…",
    "connecting": "正在连接…",
    "running": "已连接",
    "ready": "已连接",
    "connected": "已连接",
    "healthy": "已连接",
    "authenticated": "已登录",
    "login_required": "请在登录网页完成登录",
    "waiting_for_login": "请在登录网页完成登录",
    "waiting_login": "请在登录网页完成登录",
    "login_open": "请在登录网页完成登录",
    "pending": "请核对配对码，在电视允许连接",
    "pairing_pending": "请核对配对码，在电视允许连接",
    "paired": "已配对",
    "unpaired": "已解除配对",
    "stopping": "正在停止…",
    "closing": "正在退出…",
    "error": "操作未完成，请重试",
    "unavailable": "连接暂不可用",
}
PENDING_STATES = frozenset(("pending", "pairing_pending"))


class DesktopApp:
    """All Tk access stays on the creator thread; notify only enqueues data."""

    def __init__(self, root, directory, *, service_factory=None):
        self.root = root
        self.directory = Path(directory)
        self.events = queue.Queue()
        self.service = None
        self.closed = False
        self.closing = False
        self.running = False
        self.paired = False
        self.stopping = False
        self.busy = False
        self._poll_id = None
        self.ip = tk.StringVar(root)
        self.status = tk.StringVar(root, value="未连接")
        self.comparison = tk.StringVar(root)

        root.title("抖音抬头版 · 电脑同步")
        root.protocol("WM_DELETE_WINDOW", self.request_close)
        # Tk otherwise prints callback tracebacks, which can contain local paths.
        root.report_callback_exception = self._callback_error
        if root.tk.call("tk", "windowingsystem") == "aqua":
            root.createcommand("tk::mac::Quit", self.request_close)

        panel = ttk.Frame(root, padding=20)
        panel.grid(row=0, column=0, sticky="nsew")
        root.columnconfigure(0, weight=1)
        root.rowconfigure(0, weight=1)
        panel.columnconfigure(1, weight=1)

        ttk.Label(panel, text="电视 IP", anchor="center").grid(
            row=0, column=0, padx=(0, 12), sticky="ns")
        self.ip_entry = ttk.Entry(panel, textvariable=self.ip, width=30)
        self.ip_entry.grid(row=0, column=1, sticky="ew", ipady=4)
        ttk.Label(panel, textvariable=self.status, anchor="w", wraplength=360).grid(
            row=1, column=0, columnspan=2, pady=(16, 8), sticky="ew")
        self.comparison_label = ttk.Label(
            panel, textvariable=self.comparison, anchor="center", padding=(8, 12))
        self.comparison_label.grid(row=2, column=0, columnspan=2, sticky="ew")
        self.comparison_label.grid_remove()

        controls = ttk.Frame(panel)
        controls.grid(row=3, column=0, columnspan=2, pady=(12, 0), sticky="ew")
        controls.columnconfigure(0, weight=1)
        controls.columnconfigure(1, weight=1)
        self.connect_button = ttk.Button(
            controls, text="连接", command=self.toggle_connection, padding=(12, 8))
        self.login_button = ttk.Button(
            controls, text="登录网页", command=self.open_login, padding=(12, 8))
        self.unpair_button = ttk.Button(
            controls, text="解除配对", command=self.unpair, padding=(12, 8))
        self.exit_button = ttk.Button(
            controls, text="退出", command=self.request_close, padding=(12, 8))
        for widget, row, column in (
            (self.connect_button, 0, 0), (self.login_button, 0, 1),
            (self.unpair_button, 1, 0), (self.exit_button, 1, 1),
        ):
            widget.grid(row=row, column=column, padx=4, pady=4, sticky="ew")

        try:
            if service_factory is None:
                from desktop_service import DesktopService
                service_factory = DesktopService
            self.service = service_factory(Path(directory), self.notify)
            snapshot = self._snapshot()
            self.ip.set(snapshot.get("ip", "") if isinstance(snapshot.get("ip", ""), str) else "")
            self.status.set(STATE_TEXT.get(snapshot.get("state"), "未连接"))
        except Exception:
            self.status.set("同步服务暂不可用")
        self._render_controls()
        self._poll_id = root.after(80, self._poll)
        self.ip_entry.focus_set()

    def notify(self, event):
        """May be called from any backend thread. Never call Tk here."""
        if isinstance(event, dict):
            self.events.put(dict(event))

    def _snapshot(self):
        snapshot = self.service.snapshot() if self.service is not None else {}
        if not isinstance(snapshot, dict):
            raise ValueError("invalid service state")
        self.running = snapshot.get("running") is True
        self.paired = snapshot.get("paired") is True
        self.busy = snapshot.get("busy") is True
        return snapshot

    def _render_controls(self):
        disabled = self.closing or self.service is None
        self.ip_entry.state(["disabled" if disabled or self.running or self.stopping or self.busy else "!disabled"])
        self.connect_button.configure(text="停止" if self.running or self.stopping else "连接")
        self.connect_button.state(["disabled" if disabled or self.stopping or (self.busy and not self.running) else "!disabled"])
        self.login_button.state(["disabled" if disabled or self.busy else "!disabled"])
        self.unpair_button.state(["disabled" if disabled or self.stopping or self.busy or not self.paired else "!disabled"])
        self.exit_button.state(["disabled" if self.closing else "!disabled"])

    def _hide_comparison(self):
        self.comparison.set("")
        self.comparison_label.grid_remove()

    def _callback_error(self, _kind, _error, _traceback):
        if not self.closed:
            self.status.set("操作未完成，请重试")

    def _poll(self):
        self._poll_id = None
        try:
            signal = self.directory / "desktop-focus.json"
            if signal.exists():
                signal.unlink()
                self.root.deiconify()
                self.root.lift()
                self.root.focus_force()
        except (OSError, tk.TclError):
            pass
        for _ in range(100):
            try:
                event = self.events.get_nowait()
            except queue.Empty:
                break
            if event.get("state") == "closed":
                self.closed = True
                self.root.destroy()
                return
            state = event.get("state")
            if not isinstance(state, str):
                continue
            self._hide_comparison()
            try:
                snapshot = self._snapshot()
            except Exception:
                self.status.set("连接状态暂不可用")
                continue
            if self.closing:
                if state == "error" and not snapshot.get("closing", False):
                    self.closing = False
                else:
                    continue
            self.stopping = state == "stopping"
            # DesktopService messages are safe UI summaries, never raw exceptions/responses.
            message = event.get("message")
            if isinstance(message, str) and message.strip():
                self.status.set(" ".join(message.split())[:160])
            else:
                self.status.set(STATE_TEXT.get(state, "连接状态已更新"))
            comparison = event.get("comparison")
            if (state in PENDING_STATES and isinstance(comparison, str)
                    and len(comparison) == 6 and comparison.isascii() and comparison.isdigit()):
                self.comparison.set("配对码  " + comparison)
                self.comparison_label.grid()
            else:
                self._hide_comparison()
            self._render_controls()
        if not self.closed:
            self._poll_id = self.root.after(80, self._poll)

    def toggle_connection(self):
        if self.closing or self.stopping or (self.busy and not self.running) or self.service is None:
            return
        self._hide_comparison()
        try:
            if self.running:
                self.stopping = True
                self.status.set("正在停止…")
                self.service.stop()
            else:
                ip = self.ip.get().strip()
                if not ip:
                    self.status.set("请输入电视 IP")
                    self.ip_entry.focus_set()
                    return
                if self.service.start(ip):
                    self.running = True
                    self.status.set("正在连接…")
                else:
                    self.status.set("暂时无法连接，请稍后重试")
            self._render_controls()
        except Exception:
            self.stopping = False
            self.status.set("连接操作未完成，请重试")
            self._render_controls()

    def open_login(self):
        if self.closing or self.busy or self.service is None:
            return
        try:
            if self.service.open_login() is not False:
                self.busy = True
                self._render_controls()
        except Exception:
            self.status.set("登录网页暂时无法打开")

    def unpair(self):
        if self.closing or self.stopping or self.busy or not self.paired or self.service is None:
            return
        self._hide_comparison()
        self.stopping = True
        self.status.set("正在解除配对…")
        self._render_controls()
        try:
            if self.service.unpair() is False:
                self.stopping = False
                self.status.set("操作正在处理中")
                self._render_controls()
        except Exception:
            self.stopping = False
            self.status.set("解除配对未完成，请重试")
            self._render_controls()

    def request_close(self):
        if self.closing or self.closed:
            return
        self.closing = True
        self._hide_comparison()
        self.status.set("正在退出…")
        self._render_controls()
        if self.service is None:
            self.notify({"state": "closed"})
            return
        try:
            self.service.close()
        except Exception:
            # Keep the window alive: a failed cleanup must never look like a clean exit.
            self.closing = False
            self.status.set("退出未完成，请重试")
            self._render_controls()


def main(directory, root=None, *, service_factory=None):
    window = root if root is not None else tk.Tk()
    app = DesktopApp(window, directory, service_factory=service_factory)
    while not app.closed:
        try:
            window.mainloop()
        except KeyboardInterrupt:
            app.request_close()
        else:
            if not app.closed:
                app.request_close()
    return app
