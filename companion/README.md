# 电脑同步工具

电脑保留官方抖音网页登录会话，为电视提供凭证同步和网页操作执行。

## 准备

需要 Python 3.10+，依赖见 [requirements-sync.txt](requirements-sync.txt)。从仓库根目录安装预编译依赖：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m pip install --only-binary=:all: --no-compile \
  --target .local-debug/python -r companion/requirements-sync.txt
export PYTHONPATH="$PWD/.local-debug/python${PYTHONPATH:+:$PYTHONPATH}"
python3 -B companion/agent_webview_sync.py login
```

在打开的官方网页与手机上完成登录。

## 配对与运行

电视打开“账号与登录 → 电脑同步”，保持页面在前台。将命令中的占位符替换为电视页面显示的地址：

```sh
python3 -B companion/agent_webview_sync.py start --ip '电视地址' --pair
python3 -B companion/agent_webview_sync.py status
```

核对两端六位数字一致后，在电视允许。后续启动省略 `--pair`，复用已保存的配对。

```sh
python3 -B companion/agent_webview_sync.py stop
```

`stop` 停止同步并保留浏览器会话。运行资料位于忽略目录 `.local-debug/`，不要提交或上传。电脑休眠、退出或官网要求验证时，需要恢复会话。

互动由用户在电视明确发起，不自动重发不确定的写入。分享能力就绪不代表真实送达已验证。所有测试仅在 GitHub Actions 执行，见[开发说明](../docs/development.md)。
