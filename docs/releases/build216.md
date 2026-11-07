# DSHA 诊断版 build 216

build216 缩短 Android 工作台就绪前的设备工具启动路径，并优化 Web 长对话的布局。基线见 [build215 验证记录](build215.md)。

- 正常 Android Web 启动可先打开工作台。首次创建或恢复智能体时等待手机操作和虚拟屏的 MCP 初始化，成功后注册工具。已有智能体时，重载阶段直接初始化工具。
- Web 对较早的消息按行跳过屏外布局。最后十二行和正在折叠的行按原方式测量。浏览器对照中，两版均挂载完整消息列表，早期消息可以滚回并操作。

## APK 身份

[下载 build216 APK](https://github.com/ansyn083-wq/DSHA-diagnosis-edition/releases/download/v1.0.0build216/DSHA-diagnostic-v1.0.0-build216-arm64.apk) · [build216 预发布页](https://github.com/ansyn083-wq/DSHA-diagnosis-edition/releases/tag/v1.0.0build216)

GitHub 附件的文件大小和 SHA-256 均与下方已验签的 APK 一致。发布标签 `v1.0.0build216` 对应[构建源码提交 `1cd25cf`](https://github.com/ansyn083-wq/DSHA-diagnosis-edition/commit/1cd25cff9b47acf9cf71c9e046a45abbd16081fb)。

| 字段 | 值 |
|---|---|
| 文件名 | `DSHA-diagnostic-v1.0.0-build216-arm64.apk` |
| 包名 | `dsh.android.client.diagnostics` |
| 版本 | `1.0.0` / `216` |
| 构建 | Standard Release，ARM64，最低 API 30 |
| 大小 | 294,449,101 字节 |
| SHA-256 | `b9d21d6f05fc9a99ad55d5740d8c584c6657ad0eaf92385d5c73f2b6a31d4088` |
| 签名证书 SHA-256 | `674f41e24ab38375c2519d3132a6ad30fcf6d49562d8c87c3e74a03c3dd9cf4f`（与 build215 一致） |

## 宿主性能基线

Web 使用两份 APK 实际打包的移动插件布局、锁定的 DSH 0.2.1-rc.2 前端和同一组 1,000 条合成 Markdown 消息。Chromium 145 以 360 px 起始宽度运行。两版各预热一轮，再交错测量各五轮。首次渲染从挂载请求计到两次动画帧回调，布局耗时来自改变视口宽度后读取布局的同步操作，滚动统计来自每轮 40 次连续动画帧。

| 指标 | build215 | build216 |
|---|---:|---:|
| Web 长对话首次渲染中位数 | 915.3 ms | 668.2 ms |
| Web 改变宽度后的布局耗时中位数 | 54.8 ms | 2.7 ms |
| Web 滚动中超过 25 ms 的帧间隔 | 57 / 200 | 0 / 200 |
| Linux 上 DSH 进程启动到鉴权后可读会话列表，中位数 | 1,631 ms | 1,529 ms |

DSH 测试在 Linux x86_64 和 Node 24 的独立空配置中执行，加载基础 Web 和 APK 中的两个设备插件。两版各预热一轮，再交错测量各五轮。build215 在就绪前启动这两类 MCP，build216 在该阶段均未启动。启动时间样本有重叠。测量覆盖宿主进程和本地 RPC。

原生消息页以 Robolectric API 34 复现。在载入 100 条历史消息后追加 1 条消息，旧列表 200 个子视图均被替换，四次重复结果一致。这是原生消息渲染的后续改造项。逐轮采样见 [基线数据](build216-benchmarks.json)，源码定位见 [`renderTranscript`](../../app/src/main/java/com/deepseekharness/app/ui/NativeChatPanel.java)。

## 核验范围

- [Web 渲染回归](../../tools/test-chat-render-performance.mjs)与[设备工具回归](../../tools/test-dsh-builtins.mjs)通过，覆盖旧消息可访问、工作台就绪前的工具延迟、首次智能体就绪与工具清理。
- Standard Release 构建及实际 APK 包名、版本、ARM64、16 KiB ZIP 对齐、签名、应急资源、第三方许可和 DSH 升级资产核验通过。
- 用户反馈已进行真机测试。此记录待补对应的真机耗时和测试细节，表中数字均为宿主测量。手机冷启动到可输入、真实模型首字时间和 Android 滚动帧数据待录入。

固定构建输入和工具链见[诊断版构建说明](../../diagnostic-build/README.md)。
