"""Paired-TV action worker using the existing official browser session.

No listening socket, signature emulation, automatic write retry, or IM fallback.
Only an authenticated, fresh job claimed from the TV can reach execute().
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import secrets
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener

from browser_session import DebugError, NoRedirect
from agent_webview_sync import (SyncError, decode64, encode64, private_host,
                               private_json, private_write, timestamp)

LIMIT = 131072
SEEN_TTL_SECONDS = 120  # Covers the full 35-second lease and delayed result transport.
KINDS = ("like", "follow", "collect", "recommend", "dislike", "share", "comments")
ERRORS = {"unsupported", "unavailable", "login_required", "account_changed", "expired",
          "rejected", "unconfirmed", "busy", "malformed", "cooldown"}
HEX32 = re.compile(r"[a-f0-9]{32}\Z")
HEX64 = re.compile(r"[a-f0-9]{64}\Z")
DIGITS = re.compile(r"[0-9]{1,32}\Z")


def matches(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate field")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite")))


def encoded(value):
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(raw) > LIMIT:
        raise SyncError("malformed")
    return raw


def session_binding(cookies):
    for name in ("sessionid", "sessionid_ss"):
        values = {c.get("value") for c in cookies if c.get("name") == name and c.get("value")}
        if len(values) > 1:
            raise SyncError("account_changed")
        if values:
            value = next(iter(values))
            if not isinstance(value, str) or len(value) > 4096 or any(ord(c) < 32 for c in value):
                raise SyncError("login_required")
            return hashlib.sha256(("mydv-browser/session/v1\n" + value).encode("utf-8")).hexdigest()
    raise SyncError("login_required")


def validate_job(job):
    if (not isinstance(job, dict)
            or set(job) != {"job_id", "kind", "args", "session_binding", "expires_in_ms"}
            or not matches(HEX32, job["job_id"]) or not matches(HEX64, job["session_binding"])
            or type(job["expires_in_ms"]) is not int or not 1 <= job["expires_in_ms"] <= 35000):
        raise SyncError("malformed")
    kind, args = job["kind"], job["args"]
    fields = {"like": {"video_id", "enabled"}, "collect": {"video_id", "enabled"},
              "recommend": {"video_id", "enabled"}, "follow": {"user_id", "sec_uid", "enabled"},
              "dislike": {"video_id"}, "share": {"video_id", "friend_id"},
              "comments": {"video_id", "cursor", "count"}}
    if not isinstance(kind, str) or kind not in fields or not isinstance(args, dict) or set(args) != fields[kind]:
        raise SyncError("malformed")
    for name, value in args.items():
        if name.endswith("_id") and not matches(DIGITS, value):
            raise SyncError("malformed")
        if name == "enabled" and type(value) is not bool:
            raise SyncError("malformed")
        if name == "sec_uid" and (not isinstance(value, str) or not 1 <= len(value) <= 256
                                  or any(ord(c) < 32 or ord(c) == 127 for c in value)):
            raise SyncError("malformed")
    if kind == "comments" and (type(args["cursor"]) is not int or not 0 <= args["cursor"] <= 2147483647
                               or type(args["count"]) is not int or args["count"] != 20):
        raise SyncError("malformed")
    return job


def outcome(error):
    return {"ok": False, "data": {}, "error_code": error if error in ERRORS else "unavailable"}


def validate_result(result, job):
    if not isinstance(result, dict) or set(result) != {"ok", "data", "error_code"} or type(result["ok"]) is not bool:
        raise SyncError("unconfirmed")
    if not result["ok"]:
        if result["data"] != {} or result["error_code"] not in ERRORS:
            raise SyncError("unconfirmed")
        return result
    data = result["data"]
    if job["kind"] == "comments":
        validate_comments(data, job["args"]["cursor"])
        if result["error_code"] != "":
            raise SyncError("unconfirmed")
        return result
    fields = {"confirmed"} if job["kind"] in {"dislike", "share"} else {"confirmed", "enabled"}
    if (result["error_code"] != "" or not isinstance(data, dict) or set(data) != fields
            or data.get("confirmed") is not True
            or ("enabled" in fields and (type(data.get("enabled")) is not bool
                                        or data["enabled"] != job["args"]["enabled"]))):
        raise SyncError("unconfirmed")
    return result


def validate_comments(data, requested_cursor):
    from urllib.parse import urlsplit
    if (not isinstance(data, dict) or set(data) != {"comments", "cursor", "has_more"}
            or not isinstance(data["comments"], list) or len(data["comments"]) > 20
            or type(data["cursor"]) is not int or not 0 <= data["cursor"] <= 2147483647
            or type(data["has_more"]) is not bool
            or (data["has_more"] and data["cursor"] <= requested_cursor)):
        raise SyncError("unconfirmed")
    for comment in data["comments"]:
        if (not isinstance(comment, dict) or set(comment) != {"cid", "text", "digg_count", "user"}
                or not matches(DIGITS, comment["cid"]) or type(comment["digg_count"]) is not int
                or not -1 <= comment["digg_count"] <= 9007199254740991):
            raise SyncError("unconfirmed")
        user = comment["user"]
        if not isinstance(user, dict) or set(user) != {"nickname", "avatar_thumb"}:
            raise SyncError("unconfirmed")
        for value, limit in ((comment["text"], 1000), (user["nickname"], 64)):
            if (not isinstance(value, str) or len(value) > limit
                    or any(ord(c) < 32 or ord(c) == 127 or 0xd800 <= ord(c) <= 0xdfff for c in value)):
                raise SyncError("unconfirmed")
        avatar = user["avatar_thumb"]
        if (not isinstance(avatar, dict) or set(avatar) != {"url_list"}
                or not isinstance(avatar["url_list"], list) or len(avatar["url_list"]) > 1):
            raise SyncError("unconfirmed")
        for url in avatar["url_list"]:
            if (not isinstance(url, str) or len(url) > 512 or any(ord(c) < 33 or ord(c) > 126 for c in url)
                    or urlsplit(url).scheme != "https" or not urlsplit(url).hostname
                    or urlsplit(url).username is not None or urlsplit(url).password is not None):
                raise SyncError("unconfirmed")


def http_json(url, body=None, *, timeout=3):
    request = Request(url, data=None if body is None else encoded(body),
                      headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=timeout) as response:
            raw = response.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise SyncError("malformed")
        result = strict_json(raw)
        if not isinstance(result, dict):
            raise SyncError("malformed")
        return result
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, UnicodeError):
        raise SyncError("unavailable") from None


class BrowserClient:
    def __init__(self, host, key, receiver_id, *, port=18765, transport=http_json):
        if (not isinstance(key, bytes) or len(key) != 16 or not matches(HEX32, receiver_id)
                or type(port) is not int or not 1 <= port <= 65535):
            raise SyncError("malformed")
        self.base = "http://" + private_host(host) + ":" + str(port)
        self.key, self.receiver_id, self.port, self.transport = key, receiver_id, port, transport
        self.current_challenge = None
        self.response_nonces = set()

    def exchange(self, operation, fields):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.exceptions import InvalidTag
        if operation not in {"poll", "result"}:
            raise SyncError("malformed")
        challenge = self.transport(self.base + "/v1/challenge", timeout=3)
        if (set(challenge) != {"version", "receiver_id", "port", "challenge"}
                or type(challenge["version"]) is not int or challenge["version"] != 1
                or challenge["receiver_id"] != self.receiver_id
                or type(challenge["port"]) is not int or challenge["port"] != self.port):
            raise SyncError("unavailable")
        decode64(challenge["challenge"], 32)
        if self.current_challenge != challenge["challenge"]:
            self.current_challenge = challenge["challenge"]
            self.response_nonces.clear()
        if len(self.response_nonces) >= 1024:
            raise SyncError("busy")
        request_id, nonce = secrets.token_hex(16), secrets.token_bytes(12)
        aad = ("mydv-browser/v1/request\n" + self.receiver_id + "\n" + request_id).encode()
        clear = encoded(dict(fields, challenge=challenge["challenge"]))
        payload = AESGCM(self.key).encrypt(nonce, clear, aad)
        envelope = {"version": 1, "request_id": request_id, "nonce": encode64(nonce), "payload": encode64(payload)}
        response = self.transport(self.base + "/v1/browser/" + operation, envelope, timeout=3)
        if (set(response) != {"version", "request_id", "nonce", "payload"}
                or type(response["version"]) is not int or response["version"] != 1
                or response["request_id"] != request_id):
            raise SyncError("unavailable")
        response_nonce = decode64(response["nonce"], 12)
        if response_nonce == nonce or response_nonce in self.response_nonces:
            raise SyncError("unavailable")
        try:
            raw = AESGCM(self.key).decrypt(response_nonce, decode64(response["payload"]),
                ("mydv-browser/v1/response\n" + self.receiver_id + "\n" + request_id).encode())
            if len(raw) > LIMIT:
                raise ValueError("oversized")
            value = strict_json(raw)
        except (InvalidTag, ValueError, UnicodeError):
            raise SyncError("unavailable") from None
        self.response_nonces.add(response_nonce)
        expected = {"request_id", "job"} if operation == "poll" else {"request_id", "accepted"}
        if not isinstance(value, dict) or set(value) != expected or value["request_id"] != request_id:
            raise SyncError("malformed")
        if operation == "result" and type(value["accepted"]) is not bool:
            raise SyncError("malformed")
        return value

    def poll(self, binding, capabilities):
        return self.exchange("poll", {"session_binding": binding, "capabilities": capabilities})["job"]

    def result(self, job, result):
        result = validate_result(result, job)
        return self.exchange("result", dict(result, job_id=job["job_id"],
                                             session_binding=job["session_binding"]))["accepted"]


# These are module identities from current official hashed sources, not an SDK copy.
# No chunks are downloaded/initialized by the probe. Jobs initialize only the website's own
# security module and use its existing axios metadata interceptor.
# Sources: official /ies/douyin_web/client-entry~c01d2505.57a98fe2.js (599090),
# client-entry~a8963009.f541c1c2.js (826601), async/75285.4c07c04d.js (action forms).
PROBE = r"""JSON.stringify((() => {
  if (location.origin !== 'https://www.douyin.com' || typeof window.axiosInstance !== 'function') return [];
  const reads = ['comments'];
  if (/验证码|安全验证/.test(document.title)) return reads;
  const chunks = window.webpackChunkdouyin_web;
  if (!Array.isArray(chunks)) return reads;
  const need = ['826601','261805','785079','200371','494916'];
  if (!need.every(id => chunks.some(c => c && c[1] && typeof c[1][id] === 'function'))
      || typeof window._SdkGlueInit !== 'function') return reads;
  // Resolve only the existing loader, so an already-ready IM session can be inspected.
  // This registers no modules and does not initialize an SDK or pull an inbox.
  if (!window.__mydvOfficialWebpack) chunks.push([['__mydv_bridge_runtime_' + crypto.randomUUID()], {}, r => {
    Object.defineProperty(window,'__mydvOfficialWebpack',{value:r,configurable:true});
  }]);
  return ['like','follow','collect','recommend','dislike',...reads];
})())"""

COMMENTS = r"""(async()=>{
  const args=__ARGS__,deadline=__DEADLINE__,controller=new AbortController();
  const fail=error_code=>({ok:false,data:{},error_code});
  const timer=setTimeout(()=>controller.abort(),Math.max(1,deadline-Date.now()));
  const clean=(value,limit)=>{
    if(typeof value!=='string')throw Error('unconfirmed');
    const characters=Array.from(value.replace(/[\x00-\x1f\x7f]/g,''));
    if(characters.some(c=>c.length===1&&/[\uD800-\uDFFF]/.test(c)))throw Error('unconfirmed');
    return characters.slice(0,limit).join('');
  };
  try{
    if(location.origin!=='https://www.douyin.com'||typeof window.axiosInstance!=='function')return JSON.stringify(fail('unavailable'));
    if(Date.now()>=deadline)return JSON.stringify(fail('expired'));
    const response=await window.axiosInstance.request({url:'/aweme/v1/web/comment/list/',method:'GET',
      params:{device_platform:'webapp',aid:6383,channel:'channel_pc_web',aweme_id:args.video_id,
        cursor:args.cursor,count:20,item_type:0},withCredentials:true,timeout:Math.min(12000,deadline-Date.now()),signal:controller.signal});
    const raw=response.data,h=response.headers||{};
    if(['x-vc-bdturing-parameters','x-tt-verify-passport-decision','x-whale-throughput-abort-data'].some(k=>h[k]))return JSON.stringify(fail('rejected'));
    if(response.status!==200||!raw||raw.status_code!==0)return JSON.stringify(fail(raw?.status_code===8?'login_required':'unconfirmed'));
    if(!Array.isArray(raw.comments)||raw.comments.length>20||!Number.isInteger(raw.cursor)||raw.cursor<0||raw.cursor>2147483647
        ||![false,true,0,1].includes(raw.has_more))return JSON.stringify(fail('unconfirmed'));
    const more=raw.has_more===true||raw.has_more===1;
    if(more&&raw.cursor<=args.cursor)return JSON.stringify(fail('unconfirmed'));
    const comments=raw.comments.map(row=>{
      if(!row||typeof row.cid!=='string'||!/^\d{1,32}$/.test(row.cid)||!row.user)throw Error('unconfirmed');
      let urls=[];
      const candidates=row.user.avatar_thumb?.url_list;
      if(Array.isArray(candidates))for(const url of candidates){
        if(typeof url!=='string'||url.length>512||!/^[\x21-\x7e]+$/.test(url))continue;
        try{const parsed=new URL(url);if(parsed.protocol==='https:'&&parsed.hostname&&!parsed.username&&!parsed.password
            &&parsed.href.length<=512){urls=[parsed.href];break;}}catch(_){}
      }
      return {cid:row.cid,text:clean(row.text,1000),
        digg_count:Number.isSafeInteger(row.digg_count)&&row.digg_count>=0?row.digg_count:-1,
        user:{nickname:clean(row.user.nickname,64),avatar_thumb:{url_list:urls}}};
    });
    const data={comments,cursor:raw.cursor,has_more:more};
    // Leave room for AES-GCM/base64 and the result envelope at the 128-KiB wire limit.
    // Preserve every row; only exceptionally large pages need a slightly shorter preview.
    while(new TextEncoder().encode(JSON.stringify(data)).length>96000)
      for(const row of comments)row.text=Array.from(row.text).slice(0,-10).join('');
    return JSON.stringify({ok:true,data,error_code:''});
  }catch(_){return JSON.stringify(fail('unconfirmed'));}
  finally{clearTimeout(timer);controller.abort();}
})()"""

EXECUTE = r"""(async () => {
  const job = __JOB__, deadline = Math.min(Date.now() + __BUDGET__, __WALL_DEADLINE__);
  const preflight = __PREFLIGHT__;
  const fail = code => ({ok:false,data:{},error_code:code});
  const done = () => ({ok:true,data:Object.assign({confirmed:true},
    job.kind === 'dislike' ? {} : {enabled:job.args.enabled}),error_code:''});
  let attempted = false;
  const controller = new AbortController();
  const remaining = () => deadline - Date.now();
  const timer = setTimeout(() => controller.abort(), Math.max(1, remaining()));
  const run = async () => {
    if (location.origin !== 'https://www.douyin.com') return fail('unavailable');
    if (/验证码|安全验证/.test(document.title)) return fail('rejected');
    if (!['like','follow','collect','recommend','dislike'].includes(job.kind)) return fail('unsupported');
    let req = window.__mydvOfficialWebpack;
    const chunks = window.webpackChunkdouyin_web, axios = window.axiosInstance;
    if (!Array.isArray(chunks) || typeof axios !== 'function' || typeof window._SdkGlueInit !== 'function') return fail('unavailable');
    // The official runtime only invokes a callback for a fresh nonempty chunk ID.
    // Register no modules; retain its loader so subsequent jobs add no further chunks.
    if (!req) chunks.push([['__mydv_bridge_runtime_' + crypto.randomUUID()], {}, r => {
      req = r;
      Object.defineProperty(window,'__mydvOfficialWebpack',{value:r,configurable:true});
    }]);
    if (!req || !req.m || !['826601','261805','785079','200371','494916'].every(id => req.m[id])) return fail('unavailable');
    const security = req(826601), pc = req(261805), platform = req(785079);
    const cookies = req(200371).Q, keys = req(494916).CookieKeys;
    const common = () => ({device_platform:'webapp',aid:6383,channel:'channel_pc_web',
      pc_client_type:pc.ub(),pc_libra_divert:platform.fV().os,update_version_code:pc.gn().version_code,
      support_h265:cookies.getItem({sKey:keys.ClientSupportHevc})?1:0,
      support_dash:cookies.getItem({sKey:keys.isDashUser})?1:0});
    const request = async (path, params, body) => {
      if (remaining() < 800 || controller.signal.aborted) throw Error('expired');
      const headers = {'Content-Type': body ? 'application/x-www-form-urlencoded; charset=UTF-8' : 'application/json'};
      const query = Object.assign(common(), params || {});
      // Match the official path-specific metadata override before its interceptor applies.
      const response = await axios.request({url:path,method:body?'POST':'GET',params:query,
        data:body?new URLSearchParams(body).toString():undefined,headers,withCredentials:true,
        timeout:Math.min(4500, remaining()),signal:controller.signal});
      const h = response.headers || {};
      if (['x-vc-bdturing-parameters','x-tt-verify-passport-decision','x-whale-throughput-abort-data']
          .some(k => h[k])) throw Error('rejected');
      if (response.status !== 200 || !response.data || typeof response.data !== 'object') throw Error('unconfirmed');
      const data = response.data;
      if (data.status_code !== 0) throw Error(data.status_code === 8 ? 'login_required' : 'rejected');
      return data;
    };
    const self = await request('/aweme/v1/web/user/profile/self/',{});
    if (!self.user || !/^[1-9][0-9]*$/.test(String(self.user.uid))) return fail('login_required');
    if (preflight && String(self.user.uid) !== preflight.account_uid) return fail('account_changed');
    const args = job.args;
    const read = async () => {
      if (job.kind === 'follow') {
        const data = await request('/aweme/v1/web/user/profile/other/',{sec_user_id:args.sec_uid});
        if (!data.user || String(data.user.uid) !== args.user_id || data.user.sec_uid !== args.sec_uid) throw Error('unconfirmed');
        return data.user;
      }
      const data = await request('/aweme/v1/web/aweme/detail/',{aweme_id:args.video_id});
      if (!data.aweme_detail || String(data.aweme_detail.aweme_id) !== args.video_id) throw Error('unconfirmed');
      return data.aweme_detail;
    };
    const state = value => {
      const key = {like:'user_digged',collect:'collect_stat',recommend:'user_recommend_status',follow:'follow_status'}[job.kind];
      const n = value[key];
      if (!Number.isInteger(n)) throw Error('unconfirmed');
      if (job.kind === 'recommend') { if (![0,1,2].includes(n)) throw Error('unconfirmed'); return n === 2; }
      if (job.kind === 'follow') { if (![0,1,2].includes(n)) throw Error('unconfirmed'); return n === 1 || n === 2; }
      if (![0,1].includes(n)) throw Error('unconfirmed');
      return n === 1;
    };
    const before = preflight ? preflight.target : await read();
    if (job.kind !== 'follow' && (before.is_ads || before.aweme_type === 101)) return fail('unsupported');
    if (job.kind === 'recommend' && (!before.status || before.status.allow_self_recommend_to_friend !== true)) return fail('unsupported');
    if (job.kind !== 'dislike' && state(before) === args.enabled) return done();
    let path, params = {}, form;
    switch(job.kind) {
      case 'like': path='/aweme/v1/web/commit/item/digg/'; form={aweme_id:args.video_id,type:args.enabled?1:0,item_type:0}; break;
      case 'collect': path='/aweme/v1/web/aweme/collect/';
        if (!Number.isInteger(before.aweme_type)) return fail('unconfirmed');
        form={aweme_id:args.video_id,action:args.enabled?1:0,aweme_type:before.aweme_type}; break;
      case 'follow': path='/aweme/v1/web/commit/follow/user/'; form={user_id:args.user_id,type:args.enabled?1:0}; break;
      case 'recommend': path='/aweme/v1/web/familiar/recommend/'+(args.enabled?'submit':'cancel');
        params={app_name:'douyin_web',device_platform:''}; form={type:2,item_id:args.video_id,source:0};
        if (args.enabled) form.is_friend_recommend=Array.isArray(before.friend_recommend_info?.label_user_list)
          && before.friend_recommend_info.label_user_list.length?1:0;
        break;
      case 'dislike': path='/aweme/v1/web/commit/dislike/item/'; params={aweme_id:args.video_id}; form={aweme_id:args.video_id}; break;
    }
    if (!preflight && security.isNeedInitSecuritySdk(path)) {
      if (remaining() < 11000) return fail('expired');
      if (await security.getSecuritySdkInitWebStatus() !== 'success') return fail('unavailable');
    }
    if (!preflight) {
      // Only these bounded target fields cross to Python. Native Cookie binding is checked
      // again after all preflight reads/SDK initialization and immediately before execution.
      const target = {};
      for (const key of ['aweme_id','aweme_type','is_ads','user_digged','collect_stat',
                         'user_recommend_status','uid','sec_uid','follow_status']) {
        if (Object.prototype.hasOwnProperty.call(before,key)) target[key]=before[key];
      }
      target.status={allow_self_recommend_to_friend:before.status?.allow_self_recommend_to_friend === true};
      target.friend_recommend_info={label_user_list:form.is_friend_recommend ? [{}] : []};
      return {prepared:true,account_uid:String(self.user.uid),target};
    }
    if (remaining() < 5500 || location.origin !== 'https://www.douyin.com') return fail('expired');
    // This is the only write call. No catch path, verification UI or retry invokes it again.
    attempted = true;
    const response = await request(path,params,form);
    if (job.kind === 'like' && response.is_digg !== Number(args.enabled) && response.is_digg !== args.enabled) return fail('unconfirmed');
    if (job.kind === 'follow' && (![0,1,2].includes(response.follow_status)
        || (response.follow_status !== 0) !== args.enabled)) return fail('unconfirmed');
    // Official dislike has no separate readable preference flag; its status_code0 is the
    // documented application-level acknowledgement after target/ad validation above.
    if (job.kind === 'dislike') return done();
    return state(await read()) === args.enabled ? done() : fail('unconfirmed');
  };
  try { return JSON.stringify(await run()); }
  catch (e) { const code = e && e.message; return JSON.stringify(fail(
    attempted ? (code === 'rejected' ? 'rejected' : 'unconfirmed') :
    ['expired','rejected','login_required','unconfirmed'].includes(code) ? code : 'unavailable')); }
  finally { clearTimeout(timer); controller.abort(); }
})()"""


class OfficialExecutor:
    def __init__(self, web):
        self.web = web
        from browser_share import ShareExecutor
        self.share = ShareExecutor(web)

    def capabilities(self):
        value = self.web.evaluate(PROBE)
        value = strict_json(value) if isinstance(value, str) else value
        if not isinstance(value, list) or any(not isinstance(kind, str) or kind not in KINDS for kind in value):
            return []
        capabilities = [kind for kind in KINDS if kind != "share" and kind in value]
        if self.share.capability():
            capabilities.append("share")
        return capabilities

    def execute(self, job, deadline, binding_reader):
        validate_job(job)
        if job["kind"] == "comments":
            return self.comments(job, deadline, binding_reader)
        if job["kind"] == "share":
            return self.share.execute(job, deadline, binding_reader)
        if job["kind"] not in KINDS:
            return outcome("unsupported")
        if binding_reader() != job["session_binding"]:
            return outcome("account_changed")
        budget = min(20000, int((deadline - time.monotonic()) * 1000) - 10000)
        if budget < 6000:
            return outcome("expired")
        try:
            def evaluate(preflight, milliseconds):
                script = (EXECUTE.replace("__JOB__", encoded(job).decode())
                          .replace("__BUDGET__", str(milliseconds))
                          .replace("__WALL_DEADLINE__", str(int(time.time() * 1000) + milliseconds))
                          .replace("__PREFLIGHT__", encoded(preflight).decode()))
                value = self.web.session("/javascript/evaluate", {"code": script, "timeout": 24})["value"]
                return strict_json(value) if isinstance(value, str) else value

            value = evaluate(None, budget)
            if isinstance(value, dict) and value.get("prepared") is True:
                if (set(value) != {"prepared", "account_uid", "target"}
                        or not matches(DIGITS, value["account_uid"]) or not isinstance(value["target"], dict)
                        or len(encoded(value)) > 2048):
                    return outcome("unavailable")
                if binding_reader() != job["session_binding"]:
                    return outcome("account_changed")
                # Reserve both bounded challenge/result round trips, not only the POST.
                budget = min(17000, int((deadline - time.monotonic()) * 1000) - 6500)
                if budget < 6000:
                    return outcome("expired")
                value = evaluate(value, budget)
            result = validate_result(value, job)
            if binding_reader() != job["session_binding"]:
                return outcome("account_changed")
            if time.monotonic() >= deadline:
                return outcome("unconfirmed")
            return result
        except (DebugError, ValueError, KeyError, TypeError, OSError):
            # Evaluation may have reached the write; never retry or fall back to Cookie HTTP.
            return outcome("unconfirmed")

    def comments(self, job, deadline, binding_reader):
        try:
            if binding_reader() != job["session_binding"]:
                return outcome("account_changed")
            budget = min(12000, int((deadline-time.monotonic())*1000)-6500)
            if budget < 1000:
                return outcome("expired")
            script = (COMMENTS.replace("__ARGS__", encoded(job["args"]).decode())
                      .replace("__DEADLINE__", str(int(time.time()*1000)+budget)))
            value = self.web.session("/javascript/evaluate", {"code": script, "timeout": 15})["value"]
            value = strict_json(value) if isinstance(value, str) else value
            if binding_reader() != job["session_binding"]:
                return outcome("account_changed")
            return validate_result(value, job) if time.monotonic()<deadline else outcome("expired")
        except (DebugError, ValueError, TypeError, KeyError, OSError, UnicodeError):
            return outcome("unconfirmed")


class ActionWorker:
    def __init__(self, web, directory, stop=None, *, executor=None, client_factory=BrowserClient):
        self.web, self.directory = web, Path(directory)
        self.stop = stop if stop is not None else threading.Event()
        self.executor = executor if executor is not None else OfficialExecutor(web)
        self.client_factory = client_factory
        self.client = self.config_identity = None
        self.seen = {}
        self.last_status = None
        self.last_status_at = None

    def binding(self):
        return session_binding(self.web.native_cookies())

    def configuration(self):
        config = private_json(self.directory / "config.json")
        if config.get("read_only", False) is not False or not config.get("pairing_key"):
            return None
        if not matches(HEX32, config.get("pairing_key")) or not matches(HEX32, config.get("receiver_id")):
            raise SyncError("unavailable")
        private_host(config.get("ip", ""))
        return config["ip"], config["pairing_key"], config["receiver_id"]

    def report(self, state, capabilities=()):
        summary = state, tuple(capabilities)
        now = time.monotonic()
        if summary != self.last_status or self.last_status_at is None or now - self.last_status_at >= 5:
            private_write(self.directory / "browser-actions-status.json",
                          {"state": state, "capabilities": list(capabilities), "observed_at": timestamp()})
            self.last_status = summary
            self.last_status_at = now

    def step(self):
        now = time.monotonic()
        self.seen = {job_id: until for job_id, until in self.seen.items() if until > now}
        identity = self.configuration()
        if identity is None:
            self.report("disabled")
            return
        if identity != self.config_identity:
            self.client = self.client_factory(identity[0], bytes.fromhex(identity[1]), identity[2])
            self.config_identity = identity
        binding = self.binding()
        capabilities = self.executor.capabilities()
        polled_at = time.monotonic()
        job = self.client.poll(binding, capabilities)
        self.report("ready" if capabilities else "unavailable", capabilities)
        if job is None:
            return
        try:
            validate_job(job)
        except DebugError:
            # An untrusted/malformed job is never echoed or executed.
            self.report("malformed")
            return
        # Starting before the round trip is conservative; network delay never extends a lease.
        deadline = polled_at + job["expires_in_ms"] / 1000
        if job["job_id"] in self.seen:
            result = outcome("unconfirmed")
        elif len(self.seen) >= 1024:
            result = outcome("busy")
        else:
            self.seen[job["job_id"]] = time.monotonic() + SEEN_TTL_SECONDS
            if self.stop.is_set() or (self.directory / "stop.json").exists():
                result = outcome("unavailable")
            elif time.monotonic() + 6 >= deadline:
                result = outcome("expired")
            elif job["session_binding"] != binding or self.binding() != binding:
                result = outcome("account_changed")
            elif self.configuration() != identity:
                result = outcome("unavailable")
            elif job["kind"] not in capabilities:
                result = outcome("unsupported")
            else:
                def current_binding():
                    if (self.stop.is_set() or (self.directory / "stop.json").exists()
                            or self.configuration() != identity):
                        raise SyncError("unavailable")
                    return self.binding()
                result = self.executor.execute(job, deadline, current_binding)
        self.client.result(job, validate_result(result, job))
        # A lost/negative result acknowledgement never puts the job back or repeats execution.

    def run(self):
        while not self.stop.is_set() and not (self.directory / "stop.json").exists():
            start = time.monotonic()
            try:
                self.step()
            except Exception:
                # Only fixed status escapes; tool/network exceptions can contain private URLs.
                self.report("unavailable")
            self.stop.wait(max(0.1, 2 - (time.monotonic() - start)))
        self.report("stopped")


def start_worker(web, directory, stop=None):
    worker = ActionWorker(web, directory, stop=stop)
    thread = threading.Thread(target=worker.run, name="official-browser-actions", daemon=True)
    thread.start()
    return worker, thread
