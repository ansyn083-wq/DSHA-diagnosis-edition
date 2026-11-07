# DSHA 诊断版

当前源码为 **v1.0.0 / build 216**，应用 ID 为 `dsh.android.client.diagnostics`，Android 11+ / ARM64。

[下载 build 215 APK](https://github.com/ansyn083-wq/DSHA-diagnosis-edition/releases/download/v1.0.0build215/DSHA-diagnostic-v1.0.0-build215-arm64.apk) · [发布页面](https://github.com/ansyn083-wq/DSHA-diagnosis-edition/releases/tag/v1.0.0build215)

已发布的 build215 APK 大小为 294,449,027 字节，SHA-256 为 `92e14c071776d1f6e0d651801a6129b64650597d457ea20953a20afa6e743656`。

- build216 延后手机操作和虚拟屏的 MCP 初始化，Web 长对话按消息行跳过屏外布局。
- 点击侧栏头像直接打开整合的账号与 API 窗口，不再打开通用设置；侧栏不再单独列出账号/API。
- 首次未配置用户在服务就绪后获得配置引导，可填写 API Key 或连接账户。已有配置和历史会话保留，通用设置仍可从侧栏进入。
- 保留问答交互、消息操作与问候语改进；工具执行结果卡片隐藏，但不隐藏问答和授权交互，不删除执行记录。

[构建与固定输入说明](diagnostic-build/README.md) · [build216 性能和 APK 核验](docs/releases/build216.md) · [build215 验证记录](docs/releases/build215.md) · [许可证](LICENSE) · [上游许可证](LICENSE.upstream) · [第三方声明](THIRD_PARTY_NOTICES.md)

本次提交提供应用源码、测试、构建工具和必要的小型第三方源码；大型离线输入按构建说明恢复。签名私钥、账号/API凭据、用户数据、原始设备日志和旧本地 Git 历史不包含在仓库中。请使用自己的签名材料构建；与已安装诊断版覆盖升级需要相同证书，仓库不提供原私钥。

build216 的宿主基线、针对性回归和签包核验见[本版记录](docs/releases/build216.md)。[旧 v1.0.0 Release](https://github.com/ansyn083-wq/DSHA-diagnosis-edition/releases/tag/v1.0.0) 对应历史 build207。

## 鸣谢

- [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) 提供 Android 应用基础与运行环境集成。
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 提供对话和工具运行能力。

许可见 [本仓库 MIT 许可](LICENSE)、[DSHA 上游 MIT 许可](LICENSE.upstream)和[第三方组件声明](THIRD_PARTY_NOTICES.md)。
