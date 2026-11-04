# DSHA GPT 诊断版 3

版本：0.2.1-diag3-dsh0.2.1-rc.2，versionCode 192。
包名：dsh.android.client.diagnostics，ARM64 / Android 11+。

本次包含 GPT 风格明暗配色、聊天首页与草稿输入、表单限宽、设置工具栏、插件和终端配色、轻量加载动画、尊重系统动画关闭设置的页面切换。首页检查更新后以提示提供入口；不自动安装官方包。启动沿用身份核验与显式停止围栏，自动进入对话只消费一次已鉴权事件。

APK：DSHA-GPT-diag3-arm64-final.apk
SHA256：fc0687316ac01c0dc3ad3a37e8a65eec900459e8c86db6c3bbf8c2586c982f11
证书 SHA256：674f41e24ab38375c2519d3132a6ad30fcf6d49562d8c87c3e74a03c3dd9cf4f

已验证：assembleStandardDebug 成功；APK v2 签名、zipalign 16KB、ZIP CRC、包名和版本检查通过；核心 rootfs 和 DSH 归档摘要与诊断版 2 相同；打包中间文件已移除，保留项逐字节比较通过。16 个选定 Android 回归、17 个入口策略及草稿桥接测试通过。

完整历史测试：2127 项执行，15 失败，1 跳过。10 项期望旧 MainActivity 而实际入口为 ChatHomeActivity；2 项被 JAVA_TOOL_OPTIONS 输出污染；其余涉及 TrialSupport、AdbWheelPaths 与终端异步超时。干净环境失败类补跑状态以交付补充记录为准。不能据此声称全套测试通过。

未完成真机安装、反复打开恢复旧服务、布局截图、启动耗时与帧率验收。不能据此保证手机上的卡住问题已消失。可覆盖同签名诊断版 2；官方 DSHA 与此包独立，不能互相覆盖，官方数据不自动迁入。建议先备份。

源码归档不含签名私钥、构建缓存、SDK/NDK/JDK 及大型离线原始输入；重建须按 diagnostic-build/README.md 补齐固定输入。构建后运行 strip-temporary-assets.py，重新 zipalign、签名并复验。
