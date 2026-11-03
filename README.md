# DSHA 诊断版

基于 DSHA 的 Android 应用。打开即可进入原生聊天首页，运行环境在后台准备。工作区、插件、终端、后台任务和诊断入口可从侧栏打开。

## 使用方法

1. 安装适合设备的诊断版 APK。发行入口为 [GitHub Releases](../../releases)，版本与校验信息见 [v1.0.0 说明](docs/releases/v1.0.0.md)。
2. 打开「账号与 API」，填写 DeepSeek API Key。模型列表统一显示 API 模型，点击模型名称可切换，模型名称下方的滑杆可调节该模型支持的思考强度。
3. 如果需要使用 DeepSeek 账号功能，可在同一页面单独登录。头像可作为设置入口，聊天请求使用配置的 API Key。

## 安装与适配

| 项目 | v1.0.0（构建 206） |
| --- | --- |
| 系统 | Android 11 及以上（最低 API 30） |
| 架构 | ARM64（`arm64-v8a`） |
| 界面 | 手机使用抽屉，宽屏可展开侧栏 |
| 安装包 | 可调试的诊断构建，应用 ID 为 `dsh.android.client.diagnostics` |

覆盖安装需要旧版诊断包的应用 ID 和签名一致，且旧版构建号不高于 206。诊断版使用独立应用 ID，可与使用其他应用 ID 的官方版本并存。

反馈问题时可以附上系统版本、设备架构、构建号和复现步骤。请先从截图或诊断日志中移除 API Key、登录信息及聊天内容。

## 鸣谢

- [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) 提供 Android 应用基础与运行环境集成。
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 提供对话和工具运行能力。

许可见 [本仓库 MIT 许可](LICENSE)、[DSHA 上游 MIT 许可](LICENSE.upstream)和[第三方组件声明](THIRD_PARTY_NOTICES.md)。
