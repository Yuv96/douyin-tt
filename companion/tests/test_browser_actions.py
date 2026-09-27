"""CI-only synthetic peer/browser fixtures. Never connects to a real account."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import browser_actions as actions
import agent_webview_sync as sync

KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
RECEIVER = "00112233445566778899aabbccddeeff"
REQUEST_ID = "ffeeddccbbaa99887766554433221100"
NONCE = bytes.fromhex("000102030405060708090a0b")
BINDING = "a" * 64
CHALLENGE = sync.encode64(b"c" * 32)


def job(kind="like", **args):
    if not args:
        args = ({"user_id": "123", "sec_uid": "synthetic-sec-uid", "enabled": True}
                if kind == "follow" else {"video_id": "123", "enabled": True})
        if kind == "dislike":
            args.pop("enabled")
    return {"job_id": "b" * 32, "kind": kind, "args": args,
            "session_binding": BINDING, "expires_in_ms": 35000}


class Peer:
    def __init__(self):
        self.clear = []
        self.mode = "normal"
        self.counter = 10

    def __call__(self, url, body=None, timeout=0):
        if url.endswith("/v1/challenge"):
            return {"version": 1, "receiver_id": RECEIVER, "port": 18765, "challenge": CHALLENGE}
        request_id = body["request_id"]
        nonce = sync.decode64(body["nonce"])
        plain = AESGCM(KEY).decrypt(nonce, sync.decode64(body["payload"]),
                    ("mydv-browser/v1/request\n" + RECEIVER + "\n" + request_id).encode())
        self.clear.append(json.loads(plain))
        ack = {"request_id": request_id}
        ack.update({"job": None} if url.endswith("/poll") else {"accepted": True})
        if self.mode == "wrong_id":
            ack["request_id"] = "0" * 32
        if self.mode == "extra_clear":
            ack["extra"] = True
        response_nonce = self.counter.to_bytes(12, "big")
        self.counter += 1
        if self.mode == "same_nonce":
            response_nonce = nonce
        aad_receiver = "0" * 32 if self.mode == "wrong_receiver" else RECEIVER
        payload = AESGCM(KEY).encrypt(response_nonce, actions.encoded(ack),
                    ("mydv-browser/v1/response\n" + aad_receiver + "\n" + request_id).encode())
        result = {"version": 1, "request_id": request_id,
                  "nonce": sync.encode64(response_nonce), "payload": sync.encode64(payload)}
        if self.mode == "extra_outer":
            result["extra"] = True
        return result


class ProtocolTests(unittest.TestCase):
    def test_authenticated_round_trip_and_fixed_aad(self):
        peer = Peer()
        client = actions.BrowserClient("127.0.0.1", KEY, RECEIVER, transport=peer)
        with patch.object(actions.secrets, "token_hex", return_value=REQUEST_ID), \
                patch.object(actions.secrets, "token_bytes", return_value=NONCE):
            self.assertIsNone(client.poll(BINDING, ["like", "dislike"]))
        self.assertEqual(peer.clear, [{"session_binding": BINDING, "capabilities": ["like", "dislike"],
                                       "challenge": CHALLENGE}])
        self.assertTrue(client.result(job(), {"ok": True, "data": {"confirmed": True, "enabled": True},
                                              "error_code": ""}))
        self.assertEqual(set(peer.clear[-1]), {"challenge", "job_id", "session_binding", "ok", "data", "error_code"})

    def test_response_authentication_and_strict_schema(self):
        for mode in ("wrong_id", "extra_clear", "same_nonce", "wrong_receiver", "extra_outer"):
            peer = Peer()
            peer.mode = mode
            client = actions.BrowserClient("127.0.0.1", KEY, RECEIVER, transport=peer)
            with self.subTest(mode=mode), self.assertRaises(sync.SyncError):
                client.poll(BINDING, ["like"])

    def test_conflicting_cookies_and_fallback_binding(self):
        cookies = [{"name": "sessionid_ss", "value": "synthetic"}]
        fallback = actions.session_binding(cookies)
        cookies.append({"name": "sessionid", "value": "synthetic"})
        self.assertEqual(actions.session_binding(cookies), fallback)
        cookies.append({"name": "sessionid", "value": "another"})
        with self.assertRaises(sync.SyncError):
            actions.session_binding(cookies)

    def test_job_and_result_validation(self):
        for change in ({"extra": True}, {"expires_in_ms": True}, {"expires_in_ms": 35001},
                       {"args": {"video_id": "123", "enabled": 1}}, {"kind": "arbitrary"},
                       {"args": {"video_id": "123", "enabled": True, "url": "https://example.com"}}):
            value = job()
            value.update(change)
            with self.subTest(change=change), self.assertRaises(sync.SyncError):
                actions.validate_job(value)
        for result in ({"ok": True, "data": {"confirmed": True, "enabled": False}, "error_code": ""},
                       {"ok": True, "data": {"confirmed": True, "enabled": True, "token": "x"}, "error_code": ""},
                       {"ok": False, "data": {}, "error_code": "raw private error"}):
            with self.assertRaises(sync.SyncError):
                actions.validate_result(result, job())
        with self.assertRaises(ValueError):
            actions.strict_json('{"a":1,"a":2}')

    def test_comments_have_their_own_bounded_read_result(self):
        read_job = job("comments", video_id="123", cursor=0, count=20)
        actions.validate_job(read_job)
        data = {"comments": [{"cid": "1", "text": "字" * 1000, "digg_count": -1,
                              "user": {"nickname": "读者", "avatar_thumb": {"url_list": []}}}] * 20,
                "cursor": 20, "has_more": True}
        result = {"ok": True, "data": data, "error_code": ""}
        self.assertIs(actions.validate_result(result, read_job), result)
        self.assertGreater(len(actions.encoded(result)), 16384)
        self.assertLess(len(actions.encoded(result)), actions.LIMIT)
        data["cursor"] = 0
        with self.assertRaises(sync.SyncError):
            actions.validate_result(result, read_job)
        with self.assertRaises(sync.SyncError):
            actions.validate_job(job("comments", video_id="123", cursor=0, count=21))


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        sync.private_write(self.directory / "config.json", {"ip": "127.0.0.1", "pairing_key": KEY.hex(),
            "receiver_id": RECEIVER, "read_only": False})
        self.web = Mock()
        self.web.native_cookies.return_value = [{"name": "sessionid", "value": "synthetic"}]
        self.binding = actions.session_binding(self.web.native_cookies())
        self.job = job()
        self.job["session_binding"] = self.binding
        self.client = Mock()
        self.client.poll.return_value = self.job
        self.client.result.return_value = True
        self.executor = Mock()
        self.executor.capabilities.return_value = [kind for kind in actions.KINDS if kind != "share"]
        self.executor.execute.return_value = {"ok": True, "data": {"confirmed": True, "enabled": True}, "error_code": ""}
        self.worker = actions.ActionWorker(self.web, self.directory, executor=self.executor,
                                          client_factory=Mock(return_value=self.client))

    def tearDown(self):
        self.temporary.cleanup()

    def test_lost_result_never_reexecutes_job(self):
        self.client.result.side_effect = sync.SyncError("unavailable")
        for _ in range(2):
            with self.assertRaises(sync.SyncError):
                self.worker.step()
        self.executor.execute.assert_called_once()
        self.assertEqual(self.client.result.call_args.args[1]["error_code"], "unconfirmed")

    def test_duplicate_job_is_retained_for_lease_and_transport_margin(self):
        with patch.object(actions.time, "monotonic", return_value=100):
            self.worker.step()
        for instant in (134, 219):
            with patch.object(actions.time, "monotonic", return_value=instant):
                self.worker.step()
        self.executor.execute.assert_called_once()
        self.assertEqual(self.worker.seen[self.job["job_id"]], 220)
        self.assertEqual(self.client.result.call_args.args[1]["error_code"], "unconfirmed")

    def test_full_replay_cache_recovers_after_expiration(self):
        self.worker.seen = {format(index, "032x"): 200 for index in range(1024)}
        with patch.object(actions.time, "monotonic", return_value=100):
            self.worker.step()
        self.executor.execute.assert_not_called()
        self.assertEqual(len(self.worker.seen), 1024)
        self.assertEqual(self.client.result.call_args.args[1]["error_code"], "busy")
        self.job["job_id"] = "f"*32
        with patch.object(actions.time, "monotonic", return_value=201):
            self.worker.step()
        self.executor.execute.assert_called_once()
        self.assertEqual(self.worker.seen, {"f"*32: 321})

    def test_expired_records_are_removed_while_queue_is_idle(self):
        self.worker.seen = {"d"*32: 200}
        self.client.poll.return_value = None
        with patch.object(actions.time, "monotonic", return_value=201):
            self.worker.step()
        self.assertEqual(self.worker.seen, {})
        self.executor.execute.assert_not_called()

    def test_changed_binding_and_unsupported_share_do_not_execute(self):
        self.job["session_binding"] = "0" * 64
        self.worker.step()
        self.executor.execute.assert_not_called()
        self.assertEqual(self.client.result.call_args.args[1]["error_code"], "account_changed")
        self.job = job("share", video_id="123", friend_id="456")
        self.job["job_id"], self.job["session_binding"] = "c" * 32, self.binding
        self.client.poll.return_value = self.job
        self.worker.step()
        self.executor.execute.assert_not_called()
        self.assertEqual(self.client.result.call_args.args[1]["error_code"], "unsupported")

    def test_readonly_reload_disables_poll(self):
        config = sync.private_json(self.directory / "config.json")
        config["read_only"] = True
        sync.private_write(self.directory / "config.json", config)
        self.worker.step()
        self.client.poll.assert_not_called()

    def test_ready_status_refreshes_after_successful_idle_polls(self):
        self.client.poll.return_value = None
        with patch.object(actions, "private_write") as write:
            with patch.object(actions.time, "monotonic", return_value=100):
                self.worker.step()
            with patch.object(actions.time, "monotonic", return_value=104):
                self.worker.step()
            self.assertEqual(write.call_count, 1)
            with patch.object(actions.time, "monotonic", return_value=106):
                self.worker.step()
            self.assertEqual(write.call_count, 2)
            self.assertEqual(write.call_args.args[1]["state"], "ready")
            self.client.poll.side_effect = sync.SyncError("unavailable")
            with patch.object(actions.time, "monotonic", return_value=112):
                with self.assertRaises(sync.SyncError):
                    self.worker.step()
            self.assertEqual(write.call_count, 2)
        self.executor.execute.assert_not_called()

    def test_nearly_expired_job_is_not_passed_to_executor(self):
        self.job["expires_in_ms"] = 1
        self.worker.step()
        self.executor.execute.assert_not_called()
        self.assertEqual(self.client.result.call_args.args[1]["error_code"], "expired")

    def test_binding_is_rechecked_between_preflight_and_write(self):
        web = Mock()
        web.session.return_value = {"value": json.dumps({"prepared": True, "account_uid": "9", "target": {}})}
        executor = actions.OfficialExecutor(web)
        reader = Mock(side_effect=[BINDING, "c" * 64])
        result = executor.execute(job(), time.monotonic() + 35, reader)
        self.assertEqual(result["error_code"], "account_changed")
        web.session.assert_called_once()


NODE_HARNESS = r"""
const fs = require('fs'); const input = JSON.parse(fs.readFileSync(0,'utf8'));
let writes=0, requests=[], current=0;
global.location={origin:'https://www.douyin.com'}; global.document={title:'fixture'};
const request=async config => {
  requests.push({path:config.url,method:config.method,form:config.data,params:config.params});
  let data={status_code:0};
  if(config.url.includes('/profile/self/')) data.user={uid:'9'};
  else if(config.url.includes('/profile/other/')) data.user={uid:'123',sec_uid:'synthetic-sec-uid',follow_status:current};
  else if(config.url.includes('/aweme/detail/')) data.aweme_detail={aweme_id:'123',aweme_type:0,is_ads:false,
    user_digged:current,collect_stat:current,user_recommend_status:current?2:0,
    status:{allow_self_recommend_to_friend:true},friend_recommend_info:{label_user_list:[]}};
  else {writes++; if(input.mode==='lost') throw Error('transport');
    if(input.mode==='reject') data.status_code=4;
    else { current=1; data.is_digg=1; data.follow_status=1; }
  }
  return {status:200,headers:{},data};
};
const modules={826601:{isNeedInitSecuritySdk:()=>true,getSecuritySdkInitWebStatus:async()=>'success'},
  261805:{ub:()=>0,gn:()=>({version_code:'170400'})},785079:{fV:()=>({os:'Mac OS'})},
  200371:{Q:{getItem:()=>null}},494916:{CookieKeys:{}}};
const loader=id=>modules[id]; loader.m=modules;
const factories=Object.fromEntries(Object.keys(modules).map(id=>[id,()=>{}]));
if(input.mode==='missing_write_modules')delete factories[826601];
const chunkIds=new Set(),chunks=[[['fixture'],factories]]; chunks.push=value=>{
  if(value[0].some(id=>!chunkIds.has(id)))value[2](loader);
  value[0].forEach(id=>chunkIds.add(id));return 1;
};
global.crypto=require('crypto').webcrypto;
const axios=()=>{};axios.request=request;
global.window={webpackChunkdouyin_web:chunks,axiosInstance:axios,_SdkGlueInit:()=>{}};
if(input.mode==='missing_chunks')delete window.webpackChunkdouyin_web;
if(input.mode==='missing_glue')delete window._SdkGlueInit;
if(input.mode==='missing_axios')delete window.axiosInstance;
(async()=>{
  const output=[];
  for(const template of input.scripts){
    const script=template.replace('__PREFLIGHT__',output.length?JSON.stringify(output[0]):'null');
    output.push(JSON.parse(await eval(script)));
  }
  process.stdout.write(JSON.stringify({output,writes,requests}));
})().catch(()=>process.exit(2));
"""


@unittest.skipUnless(shutil.which("node"), "JavaScript fixture requires CI Node runtime")
class OfficialExecutorScriptTests(unittest.TestCase):
    def test_comments_capability_is_independent_of_write_sdk(self):
        for mode in ('missing_write_modules', 'missing_chunks', 'missing_glue', 'missing_axios', 'normal'):
            with self.subTest(mode=mode):
                process = subprocess.run(["node", "-e", NODE_HARNESS],
                    input=json.dumps({"scripts": [actions.PROBE], "mode": mode}), text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=10)
                value = json.loads(process.stdout)
                expected = ([] if mode == 'missing_axios' else
                            ['like', 'follow', 'collect', 'recommend', 'dislike', 'comments']
                            if mode == 'normal' else ['comments'])
                self.assertEqual(value['output'], [expected])
                self.assertEqual(value['writes'], 0)
                self.assertEqual(value['requests'], [])

    def invoke(self, kind, mode="normal"):
        script = (actions.EXECUTE.replace("__JOB__", json.dumps(job(kind)))
                  .replace("__BUDGET__", "30000").replace("__WALL_DEADLINE__", str(int(time.time()*1000)+30000)))
        process = subprocess.run(["node", "-e", NODE_HARNESS],
            input=json.dumps({"scripts": [script, script], "mode": mode}), text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=10)
        return json.loads(process.stdout)

    def test_single_write_and_strict_confirmation_for_supported_actions(self):
        for kind in ("like", "follow", "collect", "recommend", "dislike"):
            with self.subTest(kind=kind):
                result = self.invoke(kind)
                self.assertEqual(result["writes"], 1)
                self.assertTrue(result["output"][0]["prepared"])
                self.assertTrue(result["output"][1]["data"]["confirmed"])
                write = next(r for r in result["requests"] if r["method"] == "POST")
                if kind == "recommend":
                    self.assertEqual(write["path"], "/aweme/v1/web/familiar/recommend/submit")
                    self.assertEqual(write["params"]["device_platform"], "")
                    self.assertIn("is_friend_recommend=0", write["form"])
                if kind == "dislike":
                    self.assertEqual(write["params"]["aweme_id"], "123")

    def test_lost_or_rejected_write_has_no_retry(self):
        for mode, error in (("lost", "unconfirmed"), ("reject", "rejected")):
            result = self.invoke("like", mode)
            self.assertEqual(result["writes"], 1)
            self.assertEqual(result["output"][1]["error_code"], error)


COMMENT_HARNESS = r"""
const fs=require('fs'),input=JSON.parse(fs.readFileSync(0,'utf8'));
let requests=[];global.location={origin:'https://www.douyin.com'};
const axios=()=>{};axios.request=async config=>{
  requests.push({method:config.method,path:config.url,params:config.params});
  return {status:200,headers:{},data:input.response};
};global.window={axiosInstance:axios};
(async()=>{const result=JSON.parse(await eval(input.script));
  process.stdout.write(JSON.stringify({result,requests}));})().catch(()=>process.exit(2));
"""


@unittest.skipUnless(shutil.which("node"), "JavaScript fixture requires CI Node runtime")
class CommentScriptTests(unittest.TestCase):
    def invoke(self, response):
        script = (actions.COMMENTS.replace("__ARGS__", json.dumps({"video_id": "123", "cursor": 0, "count": 20}))
                  .replace("__DEADLINE__", str(int(time.time()*1000)+15000)))
        process = subprocess.run(["node", "-e", COMMENT_HARNESS],
            input=json.dumps({"response": response, "script": script}), text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=10)
        return json.loads(process.stdout)

    def test_read_only_preserves_rows_and_unknown_counts(self):
        row = {"cid": "1", "text": "one\n\t\u0000two", "user": {"nickname": "reader\r",
               "avatar_thumb": {"url_list": ["http://example.com/a", "https://example.com/a"]}}}
        second = {"cid": "2", "text": "🙂" * 1001, "digg_count": 42,
                  "user": {"nickname": "n" * 70, "avatar_thumb": {"url_list": []}}}
        value = self.invoke({"status_code": 0, "comments": [row, second], "cursor": 20, "has_more": 1})
        self.assertEqual([request["method"] for request in value["requests"]], ["GET"])
        data = value["result"]["data"]
        self.assertEqual(len(data["comments"]), 2)
        self.assertEqual(data["comments"][0]["text"], "onetwo")
        self.assertEqual(data["comments"][0]["digg_count"], -1)
        self.assertEqual(data["comments"][0]["user"]["avatar_thumb"]["url_list"], ["https://example.com/a"])
        self.assertEqual(data["comments"][1]["text"], "🙂" * 1000)
        actions.validate_comments(data, 0)

    def test_malformed_row_or_stuck_cursor_rejects_whole_page(self):
        for response in ({"status_code": 0, "comments": [{"cid": "broken"}], "cursor": 20, "has_more": 1},
                         {"status_code": 0, "comments": [], "cursor": 0, "has_more": 1}):
            value = self.invoke(response)
            self.assertFalse(value["result"]["ok"])
            self.assertEqual(value["result"]["data"], {})
            self.assertEqual([r["method"] for r in value["requests"]], ["GET"])

    def test_maximal_unicode_page_fits_encrypted_wire_without_dropping_rows(self):
        avatar = "https://example.com/" + "a" * (512-len("https://example.com/"))
        row = {"cid": "1"*32, "text": "🙂"*1000, "digg_count": 9007199254740991,
               "user": {"nickname": "🙂"*64, "avatar_thumb": {"url_list": [avatar]}}}
        value = self.invoke({"status_code": 0, "comments": [row]*20, "cursor": 20, "has_more": 1})
        self.assertEqual(len(value["result"]["data"]["comments"]), 20)
        clear = dict(value["result"], job_id="a"*32, session_binding=BINDING, challenge=CHALLENGE)
        payload = AESGCM(KEY).encrypt(NONCE, actions.encoded(clear), b"synthetic-aad")
        envelope = {"version": 1, "request_id": REQUEST_ID, "nonce": sync.encode64(NONCE), "payload": sync.encode64(payload)}
        self.assertLessEqual(len(actions.encoded(envelope)), actions.LIMIT)


if __name__ == "__main__":
    unittest.main()
