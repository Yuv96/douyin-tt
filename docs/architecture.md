# 架构

Android 客户端负责播放、界面和账号存储；电脑工具保留官方网页会话，执行凭证同步及电视发起的评论、互动请求。

1. 电脑和电视显示配对数字，用户核对后在电视允许。
2. 电脑加密发送当前凭证，电视验证账号后保存；失败保留现有凭证。
3. 电视将用户操作入队，电脑核对会话与能力后执行并返回结果。写入不自动重试，成功显示需要业务确认。

| 实现入口 | 职责 |
| --- | --- |
| [LanPairingSession.java](../android/src/com/dycomment/tv/LanPairingSession.java) | 配对握手与密钥交付 |
| [LanCredentialServer.java](../android/src/com/dycomment/tv/LanCredentialServer.java) | 加密接收；端口与大小限制见 `PORT`、`MAX_BODY` |
| [BrowserActionBroker.java](../android/src/com/dycomment/tv/BrowserActionBroker.java) | 队列与确认；期限、心跳和写入暂停见 `DEADLINE`、`HEARTBEAT`、`WRITE_COOLDOWN` |
| [agent_webview_sync.py](../companion/agent_webview_sync.py) | 官网会话、配对与周期同步 |
| [browser_session.py](../companion/browser_session.py) | Cookie 作用域、私有存储与控制器连接 |
| [browser_actions.py](../companion/browser_actions.py) | 网页执行与结果校验；防重放期限见 `SEEN_TTL_SECONDS` |
| [browser_share.py](../companion/browser_share.py) | 现有官网 IM SDK 的单次发送及网络回读 |

协议字段、认证上下文和限额以两端源码为准。分享能力探测仅表示 SDK 就绪，真实发送与送达尚未验证。电脑休眠、退出或官网要求验证时，依赖该会话的操作不可用。
