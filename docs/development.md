# 开发

本地只进行源码编辑、获授权的接口联调与 Git 操作。禁止本地编译、测试、模拟器及测试性质的检查命令。

所有工作流仅手动触发：

- [Android checks](../.github/workflows/android.yml)：默认运行源码与合成检查；显式启用 `build_apk` 才编译、运行 API 21 回归并上传 Actions 产物。
- [Companion checks](../.github/workflows/companion.yml)：检查电脑工具与协议合成用例。

构建依赖固定 SHA-256 的基础 APK，不是完整 Gradle 工程。依赖与版本见 [build.py](../android/build.py)，签名要求见 [signing.py](../android/signing.py)。不提供 Release 安装包。

测试结果对应具体提交；合成用例不能替代真实设备或业务确认。提交中注明实际执行的验证，保留现有协议、包名、签名及存储兼容性。敏感数据与本机配置不得入库，具体要求见 [AGENTS.md](../AGENTS.md)。
