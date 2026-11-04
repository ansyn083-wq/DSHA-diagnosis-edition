package com.deepseekharness.app.core;

import android.content.Context;
import android.util.Log;
import java.io.IOException;

/** 打包文本资产的读取、换行规范化和脱敏诊断，不依赖 Web 生命周期。 */
public final class PackagedAssetReader {
  private PackagedAssetReader() {}

  /** Reads packaged text, closes the source, and normalizes only its line endings. */
  public static String read(Context context, String name) {
    try {
      return readText(context.getAssets().open(name));
    } catch (Exception e) {
      String reason =
          com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e.getMessage()));
      if (reason.length() > 200) reason = reason.substring(0, 200);
      Log.w(
          "DSHA",
          "Packaged asset read failed: "
              + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(name))
              + " ("
              + e.getClass().getSimpleName()
              + ": "
              + reason
              + ")");
      return "";
    }
  }

  static String readText(java.io.InputStream source) throws IOException {
    try (java.io.InputStream in = source;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      // 资产在 Windows 检出时可能是 CRLF，注入容器后脚本认不了 \r → 统一转 LF
      return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
    }
  }
}
