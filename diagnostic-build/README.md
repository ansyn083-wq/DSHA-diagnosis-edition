# 独立诊断版构建

当前源码为 build216 / versionName 1.0.0。正常 Android Web 启动延后设备工具初始化，Web 长对话按消息行跳过屏外布局。见 [build216 性能和 APK 核验](../docs/releases/build216.md)与 [build215 验证记录](../docs/releases/build215.md)。

历史说明：build 211 的分发 APK 是在210底包上局部替换问候类，应用内编译常量可能仍显示210；本候选不沿用此局部替换打包方式。

基线 DSH-APP/DSHA：1bf282eb044f4082f58a60b64676419feb68a60d，应用 r3 聊天入口补丁及本目录所述独立诊断改动。本快照包含可分发源码与固定小型二进制，不包含大离线原始输入、Gradle缓存、用户数据或签名私钥。

包名 dsh.android.client.diagnostics；应用名 DSHA 诊断版；测试运行时 PROOT；Android 11+/ARM64。不能覆盖正式版，与正式版的数据目录、恢复记录、密钥与 Provider authority 独立。同包名、同证书且版本号递增的诊断版预期可覆盖升级；实际手机数据保留仍须验收，建议先备份。正式版与诊断版两个本地服务不建议同时运行，因为复用原桥端口；测试前先正常停止另一版服务，不卸载。

工具链：JDK17、SDK37、build-tools36.0.0、NDK26.3.11579264、Gradle9.3.1、Node24/npm11/Python3.9+。完整固定输入可从官方 Release 的 DSHA-0.2.1-rc.2-20261022.2100-source.zip 提取（SHA256 ef134fe8713bfa4d6cf3bdea883be1591246d4b78ace16bd924f6d739188425a），只提取 tools/ci-assets.py 的 allowed() 指定文件，不覆盖这里的改动源码。

诊断版使用优化后的 Ubuntu 输入：将该归档中的 `tools/recovery-runtime/archives/recovery-rootfs.bin` 同时放置为 `app/src/main/assets/offline-rootfs.bin`；两处必须匹配 SHA256 `b9fe8a0460bc0d0e96f1cec0d65d46c9ff71a10c6a8d05a390b732a81d30cef3`。不要用归档内正式版原始 `offline-rootfs.bin` 替换诊断版描述符，也不要修改固定摘要来绕过校验。其他输入仍按 allowed() 原路径提取。恢复后由 `tools/ci-assets.py` 的固定输入校验和正常 Gradle 构建门禁核对字节；完整离线输入打包可用 `python tools/ci-assets.py --pack /临时目录/locked-inputs.zip`。

先将独立私钥压缩包解到本机私有目录（含 dsha-diagnostics.p12、credentials.txt），再执行：

```
python tools/prepare-backup-assets.py --write
python tools/prepare-runtime-descriptor.py --write
python tools/prepare-test-runtime.py
python diagnostic-build/build-diagnostics.py /私钥目录 :app:testStandardDebugUnitTest --tests '*SessionDiagnosticTest' --tests '*WebSessionRecoveryTest'
python diagnostic-build/build-diagnostics.py /私钥目录 :app:assembleStandardDebug --max-workers=1 --no-watch-fs
```

Windows 可配置 SDK local.properties sdk.dir 后同样使用 python；项目 build.sh 适用于 Linux，Windows helper 走 gradlew.bat。网络代理由构建者本机配置。

新增日志只输出固定阶段、错误类别/白名单代码及应用代码位置，不记录恢复令牌、URL、密文、密钥或会话正文。diag2 根据真机日志修复了系统私有目录入口含链接时恢复记录无法落盘的问题；没有放松通用文件系统、进程出生身份、鉴权和停止围栏。目标真机仍需按 VERIFICATION.md 完成验证。
