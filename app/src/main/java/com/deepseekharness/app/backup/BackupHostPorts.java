package com.deepseekharness.app.backup;

/** 应用组合根向备份入口提供只读配置；备份不直接依赖宿主设置实现。 */
public interface BackupHostPorts {
  String configuredWorkdir();

  static BackupHostPorts fromOwner(Object owner) {
    if (!(owner instanceof BackupHostPorts))
      throw new IllegalStateException("BACKUP_HOST_OWNER_UNAVAILABLE");
    return (BackupHostPorts) owner;
  }
}
