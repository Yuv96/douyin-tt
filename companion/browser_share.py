"""Single-recipient video sharing through an already-ready official web IM SDK.

Audited official sources (2026-09-27), under
https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/ies/douyin_web/:
  async/54816.5bed6f2d.js: web card composer and identity-security preparation.
  async/15314.1abe9892.js: RequestPacket, native HTTP adapter, protobuf/read-back.
  client-entry~9168a00b.747d621d.js: module834268.MZ message serialization.

The current SaaS UI is a separate, audited adapter, not relaxed legacy authentication:
https://www.douyin.com/pcim_saas_vmok_entry/vmok-manifest.json (1.0.0.946)
https://lf3-social.iesdouyin.com/obj/douyin-social-cdn/pcim/static/js/async/
  4187.2096e141.js: SDK 0.1.8, build 0d50935:feat/pc-im-group, SESSION_AUTH,
    its own protobuf, RequestPacket and plural sendMessageHeaders field.
  __federation_expose_default_export.0ccd0cf4.js: existing-instance getter,
    explicit SESSION_AUTH initialization and web_im identity preparation.

No SDK initialization, inbox pull, conversation opening, read marking, message retry,
or desktop-protocol fallback. Capability requires the website's existing IM session.
"""
import json
import time

from browser_session import DebugError


READY_CONTEXT = r"""
  const req = window.__mydvOfficialWebpack;
  const common = [834268,487897,656043];
  const saasLoader = () => {
    const chunks=window['@pc-im/im:1.0.0.946'];
    if(!Array.isArray(chunks))return null;
    let remote=window.__mydvShareSaasWebpack;
    if(!remote)chunks.push([['__mydv_saas_read_' + crypto.randomUUID()],{},r=>{
      remote=r;Object.defineProperty(window,'__mydvShareSaasWebpack',{value:r,configurable:true});
    }]);
    return remote;
  };
  const ready = () => {
    if (location.origin !== 'https://www.douyin.com' || /验证码|安全验证/.test(document.title)
        || !req || !req.m || common.some(id => !req.m[id])) return null;
    let adapter;
    const context=req.m[906709]?req(906709).Z?.newImContext:null;
    const manager=context?.imSdkService?.imSdkManager;
    // This audited getter only returns the existing instance; true suppresses its absent-instance error.
    const current=typeof manager?.getImSdkInstance==='function'?manager.getImSdkInstance(true):null;
    if(current){
      // The separate Next kernel has a different transport and is deliberately not treated as 0.1.8.
      if(manager.useWebImSdkNext!==false)return null;
      const remote=saasLoader(),needed=[70507,73642,25443,49205,84629,85849,33095,47888];
      if(!remote?.m||needed.some(id=>!remote.m[id]))return null;
      const raw=remote(73642),proto=remote(25443).c;
      if(typeof remote(70507).B!=='function'||!(current instanceof remote(70507).B)
          ||raw.PE.sdkVersion!=='0.1.8'||raw.PE.buildNumber!=='0d50935:feat/pc-im-group'
          ||current.option?.authType!==proto.AuthType.SESSION_AUTH
          ||current.option.versionCode!=='360000'||current.option.biz!=='douyin_web'
          ||typeof context.mainOptions?.depends?.actionManager?.reportAction!=='function')return null;
      adapter={sdk:current,proto,constants:{rb:raw.OH,HF:raw.PE,_:raw.Wd,P6:raw.PC,v9:raw.LJ},
        Long:remote(84629),Packet:remote(49205).m,Conversation:remote(33095).s,
        monitorKey:remote(85849).MM.Monitor,identityFactory:remote(47888).Ay,
        reportAction:(_account,value)=>context.mainOptions.depends.actionManager.reportAction(value)};
    }else{
      const needed=[833027,592358,963286,449919,758951,752145,39278,173570,416578];
      if(needed.some(id=>!req.m[id]))return null;
      const sdk=req(833027).i(),constants=req(592358),proto=req(963286).m;
      if(!sdk||constants.HF.sdkVersion!=='1.1.3'
          ||constants.HF.buildNumber!=='8aa2dcb:Detached: 8aa2dcb88b41538885168e4afbbd2b6bac8aefb2'
          ||sdk.option?.authType!==proto.AuthType.PASSPORT_CERT_AUTH
          ||sdk.option.securityScene!=='web_protect'||sdk.option.securityCertType!=='cookie'
          ||!sdk.option.cert||typeof req(416578).k?.create!=='function')return null;
      adapter={sdk,constants,proto,Long:req(758951),Packet:req(449919).F,Conversation:req(39278).r,
        monitorKey:req(752145).Uk.Monitor,identityFactory:req(173570).ZP,
        reportAction:(account,value)=>req(416578).k.create(account).reportAction(value)};
    }
    const {sdk,constants}=adapter;
    if (sdk.disposed || sdk.initResult !== constants.rb.Succeeded
        || sdk.option?.appId !== 6383 || sdk.option.apiUrl !== 'https://imapi.douyin.com'
        || ![constants._.PushOnly,constants._.Disable].includes(sdk.option.webSocketLevel)
        || typeof sdk.http?.instance?.request !== 'function' || typeof sdk.http.encode !== 'function'
        || typeof sdk.http.decode !== 'function' || typeof sdk.createMessage !== 'function'
        || typeof sdk.updateSendMessageHeaders !== 'function' || typeof adapter.Packet !== 'function'
        || typeof adapter.Conversation?.fromServerConversation !== 'function'
        || typeof req(834268).MZ !== 'function' || typeof adapter.identityFactory?.instance !== 'function'
        || typeof sdk.getConversationList !== 'function' || !/^[1-9][0-9]{0,31}$/.test(String(sdk.option.userId))) return null;
    return adapter;
  };
"""

CONTEXT = READY_CONTEXT + r"""
  const self = async signal => {
    const axios = window.axiosInstance;
    if (typeof axios !== 'function') throw Error('unavailable');
    const response = await axios.request({url:'/aweme/v1/web/user/profile/self/',method:'GET',
      params:{device_platform:'webapp',aid:6383,channel:'channel_pc_web'},withCredentials:true,
      timeout:3500,signal});
    if (response.status !== 200 || response.data?.status_code !== 0
        || !/^[1-9][0-9]{0,31}$/.test(String(response.data.user?.uid))) throw Error('login_required');
    return String(response.data.user.uid);
  };
"""

PROBE = "(() => {" + READY_CONTEXT + r"""
  // Capability is a local SDK readiness hint, not an account proof.
  // Every execution phase independently checks self UID and native Cookie binding.
  try { return JSON.stringify(!!ready()); }
  catch (_) { return 'false'; }
})()"""

EXECUTE = "(async () => {" + CONTEXT + r"""
  const job=__JOB__, phase=__PHASE__, nonce=__NONCE__;
  const deadline=Math.min(__DEADLINE__,__PHASE_DEADLINE__);
  const fail=error_code=>({ok:false,data:{},error_code});
  const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),Math.max(1,deadline-Date.now()));
  const left=()=>deadline-Date.now();
  const digits=value=>/^[1-9][0-9]{0,31}$/.test(String(value));
  const str=value=>value === undefined || value === null ? '' : String(value);
  const check=()=>{if(left()<500 || controller.signal.aborted)throw Error('expired');};
  let state, physicalWrite=false;
  if (!window.__mydvShareJobs) Object.defineProperty(window,'__mydvShareJobs',{value:new Map(),configurable:true});
  const jobs=window.__mydvShareJobs;
  const run=async()=>{
    let context=ready();
    if(!context)return fail('unsupported');
    const {sdk,proto,constants,Long,Packet,Conversation,identityFactory,monitorKey,reportAction}=context;
    const account=await self(controller.signal);
    if(account!==str(sdk.option.userId))return fail('account_changed');
    const same=()=>{
      check();const current=ready();
      if(!current || current.sdk!==sdk || str(sdk.option.userId)!==account)throw Error('account_changed');
    };
    const conversationValid=conv=>{
      const parts=String(conv?.id||'').split(':');
      return conv && conv.type===proto.ConversationType.ONE_TO_ONE_CHAT && parts.length===4
        && parts.slice(2).sort().join(':')===[account,job.args.friend_id].sort().join(':')
        && digits(conv.shortId) && typeof conv.ticket==='string' && conv.ticket.length>0
        && Number.isInteger(conv.inboxType) && conv.inboxType>=0 && conv.isMember===true;
    };
    const audit=body=>{
      let code=Number(body.check_code||0);
      if(body.check_message){
        let nested;try{nested=JSON.parse(body.check_message);}catch(_){throw Error('unconfirmed');}
        if(!nested || typeof nested!=='object' || Array.isArray(nested))throw Error('unconfirmed');
        if(nested.status_code!==undefined && (typeof nested.status_code!=='number' || !Number.isInteger(nested.status_code)))throw Error('unconfirmed');
        if(nested.status_code>0)code=nested.status_code;
      }
      if(code===8610)throw Error('rejected');
      if(![0,8101].includes(code))throw Error('unconfirmed');
    };
    const prepare=async(cmd,body,inbox)=>{
      same();
      const packet=new Packet(sdk.ctx,cmd,proto.RequestBody.create(body));
      await packet.prepareRequest(inbox); // The live SDK signs the actual request here.
      same();
      const expected={ [proto.IMCMD.CREATE_CONVERSATION_V2]:'/v2/conversation/create',
        [proto.IMCMD.SEND_MESSAGE]:'/v1/message/send',
        [proto.IMCMD.GET_MESSAGE_INFO_BY_SERVER_ID]:'/v1/message/get_by_id' }[cmd];
      if(!expected || packet.url!=='https://imapi.douyin.com'+expected)throw Error('unsupported');
      return packet;
    };
    const physical=async(packet,write)=>{
      same();
      if(write && left()<6500)throw Error('expired');
      if(write)physicalWrite=true;
      // This is the official SDK HTTP adapter's single request, with per-call cancellation.
      // No CoreApi/NetworkManager/ImSdk retry wrapper is called; no shared timeout is changed.
      const response=await sdk.http.instance.request({url:packet.url,method:'POST',
        data:sdk.http.encode(packet.request),timeout:Math.min(6000,left()),signal:controller.signal});
      check();
      const h=response.headers||{};
      if(['x-vc-bdturing-parameters','x-tt-verify-passport-decision'].some(k=>h[k]))throw Error('rejected');
      if(response.status!==200)throw Error('unconfirmed');
      const decoded=sdk.http.decode(response.data);
      if(decoded.cmd!==packet.cmd || str(decoded.sequence_id)!==str(packet.seqId)
          || decoded.status_code!==0 || !decoded.body)throw Error('unconfirmed');
      // Match CoreApi response processing so official encrypted message bodies are decoded.
      for(const plugin of sdk.ctx.plugin||[])await plugin.receiveApiResponse(decoded);
      same();return decoded;
    };
    const prepareSend=async()=>{
      if(!conversationValid(state.conversation))throw Error('unconfirmed');
      const serialized=req(834268).MZ({content:JSON.stringify(state.card),type:req(487897).pp.SHARE_AWEME});
      const message=await sdk.createMessage({conversation:state.conversation,...serialized,insert:false});
      if(!message || message.type!==8 || !message.clientId || JSON.parse(message.content).itemId!==job.args.video_id)throw Error('unconfirmed');
      reportAction(account,{conversationId:state.conversation.id,
        conversationShortId:state.conversation.shortId,messageClientId:message.clientId,
        content:message.content,msgType:message.type});
      const identity=await identityFactory.instance({aid:6383}).getIdentitySecurityToken({scene:'web_im'},false);
      same();
      if(typeof identity?.identity_security_param!=='string' || !identity.identity_security_param
          || !str(identity.real_device_id))throw Error('unavailable');
      sdk.updateSendMessageHeaders({identity_security_token:identity.identity_security_param,
        identity_security_device_id:str(identity.real_device_id),identity_security_aid:'6383'});
      if(sdk.option.timeCalibration){
        const delta=sdk.ctx.resolve(monitorKey)?.avgDelta;
        if(!Number.isFinite(delta))throw Error('unavailable');
        message.ext[constants.v9.SendTime]=String(Date.now()+delta);
      }
      state.message=message;
      state.packet=await prepare(proto.IMCMD.SEND_MESSAGE,{send_message_body:{
        conversation_id:state.conversation.id,conversation_short_id:Long.fromString(state.conversation.shortId),
        conversation_type:state.conversation.type,content:message.content,mentioned_users:[],
        client_message_id:message.clientId,ticket:state.conversation.ticket,message_type:message.type,ext:message.ext
      }},state.conversation.inboxType);
      state.stage='send';
      return {prepared:true,phase:'send',nonce:state.nonce};
    };
    if(phase==='prepare'){
      for(const [id,value]of jobs)if(value.deadline<Date.now())jobs.delete(id);
      if(jobs.has(job.job_id)||jobs.size>=4)return fail('busy');
      if(account===job.args.friend_id)return fail('unsupported');
      const response=await window.axiosInstance.request({url:'/aweme/v1/web/aweme/detail/',method:'GET',
        params:{device_platform:'webapp',aid:6383,channel:'channel_pc_web',aweme_id:job.args.video_id},
        withCredentials:true,timeout:Math.min(4000,left()),signal:controller.signal});
      const raw=response.data?.aweme_detail;
      if(response.status!==200||response.data?.status_code!==0||str(raw?.aweme_id)!==job.args.video_id)return fail('unconfirmed');
      if(raw.is_ads||raw.aweme_type!==0||raw.status?.allow_share===false)return fail('unsupported');
      const mapped=req(656043).s0(raw), media=mapped.video, author=mapped.authorInfo;
      if(!media||!author?.avatarThumb||!digits(author.uid)||!Array.isArray(media.coverUrlList))return fail('unconfirmed');
      const card={aweType:req(487897).c6.AWEME_CARD,content_title:mapped.desc||'',
        cover_height:media.height,cover_width:media.width,itemId:job.args.video_id,
        cover_url:{url_list:media.coverUrlList,uri:media.coverUri},
        content_thumb:{url_list:author.avatarThumb.urlList,uri:author.avatarThumb.uri},uid:author.uid};
      state={sdk,account,card,stage:'prepare',nonce:crypto.randomUUID(),deadline:__DEADLINE__,
        binding:job.session_binding,video:job.args.video_id,friend:job.args.friend_id};
      jobs.set(job.job_id,state);
      state.conversation=sdk.getConversationList({filter:conv=>conversationValid(conv)})[0];
      if(state.conversation)return await prepareSend();
      state.packet=await prepare(proto.IMCMD.CREATE_CONVERSATION_V2,{create_conversation_v2_body:{
        conversation_type:proto.ConversationType.ONE_TO_ONE_CHAT,
        participants:[Long.fromString(job.args.friend_id),Long.fromString(account)]
      }},0);
      state.stage='create';
      return {prepared:true,phase:'create',nonce:state.nonce};
    }
    state=jobs.get(job.job_id);
    if(!state || state.nonce!==nonce || state.stage!==phase || state.sdk!==sdk
        || state.account!==account || state.binding!==job.session_binding
        || state.video!==job.args.video_id || state.friend!==job.args.friend_id
        || Date.now()>=state.deadline)return fail('unconfirmed');
    // Consume before physical dispatch. A lost controller response cannot repeat this stage.
    state.stage='consumed';
    if(phase==='create'){
      const response=await physical(state.packet,true), body=response.body.create_conversation_v2_body;
      if(!body||body.status!==0||!body.conversation)throw Error('unconfirmed');
      audit(body);
      state.conversation=Conversation.fromServerConversation(sdk.ctx,body.conversation,response.log_id);
      if(!conversationValid(state.conversation))throw Error('unconfirmed');
      return await prepareSend();
    }
    if(phase!=='send')return fail('malformed');
    const response=await physical(state.packet,true), sent=response.body.send_message_body;
    if(!sent||sent.status!==0||!digits(str(sent.server_message_id))
        || sent.client_message_id!==state.message.clientId)throw Error('unconfirmed');
    audit(sent);
    const serverId=str(sent.server_message_id), conv=state.conversation;
    // Always goes through the actual network adapter; never getMessageByServerId's cache.
    const packet=await prepare(proto.IMCMD.GET_MESSAGE_INFO_BY_SERVER_ID,{get_message_by_id_body:{
      conversation_id:conv.id,conversation_short_id:Long.fromString(conv.shortId),
      conversation_type:conv.type,server_message_id:Long.fromString(serverId)
    }},conv.inboxType);
    const read=await physical(packet,false), info=read.body.get_message_by_id_body?.msg_info, body=info?.body;
    if(info?.status!==proto.MessageStatus.AVAILABLE||!body||body.status!==constants.P6.Enable
        ||str(body.server_message_id)!==serverId||body.conversation_id!==conv.id
        ||str(body.conversation_short_id)!==conv.shortId||body.conversation_type!==conv.type
        ||str(body.sender)!==account||body.message_type!==8
        ||body.ext?.[constants.v9.ClientMessageId]!==state.message.clientId)throw Error('unconfirmed');
    const card=JSON.parse(body.content);
    if(card.itemId!==job.args.video_id||card.aweType!==req(487897).c6.AWEME_CARD||str(card.uid)!==str(state.card.uid))throw Error('unconfirmed');
    const visible=body.ext?.[constants.v9.MessageVisible], invisible=body.ext?.[constants.v9.MessageInvisible];
    if(visible && !visible.split(',').includes(job.args.friend_id)
        || invisible && invisible.split(',').includes(job.args.friend_id))throw Error('unconfirmed');
    state.stage='finished';delete state.packet;delete state.message;delete state.card;
    return {ok:true,data:{confirmed:true},error_code:''};
  };
  try{return JSON.stringify(await run());}
  catch(error){if(state)state.stage='failed';const code=error?.message;
    return JSON.stringify(fail(['account_changed','rejected','unsupported'].includes(code)?code:
      physicalWrite?'unconfirmed':['expired','unavailable','login_required'].includes(code)?code:'unconfirmed'));}
  finally{clearTimeout(timer);controller.abort();}
})()"""


class ShareExecutor:
    def __init__(self, web):
        self.web = web
        self.cached_binding = None
        self.cached_ready = False
        self.next_probe = 0

    def capability(self):
        from browser_actions import session_binding, strict_json
        try:
            binding = session_binding(self.web.native_cookies())
            if binding != self.cached_binding or time.monotonic() >= self.next_probe:
                self.cached_binding = binding
                self.cached_ready = False
                # Apply the same local-probe backoff even if evaluation/parsing fails.
                self.next_probe = time.monotonic() + 5
                value = self.web.evaluate(PROBE)
                self.cached_ready = strict_json(value) is True if isinstance(value, str) else value is True
            return self.cached_ready
        except (DebugError, ValueError, TypeError, OSError):
            self.cached_ready = False
            return False

    def execute(self, job, deadline, binding_reader):
        from browser_actions import encoded, outcome, strict_json, validate_job, validate_result
        validate_job(job)
        if job["kind"] != "share":
            return outcome("unsupported")
        phase, nonce = "prepare", None
        wall_deadline = int(time.time() * 1000 + (deadline - time.monotonic()) * 1000) - 6500
        try:
            for _ in range(3):
                if binding_reader() != job["session_binding"]:
                    return outcome("account_changed")
                remaining = int((deadline - time.monotonic()) * 1000) - 6500
                if remaining < 7000:
                    return outcome("expired" if phase == "prepare" else "unconfirmed")
                budget = min(18000 if phase == "send" else 12000, remaining)
                script = (EXECUTE.replace("__JOB__", encoded(job).decode())
                          .replace("__PHASE__", json.dumps(phase)).replace("__NONCE__", json.dumps(nonce))
                          .replace("__DEADLINE__", str(wall_deadline))
                          .replace("__PHASE_DEADLINE__", str(int(time.time()*1000)+budget)))
                value = self.web.session("/javascript/evaluate", {"code": script, "timeout": 22})["value"]
                value = strict_json(value) if isinstance(value, str) else value
                if binding_reader() != job["session_binding"]:
                    return outcome("account_changed")
                if isinstance(value, dict) and value.get("prepared") is True:
                    if (set(value) != {"prepared", "phase", "nonce"} or value["phase"] not in {"create", "send"}
                            or not isinstance(value["nonce"], str) or len(value["nonce"]) != 36
                            or (phase != "prepare" and value["phase"] != "send")):
                        return outcome("unconfirmed")
                    phase, nonce = value["phase"], value["nonce"]
                    continue
                return validate_result(value, job) if time.monotonic() < deadline else outcome("unconfirmed")
            return outcome("unconfirmed")
        except (DebugError, ValueError, TypeError, KeyError, OSError):
            return outcome("unconfirmed")
