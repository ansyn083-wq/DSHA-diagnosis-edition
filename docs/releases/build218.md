# DSHA 诊断版 build 218

本版修复原生聊天的思考入口交接、改为箭头展开，并复用已显示的消息正文、合并流式刷新请求。范围见[修订说明](../native-chat-reasoning-render-20261111.md)。保留 build217 的 Web 退出修复。没有改动模型接口或用户数据格式。

## 安装包

- 文件：`DSHA-diagnostic-v1.0.0-build218-arm64.apk`
- 包名：`dsh.android.client.diagnostics`；版本 `1.0.0` / `218`。
- Standard Release，非调试，Android 11+ / ARM64。
- 大小：294,576,332 字节。
- SHA-256：`14bb99110861f1b26f451ce5c79d4e2fe8ed41ec290d83015ca5ad1004bf8b60`。
- 证书 SHA-256：`674f41e24ab38375c2519d3132a6ad30fcf6d49562d8c87c3e74a03c3dd9cf4f`，沿用诊断版证书，v1/v2/v3 验签通过。预期支持同签名旧诊断版覆盖升级，本轮未做真机覆盖安装。

## 验证

从修订源码完整编译，使用 JDK17、Gradle9.3.1、SDK37、build-tools36.0.0、NDK26.3.11579264。固定资源与当前运行时配方一致，未复用旧 APK 的 dex。构建未签名 Release 后以诊断版原证书签名；不使用正式版 E7E3 签名身份。

APK 对应构建源码提交 `66ab9130cab3f6eb072e47aaa0cf545db93e0aed`。同步仓库时补齐 4 个新增 Java 文件的格式化登记，并按锁定工具整理 9 个文件的排版；工具确认所有 Java token 保持一致，运行时描述符不变。APK 字节和前述摘要保持原值，GitHub APK 附件待维护者上传。

- `:app:assembleStandardRelease` 通过；Release Lint Vital 无问题。
- `:app:testStandardDebugUnitTest` 选定 7 类共 52 项通过，零失败、错误或跳过：Transcript 14、RenderPlan 2、RenderQueue 2、Details 8、MessageActions 16、Markdown 8、MarkdownLink 2。
- 最终 APK 的包名、版本、最低系统、ABI、非调试标记、ZIP CRC、16 KiB ZIP 对齐及证书核验通过。
- 488 个 APK 资源逐字节摘要核对通过，包含 409 个生成运行时资源、依赖字体资源和编译后的 ART profile。
- 应急归档检查通过（22,232 文件）；第三方许可及 651 个 npm 包记录检查通过。
- ELF 核验通过：宿主 13、rootfs 723、Python 49、wheels 21、Python support 2、Ubuntu tools 106、DSH 17；无非 ARM64 ELF、16 KiB 不对齐或 RELRO 越界。

首次构建因下载通道及缺失的 GeckoView module 元数据失败。补齐的 module/AAR 与仓库既有摘要锁一致，未修改依赖校验规则；随后完整构建与测试成功。工具链 ZIP 的 NDK 符号链接已恢复，首次已缓存的少量 JNI 符号未被 strip，不影响已验证的架构或加载段对齐。

`verify-dsh-upgrade-apk.py` 因缺少独立 npm 安装的 raw 测试夹具未运行完成；没有将其记为通过。本轮运行时配方和归档未改变，构建门禁及最终资源逐字节检查已通过。没有新真机安装、真实模型输出或帧率测量，不声称所有手机卡顿已消除。

机器可读记录：[build218-verification.json](build218-verification.json)。
