# 电脑同步

需要 Python 3.10+（含 Tk）；Windows 另需 WebView2 Runtime。

1. 在 GitHub Actions 手动运行 **Desktop checks and launchers**，勾选 `package_launchers`，下载并解压产物。
2. macOS 双击 `抖音TT.app`，Windows 双击 `start_douyin_tt.bat`。启动器须与项目文件放在一起；首次联网准备依赖。
3. 电视开启“电脑同步”，在电脑填入电视 IP，点击“连接”。核对六位配对码，在电视允许；通过“登录网页”完成官方登录。

“停止”暂停同步并保留网页；“退出”关闭本工具的浏览器和同步进程。“解除配对”只清除本机配对，保留电视账号。不会开机自启，启动后也不会自动连接。

依赖在 `.runtime/`，会话在 `.local-debug/`，均不入库。电脑需保持运行；休眠或官网要求验证时需恢复会话。

命令行入口为 `companion/agent_webview_sync.py`，支持 `login`、`start --ip '电视地址' --pair`、`status` 和 `stop`。检查仅在 GitHub Actions 执行。
