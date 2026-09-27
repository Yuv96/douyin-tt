"""Synthetic HTTP fixtures only; execute in GitHub Actions, never against an account."""
import base64
import errno
from contextlib import ExitStack, redirect_stdout, redirect_stderr
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import stat
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch, Mock

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_webview_sync as sync
import browser_session as browser

KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
SESSION = "a" * 32
RECEIVER = "b" * 32
COOKIE_A = "sessionid=synthetic-account-a; msToken=synthetic-token-a"
COOKIE_B = "sessionid=synthetic-account-b; msToken=synthetic-token-b"


def cookies(label="a"):
    return [{"name": "sessionid", "value": "synthetic-account-" + label,
             "domain": ".douyin.com", "path": "/", "http_only": True, "secure": True},
            {"name": "msToken", "value": "synthetic-token-" + label,
             "domain": "www.douyin.com", "path": "/", "http_only": False, "secure": True}]


class HttpFixture:
    def __init__(self, route):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def process(self):
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    body = json.loads(self.rfile.read(length)) if length else None
                    code, value, headers = route(self.command, self.path, self.headers, body)
                except Exception:
                    code, value, headers = 500, {"error": "fixture handler failed"}, {}
                payload = json.dumps(value).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                for name, value in headers.items():
                    self.send_header(name, value)
                self.end_headers()
                self.wfile.write(payload)

            do_GET = do_POST = do_PUT = do_DELETE = process

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.base = "http://127.0.0.1:" + str(self.port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)


class ReceiverFixture:
    def __init__(self):
        self.identity = RECEIVER
        self.challenge = sync.encode64(b"c" * 32)
        self.saved, self.generation, self.mode = "synthetic-old", 0, "normal"
        self.requests, self.seen = [], set()
        self.previous_ack = None
        self.http = HttpFixture(self.route)

    def route(self, method, path, headers, body):
        if method == "GET" and path == "/v1/challenge":
            if self.mode == "redirect":
                return 302, {}, {"Location": self.redirect_url}
            return 200, {"version": 1, "receiver_id": self.identity,
                         "challenge": self.challenge, "port": self.http.port}, {}
        if method != "POST" or path != "/v1/credentials":
            return 404, {}, {}
        self.requests.append(body)
        request_id = body["request_id"]
        if request_id in self.seen:
            return 409, {}, {}
        self.seen.add(request_id)
        try:
            raw = AESGCM(KEY).decrypt(sync.decode64(body["nonce"], 12), sync.decode64(body["payload"]),
                ("mydv-sync/v1\n" + self.identity + "\n" + request_id).encode())
            payload = json.loads(raw)
        except Exception:
            return 403, {}, {}
        status = "rejected"
        if (payload["challenge"] == self.challenge and "invalid" not in payload["cookie"]
                and sync.token_from_header(payload["cookie"]) == payload["ms_token"]):
            status = "unchanged" if payload["cookie"] == self.saved else "updated"
            if status == "updated":
                self.saved = payload["cookie"]
                self.generation += 1
        if self.mode == "plain_success":
            return 200, {"status": "updated", "generation": 99}, {}
        if self.mode == "replay":
            return 200, self.previous_ack, {}
        nonce = secrets.token_bytes(12)
        encrypted = AESGCM(KEY).encrypt(nonce, json.dumps({"request_id": request_id, "status": status,
            "generation": self.generation}).encode(),
            ("mydv-sync/v1/response\n" + self.identity + "\n" + request_id).encode())
        if self.mode == "tampered":
            encrypted = bytes([encrypted[0] ^ 1]) + encrypted[1:]
        ack = {"version": 1, "request_id": request_id, "nonce": sync.encode64(nonce),
               "payload": sync.encode64(encrypted)}
        self.previous_ack = ack
        return 200, ack, {}

    def client(self):
        return sync.ReceiverClient("127.0.0.1", KEY, port=self.http.port)


class PairingFixture:
    """Independent protocol peer: public routes cannot approve the request."""
    def __init__(self, mode="normal"):
        self.mode, self.requests = mode, []
        self.pair_id = "e" * 32
        self.server_nonce = b"s" * 32
        curve = ec.SECP384R1() if mode == "wrong_curve" else ec.SECP256R1()
        self.private = ec.generate_private_key(curve)
        self.public = self.private.public_key().public_bytes(serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo)
        self.approved, self.sas = False, None
        self.http = HttpFixture(self.route)

    def route(self, method, path, headers, body):
        self.requests.append((method, path, body))
        if method != "POST":
            return 404, {}, {}
        if path == "/v1/pair/begin":
            if self.mode.startswith("http_"):
                return int(self.mode[5:]), {}, {}
            if set(body) != {"version", "commitment"}:
                return 400, {}, {}
            self.commitment = sync.decode64(body["commitment"], 32)
            public = self.public + b"trailing" if self.mode == "noncanonical_key" else self.public
            return 200, {"version": 1, "receiver_id": RECEIVER, "pair_id": self.pair_id,
                "server_public": sync.encode64(public), "server_nonce": sync.encode64(self.server_nonce),
                "expires_in": 120}, {}
        if body.get("pair_id") != self.pair_id:
            return 403, {}, {}
        if path == "/v1/pair/reveal":
            if set(body) != {"version", "pair_id", "client_public", "client_nonce"}:
                return 400, {}, {}
            public = sync.decode64(body["client_public"])
            nonce = sync.decode64(body["client_nonce"], 32)
            if hashlib.sha256(b"mydv-pair/v1/commit\n" + public + nonce).digest() != self.commitment:
                return 403, {}, {}
            peer = serialization.load_der_public_key(public)
            self.transcript = "\n".join(("mydv-pair/v1", RECEIVER, self.pair_id, body["client_public"],
                body["client_nonce"], sync.encode64(self.public), sync.encode64(self.server_nonce))).encode("ascii")
            shared = self.private.exchange(ec.ECDH(), peer)
            self.key = HKDF(algorithm=hashes.SHA256(), length=32,
                salt=hashlib.sha256(self.transcript).digest(), info=b"mydv-pair/v1/key").derive(shared)
            digest = hmac.new(self.key, b"mydv-pair/v1/sas\n" + self.transcript, hashlib.sha256).digest()
            self.sas = "%06d" % (int.from_bytes(digest[:4], "big") % 1000000)
            return 200, {"version": 1, "pair_id": self.pair_id, "status": "pending"}, {}
        if path != "/v1/pair/status" or set(body) != {"version", "pair_id"}:
            return 404, {}, {}
        result = {"version": 1, "pair_id": self.pair_id, "status": "pending"}
        if self.mode in {"rejected", "expired"}:
            result["status"] = self.mode
        elif self.approved:
            result["status"] = "approved"
            if self.mode == "plain_approved":
                return 200, result, {}
            nonce = b"n" * 12
            clear = {"receiver_id": RECEIVER, "pair_id": self.pair_id, "pairing_key": KEY.hex()}
            if self.mode == "wrong_receiver":
                clear["receiver_id"] = "f" * 32
            if self.mode == "wrong_pair":
                clear["pair_id"] = "f" * 32
            aad = b"mydv-pair/v1/approved\n" + self.transcript
            if self.mode == "wrong_transcript":
                aad += b"changed"
            payload = AESGCM(self.key).encrypt(nonce, json.dumps(clear).encode(), aad)
            if self.mode == "tampered":
                payload = bytes([payload[0] ^ 1]) + payload[1:]
            result.update(nonce=sync.encode64(nonce), payload=sync.encode64(payload))
        return 200, result, {}

    def client(self):
        return sync.PairingClient("127.0.0.1", port=self.http.port)


class BrowserFixture:
    def __init__(self):
        self.jar, self.next_jar = cookies(), None
        self.state = "authenticated"
        self.created, self.shown, self.hidden = 0, 0, 0
        self.existing = False
        self.calls, self.codes = [], []
        self.http = HttpFixture(self.route)

    def description(self):
        return {"session_id": SESSION, "state": "running", "window": {
            "ready": True, "closed": False, "url": sync.HOME, "visible": self.shown > self.hidden}}

    def route(self, method, path, headers, body):
        self.calls.append((method, path))
        if headers.get("Authorization") != "Bearer synthetic-controller":
            return 401, {}, {}
        if path == "/v1/sessions":
            if method == "POST":
                if body.get("url") != sync.HOME or body.get("cookies") or body.get("snapshot_id"):
                    return 400, {}, {}
                self.created += 1
                self.existing = True
                return 201, self.description(), {}
            return 200, {"sessions": [self.description()] if self.existing else []}, {}
        prefix = "/v1/sessions/" + SESSION
        if not self.existing or not path.startswith(prefix):
            return 404, {}, {}
        operation = path[len(prefix):]
        if not operation:
            return 200, self.description(), {}
        if operation == "/cookies" and method == "GET":
            return 200, {"cookies": self.jar, "url": sync.HOME}, {}
        if operation == "/window/show":
            self.shown += 1
            return 200, {}, {}
        if operation == "/window/hide":
            self.hidden += 1
            return 200, {}, {}
        if operation == "/instrumentation":
            return (200 if body.get("network") is False else 400), {}, {}
        if operation == "/javascript/evaluate":
            code = body["code"]
            self.codes.append(code)
            if "document.cookie" in code:
                return 400, {}, {}
            if code == sync.PAGE_METADATA:
                return 200, {"value": {"url": sync.HOME, "user_agent": "Synthetic browser"}}, {}
            if code != sync.PAGE_HEALTH:
                return 400, {}, {}
            if self.state == "unresponsive":
                return 504, {}, {}
            if self.next_jar is not None:
                self.jar, self.next_jar = self.next_jar, None
            return 200, {"value": {"state": self.state}}, {}
        # No test permits navigate, DELETE cookies, restore or snapshots.
        return 400, {}, {}


class Fixtures(unittest.TestCase):
    def setUp(self):
        self.contexts = ExitStack()
        self.addCleanup(self.contexts.close)
        self.directory = Path(self.contexts.enter_context(tempfile.TemporaryDirectory())) / "sync"
        sync.private_directory(self.directory)

    def fixture(self, value):
        self.addCleanup(value.http.close)
        return value


class PairingTests(Fixtures):
    def approve_matching(self, server):
        def compare(sas, pair_id):
            self.assertRegex(sas, r"^[0-9]{6}$")
            self.assertEqual((sas, pair_id), (server.sas, server.pair_id))
            server.approved = True
        return compare

    def test_committed_ecdh_roundtrip_requires_matching_local_comparison(self):
        server = self.fixture(PairingFixture())
        result = server.client().pair(self.approve_matching(server))
        self.assertEqual(result, {"receiver_id": RECEIVER, "pairing_key": KEY.hex()})
        requests = server.requests
        self.assertEqual([r[1] for r in requests],
            ["/v1/pair/begin", "/v1/pair/reveal", "/v1/pair/status"])
        self.assertEqual(set(requests[0][2]), {"version", "commitment"})
        reveal = requests[1][2]
        self.assertEqual(sync.decode64(requests[0][2]["commitment"]), hashlib.sha256(
            b"mydv-pair/v1/commit\n" + sync.decode64(reveal["client_public"])
            + sync.decode64(reveal["client_nonce"])).digest())
        self.assertNotIn(KEY.hex(), json.dumps(requests))
        self.assertNotIn("cookie", json.dumps(requests))

    def test_pending_waits_for_tv_approval_without_sending_credentials(self):
        server = self.fixture(PairingFixture())
        comparisons = []
        with patch.object(sync.time, "sleep", side_effect=lambda _: setattr(server, "approved", True)):
            result = server.client().pair(lambda sas, pair_id: comparisons.append((sas, pair_id)))
        self.assertEqual(comparisons, [(server.sas, server.pair_id)])
        self.assertEqual(result["pairing_key"], KEY.hex())
        self.assertEqual([r[1] for r in server.requests].count("/v1/pair/status"), 2)

    def test_cancel_before_begin_or_at_comparison_never_accepts_a_key(self):
        stop = threading.Event()
        stop.set()
        with patch.object(sync, "wire_json") as wire, self.assertRaises(sync.SyncError) as failure:
            sync.PairingClient("10.0.0.2").pair(stop_event=stop)
        self.assertEqual(failure.exception.code, "cancelled")
        wire.assert_not_called()
        stop.clear()
        server = self.fixture(PairingFixture())
        with self.assertRaises(sync.SyncError) as failure:
            server.client().pair(lambda *_: stop.set(), stop_event=stop)
        self.assertEqual(failure.exception.code, "cancelled")
        self.assertEqual([request[1] for request in server.requests], ["/v1/pair/begin", "/v1/pair/reveal"])

    def test_cancel_interrupts_the_pending_pair_wait(self):
        stop = threading.Event()
        server = self.fixture(PairingFixture())
        def cancel_wait(_):
            stop.set()
            return True
        with patch.object(stop, "wait", side_effect=cancel_wait), self.assertRaises(sync.SyncError) as failure:
            server.client().pair(lambda *_: None, stop_event=stop)
        self.assertEqual(failure.exception.code, "cancelled")
        self.assertEqual([request[1] for request in server.requests].count("/v1/pair/status"), 1)

    def test_plain_approved_tampering_and_identity_substitution_never_release_key(self):
        for mode in ("plain_approved", "tampered", "wrong_receiver", "wrong_pair", "wrong_transcript"):
            with self.subTest(mode=mode):
                server = self.fixture(PairingFixture(mode))
                with self.assertRaises(sync.SyncError) as error:
                    server.client().pair(self.approve_matching(server))
                self.assertEqual(error.exception.code, "unauthenticated_pair_response")

    def test_rejected_expired_and_client_deadline_fail_closed(self):
        for mode in ("rejected", "expired"):
            with self.subTest(mode=mode):
                server = self.fixture(PairingFixture(mode))
                with self.assertRaises(sync.SyncError) as error:
                    server.client().pair(lambda *_: None)
                self.assertEqual(error.exception.code, "pair_" + mode)
        server = self.fixture(PairingFixture())
        now = [100.0]
        with patch.object(sync.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaises(sync.SyncError) as error:
                server.client().pair(lambda *_: now.__setitem__(0, 221.0))
        self.assertEqual(error.exception.code, "pair_expired")
        self.assertFalse(any(r[1] == "/v1/pair/status" for r in server.requests))

    def test_wrong_curve_and_noncanonical_der_are_rejected_before_reveal(self):
        for mode in ("wrong_curve", "noncanonical_key"):
            with self.subTest(mode=mode):
                server = self.fixture(PairingFixture(mode))
                compare = Mock()
                with self.assertRaises(sync.SyncError):
                    server.client().pair(compare)
                compare.assert_not_called()
                self.assertEqual(len(server.requests), 1)

    def test_begin_errors_are_actionable_without_reveal_or_fallback(self):
        for status, code in ((403, "pair_closed"), (404, "pair_upgrade"),
                             (409, "pair_busy"), (429, "pair_rate_limited")):
            with self.subTest(status=status):
                server = self.fixture(PairingFixture("http_" + str(status)))
                with self.assertRaises(sync.SyncError) as error:
                    server.client().pair(Mock())
                self.assertEqual(error.exception.code, code)
                self.assertEqual(len(server.requests), 1)

    def test_modifying_reveal_breaks_commitment(self):
        server = self.fixture(PairingFixture())
        actual = sync.wire_json

        def alter(url, body, timeout):
            if url.endswith("/reveal"):
                body = dict(body, client_nonce=sync.encode64(b"x" * 32))
            return actual(url, body, timeout)

        compare = Mock()
        with patch.object(sync, "wire_json", side_effect=alter), self.assertRaises(sync.SyncError):
            server.client().pair(compare)
        compare.assert_not_called()
        self.assertIsNone(server.sas)


class ProtocolTests(Fixtures):
    def test_actual_http_roundtrip_rotation_and_rejection_preserve_previous(self):
        server = self.fixture(ReceiverFixture())
        client = server.client()
        first = client.push(COOKIE_A, "synthetic-token-a")
        self.assertEqual((first["status"], first["generation"]), ("updated", 1))
        self.assertEqual(client.push(COOKIE_A, "synthetic-token-a")["status"], "unchanged")
        second = client.push(COOKIE_B, "synthetic-token-b")
        self.assertEqual((second["status"], second["generation"]), ("updated", 2))
        rejected = client.push("sessionid=invalid-fixture", "")
        self.assertEqual((rejected["status"], rejected["generation"]), ("rejected", 2))
        self.assertEqual(server.saved, COOKIE_B)
        self.assertNotIn("synthetic-account", json.dumps(server.requests))
        self.assertEqual(len({r["request_id"] for r in server.requests}), 4)

    def test_unauthenticated_tampered_and_replayed_responses_never_succeed(self):
        server = self.fixture(ReceiverFixture())
        client = server.client()
        client.push(COOKIE_A, "synthetic-token-a")
        previous = server.previous_ack
        for mode in ("plain_success", "tampered", "replay"):
            with self.subTest(mode=mode):
                server.mode, server.previous_ack = mode, previous
                with self.assertRaises(sync.SyncError):
                    client.push(COOKIE_A, "synthetic-token-a")
        self.assertEqual(client.last_generation, 1)

    def test_request_replay_and_wrong_pairing_do_not_replace_receiver(self):
        server = self.fixture(ReceiverFixture())
        server.client().push(COOKIE_A, "synthetic-token-a")
        replay = server.requests[-1]
        with self.assertRaises(sync.SyncError):
            sync.wire_json(server.http.base + "/v1/credentials", replay)
        wrong = sync.ReceiverClient("127.0.0.1", b"x" * 16, port=server.http.port)
        with self.assertRaises(sync.SyncError):
            wrong.push(COOKIE_B, "synthetic-token-b")
        self.assertEqual(server.saved, COOKIE_A)

    def test_fixed_receiver_identity_ms_token_and_header_validation(self):
        server = self.fixture(ReceiverFixture())
        client = server.client()
        client.push(COOKIE_A, "synthetic-token-a")
        for header, token in [(COOKIE_A, "wrong"), ("sessionid=x\r\nInjected: x", "")]:
            with self.assertRaises(sync.SyncError):
                client.push(header, token)
        server.identity = "d" * 32
        with self.assertRaises(sync.SyncError) as error:
            client.push(COOKIE_A, "synthetic-token-a")
        self.assertEqual(error.exception.code, "receiver_changed")
        self.assertEqual(len(server.requests), 1)

    def test_no_environment_proxy_redirect_or_public_target(self):
        server = self.fixture(ReceiverFixture())
        with patch.dict(os.environ, {"http_proxy": "http://127.0.0.1:1", "HTTP_PROXY": "http://127.0.0.1:1"}):
            self.assertTrue(server.client().push(COOKIE_A, "synthetic-token-a")["authenticated"])
        seen = []
        redirected = HttpFixture(lambda *args: (seen.append(True) or 200, {}, {}))
        self.addCleanup(redirected.close)
        server.mode, server.redirect_url = "redirect", redirected.base
        with self.assertRaises(sync.SyncError):
            server.client().push(COOKIE_A, "synthetic-token-a")
        self.assertEqual(seen, [])
        for target in ("203.0.113.2", "169.254.1.2", "100.64.0.1", "example.com", "http://10.0.0.2", "10.0.0.2:80"):
            with self.subTest(target=target), self.assertRaises(sync.SyncError):
                sync.ReceiverClient(target, KEY)


class PersistentBrowserTests(Fixtures):
    def setUp(self):
        super().setUp()
        self.browser = self.fixture(BrowserFixture())
        sync.private_write(self.directory / "controller.json", {"base_url": self.browser.http.base,
            "token": "synthetic-controller", "proxy": {"enabled": False}})
        self.web = sync.ensure_browser(self.directory, port=self.browser.http.port)
        self.receiver = self.fixture(ReceiverFixture())
        self.config = {"ip": "127.0.0.1", "interval": 600, "keep_visible": False,
                       "pairing_key": KEY.hex()}
        self.service = sync.SyncService(self.web, self.directory, self.config, self.receiver.client())
        self.profile_reads = []

        def profile_route(method, path, headers, body):
            self.profile_reads.append(headers.get("Cookie"))
            uid = "101" if headers.get("Cookie") == COOKIE_A else "202"
            return 200, {"status_code": 0, "user": {"uid": uid}}, {}

        official = HttpFixture(profile_route)
        self.addCleanup(official.close)
        self.contexts.enter_context(patch.object(sync, "direct_read", side_effect=lambda values, agent, path:
            browser.request_json(official.base + path, {"Cookie": browser.cookie_header(values), "User-Agent": agent})))

    def test_same_native_session_rotates_cookie_and_token_without_refresh_or_restore(self):
        self.assertEqual(self.service.heartbeat()["state"], "healthy")
        self.assertEqual(self.service.synchronize()["state"], "updated")
        self.browser.next_jar = cookies("b")
        self.assertEqual(self.service.heartbeat()["state"], "healthy")
        self.assertEqual(self.service.synchronize()["state"], "updated")
        self.assertEqual(self.receiver.saved, COOKIE_B)
        self.assertEqual(self.profile_reads, [COOKIE_A, COOKIE_B])
        self.assertEqual(self.browser.created, 1)
        reattached = sync.ensure_browser(self.directory, port=self.browser.http.port)
        self.assertEqual(reattached.session()["session_id"], SESSION)
        self.assertEqual(self.browser.created, 1)
        self.assertTrue(all(code in {sync.PAGE_HEALTH, sync.PAGE_METADATA} for code in self.browser.codes))
        self.assertIn("credentials:'include'", sync.PAGE_HEALTH)
        self.assertIn("redirect:'error'", sync.PAGE_HEALTH)
        self.assertFalse(any("navigate" in path or "restore" in path or method == "DELETE"
                             for method, path in self.browser.calls))
        self.assertGreaterEqual(self.browser.hidden, 2)
        for path in self.directory.glob("*.json"):
            if not sync.WINDOWS:
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertNotIn("synthetic-account", path.read_text(encoding="utf-8"))

    def test_lost_login_preserves_receiver_and_shows_existing_window_with_backoff(self):
        self.service.heartbeat()
        self.service.synchronize()
        before = self.receiver.saved
        self.browser.state = "login_required"
        self.assertEqual(self.service.heartbeat()["retry_seconds"], 15)
        self.assertEqual(self.service.heartbeat()["retry_seconds"], 30)
        self.assertEqual(self.service.synchronize()["state"], "waiting_for_login")
        self.assertEqual(self.receiver.saved, before)
        self.assertEqual(self.browser.shown, 1)
        self.assertEqual(self.browser.created, 1)

    def test_network_failure_does_not_force_scan_but_hidden_js_failure_shows_window(self):
        self.service.heartbeat()
        self.browser.state = "network_error"
        self.assertEqual(self.service.heartbeat()["state"], "network_error")
        self.assertEqual(self.browser.shown, 0)
        self.browser.state = "unresponsive"
        self.assertEqual(self.service.heartbeat()["state"], "browser_unresponsive")
        self.assertEqual(self.browser.shown, 1)
        self.assertEqual(self.browser.created, 1)

    def test_outside_browser_verification_failure_never_pushes_candidate(self):
        self.service.heartbeat()
        with patch.object(sync, "direct_read", return_value={"status_code": 8}):
            self.assertEqual(self.service.synchronize()["state"], "login_required")
        self.assertEqual(self.receiver.requests, [])
        self.assertEqual(self.receiver.saved, "synthetic-old")

    def test_native_rotation_during_verification_requires_new_verification(self):
        self.service.heartbeat()
        seen = []

        def rotate(values, agent, path):
            seen.append(browser.cookie_header(values))
            self.browser.jar = cookies("b")
            return {"status_code": 0, "user": {"uid": "101"}}

        with patch.object(sync, "direct_read", side_effect=rotate):
            self.assertEqual(self.service.synchronize()["state"], "updated")
        self.assertEqual(seen, [COOKIE_A, COOKIE_B])
        self.assertEqual(self.receiver.saved, COOKIE_B)

    def test_read_only_health_needs_no_receiver_pairing(self):
        service = sync.SyncService(self.web, self.directory, self.config)
        self.assertEqual(service.heartbeat()["state"], "healthy")
        self.assertEqual(service.synchronize()["state"], "healthy_unpaired")
        self.assertEqual(self.receiver.requests, [])


class LocalStateTests(Fixtures):
    def pretend_background(self, directory, fd):
        sync.release_lock(fd)

    def test_stop_during_heartbeat_does_not_begin_another_push(self):
        service = Mock(page_verified=True)
        worker, thread = Mock(), Mock()
        worker.stop = threading.Event()

        def heartbeat():
            sync.private_write(self.directory / "stop.json", {"requested": True})
            return {"retry_seconds": 120}

        service.heartbeat.side_effect = heartbeat
        with patch.object(sync, "make_service", return_value=service), \
                patch("browser_actions.start_worker", return_value=(worker, thread)):
            sync.run_service(self.directory)
        service.synchronize.assert_not_called()
        service.report.assert_called_once_with("stopped", browser_retained=True)
        self.assertTrue(worker.stop.is_set())
        thread.join.assert_called_once_with()

    def test_gui_event_stops_service_and_action_worker_before_another_push(self):
        stop = threading.Event()
        service = Mock(page_verified=True)
        worker, thread = Mock(stop=stop), Mock()
        def heartbeat():
            stop.set()
            return {"retry_seconds": 120}
        service.heartbeat.side_effect = heartbeat
        with patch.object(sync, "make_service", return_value=service), \
                patch("browser_actions.start_worker", return_value=(worker, thread)) as start:
            sync.run_service(self.directory, stop)
        start.assert_called_once_with(service.web, self.directory, stop=stop)
        service.synchronize.assert_not_called()
        service.report.assert_called_once_with("stopped", browser_retained=True)
        thread.join.assert_called_once_with()
        with patch.object(sync, "make_service") as make:
            sync.run_service(self.directory, stop)
        make.assert_not_called()

    def test_gui_pair_callback_is_forwarded_and_cancelled_config_is_not_saved(self):
        original = {"ip": "10.0.0.2", "interval": 600, "pairing_key": KEY.hex(), "receiver_id": RECEIVER}
        path = self.directory / "config.json"
        sync.private_write(path, original)
        before = path.read_bytes()
        stop, compared = threading.Event(), Mock()
        args = SimpleNamespace(ip=None, interval=None, keep_visible=True, read_only=False, pair=True)
        def pair(on_compare, stop_event):
            on_compare("123456", "c" * 32)
            stop_event.set()
            return {"pairing_key": "d" * 32, "receiver_id": "e" * 32}
        with patch.object(sync.PairingClient, "pair", side_effect=pair) as pairing, \
                self.assertRaises(sync.SyncError) as failure:
            sync.load_config(self.directory, args, on_compare=compared, stop_event=stop)
        self.assertEqual(failure.exception.code, "cancelled")
        pairing.assert_called_once_with(on_compare=compared, stop_event=stop)
        compared.assert_called_once_with("123456", "c" * 32)
        self.assertEqual(path.read_bytes(), before)

    def test_lock_is_single_instance_and_config_is_private(self):
        fd = sync.claim_lock(self.directory)
        try:
            with self.assertRaises(sync.SyncError) as error:
                sync.claim_lock(self.directory)
            self.assertEqual(error.exception.code, "already_running")
        finally:
            sync.release_lock(fd)
        self.assertFalse(sync.running(self.directory))
        if not sync.WINDOWS:
            self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)

    def test_named_gui_bootstrap_and_start_locks_remain_independent(self):
        held = []
        try:
            for name in ("sync.lock", "desktop.lock", "bootstrap.lock", "start.lock"):
                held.append(sync.claim_lock(self.directory, name))
                with self.assertRaises(sync.SyncError) as failure:
                    sync.claim_lock(self.directory, name)
                self.assertEqual(failure.exception.code, "already_running")
            with self.assertRaises(sync.SyncError):
                sync.claim_lock(self.directory, "../outside.lock")
        finally:
            for fd in held:
                sync.release_lock(fd)

    def test_windows_lock_uses_one_byte_without_posix_only_calls(self):
        windows = SimpleNamespace(LK_NBLCK=1, LK_UNLCK=0, locking=Mock())
        with patch.object(sync, "WINDOWS", True), patch.object(sync, "msvcrt", windows), \
                patch.object(sync.os, "fchmod", side_effect=AssertionError("POSIX-only"), create=True), \
                patch.object(sync.os, "getuid", side_effect=AssertionError("POSIX-only"), create=True):
            fd = sync.claim_lock(self.directory)
            self.assertEqual(os.fstat(fd).st_size, 1)
            windows.locking.assert_called_once_with(fd, windows.LK_NBLCK, 1)
            sync.release_lock(fd)
            windows.locking.assert_called_with(fd, windows.LK_UNLCK, 1)
            sync.private_write(self.directory / "state.json", {"fixture": True})
            self.assertEqual(sync.private_json(self.directory / "state.json"), {"fixture": True})
        with self.assertRaises(OSError):
            os.fstat(fd)

    @unittest.skipIf(sync.WINDOWS, "Unix owner/mode and symlink security")
    def test_posix_private_mode_and_symlink_refusal_are_preserved(self):
        path = self.directory / "state.json"
        path.write_text('{}')
        path.chmod(0o644)
        with self.assertRaises(sync.SyncError):
            sync.private_json(path)
        path.chmod(0o600)
        (self.directory / "sync.lock").symlink_to(path)
        with self.assertRaises(sync.SyncError):
            sync.claim_lock(self.directory)

    def test_windows_background_handoff_never_passes_file_descriptors(self):
        child = Mock(pid=4321)
        def spawn(command, **kwargs):
            nonce = command[command.index("--startup") + 1]
            sync.private_write(sync.startup_path(self.directory, nonce), {"state": "locked", "pid": child.pid})
            return child
        with patch.object(sync, "WINDOWS", True), patch.object(sync, "release_lock") as release, \
                patch.object(sync.subprocess, "Popen", side_effect=spawn) as process:
            sync.start_background(self.directory, 42)
        release.assert_called_once_with(42)
        self.assertNotIn("pass_fds", process.call_args.kwargs)
        self.assertNotIn("start_new_session", process.call_args.kwargs)
        self.assertIn("creationflags", process.call_args.kwargs)
        receipt = next(self.directory.glob('.startup-*.json'))
        self.assertEqual(sync.private_json(receipt), {"state": "accepted", "pid": child.pid})

    def test_child_waits_for_parent_acceptance_while_owning_service_lock(self):
        nonce = "b" * 32
        path = sync.startup_path(self.directory, nonce)
        sync.private_write(path, {"state": "pending"})
        write = sync.private_write
        def parent_accepts(target, value):
            if value.get("state") == "locked":
                with self.assertRaises(sync.SyncError) as failure:
                    sync.claim_lock(self.directory)
                self.assertEqual(failure.exception.code, "already_running")
                value = {"state": "accepted", "pid": value["pid"]}
            write(target, value)
        with patch.object(sync, "private_write", side_effect=parent_accepts):
            fd = sync.claim_startup(self.directory, nonce)
        try:
            self.assertTrue(sync.running(self.directory))
            self.assertFalse(path.exists())
        finally:
            sync.release_lock(fd)

    def test_failed_child_start_releases_lock_and_removes_handshake(self):
        with patch.object(sync, "WINDOWS", True), patch.object(sync, "release_lock") as release, \
                patch.object(sync.subprocess, "Popen", return_value=Mock(pid=4321, poll=Mock(return_value=1))), \
                self.assertRaises(sync.SyncError) as failure:
            sync.start_background(self.directory, 42)
        self.assertEqual(failure.exception.code, "startup_failed")
        release.assert_called_once_with(42)
        self.assertEqual(list(self.directory.glob('.startup-*.json')), [])

    def test_native_browser_process_options_hide_windows_console(self):
        with patch.object(sync, "WINDOWS", True):
            self.assertEqual(set(sync.background_process_options()), {"creationflags"})
        with patch.object(sync, "WINDOWS", False):
            self.assertEqual(sync.background_process_options(), {"start_new_session": True})

    def test_pairing_key_is_private_and_never_in_process_arguments_or_output(self):
        output = io.StringIO()
        with patch.object(sync.PairingClient, "pair", return_value={"pairing_key": KEY.hex(),
                "receiver_id": RECEIVER}) as pair, \
                patch.object(sync, "start_background", side_effect=self.pretend_background) as process, redirect_stdout(output):
            sync.main(["--directory", str(self.directory), "start", "--ip", "10.0.0.2"])
        pair.assert_called_once_with(on_compare=None, stop_event=None)
        self.assertNotIn(KEY.hex(), output.getvalue())

        self.assertNotIn(KEY.hex(), str(process.call_args))
        config = sync.private_json(self.directory / "config.json")
        self.assertEqual(config["ip"], "10.0.0.2")
        self.assertEqual(config["pairing_key"], KEY.hex())
        self.assertEqual(config["receiver_id"], RECEIVER)
        if not sync.WINDOWS:
            self.assertEqual(stat.S_IMODE((self.directory / "config.json").stat().st_mode), 0o600)
        self.assertEqual(config["interval"], 600)
        output = io.StringIO()
        with redirect_stdout(output):
            sync.main(["--directory", str(self.directory), "status"])
        self.assertNotIn(KEY.hex(), output.getvalue())

    def test_failed_pairing_preserves_saved_config_and_never_touches_browser_or_pushes(self):
        original = {"ip": "127.0.0.1", "interval": 600, "keep_visible": False,
                    "pairing_key": KEY.hex(), "receiver_id": RECEIVER, "generation": 7}
        path = self.directory / "config.json"
        sync.private_write(path, original)
        before = path.read_bytes()
        with patch.object(sync.PairingClient, "pair", side_effect=sync.SyncError("pair_rejected")), \
                patch.object(sync, "make_service") as make_service, \
                patch.object(sync.subprocess, "Popen") as process, \
                redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
            sync.main(["--directory", str(self.directory), "once", "--pair", "--ip", "127.0.0.2"])
        self.assertEqual(path.read_bytes(), before)
        make_service.assert_not_called()
        process.assert_not_called()

    def test_new_target_requires_new_pairing_and_drops_old_generation(self):
        sync.private_write(self.directory / "config.json", {"ip": "127.0.0.1", "interval": 600,
            "pairing_key": KEY.hex(), "receiver_id": RECEIVER, "generation": 7})
        with patch.object(sync, "PairingClient") as client, \
                patch.object(sync, "start_background", side_effect=self.pretend_background), redirect_stdout(io.StringIO()):
            client.return_value.pair.return_value = {"pairing_key": "f" * 32, "receiver_id": "c" * 32}
            sync.main(["--directory", str(self.directory), "start", "--ip", "127.0.0.2"])
        client.assert_called_once_with("127.0.0.2")
        saved = sync.private_json(self.directory / "config.json")
        self.assertEqual(saved["pairing_key"], "f" * 32)
        self.assertEqual(saved["receiver_id"], "c" * 32)
        self.assertNotIn("generation", saved)

    def test_read_only_start_does_not_ask_for_secret_and_stop_never_kills_browser(self):
        with patch.object(sync.PairingClient, "pair") as prompt, \
                patch.object(sync, "start_background", side_effect=self.pretend_background), redirect_stdout(io.StringIO()):
            sync.main(["--directory", str(self.directory), "start", "--read-only"])
        prompt.assert_not_called()
        config = sync.private_json(self.directory / "config.json")
        self.assertTrue(config["read_only"])
        self.assertNotIn("ip", config)
        web = Mock()
        web.health.return_value = {"state": "authenticated"}
        web.native_cookies.return_value = cookies()
        with patch.object(sync, "ensure_browser", return_value=web), \
                patch.object(sync, "ReceiverClient") as receiver:
            service = sync.make_service(self.directory)
            self.assertIsNone(service.receiver)
            health = service.heartbeat()
            self.assertEqual(health["state"], "healthy")
            self.assertFalse(health["paired"])
            self.assertEqual(service.synchronize()["state"], "healthy_unpaired")
        receiver.assert_not_called()
        fd = sync.claim_lock(self.directory)
        try:
            with redirect_stdout(io.StringIO()):
                sync.main(["--directory", str(self.directory), "stop"])
            self.assertTrue((self.directory / "stop.json").exists())
        finally:
            sync.release_lock(fd)

    def test_first_sync_requires_explicit_target_before_pairing_or_browser_work(self):
        for command in ("start", "once"):
            with self.subTest(command=command), patch.object(sync, "PairingClient") as pair, \
                    patch.object(sync, "ensure_browser") as browser, \
                    patch.object(sync, "start_background") as process, \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as error:
                with self.assertRaises(SystemExit) as stopped:
                    sync.main(["--directory", str(self.directory), command])
                self.assertEqual(stopped.exception.code, 1)
                self.assertIn("--ip", error.getvalue())
                self.assertFalse((self.directory / "config.json").exists())
                pair.assert_not_called()
                browser.assert_not_called()
                process.assert_not_called()

    def test_readonly_configuration_without_target_cannot_become_a_writer(self):
        path = self.directory / "config.json"
        sync.private_write(path, {"interval": 600, "keep_visible": False, "read_only": True})
        before = path.read_bytes()
        with patch.object(sync, "PairingClient") as pair, patch.object(sync, "start_background") as process, \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            sync.main(["--directory", str(self.directory), "start"])
        self.assertEqual(path.read_bytes(), before)
        pair.assert_not_called()
        process.assert_not_called()

    def test_start_reuses_saved_target_and_pairing_without_ip_argument(self):
        config = {"ip": "10.0.0.2", "interval": 600, "pairing_key": KEY.hex(),
                  "receiver_id": RECEIVER, "generation": 7}
        sync.private_write(self.directory / "config.json", config)
        with patch.object(sync, "PairingClient") as pair, \
                patch.object(sync, "start_background", side_effect=self.pretend_background) as process, \
                redirect_stdout(io.StringIO()):
            sync.main(["--directory", str(self.directory), "start"])
        pair.assert_not_called()
        process.assert_called_once()
        saved = sync.private_json(self.directory / "config.json")
        for key, value in config.items():
            self.assertEqual(saved[key], value)


if __name__ == "__main__":
    unittest.main()
