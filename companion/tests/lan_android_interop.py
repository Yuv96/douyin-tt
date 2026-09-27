"""GitHub-only production Python client -> forwarded API21 receiver interoperability.

The runner starts the TV fixture with external_sender=true and forwards port
18765 before invoking this script. Only synthetic credentials are used. This
file intentionally has no test_ prefix and is not a unittest discovery target.
"""

import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_webview_sync import PairingClient, ReceiverClient, SyncError
from browser_actions import BrowserClient, session_binding, validate_job


HOST = "127.0.0.1"
PORT = 18765
PAIR_KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
A = "sessionid=fixture-lan-a; msToken=token-a"
B = "sessionid=fixture-lan-b; msToken=token-b"
C = "sessionid=fixture-lan-c; msToken=token-c"


class InteropFailure(Exception):
    """A fixed failure category; never attach a response or credential value."""


def require(condition, category):
    if not condition:
        raise InteropFailure(category)


def authenticated_result(result, status, receiver_id, request_ids):
    require(isinstance(result, dict), "response-shape")
    require(result.get("authenticated") is True, "response-not-authenticated")
    require(result.get("status") == status, "response-status")
    generation = result.get("generation")
    require(type(generation) is int and generation >= 0, "response-generation")
    identity = result.get("receiver_id")
    require(isinstance(identity, str) and re.fullmatch(r"[a-f0-9]{32}", identity),
            "response-identity")
    require(receiver_id is None or identity == receiver_id, "receiver-changed")
    request_id = result.get("request_id")
    require(isinstance(request_id, str) and re.fullmatch(r"[a-f0-9]{32}", request_id),
            "response-request")
    require(request_id not in request_ids, "request-reused")
    request_ids.add(request_id)
    return identity, generation


def final_challenge(receiver_id):
    # C rejection starts the fixture's completion grace period. Do not send
    # another push: check reachability and stable identity with one quick GET.
    connection = http.client.HTTPConnection(HOST, PORT, timeout=5)
    try:
        connection.request("GET", "/v1/challenge")
        response = connection.getresponse()
        require(response.status == 200, "challenge-status")
        body = response.read(16385)
        require(len(body) <= 16384, "challenge-size")
        value = json.loads(body)
        require(isinstance(value, dict), "challenge-shape")
        require(value.get("version") == 1 and value.get("port") == PORT,
                "challenge-version")
        require(value.get("receiver_id") == receiver_id, "challenge-identity")
        challenge = value.get("challenge")
        require(isinstance(challenge, str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", challenge),
                "challenge-value")
    finally:
        connection.close()


def browser_interop(pairing, receiver_id):
    """Exercise production BrowserClient against API21; never call an official write executor."""
    browser = BrowserClient(HOST, bytes.fromhex(pairing["pairing_key"]), receiver_id, port=PORT)
    binding = session_binding([{"name": "sessionid", "value": "fixture-lan-a"}])
    require(browser.poll(binding, ["like"]) is None, "browser-initial-queue-not-empty")
    trigger = subprocess.run(["adb", "shell", "am", "broadcast", "-a",
                    "com.dycomment.tv.TEST_BROWSER_ACTION", "-n",
                    "com.dycomment.tv.android5/com.dycomment.tv.LanPairingConfirmationReceiver"],
                   check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    require(re.search(r"Broadcast completed: result=1(?:\D|$)", trigger.stdout) is not None,
            "browser-fixture-trigger-rejected")
    deadline = time.monotonic() + 10
    job = None
    while job is None and time.monotonic() < deadline:
        job = browser.poll(binding, ["like"])
        if job is None:
            time.sleep(0.1)
    require(job is not None, "browser-fixture-job-missing")
    validate_job(job)
    require(job["kind"] == "like" and job["session_binding"] == binding
            and job["args"] == {"video_id": "123", "enabled": True}, "browser-fixture-job-mismatch")
    require(browser.poll(binding, ["like"]) is None, "browser-job-reclaimed")
    synthetic_result = {"ok": True, "data": {"confirmed": True, "enabled": True}, "error_code": ""}
    require(browser.result(job, synthetic_result) is True, "browser-fixture-result-rejected")
    require(browser.result(job, synthetic_result) is False, "browser-fixture-duplicate-result-accepted")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        evidence = subprocess.run(["adb", "logcat", "-d", "-s", "Android5LanSyncTest:I", "AndroidRuntime:E"],
                                  check=True, timeout=5, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True).stdout
        require("FAIL" not in evidence and "FATAL EXCEPTION" not in evidence, "browser-fixture-runtime-failed")
        if "PASS API21_BROWSER_ACTION_FROM_PYTHON" in evidence:
            print("PASS BROWSER_ANDROID_INTEROP", flush=True)
            return
        time.sleep(0.1)
    raise InteropFailure("browser-fixture-confirmation-missing")


def main():
    require(os.environ.get("GITHUB_ACTIONS") == "true", "github-actions-required")
    comparisons = []

    def confirm_fixture(sas, pair_id):
        require(re.fullmatch(r"[0-9]{6}", sas) is not None, "pairing-sas-shape")
        require(re.fullmatch(r"[a-f0-9]{32}", pair_id) is not None, "pairing-id-shape")
        # The test-only Activity compares both values to its local pending request.
        # This receiver is absent from production; no HTTP API can grant approval.
        broadcast = subprocess.run(["adb", "shell", "am", "broadcast", "-a",
                        "com.dycomment.tv.TEST_PAIR_CONFIRM", "-n",
                        "com.dycomment.tv.android5/com.dycomment.tv.LanPairingConfirmationReceiver",
                        "--es", "pair_id", pair_id, "--es", "sas", sas],
                       check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                       text=True)
        require(re.search(r"Broadcast completed: result=1(?:\D|$)", broadcast.stdout) is not None,
                "pairing-local-comparison-not-approved")
        comparisons.append(True)

    pairing = PairingClient(HOST, port=PORT).pair(on_compare=confirm_fixture)
    require(comparisons == [True], "pairing-local-comparison-required")
    require(pairing.get("pairing_key") == PAIR_KEY.hex(), "pairing-key-mismatch")
    require(isinstance(pairing.get("receiver_id"), str), "pairing-receiver-missing")
    print("PASS LAN_ANDROID_NO_TYPING_PAIRING", flush=True)
    wrong = ReceiverClient(HOST, bytes.fromhex("ffffffffffffffffffffffffffffffff"),
                           port=PORT, timeout=45)
    try:
        wrong.push(A, "token-a")
    except SyncError:
        pass
    else:
        raise InteropFailure("wrong-key-accepted")

    client = ReceiverClient(HOST, bytes.fromhex(pairing["pairing_key"]), port=PORT,
                            receiver_id=pairing["receiver_id"], timeout=45)
    requests = set()
    receiver_id, first = authenticated_result(client.push(A, "token-a"), "updated", None, requests)
    browser_interop(pairing, receiver_id)
    _, unchanged = authenticated_result(client.push(A, "token-a"), "unchanged", receiver_id, requests)
    require(unchanged == first, "unchanged-generation-moved")
    _, second = authenticated_result(client.push(B, "token-b"), "updated", receiver_id, requests)
    require(second > first, "updated-generation-did-not-advance")
    _, rejected = authenticated_result(client.push(C, "token-c"), "rejected", receiver_id, requests)
    require(rejected == second, "rejected-generation-moved")
    final_challenge(receiver_id)

    print("status=wrong-key-rejected")
    for status, generation in (("updated", first), ("unchanged", unchanged),
                               ("updated", second), ("rejected", rejected)):
        print(f"status={status} generation={generation}")
    print("status=receiver-stable")
    print("PASS LAN_ANDROID_INTEROP", flush=True)


if __name__ == "__main__":
    try:
        main()
    except InteropFailure as failure:
        # Categories above are fixed strings. Never print exceptions returned by
        # HTTP/crypto/client code, which could otherwise expose a raw payload.
        print("status=" + str(failure), file=sys.stderr, flush=True)
        sys.exit(1)
    except SyncError as failure:
        print("status=" + failure.code, file=sys.stderr, flush=True)
        sys.exit(1)
    except Exception:
        print("status=interop-failed", file=sys.stderr, flush=True)
        sys.exit(1)
