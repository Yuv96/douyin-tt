"""CI-only official-API-shaped IM adapter fixtures; no real message or credential."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import browser_actions as actions
import browser_share as share


def job():
    return {"job_id": "c" * 32, "kind": "share", "args": {"video_id": "123", "friend_id": "456"},
            "session_binding": "a" * 64, "expires_in_ms": 35000}


HARNESS = r"""
const fs=require('fs'),input=JSON.parse(fs.readFileSync(0,'utf8'));
const events=[],calls=[],outputs=[],RealDate=Date;let offset=0,seq=100,reads=0;
global.Date=class extends RealDate{static now(){return RealDate.now()+offset;}};
global.crypto=require('crypto').webcrypto;
global.location={origin:'https://www.douyin.com'};global.document={title:'synthetic'};
const proto={AuthType:{PASSPORT_CERT_AUTH:3,SESSION_AUTH:1},ConversationType:{ONE_TO_ONE_CHAT:1},
  MessageStatus:{AVAILABLE:0},RequestBody:{create:body=>body},
  IMCMD:{CREATE_CONVERSATION_V2:609,SEND_MESSAGE:100,GET_MESSAGE_INFO_BY_SERVER_ID:211}};
const constants={rb:{Succeeded:3},HF:{sdkVersion:'1.1.3',buildNumber:'8aa2dcb:Detached: 8aa2dcb88b41538885168e4afbbd2b6bac8aefb2'},
  _:{PushOnly:1,All:2,Disable:3},P6:{Enable:0},v9:{ClientMessageId:'s:client_message_id',SendTime:'s:stime',MessageVisible:'s:visible',MessageInvisible:'s:invisible'}};
const conv={id:'0:1:9:456',shortId:'111',type:1,ticket:'synthetic-ticket',inboxType:0,isMember:true};
const rawConv={conversation_id:conv.id,conversation_short_id:conv.shortId,conversation_type:1,ticket:conv.ticket,inbox_type:0,is_participant:true};
const sdk={initResult:3,disposed:false,option:{appId:6383,apiUrl:'https://imapi.douyin.com',authType:3,
  securityScene:'web_protect',securityCertType:'cookie',cert:'synthetic-cert',webSocketLevel:1,userId:'9',timeCalibration:true},
  ctx:{plugin:[{receiveApiResponse:async value=>{events.push('decode-plugin:'+value.cmd);}}],resolve:()=>({avgDelta:0})},
  getConversationList:({filter})=>input.fresh?[]:[conv].filter(filter),
  updateSendMessageHeaders:headers=>{events.push('identity-headers');sdk.option.sendMessageHeader=headers;},
  createMessage:async value=>{if(value.insert!==false)throw Error('must not insert optimistic message');events.push('create-local-message');
    return {content:value.content,type:value.type,clientId:'synthetic-client',ext:{'s:client_message_id':'synthetic-client'}};},
  getMessageByServerId:()=>{throw Error('cached accessor forbidden');},
  init:()=>{throw Error('SDK init forbidden');},markConversationRead:()=>{throw Error('read marking forbidden');}
};
if(input.mode==='wrong_build')constants.HF.buildNumber='unaudited-build';
if(input.mode==='websocket_all')sdk.option.webSocketLevel=2;
if(input.mode==='not_ready')sdk.initResult=1;
if(input.mode==='legacy_session_auth')sdk.option.authType=1;
class Packet{
  constructor(ctx,cmd,body){this.cmd=cmd;this.body=body;this.seqId=++seq;
    this.url='https://imapi.douyin.com'+({609:'/v2/conversation/create',100:'/v1/message/send',211:'/v1/message/get_by_id'}[cmd]);}
  async prepareRequest(inbox){events.push('sdk-sign:'+this.cmd);
    this.request={cmd:this.cmd,body:this.body,sequence_id:this.seqId,headers:{...sdk.option.sendMessageHeader}};
    if(input.mode==='expire_during_sign')offset=40000;
  }
}
let lastCard;
sdk.http={encode:value=>value,decode:value=>value,instance:{request:async config=>{
  if(config.method!=='POST'||!config.signal||config.timeout>6000)throw Error('missing bounded native adapter');
  const request=config.data,cmd=request.cmd;calls.push(cmd);events.push('physical:'+cmd);
  if(input.saas&&(request.sdk_version!=='0.1.8'||request.build_number!=='0d50935:feat/pc-im-group'))throw Error('wrong SDK packet realm');
  const response={cmd,sequence_id:request.sequence_id,status_code:0,body:{}};
  if(cmd===609){
    if(input.mode==='lost_create')throw Error('lost');
    const value=request.body.create_conversation_v2_body;
    if(value.participants.join(',')!=='456,9'||value.conversation_type!==1)throw Error('recipient mismatch');
    response.body.create_conversation_v2_body={status:0,check_code:0,conversation:rawConv};
  }else if(cmd===100){
    if(input.mode==='lost_send')throw Error('lost');
    if(request.headers.identity_security_token!=='synthetic-identity'||request.headers.identity_security_aid!=='6383')throw Error('identity absent');
    const message=request.body.send_message_body;lastCard=JSON.parse(message.content);
    if(lastCard.content_thumb.uri!=='author-avatar'||lastCard.cover_url.uri!=='video-cover'||lastCard.itemId!=='123')throw Error('web card contract mismatch');
    if(!message.ext['s:stime'])throw Error('SDK calibrated timestamp missing');
    response.body.send_message_body={status:0,server_message_id:'222',client_message_id:input.mode==='wrong_client'?'another':'synthetic-client',
      check_code:input.mode==='moderation'?10502:input.mode==='rejected'?8610:8101};
  }else if(cmd===211){
    if(request.body.get_message_by_id_body.server_message_id!=='222')throw Error('wrong readback ID');
    const ext={'s:client_message_id':'synthetic-client'};
    if(input.mode==='recipient_hidden')ext['s:invisible']='456';
    const body={server_message_id:input.mode==='read_mismatch'?'223':'222',conversation_id:conv.id,
      conversation_short_id:conv.shortId,conversation_type:1,sender:'9',message_type:8,status:0,ext,
      content:JSON.stringify(lastCard)};
    response.body.get_message_by_id_body={msg_info:{status:0,body:input.mode==='cached_only'?null:body}};
  }else throw Error('unknown command');
  return {status:200,headers:{},data:response};
}}};
const modules={833027:{i:()=>sdk},592358:constants,963286:{m:proto},449919:{F:Packet},758951:{fromString:value=>value},
  752145:{Uk:{Monitor:'monitor'}},39278:{r:{fromServerConversation:()=>conv}},
  834268:{MZ:value=>{events.push('official-serialize');return value;}},487897:{pp:{SHARE_AWEME:8},c6:{AWEME_CARD:800}},
  173570:{ZP:{instance:()=>({getIdentitySecurityToken:async(options,retry)=>{
    if(options.scene!=='web_im'||retry!==false)throw Error('wrong identity context');events.push('identity-token');
    return {identity_security_param:'synthetic-identity',real_device_id:'synthetic-device'};
  }})}},
  416578:{k:{create:()=>({reportAction:()=>events.push('report-user-action')})}},
  656043:{s0:()=>({desc:'synthetic video',video:{height:1080,width:1920,coverUri:'video-cover',coverUrlList:['https://example.com/video']},
    authorInfo:{uid:'8',avatarThumb:{uri:'author-avatar',urlList:['https://example.com/avatar']}}})}};
const loader=id=>modules[id];loader.m=modules;
const axios=()=>{};axios.request=async config=>{
  if(config.method!=='GET')throw Error('ordinary account request must be GET');
  if(config.url.includes('/profile/self/'))return{status:200,data:{status_code:0,user:{uid:input.mode==='switch_account'&&reads++>0?'8':'9'}}};
  if(config.url.includes('/aweme/detail/'))return{status:200,data:{status_code:0,aweme_detail:{aweme_id:'123',aweme_type:0,status:{allow_share:true}}}};
  throw Error('unexpected ordinary endpoint');
};global.window={__mydvOfficialWebpack:loader,axiosInstance:axios};
if(input.saas){
 class SaasSdk{}
 Object.setPrototypeOf(sdk,SaasSdk.prototype);
 sdk.option.authType=input.mode==='saas_wrong_auth'?3:1;
 sdk.option.versionCode='360000';sdk.option.biz='douyin_web';
 delete sdk.option.cert;delete sdk.option.securityScene;delete sdk.option.securityCertType;
 sdk.updateSendMessageHeaders=headers=>{events.push('saas-identity-headers');sdk.option.sendMessageHeaders=headers;};
 class SaasPacket extends Packet{
   async prepareRequest(inbox){
     events.push('saas-sdk-sign:'+this.cmd);
     this.request={cmd:this.cmd,body:this.body,sequence_id:this.seqId,headers:{...sdk.option.sendMessageHeaders},
       sdk_version:'0.1.8',build_number:'0d50935:feat/pc-im-group'};
     if(input.mode==='expire_during_sign')offset=40000;
   }
 }
 const config={sdkVersion:'0.1.8',buildNumber:input.mode==='wrong_build'?'unaudited':'0d50935:feat/pc-im-group'};
 const remoteModules={70507:{B:SaasSdk},73642:{OH:constants.rb,PE:config,Wd:constants._,PC:constants.P6,LJ:constants.v9},
   25443:{c:proto},49205:{m:SaasPacket},84629:modules[758951],85849:{MM:{Monitor:'monitor'}},
   33095:{s:modules[39278].r},47888:{Ay:{instance:()=>({getIdentitySecurityToken:async(options,retry)=>{
     if(options.scene!=='web_im'||retry!==false)throw Error('wrong SaaS identity context');
     events.push('saas-identity-token');return{identity_security_param:'synthetic-identity',real_device_id:'synthetic-device'};
   }})}}};
 const remote=id=>remoteModules[id];remote.m=remoteModules;
 if(input.mode==='saas_missing_packet')delete remoteModules[49205];
 if(input.mode==='saas_wrong_instance')Object.setPrototypeOf(sdk,Object.prototype);
 const chunkIds=new Set(),chunks=[];chunks.push=value=>{
   if(value[0].some(id=>!chunkIds.has(id)))value[2](remote);
   value[0].forEach(id=>chunkIds.add(id));return 1;
 };
 window['@pc-im/im:1.0.0.946']=chunks;
 modules[906709]={Z:{newImContext:{mainOptions:{depends:{actionManager:{reportAction:()=>events.push('saas-report-user-action')}}},
   imSdkService:{imSdkManager:{getImSdkInstance:()=>sdk,useWebImSdkNext:input.mode==='next_kernel'}}}}};
 if(input.mode==='saas_missing_action_report')delete modules[906709].Z.newImContext.mainOptions.depends.actionManager.reportAction;
 // The main-site legacy holder is absent, exactly as observed in the current website.
 modules[833027]={i:()=>null};
}
(async()=>{
 if(input.probe){
   axios.request=()=>{throw Error('capability probe must not request account data');};
   const result=JSON.parse(await eval(input.template));
   process.stdout.write(JSON.stringify({result,calls,events}));return;
 }
 let phase='prepare',nonce=null,lastScript;
 for(let i=0;i<3;i++){
   lastScript=input.template.replace('__PHASE__',JSON.stringify(phase)).replace('__NONCE__',JSON.stringify(nonce));
   const result=JSON.parse(await eval(lastScript));outputs.push(result);
   if(!result.prepared)break;phase=result.phase;nonce=result.nonce;
 }
 if(input.repeat)outputs.push(JSON.parse(await eval(lastScript)));
 process.stdout.write(JSON.stringify({outputs,calls,events}));
})().catch(error=>{
 process.stderr.write(String(error?.stack||error)+'\n');
 process.exitCode=2;
});
"""


@unittest.skipUnless(shutil.which("node"), "CI Node runtime required")
class ShareScriptTests(unittest.TestCase):
    def run_fixture(self, payload):
        try:
            process = subprocess.run(["node", "-e", HARNESS], input=json.dumps(payload),
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, check=False)
        except subprocess.TimeoutExpired as error:
            detail = error.stderr or "(no stderr)"
            if isinstance(detail, bytes):
                detail = detail.decode("utf-8", errors="replace")
            self.fail("Synthetic Node fixture timed out:\n" + detail)
        self.assertEqual(process.returncode, 0,
                         "Synthetic Node fixture failed:\n" + (process.stderr or "(no stderr)"))
        return json.loads(process.stdout)

    def test_capability_probe_only_inspects_local_ready_sdk(self):
        for mode, expected in (("normal", True), ("not_ready", False), ("wrong_build", False)):
            with self.subTest(mode=mode):
                result = self.run_fixture({"template": share.PROBE, "mode": mode, "probe": True})
                self.assertIs(result["result"], expected)
                self.assertEqual(result["calls"], [])
                self.assertEqual(result["events"], [])

    def invoke(self, *, mode="normal", fresh=False, repeat=False, saas=False):
        deadline = str(int(time.time()*1000)+30000)
        template = (share.EXECUTE.replace("__JOB__", json.dumps(job()))
                    .replace("__DEADLINE__", deadline).replace("__PHASE_DEADLINE__", deadline))
        return self.run_fixture({"template": template, "mode": mode, "fresh": fresh, "repeat": repeat, "saas": saas})

    def test_saas_capability_uses_its_own_initialized_sdk_without_network(self):
        for mode, expected in (("normal", True), ("wrong_build", False), ("saas_wrong_auth", False),
                               ("saas_missing_packet", False), ("saas_wrong_instance", False),
                               ("saas_missing_action_report", False), ("next_kernel", False)):
            with self.subTest(mode=mode):
                result = self.run_fixture({"template": share.PROBE, "mode": mode, "probe": True, "saas": True})
                self.assertIs(result["result"], expected)
                self.assertEqual(result["calls"], [])
                self.assertEqual(result["events"], [])

    def test_saas_uses_remote_packet_and_plural_identity_headers(self):
        for fresh in (False, True):
            with self.subTest(fresh=fresh):
                result = self.invoke(saas=True, fresh=fresh, repeat=True)
                self.assertEqual(result["calls"], ([609] if fresh else [])+[100, 211])
                self.assertTrue(result["outputs"][-2]["ok"])
                self.assertFalse(result["outputs"][-1]["ok"])
                events = result["events"]
                self.assertLess(events.index("saas-report-user-action"), events.index("saas-identity-token"))
                self.assertLess(events.index("saas-identity-headers"), events.index("saas-sdk-sign:100"))
                self.assertNotIn("sdk-sign:100", events)
                self.assertNotIn("identity-token", events)

    def test_saas_failure_never_retries_or_uses_legacy_fallback(self):
        for mode in ("lost_send", "moderation", "rejected", "wrong_client", "read_mismatch", "cached_only", "recipient_hidden"):
            with self.subTest(mode=mode):
                result = self.invoke(saas=True, mode=mode)
                self.assertFalse(result["outputs"][-1]["ok"])
                self.assertEqual(result["calls"].count(100), 1)
                self.assertNotIn("sdk-sign:100", result["events"])
        self.assertEqual(self.invoke(saas=True, mode="lost_create", fresh=True)["calls"], [609])
        for mode in ("switch_account", "expire_during_sign"):
            with self.subTest(mode=mode):
                self.assertEqual(self.invoke(saas=True, mode=mode)["calls"], [])

    def test_existing_conversation_sends_once_and_reads_network(self):
        result = self.invoke()
        self.assertEqual(result["calls"], [100, 211])
        self.assertEqual(result["outputs"][-1], {"ok": True, "data": {"confirmed": True}, "error_code": ""})
        events = result["events"]
        self.assertLess(events.index("report-user-action"), events.index("identity-token"))
        self.assertLess(events.index("identity-headers"), events.index("sdk-sign:100"))
        self.assertLess(events.index("sdk-sign:100"), events.index("physical:100"))
        self.assertIn("decode-plugin:211", events)

    def test_new_conversation_is_created_once_and_correlated(self):
        result = self.invoke(fresh=True, repeat=True)
        self.assertEqual(result["calls"], [609, 100, 211])
        self.assertTrue(result["outputs"][-2]["ok"])
        self.assertFalse(result["outputs"][-1]["ok"])

    def test_lost_response_moderation_and_mismatch_never_retry(self):
        for mode in ("lost_send", "moderation", "rejected", "wrong_client", "read_mismatch", "cached_only", "recipient_hidden"):
            with self.subTest(mode=mode):
                result = self.invoke(mode=mode)
                self.assertFalse(result["outputs"][-1]["ok"])
                self.assertEqual(result["calls"].count(100), 1)
                self.assertEqual(result["calls"].count(609), 0)
                self.assertLessEqual(result["calls"].count(211), 1)
        result = self.invoke(mode="lost_create", fresh=True)
        self.assertEqual(result["calls"], [609])

    def test_unready_context_changed_account_or_expired_signing_never_send(self):
        for mode in ("not_ready", "wrong_build", "websocket_all", "switch_account", "expire_during_sign", "legacy_session_auth"):
            with self.subTest(mode=mode):
                result = self.invoke(mode=mode)
                self.assertFalse(result["outputs"][-1]["ok"])
                self.assertEqual(result["calls"], [])


class ShareBindingTests(unittest.TestCase):
    def test_failed_capability_probe_observes_same_backoff(self):
        web = Mock()
        web.native_cookies.return_value = [{"name": "sessionid", "value": "synthetic"}]
        web.evaluate.side_effect = actions.SyncError("unavailable")
        executor = share.ShareExecutor(web)
        with patch.object(share.time, "monotonic", return_value=100):
            self.assertFalse(executor.capability())
            self.assertFalse(executor.capability())
        web.evaluate.assert_called_once_with(share.PROBE)
        with patch.object(share.time, "monotonic", return_value=106):
            self.assertFalse(executor.capability())
        self.assertEqual(web.evaluate.call_count, 2)

    def test_account_change_after_signed_preparation_prevents_dispatch(self):
        web = Mock()
        web.session.return_value = {"value": json.dumps({"prepared": True, "phase": "send", "nonce": "0"*36})}
        reader = Mock(side_effect=["a"*64, "b"*64])
        result = share.ShareExecutor(web).execute(job(), time.monotonic()+35, reader)
        self.assertEqual(result["error_code"], "account_changed")
        web.session.assert_called_once()

    def test_lost_controller_response_is_never_retried(self):
        web = Mock()
        web.session.side_effect = actions.SyncError("unavailable")
        result = share.ShareExecutor(web).execute(job(), time.monotonic()+35, lambda: "a"*64)
        self.assertEqual(result["error_code"], "unconfirmed")
        web.session.assert_called_once()


if __name__ == "__main__":
    unittest.main()
