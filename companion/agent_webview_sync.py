"""Persistent agent-webview login and authenticated LAN sync; never print credentials.

The inspected agent-webview 0.1.2 worker uses private_mode=True. Keep that worker
alive to retain HttpOnly cookies: its JS-based snapshot restore cannot restore
them. Hiding a window does not guarantee timers run during OS sleep/throttling.
"""
from __future__ import annotations

import argparse
import base64
import errno
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener

from browser_session import (DebugError, NoRedirect, ControllerClient, applicable_cookies,
                         cookie_header, direct_read, official_page, private_write,
                         profile_identity, read_json, SELF, COMMON)
from urllib.parse import urlencode

WINDOWS = os.name == "nt"
if WINDOWS:
    import msvcrt
    fcntl = None
else:
    import fcntl
    msvcrt = None

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / ".local-debug" / "sync"
HOME = "https://www.douyin.com/user/self?from_tab_name=main&showTab=post"
PORT = 18765
CONTROLLER_PORT = 8767
BACKGROUND_NOTE = "窗口隐藏仍复用同一会话；系统休眠或网页节流可能暂停执行。关闭浏览器进程后需重新登录。"
HEALTH_URL = "https://www.douyin.com" + SELF + "?" + urlencode(COMMON)
# agent-webview 0.1.2 command_script awaits expression Promises. Only this
# bounded summary crosses its bridge: neither the response body nor UID does.
PAGE_HEALTH = """(async () => {
  if (location.origin !== 'https://www.douyin.com') return {state:'login_required'};
  if (/验证码|安全验证/.test(document.title)) return {state:'login_required'};
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 22000);
  try {
    const response = await fetch(%s, {method:'GET', credentials:'include',
      cache:'no-store', redirect:'error', signal:controller.signal});
    if (response.status === 401 || response.status === 403) return {state:'login_required'};
    if (!response.ok) return {state:'network_error'};
    const text = await response.text();
    if (text.length > 4194304) return {state:'network_error'};
    let data;
    try { data = JSON.parse(text); } catch (_) { return {state:'network_error'}; }
    if (data && data.status_code === 0 && data.user && /^[1-9][0-9]*$/.test(String(data.user.uid)))
      return {state:'authenticated'};
    if (data && (data.status_code === 8 || data.status_code === 0)) return {state:'login_required'};
    return {state:'network_error'};
  } catch (_) { return {state:'network_error'}; }
  finally { clearTimeout(timer); }
})()""" % json.dumps(HEALTH_URL)
PAGE_METADATA = "({url:location.origin+location.pathname,user_agent:navigator.userAgent})"


class SyncError(DebugError):
    def __init__(self, code, message="同步暂不可用；未更改电视原账号。"):
        super().__init__(message)
        self.code = code


def private_directory(directory):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if linked_path(directory):
        raise SyncError("private_path", "私有目录不能是符号链接。")
    os.chmod(directory, 0o700)
    return directory


def linked_path(path):
    """Windows junctions/reparse points are not equivalent to private regular files."""
    path = Path(path)
    try:
        info = path.lstat()
    except FileNotFoundError:
        return False
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0)
                                            & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def private_json(path):
    path = Path(path)
    info = path.stat()
    # POSIX owner/mode bits are meaningful on Unix. Windows access is controlled by
    # the user's directory ACL; chmod does not implement Unix permission bits there.
    if linked_path(path) or (not WINDOWS and ((info.st_mode & 0o077) or info.st_uid != os.getuid())):
        raise SyncError("private_permissions", "运行文件必须由当前用户拥有且权限为 600。")
    return read_json(path)


def claim_lock(directory, name="sync.lock"):
    directory = private_directory(directory)
    if name not in {"sync.lock", "desktop.lock", "bootstrap.lock", "start.lock"}:
        raise SyncError("private_path")
    path = directory / name
    if linked_path(path):
        raise SyncError("private_path", "锁文件不能是符号链接。")
    fd = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        opened, current = os.fstat(fd), path.stat()
        if linked_path(path) or (opened.st_dev, opened.st_ino) != (current.st_dev, current.st_ino):
            raise SyncError("private_path")
        if WINDOWS:
            if opened.st_size == 0:
                os.write(fd, b"\0")
            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        else:
            os.fchmod(fd, 0o600)
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as error:
        os.close(fd)
        if error.errno in {errno.EACCES, errno.EAGAIN, errno.EDEADLK}:
            raise SyncError("already_running", "同步任务已运行；使用 status 查看或 stop 暂停。") from None
        raise
    except Exception:
        os.close(fd)
        raise
    return fd


def release_lock(fd):
    try:
        if WINDOWS:
            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
    finally:
        os.close(fd)


def raise_if_cancelled(stop_event):
    if stop_event is not None and stop_event.is_set():
        raise SyncError("cancelled", "已取消；原配置和浏览器会话已保留。")


def encode64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def decode64(value, length=None):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise SyncError("invalid_envelope")
    try:
        decoded = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except (ValueError, TypeError):
        raise SyncError("invalid_envelope") from None
    if encode64(decoded) != value or (length is not None and len(decoded) != length):
        raise SyncError("invalid_envelope")
    return decoded


def private_host(host):
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        raise SyncError("invalid_target", "电视地址必须是内网或回环 IP，不能使用域名或 URL。") from None
    networks = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
    if not (address.is_loopback or (address.version == 4 and any(address in n for n in networks))):
        raise SyncError("invalid_target", "只允许 RFC1918 内网或回环地址。")
    return "[" + str(address) + "]" if address.version == 6 else str(address)


def wire_json(url, body=None, timeout=45):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = Request(url, data=data, headers={"Accept": "application/json", "Content-Type": "application/json"})
    try:
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=timeout) as response:
            raw = response.read(256 * 1024 + 1)
        if len(raw) > 256 * 1024:
            raise SyncError("oversized_response")
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise SyncError("invalid_envelope")
        return result
    except SyncError:
        raise
    except HTTPError as error:
        raise SyncError("http_" + str(error.code)) from None
    except (URLError, TimeoutError, OSError, ValueError, UnicodeError):
        raise SyncError("transport") from None


def token_from_header(header):
    for part in header.split(";"):
        name, separator, value = part.strip().partition("=")
        if separator and name == "msToken":
            return value
    return ""


def timestamp():
    return datetime.now(timezone.utc).isoformat()


class PairingClient:
    """Ephemeral committed ECDH; only TV-local approval releases a sync key."""
    def __init__(self, host, *, port=PORT):
        if type(port) is not int or not 1 <= port <= 65535:
            raise SyncError("invalid_config")
        self.base = "http://" + private_host(host) + ":" + str(port)

    def pair(self, on_compare=None, stop_event=None):
        raise_if_cancelled(stop_event)
        try:
            from cryptography.exceptions import InvalidTag, UnsupportedAlgorithm
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import ec
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            from cryptography.hazmat.primitives.kdf.hkdf import HKDF
        except ImportError:
            raise SyncError("missing_crypto", "请安装 companion/requirements-sync.txt 中的加密依赖。") from None

        deadline = time.monotonic() + 120

        def exchange(operation, body):
            raise_if_cancelled(stop_event)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SyncError("pair_expired", "配对已过期；请在电视电脑同步页面重新发起。")
            try:
                result = wire_json(self.base + "/v1/pair/" + operation, body,
                                   timeout=min(3 if stop_event is not None else 10, remaining))
            except SyncError as error:
                raise_if_cancelled(stop_event)
                if operation == "begin":
                    messages = {
                        "http_404": ("pair_upgrade", "电视暂不支持免输入配对，请升级电视应用后重试。"),
                        "http_403": ("pair_closed", "请在电视开启电脑同步，并保持电脑同步页面在前台后重试。"),
                        "http_409": ("pair_busy", "电视已有待处理的配对请求；请先在电视拒绝或等待过期后重试。"),
                        "http_429": ("pair_rate_limited", "配对请求过于频繁；请稍后重新发起。"),
                    }
                    if error.code in messages:
                        raise SyncError(*messages[error.code]) from None
                raise
            raise_if_cancelled(stop_event)
            if time.monotonic() >= deadline:
                raise SyncError("pair_expired", "配对已过期；请重新发起。")
            if type(result.get("version")) is not int or result["version"] != 1:
                raise SyncError("invalid_pair_response")
            return result

        private = ec.generate_private_key(ec.SECP256R1())
        public = private.public_key().public_bytes(serialization.Encoding.DER,
                                                   serialization.PublicFormat.SubjectPublicKeyInfo)
        client_nonce = secrets.token_bytes(32)
        commitment = hashlib.sha256(b"mydv-pair/v1/commit\n" + public + client_nonce).digest()
        begin = exchange("begin", {"version": 1, "commitment": encode64(commitment)})
        if set(begin) != {"version", "receiver_id", "pair_id", "server_public", "server_nonce", "expires_in"}:
            raise SyncError("invalid_pair_response")
        receiver, pair_id = begin["receiver_id"], begin["pair_id"]
        if (not isinstance(receiver, str) or not re.fullmatch(r"[a-f0-9]{32}", receiver)
                or not isinstance(pair_id, str) or not re.fullmatch(r"[a-f0-9]{32}", pair_id)
                or type(begin["expires_in"]) is not int or not 1 <= begin["expires_in"] <= 120):
            raise SyncError("invalid_pair_response")
        deadline = min(deadline, time.monotonic() + begin["expires_in"])
        server_der = decode64(begin["server_public"])
        decode64(begin["server_nonce"], 32)
        try:
            server_public = serialization.load_der_public_key(server_der)
            if (not isinstance(server_public, ec.EllipticCurvePublicKey)
                    or not isinstance(server_public.curve, ec.SECP256R1)
                    or server_public.public_bytes(serialization.Encoding.DER,
                        serialization.PublicFormat.SubjectPublicKeyInfo) != server_der):
                raise ValueError("invalid public key")
            shared = private.exchange(ec.ECDH(), server_public)
        except (ValueError, TypeError, UnsupportedAlgorithm):
            raise SyncError("invalid_pair_key") from None
        transcript = "\n".join(("mydv-pair/v1", receiver, pair_id, encode64(public),
                                encode64(client_nonce), begin["server_public"], begin["server_nonce"])).encode("ascii")
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=hashlib.sha256(transcript).digest(),
                   info=b"mydv-pair/v1/key").derive(shared)
        digest = hmac.new(key, b"mydv-pair/v1/sas\n" + transcript, hashlib.sha256).digest()
        sas = str(int.from_bytes(digest[:4], "big") % 1000000).zfill(6)
        revealed = exchange("reveal", {"version": 1, "pair_id": pair_id,
                                       "client_public": encode64(public), "client_nonce": encode64(client_nonce)})
        if revealed != {"version": 1, "pair_id": pair_id, "status": "pending"}:
            raise SyncError("invalid_pair_response")
        if on_compare is None:
            print("配对核对数字：" + sas + "\n请核对电视显示完全相同的六位数字，再在电视点击“允许”；不一致请拒绝。",
                  flush=True)
        else:
            on_compare(sas, pair_id)
        raise_if_cancelled(stop_event)
        while True:
            result = exchange("status", {"version": 1, "pair_id": pair_id})
            if result.get("pair_id") != pair_id:
                raise SyncError("invalid_pair_response")
            status = result.get("status")
            if not isinstance(status, str):
                raise SyncError("invalid_pair_response")
            if status in {"pending", "rejected", "expired"}:
                if set(result) != {"version", "pair_id", "status"}:
                    raise SyncError("invalid_pair_response")
                if status != "pending":
                    raise SyncError("pair_" + status, "配对未获允许或已过期；原配置和浏览器会话已保留。")
                delay = min(1, max(0, deadline - time.monotonic()))
                if stop_event is None:
                    time.sleep(delay)
                else:
                    stop_event.wait(delay)
                raise_if_cancelled(stop_event)
                continue
            if status != "approved" or set(result) != {"version", "pair_id", "status", "nonce", "payload"}:
                raise SyncError("unauthenticated_pair_response")
            try:
                clear = AESGCM(key).decrypt(decode64(result["nonce"], 12), decode64(result["payload"]),
                                            b"mydv-pair/v1/approved\n" + transcript)
                approved = json.loads(clear)
            except (InvalidTag, ValueError, UnicodeError):
                raise SyncError("unauthenticated_pair_response") from None
            if (not isinstance(approved, dict) or set(approved) != {"receiver_id", "pair_id", "pairing_key"}
                    or approved["receiver_id"] != receiver or approved["pair_id"] != pair_id
                    or not isinstance(approved["pairing_key"], str)
                    or not re.fullmatch(r"[a-f0-9]{32}", approved["pairing_key"])):
                raise SyncError("unauthenticated_pair_response")
            raise_if_cancelled(stop_event)
            return {"receiver_id": receiver, "pairing_key": approved["pairing_key"]}


class ReceiverClient:
    def __init__(self, host, pairing_key, *, port=PORT, receiver_id=None,
                 last_generation=None, timeout=45):
        if not isinstance(pairing_key, bytes) or len(pairing_key) != 16:
            raise SyncError("invalid_pairing", "配对密钥必须为 32 位十六进制字符。")
        if type(port) is not int or not 1 <= port <= 65535 or timeout < 40:
            raise SyncError("invalid_config")
        if receiver_id is not None and not re.fullmatch(r"[a-f0-9]{32}", receiver_id):
            raise SyncError("invalid_receiver")
        if last_generation is not None and (type(last_generation) is not int or last_generation < 0):
            raise SyncError("invalid_generation")
        self.base = "http://" + private_host(host) + ":" + str(port)
        self.key, self.port, self.timeout = pairing_key, port, timeout
        self.receiver_id, self.last_generation = receiver_id, last_generation

    def challenge(self):
        result = wire_json(self.base + "/v1/challenge", timeout=self.timeout)
        receiver = result.get("receiver_id")
        if (type(result.get("version")) is not int or result["version"] != 1
                or not isinstance(receiver, str) or not re.fullmatch(r"[a-f0-9]{32}", receiver)
                or type(result.get("port")) is not int or result["port"] != self.port):
            raise SyncError("invalid_challenge")
        decode64(result.get("challenge"), 32)
        if self.receiver_id is not None and self.receiver_id != receiver:
            raise SyncError("receiver_changed", "电视接收器身份已变化，请重新配对。")
        return result

    def push(self, cookie, ms_token):
        # Crypto is lazy: opening an official login window needs no crypto package.
        try:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            from cryptography.exceptions import InvalidTag
        except ImportError:
            raise SyncError("missing_crypto", "请安装 companion/requirements-sync.txt 中的加密依赖。") from None
        if (not isinstance(cookie, str) or not cookie or len(cookie.encode("utf-8")) > 65536
                or any(ord(c) < 32 or ord(c) > 126 for c in cookie)
                or not isinstance(ms_token, str) or token_from_header(cookie) != ms_token):
            raise SyncError("invalid_candidate")
        challenge = self.challenge()
        receiver = challenge["receiver_id"]
        request_id, nonce = secrets.token_hex(16), secrets.token_bytes(12)
        aad = ("mydv-sync/v1\n" + receiver + "\n" + request_id).encode("utf-8")
        clear = json.dumps({"challenge": challenge["challenge"], "cookie": cookie,
                            "ms_token": ms_token}, separators=(",", ":")).encode("utf-8")
        encrypted = AESGCM(self.key).encrypt(nonce, clear, aad)
        result = wire_json(self.base + "/v1/credentials", {
            "version": 1, "request_id": request_id, "nonce": encode64(nonce),
            "payload": encode64(encrypted)}, timeout=self.timeout)
        if (type(result.get("version")) is not int or result["version"] != 1
                or result.get("request_id") != request_id):
            raise SyncError("unauthenticated_response")
        response_nonce = decode64(result.get("nonce"), 12)
        if response_nonce == nonce:
            raise SyncError("reused_nonce")
        try:
            payload = AESGCM(self.key).decrypt(response_nonce, decode64(result.get("payload")),
                ("mydv-sync/v1/response\n" + receiver + "\n" + request_id).encode("utf-8"))
            ack = json.loads(payload)
        except (InvalidTag, ValueError, UnicodeError):
            raise SyncError("unauthenticated_response") from None
        if (not isinstance(ack, dict) or ack.get("request_id") != request_id
                or ack.get("status") not in {"updated", "unchanged", "rejected"}
                or type(ack.get("generation")) is not int or ack["generation"] < 0
                or (self.last_generation is not None and ack["generation"] < self.last_generation)):
            raise SyncError("invalid_ack")
        self.receiver_id, self.last_generation = receiver, ack["generation"]
        return {"authenticated": True, "status": ack["status"], "generation": ack["generation"],
                "receiver_id": receiver, "request_id": request_id}


class SyncWebview(ControllerClient):
    def __init__(self, directory):
        self.directory = Path(directory)
        config = private_json(self.directory / "controller.json")
        if (config.get("proxy") or {}).get("enabled"):
            raise SyncError("controller_proxy", "同步控制器不能启用代理。")
        super().__init__(self.directory / "controller.json")

    def session(self, path="", body=None, method=None):
        session_id = private_json(self.directory / "session.json").get("session_id", "")
        if not isinstance(session_id, str) or not re.fullmatch(r"[a-f0-9]{32}", session_id):
            raise SyncError("invalid_session")
        return self.call("/sessions/" + session_id + path, body, method)

    def evaluate(self, code):
        return self.session("/javascript/evaluate", {"code": code, "timeout": 20})["value"]

    def native_cookies(self):
        values = self.session("/cookies").get("cookies")
        if not isinstance(values, list):
            raise SyncError("cookie_api")
        return applicable_cookies(values)

    def show(self):
        self.session("/window/show", {}, "POST")

    def hide(self):
        self.session("/window/hide", {}, "POST")

    def health(self):
        return self.session("/javascript/evaluate", {"code": PAGE_HEALTH, "timeout": 25})["value"]


def clean_environment():
    result = {k: v for k, v in os.environ.items()
              if not k.lower().endswith("proxy") and k not in {"AGENT_WEBVIEW_TOKEN", "AGENT_WEBVIEW_PROXY"}}
    result["NO_PROXY"] = "*"
    result["PYTHONDONTWRITEBYTECODE"] = "1"
    return result


def background_process_options():
    return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)} if WINDOWS else {"start_new_session": True}


def ensure_browser(directory, port=CONTROLLER_PORT, *, launch=True):
    directory = private_directory(directory)
    runtime = directory / "controller.json"
    web = None
    if runtime.exists():
        try:
            candidate = SyncWebview(directory)
            candidate.call("/sessions")
            web = candidate
        except (DebugError, OSError):
            if not launch:
                raise SyncError("controller_unavailable") from None
    if web is None:
        if not launch:
            raise SyncError("controller_unavailable")
        child = subprocess.Popen([sys.executable, "-B", "-m", "agent_webview", "--host", "127.0.0.1",
            "--port", str(port), "--runtime-file", str(runtime), "--runtime-dir", str(directory / "workers"),
            "--data-dir", str(directory / "browser-data"), "--log-level", "CRITICAL"],
            env=clean_environment(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            **background_process_options())
        for _ in range(100):
            if child.poll() is not None:
                raise SyncError("controller_start", "专属控制器启动失败；检查 agent-webview 安装与 8767 端口。")
            if runtime.exists():
                try:
                    candidate = SyncWebview(directory)
                    candidate.call("/sessions")
                    web = candidate
                    break
                except (DebugError, OSError):
                    pass
            time.sleep(0.1)
        if web is None:
            raise SyncError("controller_start")
    record = directory / "session.json"
    existing = None
    if record.exists():
        try:
            existing = web.session()
        except DebugError:
            # Confirm absence through the controller's session registry before replacing.
            saved_id = private_json(record).get("session_id")
            sessions = web.call("/sessions").get("sessions", [])
            if any(s.get("session_id") == saved_id and s.get("state") == "running" for s in sessions):
                raise SyncError("session_unavailable") from None
    if existing and existing.get("state") == "running" and not (existing.get("window") or {}).get("closed"):
        return web
    if not launch:
        raise SyncError("session_ended", "浏览器会话已结束，请运行 login 重新登录。")
    created = web.call("/sessions", {"url": HOME, "visible": True,
        "title": "抖音抬头版 · 电脑账号同步", "width": 1200, "height": 820})
    private_write(record, {"session_id": created["session_id"], "controller": web.base})
    for _ in range(80):
        if (web.session().get("window") or {}).get("ready"):
            web.session("/instrumentation", {"network": False, "mutations": False,
                "capture_request_bodies": False, "capture_response_bodies": False}, "PUT")
            return web
        time.sleep(0.25)
    raise SyncError("page_not_ready", "官方页面尚未就绪；原会话已保留，请稍后重试。")


def has_session(cookies):
    return any(c.get("name") in {"sessionid", "sessionid_ss"} and c.get("value") for c in cookies)


class Backoff:
    def __init__(self):
        self.failures = 0

    def failed(self):
        self.failures += 1
        return min(600, 15 * 2 ** min(self.failures - 1, 6))

    def reset(self):
        self.failures = 0


class SyncService:
    """One unchanged native browser session; no snapshots, reloads or synthetic activity."""
    def __init__(self, web, directory, config, receiver=None):
        self.web, self.directory, self.config = web, Path(directory), config
        self.receiver = receiver
        self.health_retry, self.push_retry = Backoff(), Backoff()
        self.last_state, self.last_health, self.last_success = "starting", None, None
        self.page_verified = False

    def report(self, state, **fields):
        self.last_state = state
        report = {"state": state, "observed_at": timestamp(), "last_health": self.last_health,
                  "last_success": self.last_success, "paired": self.receiver is not None,
                  "heartbeat_seconds": 120, "interval_seconds": self.config["interval"],
                  "background": BACKGROUND_NOTE}
        report.update(fields)
        private_write(self.directory / "status.json", report)
        return report

    def need_user(self, state):
        if self.last_state != state:
            try:
                self.web.show()
            except DebugError:
                pass
        self.page_verified = False
        return self.report(state, retry_seconds=self.health_retry.failed())

    def heartbeat(self):
        try:
            outcome = self.web.health()
            # Always read the native jar after fetch, including its Set-Cookie rotations.
            cookies = self.web.native_cookies()
        except (DebugError, OSError, KeyError, TypeError, ValueError):
            return self.need_user("browser_unresponsive")
        if not isinstance(outcome, dict):
            return self.need_user("browser_unresponsive")
        if outcome.get("state") == "login_required" or not has_session(cookies):
            return self.need_user("login_required")
        if outcome.get("state") != "authenticated":
            self.page_verified = False
            return self.report("network_error", retry_seconds=self.health_retry.failed())
        self.page_verified = True
        self.last_health = timestamp()
        self.health_retry.reset()
        if not self.config.get("keep_visible", False):
            try:
                self.web.hide()
            except DebugError:
                return self.need_user("browser_unresponsive")
        return self.report("healthy", retry_seconds=120)

    def synchronize(self):
        if not self.page_verified:
            return self.report("waiting_for_login", retry_seconds=120)
        if self.receiver is None:
            return self.report("healthy_unpaired", retry_seconds=self.config["interval"])
        try:
            metadata = self.web.evaluate(PAGE_METADATA)
            official_page(metadata)
            user_agent = metadata["user_agent"]
            if not isinstance(user_agent, str) or not user_agent or len(user_agent) > 2048:
                raise SyncError("invalid_agent")
            # A page-side rotation while the independent request is in flight
            # causes revalidation; never attach new cookies to an old proof.
            for _ in range(2):
                cookies = self.web.native_cookies()
                if not has_session(cookies):
                    return self.need_user("login_required")
                response = direct_read(cookies, user_agent, SELF)
                try:
                    profile_identity(response)
                except DebugError:
                    if response.get("status_code") in {0, 8}:
                        return self.need_user("login_required")
                    raise SyncError("profile_unavailable") from None
                header = cookie_header(cookies)
                if header == cookie_header(self.web.native_cookies()):
                    break
            else:
                raise SyncError("cookie_rotating")
            result = self.receiver.push(header, token_from_header(header))
            self.config["receiver_id"] = result["receiver_id"]
            self.config["generation"] = result["generation"]
            private_write(self.directory / "config.json", self.config)
            if result["status"] == "rejected":
                return self.report("receiver_rejected", retry_seconds=self.push_retry.failed())
            self.push_retry.reset()
            self.last_success = timestamp()
            return self.report(result["status"], generation=result["generation"], authenticated=True,
                               retry_seconds=self.config["interval"])
        except DebugError as error:
            # Shared direct_read deliberately returns safe errors, never raw responses.
            if "HTTP 401" in str(error) or "HTTP 403" in str(error):
                return self.need_user("login_required")
            return self.report("sync_unavailable", retry_seconds=self.push_retry.failed(),
                               error_code=getattr(error, "code", "official_request_unavailable"))
        except (OSError, KeyError, TypeError, ValueError):
            return self.report("sync_unavailable", retry_seconds=self.push_retry.failed())


def load_config(directory, args=None, *, on_compare=None, stop_event=None):
    raise_if_cancelled(stop_event)
    path = directory / "config.json"
    config = private_json(path) if path.exists() else {"interval": 600, "keep_visible": False}
    target_changed = False
    if args is not None:
        if args.ip is not None:
            target_changed = config.get("ip") != args.ip
            if target_changed:
                config.pop("pairing_key", None)
                config.pop("receiver_id", None)
                config.pop("generation", None)
            config["ip"] = args.ip
        if args.interval is not None:
            config["interval"] = args.interval
        config["keep_visible"] = args.keep_visible
        config["read_only"] = args.read_only
    if config.get("ip") not in (None, ""):
        private_host(config["ip"])
    elif not config.get("read_only", False):
        raise SyncError("missing_target", "首次同步请用 --ip 指定电视局域网地址。")
    if type(config.get("interval")) is not int or not 60 <= config["interval"] <= 86400:
        raise SyncError("invalid_config", "同步间隔必须介于 60 到 86400 秒。")
    if "pairing_key" in config and (not isinstance(config["pairing_key"], str)
            or not re.fullmatch(r"[a-f0-9]{32}", config["pairing_key"])):
        raise SyncError("invalid_pairing")
    if args is not None:
        if not args.read_only and (args.pair or target_changed or "pairing_key" not in config):
            paired = PairingClient(config["ip"]).pair(on_compare=on_compare, stop_event=stop_event)
            config.update(paired)
            config.pop("generation", None)
        raise_if_cancelled(stop_event)
        private_write(path, config)
    return config


def make_service(directory, *, launch=True):
    config = load_config(directory)
    receiver = None
    if not config.get("read_only") and config.get("pairing_key"):
        receiver = ReceiverClient(config["ip"], bytes.fromhex(config["pairing_key"]),
            receiver_id=config.get("receiver_id"), last_generation=config.get("generation"))
    return SyncService(ensure_browser(directory, launch=launch), directory, config, receiver)


def run_service(directory, stop_event=None):
    """Run under the caller's sync lock; stopping preserves the native browser session."""
    directory = Path(directory)
    stop_event = stop_event if stop_event is not None else threading.Event()
    def stopping():
        return stop_event.is_set() or (directory / "stop.json").exists()
    service = None
    action_worker = action_thread = None
    failed = False
    try:
        if stopping():
            return
        service = make_service(directory)
        # Independent polling must not delay the 120-second health/600-second sync loop.
        # Imports lazily to preserve login/read-only setup without crypto dependencies.
        from browser_actions import start_worker
        action_worker, action_thread = start_worker(service.web, directory, stop=stop_event)
        next_health = next_push = 0.0
        while not stopping():
            now = time.monotonic()
            if now >= next_health:
                result = service.heartbeat()
                next_health = time.monotonic() + result["retry_seconds"]
            if stopping():
                break
            if service.page_verified and now >= next_push:
                result = service.synchronize()
                next_push = time.monotonic() + result["retry_seconds"]
            stop_event.wait(0.5)
    except KeyboardInterrupt:
        pass
    except (DebugError, OSError, ValueError, TypeError, KeyError):
        failed = True
        private_write(directory / "status.json", {"state": "startup_failed", "observed_at": timestamp(),
                      "background": BACKGROUND_NOTE})
        return
    finally:
        if action_worker is not None:
            action_worker.stop.set()
            # Let an already claimed bounded action publish its result; never kill/retry it.
            action_thread.join()
        if service is not None and not failed:
            service.report("stopped", browser_retained=True)
        (directory / "stop.json").unlink(missing_ok=True)


def running(directory):
    try:
        fd = claim_lock(directory)
    except SyncError as error:
        if error.code == "already_running":
            return True
        raise
    release_lock(fd)
    return False


def startup_path(directory, nonce):
    if not isinstance(nonce, str) or not re.fullmatch(r"[a-f0-9]{32}", nonce):
        raise SyncError("startup_failed", "后台启动未完成，请重试。")
    return Path(directory) / (".startup-" + nonce + ".json")


def claim_startup(directory, nonce):
    """Child owns the service lock before acknowledging; it waits for parent acceptance."""
    path = startup_path(directory, nonce)
    fd = None
    deadline = time.monotonic() + 8
    try:
        while time.monotonic() < deadline:
            if private_json(path) != {"state": "pending"}:
                raise SyncError("startup_failed")
            try:
                fd = claim_lock(directory)
                break
            except SyncError as error:
                if error.code != "already_running":
                    raise
                time.sleep(0.05)
        if fd is None:
            raise SyncError("already_running")
        private_write(path, {"state": "locked", "pid": os.getpid()})
        while time.monotonic() < deadline:
            if private_json(path) == {"state": "accepted", "pid": os.getpid()}:
                path.unlink()
                return fd
            time.sleep(0.05)
        raise SyncError("startup_failed")
    except Exception as error:
        if fd is not None:
            release_lock(fd)
        if path.exists():
            private_write(path, {"state": "error", "pid": os.getpid(),
                                "code": "already_running" if isinstance(error, SyncError)
                                and error.code == "already_running" else "startup_failed"})
        raise


def start_background(directory, fd):
    """Consume the caller's service lock, either by inheritance or a Windows handoff."""
    command = [sys.executable, "-B", str(Path(__file__).resolve()), "--directory", str(directory), "_run"]
    if not WINDOWS:
        try:
            subprocess.Popen(command + ["--lock-fd", str(fd)], pass_fds=(fd,), env=clean_environment(),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        finally:
            release_lock(fd)
        return
    path = startup_path(directory, secrets.token_hex(16))
    accepted = False
    try:
        try:
            private_write(path, {"state": "pending"})
            child = subprocess.Popen(command + ["--startup", path.stem.removeprefix(".startup-")],
                env=clean_environment(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
                              | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
        finally:
            release_lock(fd)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            receipt = private_json(path)
            if receipt.get("pid") == child.pid:
                if receipt.get("state") == "locked":
                    private_write(path, {"state": "accepted", "pid": child.pid})
                    accepted = True
                    return
                if receipt.get("state") == "error":
                    if receipt.get("code") == "already_running":
                        raise SyncError("already_running", "同步任务已运行。")
                    raise SyncError("startup_failed", "后台启动未完成，请重试。")
            if child.poll() is not None:
                break
            time.sleep(0.05)
        raise SyncError("startup_failed", "后台启动未完成，请重试。")
    finally:
        if not accepted:
            path.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=PRIVATE)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("login", aliases=["open"])
    commands.add_parser("status")
    commands.add_parser("stop")
    for name in ("start", "once"):
        command = commands.add_parser(name)
        command.add_argument("--ip", help="电视局域网 IP；首次同步必填，之后沿用已保存地址")
        command.add_argument("--interval", type=int)
        command.add_argument("--pair", action="store_true", help="显示六位核对数字，在电视确认后配对，无需输入密钥")
        command.add_argument("--read-only", action="store_true", help="只保活，无需电视 IP，不配对、不推送")
        command.add_argument("--keep-visible", action="store_true", help="保留可见窗口，避免隐藏节流")
    internal = commands.add_parser("_run", help=argparse.SUPPRESS)
    internal.add_argument("--lock-fd", type=int)
    internal.add_argument("--startup")
    args = parser.parse_args(argv)
    try:
        args.directory = private_directory(args.directory.resolve())
        if args.command == "_run":
            if args.startup is not None and args.lock_fd is None:
                fd = claim_startup(args.directory, args.startup)
            elif args.lock_fd is not None and args.startup is None and not WINDOWS:
                fd = args.lock_fd
            else:
                raise SyncError("startup_failed", "后台启动参数无效。")
            try:
                run_service(args.directory)
            finally:
                release_lock(fd)
            return
        if args.command in {"login", "open"}:
            fd = None
            try:
                active = running(args.directory)
                if not active:
                    fd = claim_lock(args.directory)
                web = ensure_browser(args.directory, launch=not active)
                web.show()
                result = {"opened": True, "persistent_session": True,
                          "next": "在此官方网页与手机完成登录；配对电视后再启动同步。", "background": BACKGROUND_NOTE}
            finally:
                if fd is not None:
                    release_lock(fd)
        elif args.command in {"start", "once"}:
            fd = launch_fd = None
            try:
                if WINDOWS and args.command == "start":
                    launch_fd = claim_lock(args.directory, "start.lock")
                fd = claim_lock(args.directory)
                load_config(args.directory, args)
                (args.directory / "stop.json").unlink(missing_ok=True)
                if args.command == "once":
                    service = make_service(args.directory)
                    result = service.heartbeat()
                    if service.page_verified:
                        result = service.synchronize()
                else:
                    private_write(args.directory / "status.json", {"state": "starting", "observed_at": timestamp()})
                    inherited = fd
                    fd = None
                    start_background(args.directory, inherited)
                    result = {"started": True, "read_only": args.read_only, "background": BACKGROUND_NOTE}
            finally:
                if fd is not None:
                    release_lock(fd)
                if launch_fd is not None:
                    release_lock(launch_fd)
        elif args.command == "stop":
            active = running(args.directory)
            if active:
                private_write(args.directory / "stop.json", {"requested_at": timestamp()})
            result = {"stop_requested": active, "browser_retained": True,
                      "note": "仅停止同步；保留原生浏览器登录会话，下次 start/once 继续复用。"}
        else:
            result = {"configured": (args.directory / "config.json").exists(),
                      "running": running(args.directory),
                      "session_recorded": (args.directory / "session.json").exists(), "background": BACKGROUND_NOTE}
            if (args.directory / "status.json").exists():
                saved = private_json(args.directory / "status.json")
                for key in ("state", "last_health", "last_success", "generation", "observed_at", "paired", "error_code"):
                    if key in saved:
                        result[key] = saved[key]
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except KeyboardInterrupt:
        parser.exit(1, "已取消；浏览器会话和电视原账号已保留。\n")
    except (DebugError, OSError, ValueError, KeyError, TypeError) as error:
        message = str(error) if isinstance(error, SyncError) else "操作未完成；会话和电视原账号已保留。"
        parser.exit(1, message + "\n")


if __name__ == "__main__":
    main()
