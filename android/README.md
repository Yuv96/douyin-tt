# Android 客户端

抖音抬头版支持 Android 5.0（API 21）及以上，使用 LibVLC 播放。

- `src/`：播放、界面、账号、配对与网络实现。
- `tests/`：GitHub Actions 使用的合成与设备场景。
- `build.py`：基于固定哈希基础 APK 组装应用。
- `branding/`：图标矢量源文件。

本地禁止编译和测试。手动运行 [Android checks](../.github/workflows/android.yml)，默认只检查；显式启用 `build_apk` 才构建并生成 Actions 产物。

编译需要仓库 Secrets `ANDROID5_KEYSTORE_BASE64` 与 `ANDROID5_KEYSTORE_PASSWORD`。签名必须符合 [signing.py](signing.py) 的固定证书；缺失或不符时失败。

[架构](../docs/architecture.md) · [开发](../docs/development.md) · [第三方许可](../NOTICE)
