# 开发

本地只进行源码编辑、获授权的接口联调与 Git 操作。禁止本地编译、测试、模拟器及测试性质的检查命令。

检查范围：

- [Android checks](../.github/workflows/android.yml)：源码、签名兼容与 API 21 回归。
- [Companion checks](../.github/workflows/companion.yml)：检查电脑工具与协议合成用例。
- [Desktop checks and launchers](../.github/workflows/desktop.yml)：macOS、Windows 桌面启动与退出。

构建依赖固定 SHA-256 的基础 APK，不是完整 Gradle 工程。依赖与版本见 [build.py](../android/build.py)，签名要求见 [signing.py](../android/signing.py)。

测试结果对应具体提交；合成用例不能替代真实设备或业务确认。提交中注明实际执行的验证，保留现有协议、包名、签名及存储兼容性。敏感数据与本机配置不得入库，具体要求见 [AGENTS.md](../AGENTS.md)。
