"""Portable desktop entry; dependencies stay beside the source, never system-wide."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading

sys.dont_write_bytecode = True
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
os.environ["PIP_NO_COMPILE"] = "1"
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "companion"))
import agent_webview_sync as sync


class Cancelled(Exception):
    pass


def safe_path(path):
    path = Path(path).absolute()
    if any(sync.linked_path(item) for item in (path, *path.parents)):
        raise RuntimeError("linked runtime path")
    return path


def interpreter(root=ROOT, *, windowed=False):
    env = Path(root) / ".runtime" / "venv"
    if os.name == "nt":
        return env / "Scripts" / ("pythonw.exe" if windowed else "python.exe")
    return env / "bin" / "python"


def signature(root=ROOT):
    payload = (Path(root) / "companion" / "requirements-sync.txt").read_bytes()
    payload += (str(Path(root).resolve()) + sys.platform + str(sys.version_info[:2])).encode()
    return hashlib.sha256(payload).hexdigest()


def ready(root=ROOT):
    runtime = safe_path(Path(root) / ".runtime")
    safe_path(runtime / "venv")
    stamp = safe_path(runtime / "ready.json")
    try:
        return (interpreter(root).is_file()
                and json.loads(stamp.read_text(encoding="utf-8")) == {"signature": signature(root)})
    except (OSError, ValueError):
        return False


def quiet_run(command, cancel):
    if cancel.is_set():
        raise Cancelled()
    options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
    with subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, **options) as process:
        while process.poll() is None:
            if cancel.wait(0.15):
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                raise Cancelled()
        if process.returncode:
            raise RuntimeError("dependency setup failed")
    if cancel.is_set():
        raise Cancelled()


def prepare(root, cancel, run=quiet_run):
    root = Path(root)
    runtime = safe_path(root / ".runtime")
    env = safe_path(runtime / "venv")
    owner = safe_path(runtime / "owner.json")
    stamp = safe_path(runtime / "ready.json")
    if ready(root):
        return
    runtime.mkdir(mode=0o700, exist_ok=True)
    if owner.exists():
        if json.loads(owner.read_text(encoding="utf-8")) != {"application": "douyin-tt"}:
            raise RuntimeError("unknown environment")
    else:
        if env.exists():
            raise RuntimeError("unknown environment")
        sync.private_write(owner, {"application": "douyin-tt"})
    stamp.unlink(missing_ok=True)
    if not interpreter(root).is_file():
        # The installed base interpreter creates the private environment. No source builds.
        base = getattr(sys, "_base_executable", sys.executable)
        run([base, "-B", "-m", "venv", str(env)], cancel)
    run([str(interpreter(root)), "-B", "-m", "pip", "install",
         "--only-binary=:all:", "--no-compile", "--disable-pip-version-check",
         "--find-links", str(root / "companion" / "wheels"),
         "-r", str(root / "companion" / "requirements-sync.txt")], cancel)
    if cancel.is_set():
        raise Cancelled()
    sync.private_write(stamp, {"signature": signature(root)})


def acquire_desktop(directory):
    try:
        return sync.claim_lock(directory, "desktop.lock")
    except sync.SyncError as error:
        if error.code != "already_running":
            raise
        sync.private_write(Path(directory) / "desktop-focus.json", {"focus": True})
        return None


def prepare_window(root):
    """Show a single cancellable setup window; never block Tk on pip/network."""
    import tkinter as tk
    from tkinter import ttk
    window = tk.Tk()
    window.title("抖音抬头版")
    window.resizable(False, False)
    message = tk.StringVar(window, value="正在准备运行环境…")
    ttk.Label(window, textvariable=message, padding=24).pack()
    cancel = threading.Event()
    results = queue.Queue()

    def stop():
        cancel.set()
        message.set("正在退出…")

    window.protocol("WM_DELETE_WINDOW", stop)
    if window.tk.call("tk", "windowingsystem") == "aqua":
        window.createcommand("tk::mac::Quit", stop)
    ttk.Button(window, text="取消", command=stop).pack(pady=(0, 16))

    def work():
        try:
            prepare(root, cancel)
            results.put("ready")
        except Cancelled:
            results.put("cancelled")
        except Exception:
            results.put("failed")

    outcome = []

    def poll():
        try:
            outcome.append(results.get_nowait())
        except queue.Empty:
            window.after(100, poll)
        else:
            window.destroy()

    worker = threading.Thread(target=work, name="desktop-setup")
    worker.start()
    window.after(100, poll)
    window.mainloop()
    cancel.set()
    worker.join()
    if outcome == ["failed"]:
        raise RuntimeError("dependency setup failed")
    return outcome == ["ready"]


def show_error():
    import tkinter as tk
    from tkinter import messagebox
    window = tk.Tk()
    window.withdraw()
    messagebox.showerror("抖音TT无法启动", "请检查网络和 Python（含 Tk），然后重试。", parent=window)
    window.destroy()


def main(root=ROOT):
    root = Path(root)
    directory = safe_path(root / ".local-debug" / "sync")
    lock = acquire_desktop(directory)
    if lock is None:
        return
    try:
        if not ready(root) and not prepare_window(root):
            return
        env = root / ".runtime" / "venv"
        if Path(sys.prefix).resolve() != env.resolve():
            # Release before launching: the new process takes the same lock.
            sync.release_lock(lock)
            lock = None
            options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
            subprocess.Popen([str(interpreter(root, windowed=True)), "-B", str(root / "run_douyin_tt.py")],
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, **options)
            return
        from desktop_app import main as desktop_main
        desktop_main(directory)
    finally:
        if lock is not None:
            sync.release_lock(lock)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        show_error()
