"""Private session storage, scoped cookies and bounded HTTP for the companion."""

import json
import os
import re
import tempfile
import time
from datetime import datetime
from email.utils import parsedate_to_datetime
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

ORIGIN = "https://www.douyin.com"
SELF = "/aweme/v1/web/user/profile/self/"
COMMON = {
    "device_platform": "webapp", "aid": "6383", "channel": "channel_pc_web",
    "version_code": "170400", "version_name": "17.4.0", "cookie_enabled": "true",
}


class DebugError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def private_write(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".pending-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise DebugError("无法读取本地运行文件；请先启动电脑同步。") from None


def request_json(url, headers=None, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    request = Request(url, data=data, headers=headers or {}, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        # Do not send controller tokens through environment proxies or redirects.
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=35) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise DebugError("接口响应超出 4 MiB 限制。")
            try:
                result = json.loads(raw)
            except (ValueError, UnicodeError):
                raise DebugError("接口未返回有效 JSON，可能处于验证中间页。") from None
            if not isinstance(result, dict):
                raise DebugError("接口响应不是 JSON 对象。")
            return result
    except HTTPError as error:
        raise DebugError(f"接口返回 HTTP {error.code}；未跟随重定向。") from None
    except (URLError, TimeoutError, OSError):
        raise DebugError("接口连接失败或超时；检查服务、DNS 和网络。") from None


class ControllerClient:
    def __init__(self, runtime):
        config = read_json(runtime)
        base = config.get("base_url", "")
        parsed = urlsplit(base)
        if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
                or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment
                or parsed.path not in {"", "/"}):
            raise DebugError("控制器地址必须是本机 HTTP 地址。")
        self.base = base.rstrip("/")
        self.headers = {"Authorization": "Bearer " + config["token"]}

    def call(self, path, body=None, method=None):
        return request_json(self.base + "/v1" + path, self.headers, body, method)


def official_page(info):
    parsed = urlsplit(info.get("url", ""))
    if (parsed.scheme != "https" or parsed.hostname != "www.douyin.com" or parsed.port not in {None, 443}
            or parsed.username is not None or parsed.password is not None):
        raise DebugError("请先在 www.douyin.com 官方页面完成登录。")


def applicable_cookies(cookies, host="www.douyin.com", path=SELF):
    selected = []
    for cookie in cookies:
        domain = (cookie.get("domain") or "").lower()
        # A leading dot permits subdomains; a host-only cookie is exact-match.
        matches = (host == domain or (domain.startswith(".") and
                   (host == domain[1:] or host.endswith(domain))))
        cookie_path = cookie.get("path") or "/"
        if not matches or not (path == cookie_path or path.startswith(cookie_path.rstrip("/") + "/")):
            continue
        expires = cookie.get("expires")
        if expires:
            try:
                deadline = float(expires)
            except (TypeError, ValueError):
                try:
                    deadline = parsedate_to_datetime(str(expires)).timestamp()
                except (ValueError, TypeError, OverflowError):
                    try:
                        # WKWebView exposes NSDate as "2026-11-25 03:18:24 +0000".
                        deadline = datetime.strptime(str(expires), "%Y-%m-%d %H:%M:%S %z").timestamp()
                    except ValueError:
                        try:
                            parsed = datetime.fromisoformat(str(expires).replace("Z", "+00:00"))
                            if parsed.tzinfo is None:
                                continue
                            deadline = parsed.timestamp()
                        except ValueError:
                            continue
            if deadline <= time.time():
                continue
        name, value = cookie.get("name", ""), cookie.get("value", "")
        if (not re.fullmatch(r"[A-Za-z0-9_.-]+", name)
                or any(ord(c) < 33 or ord(c) > 126 or c == ";" for c in value)):
            continue
        selected.append(cookie)
    return sorted(selected, key=lambda c: -len(c.get("path") or "/"))


def cookie_header(cookies, host="www.douyin.com", path=SELF):
    return "; ".join(c["name"] + "=" + c["value"] for c in applicable_cookies(cookies, host, path))


def profile_identity(response):
    user = response.get("user")
    if (type(response.get("status_code")) is not int or response["status_code"] != 0
            or not isinstance(user, dict) or not re.fullmatch(r"[1-9][0-9]*", str(user.get("uid", "")))):
        raise DebugError("账号接口未确认有效登录；现有凭证未替换。")
    return str(user["uid"])


def direct_read(cookies, user_agent, path, values=None, host="www.douyin.com"):
    params = dict(COMMON)
    params.update(values or {})
    return request_json("https://" + host + path + "?" + urlencode(params), {
        "User-Agent": user_agent, "Referer": ORIGIN + "/", "Accept": "application/json",
        "Cookie": cookie_header(cookies, host, path),
    })
