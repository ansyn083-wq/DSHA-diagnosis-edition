# DSHA 独立诊断版 2 · 修复与验证说明

## 诊断结论

真机日志证明 Web 进程、鉴权链接和进程身份捕获均成功；失败发生在加密恢复记录的
`ATOMIC_WRITE` 阶段。`AndroidBackupFileSystem.anchored()` 在系统提供的 noBackup 私有目录
路径中遇到 OEM/系统链接并以 `PARENT_LINK` 拒绝写入。由于 PID 记录存在而恢复记录缺失，
后续打开只能得到 `WEB_SESSION_RECORD_MISSING_FOR_EXISTING_PID`，因此要求先停止旧服务。

diag2 只对 `Context.getNoBackupFilesDir()` 返回的受信任私有目录入口调用
`getCanonicalFile()`，再交给原有的 no-follow 文件系统边界。通用备份文件层、进程身份、
鉴权、停止围栏和维护门禁均未放宽。

## 安装与验证

1. 直接覆盖安装 `dsha-diagnostics-2-arm64.apk`，不要卸载诊断版 1，数据会保留。
2. 诊断版 1 没能保存恢复记录，所以升级后的第一次启动可能仍需停止一次遗留服务。
3. 启动并进入对话，等待约十秒，然后退出应用并重新打开。
4. 正常结果是直接恢复/进入对话，不再要求停止旧服务。
5. 若仍异常，先点“导出诊断日志”，再做停止或重启，并将 tar.gz 发回。

不要同时运行原 DSHA 和诊断版的本地服务，因为两者仍使用同一桥端口。

## 已验证

- 定向单元测试 23 项通过，0 failure / 0 error / 0 skipped。
  - StoredWebSessionPrivateDirectoryTest：2 项（系统链接规范化与空目录拒绝）
  - WebSessionRecoveryTest：9 项
  - WebSessionProofTest：6 项
  - DiagnosticPackageTest：2 项
  - SessionDiagnosticTest：4 项
- `assembleStandardDebug` 完整构建成功；ZIP CRC 检查通过，无临时资源条目。
- APK v2 签名检查、16 KiB zipalign、包名、版本码和 ARM64/minSdk 身份检查通过。
- 签名证书与诊断版 1 相同，可直接覆盖安装。
- 尚未在目标 vivo/Android 16 真机验证恢复行为，因此这是有明确证据支持的修复候选，
  不是已经完成真机验收的结论。

## 应用身份

包名 `dsh.android.client.diagnostics`；名称“DSHA 诊断版”；版本
`0.2.1-diag2-dsh0.2.1-rc.2` / code 191；Android 11+；ARM64。

APK SHA256：`71af262767ddc794db3dfc7dbea80e55f8129fe17ac00c9d36ce52d71abfce86`

APK 字节数：`297045680`

签名证书 SHA256：`674f41e24ab38375c2519d3132a6ad30fcf6d49562d8c87c3e74a03c3dd9cf4f`
