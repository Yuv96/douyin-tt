# Android 客户端

抖音抬头版支持 Android 5.0（API 21）及以上，使用 LibVLC 播放。

- `src/`：播放、界面、账号、配对与网络实现。
- `tests/`：合成与设备场景。
- `build.py`：基于固定哈希基础 APK 组装应用。
- `branding/`：图标矢量源文件。

签名必须符合 [signing.py](signing.py) 的固定证书，以保持覆盖安装兼容。

[架构](../docs/architecture.md) · [开发](../docs/development.md) · [第三方许可](../NOTICE)
