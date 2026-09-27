"""Session safety fixtures; execute only in GitHub Actions."""
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from contextlib import ExitStack
from datetime import datetime, timezone
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import browser_session as browser


NOW = datetime(2026, 9, 26, tzinfo=timezone.utc).timestamp()


def cookie(name="sessionid", **changes):
    value = {"name": name, "value": "synthetic-fixture", "domain": ".douyin.com",
             "path": "/", "secure": True, "http_only": True}
    value.update(changes)
    return value


class OfflineTest(unittest.TestCase):
    def setUp(self):
        self.contexts = ExitStack()
        self.addCleanup(self.contexts.close)
        # Any accidentally unmocked request fails before connecting.
        for target in ("socket.socket.connect", "socket.create_connection"):
            self.enterContext(patch(target, side_effect=AssertionError("network forbidden")))
        self.enterContext(patch.object(browser.time, "time", return_value=NOW))

    def enterContext(self, context):
        # unittest.TestCase.enterContext was only added in Python 3.11.
        return self.contexts.enter_context(context)


class CookieTests(OfflineTest):
    def test_domain_path_boundaries_and_longest_path_first(self):
        values = [cookie("root"), cookie("host", domain="www.douyin.com"),
                  cookie("specific", path="/aweme/v1/web/user"),
                  cookie("wrong_host", domain="login.douyin.com"),
                  cookie("host_only_root", domain="douyin.com"),
                  cookie("suffix_attack", domain=".evil-douyin.com"),
                  cookie("path_prefix", path="/aweme/v1/web/users"),
                  cookie("path_suffix", path=browser.SELF + "extra")]
        selected = browser.applicable_cookies(values)
        self.assertEqual([c["name"] for c in selected], ["specific", "root", "host"])
        self.assertTrue(all(c["http_only"] for c in selected))
        self.assertEqual(browser.applicable_cookies([cookie()], "douyin.com.evil.example"), [])
        self.assertEqual(browser.applicable_cookies([cookie(path="/exact")], path="/exact"),
                         [cookie(path="/exact")])
        self.assertEqual(browser.applicable_cookies([cookie(path="/exact")], path="/exactly"), [])

    def test_expiry_formats_include_wkwebview_nsdate(self):
        future = NOW + 86400
        valid = [None, future, str(future), "2026-11-25 03:18:24 +0000",
                 "Wed, 25 Nov 2026 03:18:24 GMT", "2026-11-25T03:18:24Z"]
        for expires in valid:
            with self.subTest(expires=expires):
                self.assertEqual(len(browser.applicable_cookies([cookie(expires=expires)])), 1)
        for expires in [NOW, NOW - 1, "2026-01-01 03:18:24 +0000",
                        "2026-11-25T03:18:24", "not a date"]:
            with self.subTest(expires=expires):
                self.assertEqual(browser.applicable_cookies([cookie(expires=expires)]), [])

    def test_cookie_header_excludes_injection_and_keeps_httponly(self):
        values = [cookie(), cookie("bad name"), cookie("bad", value="a; extra=b"),
                  cookie("newline", value="a\r\nInjected:yes")]
        self.assertEqual(browser.cookie_header(values), "sessionid=synthetic-fixture")


class IdentityTests(OfflineTest):
    def test_requires_integer_zero_status_and_positive_numeric_uid(self):
        for uid in ["123", 123]:
            self.assertEqual(browser.profile_identity({"status_code": 0, "user": {"uid": uid}}),
                             "123")
        for status in [None, False, True, "0", 0.0, 1]:
            with self.subTest(status=status):
                with self.assertRaises(browser.DebugError):
                    browser.profile_identity({"status_code": status, "user": {"uid": "123"}})
        for user in [None, [], {}, {"uid": "0"}, {"uid": -1}, {"uid": True},
                     {"uid": "123.0"}, {"uid": " 123"}, {"uid": "123abc"}]:
            with self.subTest(user=user):
                with self.assertRaises(browser.DebugError):
                    browser.profile_identity({"status_code": 0, "user": user})


class PrivateFileTests(OfflineTest):
    def setUp(self):
        super().setUp()
        self.private = Path(self.enterContext(tempfile.TemporaryDirectory())) / "private"
        self.private.mkdir(mode=0o700)
        self.saved = self.private / "state.json"
        self.saved.write_bytes(b'{"previous":"synthetic-account"}')
        self.saved.chmod(0o600)

    def test_atomic_write_is_private_and_keeps_httponly_data(self):
        previous = self.saved.read_bytes()
        replace = os.replace
        value = {"cookies": [cookie()]}

        def inspect_replace(source, destination):
            source, destination = Path(source), Path(destination)
            self.assertEqual(destination, self.saved)
            self.assertEqual(source.parent, self.private)
            self.assertEqual(destination.read_bytes(), previous)
            self.assertEqual(stat.S_IMODE(source.stat().st_mode), 0o600)
            self.assertEqual(json.loads(source.read_text()), value)
            replace(source, destination)

        with patch.object(browser.os, "replace", side_effect=inspect_replace) as atomic:
            browser.private_write(self.saved, value)
        atomic.assert_called_once()
        self.assertEqual(json.loads(self.saved.read_text()), value)
        self.assertEqual(stat.S_IMODE(self.saved.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.private.stat().st_mode), 0o700)
        self.assertEqual(list(self.private.glob(".pending-*")), [])

    def test_failed_replace_preserves_old_state_and_removes_temporary(self):
        previous = self.saved.read_bytes()
        with patch.object(browser.os, "replace", side_effect=OSError("synthetic disk failure")):
            with self.assertRaises(OSError):
                browser.private_write(self.saved, {"new": "state"})
        self.assertEqual(self.saved.read_bytes(), previous)
        self.assertEqual(list(self.private.glob(".pending-*")), [])


class BoundaryTests(OfflineTest):
    def test_controller_rejects_remote_targets_and_url_credentials(self):
        urls = ["http://example.com", "https://localhost", "http://127.0.0.1.evil.example",
                "http://user:secret@localhost", "http://@localhost", "http://localhost/path",
                "http://localhost?token=secret", "http://localhost/#secret"]
        for url in urls:
            with self.subTest(url=url), patch.object(browser, "read_json", return_value={
                    "base_url": url, "token": "synthetic-controller"}):
                with self.assertRaises(browser.DebugError):
                    browser.ControllerClient(Path("unused-runtime.json"))
        for url in ["http://127.0.0.1:1234", "http://localhost:1234/", "http://[::1]:1234"]:
            with self.subTest(url=url), patch.object(browser, "read_json", return_value={
                    "base_url": url, "token": "synthetic-controller"}):
                self.assertEqual(browser.ControllerClient(Path("unused-runtime.json")).base, url.rstrip("/"))

    def test_official_page_rejects_foreign_origins_and_userinfo(self):
        for url in ["http://www.douyin.com/", "https://www.douyin.com.evil.example/",
                    "https://user@www.douyin.com/", "https://@www.douyin.com/"]:
            with self.subTest(url=url), self.assertRaises(browser.DebugError):
                browser.official_page({"url": url})


if __name__ == "__main__":
    unittest.main()
